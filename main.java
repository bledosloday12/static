/*
 * Static — Chatter-bot contract. Session-scoped replies and intent matching;
 * realm and node bindings are fixed at init. No user fill-in; all ids and addresses pre-set.
 */

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chatter-bot contract: sessions, intents, reply rules. Realm id and node addresses
 * are assigned at construction and never change.
 */
public final class Static {

    // ---------------------------------------------------------------------------
    // Constants — unique names, never reused from other contracts
    // ---------------------------------------------------------------------------

    public static final String STATIC_REALM_ID = "0x1f7a3b9e5c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d0e2f4";
    public static final int MAX_UTTERANCE_LEN = 512;
    public static final int MAX_SESSIONS_PER_REALM = 65536;
    public static final int MAX_REPLY_LEN = 1024;
    public static final long SESSION_TTL_MS = 3600_000L;
    public static final int RATE_LIMIT_UTTERANCES_PER_MIN = 60;
    public static final byte CHANNEL_FLAG_DEFAULT = 0x01;
    public static final byte CHANNEL_FLAG_VERBOSE = 0x02;
    public static final String DEPLOYMENT_SALT = "c4e8a1f5b9d2e7a0c3f6b8d1e4a7c0f3b6e9d2a5";

    /** Node addresses — unique, not used in any other contract or generation */
    public static final String NODE_PRIMARY = "0x1F7a3B9e5C0d2E4f6A8b0c2D4e6F8a0B2c4D6e8";
    public static final String NODE_REPLICA = "0x6E0b3D5f7A9c1E4b6D8f0A2c4E6b8D0f2A4c6E8";
    public static final String NODE_FALLBACK = "0xA3c5E7f9B1d4F6a8C0e2B4d6F8a0C2e4B6d8F0";

    public enum StaticEvent {
        SESSION_OPENED,
        UTTERANCE_RECEIVED,
        REPLY_EMITTED,
        SESSION_CLOSED,
        INTENT_MATCHED,
        RATE_LIMIT_HIT
    }

    // ---------------------------------------------------------------------------
    // Exceptions — unique names
    // ---------------------------------------------------------------------------

    public static final class StaticSessionExpiredException extends IllegalStateException {
        public StaticSessionExpiredException(String sessionId) {
            super("Static: session expired or invalid: " + sessionId);
        }
    }

    public static final class StaticIntentUnknownException extends IllegalArgumentException {
        public StaticIntentUnknownException(String intent) {
            super("Static: unknown intent: " + intent);
        }
    }

    public static final class StaticRealmMismatchException extends SecurityException {
        public StaticRealmMismatchException() {
            super("Static: realm id mismatch");
        }
    }

    public static final class StaticUtteranceTooLongException extends IllegalArgumentException {
        public StaticUtteranceTooLongException(int len) {
            super("Static: utterance length " + len + " exceeds max " + MAX_UTTERANCE_LEN);
        }
    }

    public static final class StaticRateLimitExceededException extends IllegalStateException {
        public StaticRateLimitExceededException() {
            super("Static: rate limit exceeded");
        }
    }

    public static final class StaticSessionCapReachedException extends IllegalStateException {
        public StaticSessionCapReachedException() {
            super("Static: max sessions per realm reached");
        }
    }

    // ---------------------------------------------------------------------------
    // Config — immutable bindings
    // ---------------------------------------------------------------------------

    public static final class StaticConfig {
        private final String realmId;
        private final String primaryNode;
        private final String replicaNode;
        private final String fallbackNode;
        private final long createdAtMs;

        public StaticConfig(String realmId, String primaryNode, String replicaNode, String fallbackNode) {
            this.realmId = Objects.requireNonNull(realmId);
            this.primaryNode = Objects.requireNonNull(primaryNode);
            this.replicaNode = Objects.requireNonNull(replicaNode);
            this.fallbackNode = Objects.requireNonNull(fallbackNode);
            this.createdAtMs = System.currentTimeMillis();
        }

        public String getRealmId() { return realmId; }
        public String getPrimaryNode() { return primaryNode; }
        public String getReplicaNode() { return replicaNode; }
        public String getFallbackNode() { return fallbackNode; }
        public long getCreatedAtMs() { return createdAtMs; }
    }

    // ---------------------------------------------------------------------------
    // Reply rule — pattern + responses
    // ---------------------------------------------------------------------------

