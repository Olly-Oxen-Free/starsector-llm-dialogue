package starlogue.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import starlogue.debug.DebugSessionLog;

/**
 * Some flows (e.g. bar follow-up) leave {@link InteractionDialogAPI#getInteractionTarget()} null
 * even though the player is still at a market; the player fleet may still have a
 * {@link CampaignFleetAPI#getInteractionTarget()} (planet/station).
 */
public final class InteractionTargetResolver {

    private InteractionTargetResolver() {}

    public static SectorEntityToken resolve(InteractionDialogAPI dialog) {
        if (dialog == null) return null;
        SectorEntityToken t = null;
        try {
            t = dialog.getInteractionTarget();
        } catch (Throwable ignored) { }
        if (t != null) return t;

        CampaignFleetAPI pf = null;
        try {
            pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
            if (pf == null) return null;
            t = pf.getInteractionTarget();
            if (t != null) {
                // #region agent log
                DebugSessionLog.log("H_NULL_TARGET", "InteractionTargetResolver.resolve",
                    "using player fleet interaction target (dialog.getInteractionTarget was null)",
                    "{\"fleetTargetClass\":\"" + t.getClass().getSimpleName() + "\"}");
                // #endregion
                return t;
            }
        } catch (Throwable ignored) { }

        // Bar / comm directory / nested market UIs often leave dialog + fleet interaction targets null
        // even though the fleet is docked at a colony — use orbit focus when it carries a market.
        try {
            if (pf != null) {
                SectorEntityToken focus = pf.getOrbitFocus();
                if (focus != null && focus.getMarket() != null) {
                    // #region agent log
                    try {
                        DebugSessionLog.log("H_NULL_TARGET", "InteractionTargetResolver.resolve",
                            "using player fleet orbitFocus (market present)",
                            "{\"focus\":\"" + focus.getName() + "\"}");
                    } catch (Throwable ignore) { }
                    // #endregion
                    return focus;
                }
            }
        } catch (Throwable ignored) { }

        return null;
    }
}
