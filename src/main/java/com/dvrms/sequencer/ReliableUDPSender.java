package com.dvrms.sequencer;

import com.dvrms.common.Config;

import java.io.IOException;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Handles reliable UDP delivery to a single replica.
 * Implements stop-and-wait ACK with retransmission (500ms timeout, 5 retries).
 *
 * Each replica gets its own ReliableUDPSender running on a dedicated thread,
 * so a slow replica doesn't block delivery to others.
 */
public class ReliableUDPSender {

    private static final Logger LOG = Logger.getLogger(ReliableUDPSender.class.getName());

    private final ReplicaTarget target;
    private final DatagramSocket socket;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingAcks;
    private final RMNotifier rmNotifier;

    public ReliableUDPSender(ReplicaTarget target, DatagramSocket socket, RMNotifier rmNotifier) {
        this.target = target;
        this.socket = socket;
        this.pendingAcks = new ConcurrentHashMap<>();
        this.rmNotifier = rmNotifier;
    }

    /**
     * Sends a sequenced request to this replica with reliable delivery.
     * Retransmits up to MAX_RETRIES times if no ACK is received.
     *
     * @param seqNum   the sequence number assigned to the request
     * @param feHost   the FE's return address (host)
     * @param fePort   the FE's return address (port)
     * @param method   the CORBA method name
     * @param args     the method arguments
     * @return true if the replica ACKed, false if unreachable
     */
    public boolean sendReliably(int seqNum, String feHost, int fePort, String method, String args) {
        String msgId = UUID.randomUUID().toString();

        // Build: REQ|<msgID>|<seqNum>|<feHost>|<fePort>|<method>|<args>
        String message = String.join(Config.DELIMITER,
                Config.MSG_REQ, msgId, String.valueOf(seqNum),
                feHost, String.valueOf(fePort), method, args);

        byte[] data = message.getBytes();

        for (int attempt = 1; attempt <= Config.MAX_RETRIES; attempt++) {
            try {
                InetAddress address = InetAddress.getByName(target.getHost());
                DatagramPacket packet = new DatagramPacket(data, data.length, address, target.getPort());

                // Register pending ACK before sending
                CompletableFuture<Boolean> ackFuture = new CompletableFuture<>();
                pendingAcks.put(msgId, ackFuture);

                socket.send(packet);
                LOG.info("[Sequencer] Sent to " + target + " (attempt " + attempt + ") seqNum=" + seqNum + " msgId=" + msgId);

                // Wait for ACK with timeout
                Boolean acked = ackFuture.get(Config.ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (acked != null && acked) {
                    pendingAcks.remove(msgId);
                    LOG.info("[Sequencer] ACK received from " + target + " for seqNum=" + seqNum);
                    return true;
                }

            } catch (TimeoutException e) {
                LOG.warning("[Sequencer] ACK timeout from " + target + " (attempt " + attempt + "/" + Config.MAX_RETRIES + ") seqNum=" + seqNum);
                pendingAcks.remove(msgId);
            } catch (IOException | InterruptedException | ExecutionException e) {
                LOG.severe("[Sequencer] Error sending to " + target + ": " + e.getMessage());
                pendingAcks.remove(msgId);
            }
        }

        // All retries exhausted — report to RM
        LOG.severe("[Sequencer] Replica " + target.getReplicaId() + " unreachable after " + Config.MAX_RETRIES + " attempts. Reporting to RM.");
        target.setActive(false);
        rmNotifier.reportUnreachable(target.getReplicaId());
        return false;
    }

    /**
     * Called by the ACK listener thread when an ACK arrives.
     * Completes the pending future so sendReliably() unblocks.
     */
    public void handleAck(String msgId) {
        CompletableFuture<Boolean> future = pendingAcks.get(msgId);
        if (future != null) {
            future.complete(true);
        }
    }

    public ReplicaTarget getTarget() {
        return target;
    }
}
