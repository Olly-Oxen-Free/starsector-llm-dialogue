package starlogue;

import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import java.util.HashMap;
import java.util.Map;

public class MemoryEngineTest {
    public static void main(String[] args) {
        testScoreSum();
        testAccumulationCap();
        testNonStarlogueKeysIgnored();
        testNegativeEvents();
        System.out.println("MemoryEngineTest: ALL PASSED");
    }

    static void testScoreSum() {
        Map<String, Object> mem = new HashMap<String, Object>();
        mem.put("$starlogue_helped_battle", 30f);
        mem.put("$starlogue_deescalated", 15f);
        mem.put("$starlogue_offended", -20f);
        float score = MemoryEngine.scoreFromMap(mem);
        assert score == 25f : "Expected 25, got " + score;
    }

    static void testAccumulationCap() {
        Map<String, Object> mem = new HashMap<String, Object>();
        // HELPED_IN_BATTLE = +30 points, cap = 60
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HELPED_IN_BATTLE, 1.0f);
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HELPED_IN_BATTLE, 1.0f);
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HELPED_IN_BATTLE, 1.0f); // hits cap
        float val = (Float) mem.get("$starlogue_helped_battle");
        assert val == 60f : "Expected 60 (cap), got " + val;
    }

    static void testNonStarlogueKeysIgnored() {
        Map<String, Object> mem = new HashMap<String, Object>();
        mem.put("$starlogue_helped_battle", 30f);
        mem.put("$some_other_key", 999f);
        mem.put("notStarlogue", -500f);
        float score = MemoryEngine.scoreFromMap(mem);
        assert score == 30f : "Expected 30, got " + score;
    }

    static void testNegativeEvents() {
        Map<String, Object> mem = new HashMap<String, Object>();
        // HOSTILE_ACT = -40 points, cap = -80
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HOSTILE_ACT, 1.0f);
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HOSTILE_ACT, 1.0f);
        MemoryEngine.recordEventToMap(mem, MemoryEvent.HOSTILE_ACT, 1.0f); // hits cap
        float val = (Float) mem.get("$starlogue_hostile_act");
        assert val == -80f : "Expected -80 (negative cap), got " + val;
    }
}
