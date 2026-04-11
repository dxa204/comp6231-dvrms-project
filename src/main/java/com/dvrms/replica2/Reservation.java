
public class Reservation {
    public String customerID;
    public String start;
    public String end;
    public Vehicle vehicle;

    public Reservation(String c, String s, String e, Vehicle v) {
        customerID = c;
        start = s;
        end = e;
        vehicle = v;
    }
}
