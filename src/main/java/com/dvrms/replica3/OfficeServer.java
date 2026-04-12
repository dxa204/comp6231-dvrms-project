package com.dvrms.replica3;

import com.dvrms.common.VehicleRecord;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class OfficeServer {
    public enum Office {MTL, WPG, BNF}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter DISPLAY_DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int DEFAULT_BUDGET = 1000;
    private static final int UDP_TIMEOUT_MS = 1500;
    private static final Map<Office, Integer> REPLICA3_PORTS = createPortMap();

    private final Office office;
    private final int udpPort;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private Logger logger;
    private FileHandler fileHandler;

    private final ConcurrentHashMap<String, VehicleRecord> vehicles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReservationRecord> reservationDB = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> notifications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> budget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pendingWaitlistConfirm = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> customerReservedFromOffice =
            new ConcurrentHashMap<>();

    public OfficeServer(Office office, int udpPort) {
        this.office = office;
        this.udpPort = udpPort;
        initServerLogger();
    }

    private static Map<Office, Integer> createPortMap() {
        Map<Office, Integer> ports = new EnumMap<>(Office.class);
        ports.put(Office.MTL, 7101);
        ports.put(Office.WPG, 7102);
        ports.put(Office.BNF, 7103);
        return ports;
    }

    private static String resKey(String customerID, String vehicleID) {
        return customerID + "|" + vehicleID;
    }

    private static String pendingKey(String customerID, String vehicleID, String start, String end) {
        return customerID + "|" + vehicleID + "|" + start + "|" + end;
    }

    private ReentrantLock lockFor(String vehicleID) {
        return locks.computeIfAbsent(vehicleID, ignored -> new ReentrantLock());
    }

    private static String officeOfUser(String userID) {
        return userID.substring(0, 3);
    }

    private static String officeOfVehicle(String vehicleID) {
        return vehicleID.substring(0, 3);
    }

    private boolean isManager(String managerID) {
        return managerID != null && managerID.length() >= 4 && managerID.charAt(3) == 'M';
    }

    private boolean isCustomer(String customerID) {
        return customerID != null && customerID.length() >= 4 && customerID.charAt(3) == 'U';
    }

    private String managerOffice(String managerID) {
        return officeOfUser(managerID);
    }

    private int getBudget(String customerID) {
        return budget.computeIfAbsent(customerID, ignored -> DEFAULT_BUDGET);
    }

    private void setBudget(String customerID, int newBudget) {
        budget.put(customerID, newBudget);
    }

    private void incrementReservationCount(String customerID, String officeCode) {
        customerReservedFromOffice
                .computeIfAbsent(customerID, ignored -> new ConcurrentHashMap<>())
                .merge(officeCode, 1, Integer::sum);
    }

    private void decrementReservationCount(String customerID, String officeCode) {
        ConcurrentHashMap<String, Integer> counts =
                customerReservedFromOffice.computeIfAbsent(customerID, ignored -> new ConcurrentHashMap<>());
        counts.merge(officeCode, -1, Integer::sum);
        Integer updated = counts.get(officeCode);
        if (updated != null && updated <= 0) {
            counts.remove(officeCode);
        }
    }

    private int getTotalRemoteReservedCount(String customerID, String homeOffice) {
        Map<String, Integer> counts = customerReservedFromOffice.computeIfAbsent(
                customerID, ignored -> new ConcurrentHashMap<>());

        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!entry.getKey().equals(homeOffice)) {
                total += (entry.getValue() == null ? 0 : entry.getValue());
            }
        }
        return total;
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        } else {
            System.out.printf("[%s][%s] %s%n", LocalDateTime.now(), office, msg);
        }
    }

    private void initServerLogger() {
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);

            String filename = dir.resolve("R3-" + office.name() + "-server.log").toString();
            logger = Logger.getLogger("DVRMS-R3-" + office.name());
            logger.setUseParentHandlers(false);

            fileHandler = new FileHandler(filename, true);
            fileHandler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return String.format("%s [%s] [%s] %s%n",
                            LocalDateTime.now(),
                            office.name(),
                            Thread.currentThread().getName(),
                            record.getMessage());
                }
            });

            logger.addHandler(fileHandler);
        } catch (IOException e) {
            System.err.println("Failed to initialize logger for " + office.name() + ": " + e.getMessage());
        }
    }

    private LocalDate parseDateDDMMYYYY(String value) {
        return LocalDate.parse(value, DMY);
    }

    private String formatDate(LocalDate date) {
        return date.format(DISPLAY_DMY);
    }

    private boolean overlapsInclusive(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        return !(end1.isBefore(start2) || start1.isAfter(end2));
    }

    private boolean overlaps(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        return !aEnd.isBefore(bStart) && !bEnd.isBefore(aStart);
    }

    private boolean isAvailableForPeriod(String vehicleID, LocalDate requestedStart, LocalDate requestedEnd) {
        return isAvailableForPeriod(vehicleID, requestedStart, requestedEnd, null);
    }

    private boolean isAvailableForPeriod(String vehicleID,
                                         LocalDate requestedStart,
                                         LocalDate requestedEnd,
                                         String ignoreReservationKey) {
        for (Map.Entry<String, ReservationRecord> entry : reservationDB.entrySet()) {
            if (ignoreReservationKey != null && ignoreReservationKey.equals(entry.getKey())) {
                continue;
            }

            ReservationRecord record = entry.getValue();
            if (!record.vehicleID.equals(vehicleID)) {
                continue;
            }

            if (overlapsInclusive(requestedStart, requestedEnd, record.startDate, record.endDate)) {
                return false;
            }
        }
        return true;
    }

    private DateRange parseRange(String startDDMMYYYY, String endDDMMYYYY) {
        try {
            LocalDate start = parseDateDDMMYYYY(startDDMMYYYY);
            LocalDate end = parseDateDDMMYYYY(endDDMMYYYY);
            if (end.isBefore(start)) {
                return DateRange.error("ERROR: endDate before startDate.");
            }
            return DateRange.success(start, end);
        } catch (Exception e) {
            return DateRange.error("ERROR: Invalid date format. Use ddmmyyyy.");
        }
    }

    private void notifyCustomer(String customerID, String message) {
        notifications.computeIfAbsent(customerID, ignored -> new ArrayList<>()).add(message);
        log("NOTIFY customer=" + customerID + " msg=" + message);
    }

    private String requireHomeManager(String managerID) {
        if (!isManager(managerID)) {
            return "ERROR: Not a manager.";
        }
        if (!managerOffice(managerID).equals(office.name())) {
            return "ERROR: Call your HOME office server.";
        }
        return null;
    }

    private String requireHomeCustomer(String customerID) {
        if (!isCustomer(customerID)) {
            return "ERROR: Not a customer.";
        }
        if (!officeOfUser(customerID).equals(office.name())) {
            return "ERROR: Call your HOME office server.";
        }
        return null;
    }

    public String addVehicle(String managerID, int vehicleNumber, String vehicleType,
                             String vehicleID, int reservationPrice) {
        String accessError = requireHomeManager(managerID);
        if (accessError != null) return accessError;
        if (reservationPrice < 0) return "ERROR: reservationPrice must be >= 0.";
        if (vehicleID == null || vehicleID.length() < 3) return "ERROR: Invalid vehicleID.";
        if (!officeOfVehicle(vehicleID).equals(office.name())) {
            return "ERROR: vehicleID does not belong to this office server.";
        }

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord previous = vehicles.get(vehicleID);
            VehicleRecord replacement = new VehicleRecord(vehicleID, vehicleType, vehicleNumber, reservationPrice);
            if (previous != null) {
                replacement.waitlist.addAll(previous.waitlist);
            }
            vehicles.put(vehicleID, replacement);

            log((previous == null ? "addVehicle NEW" : "addVehicle UPDATE")
                    + " manager=" + managerID
                    + " vehicleID=" + vehicleID
                    + " type=" + vehicleType
                    + " price=" + reservationPrice);

            autoAssignWaitlist(vehicleID);
            return previous == null
                    ? "SUCCESS: vehicle added " + vehicleID
                    : "SUCCESS: vehicle updated " + vehicleID;
        } finally {
            lock.unlock();
        }
    }

    public String removeVehicle(String managerID, String vehicleID) {
        String accessError = requireHomeManager(managerID);
        if (accessError != null) return accessError;
        if (!officeOfVehicle(vehicleID).equals(office.name())) {
            return "ERROR: vehicleID does not belong to this office server.";
        }

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord removedVehicle = vehicles.remove(vehicleID);
            if (removedVehicle == null) {
                return "ERROR: vehicle does not exist.";
            }

            List<String> keysToDelete = new ArrayList<>();
            List<ReservationRecord> affectedReservations = new ArrayList<>();
            for (Map.Entry<String, ReservationRecord> entry : reservationDB.entrySet()) {
                ReservationRecord record = entry.getValue();
                if (record != null && vehicleID.equals(record.vehicleID)) {
                    keysToDelete.add(entry.getKey());
                    affectedReservations.add(record);
                }
            }

            int totalRefund = 0;
            for (String key : keysToDelete) {
                reservationDB.remove(key);
            }
            for (ReservationRecord record : affectedReservations) {
                totalRefund += record.totalPrice;
                sendRefundToHomeOffice(record.customerID, record.totalPrice, office.name());
                sendNotifyToHomeOffice(record.customerID,
                        "CANCELLED + REFUNDED: vehicle=" + vehicleID
                                + " refund=" + record.totalPrice
                                + " (removed by manager at " + office.name() + ")");
                log("removeVehicle CANCELLED reservation customer=" + record.customerID
                        + " vehicle=" + record.vehicleID
                        + " refund=" + record.totalPrice);
            }

            int waitlisted = removedVehicle.waitlist.size();
            removedVehicle.waitlist.clear();

            log("removeVehicle OK manager=" + managerID
                    + " vehicleID=" + vehicleID
                    + " cancelledReservations=" + affectedReservations.size()
                    + " clearedWaitlist=" + waitlisted
                    + " totalRefund=" + totalRefund);

            return "SUCCESS: removed " + vehicleID
                    + " cancelledReservations=" + affectedReservations.size()
                    + " clearedWaitlist=" + waitlisted
                    + " totalRefund=" + totalRefund;
        } finally {
            lock.unlock();
        }
    }

    private void sendRefundToHomeOffice(String customerID, int amount, String fromOffice) {
        String home = officeOfUser(customerID);
        if (home.equals(office.name())) {
            setBudget(customerID, getBudget(customerID) + amount);
            decrementReservationCount(customerID, fromOffice);
            log("REFUND LOCAL customer=" + customerID + " amount=" + amount + " fromOffice=" + fromOffice);
            return;
        }

        String reply = udpRequest(home, "REFUND|" + customerID + "|" + amount + "|" + fromOffice);
        log("REFUND SENT toHome=" + home + " customer=" + customerID + " amount=" + amount
                + " fromOffice=" + fromOffice + " reply=" + reply);
    }

    private void sendNotifyToHomeOffice(String customerID, String message) {
        String home = officeOfUser(customerID);
        if (home.equals(office.name())) {
            notifyCustomer(customerID, message);
            return;
        }
        udpRequest(home, "NOTIFY|" + customerID + "|" + message.replace("|", "/"));
    }

    public String listAvailableVehicle(String managerID) {
        String accessError = requireHomeManager(managerID);
        if (accessError != null) return accessError;

        StringBuilder out = new StringBuilder();
        out.append("Vehicle Availability for today (").append(LocalDate.now()).append(")\n");
        for (VehicleRecord vehicle : vehicles.values()) {
            boolean available = reservedCountForToday(vehicle.vehicleID) == 0;
            out.append(vehicle.vehicleID)
                    .append(" type=").append(vehicle.vehicleType)
                    .append(" price=").append(vehicle.reservationPrice)
                    .append(" statusToday=").append(available ? "AVAILABLE" : "RESERVED")
                    .append(" waitlist=").append(vehicle.waitlist.size())
                    .append("\n");
        }
        return out.toString();
    }

    private String consumeNotifications(String customerID) {
        List<String> messages = notifications.remove(customerID);
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder("=== Notifications ===\n");
        for (String message : messages) {
            out.append(message).append("\n");
        }
        out.append("=====================\n");
        return out.toString();
    }

    public String displayNotifications(String customerID) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;
        return consumeNotifications(customerID);
    }

    public String reserveVehicle(String customerID, String vehicleID, String startDDMMYYYY, String endDDMMYYYY) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        DateRange range = parseRange(startDDMMYYYY, endDDMMYYYY);
        if (!range.ok) return range.error;

        String homeOffice = officeOfUser(customerID);
        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(homeOffice) && getTotalRemoteReservedCount(customerID, homeOffice) >= 1) {
            return "ERROR: You can only reserve ONE vehicle outside your home office (" + homeOffice + ").";
        }

        if (!targetOffice.equals(office.name())) {
            return reserveRemote(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY, targetOffice);
        }

        String localReply = reserveLocal(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY, true);
        if (!localReply.startsWith("SUCCESS|")) {
            return localReply;
        }

        int price = Integer.parseInt(localReply.split("\\|")[1]);
        int remaining = getBudget(customerID);
        log("reserveVehicle LOCAL ok vehicle=" + vehicleID + " cost=" + price + " remainingBudget=" + remaining);
        return "SUCCESS: reserved " + vehicleID + " cost=" + price + " remainingBudget=" + remaining;
    }

    private String reserveRemote(String customerID, String vehicleID,
                                 String startDDMMYYYY, String endDDMMYYYY,
                                 String targetOffice) {
        String reply = udpRequest(targetOffice,
                "RESERVE|" + customerID + "|" + vehicleID + "|" + startDDMMYYYY + "|" + endDDMMYYYY);
        if (!reply.startsWith("SUCCESS|")) {
            return reply;
        }

        int price = Integer.parseInt(reply.split("\\|")[1]);
        int currentBudget = getBudget(customerID);
        if (currentBudget < price) {
            udpRequest(targetOffice, "CANCEL|" + customerID + "|" + vehicleID);
            return "ERROR: Over budget (need " + price + ", have " + currentBudget + ")";
        }

        setBudget(customerID, currentBudget - price);
        incrementReservationCount(customerID, targetOffice);

        log("reserveVehicle REMOTE ok vehicle=" + vehicleID + " cost=" + price
                + " remainingBudget=" + (currentBudget - price));
        return "SUCCESS: reserved " + vehicleID + " cost=" + price
                + " remainingBudget=" + (currentBudget - price);
    }

    private String reserveLocal(String customerID, String vehicleID,
                                String startDDMMYYYY, String endDDMMYYYY,
                                boolean deductBudget) {
        LocalDate start = parseDateDDMMYYYY(startDDMMYYYY);
        LocalDate end = parseDateDDMMYYYY(endDDMMYYYY);

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord vehicle = vehicles.get(vehicleID);
            if (vehicle == null) {
                return "ERROR: vehicle not found.";
            }

            if (!isAvailableForPeriod(vehicleID, start, end)) {
                return placeOnWaitlistIfConfirmed(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY, start, end, vehicle);
            }

            int price = vehicle.reservationPrice;
            if (deductBudget) {
                int currentBudget = getBudget(customerID);
                if (currentBudget < price) {
                    return "ERROR: Over budget.";
                }
                setBudget(customerID, currentBudget - price);
                incrementReservationCount(customerID, office.name());
            }

            reservationDB.put(resKey(customerID, vehicleID),
                    new ReservationRecord(customerID, vehicleID, price, start, end));

            log("reserveVehicle OK " + customerID + " " + vehicleID + " "
                    + startDDMMYYYY + "-" + endDDMMYYYY + " cost=" + price);
            return "SUCCESS|" + price;
        } finally {
            lock.unlock();
        }
    }

    private String placeOnWaitlistIfConfirmed(String customerID, String vehicleID,
                                              String startDDMMYYYY, String endDDMMYYYY,
                                              LocalDate start, LocalDate end,
                                              VehicleRecord vehicle) {
        String waitlistKey = pendingKey(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY);
        if (!pendingWaitlistConfirm.containsKey(waitlistKey)) {
            pendingWaitlistConfirm.put(waitlistKey, System.currentTimeMillis());
            log("reserveVehicle UNAVAILABLE " + customerID + " " + vehicleID + " "
                    + startDDMMYYYY + "-" + endDDMMYYYY);
            return "UNAVAILABLE: Vehicle not available for that period. Call reserveVehicle again with same inputs to join waitlist.";
        }

        pendingWaitlistConfirm.remove(waitlistKey);
        vehicle.waitlist.addLast(new VehicleRecord.WaitlistEntry(customerID, start, end));

        log("reserveVehicle WAITLISTED " + customerID + " " + vehicleID + " "
                + startDDMMYYYY + "-" + endDDMMYYYY);
        log("WAITLIST ENQUEUE customer=" + customerID
                + " vehicle=" + vehicleID
                + " start=" + startDDMMYYYY
                + " end=" + endDDMMYYYY
                + " waitlistSizeNow=" + vehicle.waitlist.size());

        return "WAITLISTED: " + vehicleID + " for " + startDDMMYYYY + "-" + endDDMMYYYY;
    }

    public String updateReservation(String customerID, String vehicleID,
                                    String newStartDDMMYYYY, String newEndDDMMYYYY) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) {
            String reply = udpRequest(targetOffice,
                    "UPDATE|" + customerID + "|" + vehicleID + "|" + newStartDDMMYYYY + "|" + newEndDDMMYYYY);

            if (reply.startsWith("SUCCESS|")) {
                int price = Integer.parseInt(reply.split("\\|")[1]);
                int currentBudget = getBudget(customerID);
                if (currentBudget < price) {
                    udpRequest(targetOffice, "CANCEL|" + customerID + "|" + vehicleID);
                    return "ERROR: Over budget (need " + price + ", have " + currentBudget + ")";
                }

                setBudget(customerID, currentBudget - price);
                incrementReservationCount(customerID, targetOffice);
                log("updateReservation REMOTE waitlist->reserved " + customerID + " " + vehicleID + " cost=" + price);
                return "SUCCESS: updated " + vehicleID;
            }

            if (reply.startsWith("SUCCESS")) {
                log("updateReservation REMOTE ok " + customerID + " " + vehicleID);
            }
            return reply;
        }

        return updateLocal(customerID, vehicleID, newStartDDMMYYYY, newEndDDMMYYYY, true);
    }

    private String updateLocal(String customerID, String vehicleID,
                               String newStartDDMMYYYY, String newEndDDMMYYYY,
                               boolean deductBudgetIfWaitlistAssigned) {
        DateRange range = parseRange(newStartDDMMYYYY, newEndDDMMYYYY);
        if (!range.ok) {
            return range.error.equals("ERROR: Invalid date format. Use ddmmyyyy.")
                    ? "ERROR: Invalid date format ddmmyyyy."
                    : range.error;
        }

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord vehicle = vehicles.get(vehicleID);
            if (vehicle == null) {
                return "ERROR: vehicle not found.";
            }

            String key = resKey(customerID, vehicleID);
            ReservationRecord existing = reservationDB.get(key);
            if (existing != null) {
                if (!isAvailableForPeriod(vehicleID, range.start, range.end, key)) {
                    return "ERROR: Vehicle not available for updated period.";
                }

                reservationDB.put(key,
                        new ReservationRecord(customerID, vehicleID, existing.totalPrice, range.start, range.end));
                log("updateReservation OK " + customerID + " " + vehicleID + " "
                        + newStartDDMMYYYY + "-" + newEndDDMMYYYY);
                autoAssignWaitlist(vehicleID);
                return "SUCCESS: updated " + vehicleID;
            }

            VehicleRecord.WaitlistEntry entry = findWaitlistEntry(vehicle, customerID);
            if (entry == null) {
                return "ERROR: reservation not found for this user and vehicle.";
            }
            if (!isAvailableForPeriod(vehicleID, range.start, range.end)) {
                return "ERROR: Vehicle not available for updated period.";
            }

            int price = vehicle.reservationPrice;
            if (deductBudgetIfWaitlistAssigned) {
                int currentBudget = getBudget(customerID);
                if (currentBudget < price) {
                    return "ERROR: Over budget.";
                }
                setBudget(customerID, currentBudget - price);
                incrementReservationCount(customerID, office.name());
            }

            vehicle.waitlist.remove(entry);
            reservationDB.put(key, new ReservationRecord(customerID, vehicleID, price, range.start, range.end));

            log("updateReservation WAITLIST->RESERVED " + customerID + " " + vehicleID + " "
                    + newStartDDMMYYYY + "-" + newEndDDMMYYYY + " cost=" + price);
            return deductBudgetIfWaitlistAssigned ? "SUCCESS: updated " + vehicleID : ("SUCCESS|" + price);
        } finally {
            lock.unlock();
        }
    }

    private VehicleRecord.WaitlistEntry findWaitlistEntry(VehicleRecord vehicle, String customerID) {
        for (VehicleRecord.WaitlistEntry entry : vehicle.waitlist) {
            if (entry.customerID.equals(customerID)) {
                return entry;
            }
        }
        return null;
    }

    public String cancelReservation(String customerID, String vehicleID) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) {
            String reply = udpRequest(targetOffice, "CANCEL|" + customerID + "|" + vehicleID);
            if (!reply.startsWith("SUCCESS|")) {
                return reply;
            }

            int refund = Integer.parseInt(reply.split("\\|")[1]);
            if (refund > 0) {
                setBudget(customerID, getBudget(customerID) + refund);
                decrementReservationCount(customerID, targetOffice);
            }

            log("cancelReservation REMOTE ok " + customerID + " " + vehicleID + " refund=" + refund);
            return refund > 0
                    ? "SUCCESS: cancelled " + vehicleID + " refund=" + refund
                    : "SUCCESS: cancelled waitlist for " + vehicleID;
        }

        return cancelLocal(customerID, vehicleID, true);
    }

    public String displayCurrentBudget(String customerID) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        int currentBudget = getBudget(customerID);
        log("displayCurrentBudget customer=" + customerID + " budget=" + currentBudget);
        return "Current budget: " + currentBudget;
    }

    public String displayReservations(String customerID) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        StringBuilder out = new StringBuilder();
        out.append(listReservationsLocal(customerID));

        for (Office other : Office.values()) {
            if (other == office) continue;
            String reply = udpRequest(other.name(), "LISTRES|" + customerID);
            if (reply != null && !reply.trim().isEmpty()) {
                out.append(reply);
            }
        }

        String result = out.toString().trim();
        log("displayReservations customer=" + customerID + " combinedLen=" + result.length());
        return result.isEmpty() ? "No active reservations." : (result + "\n");
    }

    private String listReservationsLocal(String customerID) {
        StringBuilder out = new StringBuilder();
        for (ReservationRecord record : reservationDB.values()) {
            if (record.customerID.equals(customerID)) {
                out.append(record.vehicleID).append(" from ")
                        .append(formatDate(record.startDate)).append(" to ")
                        .append(formatDate(record.endDate)).append("\n");
            }
        }
        return out.toString();
    }

    private String cancelLocal(String customerID, String vehicleID, boolean adjustBudgetAtHome) {
        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            ReservationRecord active = reservationDB.remove(resKey(customerID, vehicleID));
            if (active != null) {
                int refund = active.totalPrice;
                if (adjustBudgetAtHome) {
                    setBudget(customerID, getBudget(customerID) + refund);
                    decrementReservationCount(customerID, office.name());
                }

                log("cancelReservation OK " + customerID + " " + vehicleID + " refund=" + refund);
                autoAssignWaitlist(vehicleID);
                return "SUCCESS|" + refund;
            }

            VehicleRecord vehicle = vehicles.get(vehicleID);
            if (vehicle == null) {
                return "ERROR: vehicle not found.";
            }

            boolean removedFromWaitlist = false;
            for (Iterator<VehicleRecord.WaitlistEntry> it = vehicle.waitlist.iterator(); it.hasNext(); ) {
                VehicleRecord.WaitlistEntry entry = it.next();
                if (entry.customerID.equals(customerID)) {
                    it.remove();
                    removedFromWaitlist = true;
                }
            }

            if (removedFromWaitlist) {
                log("cancelReservation WAITLIST OK " + customerID + " " + vehicleID);
                return "SUCCESS|0";
            }

            return "ERROR: no reservation found.";
        } finally {
            lock.unlock();
        }
    }

    private int reservedCountForToday(String vehicleID) {
        LocalDate today = LocalDate.now();
        int count = 0;
        for (ReservationRecord record : reservationDB.values()) {
            if (!record.vehicleID.equals(vehicleID)) continue;
            if (overlaps(record.startDate, record.endDate, today, today)) {
                count++;
            }
        }
        return count;
    }

    private String availabilityStatusToday(VehicleRecord vehicle) {
        return reservedCountForToday(vehicle.vehicleID) == 0 ? "Available" : "Reserved";
    }

    public String findVehicle(String customerID, String vehicleType) {
        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        StringBuilder out = new StringBuilder(findLocal(vehicleType));
        for (Office other : Office.values()) {
            if (other == office) continue;
            out.append(udpRequest(other.name(), "FIND|" + vehicleType));
        }

        log("findVehicle " + vehicleType);
        return out.toString();
    }

    private String findLocal(String vehicleType) {
        StringBuilder out = new StringBuilder();
        for (VehicleRecord vehicle : vehicles.values()) {
            if (!vehicle.vehicleType.equalsIgnoreCase(vehicleType)) continue;
            out.append(vehicle.vehicleID).append(" ")
                    .append(vehicle.vehicleType).append(" ")
                    .append(availabilityStatusToday(vehicle)).append(" ")
                    .append(vehicle.reservationPrice).append("\n");
        }
        return out.toString();
    }

    private void autoAssignWaitlist(String vehicleID) {
        VehicleRecord vehicle = vehicles.get(vehicleID);
        if (vehicle == null) {
            return;
        }

        while (tryAssignOneWaitlistedCustomer(vehicleID, vehicle)) {
            // keep scanning until no more assignments can be made
        }
    }

    private boolean tryAssignOneWaitlistedCustomer(String vehicleID, VehicleRecord vehicle) {
        for (Iterator<VehicleRecord.WaitlistEntry> it = vehicle.waitlist.iterator(); it.hasNext(); ) {
            VehicleRecord.WaitlistEntry entry = it.next();
            if (!isAvailableForPeriod(vehicleID, entry.start, entry.end)) {
                continue;
            }

            String customerID = entry.customerID;
            String homeOffice = officeOfUser(customerID);
            int price = vehicle.reservationPrice;
            if (!approveWaitlistAssignment(customerID, homeOffice, office.name(), price, vehicleID)) {
                continue;
            }

            it.remove();
            reservationDB.put(resKey(customerID, vehicleID),
                    new ReservationRecord(customerID, vehicleID, price, entry.start, entry.end));

            String message = "WAITLIST ASSIGNED: vehicle=" + vehicleID
                    + " from " + formatDate(entry.start)
                    + " to " + formatDate(entry.end)
                    + " cost=" + price;
            if (homeOffice.equals(office.name())) {
                notifyCustomer(customerID, message);
            } else {
                udpRequest(homeOffice, "NOTIFY|" + customerID + "|" + message);
            }

            log("autoAssignWaitlist OK " + customerID + " " + vehicleID + " "
                    + entry.start + "-" + entry.end);
            return true;
        }
        return false;
    }

    private boolean approveWaitlistAssignment(String customerID,
                                              String homeOffice,
                                              String targetOffice,
                                              int price,
                                              String vehicleID) {
        if (homeOffice.equals(targetOffice)) {
            int currentBudget = getBudget(customerID);
            if (currentBudget < price) {
                log("autoAssignWaitlist SKIP_OVER_BUDGET " + customerID + " " + vehicleID);
                return false;
            }
            setBudget(customerID, currentBudget - price);
            incrementReservationCount(customerID, targetOffice);
            return true;
        }

        String reply = udpRequest(homeOffice,
                "WAITLIST_ASSIGN|" + customerID + "|" + targetOffice + "|" + price);
        if (reply != null && reply.startsWith("SUCCESS")) {
            return true;
        }

        log("autoAssignWaitlist REMOTE_DENIED " + customerID + " " + vehicleID + " reply=" + reply);
        return false;
    }

    public void startUdpListener() {
        pool.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket(udpPort)) {
                log("UDP listening on port " + udpPort);
                byte[] buf = new byte[2048];

                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket requestPacket = new DatagramPacket(buf, buf.length);
                    socket.receive(requestPacket);

                    String message = new String(requestPacket.getData(), 0, requestPacket.getLength(), StandardCharsets.UTF_8);
                    String reply = handleUdp(message);

                    byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket responsePacket = new DatagramPacket(out, out.length,
                            requestPacket.getAddress(), requestPacket.getPort());
                    socket.send(responsePacket);
                }
            } catch (IOException e) {
                log("UDP error: " + e.getMessage());
            }
        });
    }

    private String handleUdp(String msg) {
        String[] parts = msg.split("\\|");
        String op = parts[0];

        switch (op) {
            case "FIND":
                return findLocal(parts[1]);
            case "RESERVE":
                return reserveLocal(parts[1], parts[2], parts[3], parts[4], false);
            case "UPDATE":
                return updateLocal(parts[1], parts[2], parts[3], parts[4], false);
            case "CANCEL":
                return cancelLocal(parts[1], parts[2], false);
            case "REFUND":
                return applyRefund(parts[1], Integer.parseInt(parts[2]), parts[3]);
            case "LISTRES":
                return listReservationsLocal(parts[1]);
            case "NOTIFY":
                notifyCustomer(parts[1], parts[2]);
                return "SUCCESS";
            case "WAITLIST_ASSIGN":
                return approveRemoteWaitlistAssignment(parts[1], parts[2], Integer.parseInt(parts[3]));
            default:
                return "ERROR: unknown UDP op";
        }
    }

    private String applyRefund(String customerID, int amount, String fromOffice) {
        setBudget(customerID, getBudget(customerID) + amount);
        decrementReservationCount(customerID, fromOffice);
        log("REFUND APPLIED customer=" + customerID + " amount=" + amount + " fromOffice=" + fromOffice);
        return "SUCCESS";
    }

    private String approveRemoteWaitlistAssignment(String customerID, String fromOffice, int price) {
        String homeOffice = officeOfUser(customerID);
        if (!fromOffice.equals(homeOffice) && getTotalRemoteReservedCount(customerID, homeOffice) >= 1) {
            return "ERROR: You can only reserve ONE vehicle outside your home office (" + homeOffice + ").";
        }

        int currentBudget = getBudget(customerID);
        if (currentBudget < price) {
            return "ERROR: Over budget.";
        }

        setBudget(customerID, currentBudget - price);
        incrementReservationCount(customerID, fromOffice);

        log("WAITLIST_ASSIGN APPROVED customer=" + customerID
                + " fromOffice=" + fromOffice
                + " price=" + price
                + " remainingBudget=" + (currentBudget - price));
        return "SUCCESS";
    }

    private String udpRequest(String officeCode, String payload) {
        Office target = Office.valueOf(officeCode);
        int port = REPLICA3_PORTS.get(target);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(UDP_TIMEOUT_MS);
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(out, out.length, InetAddress.getLocalHost(), port);
            socket.send(request);

            byte[] in = new byte[4096];
            DatagramPacket response = new DatagramPacket(in, in.length);
            socket.receive(response);
            return new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ERROR: UDP failed to " + officeCode + " (" + e.getMessage() + ")";
        }
    }

    public void seed(Map<String, VehicleRecord> initial) {
        vehicles.putAll(initial);
    }

    public void shutdown() {
        pool.shutdownNow();
        if (fileHandler != null) {
            fileHandler.close();
        }
    }

    private static final class DateRange {
        private final boolean ok;
        private final String error;
        private final LocalDate start;
        private final LocalDate end;

        private DateRange(boolean ok, String error, LocalDate start, LocalDate end) {
            this.ok = ok;
            this.error = error;
            this.start = start;
            this.end = end;
        }

        private static DateRange success(LocalDate start, LocalDate end) {
            return new DateRange(true, null, start, end);
        }

        private static DateRange error(String message) {
            return new DateRange(false, message, null, null);
        }
    }
}
