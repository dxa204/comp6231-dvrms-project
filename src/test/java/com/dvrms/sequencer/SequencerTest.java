package com.dvrms.sequencer;

import com.dvrms.common.Config;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-checking executable test harness for the Sequencer.
 *
 * Runs the documented Sequencer scenarios:
 *  1. Sequence number ordering
 *  2. Reliable delivery with a single dropped packet
 *  3. Replica replacement via UPDATE_TARGETS
 *  4. Duplicate suppression at the replica boundary
 *  5. Unreachable replica escalation to Replica Managers
 *
 * Run this class directly. It starts the Sequencer in-process.
 */
public class SequencerTest {

    private Sequencer sequencer;
    private Thread sequencerThread;

    private final List<MockReplica> replicas = new ArrayList<>();
    private final List<MockRM> rms = new ArrayList<>();

    private DatagramSocket feSocket;

    public static void main(String[] args) throws Exception {
        SequencerTest test = new SequencerTest();
        test.runAll();
    }

    private void runAll() throws Exception {
        try {
            scenarioSequenceNumberOrdering();
            scenarioReliableDeliverySinglePacketLoss();
            scenarioReplicaReplacementTargetUpdate();
            scenarioDuplicateSuppression();
            scenarioUnreachableReplicaEscalation();
            System.out.println("\n=== ALL DOCUMENTED SEQUENCER TESTS PASSED ===");
        } finally {
            cleanup();
        }
    }

    // ---------------------------------------------------------------------
    // Scenario 1
    // ---------------------------------------------------------------------
    private void scenarioSequenceNumberOrdering() throws Exception {
        System.out.println("\n=== Scenario 1: Sequence number ordering ===");
        setupFreshEnvironment();

        MockReplica r1 = addReplica("R1", Config.REPLICA_1_PORT, ReplicaBehavior.NORMAL);
        MockReplica r2 = addReplica("R2", Config.REPLICA_2_PORT, ReplicaBehavior.NORMAL);
        MockReplica r3 = addReplica("R3", Config.REPLICA_3_PORT, ReplicaBehavior.NORMAL);
        MockReplica r4 = addReplica("R4", Config.REPLICA_4_PORT, ReplicaBehavior.NORMAL);

        startSequencer();

        for (int i = 0; i < 10; i++) {
            sendFERequest("reserveVehicle", "MTLU1000|MTL1000|10042026|12042026|" + i);
        }

        waitUntil(() ->
                        r1.uniqueSeqs().size() == 10 &&
                                r2.uniqueSeqs().size() == 10 &&
                                r3.uniqueSeqs().size() == 10 &&
                                r4.uniqueSeqs().size() == 10,
                5000,
                "Not all replicas received 10 requests");

        assertOrdered(r1.uniqueSeqs(), 1, 10, "R1 sequence order");
        assertOrdered(r2.uniqueSeqs(), 1, 10, "R2 sequence order");
        assertOrdered(r3.uniqueSeqs(), 1, 10, "R3 sequence order");
        assertOrdered(r4.uniqueSeqs(), 1, 10, "R4 sequence order");

        System.out.println("PASS: all replicas received seq 1..10 in order");
        cleanup();
    }

    // ---------------------------------------------------------------------
    // Scenario 2
    // ---------------------------------------------------------------------
    private void scenarioReliableDeliverySinglePacketLoss() throws Exception {
        System.out.println("\n=== Scenario 2: Reliable delivery — single packet loss ===");
        setupFreshEnvironment();

        MockReplica r1 = addReplica("R1", Config.REPLICA_1_PORT, ReplicaBehavior.NORMAL);
        MockReplica r2 = addReplica("R2", Config.REPLICA_2_PORT, ReplicaBehavior.DROP_FIRST_PACKET_ONLY);
        MockReplica r3 = addReplica("R3", Config.REPLICA_3_PORT, ReplicaBehavior.NORMAL);
        MockReplica r4 = addReplica("R4", Config.REPLICA_4_PORT, ReplicaBehavior.NORMAL);

        startSequencer();

        sendFERequest("cancelReservation", "MTLU1000|MTL1000");

        waitUntil(() ->
                        r1.uniqueSeqs().size() == 1 &&
                                r2.uniqueSeqs().size() == 1 &&
                                r3.uniqueSeqs().size() == 1 &&
                                r4.uniqueSeqs().size() == 1 &&
                                r2.totalReceiveCount() >= 2,
                5000,
                "Replica R2 did not observe the retransmitted request");

        if (r2.totalReceiveCount() < 2) {
            throw new AssertionError("R2 should have seen at least 2 deliveries due to retransmission, but saw " + r2.totalReceiveCount());
        }

        System.out.println("PASS: dropped packet was retransmitted and eventually ACKed");
        cleanup();
    }

