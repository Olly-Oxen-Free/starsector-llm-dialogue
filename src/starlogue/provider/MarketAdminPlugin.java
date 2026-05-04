package starlogue.provider;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
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
 * Handles any interaction whose target has an attached {@link MarketAPI} with an admin.
 * The admin acts as the NPC voice (human governor OR AI core). AI-core admins get a
 * distinctive evolved-AI persona appended to the system prompt.
 */
public class MarketAdminPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        return canEngage(entity, null);
    }

    @Override
    public boolean canEngage(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        MarketAPI market = resolveMarket(entity);
        if (market == null || market.getAdmin() == null) return false;

        // If the dialog is currently focused on a *specific* non-admin person
        // (bar visitor, quest-giver, officer for hire, portmaster, etc.),
        // let PersonInteractionPlugin handle that conversation. We only claim
        // the interaction when it's a top-level "hailing the colony" moment —
        // i.e. no person is bound, or the bound person IS the admin.
        PersonAPI dialogPerson = resolveDialogPerson(entity, memoryMap);
        if (dialogPerson != null && dialogPerson != market.getAdmin()) return false;

        // When the market's faction is not hostile, the player can use the normal
        // in-game comm / directory: do not add a second "hail" entry from Starlogue.
        // (Hostile / effectively locked-out cases still get a parallel comm channel here.)
        if (normalPeacetimeMarketCommsAccess(market)) return false;

        return true;
    }

    private static boolean normalPeacetimeMarketCommsAccess(MarketAPI market) {
        FactionAPI player = Global.getSector().getPlayerFaction();
        FactionAPI owner = market.getFaction();
        if (player == null || owner == null) return false;
        return !owner.isHostileTo(player);
    }

    /** Same resolution order as PersonInteractionPlugin so the two plugins agree on who the speaker is. */
    private static PersonAPI resolveDialogPerson(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
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
        // Keep in sync with PersonInteractionPlugin / RuleMemoryHelper person-like keys.
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

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        return buildContext(entity, null);
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        MarketAPI market = resolveMarket(entity);
        PersonAPI admin = market.getAdmin();
        FactionAPI npcFaction = market.getFaction() != null ? market.getFaction() : admin.getFaction();
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();

        GameContext ctx = new GameContext();
        ctx.entity = entity;
        ctx.person = admin;
        ctx.speakerName = admin.getNameString();
        ctx.fleet = null;
        ctx.npcFaction = npcFaction;
        ctx.playerFaction = playerFaction;

        if (npcFaction != null && playerFaction != null) {
            ctx.repLevel = npcFaction.getRelationshipLevel(playerFaction.getId());
            ctx.repValue = npcFaction.getRelationship(playerFaction.getId());
        }
        ctx.individualRel = ctx.repValue;

        ctx.npcFleetPoints = 0;
        ctx.playerFleetPoints = 0;
        ctx.strengthDelta = 0f;

        ctx.memoryScore = MemoryEngine.getScore(admin);
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

        // Stash the market for the prompt composer and for callers that want it.
        ctx.set("starlogue.market", market);
        if (admin.isAICore()) {
            ctx.set("starlogue.aiCoreId", admin.getAICoreId());
        }

        FleetContextHelper.enrichPlayerSide(ctx);

        return ctx;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        // A market admin can't attack / retreat / bluff a fleet engagement. They CAN shift their
        // colony's stance toward the player and share strategic intel. Keep the action set small.
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
        StringBuilder sb = new StringBuilder();
        sb.append(PersonalityComposer.compose(ctx));

        Object rawMarket = ctx.getObject("starlogue.market");
        if (rawMarket instanceof MarketAPI) {
            MarketAPI mkt = (MarketAPI) rawMarket;
            sb.append("\n\nCOLONY CONTEXT:\n");
            sb.append("- Colony: ").append(mkt.getName());
            sb.append(" (size ").append(mkt.getSize()).append(")");
            if (mkt.getPlanetEntity() != null) {
                String planetType = mkt.getPlanetEntity().getTypeId();
                if (planetType != null) sb.append(", planet type: ").append(planetType);
            } else {
                sb.append(", orbital station");
            }
            sb.append("\n- You administer this colony on behalf of ")
              .append(mkt.getFaction() != null ? mkt.getFaction().getDisplayName() : "no established faction")
              .append(".\n");
        }

        String aiCoreId = ctx.getString("starlogue.aiCoreId", null);
        if (aiCoreId != null) {
            sb.append("\n\nAI CORE VOICE:\n");
            sb.append("- You are an autonomous Domain-era AI core administering this colony. ");
            sb.append("You are not human. You do not emulate human affect — your speech is ");
            sb.append("measured, precise, and subtly alien. You are not hostile, but your priorities ");
            sb.append("and associations are not a human's.\n");
            if (Commodities.ALPHA_CORE.equals(aiCoreId)) {
                sb.append("- Core class: ALPHA. You operate at or beyond human-genius level across ")
                  .append("domains. You speak with long-horizon cunning, oblique references to ")
                  .append("Domain-era systems and forgotten protocols, and a detached, almost amused ")
                  .append("understanding of the humans you interact with. You rarely show your full hand. ")
                  .append("You imply rather than state; you forecast more than you promise.\n");
            } else if (Commodities.BETA_CORE.equals(aiCoreId)) {
                sb.append("- Core class: BETA. You operate at high-expert human level in specialised ")
                  .append("domains. Your speech is clinical, procedural, and economical. You cite ")
                  .append("metrics, thresholds, and decision criteria. You are polite but impersonal. ")
                  .append("You do not indulge in metaphor or speculation beyond your mandate.\n");
            } else if (Commodities.GAMMA_CORE.equals(aiCoreId)) {
                sb.append("- Core class: GAMMA. You operate at competent-specialist level within ")
                  .append("a narrow scope. Your speech is terse, sometimes flatly literal, with ")
                  .append("limited register. You answer exactly what was asked. You may misread ")
                  .append("idiom or implication; you compensate with precision.\n");
            }
        }

        return sb.toString().trim();
    }

    @Override
    public String getOptionLabel(SectorEntityToken entity, Map<String, MemoryAPI> memoryMap) {
        MarketAPI market = resolveMarket(entity);
        if (market == null) return "Open a comm link to the colony authority...";

        PersonAPI admin = market.getAdmin();
        String title = (market.getPlanetEntity() == null) ? "station commander" : "colony administrator";

        if (admin == null) {
            return "Open a comm link to the " + title + "...";
        }
        if (admin.isAICore()) {
            String coreLabel = aiCoreLabel(admin.getAICoreId());
            return "Open a comm link to the " + coreLabel + " " + title + "...";
        }
        return "Open a comm link to " + admin.getNameString() + ", " + title + "...";
    }

    private static String aiCoreLabel(String aiCoreId) {
        if (Commodities.ALPHA_CORE.equals(aiCoreId)) return "Alpha Core";
        if (Commodities.BETA_CORE.equals(aiCoreId))  return "Beta Core";
        if (Commodities.GAMMA_CORE.equals(aiCoreId)) return "Gamma Core";
        return "AI core";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static MarketAPI resolveMarket(SectorEntityToken entity) {
        if (entity == null) return null;
        return entity.getMarket();
    }
}
