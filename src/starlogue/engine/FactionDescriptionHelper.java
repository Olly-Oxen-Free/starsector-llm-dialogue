package starlogue.engine;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds live faction context strings from the game API — no hand-authored profiles needed.
 * Works for both vanilla and modded factions.
 *
 * Description priority:
 *   1. Game descriptions.csv entry (type FACTION) — accessed reflectively so we don't
 *      hard-depend on a specific SettingsAPI overload.
 *   2. Faction relationships with the 7 major factions as inferred context (e.g.
 *      "Allied with Sindrian Diktat, Hostile to Hegemony").
 */
public final class FactionDescriptionHelper {

    private static final String[] MAJOR_FACTION_IDS = {
        "hegemony", "persean", "tritachyon", "sindrian_diktat",
        "luddic_church", "luddic_path", "pirates", "independent"
    };

    private FactionDescriptionHelper() {}

    /**
     * Returns a brief context block for the given faction, suitable for injecting into a
     * system prompt. Never throws.
     */
    public static String buildFactionContext(FactionAPI faction) {
        if (faction == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Faction: ").append(faction.getDisplayName())
              .append(" [id: ").append(faction.getId()).append("]");

            // Try to fetch the game's own description for this faction
            String desc = fetchGameDescription(faction.getId());
            if (desc != null && !desc.isEmpty()) {
                sb.append("\n").append(desc.trim());
            }

            // Relationship context vs major factions
            List<String> hostile = new ArrayList<String>();
            List<String> allied  = new ArrayList<String>();
            for (String mid : MAJOR_FACTION_IDS) {
                if (mid.equals(faction.getId())) continue;
                try {
                    FactionAPI other = Global.getSector().getFaction(mid);
                    if (other == null) continue;
                    RepLevel rel = faction.getRelationshipLevel(mid);
                    if (rel == null) continue;
                    if (rel.isAtBest(RepLevel.HOSTILE)) {
                        hostile.add(other.getDisplayName());
                    } else if (!rel.isAtBest(RepLevel.FAVORABLE)) {
                        allied.add(other.getDisplayName());
                    }
                } catch (Throwable ignored) {}
            }
            if (!hostile.isEmpty()) {
                sb.append("\nHostile toward: ").append(join(hostile));
            }
            if (!allied.isEmpty()) {
                sb.append("\nAllied/friendly with: ").append(join(allied));
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            try { return "Faction: " + faction.getDisplayName(); } catch (Throwable t2) { return ""; }
        }
    }

    /**
     * Looks up another faction by ID or display-name substring. Returns null if not found.
     * Used by GetFactionInfoAction when the LLM passes a faction name it knows.
     */
    public static FactionAPI findFaction(String query) {
        if (query == null || query.isEmpty()) return null;
        String q = query.trim().toLowerCase();
        try {
            // Exact ID match first
            FactionAPI byId = Global.getSector().getFaction(q);
            if (byId != null) return byId;
            // Display name substring match (case-insensitive)
            for (FactionAPI f : Global.getSector().getAllFactions()) {
                if (f == null) continue;
                String dn = f.getDisplayName();
                if (dn != null && dn.toLowerCase().contains(q)) return f;
                if (f.getId().contains(q)) return f;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Tries to fetch the game's own faction description from descriptions.csv.
     * Accesses via SettingsAPI.getDescription reflectively so it doesn't break
     * if the method signature changes between Starsector versions.
     */
    private static String fetchGameDescription(String factionId) {
        try {
            Object settings = Global.getSettings();
            if (settings == null) return null;
            // Try overload: getDescription(String id, Enum type)
            for (java.lang.reflect.Method m : settings.getClass().getMethods()) {
                if (!"getDescription".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || !pt[0].equals(String.class) || !pt[1].isEnum()) continue;
                // Find the FACTION enum constant in the parameter type
                Object factionEnum = null;
                try {
                    factionEnum = Enum.valueOf((Class<Enum>) pt[1], "FACTION");
                } catch (IllegalArgumentException ignored) {}
                if (factionEnum == null) continue;
                Object desc = m.invoke(settings, factionId, factionEnum);
                if (desc == null) return null;
                // Call getText1() or getText() on the Description object
                for (String getter : new String[]{"getText1", "getText"}) {
                    try {
                        java.lang.reflect.Method gm = desc.getClass().getMethod(getter);
                        Object text = gm.invoke(desc);
                        if (text instanceof String && !((String) text).isEmpty()) {
                            return (String) text;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