    // ---------------------------------------------------------------------
    // Scenario 3
    // ---------------------------------------------------------------------
    private void scenarioReplicaReplacementTargetUpdate() throws Exception {
        System.out.println("\n=== Scenario 3: Replica replacement — target update ===");
        setupFreshEnvironment();

        MockReplica r1 = addReplica("R1", Config.REPLICA_1_PORT, ReplicaBehavior.NORMAL);
        MockReplica r2Old = addReplica("R2", Config.REPLICA_2_PORT, ReplicaBehavior.NORMAL);
        MockReplica r3 = addReplica("R3", Config.REPLICA_3_PORT, ReplicaBehavior.NORMAL);
        MockReplica r4 = addReplica("R4", Config.REPLICA_4_PORT, ReplicaBehavior.NORMAL);

        startSequencer();

        // First request goes to old R2 endpoint
        sendFERequest("displayCurrentBudget", "MTLU1000");
        waitUntil(() -> r2Old.uniqueSeqs().size() == 1, 3000, "Old R2 did not receive initial request");

        // Stop old R2 and start replacement on new port
        r2Old.stop();
        MockReplica r2New = addReplica("R2-new", 6010, ReplicaBehavior.NORMAL);

        sendUpdateTargets("R2", "localhost", 6010);
        Thread.sleep(500);

        sendFERequest("displayReservations", "MTLU1000");

        waitUntil(() -> r2New.uniqueSeqs().size() == 1, 4000, "New R2 endpoint did not receive updated request");

        if (r2Old.totalReceiveCount() != 1) {
            throw new AssertionError("Old R2 should not receive requests after UPDATE_TARGETS");
        }

        System.out.println("PASS: UPDATE_TARGETS redirected delivery to new replica endpoint");
        cleanup();
    }

    // ---------------------------------------------------------------------
    // Scenario 4
    // ---------------------------------------------------------------------
    private void scenarioDuplicateSuppression() throws Exception {
        System.out.println("\n=== Scenario 4: Duplicate suppression ===");
        setupFreshEnvironment();

        MockReplica r1 = addReplica("R1", Config.REPLICA_1_PORT, ReplicaBehavior.DELAY_FIRST_ACK);
        MockReplica r2 = addReplica("R2", Config.REPLICA_2_PORT, ReplicaBehavior.NORMAL);
        MockReplica r3 = addReplica("R3", Config.REPLICA_3_PORT, ReplicaBehavior.NORMAL);
        MockReplica r4 = addReplica("R4", Config.REPLICA_4_PORT, ReplicaBehavior.NORMAL);

        startSequencer();

        sendFERequest("findVehicle", "MTLU1000|SUV");

        waitUntil(() ->
                        r1.totalReceiveCount() >= 2 &&
                                r1.uniqueSeqs().size() == 1,
                5000,
                "R1 did not receive the same seq twice");

        Integer seq = r1.uniqueSeqs().get(0);
        int executions = r1.executionCount(seq);
        if (executions != 1) {
            throw new AssertionError("Replica should execute duplicated seq only once, but executed " + executions + " times");
        }

        System.out.println("PASS: duplicate retransmission was received but executed exactly once");
        cleanup();
    }

