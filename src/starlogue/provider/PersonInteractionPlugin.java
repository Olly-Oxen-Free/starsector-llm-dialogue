package starlogue.provider;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import starlogue.action.StarlogueAction;
import starlogue.action.fleet.AdjustFactionRelAction;
import starlogue.action.fleet.AdjustIndividualRelAction;
import starlogue.action.fleet.GetFactionInfoAction;
import starlogue.action.fleet.InspectFleetAction;
import starlogue.action.fleet.InspectShipDetailAction;
import starlogue.action.fleet.IntelMarkMemoryAction;
import starlogue.action.fleet.IntelShareTipAction;
import starlogue.action.fleet.PersonSetNoteAction;
import starlogue.action.fleet.ShareIntelAction;
import starlogue.engine.FleetContextHelper;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.personality.PersonalityComposer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles any interaction where a {@link PersonAPI} can be resolved but the entity
 * is not a fleet (e.g. contacts, bar NPCs, comm-link conversations, officers).
 *
 * <p>Looks up the person from the rules engine memoryMap (<code>$person</code>),
 * from the entity's own memory, or from the entity itself when it is bound to a person.
 */
public class PersonInteractionPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        return canEngage(entity, null);
    }

    @Override
    public boolean canEngage(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        PersonAPI p = resolvePerson(entity, memoryMap);
        if (p == null) return false;
        // Don't duplicate a Starlogue "open channel" to the colony admin when the player
        // already has peacetime comm / market access.
        if (isFriendlyColonyAdminForSameTarget(p, entity)) return false;
        return true;
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        return buildContext(entity, null);
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        PersonAPI person = resolvePerson(entity, memoryMap);
        FactionAPI npcFaction = resolveFaction(person, entity);
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();

        GameContext ctx = new GameContext();
        ctx.entity = entity;
        ctx.person = person;
        ctx.speakerName = person.getNameString();
        ctx.fleet = null;
        ctx.npcFaction = npcFaction;
        ctx.playerFaction = playerFaction;

        if (npcFaction != null && playerFaction != null) {
            ctx.repLevel = npcFaction.getRelationshipLevel(playerFaction.getId());
            ctx.repValue = npcFaction.getRelationship(playerFaction.getId());
        }
        ctx.individualRel = ctx.repValue;

        // No fleets involved — leave strength fields at default (equal footing).
        ctx.npcFleetPoints = 0;
        ctx.playerFleetPoints = 0;
        ctx.strengthDelta = 0f;

        ctx.memoryScore = MemoryEngine.getScore(person);
        ctx.playerHasBluffCredibility = npcFaction != null
            ? MemoryEngine.getFactionScore(npcFaction) > -20f
            : true;

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet != null) {
            MemoryAPI playerMem = playerFleet.getMemory();
            ctx.repGained30d = playerMem.contains("$starlogue_rep_gained_30d")
                ? playerMem.getFloat("$starlogue_rep_gained_30d") : 0f;
            ctx.repLost30d = playerMem.contains("$starlogue_rep_lost_30d")
                ? playerMem.getFloat("$starlogue_rep_lost_30d") : 0f;
        }

        FleetContextHelper.enrichPlayerSide(ctx);

        return ctx;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        // Persons (contacts, bar, officers) don't fight you right now — skip combat actions.
        // Keep relationship adjustments + intel sharing which are thematic for a conversation.
        List<StarlogueAction> actions = new ArrayList<StarlogueAction>();
        actions.add(new ShareIntelAction());
        actions.add(new IntelShareTipAction());
        actions.add(new IntelMarkMemoryAction());
        actions.add(new InspectFleetAction());
        actions.add(new InspectShipDetailAction());
        actions.add(new PersonSetNoteAction());
        actions.add(new AdjustIndividualRelAction());
        actions.add(new AdjustFactionRelAction());
        actions.add(new GetFactionInfoAction());
        return actions;
    }

    @Override
    public String getSystemPromptPreamble(GameContext ctx) {
        return PersonalityComposer.compose(ctx);
    }

    @Override
    public String getOptionLabel(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        return "Open a channel...";
    }

    private static boolean isFriendlyColonyAdminForSameTarget(PersonAPI person, SectorEntityToken entity) {
        if (entity == null) return false;
        MarketAPI m = entity.getMarket();
        if (m == null || m.getAdmin() == null) return false;
        if (m.getAdmin() != person) return false;
        FactionAPI pl = Global.getSector().getPlayerFaction();
        FactionAPI owner = m.getFaction();
        if (pl == null || owner == null) return false;
        return !owner.isHostileTo(pl);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Attempts to resolve a PersonAPI from (in order):
     * 1. the rules memoryMap's <code>$person</code>
     * 2. the entity's own memory's <code>$person</code>
     * 3. the entity's market admin (ONLY as a soft fallback when no person found; the
     *    MarketAdminPlugin will normally have matched first, but we guard here too)
     */
    private static PersonAPI resolvePerson(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        if (memoryMap != null) {
            PersonAPI p = extractPerson(memoryMap.get("local"));
            if (p != null) return p;
            p = extractPerson(memoryMap.get("global"));
            if (p != null) return p;
            for (MemoryAPI bag : memoryMap.values()) {
                p = extractPerson(bag);
                if (p != null) return p;
            }
        }
        if (entity != null) {
            PersonAPI p = extractPerson(entity.getMemoryWithoutUpdate());
            if (p != null) return p;
        }
        return null;
    }

    private static PersonAPI extractPerson(MemoryAPI mem) {
        if (mem == null) return null;
        if (mem.contains("$person")) {
            Object v = mem.get("$person");
            if (v instanceof PersonAPI) return (PersonAPI) v;
        }
        // Some Nex / directory / custom dialogs stash the contact under alternate keys.
        String[] altKeys = new String[] {
            "$contactPerson", "$activePerson", "$talkingPerson", "$commPerson", "$barPerson"
        };
        for (String k : altKeys) {
            if (!mem.contains(k)) continue;
            Object v = mem.get(k);
            if (v instanceof PersonAPI) return (PersonAPI) v;
        }
        return null;
    }

    private static FactionAPI resolveFaction(PersonAPI person, SectorEntityToken entity) {
        if (person != null && person.getFaction() != null) return person.getFaction();
        if (entity != null && entity.getFaction() != null) return entity.getFaction();
        if (entity != null) {
            MarketAPI market = entity.getMarket();
            if (market != null && market.getFaction() != null) return market.getFaction();
        }
        return Global.getSector().getFaction(Factions.INDEPENDENT);
    }
}
