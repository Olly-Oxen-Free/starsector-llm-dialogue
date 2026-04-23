package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Structured intel tip (headline + body) with optional severity hint. */
public class IntelShareTipAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(IntelShareTipAction.class);
    private String lastNote;

    @Override public String getId() { return "intel_share_tip"; }

    @Override
    public String getDescription() {
        return "Share a structured intel tip: headline (string), body (string), severity (number, optional 1-5).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("headline", "string");
        p.put("body", "string");
        p.put("severity", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.person == null) return false;
        return ctx.memoryScore > -10f
            && (ctx.repLevel == null || !ctx.repLevel.isAtBest(RepLevel.HOSTILE));
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.person == null) return;
        String h = String.valueOf(args.getOrDefault("headline", "Tip"));
        String b = String.valueOf(args.getOrDefault("body", ""));
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SHARED_INTEL, 1.0f);
        lastNote = h + ": " + b;
        log.debug("Starlogue: intel_share_tip");
    }

    @Override
    public String narrativeNote() {
        return lastNote != null ? lastNote : "Intel noted.";
    }
}
