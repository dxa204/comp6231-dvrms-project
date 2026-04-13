package com.dvrms.frontend;

import FrontEndApp.FrontEnd;

import java.util.Locale;
import java.util.Scanner;

public class FrontEndCLI {

    public static void main(String[] args) throws Exception {
        FrontEnd frontEnd = FrontEndClient.connect(
                System.getProperty("orb.host", "localhost"),
                System.getProperty("orb.port", "1050")
        );

        try (Scanner scanner = new Scanner(System.in)) {
            run(scanner, frontEnd);
        }
    }

    private static void run(Scanner scanner, FrontEnd frontEnd) {
        while (true) {
            System.out.println();
            System.out.println("===== DVRMS Login =====");
            System.out.println("1) Manager login");
            System.out.println("2) Customer login");
            System.out.println("0) Exit");
            System.out.print("Choose an option: ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    handleManagerLogin(scanner, frontEnd);
                    break;
                case "2":
                    handleCustomerLogin(scanner, frontEnd);
                    break;
                case "0":
                    return;
                default:
                    System.out.println("Invalid option.");
            }
        }
    }

    private static void handleManagerLogin(Scanner scanner, FrontEnd frontEnd) {
        System.out.print("Enter manager ID: ");
        String managerId = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
        if (!isValidManagerId(managerId)) {
            System.out.println("Invalid manager ID. Expected format like MTLM1111, WPGM2222, BNFM3333.");
            return;
        }

        runManagerMenu(scanner, frontEnd, managerId);
    }

    private static void handleCustomerLogin(Scanner scanner, FrontEnd frontEnd) {
        System.out.print("Enter customer ID: ");
        String customerId = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
        if (!isValidCustomerId(customerId)) {
            System.out.println("Invalid customer ID. Expected format like MTLU1111, WPGU2222, BNFU3333.");
            return;
        }

        runCustomerMenu(scanner, frontEnd, customerId);
    }

    private static void runManagerMenu(Scanner scanner, FrontEnd frontEnd, String managerId) {
        while (true) {
            printManagerMenu();
            System.out.print("Enter choice: ");
            String choice = scanner.nextLine().trim();

            try {
                String result;
                switch (choice) {
                    case "1":
                        System.out.print("Enter vehicle number: ");
                        String vehicleNumber = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        System.out.print("Enter vehicle type (Sedan/SUV/Truck): ");
                        String vehicleType = scanner.nextLine().trim();
                        System.out.print("Enter vehicle ID: ");
                        String vehicleId = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        System.out.print("Enter price: ");
                        double price = Double.parseDouble(scanner.nextLine().trim());
                        result = frontEnd.addVehicle(managerId, vehicleNumber, vehicleType, vehicleId, price);
                        System.out.println(result);
                        break;
                    case "2":
                        System.out.print("Enter vehicle ID to remove: ");
                        result = frontEnd.removeVehicle(managerId, scanner.nextLine().trim().toUpperCase(Locale.ROOT));
                        System.out.println(result);
                        break;
                    case "3":
                        result = frontEnd.listAvailableVehicles(managerId);
                        System.out.println(result);
                        break;
                    case "0":
                        return;
                    default:
                        System.out.println("Invalid option.");
                }
            } catch (Exception e) {
                System.out.println("Operation failed: " + e.getMessage());
            }
        }
    }

    private static void runCustomerMenu(Scanner scanner, FrontEnd frontEnd, String customerId) {
        while (true) {
            printCustomerMenu();
            System.out.print("Enter choice: ");
            String choice = scanner.nextLine().trim();

            try {
                String result;
                switch (choice) {
                    case "1":
                        System.out.print("Enter vehicle type (Sedan/SUV/Truck): ");
                        result = frontEnd.findVehicle(customerId, scanner.nextLine().trim());
                        System.out.println(result);
                        break;
                    case "2":
                        System.out.print("Enter vehicle ID: ");
                        String reserveVehicleId = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        System.out.print("Enter start date (YYYY-MM-DD): ");
                        String reserveStart = scanner.nextLine().trim();
                        System.out.print("Enter end date (YYYY-MM-DD): ");
                        String reserveEnd = scanner.nextLine().trim();
                        result = frontEnd.reserveVehicle(customerId, reserveVehicleId, reserveStart, reserveEnd);
                        System.out.println(result);
                        break;
                    case "3":
                        System.out.print("Enter vehicle ID: ");
                        String updateVehicleId = scanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        System.out.print("Enter new start date (YYYY-MM-DD): ");
                        String updateStart = scanner.nextLine().trim();
                        System.out.print("Enter new end date (YYYY-MM-DD): ");
                        String updateEnd = scanner.nextLine().trim();
                        result = frontEnd.updateReservation(customerId, updateVehicleId, updateStart, updateEnd);
                        System.out.println(result);
                        break;
                    case "4":
                        System.out.print("Enter vehicle ID: ");
                        result = frontEnd.cancelReservation(customerId, scanner.nextLine().trim().toUpperCase(Locale.ROOT));
                        System.out.println(result);
                        break;
                    case "5":
                        System.out.println("displayCurrentBudget is not exposed by the current FrontEnd IDL.");
                        break;
                    case "6":
                        System.out.println("displayReservations is not exposed by the current FrontEnd IDL.");
                        break;
                    case "0":
                        return;
                    default:
                        System.out.println("Invalid option.");
                }
            } catch (Exception e) {
                System.out.println("Operation failed: " + e.getMessage());
            }
        }
    }

    private static boolean isValidManagerId(String id) {
        return id.matches("(MTL|WPG|BNF)M\\d{4}");
    }

    private static boolean isValidCustomerId(String id) {
        return id.matches("(MTL|WPG|BNF)U\\d{4}");
    }

    private static void printManagerMenu() {
        System.out.println();
        System.out.println("===== Manager Menu =====");
        System.out.println("1) addVehicle");
        System.out.println("2) removeVehicle");
        System.out.println("3) listAvailableVehicles");
        System.out.println("0) Exit");
    }

    private static void printCustomerMenu() {
        System.out.println();
        System.out.println("===== Customer Menu =====");
        System.out.println("1) findVehicle");
        System.out.println("2) reserveVehicle");
        System.out.println("3) updateReservation");
        System.out.println("4) cancelReservation");
        System.out.println("5) displayCurrentBudget");
        System.out.println("6) displayReservations");
        System.out.println("0) Exit");
    }
}
