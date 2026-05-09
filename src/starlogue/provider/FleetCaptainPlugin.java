package starlogue.provider;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import starlogue.action.StarlogueAction;
import starlogue.action.fleet.*;
import starlogue.engine.FleetContextBuilder;
import starlogue.engine.GameContext;
import starlogue.personality.PersonalityComposer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FleetCaptainPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        if (!(entity instanceof CampaignFleetAPI)) return false;
        CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
        return !fleet.isPlayerFleet()
            && fleet.getCommander() != null
            && fleet.getFaction() != null;
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        return FleetContextBuilder.buildBase((CampaignFleetAPI) entity);
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        List<StarlogueAction> actions = new ArrayList<StarlogueAction>();
        // Combat resolution
        actions.add(new AttackAction());
        actions.add(new RetreatAction());
        actions.add(new StandDownAction());
        actions.add(new WarnOffAction());
        // Negotiation / transfers
        actions.add(new ExtortAction());
        actions.add(new PayTributeAction());
        actions.add(new TradeOfferAction());
        actions.add(new TransferCreditsAction());
        actions.add(new TransferSuppliesAction());
        actions.add(new TransferFuelAction());
        actions.add(new RansomCrewAction());
        // Diplomatic
        actions.add(new ShareIntelAction());
        actions.add(new RecruitAllyAction());
        actions.add(new CeasefireAction());
        // Relationship
        actions.add(new AdjustIndividualRelAction());
        actions.add(new AdjustFactionRelAction());
        actions.add(new InspectFleetAction());
        actions.add(new InspectShipDetailAction());
        actions.add(new BluffIdentityAction());
        actions.add(new ChallengeDisguiseAction());
        actions.add(new ExposeInconsistencyAction());
        actions.add(new IntelShareTipAction());
        actions.add(new IntelMarkMemoryAction());
        actions.add(new PersonSetNoteAction());
        actions.add(new FleetEscortPlayerAction());
        actions.add(new FleetPatrolHereAction());
        actions.add(new FleetDisengageSoftAction());
        actions.add(new FleetHarassAction());
        actions.add(new TransferMarinesAction());
        actions.add(new TransferCommodityBundleAction());
        actions.add(new GetFactionInfoAction());
        return actions;
    }

    @Override
    public String getSystemPromptPreamble(GameContext ctx) {
        return PersonalityComposer.compose(ctx);
    }

    @Override
    public String getOptionLabel(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        if (!(entity instanceof CampaignFleetAPI)) {
            return "Hail the fleet...";
        }
        CampaignFleetAPI f = (CampaignFleetAPI) entity;
        FactionAPI fac = f.getFaction();
        String n = "unknown";
        if (fac != null) {
            n = fac.getDisplayName();
            if (n == null || n.isEmpty()) n = fac.getId();
        }
        return "Hail the " + n + " fleet...";
    }
}
