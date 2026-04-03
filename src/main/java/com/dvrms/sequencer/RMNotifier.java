package com.dvrms.sequencer;

import com.dvrms.common.Config;

import java.io.IOException;
import java.net.*;
import java.util.logging.Logger;

/**
 * Sends failure notifications to all Replica Managers.
 * Used by the Sequencer when a replica becomes unreachable.
 */
public class RMNotifier {

    private static final Logger LOG = Logger.getLogger(RMNotifier.class.getName());

    private final int[] rmPorts = {
            Config.RM_1_PORT, Config.RM_2_PORT,
            Config.RM_3_PORT, Config.RM_4_PORT
    };

    private final DatagramSocket socket;

    public RMNotifier(DatagramSocket socket) {
        this.socket = socket;
    }

    /**
     * Notify all RMs that a replica is unreachable (suspected crash).
     * Message format: CRASH|<replicaId>
     */
    public void reportUnreachable(String replicaId) {
        String message = Config.MSG_CRASH_SUSPECT + Config.DELIMITER + replicaId;
        byte[] data = message.getBytes();

        for (int rmPort : rmPorts) {
            try {
                InetAddress addr = InetAddress.getByName("localhost"); // adjust for LAN
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, rmPort);
                socket.send(packet);
                LOG.info("[Sequencer] Reported unreachable replica " + replicaId + " to RM on port " + rmPort);
            } catch (IOException e) {
                LOG.severe("[Sequencer] Failed to notify RM on port " + rmPort + ": " + e.getMessage());
            }
        }
    }
}
