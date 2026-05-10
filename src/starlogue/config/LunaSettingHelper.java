package starlogue.config;

/**
 * LunaLib settings read without hard class dependency (matches StarlogueDialogPlugin pattern).
 */
public final class LunaSettingHelper {

    private static Boolean LUNA_AVAILABLE = null;

    private LunaSettingHelper() {}

    public static boolean lunaLibAvailable() {
        if (LUNA_AVAILABLE != null) return LUNA_AVAILABLE;
        try {
            Class.forName("lunalib.lunaSettings.LunaSettings",
                false, LunaSettingHelper.class.getClassLoader());
            LUNA_AVAILABLE = Boolean.TRUE;
        } catch (Throwable t) {
            LUNA_AVAILABLE = Boolean.FALSE;
        }
        return LUNA_AVAILABLE;
    }

    public static boolean getBoolean(String key, boolean fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Boolean v = lunalib.lunaSettings.LunaSettings.getBoolean("starlogue", key);
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static int getInt(String key, int fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Integer v = lunalib.lunaSettings.LunaSettings.getInt("starlogue", key);
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static double getDouble(String key, double fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Double v = lunalib.lunaSettings.LunaSettings.getDouble("starlogue", key);
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Reads a float setting, checking Double first (LunaLib CSV stores numeric fields as Double)
     * then falling back to legacy Float rows.
     */
    public static float getFloat(String key, float fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Double d = lunalib.lunaSettings.LunaSettings.getDouble("starlogue", key);
            if (d != null) return d.floatValue();
        } catch (Throwable ignored) { }
        try {
            Float f = lunalib.lunaSettings.LunaSettings.getFloat("starlogue", key);
            if (f != null) return f;
        } catch (Throwable ignored) { }
        return fallback;
    }
}
