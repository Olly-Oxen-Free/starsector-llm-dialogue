package starlogue.api;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import java.util.List;

/**
 * Contributes additional StarlogueActions to a conversation.
 * Called during ConstraintEngine.evaluate(); returned actions go through
 * the same isAvailable() / isBluffable() gate as built-in actions.
 *
 * <p>Return an empty list (not null) if this contributor has nothing
 * to add for the given context.
 */
public interface ActionContributor {
    String getModId();

    /**
     * Return actions to add to the available pool for this conversation.
     * Each action's {@code isAvailable(ctx)} will still be evaluated —
     * returning an action here does not force it into the tools list.
     */
    List<StarlogueAction> getActions(GameContext ctx);
}
