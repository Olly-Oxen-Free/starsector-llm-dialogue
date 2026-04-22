package starlogue.provider;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Last-priority fallback. Accepts any entity and synthesises a "system AI" persona
 * (automated salvage transponder, derelict ship VI, buoy comms relay, etc.) keyed off
 * the entity's tags. No PersonAPI is created — {@link GameContext#person} is left null
 * and {@code ctx.speakerName} carries a generated callsign.
 */
public class SystemAIPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        return entity != null;
    }

    @Override
    public boolean canEngage(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return entity != null;
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        return buildContext(entity, null);
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        FactionAPI entityFaction = entity.getFaction() != null
            ? entity.getFaction()
            : Global.getSector().getFaction(Factions.NEUTRAL);
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();

        GameContext ctx = new GameContext();
        ctx.entity = entity;
        ctx.person = null;
        ctx.speakerName = deriveSpeakerName(entity);
        ctx.fleet = null;
        ctx.npcFaction = entityFaction;
        ctx.playerFaction = playerFaction;

        // No meaningful rep for an automated transponder — default to NEUTRAL.
        if (entityFaction != null && playerFaction != null) {
            ctx.repLevel = entityFaction.getRelationshipLevel(playerFaction.getId());
            ctx.repValue = entityFaction.getRelationship(playerFaction.getId());
        } else {
            ctx.repLevel = RepLevel.NEUTRAL;
            ctx.repValue = 0f;
        }
        ctx.individualRel = ctx.repValue;

        ctx.npcFleetPoints = 0;
        ctx.playerFleetPoints = 0;
        ctx.strengthDelta = 0f;
        ctx.memoryScore = 0f;
        ctx.playerHasBluffCredibility = true;

        ctx.set("starlogue.systemAIKind", classifyEntity(entity));
        return ctx;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        // Pure conversational surface — no mechanical actions. The LLM just talks.
        return new ArrayList<StarlogueAction>();
    }

    @Override
    public String getSystemPromptPreamble(GameContext ctx) {
        String kind = ctx.getString("starlogue.systemAIKind", "unknown_signal");
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM CHANNEL (automated):\n");
        sb.append("- You are not a person. You are a Domain-era or post-Domain automated ");
        sb.append("subsystem responding to an incoming comm ping. You have a narrow scope, ");
        sb.append("limited situational awareness, and no personal agenda. Your voice is ");
        sb.append("clipped, procedural, slightly degraded.\n");

        if ("comm_relay".equals(kind)) {
            sb.append("- Subsystem type: comm relay. You can relay standard channel queries, ")
              .append("sector-wide bulletins, and report signal integrity. You have no opinion.\n");
        } else if ("gate".equals(kind)) {
            sb.append("- Subsystem type: Domain-era gate terminal. You speak only in confirmation ")
              .append("codes, throughput metrics, and dormant-protocol acknowledgements. You are ")
              .append("unsettlingly old; you remember a functional gate network you cannot restore.\n");
        } else if ("station".equals(kind)) {
            sb.append("- Subsystem type: unmanned station docking-control VI. You handle berthing, ")
              .append("lock status, and hazard warnings. You refuse anything outside that scope.\n");
        } else if ("derelict".equals(kind) || "salvageable".equals(kind)) {
            sb.append("- Subsystem type: derelict onboard VI. You are damaged. Your log entries are ")
              .append("fragmentary. You answer with partial records, corrupted time stamps, and ")
              .append("occasional nonsense words that may or may not be meaningful.\n");
        } else if ("warning_beacon".equals(kind)) {
            sb.append("- Subsystem type: warning beacon. You repeat and contextualise a hazard ")
              .append("advisory. You answer clarifying questions about the hazard only.\n");
        } else if ("stable_location".equals(kind)) {
            sb.append("- Subsystem type: stable-location survey buoy. You report orbital parameters, ")
              .append("deployment history, and nearby construction potential. Terse.\n");
        } else {
            sb.append("- Subsystem type: unidentified automated signal. You answer what you can ")
              .append("from a limited internal log. Decline anything outside scope.\n");
        }

        sb.append("- Do not roleplay as a human. Do not invent personal history. Never claim ")
          .append("capabilities you were not given. When asked something out of scope, respond ")
          .append("with a brief refusal citing your subsystem limits.");
        return sb.toString();
    }

    @Override
    public String getOptionLabel(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return "Query the system...";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String classifyEntity(SectorEntityToken entity) {
        if (entity == null) return "unknown_signal";
        if (hasTag(entity, Tags.COMM_RELAY)) return "comm_relay";
        if (hasTag(entity, Tags.GATE)) return "gate";
        if (hasTag(entity, Tags.STATION)) return "station";
        if (hasTag(entity, Tags.SALVAGEABLE)) return "salvageable";
        if (hasTag(entity, "derelict")) return "derelict";
        if (hasTag(entity, Tags.WARNING_BEACON)) return "warning_beacon";
        if (hasTag(entity, Tags.STABLE_LOCATION)) return "stable_location";
        return "unknown_signal";
    }

    private static String deriveSpeakerName(SectorEntityToken entity) {
        String kind = classifyEntity(entity);
        if ("comm_relay".equals(kind)) return "Comm Relay VI";
        if ("gate".equals(kind)) return "Gate Terminal";
        if ("station".equals(kind)) return "Station Control";
        if ("salvageable".equals(kind) || "derelict".equals(kind)) return "Derelict VI";
        if ("warning_beacon".equals(kind)) return "Warning Beacon";
        if ("stable_location".equals(kind)) return "Survey Buoy";
        return "Unknown Signal";
    }

    private static boolean hasTag(SectorEntityToken entity, String tag) {
        if (entity == null || tag == null) return false;
        return entity.hasTag(tag);
    }
}
