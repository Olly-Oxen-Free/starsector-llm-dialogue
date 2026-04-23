package starlogue.personality;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.api.CharacterProfileContributor;
import starlogue.api.StarlogueAPI;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and resolves per-character roleplay profiles.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Registered {@link CharacterProfileContributor} plugins (first non-null wins)</li>
 *   <li>Built-in entries from {@code data/config/starlogue/character_profiles.json}</li>
 * </ol>
 *
 * <p>Profile keys in the JSON are the NPC's display name as returned by
 * {@code PersonAPI.getNameString()} (case-insensitive match).
 */
public class CharacterProfileRegistry {

    private static final Logger log = Logger.getLogger(CharacterProfileRegistry.class);

    /** Key = lowercase display name. */
    private static final Map<String, CharacterProfile> profiles = new HashMap<String, CharacterProfile>();

    /** Key = archetype ID (e.g. "smug"). */
    private static final Map<String, CharacterProfile> archetypeProfiles = new HashMap<String, CharacterProfile>();

    public static void load() throws Exception {
        JSONObject json = Global.getSettings().loadJSON(
            "data/config/starlogue/character_profiles.json");
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String nameKey = keys.next();
            JSONObject entry = json.getJSONObject(nameKey);
            String baseline  = entry.optString("baseline", "");
            String factionId = entry.has("factionId") && !entry.isNull("factionId")
                ? entry.getString("factionId") : null;
            profiles.put(nameKey.toLowerCase(), new CharacterProfile(nameKey, factionId, baseline));
        }
        log.info("Starlogue: loaded " + profiles.size() + " character profiles");

        // Load bar event archetype profiles
        try {
            JSONObject arcJson = Global.getSettings().loadJSON(
                "data/config/starlogue/bar_archetype_profiles.json");
            java.util.Iterator<String> arcKeys = arcJson.keys();
            while (arcKeys.hasNext()) {
                String archetypeId = arcKeys.next();
                JSONObject entry = arcJson.getJSONObject(archetypeId);
                String baseline  = entry.optString("baseline", "");
                String factionId = entry.has("factionId") && !entry.isNull("factionId")
                    ? entry.getString("factionId") : null;
                archetypeProfiles.put(archetypeId, new CharacterProfile(archetypeId, factionId, baseline));
            }
            log.info("Starlogue: loaded " + archetypeProfiles.size() + " bar archetype profiles");
        } catch (Exception e) {
            log.warn("Starlogue: could not load bar_archetype_profiles.json: " + e.getMessage());
        }
    }

    /**
     * Returns the best matching profile for the given NPC, or {@code null} if none found.
     * Contributor profiles take priority over built-in entries.
     */
    public static CharacterProfile getProfile(PersonAPI person, String factionId) {
        if (person == null) return null;

        // 1. Registered contributor plugins
        for (CharacterProfileContributor c : StarlogueAPI.getCharacterProfileContributors()) {
            try {
                CharacterProfile p = c.getProfile(person, factionId);
                if (p != null) return p;
            } catch (Exception e) {
                log.error("Starlogue: CharacterProfileContributor from mod '"
                    + c.getModId() + "' threw", e);
            }
        }

        // 2. Built-in profiles — match by lowercased display name
        String nameKey = person.getNameString() != null
            ? person.getNameString().toLowerCase() : "";
        CharacterProfile p = profiles.get(nameKey);
        if (p == null) return null;

        // Apply optional faction filter
        if (p.factionId != null && !p.factionId.equals(factionId)) return null;

        return p;
    }

    /**
     * Returns the archetype profile for the given bar event mission ID, or {@code null} if none.
     * The archetypeId comes from {@code $missionId} in the rules memory (e.g. "smug", "cheapCom").
     */
    public static CharacterProfile getArchetypeProfile(String archetypeId) {
        if (archetypeId == null || archetypeId.isEmpty()) return null;
        return archetypeProfiles.get(archetypeId);
    }
}
