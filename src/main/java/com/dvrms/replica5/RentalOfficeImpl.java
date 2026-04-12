import RentalOfficeApp.*;
import org.omg.CORBA.ORB;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;

import static java.util.logging.Logger.getLogger;


public class RentalOfficeImpl extends RentalOfficePOA {

    private final String officeID;

    /* ===================== DATA ===================== */

    // vehicleID -> Vehicle
    private final Map<String, Vehicle> vehicles;

    // vehicleID -> list of reservations
    private final Map<String, List<Reservation>> reservations;

    // vehicleID -> waiting queue
    private final Map<String, Queue<WaitingRequest>> waitingQueues;

    // customerID -> budget
    private final Map<String, Double> customerBudgets;

    // customerID -> (foreignOffice -> count)
    private final Map<String, Map<String, Integer>> foreignReservationsByOffice;
    
    // officeID -> UDP port
    private final Map<String, Integer> officePorts;

    // logger
    private static Logger logger;

    // ORB reference for remote calls
    private ORB orb;

    /* ===================== CONSTRUCTOR ===================== */

    public RentalOfficeImpl(String officeID) throws IOException {
        this.officeID = officeID;
        this.vehicles = new HashMap<>();
        this.reservations = new HashMap<>();
        this.waitingQueues = new HashMap<>();
        this.customerBudgets = new HashMap<>();
        this.foreignReservationsByOffice = new HashMap<>();
        this.officePorts = new HashMap<>();

        // Define UDP ports for each office
        this.officePorts.put("MTL", 5000);
        this.officePorts.put("WPG", 5001);
        this.officePorts.put("BNF", 5002);

        // Create logs directory if it doesn't exist
        Path logDir = Paths.get("logs", "servers");
        Files.createDirectories(logDir);

        logger = Logger.getLogger("RentalOfficeServer_" + officeID);
        logger.setUseParentHandlers(false);
        try {
            FileHandler fh = new FileHandler("logs/servers/RentalOfficeServer_" + officeID + ".log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);

        } catch (IOException e) {
            System.err.println("Could not create log file for office: " + officeID);
        }
    }

    public void setORB(ORB orb) {
        this.orb = orb;
    }

    /* ===================== RESERVE ===================== */

    @Override
    public synchronized String addVehicle(
            String managerID,
            String vehicleNumber,
            String vehicleType,
            String vehicleID,
            double price) {

        String msg;

        if (vehicleID.length() != 7) {
            msg = "Invalid vehicle ID format: " + vehicleID;

            logger.info(msg);

            return msg;
        }

        // Vehicle Number format check (e.g., ABC1234)
        if (!vehicleNumber.matches("[A-Z]{3}\\d{4}")) {
            msg = "Invalid vehicle number format (expected format: ABC1234): " + vehicleNumber;
            logger.info(msg);
            return msg;
        }

        // Check if vehicleID starts with officeID prefix
        if (!vehicleID.startsWith(officeID)) {
            msg = "Vehicle ID must start with office ID prefix (" + officeID + ").";
            logger.info(msg);
            return msg;
        }

        // Update existing vehicle
        if (vehicles.containsKey(vehicleID)) {
            Vehicle v = vehicles.get(vehicleID);
            v.setVehicleNumber(vehicleNumber);
            v.setVehicleType(vehicleType);
            v.setPrice(price);
            msg = "Vehicle with ID " + vehicleID + " already exists and was updated.";
            logger.info(msg);
            return msg;
        }


        // Add new vehicle
        Vehicle vehicle = new Vehicle(vehicleID, vehicleNumber, vehicleType, managerID, price);
        vehicles.put(vehicleID, vehicle);
        msg = "Vehicle with " + vehicleID + " added successfully by manager with " + managerID;
        logger.info(msg);
        return msg;
    }

    @Override
    public synchronized String removeVehicle(String managerID, String vehicleID) {

        StringBuilder msg = new StringBuilder();

        Vehicle vehicle = vehicles.get(vehicleID);

        if (!vehicles.containsKey(vehicleID)) {
            msg = new StringBuilder("Vehicle with ID " + vehicleID + " does not exist.");
            logger.info(msg.toString());
            return msg.toString();
        }

        double price = vehicle.getPrice();

        // Check if the manager trying to remove the vehicle is the one who added it
        if (!vehicle.getManagerID().equals(managerID)) {
            msg = new StringBuilder("You do not have permission to remove this vehicle. Only the manager who added it can remove it.");
            logger.info(msg.toString());
            return msg.toString();
        }

        // Check if vehicle is currently reserved
        List<Reservation> reservationsForVehicle = reservations.get(vehicleID);

        if (reservationsForVehicle != null) {

            for (Reservation r : reservationsForVehicle) {
                String customerID = r.getCustomerID();

                // Initialize the remaining budget variable to be used in both local and foreign cancellation scenarios
                double remainingBudget;

                // Check if customer is foreign
                String customerOffice = customerID.substring(0, 3);
                boolean isForeignCustomer = !customerOffice.equals(officeID);

                // Refund the customer
                if (isForeignCustomer) {
                    // Decrement foreign reservation counter locally
                    Map<String, Integer> officeMap =
                            foreignReservationsByOffice.get(customerID);

                    if (officeMap != null) {
                        officeMap.computeIfPresent(
                                officeID,
                                (k, v) -> Math.max(0, v - 1)
                        );

                        if (officeMap.getOrDefault(officeID, 0) == 0) {
                            officeMap.remove(officeID);
                        }

                        if (officeMap.isEmpty()) {
                            foreignReservationsByOffice.remove(customerID);
                        }
                    }

                    // Notify customer's home office via UDP
                    String homeOffice = customerID.substring(0, 3);
                    int port = officePorts.get(homeOffice);

                    try (DatagramSocket socket = new DatagramSocket()) {

                        String message = "FORCE_CANCEL;" +
                                customerID + "," +
                                vehicleID + "," +
                                price;

                        byte[] data = message.getBytes();
                        DatagramPacket packet = new DatagramPacket(
                                data,
                                data.length,
                                InetAddress.getByName("localhost"),
                                port
                        );

                        socket.send(packet);

                        // Receive response
                        byte[] buffer = new byte[4096];
                        DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                        socket.setSoTimeout(2000); // 2 seconds timeout
                        socket.receive(responsePacket);
                        String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                        msg.append(response);

                    } catch (Exception e) {
                        logger.warning(
                                "Failed to notify forced cancellation for " +
                                        customerID + ": " + e.getMessage()
                        );
                    }
                } else {
                    // Local customer refund
                    remainingBudget = customerBudgets.get(customerID);
                    if (customerBudgets.containsKey(customerID)) {
                        customerBudgets.put(customerID, remainingBudget + price);
                    } else {
                        logger.warning("Missing budget for customer " + customerID);
                    }

                    msg.append("Reservation for customer ").append(customerID).append(" cancelled and refunded $").append(price).append(".\n\n").
                            append("Cancellation details:\n").append("---------------------\n").append("Customer ID: ").append(customerID).append("\n").
                            append("Vehicle ID: ").append(vehicleID).append("\n").append("Refund Amount: $").append(price).append("\n").
                            append("Updated budget for customer: $").append(customerBudgets.get(customerID)).append("\n\n");
                }
            }
        }

        // Finally, remove the vehicle and associated data
        reservations.remove(vehicleID);
        waitingQueues.remove(vehicleID);
        vehicles.remove(vehicleID);

        msg.append("Vehicle with ID ").append(vehicleID).append(" removed successfully by manager with ").append(managerID).append(".\n\n");

        logger.info(msg.toString());
        return msg.toString();
    }

    @Override
    public synchronized String listAvailableVehicles(String managerID) {
        StringBuilder sb = new StringBuilder();

        // Adding a header
        sb.append(String.format("%-12s %-8s %-12s %-8s%n",
                "VehicleID", "Type", "Number", "Price"));

        // Aligning the header
        sb.append(String.format(
                "%-12s %-8s %-12s %-8s%n",
                "---------", "----", "------", "-----"
        ));

        for (Vehicle v : vehicles.values()) {
            if (v.isAvailable() && v.getManagerID().equals(managerID)) {
                sb.append(String.format(
                        "%-12s %-8s %-12s %-8.2f%n",
                        v.getVehicleID(),
                        v.getVehicleType(),
                        v.getVehicleNumber(),
                        v.getPrice()
                ));
            }
        }

        return sb.toString();
    }

    @Override
    public synchronized String reserveVehicle(
            String customerID,
            String vehicleID,
            String startDate,
            String endDate) {

        try {
            String msg;

        /* ========================
           VALIDATION
           ======================== */

            if (vehicleID == null || vehicleID.length() != 7 ||
                    !vehicleID.matches("[A-Z]{3}\\d{4}")) {

                msg = "Invalid vehicle ID format (expected: ABC1234): " + vehicleID;
                logger.info(msg);
                return msg;
            }

            if (startDate == null || endDate == null ||
                    !startDate.matches("\\d{8}") || !endDate.matches("\\d{8}")) {

                msg = "Invalid date format. Use ddmmyyyy.";
                logger.info(msg);
                return msg;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end = LocalDate.parse(endDate, formatter);

            if (start.isAfter(end)) {
                msg = "Error: Start date must be before end date.";
                logger.info(msg);
                return msg;
            }

            LocalDate today = LocalDate.now();
            if (start.isBefore(today) || end.isBefore(today)) {
                msg = "Error: Dates cannot be in the past.";
                logger.info(msg);
                return msg;
            }

            String officePrefix = vehicleID.substring(0, 3);

        /* ========================
           CUSTOMER BUDGET
           ======================== */

            // Initialize customer budget if not present (default $1000)
            customerBudgets.putIfAbsent(
                    customerID, 1000.0
            );

            double budgetAmount = customerBudgets.get(customerID);

            foreignReservationsByOffice
                    .putIfAbsent(customerID, new HashMap<>());

        /* ========================
           LOCAL RESERVATION
           ======================== */

            if (officePrefix.equals(officeID)) {

                Vehicle vehicle = vehicles.get(vehicleID);
                if (vehicle == null) {
                    msg = "Vehicle " + vehicleID + " does not exist.";
                    logger.info(msg);
                    return msg;
                }

                List<Reservation> list =
                        reservations.computeIfAbsent(vehicleID, k -> new ArrayList<>());

                for (Reservation r : list) {
                    if (r.getCustomerID().equals(customerID)) {
                        msg = "You already reserved this vehicle. Please update your existing reservation instead.";
                        logger.info(msg);
                        return msg;
                    }
                    if (r.overlapsWith(start, end)) {
                        msg = "OVERLAPPED: Vehicle already reserved.";
                        logger.info(msg);
                        return msg;
                    }
                }

                if (budgetAmount < vehicle.getPrice()) {
                    msg = "Insufficient budget. Current: $" + budgetAmount;
                    logger.info(msg);
                    return msg;
                }

                vehicle.reserve();
                list.add(new Reservation(customerID, vehicleID, start, end));
                customerBudgets.put(customerID, budgetAmount - vehicle.getPrice());

                msg =   "SUCCESS: Vehicle " + vehicleID + " reserved. \n\n" +
                        "Reservation details:\n" +
                        "---------------------\n" +
                        "Customer ID: " + customerID + "\n" +
                        "Vehicle ID: " + vehicleID + "\n" +
                        "Pay Amount: $" + vehicle.getPrice() + "\n" +
                        "Remaining budget: $" + customerBudgets.get(customerID);

                logger.info(msg);
                return msg;
            }

        /* ========================
           FOREIGN RESERVATION LIMIT
           ======================== */

            int port = officePorts.get(officePrefix);

            try (DatagramSocket socket = new DatagramSocket()) {

                socket.setSoTimeout(2000); // 2 seconds timeout

                String requestData = "RESERVE;" + customerID + "," + vehicleID + "," + startDate + "," + endDate + "," + budgetAmount;
                byte[] buffer = requestData.getBytes();

                InetAddress address = InetAddress.getByName("localhost");
                DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);
                socket.send(request);

                // Receive response
                byte[] responseBuffer = new byte[4096];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);

                String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                if (response.startsWith("SUCCESS")) {
                    // officeMap.put(officePrefix, officeMap.getOrDefault(officePrefix, 0) + 1);

                    // Looking for the remaining budget in the response to update local record
                    if (response.contains("Remaining budget: $")) {
                        String[] parts = response.split("Remaining budget: \\$");
                        if (parts.length > 1) {
                            try {
                                double remainingBudget = Double.parseDouble(parts[1].trim());
                                customerBudgets.put(customerID, remainingBudget);
                            } catch (NumberFormatException e) {
                                logger.warning("Could not parse remaining budget from response: " + response);
                            }
                        }
                    }
                }

                logger.info(response);
                return response;

            } catch (SocketTimeoutException e) {
                msg = "Timeout while trying to reserve vehicle " + vehicleID + " from office " + officePrefix;
                logger.warning(msg);
                return msg;
            }

        } catch (Exception e) {
            String msg = "Internal server error during reservation.";
            logger.severe(msg + " " + e.getMessage());
            return msg;
        }
    }

