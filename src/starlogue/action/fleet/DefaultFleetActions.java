package starlogue.action.fleet;

import starlogue.action.StarlogueAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for the fleet-action list shared between
 * {@link starlogue.provider.FleetCaptainPlugin} and
 * {@link starlogue.starlords.StarLordPlugin}.
 *
 * <p>StarLordPlugin calls {@link #list()} and appends its lord-specific actions.
 * FleetCaptainPlugin calls {@link #list()} and returns it directly.
 */
public final class DefaultFleetActions {

    private DefaultFleetActions() {}

    /**
     * Returns a mutable list containing all default fleet actions in canonical order.
     * Callers may add extra actions to the returned list.
     */
    public static List<StarlogueAction> list() {
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
}
