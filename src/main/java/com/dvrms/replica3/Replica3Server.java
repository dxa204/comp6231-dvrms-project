package com.dvrms.replica3;

import com.dvrms.common.Config;
import com.dvrms.common.InitialData;
import com.dvrms.common.ReplicaResponseNormalizer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replica 3 server process.
 *
 * Protocols preserved:
 *   Sequencer -> Replica: REQ|<msgId>|<seqNum>|<feHost>|<fePort>|<method>|<args...>
 *   Replica   -> Sequencer: ACK|<replicaId>|<msgId>
 *   Replica   -> FE:       RESULT|<msgId>|R3|<result>
 *
 *   RM <-> Replica:
 *     Replica -> RM (startup): REGISTER|<replicaIdInt>|<city>|<listenPort>
 *     RM -> Replica (probe):   PING|<replicaIdInt>
 *     RM -> Replica (kill):    CRASH|<replicaIdInt>
 */
public class Replica3Server {

    private static final String REPLICA_ID = "R3";
    private static final int REPLICA_ID_INT = 3;
    private static final int LISTEN_PORT = Config.REPLICA_3_PORT;

    private static final int MTL_UDP_PORT = 7101;
    private static final int WPG_UDP_PORT = 7102;
    private static final int BNF_UDP_PORT = 7103;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DMY_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final Map<String, OfficeServer> offices = new ConcurrentHashMap<>();
    private final SequencedExecutor sequencedExecutor = new SequencedExecutor();

    public Replica3Server() {
        offices.put("MTL", new OfficeServer(OfficeServer.Office.MTL, MTL_UDP_PORT));
        offices.put("WPG", new OfficeServer(OfficeServer.Office.WPG, WPG_UDP_PORT));
        offices.put("BNF", new OfficeServer(OfficeServer.Office.BNF, BNF_UDP_PORT));
    }

    public static void main(String[] args) throws Exception {
        new Replica3Server().start();
    }

    public void start() throws Exception {
        seedInitialData();
        startOfficeUdpListeners();

        try (DatagramSocket socket = new DatagramSocket(LISTEN_PORT)) {
            System.out.println("[" + REPLICA_ID + "] listening on port " + LISTEN_PORT);
            registerWithReplicaManagers();
            listenForever(socket);
        }
    }

