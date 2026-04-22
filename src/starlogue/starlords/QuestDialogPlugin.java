package starlogue.starlords;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.missions.cb.BaseCustomBounty;
import com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission;
import com.fs.starfarer.api.impl.campaign.rulecmd.BeginMission;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import starlords.controllers.QuestController;
import starlords.person.Lord;
import starlords.ui.MissionPreviewIntelPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.fs.starfarer.api.impl.campaign.rulecmd.BeginMission.TEMP_MISSION_KEY;

/**
 * One-shot dialog plugin launched via StarlogueAPI.handoffToPlugin() from RequestQuestAction.
 * Replicates DialogAddon_attemptToAddRandomQuest logic, then immediately dismisses.
 */
public class QuestDialogPlugin implements InteractionDialogPlugin {

    private static final Logger log = Logger.getLogger(QuestDialogPlugin.class);
    private final Lord lord;

    public QuestDialogPlugin(Lord lord) {
        this.lord = lord;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        TextPanelAPI text = dialog.getTextPanel();
        CampaignFleetAPI lordFleet = (CampaignFleetAPI) dialog.getInteractionTarget();

        // Save and swap market reference (BeginMission needs the lord positioned at a base)
        MarketAPI savedFleetMarket = lordFleet != null ? lordFleet.getMarket() : null;
        MarketAPI savedLordMarket = lord.getLordAPI().getMarket();

        SectorEntityToken base = lord.getClosestBase();
        MarketAPI tempMarket = null;
        if (base != null) {
            tempMarket = base.getMarket();
        }
        if (tempMarket == null) {
            if (!Global.getSector().getEconomy().getMarketsCopy().isEmpty()) {
                tempMarket = Global.getSector().getEconomy().getMarketsCopy().get(0);
            }
        }

        boolean questGiven = false;
        if (tempMarket != null && !QuestController.isQuestGiven(lord)) {
            if (lordFleet != null) lordFleet.setMarket(tempMarket);
            lord.getLordAPI().setMarket(tempMarket);

            try {
                ArrayList<Misc.Token> params = new ArrayList<Misc.Token>();
                params.add(new Misc.Token(QuestController.getQuestId(lord), Misc.TokenType.LITERAL));
                params.add(new Misc.Token("false", Misc.TokenType.LITERAL));
                new BeginMission().execute("", dialog, params, new HashMap<String, MemoryAPI>());

                Object raw = Global.getSector().getMemoryWithoutUpdate().get(TEMP_MISSION_KEY);
                if (raw instanceof BaseHubMission) {
                    BaseHubMission mission = (BaseHubMission) raw;
                    if (!(mission instanceof BaseCustomBounty)) {
                        Global.getSector().getIntelManager().addIntel(new MissionPreviewIntelPlugin(mission));
                        text.addParagraph(lord.getLordAPI().getNameString()
                            + " outlines the task. Check your intel screen for the mission briefing.");
                        questGiven = true;
                    } else {
                        log.warn("Starlogue QuestDialogPlugin: mission is bounty type — no quest added");
                    }
                } else {
                    log.warn("Starlogue QuestDialogPlugin: mission null or unexpected type — no quest added");
                }
            } catch (Exception e) {
                log.error("Starlogue QuestDialogPlugin: BeginMission failed", e);
            } finally {
                if (lordFleet != null) lordFleet.setMarket(savedFleetMarket);
                lord.getLordAPI().setMarket(savedLordMarket);
            }
        }

        QuestController.setQuestGiven(lord, true);

        if (!questGiven) {
            text.addParagraph(lord.getLordAPI().getNameString()
                + " has nothing to offer right now.");
        }

        dialog.dismiss();
    }

    // ── Stub implementations (plugin dismisses in init, these never fire) ──

    @Override public void optionSelected(String text, Object optionData) {}
    @Override public void optionMousedOver(String text, Object optionData) {}
    @Override public void advance(float amount) {}
    @Override public void backFromEngagement(EngagementResultAPI result) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
