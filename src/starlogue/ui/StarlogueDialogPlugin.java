package starlogue.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import starlogue.engine.FleetSnapshotFormatter;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import org.lwjgl.input.Keyboard;
import starlogue.engine.*;
import starlogue.llm.*;
import starlogue.api.StarlogueAPI;
import starlogue.config.LlmBackendConfig;
import starlogue.debug.ConversationAuditLog;
import starlogue.debug.DebugSessionLog;
import starlogue.provider.StarloguePlugin;
import org.json.JSONObject;
import org.apache.log4j.Logger;
import java.util.*;
import starlogue.llm.LlmDispatcher;

public class StarlogueDialogPlugin implements InteractionDialogPlugin {

    private static final Logger log = Logger.getLogger(StarlogueDialogPlugin.class);

    private InteractionDialogAPI dialog;
    private OptionPanelAPI options;
    private TextPanelAPI text;

    private final SectorEntityToken target;
    private final Map<String, MemoryAPI> memoryMap;
    /** Dialog plugin that was installed before we swapped in — restored on clean exit. */
    private final InteractionDialogPlugin priorPlugin;
    /** Option panel snapshot captured before we swapped in — restored on clean exit. */
    private final List<Object> priorOptions;
    /** Becomes true if any action tool runs during the conversation. Dirty state means
     *  we dismiss on exit instead of returning to the prior point. */
    private boolean toolCallMade = false;

    private GameContext ctx;
    private EvaluatedActionSet actionSet;
    private final ConversationHistory history = new ConversationHistory();

    private enum State { IDLE, WAITING, ERROR }
    private State state = State.IDLE;
    private float waitTimer = 0f;
    private final LlmDispatcher dispatcher = new LlmDispatcher();
    private String pendingUserMessage = null;
    private final String conversationId = ConversationAuditLog.newConversationId();
    private boolean conversationAuditClosed = false;
    /** Counts LLM requests in this dialog (initial greeting = 1). */
    private int llmRequestOrdinal = 0;

    /** The text field at the bottom of the chat the player types into. Recreated each turn. */
    private TextFieldAPI inputField;
    /** True while an input field is the last thing appended to the text panel — suppresses the
     *  waiting-dots animation so we don't overwrite the field. */
    private boolean inputFieldIsLatest = false;

    private static final String OPT_SEND = "starlogue_send";
    private static final String OPT_END  = "starlogue_end";
    private static final String[] WAIT_FRAMES = new String[] { "-", "\\", "|", "/" };

    public StarlogueDialogPlugin(SectorEntityToken target) {
        this(target, null, null, null);
    }

    public StarlogueDialogPlugin(SectorEntityToken target, Map<String, MemoryAPI> memoryMap) {
        this(target, memoryMap, null, null);
    }

    public StarlogueDialogPlugin(SectorEntityToken target,
                                 Map<String, MemoryAPI> memoryMap,
                                 InteractionDialogPlugin priorPlugin,
                                 List<Object> priorOptions) {
        this.target = target;
        this.memoryMap = memoryMap;
        this.priorPlugin = priorPlugin;
        this.priorOptions = priorOptions;
    }

    // ── InteractionDialogPlugin ───────────────────────────────────────────

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.options = dialog.getOptionPanel();
        this.text = dialog.getTextPanel();

