package starlogue.api;

import starlogue.engine.GameContext;

/**
 * Contributes custom fields and context notes to a GameContext before
 * prompt assembly. Called once per conversation, after the core context
 * is assembled by the plugin.
 *
 * <p>Registration (in your ModPlugin.onApplicationLoad):
 * <pre>
 *   if (StarlogueAPI.isLoaded()) {
 *       StarlogueAPI.registerContextModifier(new MyContextModifier());
 *   }
 * </pre>
 */
public interface ContextModifier {
    /** Unique mod identifier. Used for deduplication if the same mod registers twice. */
    String getModId();

    /**
     * Modify the context before prompt assembly.
     * Use {@code ctx.set(key, value)} for cross-contributor data,
     * {@code ctx.addContextNote(note)} to inject into the [SITUATION] block.
     */
    void modify(GameContext ctx);
}
