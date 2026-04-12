package com.dvrms.replica1;

import java.time.LocalDate;

public class WaitingRequest {

    private final String customerID;
    private final LocalDate startDate;
    private final LocalDate endDate;

    public WaitingRequest(String customerID, LocalDate startDate, LocalDate endDate) {
        this.customerID = customerID;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public String getCustomerID() {
        return customerID;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean overlapsWith(LocalDate otherStartDate, LocalDate otherEndDate) {
        return !(this.endDate.isBefore(otherStartDate) || this.startDate.isAfter(otherEndDate));
    }
}
