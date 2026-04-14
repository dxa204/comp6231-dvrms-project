package com.dvrms.replica_manager;

import com.dvrms.common.Config;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executable integration harness for RM restart/replacement behavior.
 *
 * Verifies that an owning RM:
 *   - confirms crash through RM coordination
 *   - restarts its replica
 *   - notifies the Sequencer with UPDATE
 *   - notifies peer RMs with RECOVER
 */
public class ReplicaManagerRecoveryTest {

    public static void main(String[] args) throws Exception {
        new ReplicaManagerRecoveryTest().run();
    }

    private void run() throws Exception {
        System.setProperty("rm.host", "127.0.0.1");
        System.setProperty("sequencer.host", "127.0.0.1");
        System.setProperty("dvrms.replica1.mainClass", "com.dvrms.replica_manager.FakeReplicaProcess");
        System.setProperty("dvrms.test.fakeReplica.port", String.valueOf(Config.REPLICA_1_PORT));
        System.setProperty("dvrms.test.fakeReplica.id", "1");

        MockSequencer sequencer = new MockSequencer();
        MockPeerRM rm2 = new MockPeerRM(Config.RM_2_PORT, Config.RM_1_PORT, "NO_RESPONSE");
        MockPeerRM rm3 = new MockPeerRM(Config.RM_3_PORT, Config.RM_1_PORT, "NO_RESPONSE");
        MockPeerRM rm4 = new MockPeerRM(Config.RM_4_PORT, Config.RM_1_PORT, "NO_RESPONSE");

        try {
            sequencer.start();
            rm2.start();
            rm3.start();
            rm4.start();

            ReplicaManager rm1 = new ReplicaManager(1, "127.0.0.1");
            rm1.addPeerRM(2, "127.0.0.1", Config.RM_2_PORT);
            rm1.addPeerRM(3, "127.0.0.1", Config.RM_3_PORT);
            rm1.addPeerRM(4, "127.0.0.1", Config.RM_4_PORT);
            rm1.start();

            Thread.sleep(300);
            sendRegister();
            Thread.sleep(300);
            sendCrashSuspicion();

            waitUntil(() -> sequencer.seen("UPDATE|1|127.0.0.1|6001"), 8000,
                    "Sequencer did not receive UPDATE for restarted replica");
            waitUntil(() -> rm2.seen("RECOVER|1|127.0.0.1|6001")
                            && rm3.seen("RECOVER|1|127.0.0.1|6001")
                            && rm4.seen("RECOVER|1|127.0.0.1|6001"),
                    4000,
                    "Peer RMs did not receive RECOVER notice");
            waitUntil(this::fakeReplicaRespondsToPing, 4000,
                    "Restarted fake replica did not bind and answer PING");

            System.out.println("PASS: RM restart and replacement flow completed");
            stopFakeReplica();
        } finally {
            sequencer.stop();
            rm2.stop();
            rm3.stop();
            rm4.stop();
            stopFakeReplica();
        }
    }

    private void sendRegister() throws Exception {
        sendAndAck(Config.RM_1_PORT, "REGISTER|1|ALL|6001");
    }

    private void sendCrashSuspicion() throws Exception {
        sendAndAck(Config.RM_1_PORT, "CRASH|R1");
    }

    private void sendAndAck(int port, String message) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1000);
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), port);
            socket.send(packet);
            DatagramPacket ack = new DatagramPacket(new byte[64], 64);
            socket.receive(ack);
        }
    }

    private boolean fakeReplicaRespondsToPing() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(400);
            byte[] payload = "PING|1".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("127.0.0.1"), Config.REPLICA_1_PORT));
            DatagramPacket reply = new DatagramPacket(new byte[64], 64);
            socket.receive(reply);
            String message = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8).trim();
            return message.startsWith("PONG|1");
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    private void stopFakeReplica() {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] payload = "CRASH|1".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("127.0.0.1"), Config.REPLICA_1_PORT));
        } catch (Exception ignored) {
            // Replica may not be running.
        }
    }

    private void waitUntil(Check check, long timeoutMs, String failureMessage) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.evaluate()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(failureMessage);
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private static final class MockSequencer extends Listener {
        private MockSequencer() throws Exception {
            super(Config.SEQUENCER_PORT);
        }
    }

    private static final class MockPeerRM extends Listener {
        private final int replyPort;
        private final String statusObservation;

        private MockPeerRM(int listenPort, int replyPort, String statusObservation) throws Exception {
            super(listenPort);
            this.replyPort = replyPort;
            this.statusObservation = statusObservation;
        }

        @Override
        protected void handle(String message) throws Exception {
            if (message.startsWith("CHECK|")) {
                String replicaId = message.split("\\|")[1];
                try (DatagramSocket socket = new DatagramSocket()) {
                    byte[] data = ("STATUS|" + replicaId + "|" + statusObservation).getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), replyPort));
                }
            }
        }
    }

    private static class Listener {
        private final DatagramSocket socket;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;
        private Thread thread;

        private Listener(int port) throws Exception {
            this.socket = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"));
            this.socket.setSoTimeout(250);
        }

        protected void start() {
            thread = new Thread(() -> {
                byte[] buffer = new byte[512];
                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                        messages.add(message);
                        sendAck(packet);
                        handle(message);
                    } catch (SocketTimeoutException ignored) {
                        // Poll until stopped.
                    } catch (Exception e) {
                        if (running) {
                            throw new RuntimeException("Listener failed", e);
                        }
                    }
                }
            }, "rm-recovery-listener-" + socket.getLocalPort());
            thread.start();
        }

        protected void handle(String message) throws Exception {
            // Default no-op.
        }

        private void sendAck(DatagramPacket packet) throws Exception {
            byte[] ack = "ACK".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort()));
        }

        protected boolean seen(String expected) {
            return messages.contains(expected);
        }

        protected void stop() {
            running = false;
            socket.close();
            if (thread != null) {
                try {
                    thread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
