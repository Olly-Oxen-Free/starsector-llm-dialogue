package starlogue.debug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link ConversationAuditLog#safeArgsJson} (audit test gap #9): recursive coercion of
 * nested {@link Map}/{@link java.util.Collection} tool-call args into a {@link JSONObject}, plus
 * the unserializable-value fallback.
 */
public class ConversationAuditLogTest {

    public static void main(String[] args) throws Exception {
        ConversationAuditLogTest t = new ConversationAuditLogTest();
        t.testNullArgsReturnsEmptyObject();
        t.testScalarsCoercedDirectly();
        t.testNestedMapCoercedRecursively();
        t.testCollectionCoercedToJsonArray();
        t.testUnserializableValueFallsBackToPlaceholder();
        System.out.println("ConversationAuditLogTest: ALL PASSED");
    }

    public void testNullArgsReturnsEmptyObject() throws Exception {
        JSONObject o = ConversationAuditLog.safeArgsJson(null);
        assert o != null && o.length() == 0 : "expected empty object for null args";
    }

    public void testScalarsCoercedDirectly() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("amount", 42);
        args.put("enabled", true);
        args.put("note", "hello");
        args.put("missing", null);

        JSONObject o = ConversationAuditLog.safeArgsJson(args);

        assert o.getInt("amount") == 42;
        assert o.getBoolean("enabled");
        assert "hello".equals(o.getString("note"));
        assert o.isNull("missing") : "null value should coerce to JSONObject.NULL";
    }

    public void testNestedMapCoercedRecursively() throws Exception {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("nestedNum", 7);
        inner.put("nestedStr", "deep");

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("child", inner);

        JSONObject o = ConversationAuditLog.safeArgsJson(outer);

        assert o.has("child") : "expected nested map to be coerced into a nested object";
        JSONObject child = o.getJSONObject("child");
        assert child.getInt("nestedNum") == 7;
        assert "deep".equals(child.getString("nestedStr"));
    }

    public void testCollectionCoercedToJsonArray() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add(true);
        list.add("x");
        list.add(null);
        args.put("items", list);

        JSONObject o = ConversationAuditLog.safeArgsJson(args);

        assert o.has("items");
        JSONArray arr = o.getJSONArray("items");
        assert arr.length() == 4;
        assert arr.getInt(0) == 1;
        assert arr.getBoolean(1);
        assert "x".equals(arr.getString(2));
        assert arr.isNull(3);
    }

    public void testUnserializableValueFallsBackToPlaceholder() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("weird", new Object() {
            @Override public String toString() { throw new RuntimeException("boom"); }
        });

        JSONObject o = ConversationAuditLog.safeArgsJson(args);

        assert o.has("weird") : "even an unserializable value should leave a placeholder key";
        String v = o.getString("weird");
        assert v.startsWith("<unserializable:") : "expected unserializable placeholder, got: " + v;
    }
}
