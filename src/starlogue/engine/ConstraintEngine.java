package starlogue.engine;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import starlogue.action.StarlogueAction;
import starlogue.api.ActionContributor;
import starlogue.api.ContextModifier;
import starlogue.api.StarlogueAPI;
import starlogue.provider.FleetCaptainPlugin;
import starlogue.provider.MarketAdminPlugin;
import starlogue.provider.PersonInteractionPlugin;
import starlogue.provider.StarloguePlugin;
import starlogue.provider.SystemAIPlugin;
import org.apache.log4j.Logger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConstraintEngine {

    private static final Logger log = Logger.getLogger(ConstraintEngine.class);
    // Built-in plugins (lowest priority — external plugins registered via StarlogueAPI take precedence).
    // Order matters: first match wins, so the richest context plugin is listed first.
    private static final List<StarloguePlugin> plugins = new ArrayList<StarloguePlugin>();

    static {
        plugins.add(new FleetCaptainPlugin());
        // MarketAdmin matches before PersonInteraction so that a top-level market
        // dialog (where memoryMap's $person often defaults to the admin) is framed
        // as "hailing the colony authority" rather than as a 1:1 chat with the admin.
        // MarketAdminPlugin.canEngage self-defers when $person is a *different* person
        // (bar visitor, quest giver, officer for hire), so those cases still route
        // to PersonInteractionPlugin.
        plugins.add(new MarketAdminPlugin());
        plugins.add(new PersonInteractionPlugin());
        plugins.add(new SystemAIPlugin()); // always-true fallback
    }

    /** All plugins in priority order: external (registered first) then built-in. */
    private static List<StarloguePlugin> allPlugins() {
        List<StarloguePlugin> all = new ArrayList<StarloguePlugin>(StarlogueAPI.getPlugins());
        all.addAll(plugins);
        return all;
    }

    public static boolean canEngage(SectorEntityToken entity) {
        return canEngage(entity, null);
    }

    public static boolean canEngage(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        if (StarlogueTargetFilter.isExcluded(entity)) return false;
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity, memoryMap)) return true;
        }
        return false;
    }

    public static GameContext buildContext(SectorEntityToken entity) {
        return buildContext(entity, null);
    }

    public static GameContext buildContext(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        if (StarlogueTargetFilter.isExcluded(entity)) {
            throw new IllegalStateException("StarlogueTargetFilter excludes entity: " + entity);
        }
        GameContext ctx = null;
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity, memoryMap)) { ctx = p.buildContext(entity, memoryMap); break; }
        }
        if (ctx == null) throw new IllegalStateException("No plugin can engage entity: " + entity);

        // Run registered ContextModifiers
        for (ContextModifier mod : StarlogueAPI.getContextModifiers()) {
            try {
                mod.modify(ctx);
            } catch (Exception e) {
                log.error("Starlogue: ContextModifier from mod '" + mod.getModId() + "' threw", e);
            }
        }
        return ctx;
    }

    /** Resolve the matching plugin for this entity. */
    public static StarloguePlugin getPlugin(SectorEntityToken entity) {
        return getPlugin(entity, null);
    }

    public static StarloguePlugin getPlugin(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        if (StarlogueTargetFilter.isExcluded(entity)) return null;
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity, memoryMap)) return p;
        }
        return null;
    }

    public static EvaluatedActionSet evaluate(SectorEntityToken entity, GameContext ctx) {
        return evaluate(entity, ctx, null);
    }

    /**
     * Same as {@link #evaluate(SectorEntityToken, GameContext)} but uses {@code memoryMap}
     * when resolving the active {@link StarloguePlugin} so bar/comm flows match
     * {@link #buildContext(SectorEntityToken, Map)}.
     */
    public static EvaluatedActionSet evaluate(SectorEntityToken entity, GameContext ctx,
                                              Map<String, MemoryAPI> memoryMap) {
        StarloguePlugin plugin = getPlugin(entity, memoryMap);
        if (plugin == null) return new EvaluatedActionSet(new ArrayList<StarlogueAction>(), new ArrayList<StarlogueAction>());

        List<StarlogueAction> pool = new ArrayList<StarlogueAction>(plugin.getActions(ctx));
        // Add actions from registered ActionContributors
        for (ActionContributor contributor : StarlogueAPI.getActionContributors()) {
            try {
                List<StarlogueAction> extra = contributor.getActions(ctx);
                if (extra != null) pool.addAll(extra);
            } catch (Exception e) {
                log.error("Starlogue: ActionContributor from mod '" + contributor.getModId() + "' threw", e);
            }
        }
        List<StarlogueAction> available = new ArrayList<StarlogueAction>();
        List<StarlogueAction> bluffOnly = new ArrayList<StarlogueAction>();
        boolean npcCanBluff = isBluffCapable(ctx);

        for (StarlogueAction action : pool) {
            if (action.isAvailable(ctx)) {
                available.add(action);
            } else if (npcCanBluff && action.isBluffable()) {
                bluffOnly.add(action);
            }
        }

        return new EvaluatedActionSet(available, bluffOnly);
    }

    /** Build tools[] array for the OpenAI-compatible request body. */
    public static List<Map<String, Object>> buildToolsArray(EvaluatedActionSet set) {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        for (StarlogueAction action : set.available) {
            Map<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<String, Object>();
            fn.put("name", action.getId());
            fn.put("description", action.getDescription());
            fn.put("parameters", buildParamSchema(action.getParameters(), action.getParameterDescriptions()));
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    private static Map<String, Object> buildParamSchema(
            Map<String, Object> paramDefs,
            Map<String, String> descriptions) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> required = new ArrayList<String>();
        for (Map.Entry<String, Object> e : paramDefs.entrySet()) {
            Map<String, Object> typeObj = new LinkedHashMap<String, Object>();
            typeObj.put("type", e.getValue());
            String desc = descriptions.get(e.getKey());
            if (desc != null && !desc.isEmpty()) {
                typeObj.put("description", desc);
            }
            properties.put(e.getKey(), typeObj);
            required.add(e.getKey());
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static boolean isBluffCapable(GameContext ctx) {
        if (ctx.person == null || ctx.person.getPersonalityAPI() == null) return false;
        String pid = ctx.person.getPersonalityAPI().getId();
        return Personalities.RECKLESS.equals(pid) || Personalities.AGGRESSIVE.equals(pid);
    }
}
