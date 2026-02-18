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