    public static final class ReplyRule {
        private final String intentId;
        private final Pattern pattern;
        private final List<String> responses;
        private final int priority;

        public ReplyRule(String intentId, Pattern pattern, List<String> responses, int priority) {
            this.intentId = Objects.requireNonNull(intentId);
            this.pattern = Objects.requireNonNull(pattern);
            this.responses = Collections.unmodifiableList(new ArrayList<>(responses));
            this.priority = priority;
        }

        public String getIntentId() { return intentId; }
        public Pattern getPattern() { return pattern; }
        public List<String> getResponses() { return responses; }
        public int getPriority() { return priority; }
    }

    // ---------------------------------------------------------------------------
    // Session — per-user conversation state
    // ---------------------------------------------------------------------------

    public static final class ChatterSession {
        private final String sessionId;
        private final String realmId;
        private final long openedAtMs;
        private long lastUtteranceAtMs;
        private int utteranceCountThisMinute;
        private long minuteWindowStartMs;
        private final List<String> history;
        private final Map<String, Object> context;

        public ChatterSession(String sessionId, String realmId) {
            this.sessionId = Objects.requireNonNull(sessionId);
            this.realmId = Objects.requireNonNull(realmId);
            this.openedAtMs = System.currentTimeMillis();
            this.lastUtteranceAtMs = openedAtMs;
            this.utteranceCountThisMinute = 0;
            this.minuteWindowStartMs = openedAtMs;
            this.history = new ArrayList<>();
            this.context = new ConcurrentHashMap<>();
        }

        public String getSessionId() { return sessionId; }
        public String getRealmId() { return realmId; }
        public long getOpenedAtMs() { return openedAtMs; }
        public long getLastUtteranceAtMs() { return lastUtteranceAtMs; }
        public List<String> getHistory() { return Collections.unmodifiableList(new ArrayList<>(history)); }
        public Map<String, Object> getContext() { return new HashMap<>(context); }

        public void recordUtterance() {
            long now = System.currentTimeMillis();
            if (now - minuteWindowStartMs >= 60_000) {
                minuteWindowStartMs = now;
                utteranceCountThisMinute = 0;
            }
            utteranceCountThisMinute++;
            lastUtteranceAtMs = now;
        }

        public void appendHistory(String entry) {
            history.add(entry);
        }

        public void putContext(String key, Object value) {
            context.put(key, value);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastUtteranceAtMs > SESSION_TTL_MS;
        }

        public boolean isRateLimitOk() {
            return utteranceCountThisMinute <= RATE_LIMIT_UTTERANCES_PER_MIN;
        }
    }

    // ---------------------------------------------------------------------------
    // Intent match result
    // ---------------------------------------------------------------------------

    public static final class IntentMatch {
        private final String intentId;
        private final ReplyRule rule;
        private final String chosenResponse;

        public IntentMatch(String intentId, ReplyRule rule, String chosenResponse) {
            this.intentId = intentId;
            this.rule = rule;
            this.chosenResponse = chosenResponse;
        }

        public String getIntentId() { return intentId; }
        public ReplyRule getRule() { return rule; }
        public String getChosenResponse() { return chosenResponse; }
    }

    // ---------------------------------------------------------------------------
    // Contract state
    // ---------------------------------------------------------------------------

    private final StaticConfig config;
    private final Map<String, ChatterSession> sessions;
    private final List<ReplyRule> replyRules;
    private final List<StaticEvent> eventLog;
    private int totalUtterancesProcessed;

    public Static() {
        this.config = new StaticConfig(
                STATIC_REALM_ID,
                NODE_PRIMARY,
                NODE_REPLICA,
                NODE_FALLBACK
        );
        this.sessions = new ConcurrentHashMap<>();
        this.replyRules = new ArrayList<>();
        this.eventLog = Collections.synchronizedList(new ArrayList<>());
        this.totalUtterancesProcessed = 0;
        registerBuiltInIntents();
    }

