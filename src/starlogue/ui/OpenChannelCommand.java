package starlogue.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import starlogue.debug.DebugSessionLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * rules.csv script: handles "starlogue_open" option selection.
 * Captures a snapshot of the pre-swap dialog state (plugin + options) so
 * {@link StarlogueDialogPlugin} can restore it when the player ends the
 * conversation without having made any game-state-changing tool call.
 */
public class OpenChannelCommand extends BaseCommandPlugin {

    private static final Logger log = Logger.getLogger(OpenChannelCommand.class);

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog,
                           List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) return false;

        memoryMap = RuleMemoryHelper.mergeWithRuleBasedDialog(memoryMap, dialog);

        SectorEntityToken target = InteractionTargetResolver.resolve(dialog);
        if (target == null) {
            // #region agent log
            try {
                DebugSessionLog.log("H_NULL_TARGET", "OpenChannelCommand.execute", "null after resolve", "{}");
            } catch (Throwable ignore) { }
            // #endregion
            log.warn("Starlogue: OpenChannelCommand called with null interaction target (after fleet fallback)");
            return false;
        }

        InteractionDialogPlugin priorPlugin = null;
        List<Object> priorOptions = null;
        try {
            priorPlugin = dialog.getPlugin();
        } catch (Throwable ignored) { }
        try {
            OptionPanelAPI op = dialog.getOptionPanel();
            if (op != null) {
                // Defensive copy — getSavedOptionList typically returns the live list,
                // which we don't want to mutate or lose when the Starlogue plugin manages options.
                List<?> live = op.getSavedOptionList();
                if (live != null) priorOptions = new ArrayList<Object>(live);
            }
        } catch (Throwable ignored) { }

        StarlogueDialogPlugin plugin = new StarlogueDialogPlugin(target, memoryMap, priorPlugin, priorOptions);
        dialog.setPlugin(plugin);
        plugin.init(dialog);
        return true;
    }
}
