package com.dvrms.frontend;

public class Request {
    String requestID;
    String operation;
    String[] params;

    public Request(String requestID, String operation, String[] params) {
        this.requestID = requestID;
        this.operation = operation;
        this.params = params;
    }

    public String serialize() {
        return requestID + "|" + operation + "|" + String.join(",", params);
    }
}
