package com.dvrms.replica2;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
 
// ===========================================================================
// ReplicaManager (RM) — matches design documentation protocol exactly
//
// UDP message formats (pipe-delimited ASCII):
//
//   Replica -> RM at startup:
//     REGISTER|<replicaId>|<city>|<listenPort>
//
//   FE -> RM (byzantine fault):
//     FAULT|<replicaID>|<seqNum>
//
//   FE/Sequencer -> RM (replica timed out):
//     CRASH|<replicaID>
//
//   RM -> RM (coordination — step 1: broadcast suspicion):
//     CHECK|<suspectReplicaId>
//
//   RM -> RM (coordination — step 2: reply with local observation):
//     STATUS|<suspectReplicaId>|<OK|FAULTY|NO_RESPONSE>
//
//   RM -> RM (after confirmed replacement, announce new endpoint):
//     RECOVER|<replicaId>|<newHost>|<newPort>
//
//   RM -> Sequencer (update multicast target after replacement):
//     UPDATE|<oldReplicaID>|<newHost>|<newPort>
//
// All RM messages are ACK-based reliable UDP: 500 ms timeout, 5 retries.
//
// Port assignments (fixed by team protocol doc):
//   Replica 1-4: 6001-6004    RM 1-4: 7001-7004
//   Sequencer: 5000            FE: 5001
//
// Multi-host:
//   Each RM runs on a different physical host.
//   rmHost/rmPort for each peer are supplied on the command line.
//   sequencerHost is supplied via -Dsequencer.host=<ip> (default: localhost).
// ===========================================================================
public class ReplicaManager {
 
    // -----------------------------------------------------------------------
    // ReplicaInfo — everything the RM knows about one replica
    // -----------------------------------------------------------------------
 
    private static class ReplicaInfo {
        final int    replicaId;
        String city;
        String host;
        int    listenPort;
 
        // Consecutive distinct-seqNum FAULT reports from the FE
        final AtomicInteger faultCount = new AtomicInteger(0);
        volatile int lastFaultSeq = -1;
 
        // Is this replica currently considered alive and correct?
        volatile boolean alive = true;
 
        // Arguments to pass to ProcessBuilder when restarting this replica.
        // Index: [city, replicaId, rmHost, rmPort]  -- matches VehicleServer.main()
        volatile String[] launchArgs;
 
        ReplicaInfo(int id, String city, String host, int port, String[] launchArgs) {
            this.replicaId  = id;
            this.city       = city;
            this.host       = host;
            this.listenPort = port;
            this.launchArgs = launchArgs;
        }
 
        @Override public String toString() {
            return "Replica[" + replicaId + "/" + city + "@" + host + ":"
                    + listenPort + " alive=" + alive + " faults=" + faultCount + "]";
        }
    }
 
    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------
 
    private final int rmId;
    private final int listenPort;  // 7000 + rmId
 
    // All replicas known to this RM: replicaId -> ReplicaInfo
    private final ConcurrentHashMap<Integer, ReplicaInfo> replicas = new ConcurrentHashMap<>();
 
    // Peer RMs: rmId -> InetSocketAddress
    private final ConcurrentHashMap<Integer, InetSocketAddress> peerRMs = new ConcurrentHashMap<>();
 
    // Accumulated STATUS replies for an ongoing CHECK round:
    // "check_<replicaId>" -> list of "OK"/"FAULTY"/"NO_RESPONSE" strings received
    private final ConcurrentHashMap<String, List<String>> statusReplies = new ConcurrentHashMap<>();
 
    // Guard against concurrent replacement of the same replica
    private final Set<Integer> replacing = ConcurrentHashMap.newKeySet();
 
    // De-duplication: don't start a second CHECK round while one is running
    private final Set<Integer> checkInProgress = ConcurrentHashMap.newKeySet();
 
    // Sequencer coordinates (configurable for multi-host)
    private final String sequencerHost;
    private static final int SEQUENCER_PORT = 5000;
 