    /** Build all built-in intent rules and responses. */
    private void registerBuiltInIntents() {
        // Greeting intents
        replyRules.add(new ReplyRule("greeting_hi",
                Pattern.compile("^(hi|hello|hey|howdy|yo|greetings?)\\s*!?\\s*$", Pattern.CASE_INSENSITIVE),
                List.of(
                        "Hi there. What can I do for you?",
                        "Hello. How are you today?",
                        "Hey. Need any help?"
                ), 10));
        replyRules.add(new ReplyRule("greeting_morning",
                Pattern.compile("(good\\s+)?m(o(rn)?ing|ornin')", Pattern.CASE_INSENSITIVE),
                List.of("Good morning. Ready when you are.", "Morning. What's on your mind?"), 10));
        replyRules.add(new ReplyRule("greeting_evening",
                Pattern.compile("(good\\s+)?(evening|evnin'|night)", Pattern.CASE_INSENSITIVE),
                List.of("Good evening. How can I help?", "Evening. Ask me anything."), 10));

        // Question intents
        replyRules.add(new ReplyRule("question_what",
                Pattern.compile("what\\s+(is|are|do|does|did|can|could)\\b", Pattern.CASE_INSENSITIVE),
                List.of(
                        "I'm not sure of the full context. Can you narrow it down?",
                        "That depends on what you mean. Tell me more.",
                        "Good question. I'll need a bit more detail."
                ), 5));
        replyRules.add(new ReplyRule("question_how",
                Pattern.compile("how\\s+(do|does|did|can|could|would)\\b", Pattern.CASE_INSENSITIVE),
                List.of("There are a few ways. What are you trying to do?", "I can walk you through it. What exactly?", "It depends on the case. More details?"), 5));
        replyRules.add(new ReplyRule("question_why",
                Pattern.compile("why\\s+(is|are|do|does|did|can't|cannot)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Reasons vary. What situation are you in?", "I'd need more context to say why.", "Good to ask why. Can you describe the case?"), 5));
        replyRules.add(new ReplyRule("question_when",
                Pattern.compile("when\\s+(is|are|do|does|did|can)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Timing depends on a few things. What are you referring to?", "I don't have a schedule in front of me. More context?", "When varies. What do you need to know?"), 5));
        replyRules.add(new ReplyRule("question_where",
                Pattern.compile("where\\s+(is|are|do|does|did|can)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Location depends. What are you looking for?", "I'd need more detail to point you somewhere.", "Where varies. Can you specify?"), 5));

        // Help / support
        replyRules.add(new ReplyRule("help_general",
                Pattern.compile("\\b(help|support|assist)\\b", Pattern.CASE_INSENSITIVE),
                List.of(
                        "I'm here to chat. Tell me what you need.",
                        "Sure. What do you want help with?",
                        "I can try to help. What's the issue?"
                ), 7));
        replyRules.add(new ReplyRule("help_stuck",
                Pattern.compile("\\b(stuck|confused|don't understand)\\b", Pattern.CASE_INSENSITIVE),
                List.of("No worries. Describe what you're doing and where it stops.", "Take your time. What step are you on?", "I can try to clarify. What part is confusing?"), 7));

        // Thanks / goodbye
        replyRules.add(new ReplyRule("thanks",
                Pattern.compile("\\b(thanks?|thank you|ty|thx)\\b", Pattern.CASE_INSENSITIVE),
                List.of("You're welcome.", "Glad to help.", "Anytime."), 8));
        replyRules.add(new ReplyRule("goodbye",
                Pattern.compile("\\b(bye|goodbye|see ya|later|gtg|gotta go)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Bye. Come back if you need anything.", "See you later.", "Take care."), 9));

        // Opinion / feeling
        replyRules.add(new ReplyRule("opinion_like",
                Pattern.compile("\\b(i like|i love|i enjoy)\\b", Pattern.CASE_INSENSITIVE),
                List.of("That's nice to hear.", "Good to know.", "Thanks for sharing."), 4));
        replyRules.add(new ReplyRule("opinion_dislike",
                Pattern.compile("\\b(i hate|i don't like|dislike)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Understood. Anything else I can help with?", "Got it. Want to talk about something else?", "Noted."), 4));
        replyRules.add(new ReplyRule("feeling_good",
                Pattern.compile("\\b(good|great|fine|ok|okay|doing well)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Good to hear.", "That's great.", "Nice."), 4));
        replyRules.add(new ReplyRule("feeling_bad",
                Pattern.compile("\\b(bad|terrible|awful|not good|sad)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Sorry to hear that. Want to talk about it?", "That sounds tough. I'm here if you need to vent.", "Hope things get better."), 6));

        // Confirmation / negation
        replyRules.add(new ReplyRule("confirm_yes",
                Pattern.compile("^(yes|yeah|yep|yup|sure|ok|okay|correct|right)\\s*!?\\s*$", Pattern.CASE_INSENSITIVE),
                List.of("Got it.", "Understood.", "Alright."), 3));
        replyRules.add(new ReplyRule("confirm_no",
                Pattern.compile("^(no|nope|nah|negative)\\s*!?\\s*$", Pattern.CASE_INSENSITIVE),
                List.of("Understood.", "No problem.", "Alright."), 3));

        // Static / bot identity
        replyRules.add(new ReplyRule("identity_who",
                Pattern.compile("\\b(who are you|what are you|are you a bot|your name)\\b", Pattern.CASE_INSENSITIVE),
                List.of(
                        "I'm Static, a chatter bot. I reply from fixed rules and intents.",
                        "Static here — a bot. I match what you say to predefined replies.",
                        "I'm Static. Just a chatter contract with sessions and intents."
                ), 8));
        replyRules.add(new ReplyRule("identity_what_can_you_do",
                Pattern.compile("\\b(what can you do|what do you do|your purpose|capabilities)\\b", Pattern.CASE_INSENSITIVE),
                List.of(
                        "I match your messages to intents and send back one of several possible replies.",
                        "I run in a session: you talk, I reply from a set of rules.",
                        "Chatter only: intent matching and session-scoped replies."
                ), 8));

        // Small talk
        replyRules.add(new ReplyRule("smalltalk_weather",
                Pattern.compile("\\b(weather|rain|sunny|cold|hot|temperature)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't have weather data. How's it where you are?", "No weather feed here. You can describe it.", "I'm just text. Tell me about your weather."), 2));
        replyRules.add(new ReplyRule("smalltalk_time",
                Pattern.compile("\\b(time|date|day|today)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't have a live clock. Your device does.", "No time source here. Check your system time.", "I'm stateless about time. What do you need?"), 2));
        replyRules.add(new ReplyRule("smalltalk_joke",
                Pattern.compile("\\b(joke|funny|make me laugh)\\b", Pattern.CASE_INSENSITIVE),
                List.of(
                        "Why did the bot go to school? To improve its learning rate.",
                        "I'm a static contract — my jokes are constant.",
                        "I'd tell a joke but my responses are predefined."
                ), 2));

        // Fallback
        replyRules.add(new ReplyRule("fallback",
                Pattern.compile(".*"),
                List.of(
                        "I'm not sure how to reply to that. Try rephrasing.",
                        "I didn't match that to an intent. Want to try something else?",
                        "No specific rule for that. Ask in another way?"
                ), 0));

        // More intents for coverage
        replyRules.add(new ReplyRule("command_status",
                Pattern.compile("\\b(status|ping|alive|running)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I'm running. Session-based chatter contract.", "Status: active. Send me a message.", "Alive. Static realm " + STATIC_REALM_ID.substring(0, 10) + "..."), 6));
        replyRules.add(new ReplyRule("command_clear",
                Pattern.compile("\\b(clear|reset|start over)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't clear history in-session. You can open a new session.", "History stays for this session. Open another for a fresh start.", "No clear command. New session = new history."), 5));
        replyRules.add(new ReplyRule("meta_meaning",
                Pattern.compile("\\b(meaning of life|42|why do we exist)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I'm a static contract. I leave philosophy to you.", "42 might be the answer. I'm just matching intents.", "Existence is outside my reply set."), 1));
        replyRules.add(new ReplyRule("meta_robot",
                Pattern.compile("\\b(robot|machine|ai|artificial)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I'm a rule-based chatter bot, not a general AI.", "I match patterns and pick from fixed replies.", "Static contract: no learning, just rules."), 5));
        replyRules.add(new ReplyRule("feedback_positive",
                Pattern.compile("\\b(perfect|excellent|awesome|good job|well done)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Thanks.", "Glad it helped.", "Appreciate it."), 4));
        replyRules.add(new ReplyRule("feedback_negative",
                Pattern.compile("\\b(wrong|bad answer|not helpful|useless)\\b", Pattern.CASE_INSENSITIVE),
                List.of("Sorry. Try rephrasing or ask something else.", "I'll try to do better. What would help?", "Noted. I have limited replies."), 5));
        replyRules.add(new ReplyRule("repeat",
                Pattern.compile("\\b(repeat|say that again|what did you say)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't store my last reply. Ask again and I'll reply anew.", "I'm stateless per turn. Send the same message again.", "No repeat buffer. Resend your question."), 3));
        replyRules.add(new ReplyRule("unknown_word",
                Pattern.compile("\\b(what does .+ mean|define .+|meaning of .+)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't have a dictionary. Try a general question.", "No definitions in my rule set.", "I can't define words. Rephrase?"), 2));
        replyRules.add(new ReplyRule("choice_a_or_b",
                Pattern.compile("\\b(or|either|choose|pick one)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I don't choose between options. State what you want.", "Pick one yourself and tell me.", "I don't have preferences. You choose."), 3));
        replyRules.add(new ReplyRule("advice",
                Pattern.compile("\\b(advice|recommend|suggest|should i)\\b", Pattern.CASE_INSENSITIVE),
                List.of("I can't give personal advice. I only have canned replies.", "No recommendations in my set. Describe your case?", "I'm not an advisor. General chat only."), 4));
    }

