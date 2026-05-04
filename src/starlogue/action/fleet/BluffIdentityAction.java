package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a player-claimed false-flag identity on the player fleet memory for later callbacks.
 * Available with transponder off; bluff-only listing when transponder is on.
 */
public class BluffIdentityAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(BluffIdentityAction.class);
    public static final String MEM_KEY = "$starlogue_bluff_claim";
    private String lastNote;

    @Override public String getId() { return "bluff_identity"; }

    @Override
    public String getDescription() {
        return "Player commits to a false-flag cover: claimed_faction_id (string), cover_story (string). "
            + "Only meaningful when the player's transponder is OFF.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("claimed_faction_id", "string");
        p.put("cover_story", "string");
        return p;
    }

    @Override
    public boolean isBluffable() {
        return true;
    }

    @Override
    public boolean isAvailable(GameContext ctx) {
        CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (pf == null) return false;
        try {
            return !pf.isTransponderOn();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (pf == null) return;
        String fid = String.valueOf(args.getOrDefault("claimed_faction_id", ""));
        String story = String.valueOf(args.getOrDefault("cover_story", ""));
        MemoryAPI mem = pf.getMemory();
        mem.set(MEM_KEY, "faction=" + fid + " | " + story, 120f);
        lastNote = "You lean into the cover story.";
        log.info("Starlogue: bluff_identity recorded for player fleet");
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
