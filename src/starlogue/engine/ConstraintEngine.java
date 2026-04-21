package starlogue.engine;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import starlogue.action.StarlogueAction;
import starlogue.api.ActionContributor;
import starlogue.api.ContextModifier;
import starlogue.api.StarlogueAPI;
import starlogue.provider.FleetCaptainPlugin;
import starlogue.provider.StarloguePlugin;
import org.apache.log4j.Logger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConstraintEngine {

    private static final Logger log = Logger.getLogger(ConstraintEngine.class);
    // Built-in plugins (lowest priority — external plugins registered via StarlogueAPI take precedence)
    private static final List<StarloguePlugin> plugins = new ArrayList<StarloguePlugin>();

    static {
        plugins.add(new FleetCaptainPlugin());
    }

    /** All plugins in priority order: external (registered first) then built-in. */
    private static List<StarloguePlugin> allPlugins() {
        List<StarloguePlugin> all = new ArrayList<StarloguePlugin>(StarlogueAPI.getPlugins());
        all.addAll(plugins);
        return all;
    }

    public static boolean canEngage(SectorEntityToken entity) {
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity)) return true;
        }
        return false;
    }

    public static GameContext buildContext(SectorEntityToken entity) {
        GameContext ctx = null;
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity)) { ctx = p.buildContext(entity); break; }
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
        for (StarloguePlugin p : allPlugins()) {
            if (p.canEngage(entity)) return p;
        }
        return null;
    }

    public static EvaluatedActionSet evaluate(SectorEntityToken entity, GameContext ctx) {
        StarloguePlugin plugin = getPlugin(entity);
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
            fn.put("parameters", buildParamSchema(action.getParameters()));
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    private static Map<String, Object> buildParamSchema(Map<String, Object> paramDefs) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> required = new ArrayList<String>();
        for (Map.Entry<String, Object> e : paramDefs.entrySet()) {
            Map<String, Object> typeObj = new LinkedHashMap<String, Object>();
            typeObj.put("type", e.getValue());
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
