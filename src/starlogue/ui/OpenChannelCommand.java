package starlogue.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import java.util.List;
import java.util.Map;

/**
 * rules.csv script: handles "starlogue_open" option selection.
 * Swaps the current dialog plugin for StarlogueDialogPlugin.
 */
public class OpenChannelCommand extends BaseCommandPlugin {

    private static final Logger log = Logger.getLogger(OpenChannelCommand.class);

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog,
                           List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) return false;

        SectorEntityToken target = dialog.getInteractionTarget();
        if (target == null) {
            log.warn("Starlogue: OpenChannelCommand called with null interaction target");
            return false;
        }

        StarlogueDialogPlugin plugin = new StarlogueDialogPlugin(target);
        dialog.setPlugin(plugin);
        plugin.init(dialog);
        return true;
    }
}
