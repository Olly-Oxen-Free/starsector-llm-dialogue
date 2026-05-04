package starlogue.engine;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;

/** Injects player-fleet visibility hints for fleet encounters and transponder/bluff logic. */
public final class FleetContextHelper {

    private FleetContextHelper() {}

    public static void enrichPlayerSide(GameContext ctx) {
        CampaignFleetAPI playerFleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (playerFleet == null) return;
        try {
            ctx.playerTransponderOn = playerFleet.isTransponderOn();
        } catch (Throwable t) {
            ctx.playerTransponderOn = true;
        }
        ctx.fleetSignatureSummary = FleetSnapshotFormatter.buildTechSignature(playerFleet);
        if (ctx.playerFaction != null) {
            ctx.fleetSignatureMismatchHint = FleetSnapshotFormatter.computeMismatchHint(playerFleet, ctx.playerFaction);
        }
        if (ctx.fleetSignatureSummary != null && !ctx.fleetSignatureSummary.isEmpty()) {
            ctx.contextNotes.add("PLAYER FLEET SIGNATURE (hull styles / manufacturers): " + ctx.fleetSignatureSummary);
        }
        if (ctx.fleetSignatureMismatchHint > 0.35f) {
            ctx.contextNotes.add("VISUAL ANOMALY: many hulls do not match the player's official faction markings — cover stories may not hold up.");
        }
    }
}