    private void listenForever(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[Config.BUFFER_SIZE];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
            handleMessage(message, socket, packet);
        }
    }

    private void handleMessage(String message, DatagramSocket socket, DatagramPacket packet) throws Exception {
        if (message.startsWith(Config.MSG_REQ + "|")) {
            acceptSequencerRequest(message, socket, packet);
            return;
        }

        if (message.startsWith("PING|")) {
            replyPong(socket, packet);
            return;
        }

        if (message.startsWith("CRASH|")) {
            terminateReplica(message);
        }
    }

    private void acceptSequencerRequest(String rawMessage, DatagramSocket socket, DatagramPacket packet) throws Exception {
        RequestEnvelope envelope = RequestEnvelope.parse(rawMessage);
        acknowledgeSequencer(socket, packet, envelope.msgId);

        for (ExecutionRecord record : sequencedExecutor.accept(envelope)) {
            sendResultToFrontEnd(socket, record.envelope, record.result);
        }
    }

    private void acknowledgeSequencer(DatagramSocket socket, DatagramPacket packet, String msgId) throws IOException {
        byte[] payload = (Config.MSG_ACK + "|" + REPLICA_ID + "|" + msgId).getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort()));
    }

    private void replyPong(DatagramSocket socket, DatagramPacket packet) throws IOException {
        byte[] payload = "PONG".getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort()));
    }

    private void terminateReplica(String message) {
        System.out.println("[" + REPLICA_ID + "] received " + message + " from RM — shutting down");
        for (OfficeServer office : offices.values()) {
            office.shutdown();
        }
        System.exit(0);
    }

    private void sendResultToFrontEnd(DatagramSocket socket, RequestEnvelope envelope, String result) throws IOException {
        String response = Config.MSG_RESULT + "|" + envelope.msgId + "|" + REPLICA_ID + "|"
                + ReplicaResponseNormalizer.normalize(envelope.method, result);
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(
                payload,
                payload.length,
                InetAddress.getByName(envelope.feHost),
                envelope.fePort
        );
        socket.send(packet);
    }

    private String execute(RequestEnvelope envelope) {
        try {
            OfficeServer target = resolveOffice(envelope.targetOffice());
            return invoke(target, envelope);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private OfficeServer resolveOffice(String officeCode) {
        OfficeServer office = offices.get(officeCode);
        if (office == null) {
            throw new IllegalArgumentException("Unknown office " + officeCode);
        }
        return office;
    }

    private String invoke(OfficeServer target, RequestEnvelope envelope) {
        String[] args = envelope.args;

        switch (envelope.method) {
            case "addVehicle":
                return target.addVehicle(
                        args[0],
                        Integer.parseInt(args[1]),
                        args[2],
                        args[3],
                        Integer.parseInt(args[4])
                );
            case "removeVehicle":
                return target.removeVehicle(args[0], args[1]);
            case "listAvailableVehicle":
            case "listAvailableVehicles":
                return target.listAvailableVehicle(args[0]);
            case "reserveVehicle":
                return target.reserveVehicle(args[0], args[1], toDdMmYyyy(args[2]), toDdMmYyyy(args[3]));
            case "cancelReservation":
                return target.cancelReservation(args[0], args[1]);
            case "updateReservation":
                return target.updateReservation(args[0], args[1], toDdMmYyyy(args[2]), toDdMmYyyy(args[3]));
            case "findVehicle":
                return target.findVehicle(args[0], args[1]);
            case "displayCurrentBudget":
                return target.displayCurrentBudget(args[0]);
            case "displayReservations":
                return target.displayReservations(args[0]);
            case "displayNotifications":
                return target.displayNotifications(args[0]);
            default:
                return "ERROR: unknown method " + envelope.method;
        }
    }

    private void seedInitialData() {
        resolveOffice("MTL").seed(InitialData.getMTLData());
        resolveOffice("WPG").seed(InitialData.getWPGData());
        resolveOffice("BNF").seed(InitialData.getBNFData());
        System.out.println("[" + REPLICA_ID + "] seeded initial data");
    }

    private String toDdMmYyyy(String value) {
        if (value != null && value.matches("\\d{8}")) {
            return value;
        }
        return LocalDate.parse(value, ISO_DATE).format(DMY_DATE);
    }

    private void startOfficeUdpListeners() {
        for (OfficeServer office : offices.values()) {
            office.startUdpListener();
        }
    }

    private void registerWithReplicaManagers() {
        String message = "REGISTER|" + REPLICA_ID_INT + "|ALL|" + LISTEN_PORT;
        int[] rmPorts = {
                Config.RM_1_PORT,
                Config.RM_2_PORT,
                Config.RM_3_PORT,
                Config.RM_4_PORT
        };

        for (int rmPort : rmPorts) {
            boolean acked = sendReliableToRM("localhost", rmPort, message);
            System.out.println("[" + REPLICA_ID + "] REGISTER -> RM@" + rmPort + " "
                    + (acked ? "ACKed" : "no ACK after retries"));
        }
    }

    private boolean sendReliableToRM(String host, int port, String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(Config.ACK_TIMEOUT_MS);
            InetAddress address = InetAddress.getByName(host);

            for (int attempt = 0; attempt < Config.MAX_RETRIES; attempt++) {
                socket.send(new DatagramPacket(payload, payload.length, address, port));
                try {
                    byte[] buffer = new byte[128];
                    DatagramPacket ackPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(ackPacket);
                    String reply = new String(ackPacket.getData(), 0, ackPacket.getLength(), StandardCharsets.UTF_8).trim();
                    if (reply.startsWith("ACK")) {
                        return true;
                    }
                } catch (SocketTimeoutException ignored) {
                    // retry
                }
            }
        } catch (IOException e) {
            System.err.println("[" + REPLICA_ID + "] sendReliableToRM failed: " + e.getMessage());
        }

        return false;
    }

    private final class SequencedExecutor {
        private int nextExpectedSeq = 1;
        private final Map<Integer, RequestEnvelope> pending = new TreeMap<>();
        private final Map<Integer, String> completed = new ConcurrentHashMap<>();

        private Iterable<ExecutionRecord> accept(RequestEnvelope envelope) {
            java.util.List<ExecutionRecord> ready = new java.util.ArrayList<>();

            if (completed.containsKey(envelope.seqNum)) {
                return ready;
            }

            pending.put(envelope.seqNum, envelope);

            while (pending.containsKey(nextExpectedSeq)) {
                RequestEnvelope next = pending.remove(nextExpectedSeq);
                String result = execute(next);
                completed.put(nextExpectedSeq, result);
                ready.add(new ExecutionRecord(next, result));
                nextExpectedSeq++;
            }

            return ready;
        }
    }

    private static final class ExecutionRecord {
        private final RequestEnvelope envelope;
        private final String result;

        private ExecutionRecord(RequestEnvelope envelope, String result) {
            this.envelope = envelope;
            this.result = result;
        }
    }
}
