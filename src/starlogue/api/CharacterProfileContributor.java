package starlogue.api;

import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.personality.CharacterProfile;

/**
 * Allows mods to provide hand-tuned character profiles for specific named NPCs.
 * Called before the built-in character profiles and before any faction profile lookup.
 */
public interface CharacterProfileContributor {

    /** The mod ID that registered this contributor — used in error logs. */
    String getModId();

    /**
     * Return a {@link CharacterProfile} for the given NPC, or {@code null} to pass through
     * to the next contributor / built-in profiles.
     *
     * @param person    the NPC PersonAPI (never null)
     * @param factionId the NPC's faction ID (may be "unknown")
     */
    CharacterProfile getProfile(PersonAPI person, String factionId);
}
