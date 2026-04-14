package com.dvrms.common;

/**
 * Shared configuration constants for the DVRMS system.
 * All team members should use these constants to ensure consistency.
 */
public class Config {

    private static String host(String propertyName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    // ── Sequencer ──
    public static final String SEQUENCER_HOST = host("dvrms.sequencer.host", "localhost");
    public static final int SEQUENCER_PORT = 5000;

    // ── Front End ──
    public static final String FE_HOST = host("dvrms.fe.host", "localhost");
    public static final int FE_PORT = 5001;

    // ── Fixed replica host mapping for multi-laptop deployment ──
    public static final String REPLICA_1_HOST = host("dvrms.replica1.host", "localhost");
    public static final String REPLICA_2_HOST = host("dvrms.replica2.host", "localhost");
    public static final String REPLICA_3_HOST = host("dvrms.replica3.host", "localhost");
    public static final String REPLICA_4_HOST = host("dvrms.replica4.host", "localhost");

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

    public static final String RM_1_HOST = host("dvrms.rm1.host", "localhost");
    public static final String RM_2_HOST = host("dvrms.rm2.host", "localhost");
    public static final String RM_3_HOST = host("dvrms.rm3.host", "localhost");
    public static final String RM_4_HOST = host("dvrms.rm4.host", "localhost");

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

    public static String replicaHost(String replicaId) {
        switch (replicaId) {
            case "R1":
                return REPLICA_1_HOST;
            case "R2":
                return REPLICA_2_HOST;
            case "R3":
                return REPLICA_3_HOST;
            case "R4":
                return REPLICA_4_HOST;
            default:
                throw new IllegalArgumentException("Unknown replica id: " + replicaId);
        }
    }

    public static int replicaPort(String replicaId) {
        switch (replicaId) {
            case "R1":
                return REPLICA_1_PORT;
            case "R2":
                return REPLICA_2_PORT;
            case "R3":
                return REPLICA_3_PORT;
            case "R4":
                return REPLICA_4_PORT;
            default:
                throw new IllegalArgumentException("Unknown replica id: " + replicaId);
        }
    }

    public static String rmHost(int rmId) {
        switch (rmId) {
            case 1:
                return RM_1_HOST;
            case 2:
                return RM_2_HOST;
            case 3:
                return RM_3_HOST;
            case 4:
                return RM_4_HOST;
            default:
                throw new IllegalArgumentException("Unknown RM id: " + rmId);
        }
    }

    public static int rmPort(int rmId) {
        switch (rmId) {
            case 1:
                return RM_1_PORT;
            case 2:
                return RM_2_PORT;
            case 3:
                return RM_3_PORT;
            case 4:
                return RM_4_PORT;
            default:
                throw new IllegalArgumentException("Unknown RM id: " + rmId);
        }
    }
}
