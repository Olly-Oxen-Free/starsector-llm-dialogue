package starlogue.personality;

public class FactionProfile {
    public final String factionId;
    public final String baseline;

    public FactionProfile(String factionId, String baseline) {
        this.factionId = factionId;
        this.baseline = baseline != null ? baseline : "";
    }
}
