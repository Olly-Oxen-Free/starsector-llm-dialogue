package starlogue;

import starlogue.action.ActionMath;

public class ActionMathTest {
    public static void main(String[] args) throws Exception {
        testClampRansomNegativeYieldsZero();
        testClampRansomHugeCapsAtMax();
        testClampRansomCapsAtPlayerBalance();
        testClampRansomNormal();
        testTradeOfferRejectsNonPositivePrice();
        testTradeOfferRejectsNonPositiveQuantity();
        testTradeOfferAcceptsPositiveTerms();
        testRepCapAccountingStopsAtCapGain();
        testRepCapAccountingStopsAtCapLoss();
        testRepCapAlreadyExhaustedReturnsZero();
        testRepPointsUsedConversion();
        testClampExtortFloorNeverExceedsTenPercent();
        testClampExtortNormalWithinCap();
        testClampExtortZeroBalance();
        testAsFloatCoercesStringsAndNumbers();
        System.out.println("ActionMathTest: ALL PASSED");
    }

    static void testClampRansomNegativeYieldsZero() {
        float clamped = ActionMath.clampRansom(-1_000_000_000f, 100_000f, 50_000f);
        assert clamped == 0f : "Expected 0 for negative amount, got " + clamped;
    }

    static void testClampRansomHugeCapsAtMax() {
        float clamped = ActionMath.clampRansom(1_000_000f, 100_000f, 5_000_000f);
        assert clamped == 100_000f : "Expected cap at MAX_RANSOM, got " + clamped;
    }

    static void testClampRansomCapsAtPlayerBalance() {
        float clamped = ActionMath.clampRansom(90_000f, 100_000f, 20_000f);
        assert clamped == 20_000f : "Expected cap at player balance, got " + clamped;
    }

    static void testClampRansomNormal() {
        float clamped = ActionMath.clampRansom(5_000f, 100_000f, 50_000f);
        assert clamped == 5_000f : "Expected pass-through for normal amount, got " + clamped;
    }

    static void testTradeOfferRejectsNonPositivePrice() {
        assert !ActionMath.isValidTradeOffer(0f, 10f) : "Zero price must be rejected";
        assert !ActionMath.isValidTradeOffer(-5f, 10f) : "Negative price must be rejected";
    }

    static void testTradeOfferRejectsNonPositiveQuantity() {
        assert !ActionMath.isValidTradeOffer(10f, 0f) : "Zero quantity must be rejected";
        assert !ActionMath.isValidTradeOffer(10f, -1f) : "Negative quantity must be rejected";
    }

    static void testTradeOfferAcceptsPositiveTerms() {
        assert ActionMath.isValidTradeOffer(10f, 5f) : "Positive price+quantity must be accepted";
    }

    static void testRepCapAccountingStopsAtCapGain() {
        // delta = +0.10 internal scale per call (10 points), cap = 20 points -> stops after 2 calls.
        float usedPoints = 0f;
        float capPoints = 20f;
        float delta = 0.10f;
        int callsApplied = 0;
        for (int i = 0; i < 10; i++) {
            float effective = ActionMath.clampRepDelta(delta, usedPoints, capPoints);
            if (effective == 0f) break;
            usedPoints += ActionMath.repPointsUsed(effective);
            callsApplied++;
        }
        assert callsApplied == 2 : "Expected exactly 2 calls of +0.10 (10 points each) to exhaust a 20-point cap, got " + callsApplied;
        assert usedPoints == 20f : "Expected accumulator to land exactly at the 20-point cap, got " + usedPoints;
    }

    static void testRepCapAccountingStopsAtCapLoss() {
        float usedPoints = 0f;
        float capPoints = 20f;
        float delta = -0.08f;
        int callsApplied = 0;
        for (int i = 0; i < 10; i++) {
            float effective = ActionMath.clampRepDelta(delta, usedPoints, capPoints);
            if (effective == 0f) break;
            usedPoints += ActionMath.repPointsUsed(effective);
            callsApplied++;
        }
        // 0.08 * 100 = 8 points/call; 2 calls = 16, 3rd call clamped to remaining 4 points.
        assert callsApplied == 3 : "Expected 3 calls (2 full + 1 partial) to exhaust a 20-point cap, got " + callsApplied;
        assert usedPoints == 20f : "Expected accumulator to land exactly at the 20-point cap, got " + usedPoints;
    }

    static void testRepCapAlreadyExhaustedReturnsZero() {
        float effective = ActionMath.clampRepDelta(0.05f, 20f, 20f);
        assert effective == 0f : "Expected 0 when cap already fully used, got " + effective;
    }

    static void testRepPointsUsedConversion() {
        float points = ActionMath.repPointsUsed(-0.10f);
        assert points == 10f : "Expected |delta|*100 = 10 points, got " + points;
    }

    static void testClampExtortFloorNeverExceedsTenPercent() {
        // Poor player: 5000 credits -> 10% = 500, which is below the 1000 floor.
        // Result must not exceed the 10% cap even though the floor is nominally 1000.
        float clamped = ActionMath.clampExtort(150_000f, 5_000f, 200_000f, 1000f);
        assert clamped == 500f : "Expected result capped at 10% of balance (500), got " + clamped;
    }

    static void testClampExtortNormalWithinCap() {
        // Player with 100k credits: 10% = 10000, raw demand of 5000 is within cap and above floor.
        float clamped = ActionMath.clampExtort(5_000f, 100_000f, 200_000f, 1000f);
        assert clamped == 5_000f : "Expected raw demand honored when within cap, got " + clamped;
    }

    static void testClampExtortZeroBalance() {
        float clamped = ActionMath.clampExtort(50_000f, 0f, 200_000f, 1000f);
        assert clamped == 0f : "Expected 0 when player has no credits, got " + clamped;
    }

    static void testAsFloatCoercesStringsAndNumbers() {
        assert ActionMath.asFloat(42) == 42f : "Expected numeric coercion";
        assert ActionMath.asFloat("13.5") == 13.5f : "Expected string coercion";
        assert ActionMath.asFloat("not-a-number") == 0f : "Expected fallback to 0 on parse failure";
        assert ActionMath.asFloat((Object) null) == 0f : "Expected fallback to 0 on null";
    }
}
