
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

public class CustomerClient {
    private static DVRMS connect(String city) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", "localhost");
        props.put("org.omg.CORBA.ORBInitialPort", "1050");
        
        ORB orb = ORB.init(new String[]{}, props);
        org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
        NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
        return DVRMSHelper.narrow(ncRef.resolve_str(city));
    }

    public static void main(String[] args)throws Exception{
    	
        DVRMS clientMTL = connect("MTL");
        DVRMS clientWPG = connect("WPG");
        DVRMS clientBNF = connect("BNF");
        
        // test cases for clients
        System.out.println(clientMTL.reserveVehicle("MTLU1111", "MTL1002", "2026-03-10", "2026-03-11"));
        System.out.println(clientMTL.updateReservation("MTLU1111","MTL1002", "2026-03-10", "2026-03-11"));
        
        System.out.println("\n" + clientMTL.reserveVehicle("MTLU2222", "MTL1002", "2026-03-10", "2026-03-11"));
        System.out.println(clientMTL.cancelReservation("MTLU1111","MTL1002"));
        System.out.println(clientMTL.cancelReservation("MTLU2222","MTL1002"));
        
        System.out.println("\n" + clientMTL.reserveVehicle("MTLU1111", "WPG2001", "2026-03-10", "2026-03-11"));

        System.out.println("\n" + clientMTL.findVehicle("MTLU1111", "SUV"));
        System.out.println(clientMTL.listAvailableVehicle("MTLU1111"));
    }
}
