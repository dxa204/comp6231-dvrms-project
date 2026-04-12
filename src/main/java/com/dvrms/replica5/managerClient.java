package com.dvrms.replica5;

import org.omg.CORBA.ORB;
import org.omg.CosNaming.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

import RentalOfficeApp.*;

public class managerClient {

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

            // ---------- MANAGER ID ----------
            System.out.print("Enter your Manager ID: ");
            String managerID = sc.nextLine().trim().toUpperCase();

            if (!managerID.startsWith(officeCode) ||
                    managerID.charAt(3) != 'M' ||
                    managerID.length() != 8 ||
                    !managerID.substring(4).matches("\\d{4}")) {

                System.out.println("Invalid Manager ID. Exiting.");
                return;
            }

            // ---------- LOGGING ----------
            Path logDir = Paths.get("logs", "managers");
            Files.createDirectories(logDir);

            Logger logger = Logger.getLogger("ManagerClient");
            logger.setUseParentHandlers(false);

            FileHandler fh = new FileHandler(
                    "logs/managers/Manager_" + managerID + ".log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);

            logger.info("Manager logged in: " + managerID + " (" + location + ")");

            // ---------- MENU LOOP ----------
            boolean exit = false;

            while (!exit) {

                System.out.println("\nChoose an option:");
                System.out.println("1) Add Vehicle");
                System.out.println("2) Remove Vehicle");
                System.out.println("3) List Vehicle Availability");
                System.out.println("4) Exit");
                System.out.println();
                System.out.print("Enter choice: ");

                String choice = sc.nextLine().trim();
                System.out.println();

                switch (choice) {

                    case "1": {
                        System.out.print("Enter vehicle ID: ");
                        String vehicleID = sc.nextLine().trim().toUpperCase();

                        System.out.print("Enter vehicle number (license plate): ");
                        String vehicleNumber = sc.nextLine().trim().toUpperCase();

                        System.out.print("Enter vehicle type (Sedan, SUV, Truck): ");
                        String vehicleType = sc.nextLine().trim();

                        System.out.print("Enter reservation price: ");
                        double price =
                                Double.parseDouble(sc.nextLine().trim());

                        System.out.println();

                        logger.info("Add vehicle: " + vehicleID);

                        System.out.println(
                                office.addVehicle(
                                        managerID,
                                        vehicleNumber,
                                        vehicleType,
                                        vehicleID,
                                        price
                                )
                        );
                        break;
                    }

                    case "2": {
                        System.out.print("Enter vehicle ID to remove: ");
                        String removeID = sc.nextLine().trim().toUpperCase();

                        System.out.println();

                        logger.info("Remove vehicle: " + removeID);

                        System.out.println(
                                office.removeVehicle(managerID, removeID)
                        );
                        break;
                    }

                    case "3": {
                        logger.info("List available vehicles");

                        System.out.println(
                                office.listAvailableVehicles(managerID)
                        );
                        break;
                    }

                    case "4":
                        exit = true;
                        logger.info("Manager exiting: " + managerID);
                        System.out.println("Exiting Manager Client. Goodbye!");
                        break;

                    default:
                        System.out.println("Invalid choice.");
                }
            }

            sc.close();

        } catch (Exception e) {
            System.err.println("Manager client error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

