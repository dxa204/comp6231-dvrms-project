package com.dvrms.replica_manager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal UDP replica used by ReplicaManagerRecoveryTest.
 *
 * Default behavior mirrors replica1 enough for RM probing:
 *   - listens on UDP 6001
 *   - replies PONG to PING|1
 *   - exits on CRASH|1
 */
public class FakeReplicaProcess {

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("dvrms.test.fakeReplica.port", 6001);
        int replicaId = Integer.getInteger("dvrms.test.fakeReplica.id", 1);

        try (DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"))) {
            byte[] buffer = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                if (message.equals("PING|" + replicaId)) {
                    byte[] payload = ("PONG|" + replicaId).getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort()));
                    continue;
                }
                if (message.equals("CRASH|" + replicaId)) {
                    return;
                }
            }
        }
    }
}