    // ---------------------------------------------------------------------
    // Scenario 5
    // ---------------------------------------------------------------------
    private void scenarioUnreachableReplicaEscalation() throws Exception {
        System.out.println("\n=== Scenario 5: Unreachable replica escalation ===");
        setupFreshEnvironment();

        addMockRMs();

        MockReplica r1 = addReplica("R1", Config.REPLICA_1_PORT, ReplicaBehavior.NORMAL);
        MockReplica r2 = addReplica("R2", Config.REPLICA_2_PORT, ReplicaBehavior.NORMAL);
        MockReplica r3 = addReplica("R3", Config.REPLICA_3_PORT, ReplicaBehavior.NEVER_ACK);
        MockReplica r4 = addReplica("R4", Config.REPLICA_4_PORT, ReplicaBehavior.NORMAL);

        startSequencer();

        sendFERequest("reserveVehicle", "MTLU1000|MTL1000|10042026|12042026");

        waitUntil(this::allRmsSawCrash, 5000, "Replica Managers did not receive CRASH notification");

        System.out.println("PASS: unreachable replica was escalated to all RMs");
        cleanup();
    }

    // ---------------------------------------------------------------------
    // Setup / teardown
    // ---------------------------------------------------------------------
    private void setupFreshEnvironment() throws Exception {
        cleanup();
        feSocket = new DatagramSocket();
        feSocket.setSoTimeout(2000);
    }

    private void startSequencer() throws Exception {
        sequencer = new Sequencer();
        sequencerThread = new Thread(sequencer::start, "sequencer-test-thread");
        sequencerThread.start();
        Thread.sleep(500);
    }

    private MockReplica addReplica(String name, int port, ReplicaBehavior behavior) throws Exception {
        MockReplica replica = new MockReplica(name, port, behavior);
        replica.start();
        replicas.add(replica);
        return replica;
    }

    private void addMockRMs() throws Exception {
        rms.add(new MockRM(Config.RM_1_PORT));
        rms.add(new MockRM(Config.RM_2_PORT));
        rms.add(new MockRM(Config.RM_3_PORT));
        rms.add(new MockRM(Config.RM_4_PORT));
        for (MockRM rm : rms) {
            rm.start();
        }
    }

    private boolean allRmsSawCrash() {
        for (MockRM rm : rms) {
            if (!rm.sawCrash()) {
                return false;
            }
        }
        return true;
    }

    private void cleanup() throws Exception {
        for (MockReplica r : replicas) {
            r.stop();
        }
        replicas.clear();

        for (MockRM rm : rms) {
            rm.stop();
        }
        rms.clear();

        if (sequencer != null) {
            sequencer.stop();
            sequencer = null;
        }
        if (sequencerThread != null) {
            sequencerThread.join(1000);
            sequencerThread = null;
        }

        if (feSocket != null && !feSocket.isClosed()) {
            feSocket.close();
            feSocket = null;
        }

        Thread.sleep(300);
    }

