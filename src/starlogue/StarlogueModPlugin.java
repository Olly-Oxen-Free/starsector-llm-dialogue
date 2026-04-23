package starlogue;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import starlogue.api.StarlogueAPI;
import starlogue.compat.nex.NexStarlogueCompat;
import starlogue.config.StarlogueCredentials;
import starlogue.personality.CharacterProfileRegistry;
import starlogue.personality.FactionProfileRegistry;
import starlogue.ui.StarlogueOptionEnforcerScript;
import org.apache.log4j.Logger;

public class StarlogueModPlugin extends BaseModPlugin {
    private static final Logger log = Logger.getLogger(StarlogueModPlugin.class);
    private static final String BUILD_MARKER = "cred-debug-2026-04-23-2";

    @Override
    public void onApplicationLoad() throws Exception {
        StarlogueCredentials.ensureDefaultFileInCommon();
        FactionProfileRegistry.load();
        CharacterProfileRegistry.load();
        StarlogueAPI.markLoaded();

        if (Global.getSettings().getModManager().isModEnabled("starlords")) {
            StarlogueAPI.registerPlugin(new starlogue.starlords.StarLordPlugin());
            StarlogueAPI.registerPersonalityModifier(new starlogue.starlords.StarLordPersonalityModifier());
            log.info("Starlogue: Star Lords detected — StarLordPlugin registered");
        }

        if (Global.getSettings().getModManager().isModEnabled("nexerelin")) {
            StarlogueAPI.registerActionContributor(new NexStarlogueCompat());
            log.info("Starlogue: Nexerelin detected — soft compat tools registered");
        }

        log.info("Starlogue v0.1.0 loaded [" + BUILD_MARKER + "]");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // rules.csv (PopulateOptions → AddStarlogueOptionCmd) covers most screens.
        // Some UIs (e.g. portside bar) never run PopulateOptions — add a throttled
        // fallback that reuses the same insert logic (not reportShownInteractionDialog;
        // see StarlogueOptionEnforcerScript) and skip refit-class plugins.
        if (Global.getSector() != null) {
            Global.getSector().addScript(new StarlogueOptionEnforcerScript());
        }
    }
}
