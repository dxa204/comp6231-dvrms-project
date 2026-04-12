import java.io.Serializable;
import java.time.LocalDate;

public class Reservation implements Serializable {
    private final String customerID;
    private final String vehicleID;
    private LocalDate startDate;
    private LocalDate endDate;

    public Reservation(String customerID, String vehicleID, LocalDate startDate, LocalDate endDate) {
        this.customerID = customerID;
        this.vehicleID = vehicleID;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public String getCustomerID() {
        return customerID;
    }

    public String getVehicleID() {
        return vehicleID;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void updateDates(LocalDate newStartDate, LocalDate newEndDate) {
        this.startDate = newStartDate;
        this.endDate = newEndDate;
    }

    public boolean overlapsWith(LocalDate otherStartDate, LocalDate otherEndDate) {
        return !(this.endDate.isBefore(otherStartDate) || this.startDate.isAfter(otherEndDate));
    }

    @Override
    public String toString() {
        return "Reservation{" +
                "customerID='" + customerID + '\'' +
                ", vehicleID='" + vehicleID + '\'' +
                ", startDate='" + startDate + '\'' +
                ", endDate='" + endDate + '\'' +
                '}';
    }
}