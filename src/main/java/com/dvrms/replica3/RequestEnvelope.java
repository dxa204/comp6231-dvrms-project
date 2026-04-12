package com.dvrms.replica3;

import java.util.Arrays;

public final class RequestEnvelope {
    public final String msgId;
    public final int seqNum;
    public final String feHost;
    public final int fePort;
    public final String method;
    public final String[] args;

    private RequestEnvelope(Builder builder) {
        this.msgId = builder.msgId;
        this.seqNum = builder.seqNum;
        this.feHost = builder.feHost;
        this.fePort = builder.fePort;
        this.method = builder.method;
        this.args = builder.args;
    }

    public static RequestEnvelope from(String[] parts) {
        return new Builder()
                .msgId(parts[1])
                .seqNum(Integer.parseInt(parts[2]))
                .feHost(parts[3])
                .fePort(Integer.parseInt(parts[4]))
                .method(parts[5])
                .args(Arrays.copyOfRange(parts, 6, parts.length))
                .build();
    }

    public static RequestEnvelope parse(String rawMessage) {
        return from(rawMessage.split("\\|"));
    }

    public String targetOffice() {
        if (args.length == 0 || args[0] == null || args[0].length() < 3) {
            throw new IllegalArgumentException("Cannot determine target office for method " + method);
        }
        return officePrefix(args[0]);
    }

    private static String officePrefix(String value) {
        return value.substring(0, 3);
    }

    public boolean isManagerOperation() {
        return "addVehicle".equals(method)
                || "removeVehicle".equals(method)
                || "listAvailableVehicle".equals(method);
    }

    public boolean isCustomerOperation() {
        return !isManagerOperation();
    }

    public String primaryActorId() {
        if (args.length == 0) {
            throw new IllegalStateException("No primary actor id available for method " + method);
        }
        return args[0];
    }

    public static final class Builder {
        private String msgId;
        private int seqNum;
        private String feHost;
        private int fePort;
        private String method;
        private String[] args = new String[0];

        public Builder msgId(String msgId) {
            this.msgId = msgId;
            return this;
        }

        public Builder seqNum(int seqNum) {
            this.seqNum = seqNum;
            return this;
        }

        public Builder feHost(String feHost) {
            this.feHost = feHost;
            return this;
        }

        public Builder fePort(int fePort) {
            this.fePort = fePort;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder args(String[] args) {
            this.args = args;
            return this;
        }

        public RequestEnvelope build() {
            return new RequestEnvelope(this);
        }
    }
}
