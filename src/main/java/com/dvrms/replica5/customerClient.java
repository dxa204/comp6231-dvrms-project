import org.omg.CORBA.ORB;
import org.omg.CosNaming.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

import RentalOfficeApp.*;

public class customerClient {

    public static void main(String[] args) {

        try {
            Scanner sc = new Scanner(System.in);

            // ---------- ORB INIT ----------
            ORB orb = ORB.init(args, null);

            // ---------- OFFICE SELECTION ----------
            System.out.print("Enter office server to connect (MTL / WPG / BNF): ");
            String officeCode = sc.nextLine().trim().toUpperCase();

            if (!officeCode.equals("MTL") &&
                    !officeCode.equals("WPG") &&
                    !officeCode.equals("BNF")) {

                System.out.println("Invalid office server. Exiting.");
                return;
            }

            String location =
                    officeCode.equals("MTL") ? "Montreal" :
                            officeCode.equals("WPG") ? "Winnipeg" : "Banff";

            System.out.println("Connecting to Rental Office Server in " + location + "...");

            // ---------- NAMING LOOKUP ----------
            org.omg.CORBA.Object objRef =
                    orb.resolve_initial_references("NameService");

            NamingContextExt ncRef =
                    NamingContextExtHelper.narrow(objRef);

            RentalOffice office =
                    RentalOfficeHelper.narrow(
                            ncRef.resolve_str("RentalOffice_" + officeCode)
                    );

            System.out.println("Connected successfully.");

            // ---------- CUSTOMER ID ----------
            System.out.print("Enter your Customer ID: ");
            String customerID = sc.nextLine().trim().toUpperCase();

            if (!customerID.startsWith(officeCode) ||
                    customerID.charAt(3) != 'U' ||
                    customerID.length() != 8 ||
                    !customerID.substring(4).matches("\\d{4}")) {

                System.out.println("Invalid Customer ID. Exiting.");
                return;
            }

            // ---------- LOGGING ----------
            Path logDir = Paths.get("logs", "customers");
            Files.createDirectories(logDir);

            Logger logger = Logger.getLogger("CustomerClient");
            logger.setUseParentHandlers(false);

            FileHandler fh = new FileHandler(
                    "logs/customers/Customer_" + customerID + ".log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);

            logger.info("Customer logged in: " + customerID + " (" + location + ")");

            // ---------- MENU LOOP ----------
            boolean exit = false;

            while (!exit) {

                System.out.println("\nChoose an option:");
                System.out.println("1) Reserve Vehicle");
                System.out.println("2) Update Reservation");
                System.out.println("3) Cancel Reservation");
                System.out.println("4) Find Available Vehicles");
                System.out.println("5) Exit");
                System.out.println();
                System.out.print("Enter choice: ");

                String choice = sc.nextLine().trim();
                System.out.println();

                switch (choice) {

                    case "1": {
                        System.out.print("Enter vehicleID: ");
                        String vehicleID = sc.nextLine().trim().toUpperCase();

                        System.out.print("Enter start date (ddmmyyyy): ");
                        String startDate = sc.nextLine().trim();

                        System.out.print("Enter end date (ddmmyyyy): ");
                        String endDate = sc.nextLine().trim();

                        System.out.println();

                        logger.info("Reserve " + vehicleID +
                                " from " + startDate + " to " + endDate);

                        String res =
                                office.reserveVehicle(
                                        customerID, vehicleID, startDate, endDate);

                        System.out.println(res);

                        if (res.startsWith("OVERLAPPED")) {
                            System.out.print(
                                    "Join waiting list? (yes/no or y/n): ");
                            String ans =
                                    sc.nextLine().trim().toLowerCase();

                            while (!ans.matches("yes|no|y|n")) {
                                System.out.print("Invalid input. Try again: ");
                                ans = sc.nextLine().trim().toLowerCase();
                            }

                            if (ans.equals("y")) ans = "yes";
                            if (ans.equals("n")) ans = "no";

                            if (ans.equals("yes")) {
                                logger.info("Joined waiting list for " + vehicleID);
                                System.out.println(
                                        office.addToWaitingList(
                                                customerID, vehicleID, startDate, endDate));
                            } else {
                                logger.info("Declined waiting list for " + vehicleID);
                                System.out.println("Waiting list declined.");
                            }
                        }
                        break;
                    }

                    case "2": {
                        System.out.print("Enter vehicleID: ");
                        String vehicleID = sc.nextLine().trim().toUpperCase();

                        System.out.print("Enter new start date (ddmmyyyy): ");
                        String ns = sc.nextLine().trim();

                        System.out.print("Enter new end date (ddmmyyyy): ");
                        String ne = sc.nextLine().trim();

                        System.out.println();

                        logger.info("Update reservation for " + vehicleID);
                        System.out.println(
                                office.updateReservation(customerID, vehicleID, ns, ne));
                        break;
                    }

                    case "3": {
                        System.out.print("Enter vehicleID to cancel: ");
                        String vid = sc.nextLine().trim().toUpperCase();

                        System.out.println();

                        logger.info("Cancel reservation " + vid);
                        System.out.println(
                                office.cancelReservation(customerID, vid));
                        break;
                    }

                    case "4": {
                        System.out.print("Enter vehicle type (Sedan/SUV/Truck): ");
                        String type = sc.nextLine().trim();

                        System.out.println();

                        logger.info("Find vehicles: " + type);
                        System.out.println(
                                office.findVehicle(customerID, type));
                        break;
                    }

                    case "5":
                        exit = true;
                        logger.info("Customer exiting: " + customerID);
                        System.out.println("Goodbye!");
                        break;

                    default:
                        System.out.println("Invalid choice.");
                }
            }

            sc.close();

        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
