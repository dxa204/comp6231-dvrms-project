import org.omg.CORBA.ORB;
import org.omg.CosNaming.*;
import org.omg.PortableServer.*;

public class RentalOfficeServer {

    public static void main(String[] args) {

        try {
            if (args.length < 1) {
                System.out.println(
                        "Usage: java RentalOfficeServer <OfficeID> " +
                        "[-ORBInitialPort port] [-ORBInitialHost host]"
                );
                return;
            }

            String officeID = args[0].toUpperCase();

            int udpPort;
            switch (officeID) {
                case "MTL":
                    udpPort = 5000;
                    break;
                case "WPG":
                    udpPort = 5001;
                    break;
                case "BNF":
                    udpPort = 5002;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown office ID: " + officeID);
            }


            /* ===================== ORB ===================== */

            ORB orb = ORB.init(args, null);

            /* ===================== POA ===================== */

            POA rootPOA =
                    POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            /* ===================== SERVANT ===================== */

            RentalOfficeImpl rentalOffice = new RentalOfficeImpl(officeID);
            rentalOffice.setORB(orb);

            org.omg.CORBA.Object ref =
                    rootPOA.servant_to_reference(rentalOffice);

            /* ===================== NAMING SERVICE ===================== */

            org.omg.CORBA.Object objRef =
                    orb.resolve_initial_references("NameService");

            NamingContextExt ncRef =
                    NamingContextExtHelper.narrow(objRef);

            NameComponent[] path =
                    ncRef.to_name("RentalOffice_" + officeID);

            ncRef.rebind(path, ref);

            System.out.println(
                    "CORBA RentalOffice " + officeID +
                            " bound in Naming Service");

            /* ===================== UDP SERVER ===================== */

            Thread udpThread =
                    new Thread(new RentalOfficeUDPServer(
                            officeID,
                            udpPort,
                            rentalOffice));

            udpThread.start();

            System.out.println(
                    "RentalOfficeServer " + officeID +
                            " running | UDP port " + udpPort);

            /* ===================== ORB LOOP ===================== */

            orb.run();

        } catch (Exception e) {
            System.err.println("RentalOfficeServer ERROR:");
            e.printStackTrace();
        }
    }
}

