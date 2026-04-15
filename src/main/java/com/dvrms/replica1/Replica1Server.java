package com.dvrms.replica1;

import com.dvrms.common.Config;
import com.dvrms.common.InitialData;
import com.dvrms.common.ReplicaResponseNormalizer;
import com.dvrms.common.VehicleRecord;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class Replica1Server {

    private static final String REPLICA_ID = "R1";
    private static final int REPLICA_ID_INT = 1;
    private static final int LISTEN_PORT = Config.REPLICA_1_PORT;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DMY_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final Map<String, OfficeServer> offices = new ConcurrentHashMap<>();
    private final Map<Integer, RequestEnvelope> pending = new TreeMap<>();
    private final Map<Integer, String> executed = new ConcurrentHashMap<>();

    private int nextExpectedSeq = 1;

    public static void main(String[] args) throws Exception {
        new Replica1Server().start();
    }

    public void start() throws Exception {
        createOffices();
        seedInitialData();
        startOfficeUdpListeners();

        try (DatagramSocket socket = new DatagramSocket(LISTEN_PORT)) {
            System.out.println("[" + REPLICA_ID + "] listening on port " + LISTEN_PORT);
            registerWithReplicaManagers();
            listenForever(socket);
        }
    }

    private void createOffices() {
        offices.put("MTL", new OfficeServer(OfficeServer.Office.MTL, 7201));
        offices.put("WPG", new OfficeServer(OfficeServer.Office.WPG, 7202));
        offices.put("BNF", new OfficeServer(OfficeServer.Office.BNF, 7203));
    }

    private void startOfficeUdpListeners() {
        for (OfficeServer office : offices.values()) {
            office.startUdpListener();
        }
    }

    private void seedInitialData() {
        seedOffice("MTL", InitialData.getMTLData());
        seedOffice("WPG", InitialData.getWPGData());
        seedOffice("BNF", InitialData.getBNFData());
        System.out.println("[" + REPLICA_ID + "] seeded initial data");
    }

    private void seedOffice(String officeCode, Map<String, VehicleRecord> data) {
        OfficeServer office = offices.get(officeCode);
        String managerId = officeCode + "M1111";
        for (VehicleRecord record : data.values()) {
            office.addVehicle(managerId, record.vehicleNumber, record.vehicleType, record.vehicleID, record.reservationPrice);
        }
    }

    private void listenForever(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[Config.BUFFER_SIZE];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
            handleIncoming(message, socket, packet);
        }
    }

    private void handleIncoming(String message, DatagramSocket socket, DatagramPacket packet) throws Exception {
        if (message.startsWith(Config.MSG_REQ + "|")) {
            handleSequencerRequest(message, socket, packet);
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

    private void handleSequencerRequest(String message, DatagramSocket socket, DatagramPacket packet) throws Exception {
        RequestEnvelope envelope = RequestEnvelope.from(message.split("\\|"));
        acknowledgeSequencer(socket, packet, envelope.msgId);

        if (executed.containsKey(envelope.seqNum)) {
            return;
        }

        pending.put(envelope.seqNum, envelope);
        while (pending.containsKey(nextExpectedSeq)) {
            RequestEnvelope next = pending.remove(nextExpectedSeq);
            String normalized = ReplicaResponseNormalizer.normalize(next.method, execute(next));
            executed.put(nextExpectedSeq, normalized);
            sendResultToFrontEnd(socket, next, normalized);
            nextExpectedSeq++;
        }
    }

    private void acknowledgeSequencer(DatagramSocket socket, DatagramPacket packet, String msgId) throws IOException {
        byte[] payload = (Config.MSG_ACK + "|" + REPLICA_ID + "|" + msgId).getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort()));
    }

    private void sendResultToFrontEnd(DatagramSocket socket, RequestEnvelope envelope, String result) throws IOException {
        String response = Config.MSG_RESULT + "|" + envelope.msgId + "|" + REPLICA_ID + "|" + result;
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(
                payload,
                payload.length,
                InetAddress.getByName(envelope.feHost),
                envelope.fePort
        );
        socket.send(packet);
    }

    private void replyPong(DatagramSocket socket, DatagramPacket packet) throws IOException {
        byte[] payload = "PONG".getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort()));
    }

    private void terminateReplica(String message) {
        System.out.println("[" + REPLICA_ID + "] received " + message + " from RM - shutting down");
        for (OfficeServer office : offices.values()) {
            office.shutdown();
        }
        System.exit(0);
    }

    private String execute(RequestEnvelope envelope) {
        try {
            switch (envelope.method) {
                case "addVehicle":
                    return officeForManager(envelope.args[0]).addVehicle(
                            envelope.args[0], Integer.parseInt(envelope.args[1]), envelope.args[2], envelope.args[3], parseReservationPrice(envelope.args[4]));
                case "removeVehicle":
                    return officeForManager(envelope.args[0]).removeVehicle(envelope.args[0], envelope.args[1]);
                case "listAvailableVehicle":
                case "listAvailableVehicles":
                    return officeForManager(envelope.args[0]).listAvailableVehicle(envelope.args[0]);
                case "reserveVehicle":
                    return officeForCustomer(envelope.args[0]).reserveVehicle(
                            envelope.args[0], envelope.args[1], toDdMmYyyy(envelope.args[2]), toDdMmYyyy(envelope.args[3]));
                case "cancelReservation":
                    return officeForCustomer(envelope.args[0]).cancelReservation(envelope.args[0], envelope.args[1]);
                case "updateReservation":
                    return officeForCustomer(envelope.args[0]).updateReservation(
                            envelope.args[0], envelope.args[1], toDdMmYyyy(envelope.args[2]), toDdMmYyyy(envelope.args[3]));
                case "findVehicle":
                    return officeForCustomer(envelope.args[0]).findVehicle(envelope.args[0], envelope.args[1]);
                case "displayCurrentBudget":
                    return officeForCustomer(envelope.args[0]).displayCurrentBudget(envelope.args[0]);
                case "displayReservations":
                    return officeForCustomer(envelope.args[0]).displayReservations(envelope.args[0]);
                case "displayNotifications":
                    return officeForCustomer(envelope.args[0]).displayNotifications(envelope.args[0]);
                default:
                    return "Unsupported operation in replica1 backend: " + envelope.method;
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private OfficeServer officeForManager(String managerId) {
        return officeByCode(managerId.substring(0, 3));
    }

    private OfficeServer officeForCustomer(String customerId) {
        return officeByCode(customerId.substring(0, 3));
    }

    private OfficeServer officeByCode(String officeCode) {
        OfficeServer office = offices.get(officeCode);
        if (office == null) {
            throw new IllegalArgumentException("Unknown office " + officeCode);
        }
        return office;
    }

    private String toDdMmYyyy(String value) {
        if (value != null && value.matches("\\d{8}")) {
            return value;
        }
        return LocalDate.parse(value, ISO_DATE).format(DMY_DATE);
    }

    private int parseReservationPrice(String value) {
        return (int) Math.round(Double.parseDouble(value));
    }

    private void registerWithReplicaManagers() {
        String message = "REGISTER|" + REPLICA_ID_INT + "|ALL|" + LISTEN_PORT;
        for (int rmId = 1; rmId <= 4; rmId++) {
            int rmPort = Config.rmPort(rmId);
            boolean acked = sendReliableToRM(Config.rmHost(rmId), rmPort, message);
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

    private static final class RequestEnvelope {
        private final String msgId;
        private final int seqNum;
        private final String feHost;
        private final int fePort;
        private final String method;
        private final String[] args;

        private RequestEnvelope(String msgId, int seqNum, String feHost, int fePort, String method, String[] args) {
            this.msgId = msgId;
            this.seqNum = seqNum;
            this.feHost = feHost;
            this.fePort = fePort;
            this.method = method;
            this.args = args;
        }

        private static RequestEnvelope from(String[] parts) {
            return new RequestEnvelope(
                    parts[1],
                    Integer.parseInt(parts[2]),
                    parts[3],
                    Integer.parseInt(parts[4]),
                    parts[5],
                    Arrays.copyOfRange(parts, 6, parts.length)
            );
        }
    }
}
