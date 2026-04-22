package starlogue.provider;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import java.util.List;
import java.util.Map;

public interface StarloguePlugin {
    boolean canEngage(SectorEntityToken entity);
    GameContext buildContext(SectorEntityToken entity);
    List<StarlogueAction> getActions(GameContext ctx);
    String getSystemPromptPreamble(GameContext ctx);

    // ── memoryMap-aware overloads (added in v0.1.1) ──────────────────────
    // Default impls delegate to the legacy single-arg methods so existing
    // external plugin implementations keep working without modification.

    default boolean canEngage(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return canEngage(entity);
    }

    default GameContext buildContext(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return buildContext(entity);
    }

    /**
     * Label for the dialog option that opens the Starlogue channel against this entity.
     * Plugins can override to provide a context-appropriate label
     * (e.g. "Hail the station...", "Query the system...").
     */
    default String getOptionLabel(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return "Open a channel...";
    }
}
