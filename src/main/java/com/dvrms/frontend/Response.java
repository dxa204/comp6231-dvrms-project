package com.dvrms.frontend;

import com.dvrms.common.Config;

public class Response {

    public String requestID;
    public String replicaID;
    public String result;

    public Response(String msg) {
        // RESULT|<requestID>|<replicaID>|<result>
        String[] parts = msg.split("\\" + Config.DELIMITER);

        this.requestID = parts[1];
        this.replicaID = parts[2];
        this.result = parts[3];
    }

    public Response(String requestID, String replicaID, String result) {
        this.requestID = requestID;
        this.replicaID = replicaID;
        this.result = result;
    }
}