        try {
            ctx = ConstraintEngine.buildContext(target, memoryMap);
            actionSet = ConstraintEngine.evaluate(target, ctx, memoryMap);
            ctx.evaluatedSet = actionSet;
            StarlogueAPI.setCurrentContext(ctx);
            ConversationAuditLog.logConversationStart(conversationId, target, ctx, actionSet);

            String speaker = ctx.speakerName != null
                ? ctx.speakerName
                : (ctx.person != null ? ctx.person.getNameString() : "the other side");
            text.addParagraph("Channel open. You are speaking with " + speaker + ".");

            // Validate config before making the LLM call so misconfiguration is visible
            // in-dialog instead of causing a silent dismiss.
            LlmBackendConfig.Snapshot cfg = LlmBackendConfig.load();
            String configError = validateConfig(cfg);
            if (configError != null) {
                reportError(configError);
                return;
            }
            sendToLLM("(Conversation starts. Greet the player briefly and in character. One or two sentences only.)");
        } catch (Throwable e) {
            log.error("Starlogue: init failed", e);
            reportError("Starlogue failed to initialise: " + describe(e));
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("optionData", optionData != null ? String.valueOf(optionData) : "null");
            d.put("state", String.valueOf(state));
            d.put("hasInputField", inputField != null);
            d.put("inputFieldIsLatest", inputFieldIsLatest);
            DebugSessionLog.log("H_UI_SEND", "StarlogueDialogPlugin.optionSelected", "option", d.toString());
        } catch (Throwable ignore) { }
        // #endregion
        if (OPT_SEND.equals(optionData)) {
            submitCurrentInput();
        } else if (OPT_END.equals(optionData)) {
            exitConversation();
        }
    }

    /**
     * Ends the Starlogue conversation. If no tool call was made and we have a prior
     * plugin + option panel snapshot, swap back so the player resumes the parent
     * dialog at the exact menu they were on before opening the comm link. If a tool
     * ran, the parent dialog's state may be stale (reputation changed, fleet stance
     * flipped, etc.) — in that case we dismiss outright.
     */
    private void exitConversation() {
        closeConversationAudit("player_exit");
        // Peaceful fleet comms: brief no-engage window so closing the LLM layer is less likely
        // to snap straight into a hostile pursuit in some encounter states.
        try {
            if (!toolCallMade && ctx != null && ctx.fleet != null) {
                CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
                float cool = 2.5f;
                if (pf != null) {
                    try { pf.setNoEngaging(cool); } catch (Throwable ignored) { }
                }
                try { ctx.fleet.setNoEngaging(cool); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        StarlogueAPI.clearCurrentContext();
        if (!toolCallMade && priorPlugin != null) {
            try {
                dialog.setPlugin(priorPlugin);
                OptionPanelAPI op = dialog.getOptionPanel();
                if (op != null && priorOptions != null) {
                    try {
                        // restoreSavedOptions replaces the panel's option list wholesale,
                        // so there's no need to clearOptions first (which can corrupt the
                        // panel state for rule-based parent dialogs).
                        op.restoreSavedOptions(priorOptions);
                    } catch (Throwable t) {
                        log.warn("Starlogue: restoreSavedOptions threw on exit", t);
                    }
                }
                // Re-drive the parent rules engine so it has a chance to refresh memory-derived state.
                try {
                    if (priorPlugin instanceof com.fs.starfarer.api.campaign.RuleBasedDialog) {
                        ((com.fs.starfarer.api.campaign.RuleBasedDialog) priorPlugin).updateMemory();
                    }
                } catch (Throwable ignored) { }
                return;
            } catch (Throwable t) {
                log.warn("Starlogue: failed to restore prior dialog plugin; falling back to dismiss", t);
            }
        }
        dialog.dismiss();
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {}

    @Override
    public void advance(float amount) {
        if (state != State.WAITING) return;
        waitTimer += amount;

        // Animated waiting indicator — only safe to run when the last thing in the text
        // panel is a regular paragraph (e.g. "..."). If the last append was an inline
        // text field (input), replaceLastParagraph would clobber it.
        if (!inputFieldIsLatest) {
            int idx = ((int) (waitTimer / 0.2f)) % WAIT_FRAMES.length;
            text.replaceLastParagraph("Starlogue is processing your message " + WAIT_FRAMES[idx]);
        }
        // Always mirror status on the option row (some builds rarely refresh text-panel animations).
        try {
            int idx = ((int) (waitTimer / 0.2f)) % WAIT_FRAMES.length;
            options.setOptionText("⌛ Working… " + WAIT_FRAMES[idx], OPT_SEND);
        } catch (Throwable ignored) { }

        java.util.Optional<LLMResponse> maybeResponse = dispatcher.poll();
        if (maybeResponse.isPresent()) {
            state = State.IDLE;
            waitTimer = 0f;
            try {
                displayResponse(maybeResponse.get());
            } catch (Throwable e) {
                log.error("Starlogue: displayResponse failed", e);
                reportError("Failed to render LLM response: " + describe(e));
            }
            return;
        }

        java.util.Optional<Exception> maybeError = dispatcher.pollError();
        if (maybeError.isPresent()) {
            Exception err = maybeError.get();
            log.error("Starlogue: LLM error", err);
            state = State.IDLE;
            waitTimer = 0f;
            pendingUserMessage = null;
            ConversationAuditLog.logToolCall(conversationId, "", null, "llm_error", describe(err));
            reportError(friendlyLlmFailure(err));
            return;
        }

        if (waitTimer > 30f) {
            log.warn("Starlogue: LLM timeout");
            state = State.IDLE;
            waitTimer = 0f;
            pendingUserMessage = null;
            ConversationAuditLog.logToolCall(conversationId, "", null, "llm_timeout", "30s timeout");
            reportError("LLM call timed out after 30s. Check that your endpoint/model is reachable.");
        }
    }

    @Override
    public void backFromEngagement(EngagementResultAPI result) {}

    @Override
    public Object getContext() { return null; }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() { return null; }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Appends an inline text field to the bottom of the chat panel and focuses it.
     * The field lives inside a tooltip region on the text panel, so it scrolls with
     * the rest of the conversation and feels like a natural chat composer.
     *
     * <p>The previously-active field (if any) is left in the scrollback — visually it
     * stays next to the message the player sent, which is fine; the reference is just
     * overwritten so {@link #submitCurrentInput()} only reads the newest one.
     */
    private void appendInputField() {
        try {
            TooltipMakerAPI tooltip = text.beginTooltip();
            // Wider / taller composer. Keep width-based limiting ON so the field wraps
            // to multiple lines instead of extending as one long horizontal line.
            inputField = tooltip.addTextField(800f, 120f);
            if (inputField != null) {
                try {
                    inputField.setLimitByStringWidth(true);
                } catch (Throwable ignored) { }
                inputField.setMaxChars(200_000);
                inputField.setUndoOnEscape(false);
                inputField.setHandleCtrlV(true);
            }
            text.addTooltip();
            if (inputField != null) {
                inputFieldIsLatest = true;
                inputField.grabFocus();
            } else {
                // Some dialogs can return null without throwing. Keep retrying on refresh.
                inputFieldIsLatest = false;
                log.warn("Starlogue: addTextField returned null (no exception); will retry");
                // #region agent log
                try {
                    DebugSessionLog.log("H_UI", "StarlogueDialogPlugin.appendInputField", "textfield-null", "{}");
                } catch (Throwable ignore) { }
                // #endregion
            }
        } catch (Throwable t) {
            log.warn("Starlogue: inline text field unavailable, falling back to paragraph-only mode", t);
            inputField = null;
            inputFieldIsLatest = false;
        }
    }

    /** Pulls text from the current input field, clears it, and sends it to the LLM. */
    private void submitCurrentInput() {
        if (state == State.WAITING) {
            // #region agent log
            try {
                DebugSessionLog.log("H_UI_SEND", "StarlogueDialogPlugin.submitCurrentInput", "ignored-while-waiting", "{}");
            } catch (Throwable ignore) { }
            // #endregion
            return; // guard — shortcut can fire while disabled
        }
        String input = (inputField != null) ? inputField.getText() : "";
        if (input == null) input = "";
        String normalized = input.replace("\r\n", "\n");
        if (normalized.trim().isEmpty()) {
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("hasInputField", inputField != null);
                d.put("inputFieldIsLatest", inputFieldIsLatest);
                DebugSessionLog.log("H_UI_SEND", "StarlogueDialogPlugin.submitCurrentInput", "ignored-empty-input", d.toString());
            } catch (Throwable ignore) { }
            // #endregion
            if (inputField != null) inputField.grabFocus();
            return;
        }
        // Snapshot & freeze the field the player just typed in so the scrollback shows
        // what was said (text remains visible) but re-editing a stale field has no effect.
        TextFieldAPI submitted = inputField;
        inputField = null;
        handlePlayerInput(normalized);
        // Let the old field keep its text visually but drop focus.
        if (submitted != null) {
            try { submitted.setText(input); } catch (Throwable ignore) {}
        }
    }

    private void handlePlayerInput(String input) {
        text.addParagraph("You: " + input);
        ConversationAuditLog.logUserMessage(conversationId, input);
        inputFieldIsLatest = false;
        sendToLLM(input);
    }

    private void sendToLLM(String userMessage) {
        try {
            sendToLLMImpl(userMessage);
        } catch (Throwable e) {
            log.error("Starlogue: sendToLLM failed synchronously", e);
            reportError("Could not prepare LLM request: " + describe(e));
        }
    }

    private void sendToLLMImpl(String userMessage) {
        LlmBackendConfig.Snapshot cfg = LlmBackendConfig.load();
        String configError = validateConfig(cfg);
        if (configError != null) {
            reportError(configError);
            return;
        }

        state = State.WAITING;
        waitTimer = 0f;
        text.addParagraph("Starlogue is processing your message -");
        inputFieldIsLatest = false;
        pendingUserMessage = userMessage;
        showMainOptions(); // refresh so "Send" is disabled while we wait

        llmRequestOrdinal++;
        boolean includeFleetSighting = ctx.fleet != null && llmRequestOrdinal == 1;
        String systemPrompt = buildSystemPrompt(includeFleetSighting);

        // Single credentials snapshot (same parse for validate + request).
        final List<LlmBackendConfig.BackendOption> backends = cfg.backends;
        String model     = cfg.model;
        float  temp      = safeGetFloat ("starlogue_temperature",  0.8f);
        int    maxTokens = safeGetInt   ("starlogue_max_tokens",   300);
        int    maxTurns  = safeGetInt   ("starlogue_history_turns", 10);

        LlmBackendConfig.BackendOption first = backends.get(0);
        log.info("Starlogue: effective LLM settings — backends=" + backends.size()
            + " firstProvider=" + first.provider
            + " firstModel=" + first.model
            + " firstApiKeyChars=" + (first.apiKey != null ? first.apiKey.length() : 0));

        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("backendCount", backends.size());
            d.put("provider", first.provider);
            d.put("apiKeyLen", first.apiKey != null ? first.apiKey.length() : 0);
            d.put("modelLen", model != null ? model.length() : 0);
            d.put("endpointLen", first.customEndpoint != null ? first.customEndpoint.length() : 0);
            DebugSessionLog.log("H_API", "StarlogueDialogPlugin.sendToLLMImpl", "backend", d.toString());
        } catch (Throwable ignore) { }
        // #endregion

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

        final List<Map<String, Object>> tools = ConstraintEngine.buildToolsArray(actionSet);
        log.info("Starlogue: sending request — tools=" + tools.size()
            + " provider=" + first.provider + " model=" + first.model);

        if (safeGetBoolean("starlogue_debug_prompts", false)) {
            log.debug("Starlogue system prompt:\n" + systemPrompt);
        }

        // Delegate background dispatch + retry to LlmDispatcher. The dispatcher owns
        // the daemon thread; we supply the per-backend client factory inline.
        final LLMRequest baseRequest = new LLMRequest(messages, tools, first.model, temp, maxTokens);
        dispatcher.dispatch(baseRequest, backends, this::createClientForBackend);
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
        ConversationAuditLog.logAssistantMessage(conversationId,
            content != null ? content : "",
            response.toolCalls != null ? response.toolCalls.size() : 0);

        if (response.reasoning != null && !response.reasoning.isBlank()) {
            ConversationAuditLog.logModelThinking(conversationId, response.reasoning);
        }
        if (response.usageSummary != null && !response.usageSummary.isBlank()) {
            try {
                JSONObject meta = new JSONObject();
                meta.put("usage", response.usageSummary);
                ConversationAuditLog.logLlmMeta(conversationId, "response", meta);
            } catch (Throwable ignored) { }
        }

        int toolCallCount = response.toolCalls.size();
        log.info("Starlogue: LLM response received — toolCalls=" + toolCallCount
            + " hasContent=" + (content != null && !content.isBlank()));
        if (safeGetBoolean("starlogue_debug_tools", false)) {
            log.debug("Starlogue: " + toolCallCount + " tool call(s)");
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
            ConversationAuditLog.logToolCall(conversationId, call.toolId, ConversationAuditLog.safeArgsJson(call.args),
                "unavailable", "tool not in current action set");
            return;
        }
        try {
            action.execute(ctx, call.args);
            toolCallMade = true;
            ConversationAuditLog.logToolCall(conversationId, call.toolId, ConversationAuditLog.safeArgsJson(call.args),
                "ok", "executed");
        } catch (Throwable t) {
            log.error("Starlogue: tool call failed: " + call.toolId, t);
            ConversationAuditLog.logToolCall(conversationId, call.toolId, ConversationAuditLog.safeArgsJson(call.args),
                "error", describe(t));
            text.addParagraph("[Tool error] " + call.toolId + " failed: " + describe(t));
            return;
        }

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
        options.addOption("Send (Enter)", OPT_SEND);
        options.addOption("End conversation", OPT_END);

        // Keyboard shortcuts — OptionPanelAPI order is (data, key, ctrl, alt, shift, putLast).
        // Plain Enter sends (single-line field); Escape ends.
        try {
            options.setShortcut(OPT_SEND, Keyboard.KEY_RETURN, false, false, false, false);
            options.setShortcut(OPT_END,  Keyboard.KEY_ESCAPE, false, false, false, false);
            dialog.setOptionOnEscape("End conversation", OPT_END);
        } catch (Throwable t) {
            // shortcuts are nice-to-have; a missing API shouldn't break the dialog.
        }

        if (state == State.WAITING) {
            // While waiting on the LLM, disable Send but keep End available so the
            // player can always bail out cleanly. Don't add a fresh input field —
            // the previous one is still visible in scrollback and the "..." animation
            // needs the text panel's last element to remain a paragraph.
            options.setEnabled(OPT_SEND, false);
            return;
        }

        // Idle — add a fresh input line at the bottom of the chat so the player can type.
        // Only add one if the current "last thing" isn't already an input field (avoids
        // stacking multiple fields if showMainOptions is called twice in a row).
        if (inputFieldIsLatest) {
            boolean stale = (inputField == null);
            if (!stale && inputField != null) {
                try {
                    // Touching the field can throw if its UI backing was disposed.
                    inputField.getText();
                } catch (Throwable t) {
                    stale = true;
                }
            }
            if (stale) {
                inputFieldIsLatest = false;
                inputField = null;
                // #region agent log
                try {
                    DebugSessionLog.log("H_UI", "StarlogueDialogPlugin.showMainOptions",
                        "detected-stale-inputfield-recreate", "{}");
                } catch (Throwable ignore) { }
                // #endregion
            }
        }
        if (!inputFieldIsLatest) {
            appendInputField();
        } else if (inputField != null) {
            inputField.grabFocus();
        }
    }

    /** Replace the last paragraph (usually "...") with an error message and hand control back to the player. */
    private void reportError(String message) {
        if (text != null) {
            try {
                if (inputFieldIsLatest) {
                    text.addParagraph("[Starlogue error] " + message);
                } else {
                    text.replaceLastParagraph("[Starlogue error] " + message);
                }
            } catch (Throwable t) {
                text.addParagraph("[Starlogue error] " + message);
            }
            text.addParagraph("You can try typing another prompt, or end the conversation.");
            inputFieldIsLatest = false;
        }
        state = State.IDLE;
        waitTimer = 0f;
        showMainOptions();
    }

    /**
     * Returns null when config looks usable, or a human-readable reason the LLM call
     * cannot proceed. Tailored per provider: only block on fields that actually matter
     * for the selected backend. LunaLib being absent is fine — defaults will be used.
     */
    private String validateConfig(LlmBackendConfig.Snapshot cfg) {
        if (cfg == null || cfg.backends == null || cfg.backends.isEmpty()) {
            return "No LLM backend is configured. Set starlogue_backends[] in saves/common/Starlogue_credentials.json "
                + "or use legacy starlogue_provider/starlogue_model fields.";
        }
        String firstError = null;
        for (int i = 0; i < cfg.backends.size(); i++) {
            String err = validateBackendOption(cfg.backends.get(i));
            if (err == null) return null; // at least one backend is runnable
            if (firstError == null) firstError = "Backend #" + (i + 1) + ": " + err;
        }
        return firstError != null ? firstError : "No usable backend configuration found.";
    }

    private String validateBackendOption(LlmBackendConfig.BackendOption b) {
        if (b == null) return "entry is null.";
        if (b.model == null || b.model.isEmpty()) {
            return "model is empty. Set starlogue_model.";
        }
        if ("custom".equals(b.provider) && (b.customEndpoint == null || b.customEndpoint.isEmpty())) {
            return "provider=custom requires starlogue_endpoint (e.g. http://localhost:8080/v1).";
        }
        if (("openai".equals(b.provider) || "anthropic".equals(b.provider) || "openrouter".equals(b.provider)
                || "xai".equals(b.provider))
                && (b.apiKey == null || b.apiKey.isEmpty())) {
            return "provider=" + b.provider + " requires starlogue_api_key.";
        }
        return null;
    }

    private LLMClient createClientForBackend(LlmBackendConfig.BackendOption b) {
        if ("anthropic".equals(b.provider)) {
            return new AnthropicClient(b.apiKey);
        } else if ("openrouter".equals(b.provider)) {
            return new OpenRouterClient(b.apiKey);
        } else if ("xai".equals(b.provider)) {
            return new XaiClient(b.apiKey, b.customEndpoint);
        } else if ("openai".equals(b.provider)) {
            return new OpenAIClient("https://api.openai.com/v1", b.apiKey);
        } else if ("ollama".equals(b.provider)) {
            return new OpenAIClient("http://localhost:11434/v1", b.apiKey);
        }
        return new OpenAIClient(b.customEndpoint, b.apiKey); // custom
    }

    private static String describe(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        String cls = t.getClass().getSimpleName();
        if (msg == null || msg.isEmpty()) return cls;
        return cls + ": " + msg;
    }

    /**
     * Explains common failures (e.g. Ollama not running → ConnectException) using effective provider
     * from credentials so the in-dialog message matches {@code starsector.log}.
     */
    private static String friendlyLlmFailure(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException) {
                LlmBackendConfig.Snapshot s = LlmBackendConfig.load();
                if ("ollama".equals(s.provider)) {
                    return "Cannot connect to Ollama (http://127.0.0.1:11434). Start the Ollama app, or edit "
                        + "saves/common/Starlogue_credentials.json: set starlogue_provider (e.g. openrouter or xai) "
                        + "and starlogue_api_key for a cloud model.";
                }
                if ("xai".equals(s.provider)) {
                    return "Cannot reach xAI (https://api.x.ai). Check your network, firewall, or "
                        + "starlogue_endpoint in saves/common/Starlogue_credentials.json if using a proxy.";
                }
                if ("custom".equals(s.provider)) {
                    return "Cannot connect to the custom LLM server. Check starlogue_endpoint in "
                        + "saves/common/Starlogue_credentials.json and that the process is running.";
                }
            }
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("HTTP 429") || msg.contains(" 429 "))) {
            LlmBackendConfig.Snapshot s = LlmBackendConfig.load();
            if ("openrouter".equals(s.provider)) {
                return "OpenRouter rate-limited or provider capacity error (HTTP 429). "
                    + "Starlogue retries once with openrouter/auto automatically. If it still fails, "
                    + "try a non-free model or retry later.";
            }
        }
        if (msg != null && (msg.contains("HTTP 502") || msg.contains(" 502 "))) {
            LlmBackendConfig.Snapshot s = LlmBackendConfig.load();
            if ("openrouter".equals(s.provider)) {
                return "OpenRouter upstream provider error (HTTP 502). "
                    + "Starlogue retries once with openrouter/auto automatically. "
                    + "If it still fails, retry later or switch model/provider route.";
            }
        }
        if (msg != null && (msg.contains("HTTP 401") || msg.contains(" 401 "))) {
            LlmBackendConfig.Snapshot s = LlmBackendConfig.load();
            if ("openrouter".equals(s.provider)) {
                if (s.apiKey.isEmpty()) {
                    return "OpenRouter: starlogue_api_key is empty in saves/common/Starlogue_credentials.json. "
                        + "Create a key at https://openrouter.ai/keys and paste it, then set starlogue_model to "
                        + "a model id (e.g. openai/gpt-4o-mini).";
                }
                return "OpenRouter returned HTTP 401. Your key may be invalid, revoked, or not pasted fully. "
                    + "Regenerate at https://openrouter.ai/keys and ensure the file is valid JSON (no extra quotes).";
            }
            if ("xai".equals(s.provider)) {
                return "xAI returned HTTP 401. Create or rotate an API key at https://console.x.ai and update "
                    + "starlogue_api_key in saves/common/Starlogue_credentials.json.";
            }
            return "LLM backend returned HTTP 401 (invalid API key). Check starlogue_api_key in "
                + "saves/common/Starlogue_credentials.json for backend: " + s.provider + ".";
        }
        if (msg != null && (msg.contains("HTTP 402") || msg.contains(" 402 "))) {
            return "LLM backend returned HTTP 402 (payment required / insufficient credits). "
                + "Top up your account credits at your provider, or switch to a free model. "
                + "For OpenRouter free models see https://openrouter.ai/models?order=newest&supported_parameters=tools";
        }
        return "LLM call failed: " + describe(e);
    }

    /** Called on any unrecoverable error. Cleans up and exits to vanilla dialog flow. */
    private void failSafe() {
        state = State.ERROR;
        // Drain any in-flight dispatcher results so they don't surface in a future dialog.
        dispatcher.poll();
        dispatcher.pollError();
        closeConversationAudit("failsafe");
        StarlogueAPI.clearCurrentContext();
        if (dialog != null) dialog.dismiss();
    }

    private void closeConversationAudit(String reason) {
        if (conversationAuditClosed) return;
        conversationAuditClosed = true;
        ConversationAuditLog.logConversationEnd(conversationId, reason);
    }

    private String buildSystemPrompt(boolean includeFleetSighting) {
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
            sb.append("- Your faction: ").append(ctx.npcFaction.getDisplayName())
              .append(" (id: ").append(ctx.npcFaction.getId()).append(")").append("\n");
        }
        // Fleet-strength line only meaningful when a fleet is actually present.
        if (ctx.fleet != null) {
            sb.append("- Fleet strength: ");
            if (ctx.strengthDelta > 0.15f) sb.append("you outgun the player\n");
            else if (ctx.strengthDelta < -0.15f) sb.append("player outguns you\n");
            else sb.append("roughly equal forces\n");
            sb.append("- Player transponder (as seen now): ")
                .append(ctx.playerTransponderOn ? "ON" : "OFF").append("\n");
        }
        if (includeFleetSighting && ctx.fleet != null) {
            sb.append("\n");
            sb.append(FleetSnapshotFormatter.formatSightingBlock(
                ctx.fleet,
                Global.getSector() != null ? Global.getSector().getPlayerFleet() : null,
                FleetSnapshotFormatter.maxShipsDefault()));
            sb.append("\n");
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

        if (ctx.fleet != null || !ctx.playerTransponderOn) {
            sb.append("\nIDENTITY / EVIDENCE RULES:\n");
            sb.append("- When the player's transponder is OFF, false identities are possible in dialogue, ");
            sb.append("but obvious fleet composition (see VISUAL_SIGHTING_REPORT when present) may contradict claims.\n");
            sb.append("- Do not claim mind-reading; if you challenge a cover story, use the challenge_* tools or ");
            sb.append("cite only visible facts from the sighting report and situation notes.\n");
            sb.append("- When the transponder is ON, pretending to be a different faction is generally implausible.\n");
        }

        sb.append("\nTOOL USAGE POLICY:\n");
        sb.append("- FACTION IDENTITY: If you are unsure what your faction (id above) stands for, ");
        sb.append("who it is allied or hostile with, or what any other faction referenced in conversation ");
        sb.append("represents, call get_faction_info immediately with that faction's id or name. ");
        sb.append("This is especially important for sub-factions (e.g. 'lions_guard' is part of ");
        sb.append("'sindrian_diktat'; call get_faction_info to confirm affiliations).\n");
        sb.append("- ACTIONS: If your reply commits to an in-world action (combat, retreat/disengage, trade, ");
        sb.append("intel sharing, reputation shift, or transfer of credits/supplies/fuel), ");
        sb.append("you must call the corresponding tool in the same response.\n");
        sb.append("- You may call multiple tools in one response when outcomes are coupled.\n");
        sb.append("- Do not claim an action happened unless a tool call executed it.\n");
        sb.append("- Use share_intel for actionable location intel (e.g. nearby derelicts, ");
        sb.append("stations, fleets, threats) with concrete details.\n");

        return sb.toString().trim();
    }

    // ── LunaLib helper methods (null-safe, NoClassDefFoundError-safe) ─────
    //
    // LunaLib is a soft dependency. If the user hasn't installed it the
    // `lunalib.lunaSettings.LunaSettings` class is absent at runtime and any
    // direct reference would throw NoClassDefFoundError the first time the
    // method is called. We resolve the class reflectively and cache whether
    // it's available so callers always get their fallback values back instead
    // of a silent crash.

    private static Boolean LUNA_AVAILABLE = null;

    private static boolean lunaLibAvailable() {
        if (LUNA_AVAILABLE != null) return LUNA_AVAILABLE;
        try {
            Class.forName("lunalib.lunaSettings.LunaSettings",
                false, StarlogueDialogPlugin.class.getClassLoader());
            LUNA_AVAILABLE = Boolean.TRUE;
        } catch (Throwable t) {
            LUNA_AVAILABLE = Boolean.FALSE;
        }
        return LUNA_AVAILABLE;
    }

    private static float safeGetFloat(String key, float fallback) {
        if (!lunaLibAvailable()) return fallback;
        // LunaLib's CSV loader stores Double-typed fields; read as Double and narrow.
        // Keep a getFloat fallback for legacy Float-typed rows (should no longer exist).
        try {
            Double d = lunalib.lunaSettings.LunaSettings.getDouble("starlogue", key);
            if (d != null) return d.floatValue();
        } catch (Throwable ignored) { }
        try {
            Float f = lunalib.lunaSettings.LunaSettings.getFloat("starlogue", key);
            if (f != null) return f;
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static int safeGetInt(String key, int fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Integer v = lunalib.lunaSettings.LunaSettings.getInt("starlogue", key);
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean safeGetBoolean(String key, boolean fallback) {
        if (!lunaLibAvailable()) return fallback;
        try {
            Boolean v = lunalib.lunaSettings.LunaSettings.getBoolean("starlogue", key);
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
