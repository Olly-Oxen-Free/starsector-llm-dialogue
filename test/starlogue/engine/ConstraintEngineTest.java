package starlogue.engine;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.PersonalityAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import starlogue.action.StarlogueAction;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link ConstraintEngine#filterActions} (test gap #6 from the 2026-07-07 audit):
 * unavailable actions are excluded from {@code available}, and {@code bluffOnly} is populated
 * only when both the action is bluffable AND the NPC's personality is bluff-capable
 * (reckless/aggressive — see {@code ConstraintEngine.isBluffCapable}).
 *
 * <p>Exercises {@code filterActions} directly (a plain pool + {@link GameContext}) rather than
 * {@code evaluate(entity, ctx)}, since the latter requires resolving a {@code StarloguePlugin}
 * via a real {@code SectorEntityToken}/campaign state that isn't headlessly constructible.
 * {@link GameContext#person} is a plain public field, so a JDK dynamic proxy over the
 * {@code PersonAPI}/{@code PersonalityAPI} interfaces is enough to drive personality checks
 * without any game-runtime mocking library.
 */
public class ConstraintEngineTest {

    public static void main(String[] args) throws Exception {
        ConstraintEngineTest t = new ConstraintEngineTest();
        t.testUnavailableActionsExcluded();
        t.testBluffOnlyRequiresBluffCapablePersonality();
        t.testBluffOnlyExcludesNonBluffableActions();
        System.out.println("ConstraintEngineTest: ALL PASSED");
    }

    public void testUnavailableActionsExcluded() {
        GameContext ctx = new GameContext(); // ctx.person == null -> not bluff-capable
        FakeAction available = new FakeAction("avail", true, false);
        FakeAction unavailable = new FakeAction("unavail", false, false);

        EvaluatedActionSet set = ConstraintEngine.filterActions(Arrays.asList(available, unavailable), ctx);

        assert set.available.size() == 1 && set.available.contains(available)
            : "expected only the available action in available[]";
        assert !set.available.contains(unavailable) : "unavailable action leaked into available[]";
        assert set.bluffOnly.isEmpty() : "bluffOnly should be empty when personality is not bluff-capable";
    }

    public void testBluffOnlyRequiresBluffCapablePersonality() {
        FakeAction bluffableButUnavailable = new FakeAction("bluffable", false, true);

        GameContext steadyCtx = new GameContext();
        steadyCtx.person = personWithPersonality(Personalities.STEADY);
        EvaluatedActionSet steadySet = ConstraintEngine.filterActions(
            Collections.singletonList(bluffableButUnavailable), steadyCtx);
        assert steadySet.bluffOnly.isEmpty()
            : "STEADY personality must not surface bluffOnly actions";

        GameContext recklessCtx = new GameContext();
        recklessCtx.person = personWithPersonality(Personalities.RECKLESS);
        EvaluatedActionSet recklessSet = ConstraintEngine.filterActions(
            Collections.singletonList(bluffableButUnavailable), recklessCtx);
        assert recklessSet.bluffOnly.size() == 1 && recklessSet.bluffOnly.contains(bluffableButUnavailable)
            : "RECKLESS personality must surface a bluffable, unavailable action via bluffOnly";

        GameContext aggressiveCtx = new GameContext();
        aggressiveCtx.person = personWithPersonality(Personalities.AGGRESSIVE);
        EvaluatedActionSet aggressiveSet = ConstraintEngine.filterActions(
            Collections.singletonList(bluffableButUnavailable), aggressiveCtx);
        assert aggressiveSet.bluffOnly.size() == 1
            : "AGGRESSIVE personality must surface a bluffable, unavailable action via bluffOnly";
    }

    public void testBluffOnlyExcludesNonBluffableActions() {
        FakeAction nonBluffableUnavailable = new FakeAction("no-bluff", false, false);
        GameContext recklessCtx = new GameContext();
        recklessCtx.person = personWithPersonality(Personalities.RECKLESS);

        EvaluatedActionSet set = ConstraintEngine.filterActions(
            Collections.singletonList(nonBluffableUnavailable), recklessCtx);

        assert set.available.isEmpty() : "non-bluffable unavailable action must not be available";
        assert set.bluffOnly.isEmpty()
            : "non-bluffable action must not appear in bluffOnly even for a bluff-capable personality";
    }

    // ── Fakes ──────────────────────────────────────────────────────────────────

    /** Builds a proxy {@link PersonAPI} whose only wired behaviour is getPersonalityAPI(). */
    private static PersonAPI personWithPersonality(final String personalityId) {
        final PersonalityAPI personality = (PersonalityAPI) Proxy.newProxyInstance(
            ConstraintEngineTest.class.getClassLoader(),
            new Class<?>[] { PersonalityAPI.class },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] margs) {
                    if ("getId".equals(method.getName())) return personalityId;
                    return defaultReturn(method.getReturnType());
                }
            });

        return (PersonAPI) Proxy.newProxyInstance(
            ConstraintEngineTest.class.getClassLoader(),
            new Class<?>[] { PersonAPI.class },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] margs) {
                    if ("getPersonalityAPI".equals(method.getName())) return personality;
                    return defaultReturn(method.getReturnType());
                }
            });
    }

    private static Object defaultReturn(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == void.class) return null;
        return 0;
    }

    static final class FakeAction implements StarlogueAction {
        private final String id;
        private final boolean available;
        private final boolean bluffable;

        FakeAction(String id, boolean available, boolean bluffable) {
            this.id = id;
            this.available = available;
            this.bluffable = bluffable;
        }

        @Override public String getId() { return id; }
        @Override public String getDescription() { return id; }
        @Override public Map<String, Object> getParameters() { return Collections.emptyMap(); }
        @Override public boolean isAvailable(GameContext ctx) { return available; }
        @Override public boolean isBluffable() { return bluffable; }
        @Override public void execute(GameContext ctx, Map<String, Object> args) { }
        @Override public String narrativeNote() { return null; }
    }
}
