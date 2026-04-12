package com.dvrms.sequencer;

import com.dvrms.common.Config;


import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Failure-Free Sequencer for the DVRMS active replication system.
 *
 * Responsibilities:
 *   1. Receive CLIENT_REQUEST messages from the FE via UDP
 *   2. Assign each request a unique, monotonically increasing sequence number
 *   3. Reliably multicast the sequenced request to all 4 replicas
 *   4. Maintain a retransmission log for reliable delivery
 *   5. Accept UPDATE_TARGETS from RM when replicas are replaced
 *   6. Report unreachable replicas to the RM after 5 failed retransmission attempts
 *
 * Message formats (pipe-delimited):
 *   Incoming from FE:    SEQ_REQ|<msgID>|<feHost>|<fePort>|<method>|<args...>
 *   Outgoing to Replicas: REQ|<msgID>|<seqNum>|<feHost>|<fePort>|<method>|<args...>
 *   Incoming ACK:         ACK|<msgID>
 *   Incoming from RM:     UPDATE|<oldReplicaID>|<newHost>|<newPort>
 */
public class Sequencer {

    private static final Logger LOG = Logger.getLogger(Sequencer.class.getName());

    // ── Sequence counter (atomic for thread safety, starts at 1) ──
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    // ── Replica targets ──
    private final ConcurrentHashMap<String, ReplicaTarget> replicaTargets = new ConcurrentHashMap<>();

    // ── Reliable senders (one per replica) ──
    private final ConcurrentHashMap<String, ReliableUDPSender> senders = new ConcurrentHashMap<>();

    // ── Retransmission log: seqNum → original message (for potential replay) ──
    private final ConcurrentHashMap<Integer, String> retransmissionLog = new ConcurrentHashMap<>();

    // ── Thread pool for parallel multicast to replicas ──
    private final ExecutorService multicastPool = Executors.newFixedThreadPool(4);

    private DatagramSocket socket;
    private RMNotifier rmNotifier;
    private volatile boolean running = true;

    public Sequencer() {
        initializeReplicaTargets();
    }

    /**
     * Initialize the 4 replica targets with their default ports.
     * In production, the RM would send these at startup.
     */
    private void initializeReplicaTargets() {
        addReplica("R1", "localhost", Config.REPLICA_1_PORT);
        addReplica("R2", "localhost", Config.REPLICA_2_PORT);
        addReplica("R3", "localhost", Config.REPLICA_3_PORT);
        addReplica("R4", "localhost", Config.REPLICA_4_PORT);
    }

    private void addReplica(String id, String host, int port) {
        ReplicaTarget target = new ReplicaTarget(id, host, port);
        replicaTargets.put(id, target);
    }

    /**
     * Start the Sequencer: open socket, create senders, and begin listening.
     */
    public void start() {
        try {
            socket = new DatagramSocket(Config.SEQUENCER_PORT);
            socket.setSoTimeout(0); // block indefinitely on receive
            rmNotifier = new RMNotifier(socket);

            // Create a reliable sender for each replica
            for (ReplicaTarget target : replicaTargets.values()) {
                senders.put(target.getReplicaId(), new ReliableUDPSender(target, socket, rmNotifier));
            }

            // Start a background thread to listen for ACKs
            Thread ackListener = new Thread(this::listenForAcks, "ACK-Listener");
            ackListener.setDaemon(true);
            ackListener.start();

            LOG.info("[Sequencer] Started on port " + Config.SEQUENCER_PORT);
            LOG.info("[Sequencer] Replica targets: " + replicaTargets.values());

            // Main loop: receive requests from FE and UPDATE_TARGETS from RM
            listen();

        } catch (SocketException e) {
            LOG.severe("[Sequencer] Failed to start: " + e.getMessage());
        }
    }