    // ---------------------------------------------------------------------
    // FE / RM helpers
    // ---------------------------------------------------------------------
    private void sendFERequest(String method, String args) throws IOException {
        String msgId = UUID.randomUUID().toString().substring(0, 8);
        String message = String.join(Config.DELIMITER,
                Config.MSG_SEQ_REQ,
                msgId,
                Config.FE_HOST,
                String.valueOf(Config.FE_PORT),
                method,
                args);

        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName("localhost"),
                Config.SEQUENCER_PORT
        );
        feSocket.send(packet);
    }

    private void sendUpdateTargets(String replicaId, String host, int port) throws IOException {
        String message = String.join(Config.DELIMITER,
                Config.MSG_UPDATE,
                replicaId,
                host,
                String.valueOf(port));

        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName("localhost"),
                Config.SEQUENCER_PORT
        );
        feSocket.send(packet);
    }

    // ---------------------------------------------------------------------
    // Assertions / waiting
    // ---------------------------------------------------------------------
    private void waitUntil(Callable<Boolean> condition, long timeoutMs, String failureMessage) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) return;
            Thread.sleep(50);
        }
        throw new AssertionError(failureMessage);
    }

    private void assertOrdered(List<Integer> actual, int start, int end, String label) {
        List<Integer> expected = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            expected.add(i);
        }
        if (!actual.equals(expected)) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    // ---------------------------------------------------------------------
    // Mock replica + behaviors
    // ---------------------------------------------------------------------
    enum ReplicaBehavior {
        NORMAL,
        DROP_FIRST_PACKET_ONLY,
        NEVER_ACK,
        DELAY_FIRST_ACK
    }

    static class MockReplica {
        private final String name;
        private final String replicaId;
        private final int port;
        private final ReplicaBehavior behavior;
        private DatagramSocket socket;
        private Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(false);

        private final List<Integer> uniqueSeqs = Collections.synchronizedList(new ArrayList<>());
        private final Map<Integer, Integer> executionCounts = new ConcurrentHashMap<>();
        private final AtomicInteger totalReceiveCount = new AtomicInteger(0);

        private final AtomicBoolean firstPacketDropped = new AtomicBoolean(false);
        private final AtomicBoolean firstAckDelayed = new AtomicBoolean(false);
        private final Set<Integer> executed = ConcurrentHashMap.newKeySet();

        MockReplica(String name, int port, ReplicaBehavior behavior) {
            this.name = name;
            this.replicaId = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
            this.port = port;
            this.behavior = behavior;
        }

        void start() throws Exception {
            socket = new DatagramSocket(port);
            running.set(true);
            thread = new Thread(this::runLoop, "mock-" + name);
            thread.start();
        }

        private void runLoop() {
            byte[] buffer = new byte[Config.BUFFER_SIZE];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    totalReceiveCount.incrementAndGet();

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!msg.startsWith(Config.MSG_REQ)) {
                        continue;
                    }

                    String[] parts = msg.split("\\|");
                    String msgId = parts[1];
                    int seqNum = Integer.parseInt(parts[2]);

                    // simulate "execute exactly once"
                    if (executed.add(seqNum)) {
                        uniqueSeqs.add(seqNum);
                        executionCounts.merge(seqNum, 1, Integer::sum);
                    }

                    switch (behavior) {
                        case NEVER_ACK:
                            break;

                        case DROP_FIRST_PACKET_ONLY:
                            if (!firstPacketDropped.getAndSet(true)) {
                                break;
                            }
                            sendAck(packet, msgId);
                            break;

                        case DELAY_FIRST_ACK:
                            if (!firstAckDelayed.getAndSet(true)) {
                                Thread.sleep(Config.ACK_TIMEOUT_MS + 150L);
                            }
                            sendAck(packet, msgId);
                            break;

                        case NORMAL:
                        default:
                            sendAck(packet, msgId);
                            break;
                    }

                } catch (SocketException e) {
                    break;
                } catch (Exception ignored) {
                }
            }
        }

        private void sendAck(DatagramPacket requestPacket, String msgId) throws IOException {
            String ack = Config.MSG_ACK + Config.DELIMITER + replicaId + Config.DELIMITER + msgId;
            byte[] ackBytes = ack.getBytes(StandardCharsets.UTF_8);
            DatagramPacket ackPacket = new DatagramPacket(
                    ackBytes,
                    ackBytes.length,
                    requestPacket.getAddress(),
                    Config.SEQUENCER_PORT
            );
            socket.send(ackPacket);
        }

        List<Integer> uniqueSeqs() {
            synchronized (uniqueSeqs) {
                return new ArrayList<>(uniqueSeqs);
            }
        }

        int totalReceiveCount() {
            return totalReceiveCount.get();
        }

        int executionCount(int seqNum) {
            return executionCounts.getOrDefault(seqNum, 0);
        }

        void stop() throws Exception {
            running.set(false);
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (thread != null) {
                thread.join(500);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Mock RM listener
    // ---------------------------------------------------------------------
    static class MockRM {
        private final int port;
        private DatagramSocket socket;
        private Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean sawCrash = new AtomicBoolean(false);

        MockRM(int port) {
            this.port = port;
        }

        void start() throws Exception {
            socket = new DatagramSocket(port);
            running.set(true);
            thread = new Thread(this::runLoop, "mock-rm-" + port);
            thread.start();
        }

        private void runLoop() {
            byte[] buffer = new byte[512];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (msg.startsWith(Config.MSG_CRASH_SUSPECT + Config.DELIMITER)) {
                        sawCrash.set(true);
                    }
                } catch (SocketException e) {
                    break;
                } catch (Exception ignored) {
                }
            }
        }

        boolean sawCrash() {
            return sawCrash.get();
        }

        void stop() throws Exception {
            running.set(false);
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (thread != null) {
                thread.join(500);
            }
        }
    }
}
