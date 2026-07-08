package starlogue.memory;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.apache.log4j.Logger;
import starlogue.config.LunaSettingHelper;
import java.util.Collection;
import java.util.Map;

public class MemoryEngine {

    public static final String KEY_PREFIX = "$starlogue_";
    private static final Logger log = Logger.getLogger(MemoryEngine.class);

    // ── Game-API methods ──────────────────────────────────────────────────

    /**
     * Records a memory event against a person.
     *
     * @param decayMultiplier per-call intensity multiplier (caller-supplied, varies by action).
     *                        Combined with the global {@code starlogue_decay_multiplier} Luna
     *                        setting (default 1.0), which uniformly scales all memory TTLs.
     */
    public static void recordEvent(PersonAPI person, MemoryEvent event, float decayMultiplier) {
        if (person == null) return;
        float lunaDecayMultiplier = (float) LunaSettingHelper.getDouble("starlogue_decay_multiplier", 1.0);
        float ttl = event.ttlDays * decayMultiplier * lunaDecayMultiplier;
        MemoryAPI mem = person.getMemory();
        String key = KEY_PREFIX + event.keySuffix;
        float current = mem.contains(key) ? getFloat(mem, key) : 0f;
        float next = nextClampedValue(current, event);
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
        float next = nextClampedValue(current, event);
        mem.put(key, next);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** Shared clamp logic: current value + event points, clamped to +/- 2x the event's point magnitude. */
    private static float nextClampedValue(float current, MemoryEvent event) {
        float cap = Math.abs(event.points) * 2f;
        return clamp(current + event.points, -cap, cap);
    }

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
