package com.dvrms.sequencer;

import com.dvrms.common.Config;

import java.io.IOException;
import java.net.*;
import java.util.UUID;

/**
 * Test harness to verify the Sequencer in isolation.
 * Simulates the FE sending requests and replicas sending ACKs.
 *
 * Usage: Start the Sequencer first, then run this test.
 */
public class SequencerTest {

    private static DatagramSocket testSocket;

    public static void main(String[] args) throws Exception {
        testSocket = new DatagramSocket();

        // Start mock replicas (ACK responders) on separate threads
        Thread r1 = startMockReplica("R1", Config.REPLICA_1_PORT);
        Thread r2 = startMockReplica("R2", Config.REPLICA_2_PORT);
        Thread r3 = startMockReplica("R3", Config.REPLICA_3_PORT);
        Thread r4 = startMockReplica("R4", Config.REPLICA_4_PORT);

        // Give replicas time to start
        Thread.sleep(500);

        System.out.println("=== Test 1: Normal request forwarding ===");
        sendFERequest("reserveVehicle", "MTL|Sedan|2026-04-10|2026-04-12");
        Thread.sleep(2000);

        System.out.println("\n=== Test 2: Second request (verify sequence numbers increment) ===");
        sendFERequest("getAvailableVehicles", "MTL|2026-04-10");
        Thread.sleep(2000);

        System.out.println("\n=== Test 3: Rapid burst of requests ===");
        for (int i = 0; i < 5; i++) {
            sendFERequest("cancelReservation", "RES-" + i);
        }
        Thread.sleep(3000);

        System.out.println("\n=== Test 4: UPDATE_TARGETS from RM ===");
        sendUpdateTargets("R3", "localhost", 6010);
        Thread.sleep(1000);

        System.out.println("\n=== Test 5: Request after replica replacement ===");
        // Start a mock replica on the new port
        Thread r3New = startMockReplica("R3-new", 6010);
        Thread.sleep(500);
        sendFERequest("reserveVehicle", "TOR|SUV|2026-05-01|2026-05-03");
        Thread.sleep(2000);

        System.out.println("\n=== All tests complete ===");
        System.exit(0);
    }

    /**
     * Simulate FE sending a CLIENT_REQUEST to the Sequencer.
     * Format: SEQ_REQ|<msgID>|<feHost>|<fePort>|<method>|<args...>
     */
    private static void sendFERequest(String method, String args) throws IOException {
        String msgId = UUID.randomUUID().toString().substring(0, 8);
        String message = String.join(Config.DELIMITER,
                Config.MSG_SEQ_REQ, msgId, Config.FE_HOST,
                String.valueOf(Config.FE_PORT), method, args);

        byte[] data = message.getBytes();
        InetAddress addr = InetAddress.getByName("localhost");
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, Config.SEQUENCER_PORT);
        testSocket.send(packet);
        System.out.println("[Test FE] Sent: " + message);
    }

    /**
     * Simulate RM sending UPDATE_TARGETS to the Sequencer.
     * Format: UPDATE|<oldReplicaID>|<newHost>|<newPort>
     */
    private static void sendUpdateTargets(String replicaId, String newHost, int newPort) throws IOException {
        String message = String.join(Config.DELIMITER,
                Config.MSG_UPDATE, replicaId, newHost, String.valueOf(newPort));

        byte[] data = message.getBytes();
        InetAddress addr = InetAddress.getByName("localhost");
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, Config.SEQUENCER_PORT);
        testSocket.send(packet);
        System.out.println("[Test RM] Sent UPDATE: " + message);
    }

    /**
     * Start a mock replica that auto-ACKs any received sequenced request.
     */
    private static Thread startMockReplica(String name, int port) {
        Thread t = new Thread(() -> {
            try (DatagramSocket replicaSocket = new DatagramSocket(port)) {
                byte[] buffer = new byte[Config.BUFFER_SIZE];
                System.out.println("[Mock " + name + "] Listening on port " + port);

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    replicaSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength()).trim();
                    System.out.println("[Mock " + name + "] Received: " + message);

                    // Parse msgID and send ACK back to the Sequencer
                    if (message.startsWith(Config.MSG_REQ)) {
                        String[] parts = message.split("\\" + Config.DELIMITER);
                        if (parts.length >= 2) {
                            String msgId = parts[1];
                            String ack = Config.MSG_ACK + Config.DELIMITER + msgId;
                            byte[] ackData = ack.getBytes();
                            DatagramPacket ackPacket = new DatagramPacket(
                                    ackData, ackData.length,
                                    packet.getAddress(), Config.SEQUENCER_PORT);
                            replicaSocket.send(ackPacket);
                            System.out.println("[Mock " + name + "] Sent ACK for msgId=" + msgId);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[Mock " + name + "] Error: " + e.getMessage());
            }
        }, "Mock-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
