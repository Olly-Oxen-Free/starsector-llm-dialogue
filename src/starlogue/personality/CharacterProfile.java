package starlogue.personality;

/**
 * Hand-authored roleplay profile for a specific named NPC.
 * Matched by display name ({@code PersonAPI.getNameString()}) with an optional
 * faction constraint to disambiguate common names across factions.
 */
public class CharacterProfile {

    /** The display name key used to match against {@code PersonAPI.getNameString()}. */
    public final String nameKey;

    /**
     * Optional faction ID constraint. When non-null, the profile only applies if the
     * NPC's faction ID equals this value. Set to null to match the name across all factions.
     */
    public final String factionId;

    /** The roleplay baseline — replaces the faction baseline for this NPC. */
    public final String baseline;

    public CharacterProfile(String nameKey, String factionId, String baseline) {
        this.nameKey  = nameKey  != null ? nameKey  : "";
        this.factionId = factionId; // null = match any faction
        this.baseline = baseline != null ? baseline : "";
    }
}
