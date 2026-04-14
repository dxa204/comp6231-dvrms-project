package com.dvrms.replica2;

/**
 * Legacy entry point kept for compatibility.
 *
 * Delegate to VehicleServer.main() so replica2 uses one consistent startup path
 * for port assignment and ORB host configuration.
 */
public class VehicleServerMain {

    public static void main(String[] args) throws Exception {
        VehicleServer.main(args);
    }
}
