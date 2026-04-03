package com.dvrms.sequencer;

/**
 * Represents a replica's network endpoint.
 * The Sequencer maintains a list of these to know where to multicast requests.
 */
public class ReplicaTarget {

    private final String replicaId;
    private String host;
    private int port;
    private boolean active;

    public ReplicaTarget(String replicaId, String host, int port) {
        this.replicaId = replicaId;
        this.host = host;
        this.port = port;
        this.active = true;
    }

    public String getReplicaId() {
        return replicaId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Update this target's endpoint (used when RM replaces a replica).
     */
    public void updateEndpoint(String newHost, int newPort) {
        this.host = newHost;
        this.port = newPort;
        this.active = true;
    }

    @Override
    public String toString() {
        return "Replica[" + replicaId + " @ " + host + ":" + port + " active=" + active + "]";
    }
}
