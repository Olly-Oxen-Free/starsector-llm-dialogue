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
        testMalformedToolArgumentsStillParsesCalls();
        testNullContent();
        testOpenAIReasoningField();
        testAnthropicThinkingBlocks();
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

    static void testMalformedToolArgumentsStillParsesCalls() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"Executing actions\",\"tool_calls\":[" +
            "{\"function\":{\"name\":\"transfer_supplies\",\"arguments\":\"{not-valid-json}\"}}," +
            "{\"function\":{\"name\":\"retreat\",\"arguments\":\"{\\\"reason\\\":\\\"mission complete\\\"}\"}}]}}]}";
        List<LLMToolCall> calls = ToolCallParser.parseOpenAI(new JSONObject(json));
        assert calls.size() == 2 : "Expected 2 calls despite malformed first args, got " + calls.size();
        assert "transfer_supplies".equals(calls.get(0).toolId) : "Expected first tool transfer_supplies";
        assert calls.get(0).args.isEmpty() : "Malformed arguments should map to empty args";
        assert "retreat".equals(calls.get(1).toolId) : "Expected second tool retreat";
        assert "mission complete".equals(calls.get(1).args.get("reason")) : "Expected retreat reason parsed";
    }

    static void testOpenAIReasoningField() throws Exception {
        String json = "{\"choices\":[{\"message\":{"
            + "\"content\":\"ok\","
            + "\"reasoning\":\"step A then B\""
            + "}}]}";
        String r = ToolCallParser.parseOpenAIReasoning(new JSONObject(json));
        assert "step A then B".equals(r) : "Expected reasoning parsed, got " + r;
        String u = ToolCallParser.parseOpenAIUsageSummary(new JSONObject(
            "{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":{\"total_tokens\":42}}"));
        assert u != null && u.contains("42") : "Expected usage string, got " + u;
    }

    static void testAnthropicThinkingBlocks() throws Exception {
        String json = "{\"content\":["
            + "{\"type\":\"thinking\",\"thinking\":\"plan\"},"
            + "{\"type\":\"text\",\"text\":\"hello\"}]}";
        String t = ToolCallParser.parseAnthropicThinking(new JSONObject(json));
        assert "plan".equals(t) : "Expected thinking joined, got " + t;
    }
}
