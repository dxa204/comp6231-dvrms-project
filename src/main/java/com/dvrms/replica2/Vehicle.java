
import java.io.Serializable;

public class Vehicle implements Serializable {
    public String vehicleID;
    public String vehicleType;
    public String vehicleNumber;
    public double price;

    public Vehicle(String id, String type, String number, double pr) {
        this.vehicleID = id;
        this.vehicleType = type;
        this.vehicleNumber = number;
        this.price = pr;
    }
}
