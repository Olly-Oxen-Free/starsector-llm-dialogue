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

    public void clear() { turns.clear(); }
    public int size()   { return turns.size(); }
}
