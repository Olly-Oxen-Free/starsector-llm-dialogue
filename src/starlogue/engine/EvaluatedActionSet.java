package starlogue.engine;

import starlogue.action.StarlogueAction;
import java.util.Collections;
import java.util.List;

public class EvaluatedActionSet {
    /** Actions whose isAvailable() returned true — serialised as tools[] in the LLM request. */
    public final List<StarlogueAction> available;
    /** Actions that are unavailable but bluffable — appear only in the personality note. */
    public final List<StarlogueAction> bluffOnly;

    public EvaluatedActionSet(List<StarlogueAction> available, List<StarlogueAction> bluffOnly) {
        this.available = Collections.unmodifiableList(available);
        this.bluffOnly = Collections.unmodifiableList(bluffOnly);
    }
}
