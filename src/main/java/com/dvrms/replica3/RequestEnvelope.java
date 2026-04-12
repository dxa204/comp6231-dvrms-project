package com.dvrms.replica3;

import java.util.Arrays;

public class RequestEnvelope {
    public final String msgId;
    public final int seqNum;
    public final String feHost;
    public final int fePort;
    public final String method;
    public final String[] args;

    public RequestEnvelope(String msgId, int seqNum, String feHost, int fePort,
                           String method, String[] args) {
        this.msgId = msgId;
        this.seqNum = seqNum;
        this.feHost = feHost;
        this.fePort = fePort;
        this.method = method;
        this.args = args;
    }

    public static RequestEnvelope from(String[] parts) {
        String msgId = parts[1];
        int seqNum = Integer.parseInt(parts[2]);
        String feHost = parts[3];
        int fePort = Integer.parseInt(parts[4]);
        String method = parts[5];
        String[] args = Arrays.copyOfRange(parts, 6, parts.length);
        return new RequestEnvelope(msgId, seqNum, feHost, fePort, method, args);
    }

    public String getTargetOffice() {
        switch (method) {
            case "addVehicle":
            case "removeVehicle":
            case "listAvailableVehicle":
                return args[0].substring(0, 3);
            case "reserveVehicle":
            case "cancelReservation":
            case "updateReservation":
                return args[0].substring(0, 3);
            case "findVehicle":
            case "displayCurrentBudget":
            case "displayReservations":
            case "displayNotifications":
                return args[0].substring(0, 3);
            default:
                return args[0].substring(0, 3);
        }
    }
}
