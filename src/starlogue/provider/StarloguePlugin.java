package starlogue.provider;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import java.util.List;

public interface StarloguePlugin {
    boolean canEngage(SectorEntityToken entity);
    GameContext buildContext(SectorEntityToken entity);
    List<StarlogueAction> getActions(GameContext ctx);
    String getSystemPromptPreamble(GameContext ctx);
}