    /**
     * Main listening loop. Receives UDP datagrams and dispatches by message type.
     */
    private void listen() {
        byte[] buffer = new byte[Config.BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength()).trim();

                LOG.info("[Sequencer] Received: " + message);

                if (message.startsWith(Config.MSG_SEQ_REQ)) {
                    handleClientRequest(message);
                } else if (message.startsWith(Config.MSG_UPDATE)) {
                    handleUpdateTargets(message);
                } else if (message.startsWith(Config.MSG_ACK)) {
                    // ACKs are handled by the ACK listener thread,
                    // but if they arrive on the main socket they're routed here
                    handleAck(message);
                } else {
                    LOG.warning("[Sequencer] Unknown message type: " + message);
                }

            } catch (IOException e) {
                if (running) {
                    LOG.severe("[Sequencer] Error receiving: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Handle a CLIENT_REQUEST from the FE.
     * Format: SEQ_REQ|<msgID>|<feHost>|<fePort>|<method>|<args...>
     */
    private void handleClientRequest(String message) {
        String[] parts = message.split("\\" + Config.DELIMITER);
        if (parts.length < 5) {
            LOG.warning("[Sequencer] Malformed SEQ_REQ: " + message);
            return;
        }

        // Parse fields
        String feMsgId = parts[1];   // FE's original message ID
        String feHost = parts[2];
        int fePort = Integer.parseInt(parts[3]);
        String method = parts[4];
        // Remaining parts are the method arguments
        String args = "";
        if (parts.length > 5) {
            StringBuilder sb = new StringBuilder();
            for (int i = 5; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(Config.DELIMITER);
                sb.append(parts[i]);
            }
            args = sb.toString();
        }

        // Assign sequence number (atomic increment)
        int seqNum = sequenceCounter.incrementAndGet();

        LOG.info("[Sequencer] Assigned seqNum=" + seqNum + " to request from FE (method=" + method + ")");

        // Log for potential retransmission/replay
        retransmissionLog.put(seqNum, message);

        // Reliably multicast to all active replicas in parallel
        final String finalMsgId = feMsgId;
        final String finalArgs = args;
        final String finalFeHost = feHost;
        final int finalFePort = fePort;
        final String finalMethod = method;

        CountDownLatch latch = new CountDownLatch(getActiveReplicaCount());

        for (ReliableUDPSender sender : senders.values()) {
            if (sender.getTarget().isActive()) {
                multicastPool.submit(() -> {
                    try {
                        boolean acked = sender.sendReliably(finalMsgId, seqNum, finalFeHost, finalFePort, finalMethod, finalArgs);
                        if (acked) {
                            LOG.info("[Sequencer] Delivery confirmed to " + sender.getTarget().getReplicaId() + " for seqNum=" + seqNum);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        // Wait for all sends to complete (or timeout/fail)
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clean up retransmission log after all ACKs received
        retransmissionLog.remove(seqNum);
        LOG.info("[Sequencer] Completed multicast for seqNum=" + seqNum);
    }

    /**
     * Handle UPDATE_TARGETS from the Replica Manager.
     * Format: UPDATE|<oldReplicaID>|<newHost>|<newPort>
     */
    private void handleUpdateTargets(String message) {
        String[] parts = message.split("\\" + Config.DELIMITER);
        if (parts.length < 4) {
            LOG.warning("[Sequencer] Malformed UPDATE: " + message);
            return;
        }

        String oldReplicaId = parts[1];
        String newHost = parts[2];
        int newPort = Integer.parseInt(parts[3]);

        ReplicaTarget target = replicaTargets.get(oldReplicaId);
        if (target != null) {
            target.updateEndpoint(newHost, newPort);
            // Create a new sender for the updated target
            senders.put(oldReplicaId, new ReliableUDPSender(target, socket, rmNotifier));
            LOG.info("[Sequencer] Updated replica " + oldReplicaId + " → " + newHost + ":" + newPort);
        } else {
            LOG.warning("[Sequencer] Unknown replica ID in UPDATE: " + oldReplicaId);
        }
    }

    /**
     * Route an ACK message to the appropriate sender.
     * Format: ACK|<msgID>
     */
    private void handleAck(String message) {
        String[] parts = message.split("\\" + Config.DELIMITER);
        if (parts.length < 2) return;
        String msgId = parts[1];

        // Broadcast to all senders — only the one with the matching pending ACK will react
        for (ReliableUDPSender sender : senders.values()) {
            sender.handleAck(msgId);
        }
    }

    /**
     * Background thread that listens for ACK messages from replicas.
     * Since we use a single DatagramSocket, ACKs arrive on the same socket.
     * This thread is handled via the main listen() loop dispatching ACK messages.
     */
    private void listenForAcks() {
        // ACKs are handled in the main listen() loop since they share the same socket.
        // This thread is reserved for a separate ACK socket if needed in the future.
        LOG.info("[Sequencer] ACK listener thread started (ACKs handled via main loop).");
    }

    private int getActiveReplicaCount() {
        int count = 0;
        for (ReplicaTarget t : replicaTargets.values()) {
            if (t.isActive()) count++;
        }
        return count;
    }

    public void stop() {
        running = false;
        multicastPool.shutdown();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        LOG.info("[Sequencer] Stopped.");
    }

    // ── Entry point ──
    public static void main(String[] args) {
        Sequencer sequencer = new Sequencer();
        Runtime.getRuntime().addShutdownHook(new Thread(sequencer::stop));
        sequencer.start();
    }
}
