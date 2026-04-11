package com.dvrms.replica4;

import com.dvrms.common.InitialData;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class Replica4Server {

    private static final int REPLICA_PORT = 6004;

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
            System.out.println("Replica 4 listening on port " + REPLICA_PORT);

            byte[] buf = new byte[4096];

            while (true) {
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);

                String msg = new String(req.getData(), 0, req.getLength(), StandardCharsets.UTF_8);
                handleReplicaRequest(msg, socket, req);
            }
        }
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
}