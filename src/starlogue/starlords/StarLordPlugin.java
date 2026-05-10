package starlogue.starlords;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.action.StarlogueAction;
import starlogue.engine.FleetContextBuilder;
import starlogue.engine.GameContext;
import starlogue.personality.PersonalityComposer;
import starlogue.provider.StarloguePlugin;
import starlords.controllers.FiefController;
import starlords.controllers.LordController;
import starlords.person.Lord;
import starlogue.starlords.FiefValueCalculator;
import starlogue.starlords.action.*;
import java.util.ArrayList;
import java.util.List;

/**
 * StarloguePlugin for Star Lord fleet commanders.
 * Detected before FleetCaptainPlugin, so lords get the richer lord tier.
 */
public class StarLordPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        if (!(entity instanceof CampaignFleetAPI)) return false;
        CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
        if (fleet.isPlayerFleet()) return false;
        PersonAPI commander = fleet.getCommander();
        if (commander == null) return false;
        return LordController.getLordById(commander.getId()) != null;
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
        Lord lord = LordController.getLordById(fleet.getCommander().getId());

        // Shared fleet fields via builder; lord-specific additions appended below.
        GameContext ctx = FleetContextBuilder.buildBase(fleet);
        ctx.isStarLord = true;
        ctx.lordData = lord;

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();

        // Lord-specific context notes
        int playerRel = lord.getPlayerRel();
        if (playerRel > 30) {
            ctx.contextNotes.add("This lord has a positive personal history with you (player rel: " + playerRel + ").");
        } else if (playerRel < -30) {
            ctx.contextNotes.add("This lord has a negative personal history with you (player rel: " + playerRel + ").");
        }
        if (lord.isPlayerDirected()) {
            ctx.contextNotes.add("This lord has previously pledged to fight alongside you.");
        }
        if (lord.wantsToDefect()) {
            ctx.contextNotes.add("This lord is considering defecting from their current faction.");
        }
        int ranking = lord.getRanking();
        ctx.contextNotes.add("Lord ranking within their faction: " + ranking
            + " (1 = highest). " + (ranking <= 3 ? "A senior figure." : "Mid-tier lord."));

        // Per-fief: name, size, and required payment at current opinion
        if (!lord.getFiefs().isEmpty()) {
            for (SectorEntityToken token : lord.getFiefs()) {
                MarketAPI m = token.getMarket();
                if (m == null) continue;
                float required = FiefValueCalculator.computeRequiredPayment(
                    m, ctx.memoryScore, lord.getPlayerRel());
                float monthly = FiefController.getTax(m) + FiefController.getTrade(m);
                ctx.contextNotes.add("Your fief \"" + m.getName() + "\" (size " + m.getSize()
                    + ", ~" + FiefValueCalculator.formatK(monthly) + "cr/month income): "
                    + "granting it requires ~" + FiefValueCalculator.formatK(required)
                    + "cr in total payment at your current opinion of the player.");
            }
        }

        // Player's fiefs (exchange targets)
        Lord playerLord = LordController.getPlayerLord();
        if (playerLord != null && !playerLord.getFiefs().isEmpty()) {
            StringBuilder sb = new StringBuilder("Player's fiefs (could be given in exchange): ");
            for (SectorEntityToken token : playerLord.getFiefs()) {
                MarketAPI m = token.getMarket();
                if (m == null) continue;
                float val = FiefValueCalculator.computeBaseValue(m);
                sb.append('"').append(m.getName()).append("\" (size ").append(m.getSize())
                  .append(", worth ~").append(FiefValueCalculator.formatK(val)).append("cr), ");
            }
            ctx.contextNotes.add(sb.toString().replaceAll(", $", "."));
        }

        // Player's credits
        if (playerFleet != null) {
            float credits = playerFleet.getCargo().getCredits().get();
            ctx.contextNotes.add("Player's credits: ~" + FiefValueCalculator.formatK(credits) + "cr.");
        }

        // Player's fleet ships available for payment
        if (playerFleet != null) {
            String fleetNote = FiefValueCalculator.buildFleetNote(playerFleet, 8);
            if (fleetNote != null) ctx.contextNotes.add(fleetNote);
        }

        // Player's notable cargo available for payment
        if (playerFleet != null) {
            String cargoNote = FiefValueCalculator.buildCargoNote(
                playerFleet.getCargo(), 10_000f);
            if (cargoNote != null) ctx.contextNotes.add(cargoNote);
        }

        // Proposal context (Pass 1)
        starlords.faction.LawProposal proposal =
            starlords.controllers.PoliticsController.getCurrProposal(lord.getFaction());
        if (proposal != null) {
            ctx.contextNotes.add("Current council proposal: " + proposal.getSummary()
                + " (law type: " + (proposal.getLaw() != null ? proposal.getLaw().name() : "unknown") + ")");
            ctx.contextNotes.add("Proposal supporters: " + proposal.getSupporters().size()
                + ", opposers: " + proposal.getOpposers().size());
            String lordId = lord.getLordAPI().getId();
            if (proposal.getPledgedFor().contains(lordId)) {
                ctx.contextNotes.add("You have already pledged to SUPPORT this proposal.");
            } else if (proposal.getPledgedAgainst().contains(lordId)) {
                ctx.contextNotes.add("You have already pledged to OPPOSE this proposal.");
            }
        }
        if (playerLord != null && playerLord.getFaction().equals(lord.getFaction())) {
            ctx.contextNotes.add("The player is a lord in your faction.");
        }

        // Defection context (Pass 2)
        int defectionChance = starlords.util.DefectionUtils.getAutoBetrayalChance(lord);
        boolean defectionEligible = defectionChance > 0
            && starlords.controllers.RequestController.getCurrentDefectionRequest(lord) == null;
        if (defectionEligible) {
            com.fs.starfarer.api.campaign.FactionAPI preferred =
                starlords.util.DefectionUtils.getLordPreferredFaction(lord, false);
            if (preferred != null && !preferred.equals(lord.getFaction())) {
                ctx.contextNotes.add("You are privately considering defecting to "
                    + preferred.getDisplayName() + ". Keep this hidden unless trust is established.");
            }
        }

        // Prisoner context (Pass 2)
        if (playerLord != null
                && lord.getPrisoners().contains(playerLord.getLordAPI().getId())) {
            ctx.contextNotes.add("The player's lord is currently your prisoner.");
        }

        // Quest context (Pass 3)
        boolean questAvailable = !starlords.controllers.QuestController.isQuestGiven(lord);
        ctx.contextNotes.add(questAvailable
            ? "This lord has a mission available for you."
            : "This lord has no mission to offer at this time.");

        return ctx;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        // Common fleet actions shared with FleetCaptainPlugin
        List<StarlogueAction> actions = starlogue.action.fleet.DefaultFleetActions.list();
        // Star Lord exclusive
        actions.add(new PledgeAllianceAction());
        actions.add(new SwayLordAction());
        actions.add(new ModifyLordRelationAction());
        actions.add(new ModifyLordLoyaltyAction());
        actions.add(new DeclareRivalryAction());
        actions.add(new GrantFiefAction());
        actions.add(new ChallengeTournamentAction());
        // Pass 1 — Council
        actions.add(new PledgeForProposalAction());
        actions.add(new PledgeAgainstProposalAction());
        actions.add(new VetoProposalAction());
        actions.add(new ForcePassProposalAction());
        // Pass 2 — Defection & Prisoner
        actions.add(new RevealDefectionIntentAction());
        actions.add(new PerformDefectionAction());
        actions.add(new ReleasePrisonerAction());
        // Pass 3 — Quest
        actions.add(new RequestQuestAction());
        return actions;
    }

    @Override
    public String getSystemPromptPreamble(GameContext ctx) {
        return PersonalityComposer.compose(ctx);
    }
}
