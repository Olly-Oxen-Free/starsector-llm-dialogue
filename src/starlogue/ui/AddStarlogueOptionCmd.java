package starlogue.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import starlogue.engine.ConstraintEngine;
import starlogue.provider.StarloguePlugin;
import org.apache.log4j.Logger;
import java.util.List;
import java.util.Map;

/**
 * rules.csv script: fires on every {@code PopulateOptions} wave. Asks the
 * {@link ConstraintEngine} which Starlogue plugin (if any) claims this
 * interaction target, and if one does, adds the "Open a channel..." (or
 * context-appropriate) option with id {@code starlogue_open}.
 *
 * <p>This is what makes the option survive rules-driven dialogs, since
 * {@code RuleBasedInteractionDialogPluginImpl} clears and rebuilds options
 * on every greeting / option click.
 */
public class AddStarlogueOptionCmd extends BaseCommandPlugin {

    private static final Logger log = Logger.getLogger(AddStarlogueOptionCmd.class);
    private static final String OPTION_ID = "starlogue_open";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog,
                           List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) return false;
        SectorEntityToken target = dialog.getInteractionTarget();
        if (target == null) return false;

        StarloguePlugin plugin;
        try {
            plugin = ConstraintEngine.getPlugin(target, memoryMap);
        } catch (Exception e) {
            log.warn("Starlogue: plugin lookup threw during PopulateOptions", e);
            return false;
        }
        if (plugin == null) return false;

        OptionPanelAPI options = dialog.getOptionPanel();
        if (options == null) return false;

        // Duplicate-add guard: if the option is already present (e.g. the
        // reportShownInteractionDialog listener added it), don't stack it.
        try {
            if (options.hasOption(OPTION_ID)) return false;
        } catch (NoSuchMethodError e) {
            // Older API — fall through and risk a cosmetic duplicate.
        } catch (Throwable t) {
            // Defensive — never let a missing API break option population for other rules.
        }

        String label;
        try {
            label = plugin.getOptionLabel(target, memoryMap);
        } catch (Exception e) {
            label = "Open a channel...";
        }
        if (label == null || label.isEmpty()) label = "Open a channel...";

        options.addOption(label, OPTION_ID);
        return true;
    }
}
