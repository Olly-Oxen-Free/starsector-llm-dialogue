package starlogue.ui;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import starlogue.engine.ConstraintEngine;
import starlogue.provider.StarloguePlugin;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import starlogue.debug.DebugSessionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rules.csv script: fires on every {@code PopulateOptions} wave. Asks the
 * {@link ConstraintEngine} which Starlogue plugin (if any) claims this
 * interaction target, and if one does, inserts the Starlogue option
 * as the FIRST option in the menu (regardless of when this rule runs
 * relative to the other PopulateOptions rules).
 *
 * <p>Positioning pattern borrowed from the Second-in-Command mod's
 * "Immediate Action" skill ({@code second_in_command.skills.scavenging.ImmediateAction}):
 * call {@code addOption} first (which appends), then read back the current
 * option list via {@code getSavedOptionList()}, rebuild it in the desired
 * order, and push it back with {@code restoreSavedOptions(...)}. Critically,
 * this pattern never calls {@code clearOptions()} — which was the fragile
 * step that previously wiped all options in some dialog states.
 */
public class AddStarlogueOptionCmd extends BaseCommandPlugin {

    private static final Logger log = Logger.getLogger(AddStarlogueOptionCmd.class);
    private static final String DEFAULT_OPTION_ID = "starlogue_open";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog,
                           List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        return tryInsert(dialog, memoryMap, true);
    }

    /**
     * Re-run the same insert logic with no {@code memoryMap} from a rules-callback. Needed for
     * dialog types that do not go through the campaign {@code PopulateOptions} wave
     * (e.g. {@code com.fs.starfarer.api.impl.campaign.intel.bar.BarEventDialogPlugin}).
     */
    public static void injectForBarOrNonRuleDialog(InteractionDialogAPI dialog) {
        tryInsert(dialog, null, false);
    }

    /**
     * @param fromRules when {@code true}, log a PopulateOptions line on success; when false
     *                  (UI enforcer) use a separate log tag
     */
    private static boolean tryInsert(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, boolean fromRules) {
        if (dialog == null) return false;
        if (dialog.getPlugin() instanceof StarlogueDialogPlugin) return false;

        // #region agent log
        try {
            String pc = "null";
            try {
                if (dialog.getPlugin() != null) pc = dialog.getPlugin().getClass().getName();
            } catch (Throwable t) { }
            JSONObject o = new JSONObject();
            o.put("fromRules", fromRules);
            o.put("dialogPlugin", pc);
            DebugSessionLog.log("H_OPT", "AddStarlogueOptionCmd.tryInsert", "enter", o.toString());
        } catch (Throwable ignore) { }
        // #endregion

        SectorEntityToken target = InteractionTargetResolver.resolve(dialog);
        if (target == null) {
            // #region agent log
            try {
                JSONObject o = new JSONObject();
                o.put("fromRules", fromRules);
                o.put("reason", "target_null_after_resolve");
                DebugSessionLog.log("H_OPT", "AddStarlogueOptionCmd.tryInsert", "skip", o.toString());
            } catch (Throwable ignore) { }
            // #endregion
            return false;
        }
        // #region agent log
        try {
            JSONObject o2 = new JSONObject();
            o2.put("class", target.getClass().getName());
            o2.put("fromRules", fromRules);
            DebugSessionLog.log("H_OPT", "AddStarlogueOptionCmd.tryInsert", "target", o2.toString());
        } catch (Throwable ignore) { }
        // #endregion
        if (target instanceof CampaignFleetAPI && ((CampaignFleetAPI) target).isPlayerFleet()) {
            return false;
        }

        Map<String, MemoryAPI> mem = RuleMemoryHelper.mergeWithRuleBasedDialog(memoryMap, dialog);

        StarloguePlugin plugin;
        try {
            plugin = ConstraintEngine.getPlugin(target, mem);
        } catch (Exception e) {
            log.warn("Starlogue: plugin lookup threw during option insert", e);
            return false;
        }
        if (plugin == null) return false;

        OptionPanelAPI options = dialog.getOptionPanel();
        if (options == null) return false;

        String optionId;
        try {
            optionId = plugin.getOptionId(target, mem);
        } catch (Exception e) {
            optionId = DEFAULT_OPTION_ID;
        }
        if (optionId == null || optionId.isEmpty()) optionId = DEFAULT_OPTION_ID;

        try {
            if (options.hasOption(optionId)) return false;
        } catch (Throwable ignored) { }

        String label;
        try {
            label = plugin.getOptionLabel(target, mem);
        } catch (Exception e) {
            label = "Open a channel...";
        }
        if (label == null || label.isEmpty()) label = "Open a channel...";

        insertOptionFirst(options, label, optionId);
        if (fromRules) {
            log.info("[Starlogue/PopulateOptions] inserted option \"" + label + "\" id=" + optionId + " at position 0");
        } else {
            log.info("[Starlogue/option-enforcer] inserted option \"" + label + "\" id=" + optionId + " at position 0");
        }
        return true;
    }

    /**
     * Append the option, then reorder so it sits at index 0 — using the
     * SiC/ImmediateAction pattern (add → getSavedOptionList → rebuild → restore).
     * Never calls {@code clearOptions()} so the existing options are always preserved.
     */
    public static void insertOptionFirst(OptionPanelAPI options, String label, String optionId) {
        // 1. Append our option (it lands at the end).
        options.addOption(label, optionId);

        // 2. Pull the live option list back — on a working API this now includes our option as the last entry.
        List<?> current;
        try {
            current = options.getSavedOptionList();
        } catch (Throwable t) {
            // Reorder API missing — option is added at the end, which is still visible to the player.
            log.warn("Starlogue: getSavedOptionList unavailable; option appended at end instead of top", t);
            return;
        }
        if (current == null || current.size() <= 1) return; // nothing to reorder

        // 3. Rebuild: our option (the last one) first, then the original prefix in order.
        List<Object> reordered = new ArrayList<Object>(current.size());
        int lastIdx = current.size() - 1;
        reordered.add(current.get(lastIdx));
        for (int i = 0; i < lastIdx; i++) {
            reordered.add(current.get(i));
        }

        // 4. Push the reordered list back.
        try {
            options.restoreSavedOptions(reordered);
        } catch (Throwable t) {
            log.warn("Starlogue: restoreSavedOptions threw during reorder; option will stay at end", t);
        }
    }
}
