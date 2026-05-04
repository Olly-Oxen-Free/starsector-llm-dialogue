package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Namespaced note on the current person for scripted callbacks. */
public class PersonSetNoteAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(PersonSetNoteAction.class);
    private static final String PREFIX = "$starlogue_person_note_";
    private String lastNote;

    @Override public String getId() { return "person_set_note"; }

    @Override
    public String getDescription() {
        return "Store a note on this contact: note_key (string), note_value (string).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("note_key", "string");
        p.put("note_value", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return ctx.person != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.person == null) return;
        String k = String.valueOf(args.getOrDefault("note_key", "misc")).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String v = String.valueOf(args.getOrDefault("note_value", ""));
        MemoryAPI mem = ctx.person.getMemory();
        mem.set(PREFIX + k, v, 365f);
        lastNote = "You make a mental note about this contact.";
        log.debug("Starlogue: person_set_note " + k);
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
