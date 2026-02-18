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
