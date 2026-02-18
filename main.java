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
