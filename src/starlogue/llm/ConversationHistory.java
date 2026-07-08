package starlogue.llm;

import java.util.*;

public class ConversationHistory {

    private static class Turn {
        final Map<String, Object> userMessage;
        final Map<String, Object> assistantMessage;

        Turn(String userText, String assistantText) {
            Map<String, Object> um = new LinkedHashMap<String, Object>();
            um.put("role", "user");
            um.put("content", userText);
            userMessage = Collections.unmodifiableMap(um);

            Map<String, Object> am = new LinkedHashMap<String, Object>();
            am.put("role", "assistant");
            am.put("content", assistantText != null ? assistantText : "");
            assistantMessage = Collections.unmodifiableMap(am);
        }
    }

    private final List<Turn> turns = new ArrayList<Turn>();

    public void addTurn(String userText, String assistantText) {
        turns.add(new Turn(userText, assistantText));
    }

    /**
     * Returns flat message list for LLM: the last maxTurns turn pairs.
     * Does not include the system message — caller prepends it.
     */
    public List<Map<String, Object>> getTrimmedHistory(int maxTurns) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        int from = Math.max(0, turns.size() - maxTurns);
        for (int i = from; i < turns.size(); i++) {
            messages.add(turns.get(i).userMessage);
            messages.add(turns.get(i).assistantMessage);
        }
        return messages;
    }

    /**
     * Returns flat message list for LLM, bounded by both a turn-count cap and a character
     * budget (used as a cheap proxy for a token budget — see {@code starlogue_history_budget}).
     * Both limits apply: the turn-count cap is applied first, then whole turns are dropped
     * from the oldest end until the remaining turns fit within {@code maxChars}. At least the
     * single most recent turn is always kept, even if it alone exceeds the budget.
     */
    public List<Map<String, Object>> getTrimmedHistory(int maxTurns, int maxChars) {
        int from = Math.max(0, turns.size() - maxTurns);
        int lastIndex = turns.size() - 1;

        if (maxChars > 0 && lastIndex >= from) {
            int total = 0;
            int cutoff = from;
            // Walk from most recent to oldest, accumulating char length, and find how far back
            // we can go before exceeding the budget. Always keep at least the newest turn.
            for (int i = lastIndex; i >= from; i--) {
                int turnChars = turnCharLength(turns.get(i));
                if (i != lastIndex && total + turnChars > maxChars) {
                    cutoff = i + 1;
                    break;
                }
                total += turnChars;
                cutoff = i;
            }
            from = cutoff;
        }

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (int i = from; i < turns.size(); i++) {
            messages.add(turns.get(i).userMessage);
            messages.add(turns.get(i).assistantMessage);
        }
        return messages;
    }

    private static int turnCharLength(Turn turn) {
        Object u = turn.userMessage.get("content");
        Object a = turn.assistantMessage.get("content");
        int len = 0;
        if (u instanceof String) len += ((String) u).length();
        if (a instanceof String) len += ((String) a).length();
        return len;
    }

    public void clear() { turns.clear(); }
    public int size()   { return turns.size(); }
}