    private void emit(StaticEvent ev) {
        eventLog.add(ev);
    }

    /** Open a new session. */
    public String openSession() {
        if (sessions.size() >= MAX_SESSIONS_PER_REALM) {
            emit(StaticEvent.RATE_LIMIT_HIT);
            throw new StaticSessionCapReachedException();
        }
        String sessionId = UUID.randomUUID().toString();
        ChatterSession session = new ChatterSession(sessionId, config.getRealmId());
        sessions.put(sessionId, session);
        emit(StaticEvent.SESSION_OPENED);
        return sessionId;
    }

    /** Validate realm and session. */
    private ChatterSession resolveSession(String sessionId) {
        if (!config.getRealmId().equals(STATIC_REALM_ID)) {
            throw new StaticRealmMismatchException();
        }
        ChatterSession session = sessions.get(sessionId);
        if (session == null || session.isExpired()) {
            throw new StaticSessionExpiredException(sessionId);
        }
        return session;
    }

    /** Validate utterance length. */
    private static void validateUtterance(String utterance) {
        if (utterance == null || utterance.length() > MAX_UTTERANCE_LEN) {
            throw new StaticUtteranceTooLongException(utterance == null ? 0 : utterance.length());
        }
    }

    /** Find best-matching rule and pick a response. */
    public IntentMatch matchIntent(String utterance) {
        String normalized = utterance.trim();
        for (ReplyRule rule : replyRules) {
            if (rule.getPattern().matcher(normalized).matches()) {
                List<String> resp = rule.getResponses();
                String chosen = resp.get((int) (System.nanoTime() % resp.size()));
                return new IntentMatch(rule.getIntentId(), rule, chosen);
            }
        }
        ReplyRule fallback = replyRules.stream()
                .filter(r -> "fallback".equals(r.getIntentId()))
                .findFirst()
                .orElseThrow(() -> new StaticIntentUnknownException("fallback"));
        List<String> resp = fallback.getResponses();
        String chosen = resp.get((int) (System.nanoTime() % resp.size()));
        return new IntentMatch("fallback", fallback, chosen);
    }

