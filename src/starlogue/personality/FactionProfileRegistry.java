package starlogue.personality;

import com.fs.starfarer.api.Global;
import starlogue.api.FactionProfileContributor;
import starlogue.api.StarlogueAPI;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class FactionProfileRegistry {

    private static final Logger log = Logger.getLogger(FactionProfileRegistry.class);
    private static final Map<String, FactionProfile> profiles = new HashMap<String, FactionProfile>();
    private static final FactionProfile FALLBACK =
        new FactionProfile("unknown", "You are a fleet commander operating in the Persean Sector.");

    public static void load() throws Exception {
        JSONObject json = Global.getSettings().loadJSON("data/config/starlogue/faction_profiles.json");
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String factionId = keys.next();
            JSONObject entry = json.getJSONObject(factionId);
            String baseline = entry.optString("baseline", "");
            profiles.put(factionId, new FactionProfile(factionId, baseline));
        }
        log.info("Starlogue: loaded " + profiles.size() + " faction profiles");
    }

    public static FactionProfile getProfile(String factionId) {
        // Built-in profiles first
        FactionProfile p = profiles.get(factionId);
        if (p != null) return p;

        // Try registered FactionProfileContributors (first non-null wins)
        for (FactionProfileContributor c : StarlogueAPI.getProfileContributors()) {
            try {
                FactionProfile contrib = c.getProfile(factionId);
                if (contrib != null) return contrib;
            } catch (Exception e) {
                log.error("Starlogue: FactionProfileContributor from mod '" + c.getModId() + "' threw", e);
            }
        }

        return FALLBACK;
    }

    /** For unit testing — inject profiles without game API. */
    public static void putProfile(String factionId, FactionProfile profile) {
        profiles.put(factionId, profile);
    }
}
