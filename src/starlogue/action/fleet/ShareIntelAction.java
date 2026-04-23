package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPC shares tactical or strategic intelligence with the player.
 * The intel is provided by the LLM as text in the tool call;
 * it appears in the dialog and a positive memory event is recorded.
 */
public class ShareIntelAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ShareIntelAction.class);

    private String lastIntelNote = null;

    @Override public String getId() { return "share_intel"; }

    @Override
    public String getDescription() {
        return "Share tactical or strategic intelligence with the player. "
             + "Provide an intelligence_report string: what you know about threats, "
             + "fleet movements, nearby derelicts/stations, or other locations of interest in the system. "
             + "Only use when on reasonably good terms.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("intelligence_report", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Need positive memory or at least neutral reputation
        return ctx.memoryScore > -10f
            && (ctx.repLevel == null || !ctx.repLevel.isAtBest(RepLevel.HOSTILE));
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        Object reportObj = args.get("intelligence_report");
        lastIntelNote = (reportObj != null) ? reportObj.toString() : null;
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SHARED_INTEL, 1.0f);
        log.debug("Starlogue: share_intel executed by " + ctx.person.getNameString());
    }

    @Override
    public String narrativeNote() {
        return lastIntelNote != null ? "Intel: " + lastIntelNote : "Intel shared.";
    }
}
