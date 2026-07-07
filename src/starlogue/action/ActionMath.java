package starlogue.action;

/**
 * Small, pure (no game-API) numeric helpers shared across {@link StarlogueAction} implementations.
 *
 * <p>Kept dependency-free so it can be exercised by headless unit tests without a running
 * Starsector campaign.
 */
public final class ActionMath {

    private ActionMath() { }

    /**
     * Coerces numeric or string-typed JSON values to float. LLMs sometimes return numbers as
     * strings (e.g. {@code "42"} instead of {@code 42}); anything unparseable becomes {@code 0f}.
     */
    public static float asFloat(Object val) {
        if (val instanceof Number) return ((Number) val).floatValue();
        try {
            return Float.parseFloat(String.valueOf(val));
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** String overload for call sites that already have a raw token string in hand. */
    public static float asFloat(String val) {
        return asFloat((Object) val);
    }

    /**
     * Clamps a ransom payment: non-negative, at most {@code maxRansom}, and at most the player's
     * current credit balance. A negative or zero input (or a non-positive balance) yields {@code 0f},
     * signalling the caller should early-return with no transfer and no side effects.
     */
    public static float clampRansom(float amount, float maxRansom, float playerBalance) {
        if (amount <= 0f) return 0f;
        float clamped = Math.min(amount, maxRansom);
        clamped = Math.min(clamped, Math.max(0f, playerBalance));
        return Math.max(0f, clamped);
    }

    /**
     * Validates a trade offer's economic terms. A trade is only executable when both the price
     * and quantity are strictly positive — non-positive price risks div-by-zero downstream and
     * (combined with a negative-cost exploit) can grant goods and credits simultaneously.
     */
    public static boolean isValidTradeOffer(float pricePerUnit, float quantity) {
        return pricePerUnit > 0f && quantity > 0f;
    }

    /**
     * Computes the effective reputation delta allowed under a monthly cap expressed in
     * <b>display points</b> (-100..+100 scale), given a delta expressed on the game API's
     * <b>internal</b> (-1..+1) scale and an accumulator ({@code usedPoints}) also tracked in
     * display points.
     *
     * @param delta      requested delta, internal -1..+1 scale
     * @param usedPoints points already consumed this window (display-point scale, always >= 0)
     * @param capPoints  configured monthly cap (display-point scale, e.g. 20.0 == 20 rep points)
     * @return the effective delta (internal scale) to actually apply; {@code 0f} if the cap is
     *         already exhausted
     */
    public static float clampRepDelta(float delta, float usedPoints, float capPoints) {
        float allowedPoints = capPoints - usedPoints;
        if (allowedPoints <= 0f) return 0f;
        float allowedDelta = allowedPoints / 100f;
        if (delta >= 0f) {
            return Math.min(delta, allowedDelta);
        }
        return Math.max(delta, -allowedDelta);
    }

    /** Converts an applied internal-scale delta into the display-point amount to add to the accumulator. */
    public static float repPointsUsed(float effectiveDelta) {
        return Math.abs(effectiveDelta) * 100f;
    }

    /**
     * Clamps an extortion demand so the result never exceeds {@code absoluteMax} nor 10% of the
     * player's credit balance. The advertised minimum demand ({@code floor}) only applies when it
     * is actually affordable within that cap — for poor players the floor is reduced to the cap
     * itself rather than overriding it.
     */
    public static float clampExtort(float raw, float playerBalance, float absoluteMax, float floor) {
        float capAmount = Math.min(absoluteMax, Math.max(0f, playerBalance) * 0.10f);
        if (capAmount <= 0f) return 0f;
        float effectiveFloor = Math.min(floor, capAmount);
        float withinCap = Math.min(raw, capAmount);
        return Math.max(effectiveFloor, withinCap);
    }
}
