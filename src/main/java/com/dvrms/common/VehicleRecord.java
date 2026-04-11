package com.dvrms.common;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;

public class VehicleRecord implements Serializable {
    public final String vehicleID;
    public final String vehicleType;
    public int vehicleNumber;
    public int reservationPrice;
    public final Deque<WaitlistEntry> waitlist = new ArrayDeque<>();

    public VehicleRecord(String vehicleID, String vehicleType, int vehicleNumber, int reservationPrice) {
        this.vehicleID = vehicleID;
        this.vehicleType = vehicleType;
        this.vehicleNumber = vehicleNumber;
        this.reservationPrice = reservationPrice;
    }

    public static class WaitlistEntry implements Serializable {
        public final String customerID;
        public final LocalDate start;
        public final LocalDate end;

        public WaitlistEntry(String customerID, LocalDate start, LocalDate end) {
            this.customerID = customerID;
            this.start = start;
            this.end = end;
        }
    }
}
