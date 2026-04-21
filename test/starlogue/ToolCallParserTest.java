package starlogue;

import starlogue.llm.LLMToolCall;
import starlogue.llm.ToolCallParser;
import org.json.JSONObject;
import java.util.List;

public class ToolCallParserTest {
    public static void main(String[] args) throws Exception {
        testSingleToolCall();
        testNoToolCalls();
        testMultipleToolCalls();
        testNullContent();
        System.out.println("ToolCallParserTest: ALL PASSED");
    }

    static void testSingleToolCall() throws Exception {
        String json = "{\"choices\":[{\"message\":{" +
            "\"content\":\"I'm retreating.\"," +
            "\"tool_calls\":[{\"function\":{\"name\":\"retreat\",\"arguments\":\"{\\\"reason\\\":\\\"outgunned\\\"}\"}}]" +
            "}}]}";
        List<LLMToolCall> calls = ToolCallParser.parseOpenAI(new JSONObject(json));
        assert calls.size() == 1 : "Expected 1 call, got " + calls.size();
        assert "retreat".equals(calls.get(0).toolId) : "Expected toolId=retreat, got " + calls.get(0).toolId;
        assert "outgunned".equals(calls.get(0).args.get("reason")) : "Expected reason=outgunned";
        assert "I'm retreating.".equals(ToolCallParser.parseContent(new JSONObject(json)));
    }

    static void testNoToolCalls() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"Hello there.\"}}]}";
        List<LLMToolCall> calls = ToolCallParser.parseOpenAI(new JSONObject(json));
        assert calls.isEmpty() : "Expected empty list, got " + calls.size();
    }

    static void testMultipleToolCalls() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" +
            "{\"function\":{\"name\":\"retreat\",\"arguments\":\"{\\\"reason\\\":\\\"fear\\\"}\"}}," +
            "{\"function\":{\"name\":\"extort\",\"arguments\":\"{\\\"credits\\\":50000,\\\"or_else\\\":\\\"regret it\\\"}\"}}]}}]}";
        List<LLMToolCall> calls = ToolCallParser.parseOpenAI(new JSONObject(json));
        assert calls.size() == 2 : "Expected 2 calls, got " + calls.size();
        assert "extort".equals(calls.get(1).toolId) : "Expected toolId=extort";
        assert ((Number) calls.get(1).args.get("credits")).intValue() == 50000;
    }

    static void testNullContent() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[]}}]}";
        String content = ToolCallParser.parseContent(new JSONObject(json));
        assert content == null || content.isEmpty() : "Expected null/empty content";
    }
}
