public class Vehicle {

    private final String vehicleID;
    private String vehicleNumber;
    private String vehicleType;
    private double price;
    private boolean available;
    private final String managerID;

    public Vehicle(String vehicleID, String vehicleNumber, String vehicleType, String managerID, double price) {
        this.vehicleID = vehicleID;
        this.vehicleNumber = vehicleNumber;
        this.vehicleType = vehicleType;
        this.managerID = managerID;
        this.price = price;
        this.available = true;
    }

    public boolean isAvailable() {
        return this.available;
    }

    public void reserve() {
        this.available = false;
    }
    public void release() {
        this.available = true;
    }

    public void cancelReservation() {
        this.available = true;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public String getVehicleID() {
        return this.vehicleID;
    }

    public String getVehicleNumber() {
        return this.vehicleNumber;
    }

    public String getVehicleType() {
        return this.vehicleType;
    }

    public double getPrice() {
        return this.price;
    }

    public String getManagerID() {
        return this.managerID;
    }

    @Override
    public String toString() {
        return "VehicleID: " + this.vehicleID + "\nVehicleNumber: " + this.vehicleNumber + "\nVehicleType: " + this.vehicleType + "\nPrice: " + this.price + "\nAvailable: " + this.available;
    }

}