    /**
     * Send an utterance in a session and get a reply.
     */
    public String sendUtterance(String sessionId, String utterance) {
        validateUtterance(utterance);
        ChatterSession session = resolveSession(sessionId);
        if (!session.isRateLimitOk()) {
            emit(StaticEvent.RATE_LIMIT_HIT);
            throw new StaticRateLimitExceededException();
        }
        session.recordUtterance();
        session.appendHistory("user: " + utterance);
        emit(StaticEvent.UTTERANCE_RECEIVED);

        IntentMatch match = matchIntent(utterance);
        String reply = match.getChosenResponse();
        if (reply.length() > MAX_REPLY_LEN) {
            reply = reply.substring(0, MAX_REPLY_LEN);
        }
        session.appendHistory("static: " + reply);
        totalUtterancesProcessed++;
        emit(StaticEvent.REPLY_EMITTED);
        emit(StaticEvent.INTENT_MATCHED);
        return reply;
    }

    /** Close a session. */
    public void closeSession(String sessionId) {
        ChatterSession session = sessions.remove(sessionId);
        if (session != null) {
            emit(StaticEvent.SESSION_CLOSED);
        }
    }

    /** List registered intent ids. */
    public List<String> listIntents() {
        List<String> out = new ArrayList<>();
        for (ReplyRule r : replyRules) {
            if (!out.contains(r.getIntentId())) {
                out.add(r.getIntentId());
            }
        }
        return out;
    }
