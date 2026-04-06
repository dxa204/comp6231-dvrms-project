import org.omg.CORBA.*;
import org.omg.PortableServer.*;

public class CORBAServer {
    public static void main(String[] args) {
        try {
            ORB orb = ORB.init(args, null);

            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            FrontEndImpl fe = new FrontEndImpl();
            org.omg.CORBA.Object ref = rootPOA.servant_to_reference(fe);

            System.out.println("Front-End is running...");

            orb.run();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
