package starlogue;

import starlogue.personality.FactionProfile;
import starlogue.personality.FactionProfileRegistry;
import starlogue.personality.PersonalityComposer;

public class PersonalityComposerTest {
    public static void main(String[] args) {
        testFallbackUsedForUnknownFaction();
        testRegisteredProfileAppearsInOutput();
        testPersonalityNoteIncluded();
        testAICoreOverride();
        System.out.println("PersonalityComposerTest: ALL PASSED");
    }

    static void testFallbackUsedForUnknownFaction() {
        String result = PersonalityComposer.composeFromParts(
            "faction_that_does_not_exist_xyz", "steady", "Captain", false, new String[0]);
        assert result != null && !result.isBlank() : "Expected non-blank result";
        assert result.contains("fleet commander") : "Expected fallback text, got: " + result;
    }

    static void testRegisteredProfileAppearsInOutput() {
        FactionProfileRegistry.putProfile("test_pirates",
            new FactionProfile("test_pirates", "You are a pirate — pragmatic and opportunistic."));
        String result = PersonalityComposer.composeFromParts(
            "test_pirates", "aggressive", "Captain", false, new String[0]);
        assert result.contains("pirate") : "Expected pirate baseline in output, got: " + result;
    }

    static void testPersonalityNoteIncluded() {
        FactionProfileRegistry.putProfile("test_faction2",
            new FactionProfile("test_faction2", "You serve a test faction."));
        String result = PersonalityComposer.composeFromParts(
            "test_faction2", "reckless", "Captain", false, new String[0]);
        assert result.contains("reckless") : "Expected reckless personality note, got: " + result;
    }

    static void testAICoreOverride() {
        FactionProfileRegistry.putProfile("test_remnant",
            new FactionProfile("test_remnant", "You are a Remnant."));
        String result = PersonalityComposer.composeFromParts(
            "test_remnant", "steady", null, true, new String[0]);
        assert result.contains("artificial intelligence")
            : "Expected AI core override, got: " + result;
    }
}
