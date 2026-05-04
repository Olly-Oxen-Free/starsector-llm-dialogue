package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes a durable string on the player fleet for later rules / callbacks. */
public class IntelMarkMemoryAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(IntelMarkMemoryAction.class);
    private static final String PREFIX = "$starlogue_intel_";
    private String lastNote;

    @Override public String getId() { return "intel_mark_memory"; }

    @Override
    public String getDescription() {
        return "Mark durable player-fleet memory: key_suffix (string, alphanumeric), value_text (string), "
            + "ttl_days (number, optional).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("key_suffix", "string");
        p.put("value_text", "string");
        p.put("ttl_days", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.memoryScore < 5f) return false;
        if (ctx.repLevel.isAtBest(RepLevel.HOSTILE)) return false;
        return Global.getSector() != null && Global.getSector().getPlayerFleet() != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (pf == null) return;
        String suf = sanitize(String.valueOf(args.getOrDefault("key_suffix", "note")));
        String val = String.valueOf(args.getOrDefault("value_text", ""));
        float ttl = 180f;
        Object t = args.get("ttl_days");
        if (t instanceof Number) ttl = Math.max(1f, ((Number) t).floatValue());
        MemoryAPI mem = pf.getMemory();
        mem.set(PREFIX + suf, val, ttl);
        lastNote = "Intel bookmark stored on your fleet log.";
        log.debug("Starlogue: intel_mark_memory " + suf);
    }

    private static String sanitize(String s) {
        if (s == null) return "note";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
