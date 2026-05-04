package starlogue.engine;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import starlogue.config.LunaSettingHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Line-of-sight fleet summaries for LLM prompts and inspection tools.
 * Base snapshots intentionally omit d-mods / per-weapon detail — use {@link #formatShipDetail} for that.
 */
public final class FleetSnapshotFormatter {

    private FleetSnapshotFormatter() {}

    public static int maxShipsDefault() {
        return LunaSettingHelper.getInt("starlogue_fleet_sight_max_ships", 10);
    }

    public static String formatSightingBlock(CampaignFleetAPI npc, CampaignFleetAPI player, int maxShips) {
        StringBuilder sb = new StringBuilder();
        sb.append("VISUAL_SIGHTING_REPORT (line of sight — no d-mod or weapon readout):\n");
        if (npc != null) {
            sb.append("NPC fleet:\n").append(formatFleetBrief(npc, maxShips)).append("\n");
        }
        if (player != null) {
            sb.append("Player fleet:\n").append(formatFleetBrief(player, maxShips)).append("\n");
        }
        try {
            if (player != null) {
                sb.append("Player transponder: ").append(player.isTransponderOn() ? "ON" : "OFF").append("\n");
                String sig = buildTechSignature(player);
                if (sig != null && !sig.isEmpty()) {
                    sb.append("Player fleet hull-style hint: ").append(sig).append("\n");
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    public static String formatFleetBrief(CampaignFleetAPI fleet, int maxShips) {
        if (fleet == null) return "(no fleet)";
        if (maxShips < 1) maxShips = 8;
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        List<FleetMemberAPI> ships = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI m : members) {
            if (m == null) continue;
            if (m.getType() != FleetMemberType.SHIP) continue;
            if (m.isStation()) continue;
            ships.add(m);
        }
        Collections.sort(ships, new Comparator<FleetMemberAPI>() {
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });
        int fighters = 0;
        for (FleetMemberAPI m : members) {
            if (m != null && m.isFighterWing()) fighters++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Approx ").append(fleet.getFleetPoints()).append(" FP; ")
            .append(ships.size()).append(" ships; ")
            .append(fighters).append(" fighter wings.\n");
        int n = 0;
        for (FleetMemberAPI m : ships) {
            if (n >= maxShips) {
                sb.append("… +").append(Math.max(0, ships.size() - maxShips)).append(" more hulls\n");
                break;
            }
            String hull = m.getHullSpec() != null ? m.getHullSpec().getHullId() : "?";
            sb.append("- \"").append(m.getShipName()).append("\" (").append(hull).append(", ")
                .append((int) m.getDeploymentPointsCost()).append(" DP)\n");
            n++;
        }
        return sb.toString().trim();
    }

    /**
     * Rough hull-origin hint for disguise / mismatch prompts (not proof of identity).
     */
    public static String buildTechSignature(CampaignFleetAPI fleet) {
        if (fleet == null) return "";
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m == null || m.getType() != FleetMemberType.SHIP || m.isStation()) continue;
            String fid = "unknown";
            try {
                if (m.getHullSpec() != null && m.getHullSpec().getManufacturer() != null) {
                    fid = m.getHullSpec().getManufacturer();
                } else if (m.getHullSpec() != null && m.getHullSpec().getHullName() != null) {
                    fid = m.getHullSpec().getHullName();
                }
            } catch (Throwable ignored) { }
            Integer c = counts.get(fid);
            counts.put(fid, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) return "";
        List<java.util.Map.Entry<String, Integer>> list = new ArrayList<java.util.Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(list, new Comparator<java.util.Map.Entry<String, Integer>>() {
            public int compare(java.util.Map.Entry<String, Integer> a, java.util.Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 4; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getKey()).append("×").append(list.get(i).getValue());
        }
        return sb.toString();
    }

    public static float computeMismatchHint(CampaignFleetAPI playerFleet, FactionAPI officialFaction) {
        if (playerFleet == null || officialFaction == null) return 0f;
        java.util.Map<String, Float> hullFreq = null;
        try {
            hullFreq = officialFaction.getHullFrequency();
        } catch (Throwable ignored) { }
        if (hullFreq == null || hullFreq.isEmpty()) return 0f;
        int total = 0;
        int uncommon = 0;
        for (FleetMemberAPI m : playerFleet.getFleetData().getMembersListCopy()) {
            if (m == null || m.getType() != FleetMemberType.SHIP || m.isStation()) continue;
            total++;
            String hid = m.getHullId();
            if (hid == null) continue;
            try {
                Float w = hullFreq.get(hid);
                if (w == null || w < 0.0005f) uncommon++;
            } catch (Throwable ignored) {
                uncommon++;
            }
        }
        if (total == 0) return 0f;
        return Math.min(1f, (float) uncommon / (float) total);
    }

    public static String formatShipDetail(CampaignFleetAPI fleet, String selector) {
        if (fleet == null || selector == null || selector.trim().isEmpty()) return "(no ship selected)";
        String sel = selector.trim().toLowerCase();
        FleetMemberAPI found = null;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m == null || m.getType() != FleetMemberType.SHIP) continue;
            String hid = m.getHullSpec() != null ? m.getHullSpec().getHullId() : "";
            if (hid != null && hid.toLowerCase().equals(sel)) {
                found = m;
                break;
            }
        }
        if (found == null) {
            for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (m == null || m.getType() != FleetMemberType.SHIP) continue;
                if (m.getShipName() != null && m.getShipName().toLowerCase().contains(sel)) {
                    found = m;
                    break;
                }
            }
        }
        if (found == null) return "No ship matched \"" + selector + "\".";
        StringBuilder sb = new StringBuilder();
        sb.append("Ship: \"").append(found.getShipName()).append("\" hull=").append(found.getHullSpec() != null ? found.getHullSpec().getHullId() : "?")
            .append(" DP=").append((int) found.getDeploymentPointsCost()).append("\n");
        try {
            if (found.getVariant() != null) {
                sb.append("Hullmods: ").append(found.getVariant().getHullMods()).append("\n");
                sb.append("Perma-mods / d-mods: ").append(found.getVariant().getPermaMods()).append("\n");
            }
        } catch (Throwable t) {
            sb.append("(variant detail unavailable)\n");
        }
        return sb.toString().trim();
    }
}
