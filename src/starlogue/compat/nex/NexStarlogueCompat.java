package starlogue.compat.nex;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.api.ActionContributor;
import starlogue.engine.GameContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft Nexerelin hooks — no compile-time Nex dependency.
 * Tools are version-tolerant; failures are swallowed after one log line per tool class.
 */
public final class NexStarlogueCompat implements ActionContributor {

    private static final Logger log = Logger.getLogger(NexStarlogueCompat.class);
    private static volatile boolean loggedColonyProbe;
    private static volatile boolean loggedDiplomacyProbe;
    private static volatile boolean loggedAgentProbe;
    private static volatile Boolean nexProbeOk;

    @Override
    public String getModId() {
        return "starlogue_nex_compat";
    }

    /**
     * Probes for the Nexerelin class this compat layer's reflection targets (used by
     * {@link NexColonyReportAction}). Cached after the first check — this is only ever called
     * when the "nexerelin" mod is enabled (see {@code StarlogueModPlugin}), so a missing class
     * here means an incompatible/very old Nex build, not a missing mod.
     */
    private static boolean nexReflectionProbeOk() {
        if (nexProbeOk != null) return nexProbeOk;
        try {
            Class.forName("exerelin.campaign.ExerelinConstants", false,
                NexStarlogueCompat.class.getClassLoader());
            nexProbeOk = Boolean.TRUE;
        } catch (Throwable t) {
            log.info("Starlogue Nex: reflection probe failed, Nex actions disabled — " + t);
            nexProbeOk = Boolean.FALSE;
        }
        return nexProbeOk;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        if (!nexReflectionProbeOk()) return Collections.emptyList();
        List<StarlogueAction> actions = new ArrayList<StarlogueAction>(3);
        actions.add(new NexColonyReportAction());
        actions.add(new NexFactionSignalAction());
        actions.add(new NexAgentTipAction());
        return actions;
    }

    /** Best-effort Nex colony intel bridge (placeholder until versioned reflection is audited). */
    public static final class NexColonyReportAction implements StarlogueAction {
        @Override public String getId() { return "nex_colony_report"; }
        @Override
        public String getDescription() {
            return "Nexerelin: request a colony or economy snapshot if available in this game version.";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("focus", "string");
            return p;
        }
        @Override public boolean isBluffable() { return false; }
        @Override
        public boolean isAvailable(GameContext ctx) {
            return ctx.person != null || ctx.fleet != null;
        }
        private String note;
        @Override
        public void execute(GameContext ctx, Map<String, Object> args) {
            if (!loggedColonyProbe) {
                loggedColonyProbe = true;
                log.info("Starlogue Nex: nex_colony_report active — add versioned hooks after jar audit.");
            }
            note = "[Nex] Colony intel bridge: no stable reflected target on this build — describe verbally.";
            try {
                Class.forName("exerelin.campaign.ExerelinConstants", false,
                    NexColonyReportAction.class.getClassLoader());
                note = "[Nex] Exerelin present — use vanilla intel tools for now; Nex-specific data requires a versioned hook.";
            } catch (Throwable t) {
                note = "[Nex] Exerelin classes not reachable.";
            }
        }
        @Override public String narrativeNote() { return note; }
    }

    public static final class NexFactionSignalAction implements StarlogueAction {
        @Override public String getId() { return "nex_faction_signal"; }
        @Override
        public String getDescription() {
            return "Nexerelin: surface a diplomacy or strategic signal for the active faction context.";
        }
        @Override public Map<String, Object> getParameters() {
            return Collections.emptyMap();
        }
        @Override public boolean isBluffable() { return false; }
        @Override
        public boolean isAvailable(GameContext ctx) {
            return ctx.npcFaction != null;
        }
        private String note;
        @Override
        public void execute(GameContext ctx, Map<String, Object> args) {
            if (!loggedDiplomacyProbe) {
                loggedDiplomacyProbe = true;
                log.info("Starlogue Nex: nex_faction_signal placeholder (add versioned reflection when audited).");
            }
            note = ctx.npcFaction != null
                ? "[Nex] Faction context: " + ctx.npcFaction.getDisplayName()
                : "[Nex] No faction context.";
        }
        @Override public String narrativeNote() { return note; }
    }

    public static final class NexAgentTipAction implements StarlogueAction {
        @Override public String getId() { return "nex_agent_tip"; }
        @Override
        public String getDescription() {
            return "Nexerelin: pull an agent or covert-operation flavour tip when Nex is enabled.";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("topic", "string");
            return p;
        }
        @Override public boolean isBluffable() { return false; }
        @Override
        public boolean isAvailable(GameContext ctx) {
            return ctx.memoryScore > -5f;
        }
        private String note;
        @Override
        public void execute(GameContext ctx, Map<String, Object> args) {
            if (!loggedAgentProbe) {
                loggedAgentProbe = true;
                log.info("Starlogue Nex: nex_agent_tip placeholder (safe no-op narrative).");
            }
            note = "[Nex] Agent channel: no scripted intel this turn — improvise within canon.";
        }
        @Override public String narrativeNote() { return note; }
    }
}
