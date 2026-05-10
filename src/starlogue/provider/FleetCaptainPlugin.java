package starlogue.provider;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import starlogue.action.StarlogueAction;
import starlogue.action.fleet.DefaultFleetActions;
import starlogue.engine.FleetContextBuilder;
import starlogue.engine.GameContext;
import starlogue.personality.PersonalityComposer;
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
        return DefaultFleetActions.list();
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
