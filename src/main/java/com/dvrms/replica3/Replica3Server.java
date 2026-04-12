package com.dvrms.replica3;

import com.dvrms.common.Config;
import com.dvrms.common.InitialData;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replica 3 — active replica driven by the Sequencer.
 *
 *   Sequencer -> Replica: REQ|<msgId>|<seqNum>|<feHost>|<fePort>|<method>|<args...>
 *   Replica   -> Sequencer: ACK|<msgId>
 *   Replica   -> FE:       RESULT|<msgId>|R3|<result>
 *
 *   RM <-> Replica:
 *     Replica -> RM (startup): REGISTER|<replicaIdInt>|<city>|<listenPort>   (ACK'd)
 *     RM -> Replica (probe):   PING|<replicaIdInt>        -> reply "PONG"
 *     RM -> Replica (kill):    CRASH|<replicaIdInt>       -> process exits
 *
 * Sequencing: buffer out-of-order requests and execute in seqNum order
 * starting from 1. Duplicate-suppress by seqNum so retransmissions are ACK'd
 * but not re-executed.
 */
public class Replica3Server {

    private static final String REPLICA_ID = "R3";
    private static final int REPLICA_ID_INT = 3;
    private static final int REPLICA_PORT = Config.REPLICA_3_PORT; // 6003

    // Internal UDP ports for the three office servers inside this replica.
    // Distinct from replica 4's 7401/7402/7403.
    private static final int MTL_UDP = 7101;
    private static final int WPG_UDP = 7102;
    private static final int BNF_UDP = 7103;

    private final OfficeServer mtl;
    private final OfficeServer wpg;
    private final OfficeServer bnf;

    private int nextExpectedSeq = 1;
    private final Map<Integer, RequestEnvelope> buffer = new TreeMap<>();
    private final Map<Integer, String> executedResults = new ConcurrentHashMap<>();

    public Replica3Server() {
        this.mtl = new OfficeServer(OfficeServer.Office.MTL, MTL_UDP);
        this.wpg = new OfficeServer(OfficeServer.Office.WPG, WPG_UDP);
        this.bnf = new OfficeServer(OfficeServer.Office.BNF, BNF_UDP);
    }

    public static void main(String[] args) throws Exception {
        new Replica3Server().start();
    }

    public void start() throws Exception {
        seedInitialData();
        mtl.startUdpListener();
        wpg.startUdpListener();
        bnf.startUdpListener();

        try (DatagramSocket socket = new DatagramSocket(REPLICA_PORT)) {
            System.out.println("[" + REPLICA_ID + "] listening on port " + REPLICA_PORT);

            // Register with every RM now that we're bound and ready.
            registerWithReplicaManagers();

            byte[] buf = new byte[Config.BUFFER_SIZE];
            while (true) {
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);
                String msg = new String(req.getData(), 0, req.getLength(), StandardCharsets.UTF_8).trim();
                handleIncoming(msg, socket, req);
            }
        }
    }

    // ============================================================
    // Incoming dispatch (Sequencer REQ / RM PING / RM CRASH)
    // ============================================================

    private void handleIncoming(String msg, DatagramSocket socket, DatagramPacket req) throws Exception {
        if (msg.startsWith(Config.MSG_REQ + "|")) {
            handleSequencerRequest(msg, socket, req);
            return;
        }
        if (msg.startsWith("PING|")) {
            handlePing(socket, req);
            return;
        }
        if (msg.startsWith("CRASH|")) {
            handleCrash(msg);
            return;
        }
    }

    private void handleSequencerRequest(String msg, DatagramSocket socket, DatagramPacket req) throws Exception {
        String[] parts = msg.split("\\|");
        if (parts.length < 6) return;

        String msgId = parts[1];
        int seqNum = Integer.parseInt(parts[2]);

        // ACK back to the sequencer on the same socket it sent from
        sendAckToSequencer(socket, req, msgId);

        // Duplicate suppression — already executed this seq, just re-ACK and ignore
        if (executedResults.containsKey(seqNum)) {
            return;
        }

        RequestEnvelope env = RequestEnvelope.from(parts);
        buffer.put(seqNum, env);

        processBufferedRequests(socket);
    }

    private void handlePing(DatagramSocket socket, DatagramPacket req) throws IOException {
        // RM checks resp.startsWith("PONG") — plain "PONG" is enough.
        byte[] out = "PONG".getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(out, out.length, req.getAddress(), req.getPort()));
    }

    private void handleCrash(String msg) {
        System.out.println("[" + REPLICA_ID + "] received " + msg + " from RM — shutting down");
        mtl.shutdown();
        wpg.shutdown();
        bnf.shutdown();
        System.exit(0);
    }

    private void sendAckToSequencer(DatagramSocket socket, DatagramPacket req, String msgId) throws IOException {
        String ack = Config.MSG_ACK + "|" + msgId;
        byte[] data = ack.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, req.getAddress(), req.getPort()));
    }

    private void processBufferedRequests(DatagramSocket socket) throws IOException {
        while (buffer.containsKey(nextExpectedSeq)) {
            RequestEnvelope env = buffer.remove(nextExpectedSeq);

            String result;
            try {
                result = dispatch(env);
            } catch (Exception e) {
                result = "ERROR: " + e.getMessage();
            }
            executedResults.put(nextExpectedSeq, result);

            sendResultToFE(socket, env, result);
            nextExpectedSeq++;
        }
    }

    private void sendResultToFE(DatagramSocket socket, RequestEnvelope env, String result) throws IOException {
        String response = Config.MSG_RESULT + "|" + env.msgId + "|" + REPLICA_ID + "|" + result;
        byte[] data = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket resp = new DatagramPacket(
                data, data.length,
                InetAddress.getByName(env.feHost),
                env.fePort);
        socket.send(resp);
    }

    private String dispatch(RequestEnvelope env) {
        OfficeServer target = chooseOfficeServer(env);
        return invokeMethod(target, env);
    }

    private OfficeServer chooseOfficeServer(RequestEnvelope env) {
        switch (env.getTargetOffice()) {
            case "MTL": return mtl;
            case "WPG": return wpg;
            case "BNF": return bnf;
            default:
                throw new IllegalArgumentException("Unknown office " + env.getTargetOffice());
        }
    }

    private String invokeMethod(OfficeServer target, RequestEnvelope env) {
        switch (env.method) {
            case "addVehicle":
                return target.addVehicle(
                        env.args[0],
                        Integer.parseInt(env.args[1]),
                        env.args[2],
                        env.args[3],
                        Integer.parseInt(env.args[4]));
            case "removeVehicle":
                return target.removeVehicle(env.args[0], env.args[1]);
            case "listAvailableVehicle":
                return target.listAvailableVehicle(env.args[0]);
            case "reserveVehicle":
                return target.reserveVehicle(env.args[0], env.args[1], env.args[2], env.args[3]);
            case "cancelReservation":
                return target.cancelReservation(env.args[0], env.args[1]);
            case "updateReservation":
                return target.updateReservation(env.args[0], env.args[1], env.args[2], env.args[3]);
            case "findVehicle":
                return target.findVehicle(env.args[0], env.args[1]);
            case "displayCurrentBudget":
                return target.displayCurrentBudget(env.args[0]);
            case "displayReservations":
                return target.displayReservations(env.args[0]);
            case "displayNotifications":
                return target.displayNotifications(env.args[0]);
            default:
                return "ERROR: unknown method " + env.method;
        }
    }

    private void seedInitialData() {
        mtl.seed(InitialData.getMTLData());
        wpg.seed(InitialData.getWPGData());
        bnf.seed(InitialData.getBNFData());
        System.out.println("[" + REPLICA_ID + "] seeded initial data");
    }

    // ============================================================
    // Replica Manager registration
    // ============================================================

    /**
     * Announce this replica to every RM. Each RM needs the replicaId, a city
     * tag, and the replica's listen port so it can restart it on failure.
     * Because this replica hosts all three offices, we use "ALL" as the city
     * tag — the RM launch logic only uses the string verbatim in launch args.
     */
    private void registerWithReplicaManagers() {
        String message = "REGISTER|" + REPLICA_ID_INT + "|ALL|" + REPLICA_PORT;
        int[] rmPorts = {
                Config.RM_1_PORT,
                Config.RM_2_PORT,
                Config.RM_3_PORT,
                Config.RM_4_PORT
        };

        for (int port : rmPorts) {
            boolean acked = sendReliableToRM("localhost", port, message);
            System.out.println("[" + REPLICA_ID + "] REGISTER -> RM@" + port
                    + " " + (acked ? "ACKed" : "no ACK after retries"));
        }
    }

    /**
     * Reliable-UDP send that mirrors the RM's own sender: 500 ms timeout,
     * 5 retries, considers any reply starting with "ACK" a success.
     */
    private boolean sendReliableToRM(String host, int port, String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(Config.ACK_TIMEOUT_MS);
            InetAddress addr = InetAddress.getByName(host);
            for (int attempt = 1; attempt <= Config.MAX_RETRIES; attempt++) {
                sock.send(new DatagramPacket(data, data.length, addr, port));
                try {
                    byte[] buf = new byte[128];
                    DatagramPacket ack = new DatagramPacket(buf, buf.length);
                    sock.receive(ack);
                    String reply = new String(ack.getData(), 0, ack.getLength(), StandardCharsets.UTF_8).trim();
                    if (reply.startsWith("ACK")) return true;
                } catch (SocketTimeoutException ignored) {
                    // retry
                }
            }
        } catch (IOException e) {
            System.err.println("[" + REPLICA_ID + "] sendReliableToRM failed: " + e.getMessage());
        }
        return false;
    }
}
