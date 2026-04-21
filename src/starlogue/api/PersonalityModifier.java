package starlogue.api;

import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.engine.GameContext;

/**
 * Appends additional text to the NPC voice block in the system prompt.
 * Runs after the base personality stack is assembled by PersonalityComposer.
 *
 * <p>Return non-null to append; return null to skip for this NPC.
 */
public interface PersonalityModifier {
    String getModId();

    /**
     * @param factionId The NPC's faction ID.
     * @param person    The NPC PersonAPI.
     * @param ctx       The assembled GameContext (extras already populated by ContextModifiers).
     * @return Additional personality text to append, or null to skip.
     */
    String modify(String factionId, PersonAPI person, GameContext ctx);
}
