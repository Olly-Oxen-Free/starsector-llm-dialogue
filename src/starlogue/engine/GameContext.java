package starlogue.engine;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.characters.PersonAPI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only snapshot assembled at conversation start and passed to all action evaluators. */
public class GameContext {
    // Identity
    public SectorEntityToken entity;
    public PersonAPI person;
    public CampaignFleetAPI fleet;       // null for station NPCs
    public FactionAPI npcFaction;
    public FactionAPI playerFaction;

    // Relationship
    public RepLevel repLevel;
    public float repValue;               // -1.0..+1.0
    public float individualRel;

    // Fleet balance (0 if no fleet)
    public int npcFleetPoints;
    public int playerFleetPoints;
    public float strengthDelta;          // (npc - player) / max(npc, player)

    // Memory
    public float memoryScore;
    public boolean playerHasBluffCredibility; // faction score > -20

    // Tier flags
    public boolean isStarLord = false;
    public boolean isNexerelinFactionLeader = false;
    public Object lordData = null;
    public Object nexerelinContext = null;

    // Rate-limit state (from player fleet memory)
    public float repGained30d;
    public float repLost30d;

    // Extra context notes from registered ContextModifiers (injected into [SITUATION] block)
    public List<String> contextNotes = new ArrayList<String>();

    // Extra personality notes from registered PersonalityModifiers (appended to voice block)
    public List<String> personalityNotes = new ArrayList<String>();

    // Arbitrary key-value store for cross-contributor communication
    private final Map<String, Object> extras = new LinkedHashMap<String, Object>();

    // Set by ConstraintEngine after evaluation
    public EvaluatedActionSet evaluatedSet;

    // ── Extension API accessors ───────────────────────────────────────────

    public void set(String key, Object value) { extras.put(key, value); }

    public boolean getBoolean(String key) {
        Object v = extras.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    public float getFloat(String key, float defaultVal) {
        Object v = extras.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : defaultVal;
    }

    public String getString(String key, String defaultVal) {
        Object v = extras.get(key);
        return v instanceof String ? (String) v : defaultVal;
    }

    public void addPersonalityNote(String note) { personalityNotes.add(note); }
}
