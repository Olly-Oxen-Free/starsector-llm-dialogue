package starlogue.personality;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import starlogue.api.PersonalityModifier;
import starlogue.api.StarlogueAPI;
import starlogue.engine.GameContext;
import org.apache.log4j.Logger;
// CharacterProfileRegistry imported in same package
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PersonalityComposer {

    private static final Logger log = Logger.getLogger(PersonalityComposer.class);
    private static final Map<String, String> PERSONALITY_NOTES = new HashMap<String, String>();
    private static final Map<String, String> RANK_NOTES = new HashMap<String, String>();

    static {
        PERSONALITY_NOTES.put(Personalities.RECKLESS,
            "You are reckless — impulsive and willing to take extreme risks. "
          + "You may threaten actions you cannot guarantee. ");
        PERSONALITY_NOTES.put(Personalities.AGGRESSIVE,
            "You are aggressive — forceful and direct. You prefer strength over negotiation. ");
        PERSONALITY_NOTES.put(Personalities.STEADY,
            "You are steady — calm and professional. You weigh risks carefully. ");
        PERSONALITY_NOTES.put(Personalities.CAUTIOUS,
            "You are cautious — conservative and risk-averse. You prefer to avoid unnecessary conflict. ");
        PERSONALITY_NOTES.put(Personalities.TIMID,
            "You are timid — you avoid confrontation where possible. ");

        RANK_NOTES.put("Captain",       "You command this fleet directly. ");
        RANK_NOTES.put("Commander",     "You hold field command of this fleet. ");
        RANK_NOTES.put("Admiral",
            "You are a senior flag officer. You think in terms of broader strategy, not individual engagements. ");
        RANK_NOTES.put("Commodore",     "You lead a small squadron. ");
        RANK_NOTES.put("Administrator", "You are primarily an administrator, not a combat commander. ");
    }

    /** Game-API path: called during live interactions. */
    public static String compose(GameContext ctx) {
        PersonAPI person = ctx.person;
        String factionId = ctx.npcFaction != null ? ctx.npcFaction.getId() : "unknown";
        String personality = (person.getPersonalityAPI() != null)
            ? person.getPersonalityAPI().getId() : "";
        String rank = person.getRank();
        boolean isAICore = person.getAICoreId() != null;
        String[] skillNotes = buildSkillNotes(person);

        // Character profile has highest priority — check before faction profile
        CharacterProfile charProfile = CharacterProfileRegistry.getProfile(person, factionId);
        CharacterProfile archetypeProfile = CharacterProfileRegistry.getArchetypeProfile(ctx.archetypeId);

        String base;
        if (charProfile != null) {
            base = buildBaseFromProfile(charProfile.baseline, personality, rank, isAICore, skillNotes);
        } else if (archetypeProfile != null) {
            base = buildBaseFromProfile(archetypeProfile.baseline, personality, rank, isAICore, skillNotes);
        } else if (!FactionProfileRegistry.hasProfile(factionId) && ctx.npcFaction != null) {
            // No handwritten profile and no contributor profile — build from live game data.
            // Still apply personality/rank/skill notes on top of the live faction context.
            String liveContext = starlogue.engine.FactionDescriptionHelper.buildFactionContext(ctx.npcFaction);
            base = buildBaseFromLiveContext(liveContext, personality, rank, isAICore, skillNotes);
        } else {
            base = composeFromParts(factionId, personality, rank, isAICore, skillNotes);
        }

        // Append registered PersonalityModifier contributions
        StringBuilder sb = new StringBuilder(base);
        for (PersonalityModifier mod : StarlogueAPI.getPersonalityModifiers()) {
            try {
                String note = mod.modify(factionId, person, ctx);
                if (note != null && !note.isEmpty()) {
                    sb.append(" ").append(note.trim());
                }
            } catch (Exception e) {
                log.error("Starlogue: PersonalityModifier from mod '" + mod.getModId() + "' threw", e);
            }
        }
        // Append any notes injected directly onto ctx (e.g. by ContextModifiers)
        for (String note : ctx.personalityNotes) {
            sb.append(" ").append(note.trim());
        }
        return sb.toString().trim();
    }

    /** Pure method — callable from unit tests without game API. */
    public static String composeFromParts(String factionId, String personality,
                                          String rank, boolean isAICore,
                                          String[] skillNotes) {
        FactionProfile profile = FactionProfileRegistry.getProfile(factionId);
        return buildBaseFromString(profile.baseline, personality, rank, isAICore, skillNotes);
    }

    /**
     * Builds the personality baseline when no hand-authored profile exists.
     * Uses the live faction description as the identity foundation, then applies
     * the same personality/rank/skill/AI-core notes as composeFromParts.
     */
    private static String buildBaseFromLiveContext(String liveContext, String personality,
                                                   String rank, boolean isAICore,
                                                   String[] skillNotes) {
        String base = (liveContext != null && !liveContext.isEmpty())
            ? liveContext
            : "You are a fleet commander operating in the Persean Sector.";
        return buildBaseFromString(base, personality, rank, isAICore, skillNotes);
    }

    private static String buildBaseFromProfile(String baseline, String personality,
                                               String rank, boolean isAICore,
                                               String[] skillNotes) {
        return buildBaseFromString(baseline, personality, rank, isAICore, skillNotes);
    }

    /**
     * Core builder — shared by all three compose paths.
     * Appends personality, rank, skill, AI-core notes, and the closing
     * instruction onto the provided {@code base} string.
     */
    private static String buildBaseFromString(String base, String personality,
                                              String rank, boolean isAICore,
                                              String[] skillNotes) {
        StringBuilder sb = new StringBuilder(base).append(" ");

        String pNote = PERSONALITY_NOTES.get(personality);
        if (pNote != null) sb.append(pNote);

        String rNote = RANK_NOTES.get(rank);
        if (rNote != null) sb.append(rNote);

        for (String note : skillNotes) {
            sb.append(note).append(" ");
        }

        if (isAICore) {
            sb.append("You are an artificial intelligence. "
                    + "You do not form emotional attachments. "
                    + "You reason in probabilities. ");
        }

        sb.append("Respond in 1-3 sentences. Do not break character. "
                + "Do not describe your own actions in third person — speak directly.");

        return sb.toString().trim();
    }

    private static String[] buildSkillNotes(PersonAPI person) {
        List<String> notes = new ArrayList<String>();
        if (person.getStats() != null) {
            if (person.getStats().hasSkill(Skills.COMBAT_ENDURANCE)) {
                notes.add("You are battle-hardened and prefer direct solutions.");
            }
            if (person.getStats().hasSkill(Skills.SUPPORT_DOCTRINE)) {
                notes.add("You value coordination and defensive posture over aggression.");
            }
        }
        return notes.toArray(new String[0]);
    }
}
