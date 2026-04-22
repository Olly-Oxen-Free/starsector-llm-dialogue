package starlogue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import starlogue.api.StarlogueAPI;
import starlogue.engine.ConstraintEngine;
import starlogue.personality.FactionProfileRegistry;
import starlogue.provider.StarloguePlugin;
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
        // Inject the Starlogue option whenever the player opens an interaction dialog.
        // This handles fleet encounters and non-rules-driven custom plugin dialogs.
        // Rules-driven dialogs (contacts, markets, bar NPCs) are covered by rules.csv →
        // AddStarlogueOptionCmd, which re-adds the option on every PopulateOptions wave.
        Global.getSector().addListener(new BaseCampaignEventListener(false) {
            @Override
            public void reportShownInteractionDialog(InteractionDialogAPI dialog) {
                if (dialog == null) return;
                SectorEntityToken target = dialog.getInteractionTarget();
                if (target == null) return;
                StarloguePlugin plugin = ConstraintEngine.getPlugin(target, null);
                if (plugin == null) return;

                OptionPanelAPI options = dialog.getOptionPanel();
                if (options == null) return;

                try {
                    if (options.hasOption("starlogue_open")) return;
                } catch (Throwable t) {
                    // older API without hasOption — accept a cosmetic duplicate risk
                }

                String label;
                try {
                    label = plugin.getOptionLabel(target, null);
                } catch (Exception e) {
                    label = "Open a channel...";
                }
                if (label == null || label.isEmpty()) label = "Open a channel...";
                options.addOption(label, "starlogue_open");
            }
        });
        log.info("Starlogue: game listener registered");
    }
}
