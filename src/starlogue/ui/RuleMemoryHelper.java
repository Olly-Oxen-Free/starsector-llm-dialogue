package starlogue.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.RuleBasedDialog;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Merges the rules-callback memory map with the dialog plugin's memory map
 * (e.g. {@link RuleBasedDialog} or any vanilla plugin that exposes
 * {@code getMemoryMap()}) so {@code $person} and other keys match what rules see.
 * Overlays plugin bags onto rule bags while preserving {@code $person} when the
 * plugin's bag initially lacked it (comm directory / person comm flows).
 * <p>
 * Portside bar and Nex-style bar plugins: copies {@code $person} from the active
 * bar event's {@code getPerson()} when memory has no person yet — using a broad
 * bar detector (FQN, {@code BarEvent} in class name, or superclass walk) plus
 * multiple reflected field names on the dialog plugin.
 */
public final class RuleMemoryHelper {

    private static final String VANILLA_BAR_PLUGIN =
        "com.fs.starfarer.api.impl.campaign.intel.bar.BarEventDialogPlugin";

    private RuleMemoryHelper() {}

    @SuppressWarnings("unchecked")
    public static Map<String, MemoryAPI> mergeWithRuleBasedDialog(
            Map<String, MemoryAPI> fromRuleCallback, InteractionDialogAPI dialog) {
        if (dialog == null) {
            return fromRuleCallback;
        }
        Map<String, MemoryAPI> fromPlugin = null;
        try {
            InteractionDialogPlugin p = dialog.getPlugin();
            if (p instanceof RuleBasedDialog) {
                fromPlugin = ((RuleBasedDialog) p).getMemoryMap();
            } else if (p != null) {
                try {
                    java.lang.reflect.Method gm = p.getClass().getMethod("getMemoryMap");
                    Object o = gm.invoke(p);
                    if (o instanceof Map) {
                        fromPlugin = (Map<String, MemoryAPI>) o;
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }

        // Never bail solely because the dialog's memory map is empty. Vanilla
        // BarEventDialogPlugin often returns an empty map while the active PortsideBarEvent
        // on a field (BaseBarEventWithPerson#getPerson) already holds the NPC — the bar
        // inject + reflection paths below must still run.
        Map<String, MemoryAPI> m = new HashMap<String, MemoryAPI>();
        if (fromRuleCallback != null) {
            m.putAll(fromRuleCallback);
        }
        if (fromPlugin != null && !fromPlugin.isEmpty()) {
            for (Map.Entry<String, MemoryAPI> e : fromPlugin.entrySet()) {
                MemoryAPI pluginBag = e.getValue();
                if (pluginBag == null) continue;
                MemoryAPI ruleBag = m.get(e.getKey());
                copyPersonFromRuleBagIfMissing(pluginBag, ruleBag);
                m.put(e.getKey(), pluginBag);
            }
        }
        ensureAtLeastOneMemoryBag(m);
        injectPortsideBarNpcPersonIfNeeded(dialog, m);
        recoverPersonFromDialogPlugin(dialog, m);
        scanMemoryBagsForPersonLikeKeys(m);
        return m.isEmpty() ? fromRuleCallback : m;
    }

    /** So injectPersonIntoMerged always has a destination when rules + plugin maps were empty. */
    private static void ensureAtLeastOneMemoryBag(Map<String, MemoryAPI> m) {
        if (m == null || !m.isEmpty()) return;
        try {
            m.put("local", Global.getFactory().createMemory());
        } catch (Throwable ignored) { }
    }

    private static void copyPersonFromRuleBagIfMissing(MemoryAPI pluginBag, MemoryAPI ruleBag) {
        if (pluginBag == null || ruleBag == null) return;
        if (pluginBag.contains("$person")) return;
        if (!ruleBag.contains("$person")) return;
        Object p = ruleBag.get("$person");
        if (p != null) {
            pluginBag.set("$person", p);
        }
    }

    private static void injectPortsideBarNpcPersonIfNeeded(InteractionDialogAPI dialog,
                                                           Map<String, MemoryAPI> merged) {
        if (dialog == null || merged == null) return;
        if (anyBagHasPerson(merged)) return;

        InteractionDialogPlugin plugin;
        try {
            plugin = dialog.getPlugin();
        } catch (Throwable ignored) {
            return;
        }
        if (plugin == null || !isBarLikePlugin(plugin)) return;

        PersonAPI person = extractBarPersonFromBarPlugin(plugin);
        if (person == null) return;
        injectPersonIntoMerged(merged, person);
    }

    /** When rules merge still lacks {@code $person}, ask the dialog plugin directly (comm directory, etc.). */
    private static void recoverPersonFromDialogPlugin(InteractionDialogAPI dialog,
                                                      Map<String, MemoryAPI> merged) {
        if (dialog == null || merged == null || anyBagHasPerson(merged)) return;
        InteractionDialogPlugin plugin;
        try {
            plugin = dialog.getPlugin();
        } catch (Throwable ignored) {
            return;
        }
        PersonAPI p = reflectPersonFromNestedPlugins(plugin, 0);
        if (p != null) {
            injectPersonIntoMerged(merged, p);
        }
    }

    /**
     * Walks the dialog plugin and common nested delegates (e.g. BarEventDialogPlugin.cmd)
     * so comm directory / bar wrappers that stash the speaker on an inner plugin still resolve.
     */
    private static PersonAPI reflectPersonFromNestedPlugins(InteractionDialogPlugin plugin, int depth) {
        if (plugin == null || depth > 3) return null;
        PersonAPI p = reflectPersonFromPlugin(plugin);
        if (p != null) return p;
        String[] nested = new String[] { "cmd", "originalPlugin", "delegate", "wrapped", "inner" };
        for (String fn : nested) {
            Object o = readDeclaredField(plugin, fn);
            if (o instanceof InteractionDialogPlugin) {
                p = reflectPersonFromNestedPlugins((InteractionDialogPlugin) o, depth + 1);
                if (p != null) return p;
            }
        }
        return null;
    }

    /**
     * Some dialogs stash a {@link PersonAPI} under non-standard keys; promote the first
     * reasonable hit to {@code $person} on the primary bag vanilla reads.
     */
    private static void scanMemoryBagsForPersonLikeKeys(Map<String, MemoryAPI> merged) {
        if (merged == null || anyBagHasPerson(merged)) return;
        String[] knownPersonKeys = new String[] {
            "$contactPerson", "$activePerson", "$talkingPerson", "$commPerson", "$barPerson"
        };
        for (MemoryAPI bag : merged.values()) {
            if (bag == null) continue;
            for (String kk : knownPersonKeys) {
                try {
                    if (!bag.contains(kk)) continue;
                    Object v = bag.get(kk);
                    if (v instanceof PersonAPI) {
                        injectPersonIntoMerged(merged, (PersonAPI) v);
                        return;
                    }
                } catch (Throwable ignored) { }
            }
        }
        for (MemoryAPI bag : merged.values()) {
            if (bag == null) continue;
            Collection<String> keys;
            try {
                keys = bag.getKeys();
            } catch (Throwable ignored) {
                continue;
            }
            if (keys == null) continue;
            for (String k : keys) {
                if (k == null) continue;
                String low = k.toLowerCase(Locale.ROOT);
                if (!low.contains("person") && !low.contains("contact") && !low.contains("talk")) continue;
                if ("$player".equalsIgnoreCase(k)) continue;
                Object v;
                try {
                    v = bag.get(k);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v instanceof PersonAPI) {
                    injectPersonIntoMerged(merged, (PersonAPI) v);
                    return;
                }
            }
        }
    }

    private static boolean isBarLikePlugin(InteractionDialogPlugin plugin) {
        if (plugin == null) return false;
        for (Class<?> c = plugin.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (VANILLA_BAR_PLUGIN.equals(n)) return true;
            if (n.contains("BarEvent")) return true;
        }
        return false;
    }

    private static PersonAPI extractBarPersonFromBarPlugin(InteractionDialogPlugin plugin) {
        if (plugin == null) return null;
        String[] fieldNames = new String[] {
            "event", "activeEvent", "currentEvent", "portsideEvent", "barEvent", "delegate", "wrappedEvent"
        };
        for (String fn : fieldNames) {
            Object ev = readDeclaredField(plugin, fn);
            if (ev == null) continue;
            PersonAPI p = invokeGetPersonIfPresent(ev);
            if (p != null) return p;
            // One-level nested "inner" event objects used by some mods
            for (String inner : fieldNames) {
                Object innerEv = readDeclaredField(ev, inner);
                if (innerEv == null) continue;
                p = invokeGetPersonIfPresent(innerEv);
                if (p != null) return p;
            }
        }
        return null;
    }

    private static PersonAPI reflectPersonFromPlugin(InteractionDialogPlugin plugin) {
        if (plugin == null) return null;
        String[] methods = new String[] {
            "getPerson", "getActivePerson", "getTalkingPerson", "getCommPerson",
            "getContactPerson", "getOtherPerson", "getInteractionPerson"
        };
        for (Class<?> c = plugin.getClass(); c != null; c = c.getSuperclass()) {
            for (String mn : methods) {
                try {
                    java.lang.reflect.Method m = c.getMethod(mn);
                    if (m.getParameterTypes().length != 0) continue;
                    if (!PersonAPI.class.isAssignableFrom(m.getReturnType())) continue;
                    Object o = m.invoke(plugin);
                    if (o instanceof PersonAPI) return (PersonAPI) o;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Object readDeclaredField(Object target, String fieldName) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void injectPersonIntoMerged(Map<String, MemoryAPI> merged, PersonAPI person) {
        if (merged == null || person == null) return;
        MemoryAPI local = merged.get("local");
        if (local != null && !local.contains("$person")) {
            local.set("$person", person);
            return;
        }
        for (MemoryAPI bag : merged.values()) {
            if (bag != null && !bag.contains("$person")) {
                bag.set("$person", person);
                return;
            }
        }
    }

    private static boolean anyBagHasPerson(Map<String, MemoryAPI> merged) {
        for (MemoryAPI bag : merged.values()) {
            if (extractPersonFromBag(bag) != null) return true;
        }
        return false;
    }

    private static PersonAPI extractPersonFromBag(MemoryAPI bag) {
        if (bag == null || !bag.contains("$person")) return null;
        Object v = bag.get("$person");
        return v instanceof PersonAPI ? (PersonAPI) v : null;
    }

    private static PersonAPI invokeGetPersonIfPresent(Object event) {
        try {
            java.lang.reflect.Method m = event.getClass().getMethod("getPerson");
            Object o = m.invoke(event);
            return o instanceof PersonAPI ? (PersonAPI) o : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
