package com.dvrms.replica1;

import java.io.Serializable;
import java.time.LocalDate;

public class ReservationRecord implements Serializable {
    public final String customerID;
    public final String vehicleID;
    public final int totalPrice;
    public final LocalDate startDate;
    public final LocalDate endDate;

    public ReservationRecord(String customerID, String vehicleID,
                             int totalPrice, LocalDate startDate, LocalDate endDate) {
        this.customerID = customerID;
        this.vehicleID = vehicleID;
        this.totalPrice = totalPrice;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
