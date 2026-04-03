package com.dvrms.common;

/**
 * Shared configuration constants for the DVRMS system.
 * All team members should use these constants to ensure consistency.
 */
public class Config {

    // ── Sequencer ──
    public static final String SEQUENCER_HOST = "localhost";
    public static final int SEQUENCER_PORT = 5000;

    // ── Front End ──
    public static final String FE_HOST = "localhost";
    public static final int FE_PORT = 5001;

    // ── Replica base ports (each replica on a different port) ──
    public static final int REPLICA_1_PORT = 6001;
    public static final int REPLICA_2_PORT = 6002;
    public static final int REPLICA_3_PORT = 6003;
    public static final int REPLICA_4_PORT = 6004;

    // ── Replica Manager ports ──
    public static final int RM_1_PORT = 7001;
    public static final int RM_2_PORT = 7002;
    public static final int RM_3_PORT = 7003;
    public static final int RM_4_PORT = 7004;

    // ── Reliable UDP settings ──
    public static final int ACK_TIMEOUT_MS = 500;
    public static final int MAX_RETRIES = 5;
    public static final int BUFFER_SIZE = 4096;

    // ── Message type prefixes ──
    public static final String MSG_SEQ_REQ = "SEQ_REQ";       // FE → Sequencer
    public static final String MSG_REQ = "REQ";               // Sequencer → Replica
    public static final String MSG_ACK = "ACK";               // Replica → Sequencer
    public static final String MSG_UPDATE = "UPDATE";          // RM → Sequencer
    public static final String MSG_FAULT_REPORT = "FAULT";     // FE → RM
    public static final String MSG_CRASH_SUSPECT = "CRASH";    // FE/Sequencer → RM
    public static final String MSG_RESULT = "RESULT";          // Replica → FE

    public static final String DELIMITER = "|";
}
