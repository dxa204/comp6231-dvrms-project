package com.dvrms.frontend;

import FrontEndApp.FrontEnd;
import FrontEndApp.FrontEndHelper;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

import java.util.Arrays;
import java.util.Properties;

public class FrontEndClient {

    static FrontEnd connect(String host, String port) throws Exception {
        Properties props = new Properties();
        props.put("org.omg.CORBA.ORBInitialHost", host);
        props.put("org.omg.CORBA.ORBInitialPort", port);

        ORB orb = ORB.init(new String[]{}, props);
        org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
        NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
        return FrontEndHelper.narrow(ncRef.resolve_str("FrontEnd"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String host = System.getProperty("orb.host", "localhost");
        String port = System.getProperty("orb.port", "1050");
        FrontEnd frontEnd = connect(host, port);

        String operation = args[0];
        String result;

        switch (operation) {
            case "addVehicle":
                requireArgs(operation, args, 6);
                result = frontEnd.addVehicle(args[1], args[2], args[3], args[4], Double.parseDouble(args[5]));
                break;
            case "removeVehicle":
                requireArgs(operation, args, 2);
                result = frontEnd.removeVehicle(args[1], args[2]);
                break;
            case "listAvailableVehicles":
                requireArgs(operation, args, 1);
                result = frontEnd.listAvailableVehicles(args[1]);
                break;
            case "reserveVehicle":
                requireArgs(operation, args, 4);
                result = frontEnd.reserveVehicle(args[1], args[2], args[3], args[4]);
                break;
            case "updateReservation":
                requireArgs(operation, args, 4);
                result = frontEnd.updateReservation(args[1], args[2], args[3], args[4]);
                break;
            case "cancelReservation":
                requireArgs(operation, args, 2);
                result = frontEnd.cancelReservation(args[1], args[2]);
                break;
            case "findVehicle":
                requireArgs(operation, args, 2);
                result = frontEnd.findVehicle(args[1], args[2]);
                break;
            default:
                throw new IllegalArgumentException("Unsupported client operation: " + operation);
        }

        System.out.println(result);
    }

    private static void requireArgs(String operation, String[] args, int requiredParamCount) {
        if (args.length != requiredParamCount + 1) {
            throw new IllegalArgumentException(
                    operation + " expects " + requiredParamCount + " argument(s), got "
                            + (args.length - 1) + ": " + Arrays.toString(args));
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  FrontEndClient addVehicle <managerId> <vehicleNumber> <vehicleType> <vehicleId> <price>");
        System.out.println("  FrontEndClient removeVehicle <managerId> <vehicleId>");
        System.out.println("  FrontEndClient listAvailableVehicles <managerId>");
        System.out.println("  FrontEndClient reserveVehicle <customerId> <vehicleId> <startDate> <endDate>");
        System.out.println("  FrontEndClient updateReservation <customerId> <vehicleId> <newStartDate> <newEndDate>");
        System.out.println("  FrontEndClient cancelReservation <customerId> <vehicleId>");
        System.out.println("  FrontEndClient findVehicle <customerId> <vehicleType>");
        System.out.println();
        System.out.println("JVM properties:");
        System.out.println("  -Dorb.host=<host>   CORBA name service host (default: localhost)");
        System.out.println("  -Dorb.port=<port>   CORBA name service port (default: 1050)");
    }
}
