package starlogue.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import lunalib.lunaSettings.LunaSettings;
import starlogue.engine.*;
import starlogue.llm.*;
import starlogue.personality.PersonalityComposer;
import starlogue.api.StarlogueAPI;
import starlogue.provider.FleetCaptainPlugin;
import starlogue.provider.StarloguePlugin;
import org.apache.log4j.Logger;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class StarlogueDialogPlugin implements InteractionDialogPlugin {

    private static final Logger log = Logger.getLogger(StarlogueDialogPlugin.class);

    private InteractionDialogAPI dialog;
    private OptionPanelAPI options;
    private TextPanelAPI text;

    private final SectorEntityToken target;
    private final Map<String, MemoryAPI> memoryMap;
    private GameContext ctx;
    private EvaluatedActionSet actionSet;
    private final ConversationHistory history = new ConversationHistory();

    private enum State { IDLE, WAITING, ERROR }
    private State state = State.IDLE;
    private float waitTimer = 0f;
    private final AtomicReference<LLMResponse> pending = new AtomicReference<LLMResponse>(null);
    private final AtomicReference<Exception> pendingError = new AtomicReference<Exception>(null);
    private String pendingUserMessage = null;

    public StarlogueDialogPlugin(SectorEntityToken target) {
        this(target, null);
    }

    public StarlogueDialogPlugin(SectorEntityToken target, Map<String, MemoryAPI> memoryMap) {
        this.target = target;
        this.memoryMap = memoryMap;
    }

    // ── InteractionDialogPlugin ───────────────────────────────────────────

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.options = dialog.getOptionPanel();
        this.text = dialog.getTextPanel();

        try {
            ctx = ConstraintEngine.buildContext(target, memoryMap);
            actionSet = ConstraintEngine.evaluate(target, ctx);
            ctx.evaluatedSet = actionSet;
            StarlogueAPI.setCurrentContext(ctx);

            String speaker = ctx.speakerName != null
                ? ctx.speakerName
                : (ctx.person != null ? ctx.person.getNameString() : "the other side");
            text.addParagraph("Channel open. You are speaking with " + speaker + ".");
            sendToLLM("(Conversation starts. Greet the player briefly and in character. One or two sentences only.)");
        } catch (Exception e) {
            log.error("Starlogue: init failed — closing dialog", e);
            failSafe();
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if ("starlogue_input".equals(optionData)) {
            showInputDialog();
        } else if ("starlogue_end".equals(optionData)) {
            StarlogueAPI.clearCurrentContext();
            dialog.dismiss();
        }
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {}

    @Override
    public void advance(float amount) {
        if (state != State.WAITING) return;
        waitTimer += amount;

        // Animated waiting indicator
        int dots = ((int)(waitTimer / 0.4f) % 3) + 1;
        text.replaceLastParagraph("." + new String(new char[dots]).replace("\0", "."));

        LLMResponse response = pending.getAndSet(null);
        if (response != null) {
            state = State.IDLE;
            waitTimer = 0f;
            try {
                displayResponse(response);
            } catch (Exception e) {
                log.error("Starlogue: displayResponse failed — closing dialog", e);
                failSafe();
            }
            return;
        }

        Exception err = pendingError.getAndSet(null);
        if (err != null) {
            log.error("Starlogue: LLM error — closing dialog", err);
            failSafe();
            return;
        }

        if (waitTimer > 30f) {
            log.warn("Starlogue: LLM timeout — closing dialog");
            failSafe();
        }
    }

    @Override
    public void backFromEngagement(EngagementResultAPI result) {}

    @Override
    public Object getContext() { return null; }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() { return null; }

    // ── Private helpers ───────────────────────────────────────────────────

    private void showInputDialog() {
        dialog.showCustomDialog(520f, 130f, new CustomDialogDelegate() {
            private TextFieldAPI field;

            @Override
            public void createCustomDialog(CustomPanelAPI panel,
                                           CustomDialogDelegate.CustomDialogCallback callback) {
                TooltipMakerAPI tooltip = panel.createUIElement(500f, 80f, false);
                field = tooltip.addTextField(500f, 24f);
                field.setMaxChars(400);
                field.grabFocus();
                panel.addUIElement(tooltip);
            }

            @Override
            public boolean hasCancelButton() { return true; }

            @Override public String getConfirmText() { return "Send"; }
            @Override public String getCancelText()  { return "Cancel"; }

            @Override
            public void customDialogConfirm() {
                String input = (field != null) ? field.getText().trim() : "";
                if (!input.isEmpty()) handlePlayerInput(input);
                else showMainOptions();
            }

            @Override
            public void customDialogCancel() { showMainOptions(); }

            @Override
            public CustomUIPanelPlugin getCustomPanelPlugin() { return null; }
        });
    }

    private void handlePlayerInput(String input) {
        text.addParagraph("You: " + input);
        sendToLLM(input);
    }

    private void sendToLLM(String userMessage) {
        state = State.WAITING;
        waitTimer = 0f;
        text.addParagraph("...");
        pendingUserMessage = userMessage;

        String systemPrompt = buildSystemPrompt();

        // Read settings via LunaLib (keys match LunaSettings.csv fieldIDs)
        String provider  = safeGetString("starlogue_provider",    "openai_compatible");
        String endpoint  = safeGetString("starlogue_endpoint",    "http://localhost:11434/v1");
        String apiKey    = safeGetString("starlogue_api_key",     "");
        String model     = safeGetString("starlogue_model",       "mistral");
        float  temp      = safeGetFloat ("starlogue_temperature",  0.8f);
        int    maxTokens = safeGetInt   ("starlogue_max_tokens",   300);
        int    maxTurns  = safeGetInt   ("starlogue_history_turns", 10);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> sysMsg = new LinkedHashMap<String, Object>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        messages.addAll(history.getTrimmedHistory(maxTurns));
        Map<String, Object> userMsg = new LinkedHashMap<String, Object>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        List<Map<String, Object>> tools = ConstraintEngine.buildToolsArray(actionSet);
        LLMRequest request = new LLMRequest(messages, tools, model, temp, maxTokens);

        if (safeGetBoolean("starlogue_debug_prompts", false)) {
            log.debug("Starlogue system prompt:\n" + systemPrompt);
        }

        final LLMClient client = "anthropic".equals(provider)
            ? new AnthropicClient(apiKey)
            : new OpenAIClient(endpoint, apiKey);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LLMResponse response = client.complete(request);
                    pending.set(response);
                } catch (Exception e) {
                    log.error("Starlogue: LLM call failed", e);
                    pendingError.set(e);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void displayResponse(LLMResponse response) {
        String content = response.content;
        String speaker = ctx.speakerName != null
            ? ctx.speakerName
            : (ctx.person != null ? ctx.person.getNameString() : "???");
        if (content != null && !content.isBlank()) {
            text.replaceLastParagraph(speaker + ": " + content);
        } else {
            text.replaceLastParagraph(""); // tool-only response
        }

        if (safeGetBoolean("starlogue_debug_tools", false)) {
            log.debug("Starlogue: " + response.toolCalls.size() + " tool call(s)");
        }

        for (LLMToolCall call : response.toolCalls) {
            executeToolCall(call);
        }

        String assistantText = (content != null && !content.isBlank()) ? content : "(action)";
        if (pendingUserMessage != null) {
            history.addTurn(pendingUserMessage, assistantText);
            pendingUserMessage = null;
        }

        showMainOptions();
    }

    private void executeToolCall(LLMToolCall call) {
        if (safeGetBoolean("starlogue_debug_tools", false)) {
            log.debug("Starlogue: tool call → " + call);
        }

        starlogue.action.StarlogueAction action = null;
        for (starlogue.action.StarlogueAction a : actionSet.available) {
            if (a.getId().equals(call.toolId)) { action = a; break; }
        }
        if (action == null) {
            log.warn("Starlogue: LLM called unavailable tool '" + call.toolId + "' — ignoring");
            return;
        }

        action.execute(ctx, call.args);

        // Check if the action requested a dialog handoff (e.g. tournament)
        final InteractionDialogPlugin handoff = StarlogueAPI.consumeHandoff();
        if (handoff != null) {
            String note = action.narrativeNote();
            if (note != null) text.addParagraph("[" + note + "]");
            failSafe(); // dismiss Starlogue dialog
            // One-frame delay: engine needs a tick after dismiss before a new dialog can open
            Global.getSector().addTransientScript(new EveryFrameScript() {
                private boolean done = false;
                public boolean isDone() { return done; }
                public boolean runWhilePaused() { return true; }
                public void advance(float delta) {
                    if (!done) {
                        done = Global.getSector().getCampaignUI()
                            .showInteractionDialog(handoff, null);
                    }
                }
            });
            return;
        }

        String note = action.narrativeNote();
        if (note != null) {
            text.addParagraph("[" + note + "]");
        }
    }

    private void showMainOptions() {
        options.clearOptions();
        if (state != State.WAITING) {
            options.addOption("Say something...", "starlogue_input");
            options.addOption("End conversation", "starlogue_end");
        }
    }

    /** Called on any unrecoverable error. Cleans up and exits to vanilla dialog flow. */
    private void failSafe() {
        state = State.ERROR;
        pending.set(null);
        pendingError.set(null);
        StarlogueAPI.clearCurrentContext();
        if (dialog != null) dialog.dismiss();
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // Character block
        StarloguePlugin plugin = ConstraintEngine.getPlugin(target, memoryMap);
        if (plugin != null) {
            sb.append(plugin.getSystemPromptPreamble(ctx));
        } else {
            sb.append("You are a fleet commander in the Persean Sector.");
        }
        sb.append("\n\n");

        // Context block
        sb.append("SITUATION:\n");
        String name = ctx.speakerName != null
            ? ctx.speakerName
            : (ctx.person != null ? ctx.person.getNameString() : "(automated)");
        sb.append("- Your name: ").append(name).append("\n");
        if (ctx.npcFaction != null) {
            sb.append("- Your faction: ").append(ctx.npcFaction.getDisplayName()).append("\n");
        }
        // Fleet-strength line only meaningful when a fleet is actually present.
        if (ctx.fleet != null) {
            sb.append("- Fleet strength: ");
            if (ctx.strengthDelta > 0.15f) sb.append("you outgun the player\n");
            else if (ctx.strengthDelta < -0.15f) sb.append("player outguns you\n");
            else sb.append("roughly equal forces\n");
        }
        if (ctx.repLevel != null) {
            sb.append("- Faction standing with player: ").append(ctx.repLevel).append("\n");
        }
        if (ctx.memoryScore > 20f)
            sb.append("- You have positive history with this player\n");
        else if (ctx.memoryScore < -20f)
            sb.append("- You have negative history with this player\n");

        // Bluff note
        if (!actionSet.bluffOnly.isEmpty()) {
            sb.append("\nYou may threaten the following even though you are not certain you will act: ");
            for (int i = 0; i < actionSet.bluffOnly.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(actionSet.bluffOnly.get(i).getId());
            }
            sb.append(".\n");
        }

        // Extra notes from ContextModifiers
        for (String note : ctx.contextNotes) {
            sb.append("- ").append(note).append("\n");
        }

        return sb.toString().trim();
    }

    // ── LunaLib helper methods (null-safe) ────────────────────────────────

    private static String safeGetString(String key, String fallback) {
        String v = LunaSettings.getString("starlogue", key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static float safeGetFloat(String key, float fallback) {
        Float v = LunaSettings.getFloat("starlogue", key);
        return v != null ? v : fallback;
    }

    private static int safeGetInt(String key, int fallback) {
        Integer v = LunaSettings.getInt("starlogue", key);
        return v != null ? v : fallback;
    }

    private static boolean safeGetBoolean(String key, boolean fallback) {
        Boolean v = LunaSettings.getBoolean("starlogue", key);
        return v != null ? v : fallback;
    }
}
