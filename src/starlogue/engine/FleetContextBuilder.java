package starlogue.engine;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.memory.MemoryEngine;

/**
 * Shared factory for the common fleet GameContext fields.
 *
 * <p>Both {@code FleetCaptainPlugin} and {@code StarLordPlugin} need the same
 * base context: entity, commander, faction refs, rep, fleet strength delta,
 * memory score, bluff credibility, and 30-day rep counters. Centralising this
 * here eliminates the duplication that previously existed between the two
 * plugins. Plugin-specific additions (lord data, context notes, etc.) are
 * appended by the calling plugin after {@link #buildBase} returns.
 */
public final class FleetContextBuilder {

    private FleetContextBuilder() {}

    /**
     * Populate all shared fleet fields on a freshly constructed {@link GameContext}.
     *
     * @param fleet the NPC fleet being interacted with
     * @return a GameContext with all common fields filled; caller appends extras
     */
    public static GameContext buildBase(CampaignFleetAPI fleet) {
        PersonAPI commander = fleet.getCommander();
        FactionAPI npcFaction = fleet.getFaction();
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();

        GameContext ctx = new GameContext();
        ctx.entity = fleet;
        ctx.person = commander;
        ctx.speakerName = commander.getNameString();
        ctx.fleet = fleet;
        ctx.npcFaction = npcFaction;
        ctx.playerFaction = playerFaction;
        ctx.repLevel = npcFaction.getRelationshipLevel(playerFaction.getId());
        ctx.repValue = npcFaction.getRelationship(playerFaction.getId());

        // Individual relationship: not directly on PersonAPI in this API version.
        // Use faction relationship as proxy.
        ctx.individualRel = ctx.repValue;

        ctx.npcFleetPoints = fleet.getFleetPoints();
        ctx.playerFleetPoints = playerFleet != null ? playerFleet.getFleetPoints() : 0;
        int denom = Math.max(ctx.npcFleetPoints, ctx.playerFleetPoints);
        ctx.strengthDelta = denom == 0 ? 0f
            : (float) (ctx.npcFleetPoints - ctx.playerFleetPoints) / denom;

        ctx.memoryScore = MemoryEngine.getScore(commander);
        ctx.playerHasBluffCredibility = MemoryEngine.getFactionScore(npcFaction) > -20f;

        if (playerFleet != null) {
            com.fs.starfarer.api.campaign.rules.MemoryAPI playerMem = playerFleet.getMemory();
            ctx.repGained30d = playerMem.contains("$starlogue_rep_gained_30d")
                ? playerMem.getFloat("$starlogue_rep_gained_30d") : 0f;
            ctx.repLost30d = playerMem.contains("$starlogue_rep_lost_30d")
                ? playerMem.getFloat("$starlogue_rep_lost_30d") : 0f;
        }

        FleetContextHelper.enrichPlayerSide(ctx);

        return ctx;
    }
}
