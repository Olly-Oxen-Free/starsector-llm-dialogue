package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.FactionAPI;
import starlogue.action.StarlogueAction;
import starlogue.engine.FactionDescriptionHelper;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-demand faction lookup.  The LLM can call this when it needs to know who a faction is,
 * what they stand for, or how they relate to other major powers — without injecting all that
 * data into every prompt turn.
 *
 * <p>The result surfaces as a {@link #narrativeNote()} injected into the conversation
 * text, so the LLM reads it on its next turn.
 *
 * <p>Works for vanilla AND modded factions: data comes from the live game API rather than
 * hand-authored profiles.
 */
public class GetFactionInfoAction implements StarlogueAction {

    private String lastNote;

    @Override
    public String getId() { return "get_faction_info"; }

    @Override
    public String getDescription() {
        return "Look up live game data for any faction: display name, ideology description, "
             + "and relationships with major powers. Use whenever you are unsure who a faction is, "
             + "what they stand for, or how they relate to others. "
             + "faction_id_or_name: the faction's ID (e.g. 'lions_guard', 'sindrian_diktat') "
             + "or any part of their display name (e.g. 'Lion' or 'Diktat').";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("faction_id_or_name", "string");
        return p;
    }

    @Override
    public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return true; // always available — pure info, no game-state change
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        Object raw = args.get("faction_id_or_name");
        if (raw == null) {
            lastNote = "[get_faction_info: no faction_id_or_name provided]";
            return;
        }
        String query = String.valueOf(raw).trim();
        FactionAPI faction = FactionDescriptionHelper.findFaction(query);
        if (faction == null) {
            lastNote = "[Faction not found: \"" + query + "\". "
                + "Try the faction's ID (lowercase, underscores) or a unique part of their display name.]";
            return;
        }
        lastNote = "[FACTION LOOKUP]\n" + FactionDescriptionHelper.buildFactionContext(faction);
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
