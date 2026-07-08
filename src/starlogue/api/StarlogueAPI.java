package starlogue.api;

import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.personality.FactionProfileRegistry;
import org.apache.log4j.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public static facade for Starlogue integration.
 *
 * <p>Other mods should call {@link #isLoaded()} before registering
 * to guard against Starlogue being absent:
 *
 * <pre>
 * if (StarlogueAPI.isLoaded()) {
 *     StarlogueAPI.registerContextModifier(new MyContextMod());
 * }
 * </pre>
 *
 * <p>Starlogue declares soft dependencies, so your mod will still
 * load if Starlogue is absent — the {@code isLoaded()} guard ensures
 * your registration code never runs in that case.
 */
public final class StarlogueAPI {

    private static final Logger log = Logger.getLogger(StarlogueAPI.class);

    // Set to true in StarlogueModPlugin.onApplicationLoad()
    private static boolean loaded = false;

    // Singleton handles for the extension-point accessor methods below.
    // MemoryEngine and FactionProfileRegistry are entirely static utility classes;
    // these instances act as stable handles for contributor plugins that need a
    // typed reference (e.g. to pass to a factory or store in a field).
    private static final MemoryEngine MEMORY_ENGINE_INSTANCE = new MemoryEngine();
    private static final FactionProfileRegistry FACTION_PROFILE_REGISTRY_INSTANCE = new FactionProfileRegistry();

    // Active conversation context — non-null only during a conversation
    private static GameContext currentContext = null;

    // Registries
    private static final List<ContextModifier>          contextModifiers    = new ArrayList<ContextModifier>();
    private static final List<PersonalityModifier>       personalityModifiers = new ArrayList<PersonalityModifier>();
    private static final List<ActionContributor>         actionContributors  = new ArrayList<ActionContributor>();
    private static final List<FactionProfileContributor> profileContributors = new ArrayList<FactionProfileContributor>();
    private static final java.util.List<CharacterProfileContributor> characterProfileContributors
        = new java.util.ArrayList<CharacterProfileContributor>();
    // Full NPC-tier plugins (checked before built-in FleetCaptainPlugin)
    private static final List<starlogue.provider.StarloguePlugin> plugins = new ArrayList<starlogue.provider.StarloguePlugin>();

    private StarlogueAPI() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Called by StarlogueModPlugin.onApplicationLoad(). Do not call from external code. */
    public static void markLoaded() { loaded = true; }

    /**
     * Returns true if Starlogue is installed and fully initialised.
     * Always check this before calling any other method from an external mod.
     */
    public static boolean isLoaded() { return loaded; }

    // ── Registration ──────────────────────────────────────────────────────

    public static void registerContextModifier(ContextModifier m) {
        if (m == null) return;
        contextModifiers.add(m);
        log.info("Starlogue: registered ContextModifier from mod '" + m.getModId() + "'");
    }

    public static void registerPersonalityModifier(PersonalityModifier m) {
        if (m == null) return;
        personalityModifiers.add(m);
        log.info("Starlogue: registered PersonalityModifier from mod '" + m.getModId() + "'");
    }

    public static void registerActionContributor(ActionContributor c) {
        if (c == null) return;
        actionContributors.add(c);
        log.info("Starlogue: registered ActionContributor from mod '" + c.getModId() + "'");
    }

    public static void registerFactionProfile(FactionProfileContributor c) {
        if (c == null) return;
        profileContributors.add(c);
        log.info("Starlogue: registered FactionProfileContributor from mod '" + c.getModId() + "'");
    }

    public static void registerCharacterProfile(CharacterProfileContributor c) {
        if (c == null) return;
        characterProfileContributors.add(c);
        log.info("Starlogue: registered CharacterProfileContributor from mod '" + c.getModId() + "'");
    }

    public static java.util.List<CharacterProfileContributor> getCharacterProfileContributors() {
        return java.util.Collections.unmodifiableList(characterProfileContributors);
    }

    /**
     * Register a full StarloguePlugin (new NPC tier).
     * Registered plugins are checked BEFORE the built-in FleetCaptainPlugin,
     * so they can intercept specific entity types (e.g. Star Lords).
     */
    public static void registerPlugin(starlogue.provider.StarloguePlugin p) {
        if (p == null) return;
        plugins.add(p);
        log.info("Starlogue: registered StarloguePlugin " + p.getClass().getSimpleName());
    }

    // ── Internal accessors (called by Starlogue's own code) ───────────────

    /** Read-only view of externally registered StarloguePlugins (checked before built-ins). */
    public static List<starlogue.provider.StarloguePlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    /** Read-only view of registered context modifiers. */
    public static List<ContextModifier> getContextModifiers() {
        return Collections.unmodifiableList(contextModifiers);
    }

    /** Read-only view of registered personality modifiers. */
    public static List<PersonalityModifier> getPersonalityModifiers() {
        return Collections.unmodifiableList(personalityModifiers);
    }

    /** Read-only view of registered action contributors. */
    public static List<ActionContributor> getActionContributors() {
        return Collections.unmodifiableList(actionContributors);
    }

    /** Read-only view of registered faction profile contributors. */
    public static List<FactionProfileContributor> getProfileContributors() {
        return Collections.unmodifiableList(profileContributors);
    }

    // ── Observation API (for external mods that want to react) ────────────

    /**
     * Returns the GameContext of the active conversation, or null if
     * no Starlogue conversation is in progress.
     */
    public static GameContext getCurrentContext() { return currentContext; }

    /**
     * Returns a stable handle to the MemoryEngine.
     *
     * <p>All MemoryEngine operations are static; this instance is a typed
     * reference intended for contributor plugins that need to store or pass
     * a {@code MemoryEngine} reference. Use {@code MemoryEngine.getScore(person)}
     * etc. directly for score queries.
     *
     * <p>Extension point: contributor plugins may cast to a subclass if a
     * future release makes MemoryEngine non-final.
     */
    public static MemoryEngine getMemoryEngine() { return MEMORY_ENGINE_INSTANCE; }

    /**
     * Returns a stable handle to the FactionProfileRegistry.
     *
     * <p>All registry operations are static; this instance is a typed
     * reference for contributor plugins. Use
     * {@code FactionProfileRegistry.getProfile(factionId)} directly for lookups.
     *
     * <p>Extension point: contributor plugins can call
     * {@code StarlogueAPI.registerFactionProfile(contributor)} to inject custom
     * profiles without needing a direct registry reference.
     */
    public static FactionProfileRegistry getFactionProfileRegistry() { return FACTION_PROFILE_REGISTRY_INSTANCE; }

    // ── Internal conversation lifecycle (called by StarlogueDialogPlugin) ──

    /** Called when a conversation begins. */
    public static void setCurrentContext(GameContext ctx) { currentContext = ctx; }

    /** Called when a conversation ends. */
    public static void clearCurrentContext() { currentContext = null; }

    // ── Dialog handoff (for actions that need to launch a new dialog) ─────────

    private static InteractionDialogPlugin pendingHandoff = null;

    /**
     * Request that the Starlogue dialog dismiss itself and hand off to another
     * InteractionDialogPlugin on the next frame.
     * Call from within StarlogueAction.execute() to transition to e.g. a tournament.
     */
    public static void handoffToPlugin(InteractionDialogPlugin plugin) {
        pendingHandoff = plugin;
    }

    /** Called by StarlogueDialogPlugin after each tool call. Returns null if no handoff pending. */
    public static InteractionDialogPlugin consumeHandoff() {
        InteractionDialogPlugin p = pendingHandoff;
        pendingHandoff = null;
        return p;
    }

    /**
     * Returns the pending handoff plugin without consuming it.
     * Used by {@link starlogue.mcp.McpToolBridge} to detect handoffs triggered via
     * MCP-driven tool execution, without removing the reference before the dialog
     * plugin can act on it.
     */
    public static InteractionDialogPlugin peekHandoff() {
        return pendingHandoff;
    }
}