    private static final int UDP_TIMEOUT_MS  = 500;
    private static final int UDP_MAX_RETRIES = 5;
 
    // Majority threshold: with 4 RMs, 3 votes needed to confirm a failure
    private static final int MAJORITY = 3;
 
    // -----------------------------------------------------------------------
 
    public ReplicaManager(int rmId, String sequencerHost) {
        this.rmId           = rmId;
        this.listenPort     = 7000 + rmId;
        this.sequencerHost  = sequencerHost;
        System.out.println("[RM " + rmId + "] Starting on port " + listenPort
                + "  sequencer=" + sequencerHost + ":" + SEQUENCER_PORT);
    }
 
    /** Register a peer RM by ID, host, and port. Call before start(). */
    public void addPeerRM(int peerId, String host, int port) {
        peerRMs.put(peerId, new InetSocketAddress(host, port));
        System.out.println("[RM " + rmId + "] Peer RM " + peerId + " at " + host + ":" + port);
    }
 
    // -----------------------------------------------------------------------
    // Main UDP listener
    // -----------------------------------------------------------------------
 
    /**
     * Starts the RM's single UDP listener in a daemon thread.
     *
     * Incoming message types:
     *   REGISTER   from replicas at startup
     *   FAULT      from FE (byzantine fault)
     *   CRASH      from FE or Sequencer (crash suspicion)
     *   CHECK      from peer RM (step 1 of coordination)
     *   STATUS     from peer RM (step 2 of coordination)
     *   RECOVER    from peer RM (new replica endpoint after replacement)
     */
    public void start() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(listenPort)) {
                System.out.println("[RM " + rmId + "] Listening on port " + listenPort);
                while (true) {
                    byte[] buf = new byte[1024];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
 
                    String   raw   = new String(pkt.getData(), 0, pkt.getLength()).trim();
                    String   srcIp = pkt.getAddress().getHostAddress();
                    String[] parts = raw.split("\\|");
 
                    // Always ACK immediately so the sender's retry loop can stop
                    sendAck(socket, pkt);
 
                    switch (parts[0]) {
                        case "REGISTER":
                            // REGISTER|<replicaId>|<city>|<listenPort>
                            handleRegister(parts, srcIp);
                            break;
 
                        case "FAULT":
                            // FAULT|<replicaID>|<seqNum>   (from FE)
                            handleFault(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                            break;
 
                        case "CRASH":
                            // CRASH|<replicaID>   (from FE or Sequencer)
                            handleCrashSuspicion(Integer.parseInt(parts[1]));
                            break;
 
                        case "CHECK":
                            // CHECK|<suspectReplicaId>   (from peer RM, step 1)
                            handleStatusCheck(Integer.parseInt(parts[1]), srcIp);
                            break;
 
                        case "STATUS":
                            // STATUS|<suspectReplicaId>|<OK|FAULTY|NO_RESPONSE>
                            handleStatusReply(Integer.parseInt(parts[1]), parts[2]);
                            break;
 
                        case "RECOVER":
                            // RECOVER|<replicaId>|<newHost>|<newPort>   (from peer RM)
                            handleRecoveryNotice(Integer.parseInt(parts[1]), parts[2],
                                    Integer.parseInt(parts[3]));
                            break;
 
                        default:
                            System.err.println("[RM " + rmId + "] Unknown message: " + raw);
                    }
                }
            } catch (Exception e) {
                System.err.println("[RM " + rmId + "] Listener crashed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "rm-listener-" + rmId).start();
    }
 
    // -----------------------------------------------------------------------
    // Message handlers
    // -----------------------------------------------------------------------
 
    /**
     * REGISTER|<replicaId>|<city>|<listenPort>
     * Called by each replica on startup so the RM knows where to find it.
     * The launch args stored here are what we'll use to restart the replica
     * if it fails (city, replicaId, rmHost=this RM's own address, rmPort).
     */
    private void handleRegister(String[] parts, String srcHost) {
        int    id         = Integer.parseInt(parts[1]);
        String city       = parts[2];
        int    listenPort = Integer.parseInt(parts[3]);
 
        // Build restart command: java -Dorb.host=<ns> VehicleServer <city> <id> <rmHost> <rmPort>
        // The RM host seen from the replica's perspective is this machine's address.
        // We use srcHost as a best-effort guess; override with -Drm.host if needed.
        String rmPublicHost = System.getProperty("rm.host", "localhost");
 
        String[] launchArgs = { city, String.valueOf(id), rmPublicHost, String.valueOf(this.listenPort) };
 
        ReplicaInfo info = new ReplicaInfo(id, city, srcHost, listenPort, launchArgs);
        replicas.put(id, info);
        System.out.println("[RM " + rmId + "] Registered " + info);
    }
 
    /**
     * FAULT|<replicaID>|<seqNum>
     * FE reports that this replica returned a result different from the majority.
     * After 3 consecutive distinct-seqNum faults, start the coordination protocol.
     */
    private void handleFault(int replicaId, int seqNum) {
        ReplicaInfo info = replicas.get(replicaId);
        if (info == null) {
            System.err.println("[RM " + rmId + "] FAULT for unknown replica " + replicaId);
            return;
        }
 
        // De-duplicate: the FE retries its own FAULT reports reliably
        if (info.lastFaultSeq == seqNum) return;
        info.lastFaultSeq = seqNum;
 
        int count = info.faultCount.incrementAndGet();
        System.out.println("[RM " + rmId + "] FAULT replica=" + replicaId
                + " seqNum=" + seqNum + " count=" + count + "/3");
 
        if (count >= 3) {
            info.faultCount.set(0);
            System.out.println("[RM " + rmId + "] Replica " + replicaId
                    + " reached byzantine threshold -- initiating STATUS_CHECK coordination");
            initiateStatusCheck(replicaId, "FAULTY");
        }
    }
 
    /**
     * CRASH|<replicaID>   (from FE or Sequencer)
     * A component suspects this replica crashed (missed its reply deadline).
     * Per design doc: since the FE/Sequencer has already retried, we trust the
     * report and immediately initiate RM-to-RM coordination.
     */
    private void handleCrashSuspicion(int replicaId) {
        ReplicaInfo info = replicas.get(replicaId);
        if (info == null || !info.alive) return;
 
        System.out.println("[RM " + rmId + "] CRASH suspicion for replica " + replicaId
                + " -- initiating STATUS_CHECK coordination");
        initiateStatusCheck(replicaId, "NO_RESPONSE");
    }
 
    /**
     * CHECK|<suspectReplicaId>   (from peer RM, step 1 of coordination)
     * A peer has received a failure report. This RM probes the suspect replica
     * locally and sends back its own observation as a STATUS reply.
     */
    private void handleStatusCheck(int suspectReplicaId, String requestingRmHost) {
        ReplicaInfo info = replicas.get(suspectReplicaId);
        String observation;
 
        if (info == null || !info.alive) {
            observation = "NO_RESPONSE";
        } else {
            boolean reachable = probeReplica(info);
            observation = reachable ? "OK" : "NO_RESPONSE";
        }
 
        System.out.println("[RM " + rmId + "] STATUS_CHECK for replica " + suspectReplicaId
                + " -- my observation: " + observation);
 
        // Broadcast STATUS reply to ALL RMs (including the one that sent CHECK),
        // so every RM accumulates the full picture independently.
        String statusMsg = "STATUS|" + suspectReplicaId + "|" + observation;
        broadcastToAllRMs(statusMsg);
    }
 
    /**
     * STATUS|<suspectReplicaId>|<OK|FAULTY|NO_RESPONSE>   (from peer RM, step 2)
     * Accumulate peer observations. When we have MAJORITY votes that are NOT "OK",
     * the failure is confirmed and we proceed with replacement.
     */
    private void handleStatusReply(int suspectReplicaId, String observation) {
        String key = "check_" + suspectReplicaId;
        List<String> replies = statusReplies.computeIfAbsent(key,
                k -> Collections.synchronizedList(new ArrayList<>()));
        replies.add(observation);
 
        System.out.println("[RM " + rmId + "] STATUS for replica " + suspectReplicaId
                + ": " + observation + " (total replies=" + replies.size() + ")");
 
        long notOk = replies.stream().filter(s -> !s.equals("OK")).count();
        if (notOk >= MAJORITY) {
            statusReplies.remove(key);
            checkInProgress.remove(suspectReplicaId);
            System.out.println("[RM " + rmId + "] Majority confirmed failure of replica "
                    + suspectReplicaId + " -- replacing");
            replaceReplica(suspectReplicaId);
        } else if (replies.size() >= peerRMs.size() + 1) {
            // All votes in and majority did not confirm -- transient issue
            statusReplies.remove(key);
            checkInProgress.remove(suspectReplicaId);
            System.out.println("[RM " + rmId + "] No majority for replica "
                    + suspectReplicaId + " -- treating as transient, ignoring");
        }
    }
 
    /**
     * RECOVER|<replicaId>|<newHost>|<newPort>   (from the RM that owns the replica)
     * Update our local record so we know where the replacement replica lives.
     * This is sent AFTER the owning RM has successfully restarted the process.
     */
    private void handleRecoveryNotice(int replicaId, String newHost, int newPort) {
        ReplicaInfo info = replicas.get(replicaId);
        if (info != null) {
            info.host        = newHost;
            info.listenPort  = newPort;
            info.alive       = true;
            info.faultCount.set(0);
            System.out.println("[RM " + rmId + "] Updated replica " + replicaId
                    + " endpoint to " + newHost + ":" + newPort);
        }
    }
 
    // -----------------------------------------------------------------------
    // RM-to-RM coordination protocol
    // -----------------------------------------------------------------------
 
    /**
     * Step 1: This RM broadcasts CHECK|<suspectId> to all peer RMs.
     * Also adds its own local observation to the STATUS reply pool.
     *
     * localObservation: "FAULTY" for byzantine, "NO_RESPONSE" for crash.
     */
    private void initiateStatusCheck(int suspectReplicaId, String localObservation) {
        if (!checkInProgress.add(suspectReplicaId)) {
            System.out.println("[RM " + rmId + "] Check already in progress for replica "
                    + suspectReplicaId + " -- skipping");
            return;
        }
 
        // Count this RM's own vote first
        handleStatusReply(suspectReplicaId, localObservation);
 
        // Broadcast CHECK to peers
        String checkMsg = "CHECK|" + suspectReplicaId;
        broadcastToAllRMs(checkMsg);
    }
 
    /** Sends a message to all peer RMs via reliable UDP. */
    private void broadcastToAllRMs(String message) {
        for (Map.Entry<Integer, InetSocketAddress> entry : peerRMs.entrySet()) {
            try {
                sendUDP(entry.getValue().getHostString(), entry.getValue().getPort(), message);
            } catch (Exception e) {
                System.err.println("[RM " + rmId + "] Failed to reach peer RM "
                        + entry.getKey() + ": " + e.getMessage());
            }
        }
    }
 
    // -----------------------------------------------------------------------
    // Replica probing
    // -----------------------------------------------------------------------
 
    /**
     * Sends PING|<replicaId> to the replica's listen port and waits for PONG.
     * Returns true only if PONG is received within the timeout.
     */
    private boolean probeReplica(ReplicaInfo info) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(UDP_TIMEOUT_MS * 2);
            byte[] ping = ("PING|" + info.replicaId).getBytes();
            InetAddress addr = InetAddress.getByName(info.host);
            socket.send(new DatagramPacket(ping, ping.length, addr, info.listenPort));
 
            byte[] buf = new byte[64];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.receive(reply);
            String resp = new String(reply.getData(), 0, reply.getLength()).trim();
            return resp.startsWith("PONG");
        } catch (SocketTimeoutException e) {
            return false;
        } catch (Exception e) {
            System.err.println("[RM " + rmId + "] Probe error for replica "
                    + info.replicaId + ": " + e.getMessage());
            return false;
        }
    }
 
    // -----------------------------------------------------------------------
    // Replica replacement
    // -----------------------------------------------------------------------
 
    /**
     * Replaces a confirmed-failed replica:
     *  1. Mark it dead in local state.
     *  2. If this RM owns the replica (replicaId == rmId), restart the process.
     *  3. After restart, notify the Sequencer and all peer RMs.
     *
     * The 1:1 mapping (RM_i owns Replica_i) matches the team assignment where
     * each student runs one RM on their own host alongside their replica.
     */
    private void replaceReplica(int replicaId) {
        if (!replacing.add(replicaId)) return; // already being replaced
 
        try {
            ReplicaInfo info = replicas.get(replicaId);
            if (info == null) {
                System.err.println("[RM " + rmId + "] replaceReplica: unknown replica " + replicaId);
                return;
            }
 
            info.alive = false;
 
            if (info.replicaId != rmId) {
                System.out.println("[RM " + rmId + "] Replica " + replicaId
                        + " is hosted on another machine -- waiting for RECOVER notice");
                // The owning RM will restart it and send RECOVER to us
                return;
            }
 
            System.out.println("[RM " + rmId + "] I own replica " + replicaId + " -- restarting");
 
            // Step 1: Tell the old process to shut down (in case it is still running)
            try {
                sendUDP(info.host, info.listenPort, "CRASH|" + replicaId);
            } catch (Exception ignored) { /* already dead is fine */ }
 
            // Step 2: Give the port time to be released
            Thread.sleep(1500);
 
            // Step 3: Spawn fresh VehicleServer process
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            // Forward the orb.host system property to the new process
            String orbHost = System.getProperty("orb.host", "localhost");
            if (!orbHost.equals("localhost"))
                cmd.add("-Dorb.host=" + orbHost);
            cmd.add("VehicleServer");
            cmd.addAll(Arrays.asList(info.launchArgs));
 
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            Thread.sleep(3000); // wait for the new process to bind its port
 
            // Step 4: Update our own record
            // The port stays the same (6000 + replicaId); the host too.
            info.alive = true;
            info.faultCount.set(0);
            System.out.println("[RM " + rmId + "] Replica " + replicaId
                    + " restarted (pid=" + p + ")");
 
            // Step 5: Notify Sequencer -- UPDATE|<oldId>|<newHost>|<newPort>
            String updateMsg = "UPDATE|" + replicaId + "|" + info.host + "|" + info.listenPort;
            try {
                sendUDP(sequencerHost, SEQUENCER_PORT, updateMsg);
                System.out.println("[RM " + rmId + "] Notified Sequencer: " + updateMsg);
            } catch (Exception e) {
                System.err.println("[RM " + rmId + "] Failed to notify Sequencer: " + e.getMessage());
            }
 
            // Step 6: Broadcast RECOVER to peer RMs so they update their records
            String recoverMsg = "RECOVER|" + replicaId + "|" + info.host + "|" + info.listenPort;
            broadcastToAllRMs(recoverMsg);
 
        } catch (Exception e) {
            System.err.println("[RM " + rmId + "] Failed to replace replica "
                    + replicaId + ": " + e.getMessage());
        } finally {
            replacing.remove(replicaId);
        }
    }
 
    // -----------------------------------------------------------------------
    // Reliable UDP helpers
    // -----------------------------------------------------------------------
 
    /** Send and wait for ACK. 500 ms timeout, 5 retries. */
    private void sendUDP(String host, int port, String message) throws Exception {
        byte[] data = message.getBytes();
        InetAddress addr = InetAddress.getByName(host);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(UDP_TIMEOUT_MS);
            for (int attempt = 1; attempt <= UDP_MAX_RETRIES; attempt++) {
                socket.send(new DatagramPacket(data, data.length, addr, port));
                try {
                    byte[] ackBuf = new byte[64];
                    DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPkt);
                    if (new String(ackPkt.getData(), 0, ackPkt.getLength()).trim().startsWith("ACK"))
                        return;
                } catch (SocketTimeoutException ignored) {
                    System.err.println("[RM " + rmId + "] UDP retry " + attempt
                            + " -> " + host + ":" + port);
                }
            }
            throw new Exception("UDP failed after " + UDP_MAX_RETRIES + " retries to " + host + ":" + port);
        }
    }
 
    /** Inline ACK reply sent from the listener socket (no new socket needed). */
    private void sendAck(DatagramSocket socket, DatagramPacket request) {
        try {
            byte[] ack = "ACK".getBytes();
            socket.send(new DatagramPacket(ack, ack.length,
                    request.getAddress(), request.getPort()));
        } catch (Exception e) {
            System.err.println("[RM " + rmId + "] Failed to send ACK: " + e.getMessage());
        }
    }
 
    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
 
    /**
     * Usage:
     *   java -Dsequencer.host=<seq-ip> -Dorb.host=<ns-ip> -Drm.host=<this-rm-ip>
     *        ReplicaManager <rmId> [<peerRmId> <peerHost> <peerPort>] ...
     *
     * Arguments:
     *   rmId          Integer 1-4.  This RM's listen port = 7000 + rmId.
     *   peerRmId      Repeat triplet for each of the other 3 RMs.
     *   peerHost      Hostname/IP of that peer RM's machine.
     *   peerPort      UDP port of that peer RM (7000 + peerRmId).
     *
     * JVM system properties:
     *   -Dsequencer.host=<ip>   Host running the Sequencer (default: localhost)
     *   -Dorb.host=<ip>         Host running orbd/tnameserv  (default: localhost)
     *   -Drm.host=<ip>          This machine's own IP/hostname as seen by replicas
     *                           (default: localhost).  Used when building replica
     *                           restart commands.
     *
     * Single-machine test (all on localhost):
     *   java ReplicaManager 1 2 localhost 7002 3 localhost 7003 4 localhost 7004
     *
     * Multi-host example (4 machines: rm1=10.0.0.1 rm2=10.0.0.2 ...):
     *   On 10.0.0.1:
     *     java -Dsequencer.host=10.0.0.5 -Drm.host=10.0.0.1 \
     *          ReplicaManager 1 2 10.0.0.2 7002 3 10.0.0.3 7003 4 10.0.0.4 7004
     *
     *   On 10.0.0.2:
     *     java -Dsequencer.host=10.0.0.5 -Drm.host=10.0.0.2 \
     *          ReplicaManager 2 1 10.0.0.1 7001 3 10.0.0.3 7003 4 10.0.0.4 7004
     *
     *   (repeat pattern for RM3 on 10.0.0.3 and RM4 on 10.0.0.4)
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ReplicaManager <rmId> [<peerRmId> <peerHost> <peerPort>] ...");
            System.exit(1);
        }
 
        int    rmId          = Integer.parseInt(args[0]);
        String seqHost       = System.getProperty("sequencer.host", "localhost");
 
        ReplicaManager rm = new ReplicaManager(rmId, seqHost);
 
        // Register peer RMs from the remaining argument triplets
        for (int i = 1; i + 2 < args.length; i += 3) {
            rm.addPeerRM(Integer.parseInt(args[i]), args[i + 1], Integer.parseInt(args[i + 2]));
        }
 
        rm.start();
 
        Thread.currentThread().join(); // keep main alive
    }
}