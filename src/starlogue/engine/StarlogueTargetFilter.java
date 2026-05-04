package starlogue.engine;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

/**
 * Hard blocks for campaign tokens that should never open a Starlogue channel
 * (salvageable derelicts, belt/terrain clutter, Nex-style asteroid fields, etc.).
 * Intentionally conservative: anything with a real colony market (size &gt; 0) is left to
 * {@code MarketAdminPlugin} / {@code PersonInteractionPlugin}.
 */
public final class StarlogueTargetFilter {

    private StarlogueTargetFilter() {}

    /** @return {@code true} if no Starlogue plugin may engage this token. */
    public static boolean isExcluded(SectorEntityToken entity) {
        if (entity == null) return true;

        MarketAPI m = entity.getMarket();
        if (m != null && m.getSize() > 0) return false;

        if (entity.hasTag(Tags.SALVAGEABLE)) return true;
        if (entity.hasTag("derelict")) return true;
        if (entity.hasTag("terrain")) return true;
        if (entity.hasTag("asteroid")) return true;
        if (entity.hasTag("belt_asteroids")) return true;

        if (entity instanceof CampaignFleetAPI) {
            CampaignFleetAPI f = (CampaignFleetAPI) entity;
            if (f.isPlayerFleet()) return false;
            if (f.hasTag(Tags.SALVAGEABLE) || f.hasTag("derelict")) return true;
        }

        String ct = safeLowerCustomType(entity);
        if (ct != null) {
            if (ct.contains("asteroid")) return true;
            if (ct.contains("astfield")) return true;
            if (ct.contains("asteroids")) return true;
            if (ct.contains("debris")) return true;
            if (ct.startsWith("nex_") && (ct.contains("asteroid")
                || ct.contains("astfield")
                || ct.contains("belt")
                || ct.contains("mining_field")
                || ct.contains("survey"))) return true;
        }

        return false;
    }

    private static String safeLowerCustomType(SectorEntityToken entity) {
        try {
            String t = entity.getCustomEntityType();
            if (t == null || t.isEmpty()) return null;
            return t.toLowerCase();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
