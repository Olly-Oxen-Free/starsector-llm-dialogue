package starlogue.memory;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.apache.log4j.Logger;
import java.util.Collection;
import java.util.Map;

public class MemoryEngine {

    public static final String KEY_PREFIX = "$starlogue_";
    private static final Logger log = Logger.getLogger(MemoryEngine.class);

    // ── Game-API methods ──────────────────────────────────────────────────

    public static void recordEvent(PersonAPI person, MemoryEvent event, float decayMultiplier) {
        float ttl = event.ttlDays * decayMultiplier;
        MemoryAPI mem = person.getMemory();
        String key = KEY_PREFIX + event.keySuffix;
        float current = mem.contains(key) ? getFloat(mem, key) : 0f;
        float cap = Math.abs(event.points) * 2f;
        float next = clamp(current + event.points, -cap, cap);
        mem.set(key, next, ttl);
    }

    public static void recordFactionEvent(FactionAPI faction, MemoryEvent event, float decayMultiplier) {
        float ttl = event.ttlDays * decayMultiplier;
        MemoryAPI mem = faction.getMemory();
        String key = KEY_PREFIX + event.keySuffix;
        float current = mem.contains(key) ? getFloat(mem, key) : 0f;
        float cap = Math.abs(event.points) * 2f;
        float next = clamp(current + event.points, -cap, cap);
        mem.set(key, next, ttl);
    }

    public static float getScore(PersonAPI person) {
        return scoreFromMemoryAPI(person.getMemory());
    }

    public static float getFactionScore(FactionAPI faction) {
        return scoreFromMemoryAPI(faction.getMemory());
    }

    // ── Testable pure methods (used by unit tests without game API) ────────

    /** Compute score from a plain Map — callable without game runtime. */
    public static float scoreFromMap(Map<String, Object> mem) {
        float total = 0f;
        for (Map.Entry<String, Object> e : mem.entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX) && e.getValue() instanceof Float) {
                total += (Float) e.getValue();
            }
        }
        return total;
    }

    /** Record event into a plain Map — callable without game runtime. */
    public static void recordEventToMap(Map<String, Object> mem, MemoryEvent event, float decayMultiplier) {
        String key = KEY_PREFIX + event.keySuffix;
        float current = mem.containsKey(key) ? (Float) mem.get(key) : 0f;
        float cap = Math.abs(event.points) * 2f;
        float next = clamp(current + event.points, -cap, cap);
        mem.put(key, next);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static float scoreFromMemoryAPI(MemoryAPI mem) {
        float total = 0f;
        Collection<String> keys = mem.getKeys();
        if (keys == null) return 0f;
        for (String key : keys) {
            if (!key.startsWith(KEY_PREFIX)) continue;
            Object val = mem.get(key);
            if (val instanceof Float) {
                total += (Float) val;
            } else if (val instanceof Number) {
                total += ((Number) val).floatValue();
            }
        }
        return total;
    }

    private static float getFloat(MemoryAPI mem, String key) {
        Object val = mem.get(key);
        return val instanceof Number ? ((Number) val).floatValue() : 0f;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
