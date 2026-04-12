package com.dvrms.replica4;

import com.dvrms.common.Config;
import com.dvrms.common.InitialData;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class Replica4Server {

    private static final String REPLICA_ID = "R4";
    private static final int REPLICA_ID_INT = 4;
    private static final int REPLICA_PORT = Config.REPLICA_4_PORT;

    private final OfficeServer mtl = new OfficeServer(OfficeServer.Office.MTL, 7401);
    private final OfficeServer wpg = new OfficeServer(OfficeServer.Office.WPG, 7402);
    private final OfficeServer bnf = new OfficeServer(OfficeServer.Office.BNF, 7403);

    private int nextExpectedSeq = 1;
    private final Map<Integer, RequestEnvelope> buffer = new TreeMap<>();
    private final Map<Integer, String> executedResults = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Replica4Server replica = new Replica4Server();
        replica.start();
    }

    public void start() throws Exception {
        seedInitialData();
        mtl.startUdpListener();
        wpg.startUdpListener();
        bnf.startUdpListener();


        try (DatagramSocket socket = new DatagramSocket(REPLICA_PORT)) {
            System.out.println("[" + REPLICA_ID + "] listening on port " + REPLICA_PORT);

            // Announce ourselves to every RM now that the listen socket is bound.
            registerWithReplicaManagers();

            byte[] buf = new byte[4096];

            while (true) {
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);

                String msg = new String(req.getData(), 0, req.getLength(), StandardCharsets.UTF_8).trim();
                handleIncoming(msg, socket, req);
            }
        }
    }

    private void handleIncoming(String msg, DatagramSocket socket, DatagramPacket req) throws Exception {
        if (msg.startsWith("REQ|")) {
            handleReplicaRequest(msg, socket, req);
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
        // Unknown — ignore silently
    }

    private void handlePing(DatagramSocket socket, DatagramPacket req) throws IOException {
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

    private void handleReplicaRequest(String msg, DatagramSocket socket, DatagramPacket req) throws Exception {
        String[] parts = msg.split("\\|");
        if (parts.length < 6 || !parts[0].equals("REQ")) {
            return;
        }

        String msgId = parts[1];
        int seqNum = Integer.parseInt(parts[2]);

        // ACK immediately to sequencer
        String ack = "ACK|" + msgId;
        DatagramPacket ackPacket = new DatagramPacket(
                ack.getBytes(StandardCharsets.UTF_8),
                ack.length(),
                req.getAddress(),
                req.getPort()
        );
        socket.send(ackPacket);

        // duplicate suppression
        if (executedResults.containsKey(seqNum)) {
            return;
        }

        RequestEnvelope env = RequestEnvelope.from(parts);
        buffer.put(seqNum, env);

        processBufferedRequests(socket);
    }

    private void processBufferedRequests(DatagramSocket socket) throws Exception {
        while (buffer.containsKey(nextExpectedSeq)) {
            RequestEnvelope env = buffer.remove(nextExpectedSeq);

            String result = dispatch(env);
            executedResults.put(nextExpectedSeq, result);

            String response = "RESULT|" + env.msgId + "|R4|" + result;

            DatagramPacket resp = new DatagramPacket(
                    response.getBytes(StandardCharsets.UTF_8),
                    response.length(),
                    InetAddress.getByName(env.feHost),
                    env.fePort
            );
            socket.send(resp);

            nextExpectedSeq++;
        }
    }

    private String dispatch(RequestEnvelope env) {
        OfficeServer target = chooseOfficeServer(env);
        return invokeMethod(target, env);
    }

    private OfficeServer chooseOfficeServer(RequestEnvelope env) {
        String officeCode = env.getTargetOffice();
        switch (officeCode) {
            case "MTL": return mtl;
            case "WPG": return wpg;
            case "BNF": return bnf;
            default: throw new IllegalArgumentException("Unknown office " + officeCode);
        }
    }

    private String invokeMethod(OfficeServer target, RequestEnvelope env) {
        switch (env.method) {
            case "addVehicle":
                return target.addVehicle(env.args[0], Integer.parseInt(env.args[1]), env.args[2], env.args[3], Integer.parseInt(env.args[4]));
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

        System.out.println("Replica 4 seeded successfully");
    }

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