package com.dvrms.frontend;

import org.omg.CORBA.*;

import FrontEndApp.FrontEndPOA;
import com.dvrms.common.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FrontEndImpl extends FrontEndPOA {

    private final Map<String, List<Response>> responseMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> faultCount = new ConcurrentHashMap<>();
    private final Map<String, Long> requestStartTime = new ConcurrentHashMap<>();


    public FrontEndImpl() throws Exception {
        new UDPServer(responseMap).start();
    }

    // =========================
    // CORBA METHODS
    // =========================

    // ----- Manager Operations -----
    @Override
    public String addVehicle(String managerID, String vehicleNumber, String vehicleType, String vehicleID, double price) {
        return processRequest("add", managerID, vehicleNumber, vehicleType, vehicleID, String.valueOf(price));
    }

    @Override
    public String removeVehicle(String managerID, String vehicleID) {
        return processRequest("remove", managerID, vehicleID);
    }

    @Override
    public String listAvailableVehicles(String managerID) {
        return processRequest("list", managerID);
    }

    // ----- Customer Operations -----
    @Override
    public String reserveVehicle(String customerID, String vehicleType, String startDate, String endDate) {
        return processRequest("reserve", customerID, vehicleType, startDate, endDate);
    }

    @Override
    public String remoteReserveVehicle(String customerID, String vehicleType, String startDate, String endDate, double budgetAmount) {
        return processRequest("remoteReserve", customerID, vehicleType, startDate, endDate, String.valueOf(budgetAmount));
    }

    @Override
    public String updateReservation(String customerID, String vehicleID, String newStartDate, String newEndDate) {
        return processRequest("update", customerID, vehicleID, newStartDate, newEndDate);
    }

    @Override
    public String cancelReservation(String customerID, String vehicleID) {
        return processRequest("cancel", customerID, vehicleID);
    }

    @Override
    public String remoteCancelReservation(String customerID, String vehicleID, double budgetAmount) {
        return processRequest("remoteCancel", customerID, vehicleID);
    }

    @Override
    public String findVehicle(String customerID, String vehicleType) {
        return processRequest("find", customerID, vehicleType);
    }

    @Override
    public String addToWaitingList(String customerID, String vehicleID, String startDate, String endDate) {
        return processRequest("waitlist", customerID, vehicleID, startDate, endDate);
    }

    @Override
    public String getLocalVehiclesByType(String vehicleType) {
        return processRequest("local", vehicleType);
    }

    // =========================
    // CORE LOGIC
    // =========================

    private String processRequest(String operation, String... params) {
        try {
            String requestID = generateRequestID();
            Request req = new Request(requestID, operation, params);

            responseMap.put(requestID, new CopyOnWriteArrayList<>());
            requestStartTime.put(requestID, System.currentTimeMillis());

            sendToSequencer(req);

            return waitForMajority(requestID);

        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR";
        }
    }

    private String generateRequestID() {
        return "REQ-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }


    // =========================
    // SEQUENCER COMMUNICATION
    // =========================
    private void sendToSequencer(Request req) {

        StringBuilder sb = new StringBuilder();

        // SEQ_REQ|<msgID>|<feHost>|<fePort>|<method>|<args...>
        sb.append(Config.MSG_SEQ_REQ).append(Config.DELIMITER)
                .append(req.requestID).append(Config.DELIMITER)
                .append(Config.FE_HOST).append(Config.DELIMITER)
                .append(Config.FE_PORT).append(Config.DELIMITER)
                .append(req.operation);

        for (String p : req.params) {
            sb.append(Config.DELIMITER).append(p);
        }

        UDPClient.send(sb.toString(), Config.SEQUENCER_HOST, Config.SEQUENCER_PORT);
    }

    // =========================
    // WAIT FOR MAJORITY
    // =========================

    private String waitForMajority(String requestID) throws InterruptedException {

        long timeout = Config.ACK_TIMEOUT_MS * Config.MAX_RETRIES * 2; // fallback
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeout) {

            List<Response> responses = responseMap.get(requestID);

            if (responses != null && responses.size() >= 2) {

                String result = majorityVote(responses);

                if (result != null) {
                    detectSoftwareFailure(responses, result, requestID);
                    return result;
                }
            }

            Thread.sleep(50);
        }

        // Timeout → crash detection
        detectCrash(requestID);
        return "TIMEOUT ERROR";
    }

    // =========================
    // MAJORITY VOTING
    // =========================

    private String majorityVote(List<Response> responses) {
        Map<String, Integer> count = new HashMap<>();

        for (Response r : responses) {
            count.put(r.result, count.getOrDefault(r.result, 0) + 1);
        }

        for (String res : count.keySet()) {
            if (count.get(res) >= 2) {
                return res;
            }
        }

        return null;
    }

    // =========================
    // FAILURE DETECTION
    // =========================

    private void detectSoftwareFailure(List<Response> responses, String correct, String msgID) {

        for (Response r : responses) {

            if (!r.result.equals(correct)) {

                int count = faultCount.getOrDefault(r.replicaID, 0) + 1;
                faultCount.put(r.replicaID, count);

                System.out.println("[FE] Fault detected: " + r.replicaID + " count=" + count);

                if (count >= 3) {

                    String faultMsg = Config.MSG_FAULT_REPORT
                            + Config.DELIMITER + r.replicaID
                            + Config.DELIMITER + msgID;

                    notifyRM(faultMsg);

                    faultCount.put(r.replicaID, 0);
                }

            } else {
                faultCount.put(r.replicaID, 0);
            }
        }
    }

    private void detectCrash(String msgID) {

        System.out.println("[FE] Timeout → detecting crashed replicas");

        List<Response> responses = responseMap.getOrDefault(msgID, new ArrayList<>());

        Set<String> responded = new HashSet<>();
        for (Response r : responses) {
            responded.add(r.replicaID);
        }

        List<String> allReplicas = Arrays.asList("R1", "R2", "R3", "R4");

        for (String replica : allReplicas) {

            if (!responded.contains(replica)) {

                System.out.println("[FE] Suspected crash: " + replica);

                notifyRM(Config.MSG_CRASH_SUSPECT
                        + Config.DELIMITER + replica);
            }
        }
    }

    private void notifyRM(String message) {

        int[] rmPorts = {
                Config.RM_1_PORT,
                Config.RM_2_PORT,
                Config.RM_3_PORT,
                Config.RM_4_PORT
        };

        for (int port : rmPorts) {
            UDPClient.send(message, Config.FE_HOST, port);
        }
    }
}
