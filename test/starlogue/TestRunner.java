package starlogue;

import java.lang.reflect.Method;

/**
 * Lightweight reflective test runner for Starlogue unit tests.
 *
 * <p>Finds all {@code public void testXxx} methods on each registered test class,
 * instantiates the class, invokes each method in a try/catch, and prints PASS/FAIL.
 * Exit code 0 if all pass; non-zero if any fail.
 *
 * <p>Run via: {@code ./build.sh test}
 */
public class TestRunner {

    /** Register test classes here. */
    private static final String[] TEST_CLASS_NAMES = {
        "starlogue.ToolCallParserTest",
        "starlogue.MemoryEngineTest",
        "starlogue.PersonalityComposerTest",
    };

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;

        for (String className : TEST_CLASS_NAMES) {
            System.out.println();
            System.out.println("=== " + className + " ===");

            Class<?> cls;
            try {
                cls = Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.out.println("  FAIL [class not found]: " + className);
                failed++;
                continue;
            }

            // Collect testXxx methods — use getDeclaredMethods to pick up package-private statics.
            Method[] methods = cls.getDeclaredMethods();
            boolean anyTest = false;
            Object instance = null;
            try {
                instance = cls.getDeclaredConstructor().newInstance();
            } catch (Throwable e) {
                // Some test classes may not have a default constructor — that's fine for static methods.
                instance = null;
            }

            for (Method m : methods) {
                if (!m.getName().startsWith("test")) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.getParameterCount() != 0) continue;

                anyTest = true;
                try {
                    m.setAccessible(true);
                    m.invoke(instance);
                    System.out.println("  PASS: " + m.getName());
                    passed++;
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    String msg = cause.getMessage();
                    if (msg == null) msg = cause.getClass().getSimpleName();
                    System.out.println("  FAIL: " + m.getName() + " — " + msg);
                    failed++;
                }
            }

            if (!anyTest) {
                System.out.println("  (no testXxx methods found)");
            }
        }

        System.out.println();
        System.out.println("Results: " + passed + " passed, " + failed + " failed.");

        if (failed > 0) {
            System.exit(1);
        }
    }
}
