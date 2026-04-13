package com.dvrms.frontend;

import com.dvrms.common.Config;

import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class UDPServer extends Thread {

    private final DatagramSocket socket;
    private final Map<String, List<Response>> responseMap;

    public UDPServer(Map<String, List<Response>> responseMap) throws Exception {
        this.socket = new DatagramSocket(Config.FE_PORT);
        this.responseMap = responseMap;

        System.out.println("[FE-UDP] Listening on port " + Config.FE_PORT);
    }

    @Override
    public void run() {

        byte[] buffer = new byte[Config.BUFFER_SIZE];

        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength()).trim();

                System.out.println("[FE RECEIVE] " + message);

                // ✅ Only process RESULT messages
                if (!message.startsWith(Config.MSG_RESULT)) {
                    System.out.println("[FE-UDP] Ignored non-RESULT message");
                    continue;
                }

                handleResult(message);

            } catch (Exception e) {
                System.err.println("[FE-UDP] Error receiving packet: " + e.getMessage());
            }
        }
    }

    // =========================
    // HANDLE RESULT
    // =========================

    private void handleResult(String message) {

        try {
            // RESULT|<requestID>|<replicaID>|<result>
            // Preserve a trailing empty result payload, e.g. RESULT|req|R1|
            String[] parts = message.split("\\" + Config.DELIMITER, -1);

            if (parts.length < 4) {
                System.err.println("[FE-UDP] Malformed RESULT: " + message);
                return;
            }

            String requestID = parts[1];
            String replicaID = parts[2];

            // Handle cases where result contains '|'
            StringBuilder resultBuilder = new StringBuilder();
            for (int i = 3; i < parts.length; i++) {
                if (i > 3) resultBuilder.append(Config.DELIMITER);
                resultBuilder.append(parts[i]);
            }

            String result = resultBuilder.toString();

            Response response = new Response(requestID, replicaID, result);

            // ✅ Thread-safe insert
            responseMap.computeIfAbsent(requestID,
                    k -> new CopyOnWriteArrayList<>());

            // ✅ Avoid duplicate responses from same replica
            boolean alreadyExists = responseMap.get(requestID).stream()
                    .anyMatch(r -> r.replicaID.equals(replicaID));

            if (!alreadyExists) {
                responseMap.get(requestID).add(response);
                System.out.println("[FE-UDP] Stored response from " + replicaID);
            } else {
                System.out.println("[FE-UDP] Duplicate response ignored from " + replicaID);
            }

        } catch (Exception e) {
            System.err.println("[FE-UDP] Failed to parse RESULT: " + message);
        }
    }
}
