package com.dvrms.replica1;

import com.dvrms.common.VehicleRecord;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    private final Office office;
    private final int udpPort;
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

    private final ExecutorService pool = Executors.newCachedThreadPool();

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter DISPLAY_DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public OfficeServer(Office office, int udpPort) {
        this.office = office;
        this.udpPort = udpPort;
        initServerLogger();
    }

    private static String resKey(String customerID, String vehicleID) {
        return customerID + "|" + vehicleID;
    }

    private static String pendingKey(String customerID, String vehicleID, String start, String end) {
        return customerID + "|" + vehicleID + "|" + start + "|" + end;
    }

    private ReentrantLock lockFor(String vehicleID) {
        return locks.computeIfAbsent(vehicleID, k -> new ReentrantLock());
    }

    private static String officeOfUser(String userID) {
        return userID.substring(0, 3);
    }

    private static String officeOfVehicle(String vehicleID) {
        return vehicleID.substring(0, 3);
    }

    private LocalDate parseDateDDMMYYYY(String s) {
        return LocalDate.parse(s, DMY);
    }

    private String formatDate(LocalDate date) {
        return date.format(DISPLAY_DMY);
    }

    private int getBudget(String customerID) {
        return budget.computeIfAbsent(customerID, k -> 1000);
    }

    private void setBudget(String customerID, int newBudget) {
        budget.put(customerID, newBudget);
    }

    private void incReservedCountFromOffice(String customerID, String officeCode) {
        customerReservedFromOffice.putIfAbsent(customerID, new ConcurrentHashMap<String, Integer>());
        customerReservedFromOffice.get(customerID).merge(officeCode, 1, Integer::sum);
    }

    private void decReservedCountFromOffice(String customerID, String officeCode) {
        customerReservedFromOffice.putIfAbsent(customerID, new ConcurrentHashMap<String, Integer>());
        customerReservedFromOffice.get(customerID).merge(officeCode, -1, Integer::sum);
        Integer updated = customerReservedFromOffice.get(customerID).get(officeCode);
        if (updated != null && updated <= 0) {
            customerReservedFromOffice.get(customerID).remove(officeCode);
        }
    }

    private int getTotalRemoteReservedCount(String customerID, String homeOffice) {
        customerReservedFromOffice.putIfAbsent(customerID, new ConcurrentHashMap<String, Integer>());

        int total = 0;
        for (Map.Entry<String, Integer> e : customerReservedFromOffice.get(customerID).entrySet()) {
            if (!e.getKey().equals(homeOffice)) {
                total += e.getValue() == null ? 0 : e.getValue();
            }
        }
        return total;
    }

    private void notifyCustomer(String customerID, String message) {
        notifications.computeIfAbsent(customerID, k -> new ArrayList<String>()).add(message);
        log("NOTIFY customer=" + customerID + " msg=" + message);
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
        for (Map.Entry<String, ReservationRecord> e : reservationDB.entrySet()) {
            String key = e.getKey();
            ReservationRecord r = e.getValue();
            if (ignoreReservationKey != null && ignoreReservationKey.equals(key)) {
                continue;
            }
            if (!r.vehicleID.equals(vehicleID)) continue;
            if (overlapsInclusive(requestedStart, requestedEnd, r.startDate, r.endDate)) {
                return false;
            }
        }
        return true;
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

            String filename = dir.resolve("R1-" + office.name() + "-server.log").toString();
            logger = Logger.getLogger("DVRMS-R1-" + office.name());
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

    private boolean isManager(String managerID) {
        return managerID != null && managerID.length() >= 4 && managerID.charAt(3) == 'M';
    }

    private String managerOffice(String managerID) {
        return officeOfUser(managerID);
    }

    public String addVehicle(String managerID, int vehicleNumber, String vehicleType,
                             String vehicleID, int reservationPrice) {
        if (!isManager(managerID)) return "ERROR: Not a manager.";
        String homeOffice = managerOffice(managerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";
        if (reservationPrice < 0) return "ERROR: reservationPrice must be >= 0.";
        if (vehicleID == null || vehicleID.length() < 3) return "ERROR: Invalid vehicleID.";

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) return "ERROR: vehicleID does not belong to this office server.";

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord existing = vehicles.get(vehicleID);

            if (existing == null) {
                VehicleRecord rec = new VehicleRecord(vehicleID, vehicleType, vehicleNumber, reservationPrice);
                vehicles.put(vehicleID, rec);
                log("addVehicle NEW manager=" + managerID + " vehicleID=" + vehicleID
                        + " type=" + vehicleType + " price=" + reservationPrice);
                autoAssignWaitlist(vehicleID);
                return "SUCCESS: vehicle added " + vehicleID;
            }

            VehicleRecord updated = new VehicleRecord(vehicleID, vehicleType, vehicleNumber, reservationPrice);
            updated.waitlist.addAll(existing.waitlist);
            vehicles.put(vehicleID, updated);
            log("addVehicle UPDATE manager=" + managerID + " vehicleID=" + vehicleID
                    + " type=" + vehicleType + " price=" + reservationPrice);
            autoAssignWaitlist(vehicleID);
            return "SUCCESS: vehicle updated " + vehicleID;
        } finally {
            lock.unlock();
        }
    }

    public String removeVehicle(String managerID, String vehicleID) {
        if (!isManager(managerID)) return "ERROR: Not a manager.";
        String homeOffice = managerOffice(managerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) return "ERROR: vehicleID does not belong to this office server.";

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord rec = vehicles.remove(vehicleID);
            if (rec == null) return "ERROR: vehicle does not exist.";

            int cancelled = 0;
            int totalRefund = 0;

            Iterator<Map.Entry<String, ReservationRecord>> it = reservationDB.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ReservationRecord> entry = it.next();
                ReservationRecord rr = entry.getValue();
                if (rr != null && rr.vehicleID.equals(vehicleID)) {
                    it.remove();
                    cancelled++;
                    int refund = rr.totalPrice;
                    totalRefund += refund;
                    sendRefundToHomeOffice(rr.customerID, refund, office.name());
                    String note = "CANCELLED + REFUNDED: vehicle=" + vehicleID
                            + " refund=" + refund
                            + " (removed by manager at " + office.name() + ")";
                    sendNotifyToHomeOffice(rr.customerID, note);
                    log("removeVehicle CANCELLED reservation customer=" + rr.customerID
                            + " vehicle=" + rr.vehicleID + " refund=" + refund);
                }
            }

            int waitlisted = rec.waitlist.size();
            rec.waitlist.clear();

            log("removeVehicle OK manager=" + managerID
                    + " vehicleID=" + vehicleID
                    + " cancelledReservations=" + cancelled
                    + " clearedWaitlist=" + waitlisted
                    + " totalRefund=" + totalRefund);

            return "SUCCESS: removed " + vehicleID
                    + " cancelledReservations=" + cancelled
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
            decReservedCountFromOffice(customerID, fromOffice);
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
        String safeMsg = message.replace("|", "/");
        udpRequest(home, "NOTIFY|" + customerID + "|" + safeMsg);
    }

    public String listAvailableVehicle(String managerID) {
        if (!isManager(managerID)) return "ERROR: Not a manager.";
        String homeOffice = managerOffice(managerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        StringBuilder sb = new StringBuilder();
        sb.append("Vehicle Availability for today (").append(LocalDate.now()).append(")\n");
        for (VehicleRecord rec : vehicles.values()) {
            String vehicleID = rec.vehicleID;
            int reservedNow = reservedCountForToday(vehicleID);
            boolean availableToday = reservedNow == 0;
            sb.append(vehicleID)
                    .append(" type=").append(rec.vehicleType)
                    .append(" price=").append(rec.reservationPrice)
                    .append(" statusToday=").append(availableToday ? "AVAILABLE" : "RESERVED")
                    .append(" waitlist=").append(rec.waitlist.size())
                    .append("\n");
        }
        return sb.toString();
    }

    private String consumeNotifications(String customerID) {
        List<String> msgs = notifications.remove(customerID);
        if (msgs == null || msgs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== Notifications ===\n");
        for (String m : msgs) {
            sb.append(m).append("\n");
        }
        sb.append("=====================\n");
        return sb.toString();
    }

    public String displayNotifications(String customerID) {
        if (!officeOfUser(customerID).equals(office.name())) {
            return "ERROR: Call your HOME office server.";
        }
        return consumeNotifications(customerID);
    }

    public String reserveVehicle(String customerID, String vehicleID, String startDDMMYYYY, String endDDMMYYYY) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        LocalDate start;
        LocalDate end;
        try {
            start = parseDateDDMMYYYY(startDDMMYYYY);
            end = parseDateDDMMYYYY(endDDMMYYYY);
        } catch (Exception e) {
            return "ERROR: Invalid date format. Use ddmmyyyy.";
        }
        if (end.isBefore(start)) return "ERROR: endDate before startDate.";

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(homeOffice) && getTotalRemoteReservedCount(customerID, homeOffice) >= 1) {
            return "ERROR: You can only reserve ONE vehicle outside your home office (" + homeOffice + ").";
        }

        if (!targetOffice.equals(office.name())) {
            String udpReply = udpRequest(targetOffice,
                    "RESERVE|" + customerID + "|" + vehicleID + "|" + startDDMMYYYY + "|" + endDDMMYYYY);
            if (udpReply.startsWith("SUCCESS|")) {
                int price = Integer.parseInt(udpReply.split("\\|")[1]);
                int cur = getBudget(customerID);
                if (cur < price) return "ERROR: Over budget (need " + price + ", have " + cur + ")";
                setBudget(customerID, cur - price);
                incReservedCountFromOffice(customerID, targetOffice);
                log("reserveVehicle REMOTE ok vehicle=" + vehicleID + " cost=" + price + " remainingBudget=" + (cur - price));
                return "SUCCESS: reserved " + vehicleID + " cost=" + price + " remainingBudget=" + (cur - price);
            }
            return udpReply;
        }

        String localReply = reserveLocal(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY, true);
        if (localReply.startsWith("SUCCESS|")) {
            int price = Integer.parseInt(localReply.split("\\|")[1]);
            int remaining = getBudget(customerID);
            log("reserveVehicle LOCAL ok vehicle=" + vehicleID + " cost=" + price + " remainingBudget=" + remaining);
            return "SUCCESS: reserved " + vehicleID + " cost=" + price + " remainingBudget=" + remaining;
        }
        return localReply;
    }

    private String reserveLocal(String customerID, String vehicleID,
                                String startDDMMYYYY, String endDDMMYYYY,
                                boolean deductBudget) {
        LocalDate start = parseDateDDMMYYYY(startDDMMYYYY);
        LocalDate end = parseDateDDMMYYYY(endDDMMYYYY);

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord rec = vehicles.get(vehicleID);
            if (rec == null) return "ERROR: No current reservation or waitlist for this vehicle.";

            if (!isAvailableForPeriod(vehicleID, start, end)) {
                String pKey = pendingKey(customerID, vehicleID, startDDMMYYYY, endDDMMYYYY);
                if (!pendingWaitlistConfirm.containsKey(pKey)) {
                    pendingWaitlistConfirm.put(pKey, System.currentTimeMillis());
                    log("reserveVehicle UNAVAILABLE " + customerID + " " + vehicleID + " " + startDDMMYYYY + "-" + endDDMMYYYY);
                    return "UNAVAILABLE: Vehicle not available for that period. Call reserveVehicle again with same inputs to join waitlist.";
                }

                pendingWaitlistConfirm.remove(pKey);
                rec.waitlist.addLast(new VehicleRecord.WaitlistEntry(customerID, start, end));
                log("reserveVehicle WAITLISTED " + customerID + " " + vehicleID + " " + startDDMMYYYY + "-" + endDDMMYYYY);
                log("WAITLIST ENQUEUE customer=" + customerID
                        + " vehicle=" + vehicleID
                        + " start=" + startDDMMYYYY
                        + " end=" + endDDMMYYYY
                        + " waitlistSizeNow=" + rec.waitlist.size());
                return "WAITLISTED: " + vehicleID + " for " + startDDMMYYYY + "-" + endDDMMYYYY;
            }

            int price = rec.reservationPrice;
            if (deductBudget) {
                int cur = getBudget(customerID);
                if (cur < price) return "ERROR: Over budget.";
                setBudget(customerID, cur - price);
                incReservedCountFromOffice(customerID, office.name());
            }

            reservationDB.put(resKey(customerID, vehicleID),
                    new ReservationRecord(customerID, vehicleID, price, start, end));
            log("reserveVehicle OK " + customerID + " " + vehicleID + " " + startDDMMYYYY + "-" + endDDMMYYYY + " cost=" + price);
            return "SUCCESS|" + price;
        } finally {
            lock.unlock();
        }
    }

    public String updateReservation(String customerID, String vehicleID, String newStartDDMMYYYY, String newEndDDMMYYYY) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) {
            String udpReply = udpRequest(targetOffice,
                    "UPDATE|" + customerID + "|" + vehicleID + "|" + newStartDDMMYYYY + "|" + newEndDDMMYYYY);

            if (udpReply.startsWith("SUCCESS|")) {
                int price = Integer.parseInt(udpReply.split("\\|")[1]);
                int cur = getBudget(customerID);
                if (cur < price) {
                    udpRequest(targetOffice, "CANCEL|" + customerID + "|" + vehicleID);
                    return "ERROR: Over budget (need " + price + ", have " + cur + ")";
                }
                setBudget(customerID, cur - price);
                incReservedCountFromOffice(customerID, targetOffice);
                log("updateReservation REMOTE waitlist->reserved " + customerID + " " + vehicleID + " cost=" + price);
                return "SUCCESS: updated " + vehicleID;
            }

            if (udpReply.startsWith("SUCCESS")) {
                log("updateReservation REMOTE ok " + customerID + " " + vehicleID);
            }
            return udpReply;
        }

        return updateLocal(customerID, vehicleID, newStartDDMMYYYY, newEndDDMMYYYY, true);
    }

    private String updateLocal(String customerID, String vehicleID,
                               String newStartDDMMYYYY, String newEndDDMMYYYY,
                               boolean deductBudgetIfWaitlistAssigned) {
        DateRange range = parseAndValidateRange(newStartDDMMYYYY, newEndDDMMYYYY);
        if (!range.ok) return range.error;

        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            VehicleRecord rec = vehicles.get(vehicleID);
            if (rec == null) return "ERROR: vehicle not found.";

            String key = resKey(customerID, vehicleID);
            ReservationRecord existing = reservationDB.get(key);
            if (existing != null) {
                return updateExistingReservation(customerID, vehicleID, key, existing, rec, range,
                        newStartDDMMYYYY, newEndDDMMYYYY);
            }

            VehicleRecord.WaitlistEntry entry = findWaitlistEntry(rec, customerID);
            if (entry == null) {
                return "ERROR: No current reservation or waitlist for this vehicle.";
            }

            return promoteWaitlistToReservation(customerID, vehicleID, key, rec, entry, range,
                    newStartDDMMYYYY, newEndDDMMYYYY, deductBudgetIfWaitlistAssigned);
        } finally {
            lock.unlock();
        }
    }

    private static class DateRange {
        final boolean ok;
        final String error;
        final LocalDate start;
        final LocalDate end;

        private DateRange(boolean ok, String error, LocalDate start, LocalDate end) {
            this.ok = ok;
            this.error = error;
            this.start = start;
            this.end = end;
        }

        static DateRange ok(LocalDate s, LocalDate e) {
            return new DateRange(true, null, s, e);
        }

        static DateRange err(String msg) {
            return new DateRange(false, msg, null, null);
        }
    }

    private DateRange parseAndValidateRange(String newStartDDMMYYYY, String newEndDDMMYYYY) {
        LocalDate ns;
        LocalDate ne;
        try {
            ns = parseDateDDMMYYYY(newStartDDMMYYYY);
            ne = parseDateDDMMYYYY(newEndDDMMYYYY);
        } catch (Exception e) {
            return DateRange.err("ERROR: Invalid date format ddmmyyyy.");
        }
        if (ne.isBefore(ns)) return DateRange.err("ERROR: endDate before startDate.");
        return DateRange.ok(ns, ne);
    }

    private String updateExistingReservation(String customerID, String vehicleID,
                                             String resKey,
                                             ReservationRecord existing,
                                             VehicleRecord rec,
                                             DateRange range,
                                             String newStartDDMMYYYY, String newEndDDMMYYYY) {
        boolean ok = isAvailableForPeriod(vehicleID, range.start, range.end, resKey);
        if (!ok) {
            String pKey = pendingKey(customerID, vehicleID, newStartDDMMYYYY, newEndDDMMYYYY);
            if (!pendingWaitlistConfirm.containsKey(pKey)) {
                pendingWaitlistConfirm.put(pKey, System.currentTimeMillis());
                log("updateReservation UNAVAILABLE " + customerID + " " + vehicleID + " "
                        + newStartDDMMYYYY + "-" + newEndDDMMYYYY);
                return "UNAVAILABLE: Vehicle not available for updated period.";
            }

            pendingWaitlistConfirm.remove(pKey);
            reservationDB.remove(resKey);

            int refund = existing.totalPrice;
            setBudget(customerID, getBudget(customerID) + refund);
            decReservedCountFromOffice(customerID, office.name());

            rec.waitlist.addLast(new VehicleRecord.WaitlistEntry(customerID, range.start, range.end));

            log("updateReservation WAITLISTED " + customerID + " " + vehicleID + " "
                    + newStartDDMMYYYY + "-" + newEndDDMMYYYY
                    + " refund=" + refund
                    + " waitlistSizeNow=" + rec.waitlist.size());

            autoAssignWaitlist(vehicleID);
            return "WAITLISTED: " + vehicleID + " for " + newStartDDMMYYYY + "-" + newEndDDMMYYYY;
        }

        reservationDB.put(resKey,
                new ReservationRecord(customerID, vehicleID, existing.totalPrice, range.start, range.end));
        log("updateReservation OK " + customerID + " " + vehicleID + " "
                + newStartDDMMYYYY + "-" + newEndDDMMYYYY);
        autoAssignWaitlist(vehicleID);
        return "SUCCESS: updated " + vehicleID;
    }

    private VehicleRecord.WaitlistEntry findWaitlistEntry(VehicleRecord rec, String customerID) {
        for (VehicleRecord.WaitlistEntry e : rec.waitlist) {
            if (e.customerID.equals(customerID)) {
                return e;
            }
        }
        return null;
    }

    private String promoteWaitlistToReservation(String customerID, String vehicleID,
                                                String resKey,
                                                VehicleRecord rec,
                                                VehicleRecord.WaitlistEntry entry,
                                                DateRange range,
                                                String newStartDDMMYYYY, String newEndDDMMYYYY,
                                                boolean deductBudgetIfWaitlistAssigned) {
        boolean ok = isAvailableForPeriod(vehicleID, range.start, range.end);
        if (!ok) {
            return "ERROR: Vehicle not available for updated period.";
        }

        int price = rec.reservationPrice;
        if (deductBudgetIfWaitlistAssigned) {
            int cur = getBudget(customerID);
            if (cur < price) return "ERROR: Over budget.";
            setBudget(customerID, cur - price);
            incReservedCountFromOffice(customerID, office.name());
        }

        rec.waitlist.remove(entry);
        reservationDB.put(resKey, new ReservationRecord(customerID, vehicleID, price, range.start, range.end));
        log("updateReservation WAITLIST->RESERVED " + customerID + " " + vehicleID
                + " " + newStartDDMMYYYY + "-" + newEndDDMMYYYY + " cost=" + price);
        return deductBudgetIfWaitlistAssigned ? "SUCCESS: updated " + vehicleID : ("SUCCESS|" + price);
    }

    public String cancelReservation(String customerID, String vehicleID) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        String targetOffice = officeOfVehicle(vehicleID);
        if (!targetOffice.equals(office.name())) {
            String udpReply = udpRequest(targetOffice, "CANCEL|" + customerID + "|" + vehicleID);
            if (udpReply.startsWith("SUCCESS|")) {
                int refund = Integer.parseInt(udpReply.split("\\|")[1]);
                if (refund > 0) {
                    setBudget(customerID, getBudget(customerID) + refund);
                    decReservedCountFromOffice(customerID, targetOffice);
                    retryWaitlistsForCustomer(customerID);
                }
                log("cancelReservation REMOTE ok " + customerID + " " + vehicleID + " refund=" + refund);
                return (refund > 0)
                        ? ("SUCCESS: cancelled " + vehicleID + " refund=" + refund)
                        : ("SUCCESS: cancelled waitlist for " + vehicleID);
            }
            return udpReply;
        }

        return cancelLocal(customerID, vehicleID, true);
    }

    public String displayCurrentBudget(String customerID) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";
        int currentBudget = getBudget(customerID);
        log("displayCurrentBudget customer=" + customerID + " budget=" + currentBudget);
        return "Current budget: " + currentBudget;
    }

    public String displayReservations(String customerID) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        StringBuilder sb = new StringBuilder();
        sb.append(listReservationsLocal(customerID));

        for (Office other : Office.values()) {
            if (other == this.office) continue;
            String reply = udpRequest(other.name(), "LISTRES|" + customerID);
            if (reply != null && !reply.trim().isEmpty()) {
                sb.append(reply);
            }
        }

        String result = sb.toString().trim();
        log("displayReservations customer=" + customerID + " combinedLen=" + result.length());
        return result.isEmpty() ? "No active reservations." : (result + "\n");
    }

    private String listReservationsLocal(String customerID) {
        StringBuilder sb = new StringBuilder();
        for (ReservationRecord rr : reservationDB.values()) {
            if (rr.customerID.equals(customerID)) {
                sb.append(rr.vehicleID).append(" from ")
                        .append(formatDate(rr.startDate)).append(" to ")
                        .append(formatDate(rr.endDate)).append("\n");
            }
        }
        return sb.toString();
    }

    private String cancelLocal(String customerID, String vehicleID, boolean adjustBudgetAtHome) {
        ReentrantLock lock = lockFor(vehicleID);
        lock.lock();
        try {
            ReservationRecord rr = reservationDB.remove(resKey(customerID, vehicleID));
            if (rr != null) {
                int refund = rr.totalPrice;
                if (adjustBudgetAtHome) {
                    setBudget(customerID, getBudget(customerID) + refund);
                    decReservedCountFromOffice(customerID, office.name());
                    retryWaitlistsForCustomer(customerID);
                }

                log("cancelReservation OK " + customerID + " " + vehicleID + " refund=" + refund);
                autoAssignWaitlist(vehicleID);
                return "SUCCESS|" + refund;
            }

            VehicleRecord rec = vehicles.get(vehicleID);
            if (rec == null) {
                return "ERROR: vehicle not found.";
            }

            boolean removed = false;
            for (Iterator<VehicleRecord.WaitlistEntry> it = rec.waitlist.iterator(); it.hasNext(); ) {
                VehicleRecord.WaitlistEntry e = it.next();
                if (e.customerID.equals(customerID)) {
                    it.remove();
                    removed = true;
                }
            }

            if (removed) {
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
        for (ReservationRecord rr : reservationDB.values()) {
            if (!rr.vehicleID.equals(vehicleID)) continue;
            if (overlaps(rr.startDate, rr.endDate, today, today)) {
                count++;
            }
        }
        return count;
    }

    private String availabilityStatusToday(VehicleRecord rec) {
        int reservedNow = reservedCountForToday(rec.vehicleID);
        return reservedNow == 0 ? "Available" : "Reserved";
    }

    public String findVehicle(String customerID, String vehicleType) {
        if (customerID.charAt(3) != 'U') return "ERROR: Not a customer.";
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equals(office.name())) return "ERROR: Call your HOME office server.";

        StringBuilder sb = new StringBuilder();
        sb.append(findLocal(vehicleType));
        for (Office other : Office.values()) {
            if (other == this.office) continue;
            sb.append(udpRequest(other.name(), "FIND|" + vehicleType));
        }
        log("findVehicle " + vehicleType);
        return sb.toString();
    }

    private String findLocal(String vehicleType) {
        StringBuilder sb = new StringBuilder();
        for (VehicleRecord rec : vehicles.values()) {
            if (!rec.vehicleType.equalsIgnoreCase(vehicleType)) continue;
            String status = availabilityStatusToday(rec);
            sb.append(rec.vehicleID).append(" ")
                    .append(rec.vehicleType).append(" ")
                    .append(status).append(" ")
                    .append(rec.reservationPrice)
                    .append("\n");
        }
        return sb.toString();
    }

    private void autoAssignWaitlist(String vehicleID) {
        VehicleRecord rec = vehicles.get(vehicleID);
        if (rec == null) return;

        boolean assignedInPass;
        do {
            assignedInPass = false;
            for (Iterator<VehicleRecord.WaitlistEntry> it = rec.waitlist.iterator(); it.hasNext(); ) {
                VehicleRecord.WaitlistEntry entry = it.next();

                if (!isAvailableForPeriod(vehicleID, entry.start, entry.end)) {
                    continue;
                }

                String customerID = entry.customerID;
                String homeOffice = officeOfUser(customerID);
                String targetOffice = office.name();
                int price = rec.reservationPrice;

                boolean approved = false;
                if (homeOffice.equals(targetOffice)) {
                    int cur = getBudget(customerID);
                    if (cur < price) {
                        log("autoAssignWaitlist SKIP_OVER_BUDGET " + customerID + " " + vehicleID);
                        continue;
                    }
                    setBudget(customerID, cur - price);
                    incReservedCountFromOffice(customerID, targetOffice);
                    approved = true;
                } else {
                    String reply = udpRequest(homeOffice,
                            "WAITLIST_ASSIGN|" + customerID + "|" + targetOffice + "|" + price);
                    if (reply != null && reply.startsWith("SUCCESS")) {
                        approved = true;
                    } else {
                        log("autoAssignWaitlist REMOTE_DENIED " + customerID + " " + vehicleID + " reply=" + reply);
                        continue;
                    }
                }

                if (!approved) {
                    continue;
                }

                it.remove();
                reservationDB.put(resKey(customerID, vehicleID),
                        new ReservationRecord(customerID, vehicleID, price, entry.start, entry.end));

                String msg = "WAITLIST ASSIGNED: vehicle=" + vehicleID
                        + " from " + formatDate(entry.start)
                        + " to " + formatDate(entry.end)
                        + " cost=" + price;

                if (homeOffice.equals(office.name())) {
                    notifyCustomer(customerID, msg);
                } else {
                    udpRequest(homeOffice, "NOTIFY|" + customerID + "|" + msg);
                }

                log("autoAssignWaitlist OK " + customerID + " " + vehicleID + " "
                        + entry.start + "-" + entry.end);
                assignedInPass = true;
                break;
            }
        } while (assignedInPass);
    }

    private void retryWaitlistsForCustomer(String customerID) {
        retryWaitlistsLocal(customerID);
        for (Office other : Office.values()) {
            if (other == this.office) continue;
            udpRequest(other.name(), "RETRY_WAITLIST|" + customerID);
        }
    }

    private String retryWaitlistsLocal(String customerID) {
        List<String> vehicleIds = new ArrayList<>();
        for (Map.Entry<String, VehicleRecord> entry : vehicles.entrySet()) {
            for (VehicleRecord.WaitlistEntry waitlistEntry : entry.getValue().waitlist) {
                if (waitlistEntry.customerID.equals(customerID)) {
                    vehicleIds.add(entry.getKey());
                    break;
                }
            }
        }
        for (String vehicleID : vehicleIds) {
            autoAssignWaitlist(vehicleID);
        }
        return "SUCCESS";
    }

    public void startUdpListener() {
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try (DatagramSocket socket = new DatagramSocket(udpPort)) {
                    log("UDP listening on port " + udpPort);
                    byte[] buf = new byte[2048];

                    while (!Thread.currentThread().isInterrupted()) {
                        DatagramPacket req = new DatagramPacket(buf, buf.length);
                        socket.receive(req);

                        String msg = new String(req.getData(), 0, req.getLength(), StandardCharsets.UTF_8);
                        String reply = handleUdp(msg);

                        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket resp = new DatagramPacket(out, out.length, req.getAddress(), req.getPort());
                        socket.send(resp);
                    }
                } catch (IOException e) {
                    log("UDP error: " + e.getMessage());
                }
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
                setBudget(parts[1], getBudget(parts[1]) + Integer.parseInt(parts[2]));
                decReservedCountFromOffice(parts[1], parts[3]);
                retryWaitlistsForCustomer(parts[1]);
                log("REFUND APPLIED customer=" + parts[1] + " amount=" + parts[2] + " fromOffice=" + parts[3]);
                return "SUCCESS";
            case "RETRY_WAITLIST":
                return retryWaitlistsLocal(parts[1]);
            case "LISTRES":
                return listReservationsLocal(parts[1]);
            case "NOTIFY":
                notifyCustomer(parts[1], parts[2]);
                return "SUCCESS";
            case "WAITLIST_ASSIGN": {
                String customerID = parts[1];
                String fromOffice = parts[2];
                int price = Integer.parseInt(parts[3]);
                String homeOffice = officeOfUser(customerID);
                if (!fromOffice.equals(homeOffice) && getTotalRemoteReservedCount(customerID, homeOffice) >= 1) {
                    return "ERROR: You can only reserve ONE vehicle outside your home office (" + homeOffice + ").";
                }
                int cur = getBudget(customerID);
                if (cur < price) {
                    return "ERROR: Over budget.";
                }
                setBudget(customerID, cur - price);
                incReservedCountFromOffice(customerID, fromOffice);
                log("WAITLIST_ASSIGN APPROVED customer=" + customerID
                        + " fromOffice=" + fromOffice
                        + " price=" + price
                        + " remainingBudget=" + (cur - price));
                return "SUCCESS";
            }
            default:
                return "ERROR: unknown UDP op";
        }
    }

    private String udpRequest(String officeCode, String payload) {
        int port;
        switch (officeCode) {
            case "MTL":
                port = 7201;
                break;
            case "WPG":
                port = 7202;
                break;
            case "BNF":
                port = 7203;
                break;
            default:
                throw new IllegalArgumentException("Unknown office " + officeCode);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1500);
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket req = new DatagramPacket(out, out.length, InetAddress.getLocalHost(), port);
            socket.send(req);

            byte[] in = new byte[4096];
            DatagramPacket resp = new DatagramPacket(in, in.length);
            socket.receive(resp);

            return new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
        } catch (SocketTimeoutException e) {
            return "ERROR: UDP failed to " + officeCode + " (" + e.getMessage() + ")";
        } catch (IOException e) {
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
}