    public synchronized String remoteReserveVehicle(
            String customerID,
            String vehicleID,
            String startDate,
            String endDate,
            double currentBudget) {

        // This method is called by other offices to reserve a vehicle in this office
        // It should perform the same logic as reserveVehicle but without remote calls

        try {
            String msg;

        /* ========================
           VALIDATION
           ======================== */

            if (vehicleID == null || vehicleID.length() != 7 ||
                    !vehicleID.matches("[A-Z]{3}\\d{4}")) {

                msg = "Invalid vehicle ID format (expected: ABC1234): " + vehicleID;
                logger.info(msg);
                return msg;
            }

            if (startDate == null || endDate == null ||
                    !startDate.matches("\\d{8}") || !endDate.matches("\\d{8}")) {

                msg = "Invalid date format. Use ddmmyyyy.";
                logger.info(msg);
                return msg;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end = LocalDate.parse(endDate, formatter);

            if (start.isAfter(end)) {
                msg = "Error: Start date must be before end date.";
                logger.info(msg);
                return msg;
            }

            LocalDate today = LocalDate.now();
            if (start.isBefore(today) || end.isBefore(today)) {
                msg = "Error: Dates cannot be in the past.";
                logger.info(msg);
                return msg;
            }

            Vehicle vehicle = vehicles.get(vehicleID);
            if (vehicle == null) {
                msg = "Vehicle " + vehicleID + " does not exist.";
                logger.info(msg);
                return msg;
            }

            // Ensure that the customer has a record for foreign reservations
            foreignReservationsByOffice.putIfAbsent(customerID, new HashMap<>());

            // Check if the customer already has a reservation for this vehicle or if it overlaps with existing reservations
            if (foreignReservationsByOffice.get(customerID).getOrDefault(officeID, 0) >= 1) {
                msg = "RULE: Only one foreign reservation allowed per office.";
                logger.info(msg);
                return msg;
            }

            List<Reservation> list =
                    reservations.computeIfAbsent(vehicleID, k -> new ArrayList<>());

            for (Reservation r : list) {
                if (r.getCustomerID().equals(customerID)) {
                    msg = "You already reserved this vehicle. Please update your existing reservation instead.";
                    logger.info(msg);
                    return msg;
                }
                if (r.overlapsWith(start, end)) {
                    msg = "OVERLAPPED: Vehicle already reserved.";
                    logger.info(msg);
                    return msg;
                }
            }

            double price = vehicle.getPrice();

            if (currentBudget < price) {
                msg = "Insufficient budget. Current: $" + currentBudget;
                logger.info(msg);
                return msg;
            }

            vehicle.reserve();
            list.add(new Reservation(customerID, vehicleID, start, end));
            currentBudget -= price;
            foreignReservationsByOffice.get(customerID).put(officeID, foreignReservationsByOffice.get(customerID).getOrDefault(officeID, 0) + 1);

            msg =   "SUCCESS: Vehicle " + vehicleID + " reserved through remote office " + officeID + ".\n\n" +
                    "Reservation details:\n" +
                    "---------------------\n" +
                    "Customer ID: " + customerID + "\n" +
                    "Vehicle ID: " + vehicleID + "\n" +
                    "Pay Amount: $" + price + "\n" +
                    "Remaining budget: $" + currentBudget;
            logger.info(msg);
            return msg;
        } catch (Exception e) {
            String msg = "Internal server error during remote reservation.";
            logger.severe(msg + " " + e.getMessage());
            return msg;
        }
    }

    /* ===================== CANCEL ===================== */

    @Override
    public String cancelReservation(String customerID, String vehicleID) {

        try {
            String msg = "";
            boolean canAffordNextCustomer;

            if (vehicleID == null || vehicleID.length() < 3) {
                return "Invalid vehicle ID.";
            }

            String officePrefix = vehicleID.substring(0, 3);

        /* ========================
           LOCAL CANCELLATION
           ======================== */

            if (officePrefix.equals(officeID)) {

                List<Reservation> list = reservations.get(vehicleID);
                if (list == null || list.isEmpty()) {
                    return "No existing reservation found for vehicle " + vehicleID;
                }

                Reservation target = null;
                for (Reservation r : list) {
                    if (r.getCustomerID().equals(customerID)) {
                        target = r;
                        break;
                    }
                }

                WaitingRequest targetWaiting = null;
                Queue<WaitingRequest> searchQueue = waitingQueues.get(vehicleID);
                if (searchQueue != null) {
                    for (WaitingRequest wr : searchQueue) {
                        if (wr.getCustomerID().equals(customerID)) {
                            targetWaiting = wr;
                            break;
                        }
                    }
                }

                if (target == null && targetWaiting == null) {
                    return "No reservation/waiting request found for vehicle " + vehicleID +
                            " by customer " + customerID;
                }

                Vehicle vehicle = vehicles.get(vehicleID);
                double budget = customerBudgets.get(customerID);

                synchronized (vehicle) {

                    /* ==========================
                       CANCEL WAITING REQUEST
                       ======================== */
                    if (targetWaiting != null) {
                        searchQueue.remove(targetWaiting);
                        if (searchQueue.isEmpty()) {
                            waitingQueues.remove(vehicleID);
                        }

                        msg = "SUCCESS: Waiting request for vehicle " + vehicleID +
                                " cancelled by customer " + customerID + "\n\n" +
                                "Cancellation details:\n" +
                                "---------------------\n" +
                                "Customer ID: " + customerID + "\n" +
                                "Vehicle ID: " + vehicleID + "\n\n";
                        logger.info(msg);
                        return msg;
                    }

                    /* ========================
                       REMOVE RESERVATION
                       ======================== */

                    list.remove(target);
                    if (list.isEmpty()) {
                        reservations.remove(vehicleID);
                    }

                    vehicle.cancelReservation();
                    customerBudgets.put(customerID, budget + vehicle.getPrice());

                    /* ========================
                       AUTO-RESERVE FROM WAITLIST
                       ======================== */

                    Queue<WaitingRequest> queue = waitingQueues.get(vehicleID);

                    /* We need to loop through the waiting queue to
                       find the next customer that overlaps with the
                       canceled reservation period and can afford the vehicle,
                       and attempt to auto-reserve for them. If the next customer is a
                       foreign customer, we need to make a remote call to their office
                       to reserve for them. If the next customer is a local customer,
                       we can reserve for them directly. We should continue this
                       process until we find a suitable next customer or the
                       waiting queue is exhausted.
                    */
                    canAffordNextCustomer = false;

                    while (queue != null && !queue.isEmpty()) {

                        WaitingRequest nextCustomer = null;
                        while (!queue.isEmpty()) {
                            WaitingRequest wr = queue.poll();
                            if (wr.overlapsWith(
                                    target.getStartDate(),
                                    target.getEndDate())) {
                                nextCustomer = wr;
                                break;
                            }
                        }

                        if (nextCustomer != null) {

                            String customerOffice = nextCustomer.getCustomerID().substring(0, 3);
                            boolean isForeignCustomer = !customerOffice.equals(officeID);

                            double remainingBudget = 0.0;

                            if (isForeignCustomer) {

                                int port = officePorts.get(customerOffice);

                                try (DatagramSocket socket = new DatagramSocket()) {

                                    msg = "AUTO-RESERVE;" +
                                            nextCustomer.getCustomerID() + "," +
                                            vehicleID + "," +
                                            vehicle.getPrice();

                                    byte[] buf = msg.getBytes();
                                    InetAddress host = InetAddress.getByName("localhost");

                                    socket.send(new DatagramPacket(buf, buf.length, host, port));

                                    // Wait for acknowledgment (optional)
                                    byte[] ackBuffer = new byte[1024];
                                    DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                                    socket.setSoTimeout(2000); // 2 seconds timeout for acknowledgment
                                    socket.receive(ackPacket);

                                    String response = new String(ackPacket.getData(), 0, ackPacket.getLength());

                                    if (response.startsWith("SUCCESS")) {

                                        // Check if the waiting customer has already reached the foreign reservation limit for this office
                                        int currentForeignCount = foreignReservationsByOffice.get(nextCustomer.getCustomerID()).getOrDefault(officeID, 0);
                                        if (currentForeignCount >= 1) {
                                            logger.info("Waiting customer " + nextCustomer.getCustomerID() + " has already reached the foreign reservation limit for office " + officeID);
                                            continue;
                                        }

                                        // Update local reservation and budget records
                                        vehicle.reserve();
                                        reservations
                                                .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                                .add(new Reservation(
                                                        nextCustomer.getCustomerID(),
                                                        vehicleID,
                                                        nextCustomer.getStartDate(),
                                                        nextCustomer.getEndDate()
                                                ));
                                        canAffordNextCustomer = true;

                                        // Extract remaining budget from response to update local record
                                        if (response.contains("Remaining budget for waiting customer: $")) {
                                            String[] parts = response.split("Remaining budget for waiting customer: \\$");
                                            if (parts.length > 1) {
                                                try {
                                                    remainingBudget = Double.parseDouble(parts[1].trim());
                                                } catch (NumberFormatException e) {
                                                    logger.warning("Could not parse remaining budget from response: " + response);
                                                }
                                            }
                                        }

                                        // Update foreign reservation count
                                        foreignReservationsByOffice
                                                .computeIfAbsent(nextCustomer.getCustomerID(), k -> new HashMap<>())
                                                .compute(officePrefix, (k, v) -> (v == null) ? 1 : v + 1);
                                    }

                                    if (response.startsWith("FAILURE")) {
                                        // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                        logger.info("Auto-reservation failed for waiting customer " + nextCustomer.getCustomerID() + " due to response: " + response);
                                        continue;
                                    }

                                } catch (SocketTimeoutException e) {
                                    msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to timeout.";
                                    logger.warning(msg);
                                    return msg;
                                } catch (Exception e) {
                                    msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to error: " + e.getMessage();
                                    logger.warning(msg);
                                    return msg;
                                }
                            } else {

                                double nextBudget = customerBudgets.get(nextCustomer.getCustomerID());

                                if (nextBudget >= vehicle.getPrice()) {
                                    remainingBudget = nextBudget - vehicle.getPrice();
                                    customerBudgets.put(nextCustomer.getCustomerID(),
                                            remainingBudget);
                                } else {
                                    // If next customer can't afford, skip auto-reservation
                                    vehicle.cancelReservation();
                                    // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                    logger.info("Auto-reservation skipped for waiting customer " + nextCustomer.getCustomerID() + " due to insufficient budget.");
                                    continue;
                                }

                                vehicle.reserve();
                                reservations
                                        .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                        .add(new Reservation(
                                                nextCustomer.getCustomerID(),
                                                vehicleID,
                                                nextCustomer.getStartDate(),
                                                nextCustomer.getEndDate()
                                        ));
                                canAffordNextCustomer = true;
                            }

                            msg = "SUCCESS: Reservation cancelled and vehicle auto-reserved " +
                                    "for waiting customer " + nextCustomer.getCustomerID() + "\n\n" +
                                    "Auto-reservation details:\n" +
                                    "-------------------------\n" +
                                    "Customer ID: " + nextCustomer.getCustomerID() + "\n" +
                                    "Vehicle ID: " + vehicleID + "\n" +
                                    "Pay Amount: $" + vehicle.getPrice() + "\n" +
                                    "Remaining budget for waiting customer: $" + remainingBudget + "\n\n";

                            break; // Exit the loop after successfully auto-reserving for the next customer
                        }
                    }
                }

                if (!canAffordNextCustomer) {
                    msg = "SUCCESS: Reservation for vehicle " + vehicleID +
                            " cancelled by customer " + customerID + "\n\n" +
                            "Also, no auto-reservation is made either because there are no waiting customers or because waiting customers cannot afford the vehicle.\n\n" +
                            "Cancellation details:\n" +
                            "---------------------\n" +
                            "Customer ID: " + customerID + "\n" +
                            "Vehicle ID: " + vehicleID + "\n" +
                            "Refund Amount: $" + vehicle.getPrice() + "\n" +
                            "Updated budget: $" + customerBudgets.get(customerID);
                    logger.info(msg);
                    return msg;
                }

                msg +=  "SUCCESS: Reservation for vehicle " + vehicleID +
                        " cancelled by customer " + customerID + "\n\n" +
                        "Cancellation details:\n" +
                        "---------------------\n" +
                        "Customer ID: " + customerID + "\n" +
                        "Vehicle ID: " + vehicleID + "\n" +
                        "Refund Amount: $" + vehicle.getPrice() + "\n" +
                        "Updated budget: $" + customerBudgets.get(customerID);

                logger.info(msg);
                return msg;
            }

        /* ========================
           REMOTE CANCELLATION
           ======================== */

            int port = officePorts.get(officePrefix);

            try (DatagramSocket socket = new DatagramSocket()) {

                socket.setSoTimeout(2000); // 2 seconds timeout

                String requestData = "CANCEL;" + customerID + "," + vehicleID + "," + customerBudgets.get(customerID);
                byte[] buffer = requestData.getBytes();

                InetAddress address = InetAddress.getByName("localhost");
                DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);
                socket.send(request);

                // Receive response
                byte[] responseBuffer = new byte[4096];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);

                String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                if (response.startsWith("SUCCESS")) {
                    // Update local budget record if cancellation was successful
                    if (response.contains("Updated budget: $")) {
                        String[] parts = response.split("Updated budget: \\$");
                        if (parts.length > 1) {
                            try {
                                double updatedBudget = Double.parseDouble(parts[1].trim());
                                customerBudgets.put(customerID, updatedBudget);
                            } catch (NumberFormatException e) {
                                logger.warning("Could not parse updated budget from response: " + response);
                            }
                        }
                    }
                }

                logger.info(response);
                return response;

            } catch (SocketTimeoutException e) {
                msg = "Timeout while trying to cancel reservation for vehicle " + vehicleID + " from office " + officePrefix;
                logger.warning(msg);
                return msg;
            }

        } catch (Exception e) {
            logger.severe("Internal server error during cancellation: " + e.getMessage());
            return "Internal server error during cancellation.";
        }
    }

    public String remoteCancelReservation(String customerID, String vehicleID, double currentBudget) {
        // This method is called by other offices to cancel a reservation in this office
        // It should perform the same logic as cancelReservation but without remote calls
        String msg = "";
        boolean canAffordNextCustomer;

        try {

            if (vehicleID == null || vehicleID.length() < 3) {
                return "Invalid vehicle ID.";
            }

            String officePrefix = vehicleID.substring(0, 3);

            Vehicle vehicle = vehicles.get(vehicleID);
            if (vehicle == null) {
                return "Vehicle " + vehicleID + " does not exist.";
            }

            List<Reservation> list = reservations.get(vehicleID);
            if (list == null || list.isEmpty()) {
                return "No existing reservation found for vehicle " + vehicleID;
            }

            Reservation target = null;
            for (Reservation r : list) {
                if (r.getCustomerID().equals(customerID)) {
                    target = r;
                    break;
                }
            }

            WaitingRequest targetWaiting = null;
            Queue<WaitingRequest> searchQueue = waitingQueues.get(vehicleID);
            if (searchQueue != null) {
                for (WaitingRequest wr : searchQueue) {
                    if (wr.getCustomerID().equals(customerID)) {
                        targetWaiting = wr;
                        break;
                    }
                }
            }

            if (target == null && targetWaiting == null) {
                return "No reservation found for vehicle " + vehicleID +
                        " by customer " + customerID;
            }

            synchronized (vehicle) {

                /* ==========================
                   CANCEL WAITING REQUEST
                   ========================== */
                if (targetWaiting != null) {
                    searchQueue.remove(targetWaiting);
                    if (searchQueue.isEmpty()) {
                        waitingQueues.remove(vehicleID);
                    }

                    msg = "SUCCESS: Waiting request for vehicle " + vehicleID +
                            " cancelled by customer " + customerID + "\n\n" +
                            "Cancellation details:\n" +
                            "---------------------\n" +
                            "Customer ID: " + customerID + "\n" +
                            "Vehicle ID: " + vehicleID + "\n\n";
                    logger.info(msg);
                    return msg;
                }

                /* ========================
                   REMOVE RESERVATION
                   ======================== */

                list.remove(target);
                if (list.isEmpty()) {
                    reservations.remove(vehicleID);
                }

                vehicle.cancelReservation();
                currentBudget += vehicle.getPrice();

                // Ensure that the foreign reservation count is updated locally
                Map<String, Integer> officeMap =
                        foreignReservationsByOffice.get(customerID);
                if (officeMap != null) {
                    officeMap.computeIfPresent(
                            officeID,
                            (k, v) -> Math.max(0, v - 1)
                    );
                    if (officeMap.getOrDefault(officeID, 0) == 0) {
                        officeMap.remove(officeID);
                    }
                    if (officeMap.isEmpty()) {
                        foreignReservationsByOffice.remove(customerID);
                    }
                }

            /* ========================
               AUTO-RESERVE FROM WAITLIST
               ======================== */

                Queue<WaitingRequest> queue = waitingQueues.get(vehicleID);

                /* We need to loop through the waiting queue to
                   find the next customer that overlaps with the
                   canceled reservation period and can afford the vehicle,
                   and attempt to auto-reserve for them. If the next customer is a
                   foreign customer, we need to make a remote call to their office
                   to reserve for them. If the next customer is a local customer,
                   we can reserve for them directly. We should continue this
                   process until we find a suitable next customer or the
                   waiting queue is exhausted.
                 */
                canAffordNextCustomer = false;

                while (queue != null && !queue.isEmpty()) {

                    WaitingRequest nextCustomer = null;
                    while (!queue.isEmpty()) {
                        WaitingRequest wr = queue.poll();
                        if (wr.overlapsWith(
                                target.getStartDate(),
                                target.getEndDate())) {
                            nextCustomer = wr;
                            break;
                        }
                    }

                    if (nextCustomer != null) {

                        String customerOffice = nextCustomer.getCustomerID().substring(0, 3);
                        boolean isForeignCustomer = !customerOffice.equals(officePrefix);

                        double remainingBudget = 0.0;

                        if (isForeignCustomer) {

                            int port = officePorts.get(customerOffice);

                            try (DatagramSocket socket = new DatagramSocket()) {

                                msg = "AUTO-RESERVE;" +
                                        nextCustomer.getCustomerID() + "," +
                                        vehicleID + "," +
                                        vehicle.getPrice();

                                byte[] buf = msg.getBytes();
                                InetAddress host = InetAddress.getByName("localhost");

                                socket.send(new DatagramPacket(buf, buf.length, host, port));

                                // Wait for acknowledgment (optional)
                                byte[] ackBuffer = new byte[1024];
                                DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                                socket.setSoTimeout(2000); // 2 seconds timeout for acknowledgment
                                socket.receive(ackPacket);

                                String response = new String(ackPacket.getData(), 0, ackPacket.getLength());

                                if (response.startsWith("SUCCESS")) {

                                    // Check if the waiting customer has already reached the foreign reservation limit for this office before updating the count
                                    int currentForeignReservations = foreignReservationsByOffice
                                            .getOrDefault(nextCustomer.getCustomerID(), new HashMap<>()).getOrDefault(officePrefix, 0);
                                    if (currentForeignReservations >= 1) {
                                        logger.info("Waiting customer " + nextCustomer.getCustomerID() + " has already reached the foreign reservation limit for office " + officeID);
                                        continue;
                                    }

                                    // Update local reservation and budget records
                                    vehicle.reserve();
                                    reservations
                                            .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                            .add(new Reservation(
                                                    nextCustomer.getCustomerID(),
                                                    vehicleID,
                                                    nextCustomer.getStartDate(),
                                                    nextCustomer.getEndDate()
                                            ));
                                    canAffordNextCustomer = true;

                                    // Extract remaining budget from response to update local record
                                    if (response.contains("Remaining budget for waiting customer: $")) {
                                        String[] parts = response.split("Remaining budget for waiting customer: \\$");
                                        if (parts.length > 1) {
                                            try {
                                                remainingBudget = Double.parseDouble(parts[1].trim());
                                            } catch (NumberFormatException e) {
                                                logger.warning("Could not parse remaining budget from response: " + response);
                                            }
                                        }
                                    }

                                    // Update foreign reservation count
                                    foreignReservationsByOffice
                                            .computeIfAbsent(nextCustomer.getCustomerID(), k -> new HashMap<>())
                                            .compute(officePrefix, (k, v) -> (v == null) ? 1 : v + 1);

                                }

                                if (response.startsWith("FAILURE")) {
                                    // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                    logger.info("Auto-reservation failed for waiting customer " + nextCustomer.getCustomerID() + " due to response: " + response);
                                    continue;
                                }

                            } catch (SocketTimeoutException e) {
                                msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to timeout.";
                                logger.warning(msg);
                                return msg;
                            } catch (Exception e) {
                                msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to error: " + e.getMessage();
                                logger.warning(msg);
                                return msg;
                            }
                        } else {

                            double nextBudget = customerBudgets.get(nextCustomer.getCustomerID());

                            if (nextBudget >= vehicle.getPrice()) {
                                remainingBudget = nextBudget - vehicle.getPrice();
                                customerBudgets.put(nextCustomer.getCustomerID(),
                                        remainingBudget);
                            } else {
                                // If next customer can't afford, skip auto-reservation
                                vehicle.cancelReservation();
                                // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                logger.info("Auto-reservation skipped for waiting customer " + nextCustomer.getCustomerID() + " due to insufficient budget.");
                                continue;
                            }

                            vehicle.reserve();
                            reservations
                                    .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                    .add(new Reservation(
                                            nextCustomer.getCustomerID(),
                                            vehicleID,
                                            nextCustomer.getStartDate(),
                                            nextCustomer.getEndDate()
                                    ));
                            canAffordNextCustomer = true;
                        }

                        msg = "SUCCESS: Reservation cancelled and vehicle auto-reserved " +
                                "for waiting customer " + nextCustomer.getCustomerID() + "\n\n" +
                                "Auto-reservation details:\n" +
                                "-------------------------\n" +
                                "Customer ID: " + nextCustomer.getCustomerID() + "\n" +
                                "Vehicle ID: " + vehicleID + "\n" +
                                "Pay Amount: $" + vehicle.getPrice() + "\n" +
                                "Remaining budget for waiting customer: $" + remainingBudget + "\n\n";

                        break; // Exit the loop after successfully auto-reserving for the next customer
                    }
                }
            }

            if (!canAffordNextCustomer) {
                msg = "SUCCESS: Reservation for vehicle " + vehicleID +
                        " cancelled by customer " + customerID + "\n\n" +
                        "Also, no auto-reservation is made either because there are no waiting customers or because waiting customers cannot afford the vehicle.\n\n" +
                        "Cancellation details:\n" +
                        "---------------------\n" +
                        "Customer ID: " + customerID + "\n" +
                        "Vehicle ID: " + vehicleID + "\n" +
                        "Refund Amount: $" + vehicle.getPrice() + "\n" +
                        "Updated budget: $" + currentBudget;
                logger.info(msg);
                return msg;
            }

        /* ========================
           SUCCESS RESPONSE
           ======================== */

            msg +=  "SUCCESS: Reservation for vehicle " + vehicleID +
                    " cancelled by customer " + customerID + " through remote office " + officeID + "\n\n" +
                    "Cancellation details:\n" +
                    "---------------------\n" +
                    "Customer ID: " + customerID + "\n" +
                    "Vehicle ID: " + vehicleID + "\n" +
                    "Refund Amount: $" + vehicle.getPrice() + "\n" +
                    "Updated budget: $" + currentBudget;

            logger.info(msg);
            return msg;

        } catch (Exception e) {
            msg = "Internal server error during remote cancellation.";
            logger.severe(msg + " " + e.getMessage());
            return msg;
        }
    }

    @Override
    public String updateReservation(String customerID, String vehicleID, String newStartDate, String newEndDate) {

        try {
            String msg = "";
            boolean canAffordNextCustomer;

        /* ========================
           INPUT VALIDATION
           ======================== */

            if (vehicleID == null || !vehicleID.matches("[A-Z]{3}\\d{4}")) {
                msg = "Invalid vehicle ID format (expected format: ABC1234): " + vehicleID;
                logger.info(msg);
                return msg;
            }

            if (!newStartDate.matches("\\d{8}") || !newEndDate.matches("\\d{8}")) {
                msg = "Invalid date format. Please use ddmmyyyy format.";
                logger.info(msg);
                return msg;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
            LocalDate start = LocalDate.parse(newStartDate, formatter);
            LocalDate end   = LocalDate.parse(newEndDate, formatter);

            if (start.isAfter(end)) {
                msg = "Error: New start date must be before new end date.";
                logger.info(msg);
                return msg;
            }

            LocalDate today = LocalDate.now();
            if (start.isBefore(today) || end.isBefore(today)) {
                msg = "Error: Start and end dates cannot be in the past.";
                logger.info(msg);
                return msg;
            }

            String officePrefix = vehicleID.substring(0, 3);

        /* ========================
           LOCAL UPDATE
           ======================== */

            if (officePrefix.equals(officeID)) {

                List<Reservation> list = reservations.get(vehicleID);
                if (list == null || list.isEmpty()) {
                    msg = "No existing reservation found for vehicle " + vehicleID;
                    logger.info(msg);
                    return msg;
                }

                Reservation target = null;
                for (Reservation r : list) {
                    if (r.getCustomerID().equals(customerID)) {
                        target = r;
                        break;
                    }
                }

                if (target == null) {
                    msg = "No reservation found for vehicle " + vehicleID +
                            " by customer " + customerID;
                    logger.info(msg);
                    return msg;
                }

                // Overlap check (excluding current reservation)
                for (Reservation r : list) {
                    if (r != target && r.overlapsWith(start, end)) {
                        msg = "FAILURE TO UPDATE: Vehicle " + vehicleID +
                                " is already reserved from " +
                                r.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) +
                                " to " +
                                r.getEndDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                        logger.info(msg);
                        return msg;
                    }
                }

                Vehicle vehicle = vehicles.get(vehicleID);

                synchronized (vehicle) {
                    // Retrieving old reservation dates for waitlist processing after update
                    LocalDate oldStart = target.getStartDate();
                    LocalDate oldEnd = target.getEndDate();

                    target.updateDates(start, end);

                    // If the reservation update is successful, we should check if there
                    // are waiting customers that can now be auto-reserved due to the date change.
                    // This is because the new reservation period might overlap
                    // with different waiting requests compared to the original reservation period.

                    /* ========================
                       AUTO-RESERVE FROM WAITLIST
                       ======================== */

                    Queue<WaitingRequest> queue = waitingQueues.get(vehicleID);

                /* We need to loop through the waiting queue to
                   find the next customer that overlaps with the
                   canceled reservation period and can afford the vehicle,
                   and attempt to auto-reserve for them. If the next customer is a
                   foreign customer, we need to make a remote call to their office
                   to reserve for them. If the next customer is a local customer,
                   we can reserve for them directly. We should continue this
                   process until we find a suitable next customer or the
                   waiting queue is exhausted.
                 */
                    canAffordNextCustomer = false;

                    while (queue != null && !queue.isEmpty()) {

                        WaitingRequest nextCustomer = null;
                        while (!queue.isEmpty()) {
                            WaitingRequest wr = queue.poll();
                            // Check if it overlaps with old reservation period but does not overlap with new reservation period,
                            // which means this waiting request might now be eligible for auto-reservation due to the date change
                            if (wr.overlapsWith(oldStart, oldEnd) && !wr.overlapsWith(start, end)) {
                                nextCustomer = wr;
                                break;
                            }
                        }

                        if (nextCustomer != null) {

                            String customerOffice = nextCustomer.getCustomerID().substring(0, 3);
                            boolean isForeignCustomer = !customerOffice.equals(officeID);

                            double remainingBudget = 0.0;

                            if (isForeignCustomer) {

                                int port = officePorts.get(customerOffice);

                                try (DatagramSocket socket = new DatagramSocket()) {

                                    msg = "AUTO-RESERVE;" +
                                            nextCustomer.getCustomerID() + "," +
                                            vehicleID + "," +
                                            vehicle.getPrice();

                                    byte[] buf = msg.getBytes();
                                    InetAddress host = InetAddress.getByName("localhost");

                                    socket.send(new DatagramPacket(buf, buf.length, host, port));

                                    // Wait for acknowledgment (optional)
                                    byte[] ackBuffer = new byte[1024];
                                    DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                                    socket.setSoTimeout(2000); // 2 seconds timeout for acknowledgment
                                    socket.receive(ackPacket);

                                    String response = new String(ackPacket.getData(), 0, ackPacket.getLength());

                                    if (response.startsWith("SUCCESS")) {

                                        // Check if the waiting customer has already reached the foreign reservation limit for this office before updating the count
                                        int currentForeignReservations = foreignReservationsByOffice
                                                .getOrDefault(nextCustomer.getCustomerID(), new HashMap<>()).getOrDefault(officePrefix, 0);
                                        if (currentForeignReservations >= 1) {
                                            logger.info("Waiting customer " + nextCustomer.getCustomerID() + " has already reached the foreign reservation limit for office " + officeID);
                                            continue;
                                        }

                                        // Update local reservation and budget records
                                        vehicle.reserve();
                                        reservations
                                                .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                                .add(new Reservation(
                                                        nextCustomer.getCustomerID(),
                                                        vehicleID,
                                                        nextCustomer.getStartDate(),
                                                        nextCustomer.getEndDate()
                                                ));
                                        canAffordNextCustomer = true;

                                        // Extract remaining budget from response to update local record
                                        if (response.contains("Remaining budget for waiting customer: $")) {
                                            String[] parts = response.split("Remaining budget for waiting customer: \\$");
                                            if (parts.length > 1) {
                                                try {
                                                    remainingBudget = Double.parseDouble(parts[1].trim());
                                                } catch (NumberFormatException e) {
                                                    logger.warning("Could not parse remaining budget from response: " + response);
                                                }
                                            }
                                        }

                                        // Update foreign reservation count
                                        foreignReservationsByOffice
                                                .computeIfAbsent(nextCustomer.getCustomerID(), k -> new HashMap<>())
                                                .compute(officePrefix, (k, v) -> (v == null) ? 1 : v + 1);
                                    }

                                    if (response.startsWith("FAILURE")) {
                                        // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                        logger.info("Auto-reservation failed for waiting customer " + nextCustomer.getCustomerID() + " due to response: " + response);
                                        continue;
                                    }

                                } catch (SocketTimeoutException e) {
                                    msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to timeout.";
                                    logger.warning(msg);
                                    return msg;
                                } catch (Exception e) {
                                    msg = "Reservation cancelled but failed to auto-reserve for waiting customer " + nextCustomer.getCustomerID() + " due to error: " + e.getMessage();
                                    logger.warning(msg);
                                    return msg;
                                }
                            } else {

                                double nextBudget = customerBudgets.get(nextCustomer.getCustomerID());

                                if (nextBudget >= vehicle.getPrice()) {
                                    remainingBudget = nextBudget - vehicle.getPrice();
                                    customerBudgets.put(nextCustomer.getCustomerID(),
                                            remainingBudget);
                                } else {
                                    // If auto-reservation fails, we should continue to the next waiting customer instead of returning immediately
                                    logger.info("Auto-reservation skipped for waiting customer " + nextCustomer.getCustomerID() + " due to insufficient budget.");
                                    continue;
                                }

                                vehicle.reserve();
                                reservations
                                        .computeIfAbsent(vehicleID, k -> new ArrayList<>())
                                        .add(new Reservation(
                                                nextCustomer.getCustomerID(),
                                                vehicleID,
                                                nextCustomer.getStartDate(),
                                                nextCustomer.getEndDate()
                                        ));
                                canAffordNextCustomer = true;
                            }

                            msg = "SUCCESS: Reservation updated and vehicle auto-reserved " +
                                    "for waiting customer " + nextCustomer.getCustomerID() + "\n\n" +
                                    "Auto-reservation details:\n" +
                                    "-------------------------\n" +
                                    "Customer ID: " + nextCustomer.getCustomerID() + "\n" +
                                    "Vehicle ID: " + vehicleID + "\n" +
                                    "Pay Amount: $" + vehicle.getPrice() + "\n" +
                                    "Remaining budget for waiting customer: $" + remainingBudget + "\n\n";

                            break; // Exit the loop after successfully auto-reserving for the next customer
                        }
                    }
                }

                if (!canAffordNextCustomer) {
                    msg = "SUCCESS: Reservation for vehicle " + vehicleID +
                            " updated successfully by customer " + customerID + "\n\n" +
                            "However, no auto-reservation is made either because there are no waiting customers or because waiting customers cannot afford the vehicle.\n\n" +
                            "Updated reservation details:\n" +
                            "----------------------------\n" +
                            "Customer ID: " + customerID + "\n" +
                            "Vehicle ID: " + vehicleID + "\n" +
                            "New Start Date: " + start.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "\n" +
                            "New End Date: " + end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                    logger.info(msg);
                    return msg;
                }

                msg += "SUCCESS: Reservation for vehicle " + vehicleID +
                        " updated successfully by customer " + customerID + "\n\n" +
                        "Updated reservation details:\n" +
                        "----------------------------\n" +
                        "Customer ID: " + customerID + "\n" +
                        "Vehicle ID: " + vehicleID + "\n" +
                        "New Start Date: " + start.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "\n" +
                        "New End Date: " + end.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                logger.info(msg);
                return msg;
            }

        /* ========================
           REMOTE UPDATE
           ======================== */

            int port = officePorts.get(officePrefix);
            try (DatagramSocket socket = new DatagramSocket()) {

                socket.setSoTimeout(2000); // 2 seconds timeout

                String requestData = "UPDATE;" + customerID + "," + vehicleID + "," + newStartDate + "," + newEndDate;
                byte[] buffer = requestData.getBytes();

                InetAddress address = InetAddress.getByName("localhost");
                DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);
                socket.send(request);

                // Receive response
                byte[] responseBuffer = new byte[4096];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);

                return new String(responsePacket.getData(), 0, responsePacket.getLength());

            } catch (SocketTimeoutException e) {
                msg = "Timeout while trying to update reservation for vehicle " + vehicleID + " from office " + officePrefix;
                logger.warning(msg);
                return msg;
            }

        } catch (Exception e) {
            // 🚨 ABSOLUTE REQUIREMENT FOR CORBA
            logger.severe("Internal server error during updateReservation: " +
                    e.getMessage());
            return "Internal server error during reservation update.";
        }
    }

    @Override
    public synchronized String findVehicle(String customerID, String vehicleType) {

        StringBuilder sb = new StringBuilder();

        try {
        /* =========================
           HEADER
           ========================= */

            sb.append(String.format(
                    "%-12s %-8s %-12s %-8s %-12s%n",
                    "VehicleID", "Type", "Status", "Price", "Office"
            ));

            sb.append(String.format(
                    "%-12s %-8s %-12s %-8s %-12s%n",
                    "---------", "----", "------", "-----", "------"
            ));

        /* =========================
           LOCAL OFFICE SEARCH
           ========================= */

            for (Vehicle v : vehicles.values()) {
                if (v.getVehicleType().equalsIgnoreCase(vehicleType)) {
                    sb.append(String.format(
                            "%-12s %-8s %-12s %-8.2f %-12s%n",
                            v.getVehicleID(),
                            v.getVehicleType(),
                            v.isAvailable() ? "Available" : "Reserved",
                            v.getPrice(),
                            officeID
                    ));
                }
            }

        /* ====================
           REMOTE OFFICE SEARCH
           ==================== */

            // Search all vehicles of the same type in other offices
            for (Map.Entry<String, Integer> entry : officePorts.entrySet()) {
                String otherOfficeID = entry.getKey();
                int port = entry.getValue();

                if (otherOfficeID.equals(this.officeID)) continue;

                try (DatagramSocket socket = new DatagramSocket()) {

                    socket.setSoTimeout(2000); // 2 seconds timeout

                    String requestData = "FIND;" + vehicleType;

                    byte[] buffer = requestData.getBytes();

                    InetAddress address = InetAddress.getByName("localhost");
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);
                    socket.send(request);

                    // Receive response
                    byte[] responseBuffer = new byte[4096];
                    DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                    socket.receive(responsePacket);

                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                    // Only append lines with the vehicle type
                    String[] lines = response.split("\n");
                    for (String line : lines) {
                        if (line.trim().isEmpty() || line.startsWith("VehicleID")) continue;
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && parts[1].equalsIgnoreCase(vehicleType)) {
                            sb.append(line).append("\n");
                        }
                    }

                } catch (SocketTimeoutException e) {
                    getLogger("RentalOffice").warning("Timeout querying office " + otherOfficeID);
                }
                catch (Exception e) {
                    getLogger("RentalOffice").severe("Error querying office " + otherOfficeID + ": " + e.getMessage());
                }
            }


        } catch (Exception e) {
            logger.severe("findVehicle fatal error: " + e.getMessage());
            return "Internal server error while searching vehicles.";
        }
        return sb.toString();
    }

    public synchronized String getLocalVehiclesByType(String vehicleType) {
        StringBuilder sb = new StringBuilder();

        for (Vehicle v : vehicles.values()) {
            if (v.getVehicleType().equalsIgnoreCase(vehicleType)) {
                sb.append(String.format(
                        "%-12s %-8s %-12s %-8.2f %-12s%n",
                        v.getVehicleID(),
                        v.getVehicleType(),
                        v.isAvailable() ? "Available" : "Reserved",
                        v.getPrice(),
                        officeID
                ));
            }
        }
        return sb.toString();
    }

    @Override
    public synchronized String addToWaitingList(
            String customerID,
            String vehicleID,
            String startDate,
            String endDate) {

        String msg;

        try {
        /* =========================
           VALIDATION
           ========================= */

            if (vehicleID == null || !vehicleID.matches("[A-Z]{3}\\d{4}")) {
                msg = "Invalid vehicle ID format (expected format: ABC1234): " + vehicleID;
                logger.info(msg);
                return msg;
            }

            if (!startDate.matches("\\d{8}") || !endDate.matches("\\d{8}")) {
                msg = "Invalid date format. Please use ddmmyyyy.";
                logger.info(msg);
                return msg;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy");
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end   = LocalDate.parse(endDate, formatter);

            if (start.isAfter(end)) {
                msg = "Error: Start date must be before end date.";
                logger.info(msg);
                return msg;
            }

            String officePrefix = vehicleID.substring(0, 3);

        /* =========================
           LOCAL WAITLIST
           ========================= */

            if (officePrefix.equals(officeID)) {

                Vehicle vehicle = vehicles.get(vehicleID);
                if (vehicle == null) {
                    msg = "Vehicle with ID " + vehicleID + " does not exist.";
                    logger.info(msg);
                    return msg;
                }

                Queue<WaitingRequest> queue =
                        waitingQueues.computeIfAbsent(vehicleID, k -> new LinkedList<>());

                for (WaitingRequest r : queue) {
                    if (r.getCustomerID().equals(customerID)) {
                        msg = "You are already in the waiting list for vehicle " + vehicleID + ".";
                        logger.info(msg);
                        return msg;
                    }
                }

                queue.add(new WaitingRequest(customerID, start, end));

                msg = "WAITLISTED: Added to waiting list for vehicle " + vehicleID + ".";
                logger.info(msg);
                return msg;
            }

        /* =========================
           REMOTE WAITLIST (UDP)
           ========================= */

            int port = officePorts.get(officePrefix);

            try (DatagramSocket socket = new DatagramSocket()) {

                socket.setSoTimeout(2000); // 2 seconds timeout

                String requestData = "WAITLIST;" + customerID + "," + vehicleID + "," + startDate + "," + endDate;
                byte[] buffer = requestData.getBytes();

                InetAddress address = InetAddress.getByName("localhost");
                DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, port);
                socket.send(request);

                // Receive response
                byte[] responseBuffer = new byte[4096];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);

                return new String(responsePacket.getData(), 0, responsePacket.getLength());

            } catch (SocketTimeoutException e) {
                msg = "Timeout while trying to reserve vehicle " + vehicleID + " from office " + officePrefix;
                logger.warning(msg);
                return msg;
            }

        } catch (Exception e) {
            logger.severe("addToWaitingList error: " + e.getMessage());
            return "Internal server error while adding to waiting list.";
        }
    }

    // --- UDP HANDLERS ---
    public synchronized String handleUDPFindRequest(String message) {
        String[] parts = message.split(";");
        if (parts.length != 2) {
            return "Invalid find request format.";
        }

        String vehicleType = parts[1];
        return getLocalVehiclesByType(vehicleType);
    }

    public synchronized String handleUDPReservationRequest(String message) {

        String[] parts = message.split(";");

        try {
            // Message format: "RESERVE;customerID,vehicleID,startDate,endDate,currentBudget"
            if (parts.length < 2) {
                return "Invalid UDP reservation request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 5) {
                return "Invalid UDP reservation parameters.";
            }

            String customerID = params[0];
            String vehicleID = params[1];
            String startDate = params[2];
            String endDate = params[3];
            double currentBudget = Double.parseDouble(params[4]);

            return remoteReserveVehicle(customerID, vehicleID, startDate, endDate, currentBudget);

        } catch (Exception e) {
            logger.severe("UDP reservation error: " + e.getMessage());
            return "Internal UDP reservation error.";
        }
    }

    public synchronized String handleUDPWaitlistRequest(String message) {

        String[] parts = message.split(";");

        try {
            // Message format: "WAITLIST;customerID,vehicleID,startDate,endDate"
            if (parts.length < 2) {
                return "Invalid UDP waitlist request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 4) {
                return "Invalid UDP waitlist parameters.";
            }
            String customerID = params[0];
            String vehicleID = params[1];
            String startDate = params[2];
            String endDate = params[3];
            return addToWaitingList(customerID, vehicleID, startDate, endDate);
        } catch (Exception e) {
            return "UDP waitlist error.";
        }
    }

    public synchronized String handleUDPCancellationRequest(String message) {

        String[] parts = message.split(";");

        try {
            // Message format: "CANCEL;customerID,vehicleID,currentBudget"
            if (parts.length < 2) {
                return "Invalid UDP cancellation request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 3) {
                return "Invalid UDP cancellation parameters.";
            }
            String customerID = params[0];
            String vehicleID = params[1];
            double currentBudget = Double.parseDouble(params[2]);
            return remoteCancelReservation(customerID, vehicleID, currentBudget);

        } catch (Exception e) {
            return "UDP cancellation error.";
        }
    }

    public synchronized String handleUDPUpdateRequest(String message) {

        String[] parts = message.split(";");

        try {
            if (parts.length < 2) {
                return "Invalid UDP update request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 4) {
                return "Invalid UDP update parameters.";
            }
            String customerID = params[0];
            String vehicleID = params[1];
            String newStartDate = params[2];
            String newEndDate = params[3];
            return updateReservation(customerID, vehicleID, newStartDate, newEndDate);
        } catch (Exception e) {
            return "UDP update error.";
        }
    }

    public synchronized String handleUDPForceCancellation(String message) {

        String[] parts = message.split(";");

        try {
            // Message format: "FORCE_CANCEL;customerID,vehicleID,price"
            if (parts.length < 2) {
                return "Invalid UDP update request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 3) {
                return "Invalid UDP update parameters.";
            }
            String customerID = params[0];
            String vehicleID = params[1];
            double price = Double.parseDouble(params[2]);

            // Refunding the customer for the forced cancellation
            customerBudgets.put(customerID, customerBudgets.getOrDefault(customerID, 0.0) + price);
            return  "Reservation for customer " + customerID + " cancelled and refunded $" + price + ".\n\n" +
                    "Cancellation details:\n" +
                    "---------------------\n" +
                    "Customer ID: " + customerID + "\n" +
                    "Vehicle ID: " + vehicleID + "\n" +
                    "Refund Amount: $" + price + "\n" +
                    "Updated budget for customer: $" + customerBudgets.get(customerID) + "\n\n";

        } catch (Exception e) {
            return "UDP update error.";
        }
    }

    public synchronized String handleUDPAutoReservation(String message) {

        String[] parts = message.split(";");

        try {
            // Message format: "AUTO-RESERVE;customerID,vehicleID,price"
            if (parts.length < 2) {
                return  "Invalid auto reservation request format.";
            }
            String[] params = parts[1].split(",");
            if (params.length < 3) {
                return "Invalid auto reservation parameters.";
            }
            String customerID = params[0];
            String vehicleID = params[1];
            double price = Double.parseDouble(params[2]);

            // Ensure customer budget exists
            customerBudgets.computeIfAbsent(customerID, k -> 1000.0);

            // Check if customer has sufficient budget to reserve the vehicle
            if (customerBudgets.get(customerID) < price) {
                return "FAILURE: Cannot auto-reserve vehicle " + vehicleID + " for waiting customer " + customerID + " due to insufficient budget. Required: $" + price + ", Available: $" + customerBudgets.get(customerID);
            }

            // Deduct the price from customer's budget
            customerBudgets.put(customerID, customerBudgets.get(customerID) - price);

            return  "SUCCESS: Vehicle " + vehicleID + " auto-reserved for waiting customer " + customerID + "\n\n" +
                    "Auto-reservation details:\n" +
                    "-------------------------\n" +
                    "Customer ID: " + customerID + "\n" +
                    "Vehicle ID: " + vehicleID + "\n" +
                    "Pay Amount: $" + price + "\n" +
                    "Remaining budget for waiting customer: $" + customerBudgets.get(customerID) + "\n\n";

        } catch (Exception e) {
            return "UDP auto-reservation error.";
        }
    }
}