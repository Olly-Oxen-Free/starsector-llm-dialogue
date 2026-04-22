package starlogue.starlords;

import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.api.PersonalityModifier;
import starlogue.engine.GameContext;
import starlords.person.Lord;
import starlords.person.LordPersonality;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps Star Lords' LordPersonality enum to voice notes for the system prompt.
 * Only fires for Star Lord contexts (lordData != null).
 */
public class StarLordPersonalityModifier implements PersonalityModifier {

    private static final Map<LordPersonality, String> NOTES = new HashMap<LordPersonality, String>();

    static {
        NOTES.put(LordPersonality.UPSTANDING,
            "You are upstanding — honourable and principled. You keep your word and expect others to keep theirs. "
          + "You are offended by deception and respond poorly to threats.");
        NOTES.put(LordPersonality.MARTIAL,
            "You are martial — you respect strength above all else. You admire bold action and speak bluntly. "
          + "Weakness or excessive diplomacy irritates you.");
        NOTES.put(LordPersonality.CALCULATING,
            "You are calculating — you think several steps ahead. You speak carefully and commit to nothing "
          + "without understanding what you gain. Emotion rarely guides your decisions.");
        NOTES.put(LordPersonality.QUARRELSOME,
            "You are quarrelsome — quick to take offence and slow to forgive. You enjoy conflict and will "
          + "escalate tensions if given the opportunity. You respect persistence over politeness.");
    }

    @Override
    public String getModId() { return "starlogue.starlords"; }

    @Override
    public String modify(String factionId, PersonAPI person, GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return null;
        Lord lord = (Lord) ctx.lordData;
        LordPersonality personality = lord.getPersonality();
        if (personality == null) return null;
        String note = NOTES.get(personality);
        if (note == null) return null;
        // Replace generic personality note — lords use their own personality system
        return note + " You are a Star Lord — a powerful landed noble with fiefs, vassals, and political ambitions.";
    }
}
