package starlogue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import starlogue.api.StarlogueAPI;
import starlogue.engine.ConstraintEngine;
import starlogue.personality.FactionProfileRegistry;
import org.apache.log4j.Logger;

public class StarlogueModPlugin extends BaseModPlugin {
    private static final Logger log = Logger.getLogger(StarlogueModPlugin.class);

    @Override
    public void onApplicationLoad() throws Exception {
        FactionProfileRegistry.load();
        StarlogueAPI.markLoaded();

        if (Global.getSettings().getModManager().isModEnabled("starlords")) {
            StarlogueAPI.registerPlugin(new starlogue.starlords.StarLordPlugin());
            StarlogueAPI.registerPersonalityModifier(new starlogue.starlords.StarLordPersonalityModifier());
            log.info("Starlogue: Star Lords detected — StarLordPlugin registered");
        }

        log.info("Starlogue v0.1.0 loaded");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Inject "Open a channel..." option whenever the player opens an interaction dialog.
        // The option is handled by rules.csv → OpenChannelCommand → StarlogueDialogPlugin.
        Global.getSector().addListener(new BaseCampaignEventListener(false) {
            @Override
            public void reportShownInteractionDialog(InteractionDialogAPI dialog) {
                if (dialog == null || dialog.getInteractionTarget() == null) return;
                if (!ConstraintEngine.canEngage(dialog.getInteractionTarget())) return;
                dialog.getOptionPanel().addOption("Open a channel...", "starlogue_open");
            }
        });
        log.info("Starlogue: game listener registered");
    }
}
