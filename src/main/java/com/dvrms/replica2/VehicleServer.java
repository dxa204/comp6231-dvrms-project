package com.dvrms.replica2;

import com.dvrms.common.InitialData;
import com.dvrms.common.ReplicaResponseNormalizer;
import com.dvrms.common.VehicleRecord;
import org.omg.CORBA.*;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

import java.net.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.lang.Object;

public class VehicleServer extends DVRMSPOA {

    private final String city;
    private final int    replicaId;   // 1–4
    private final int    listenPort;  // 6001–6004
    private final String rmHost;
    private final int    rmPort;      // 7001–7004

    // vehicleID -> Vehicle
    private final ConcurrentHashMap<String, Vehicle> vehicles = new ConcurrentHashMap<>();

    // vehicleID -> reservations
    private final ConcurrentHashMap<String, List<Reservation>> reservations = new ConcurrentHashMap<>();

    // customerID -> budget
    private final ConcurrentHashMap<String, Double> budgets = new ConcurrentHashMap<>();

    // vehicleID -> waitlist
    private final ConcurrentHashMap<String, Queue<Reservation>> waitlists = new ConcurrentHashMap<>();

    // vehicleID -> lock
    private final ConcurrentHashMap<String, ReentrantLock> recordLocks = new ConcurrentHashMap<>();

    // customerID|vehicleID|start|end -> first unavailable observation time
    private final ConcurrentHashMap<String, Long> pendingWaitlistConfirm = new ConcurrentHashMap<>();

    // customerID -> officeCode -> count
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> customerReservedFromOffice =
            new ConcurrentHashMap<>();

    // Total-order sequencing
    private final Object seqLock = new Object();
    private int nextExpectedSeq = 1;
 
    // Out-of-order buffer: seqNum → parts array
    private final ConcurrentHashMap<Integer, String[]> pendingRequests = new ConcurrentHashMap<>();
 
    // Duplicate detection: seqNums already executed
    private final Set<Integer> processedSeqNums = ConcurrentHashMap.newKeySet();
 
    // Per team protocol: 500 ms timeout, 5 retries
    private static final int UDP_TIMEOUT_MS  = 500;
    private static final int UDP_MAX_RETRIES = 5;

    // -----------------------------------------------------------------------
 
    public VehicleServer(String city, int replicaId, int listenPort, String rmHost, int rmPort) {
        this.city = city;
        this.replicaId = replicaId;
        this.listenPort = listenPort;
        this.rmHost = rmHost;
        this.rmPort = rmPort;
        seedInitialData();
        System.out.println("[Replica " + replicaId + "] " + city + " ready on port " + listenPort);
    }

    private void seedInitialData() {
        Map<String, VehicleRecord> initialRecords;
        switch (city.toUpperCase(Locale.ROOT)) {
            case "MTL":
                initialRecords = InitialData.getMTLData();
                break;
            case "WPG":
                initialRecords = InitialData.getWPGData();
                break;
            case "BNF":
                initialRecords = InitialData.getBNFData();
                break;
            default:
                throw new IllegalArgumentException("Unknown city for initial data: " + city);
        }

        for (VehicleRecord record : initialRecords.values()) {
            vehicles.put(record.vehicleID, new Vehicle(
                    record.vehicleID,
                    record.vehicleType,
                    String.valueOf(record.vehicleNumber),
                    record.reservationPrice
            ));
        }
    }

    /**
     * Sends startup announcement to the RM.
     * Format: REGISTER|<replicaId>|<city>|<listenPort>
     *
     * The RM probes replicas via PING|<id> on this same listen port.
     * The replica listener handles PING and replies PONG|<id> immediately.
     */
    public void registerWithRM() {
        try {
            sendUDP(rmHost, rmPort, "REGISTER|" + replicaId + "|" + city + "|" + listenPort);
            System.out.println("[Replica " + replicaId + "] Registered with RM at " + rmHost + ":" + rmPort);
        } catch (Exception e) {
            System.err.println("[Replica " + replicaId + "] Failed to register with RM: " + e.getMessage());
        }
    }

    /**
     * Listens on listenPort for two message types:
     *
     * 1. From Sequencer:
     *      REQ|<msgID>|<seqNum>|<feHost>|<fePort>|<method>|<args...>
     *    Immediately replies: ACK|<msgID>  (back to Sequencer)
     *    Then executes in total seqNum order and replies:
     *      RESULT|<msgID>|<replicaID>|<resultString>  → to FE
     *
     * 2. From RM:
     *      CRASH|<replicaId>
     *    If replicaId matches ours: System.exit(0) so the RM can restart us.
     */

    public void startReplicaListener() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(listenPort)) {
                System.out.println("[Replica " + replicaId + "] Listening on port " + listenPort);
                while (true) {
                    byte[] buf = new byte[4096];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
 
                    String   raw   = new String(pkt.getData(), 0, pkt.getLength()).trim();
                    String[] parts = raw.split("\\|");
 
                    // --- RM: shut this replica down so it can be restarted ---
                    // CRASH|<replicaId>
                    if (parts[0].equals("CRASH")) {
                        if (Integer.parseInt(parts[1]) == replicaId) {
                            System.out.println("[Replica " + replicaId + "] CRASH from RM -- shutting down");
                            System.exit(0);
                        }
                        continue;
                    }
 
                    // --- RM liveness probe ---
                    // PING|<replicaId>  ->  PONG|<replicaId>
                    if (parts[0].equals("PING")) {
                        byte[] pong = ("PONG|" + replicaId).getBytes();
                        socket.send(new DatagramPacket(pong, pong.length,
                                pkt.getAddress(), pkt.getPort()));
                        continue;
                    }
 
                    // --- Sequencer request ---
                    // REQ|<msgID>|<seqNum>|<feHost>|<fePort>|<method>|<args...>
                    if (!parts[0].equals("REQ") || parts.length < 6) {
                        System.err.println("[Replica " + replicaId + "] Malformed: " + raw);
                        continue;
                    }
 
                    String msgID  = parts[1];
                    int    seqNum = Integer.parseInt(parts[2]);
 
                    // ACK|<replicaId>|<msgID> back to Sequencer immediately
                    byte[] ack = ("ACK|R" + replicaId + "|" + msgID).getBytes();
                    socket.send(new DatagramPacket(ack, ack.length, pkt.getAddress(), pkt.getPort()));
 
                    // Duplicate guard
                    if (processedSeqNums.contains(seqNum)) {
                        System.out.println("[Replica " + replicaId + "] Dup seqNum=" + seqNum + " skipped");
                        continue;
                    }
 
                    // Buffer and drain in order
                    synchronized (seqLock) {
                        pendingRequests.put(seqNum, parts);
                        while (pendingRequests.containsKey(nextExpectedSeq)) {
                            String[] p = pendingRequests.remove(nextExpectedSeq);
                            processedSeqNums.add(nextExpectedSeq);
                            nextExpectedSeq++;
                            processRequest(p);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Replica " + replicaId + "] Listener crashed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "replica-listener-" + replicaId).start();
    }
 
    /**
     * Executes one sequenced request and sends RESULT to the FE.
     *
     * parts layout:
     *   [0] REQ  [1] msgID  [2] seqNum  [3] feHost  [4] fePort
     *   [5] method  [6..] method args
     *
     * FE address is read from the message so the RM can redirect to a new FE
     * without restarting replicas.
     *
     * Output: RESULT|<msgID>|<replicaID>|<resultString>
     */

    private void processRequest(String[] parts) {
        String msgID = parts[1];
        String feHost = parts[3];
        int fePort = Integer.parseInt(parts[4]);
        String method = parts[5];
        String result;
 
        try {
            switch (method) {
                case "add":
                case "addVehicle":
                    // args: mID vnum vtype vID price
                    result = addVehicle(parts[6], parts[7], parts[8], parts[9],
                                        Double.parseDouble(parts[10]));
                    break;
                case "remove":
                case "removeVehicle":
                    // args: mID vID
                    result = removeVehicle(parts[6], parts[7]);
                    break;
                case "list":
                case "listVehicles":
                case "listAvailableVehicles":
                    // args: mID
                    result = listAvailableVehicle(parts[6]);
                    break;
                case "reserve":
                case "reserveVehicle":
                    // args: cID vID start end
                    result = reserveVehicle(parts[6], parts[7], parts[8], parts[9]);
                    break;
                case "update":
                case "updateReservation":
                    // args: cID vID newStart newEnd
                    result = updateReservation(parts[6], parts[7], parts[8], parts[9]);
                    break;
                case "cancel":
                case "cancelReservation":
                    // args: cID vID
                    result = cancelReservation(parts[6], parts[7]);
                    break;
                case "find":
                case "findVehicle":
                    // args: cID vtype
                    result = findVehicle(parts[6], parts[7]);
                    break;
                case "displayCurrentBudget":
                    result = displayCurrentBudget(parts[6]);
                    break;
                case "displayReservations":
                    result = displayReservations(parts[6]);
                    break;
                case "displayReservationsLocal":
                    result = displayReservationsLocal(parts[6]);
                    break;
                case "remoteReserveVehicle":
                case "remoteCancelReservation":
                case "addToWaitingList":
                case "getLocalVehiclesByType":
                    result = "Unsupported operation in replica2 backend: " + method;
                    break;
                default:
                    result = "Unknown method: " + method;
            }
        } catch (Exception e) {
            result = "Execution error: " + e.getMessage();
        }
 
        // Send RESULT|<msgID>|<replicaID>|<resultString> to FE.
        // FE does not implement ACKs for result packets, so use plain UDP here.
        try {
            sendResultToFrontEnd(feHost, fePort, msgID, ReplicaResponseNormalizer.normalize(method, result));
            Logger.log(city, "[R" + replicaId + "] " + method + " msgID=" + msgID + " → " + result, "replica");
        } catch (Exception e) {
            System.err.println("[Replica " + replicaId + "] Failed to send RESULT to FE: " + e.getMessage());
        }
    }

    private void sendResultToFrontEnd(String host, int port, String msgId, String result) throws Exception {
        String message = "RESULT|" + msgId + "|R" + replicaId + "|" + result;
        byte[] data = message.getBytes();
        InetAddress addr = InetAddress.getByName(host);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(data, data.length, addr, port));
        }
    }
 
    // ----------------------------------------------
    // Reliable UDP — ACK-based, 500 ms / 5 retries
    // ----------------------------------------------
 
    private void sendUDP(String host, int port, String message) throws Exception {
        byte[] data = message.getBytes();
        InetAddress addr = InetAddress.getByName(host);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(UDP_TIMEOUT_MS);
            for (int attempt = 1; attempt <= UDP_MAX_RETRIES; attempt++) {
                socket.send(new DatagramPacket(data, data.length, addr, port));
                try {
                    byte[] ackBuf = new byte[64];
                    DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPkt);
                    if (new String(ackPkt.getData(), 0, ackPkt.getLength()).trim().startsWith("ACK"))
                        return;
                } catch (SocketTimeoutException ignored) {
                    System.err.println("[Replica " + replicaId + "] Retry " + attempt + " → " + host + ":" + port);
                }
            }
            throw new Exception("UDP failed after " + UDP_MAX_RETRIES + " retries to " + host + ":" + port);
        }
    }

    // ================= HELPERS =================

    private ReentrantLock lockFor(String vID) {
        return recordLocks.computeIfAbsent(vID, k -> new ReentrantLock());
    }

    private boolean local(String vID) {
        return vID.startsWith(city);
    }

    private static String officeOfUser(String userID) {
        return userID.substring(0, 3);
    }

    private static String officeOfVehicle(String vehicleID) {
        return vehicleID.substring(0, 3);
    }

    private boolean isManager(String managerID) {
        return managerID != null && managerID.length() >= 4 && Character.toUpperCase(managerID.charAt(3)) == 'M';
    }

    private boolean isCustomer(String customerID) {
        return customerID != null && customerID.length() >= 4 && Character.toUpperCase(customerID.charAt(3)) == 'U';
    }

    private String requireHomeManager(String managerID) {
        if (!isManager(managerID)) {
            return "ERROR: Not a manager.";
        }
        if (!officeOfUser(managerID).equalsIgnoreCase(city)) {
            return "ERROR: Call your HOME office server.";
        }
        return null;
    }

    private String requireHomeCustomer(String customerID) {
        if (!isCustomer(customerID)) {
            return "ERROR: Not a customer.";
        }
        if (!officeOfUser(customerID).equalsIgnoreCase(city)) {
            return "ERROR: Call your HOME office server.";
        }
        return null;
    }

    private int getTotalRemoteReservedCount(String customerID, String homeOffice) {
        Map<String, Integer> counts = customerReservedFromOffice.computeIfAbsent(
                customerID, ignored -> new ConcurrentHashMap<>());

        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!entry.getKey().equals(homeOffice)) {
                total += entry.getValue() == null ? 0 : entry.getValue();
            }
        }
        return total;
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

    // ================= MANAGER =================

    public String addVehicle(String mID, String vnum, String vtype, String vID, double price) {
        String managerOffice = officeOfUser(mID);
        if (!managerOffice.equalsIgnoreCase(city)) {
            try {
                return getRemoteServer(managerOffice).addVehicle(mID, vnum, vtype, vID, price);
            } catch (Exception e) {
                return "ERROR: UDP failed to " + managerOffice + " (" + e.getMessage() + ")";
            }
        }

        String accessError = requireHomeManager(mID);
        if (accessError != null) return accessError;
        if (!officeOfVehicle(vID).equalsIgnoreCase(city))
            return "ERROR: vehicleID does not belong to this office server.";

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            
            boolean isUpdate = vehicles.containsKey(vID);
            vehicles.put(vID, new Vehicle(vID, vtype, vnum, price));
 
            if (isUpdate) {
                Logger.log(city, "addVehicle UPDATE manager=" + mID + " vehicleID=" + vID
                        + " type=" + vtype + " price=" + price, "manager");
                promoteWaitlist(vID);
                return "SUCCESS: vehicle updated " + vID;
            } else {
                Logger.log(city, "addVehicle NEW manager=" + mID + " vehicleID=" + vID
                        + " type=" + vtype + " price=" + price, "manager");
                promoteWaitlist(vID);
                return "SUCCESS: vehicle added " + vID;
            }
        } finally {
            lock.unlock();
        }
    }

    public String removeVehicle(String mID, String vID) {
        String managerOffice = officeOfUser(mID);
        if (!managerOffice.equalsIgnoreCase(city)) {
            try {
                return getRemoteServer(managerOffice).removeVehicle(mID, vID);
            } catch (Exception e) {
                return "ERROR: UDP failed to " + managerOffice + " (" + e.getMessage() + ")";
            }
        }

        String accessError = requireHomeManager(mID);
        if (accessError != null) return accessError;
        if (!officeOfVehicle(vID).equalsIgnoreCase(city))
            return "ERROR: vehicleID does not belong to this office server.";

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            if(!vehicles.containsKey(vID) && !reservations.containsKey(vID)) {
                return "ERROR: vehicle does not exist.";
            }
            vehicles.remove(vID);

            // Refund all customers who had active reservations for this vehicle
            List<Reservation> activeReservations = reservations.remove(vID);
            int cancelled = 0;
            double totalRefund = 0.0;
            if (activeReservations != null) {
                for (Reservation r : activeReservations) {
                    budgets.compute(r.customerID, (k, b) -> (b == null ? 0.0 : b) + r.vehicle.price);
                    decrementReservationCount(r.customerID, officeOfVehicle(vID));
                    cancelled++;
                    totalRefund += r.vehicle.price;
                    Logger.log(city, "removeVehicle CANCELLED reservation customer=" + r.customerID
                            + " vehicle=" + vID + " refund=" + r.vehicle.price, "manager");
                }
            }
 
            // Clear the waitlist for this vehicle
            Queue<Reservation> wl = waitlists.remove(vID);
            int waitlisted = wl == null ? 0 : wl.size();
            if (wl != null && !wl.isEmpty()) {
                Logger.log(city, "Cleared waitlist for removed vehicle " + vID, "manager");
            }

	        Logger.log(city, "removeVehicle OK manager=" + mID + " vehicleID=" + vID
                    + " cancelledReservations=" + cancelled + " clearedWaitlist=" + waitlisted
                    + " totalRefund=" + totalRefund, "manager");
            return "SUCCESS: removed " + vID
                    + " cancelledReservations=" + cancelled
                    + " clearedWaitlist=" + waitlisted
                    + " totalRefund=" + (int) totalRefund;
        } finally {
            lock.unlock();
        }
    }

    public String listAvailableVehicle(String mID) {
        String managerOffice = officeOfUser(mID);
        if (!managerOffice.equalsIgnoreCase(city)) {
            try {
                return getRemoteServer(managerOffice).listAvailableVehicle(mID);
            } catch (Exception e) {
                return "ERROR: UDP failed to " + managerOffice + " (" + e.getMessage() + ")";
            }
        }

        String accessError = requireHomeManager(mID);
        if (accessError != null) return accessError;

        StringBuilder sb = new StringBuilder();
        sb.append("Vehicle Availability for today (").append(LocalDate.now()).append(")\n");
        vehicles.values().forEach(v -> sb.append(v.vehicleID)
                .append(" type=").append(v.vehicleType)
                .append(" price=").append((int) v.price)
                .append(" statusToday=").append(reservedCountForToday(v.vehicleID) == 0 ? "AVAILABLE" : "RESERVED")
                .append(" waitlist=").append(waitlists.getOrDefault(v.vehicleID, new ConcurrentLinkedQueue<Reservation>()).size())
                .append("\n"));
        return sb.toString();
    }

    // ================= CUSTOMER =================

    public String reserveVehicle(String cID, String vID, String start, String end) {
        if (!isCustomer(cID)) {
            return "ERROR: Not a customer.";
        }

        budgets.putIfAbsent(cID, 1000.0);

        String homeOffice = officeOfUser(cID);
        String targetOffice = officeOfVehicle(vID);
        if (!targetOffice.equals(homeOffice) && getTotalRemoteReservedCount(cID, homeOffice) >= 1) {
            return "ERROR: You can only reserve ONE vehicle outside your home office (" + homeOffice + ").";
        }

        if (!local(vID)) {
            String accessError = requireHomeCustomer(cID);
            if (accessError != null) return accessError;
            return forwardReserve(targetOffice, cID, vID, start, end);
        }

        boolean chargeHomeBudget = homeOffice.equalsIgnoreCase(city);

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            Vehicle v = vehicles.get(vID);
            
            if (v == null)
                return "Vehicle does not exist";

            double cost = v.price;
            if (chargeHomeBudget && budgets.get(cID) < cost)
                return "ERROR: Over budget.";
            
            if (!isAvailable(vID, start, end)) {
                String waitlistKey = cID + "|" + vID + "|" + start + "|" + end;
                if (!pendingWaitlistConfirm.containsKey(waitlistKey)) {
                    pendingWaitlistConfirm.put(waitlistKey, System.currentTimeMillis());
                    Logger.log(city, "reserveVehicle UNAVAILABLE " + cID + " " + vID
                            + " " + start + "-" + end, "customer");
                    return "UNAVAILABLE: Vehicle not available for that period. Call reserveVehicle again with same inputs to join waitlist.";
                }

                pendingWaitlistConfirm.remove(waitlistKey);
                waitlists.computeIfAbsent(vID, k -> new ConcurrentLinkedQueue<>()).add(new Reservation(cID, start, end, v));
                Logger.log(city, "reserveVehicle WAITLISTED " + cID + " " + vID
                        + " " + start + "-" + end, "customer");
                return "WAITLISTED: " + vID + " for " + start + "-" + end;
            }

            reservations.computeIfAbsent(vID, k -> new ArrayList<>()).add(new Reservation(cID, start, end, v));

            if (chargeHomeBudget) {
                budgets.compute(cID, (k, b) -> b - cost);
                incrementReservationCount(cID, city);
            }

            Logger.log(city,"Customer " + cID + " reserved " + vID + " from " + start + " to " + end, "customer");
            
            return "SUCCESS|"+ (int) cost;
        } finally {
            lock.unlock();
        }
    }

    public String updateReservation(String cID, String vID, String start, String end) {
        if (!isCustomer(cID)) {
            return "ERROR: Not a customer.";
        }
        
        String homeOffice = officeOfUser(cID);
        String targetOffice = officeOfVehicle(vID);
        if (!local(vID)) {
            return forwardUpdate(targetOffice, cID, vID, start, end);
        }

        boolean chargeHomeBudget = homeOffice.equalsIgnoreCase(city);
        try {
            ReentrantLock lock = lockFor(vID);
            lock.lock();
            
            try {
                List<Reservation> list = reservations.get(vID);
                Reservation target = null;
                if (list != null) {
                    for (Reservation r : list) {
                        if (r.customerID.equals(cID)) {
                            target = r;
                        }
                    }
                }
                if (target == null) {
                    Queue<Reservation> queue = waitlists.get(vID);
                    Reservation waitlisted = null;
                    if (queue != null) {
                        for (Reservation r : queue) {
                            if (r.customerID.equals(cID)) {
                                waitlisted = r;
                                break;
                            }
                        }
                    }
                    if (waitlisted == null) {
                        return "ERROR: No current reservation or waitlist for this vehicle.";
                    }

                    if (!isAvailable(vID, start, end)) {
                        return "UNAVAILABLE: Vehicle not available for updated period.";
                    }

                    double cost = waitlisted.vehicle.price;
                    if (chargeHomeBudget && budgets.getOrDefault(cID, 1000.0) < cost) {
                        return "ERROR: Over budget.";
                    }

                    queue.remove(waitlisted);
                    waitlisted.start = start;
                    waitlisted.end = end;
                    reservations.computeIfAbsent(vID, k -> new ArrayList<>()).add(waitlisted);

                    if (chargeHomeBudget) {
                        budgets.compute(cID, (k, b) -> b - cost);
                        incrementReservationCount(cID, city);
                        return "SUCCESS: updated " + vID;
                    }

                    return "SUCCESS|" + (int) cost;
                } else {
                    list.remove(target);
                    if (isAvailable(vID, start, end)) {
                        target.start = start;
                        target.end = end;
                        list.add(target);
                        return "SUCCESS: updated " + vID;
                    }
                    else {
                        String waitlistKey = cID + "|" + vID + "|" + start + "|" + end;
                        if (!pendingWaitlistConfirm.containsKey(waitlistKey)) {
                            pendingWaitlistConfirm.put(waitlistKey, System.currentTimeMillis());
                            list.add(target);
                            return "UNAVAILABLE: Vehicle not available for updated period.";
                        }

                        pendingWaitlistConfirm.remove(waitlistKey);
                        if (chargeHomeBudget) {
                            final double refund = target.vehicle.price;
                            budgets.compute(cID, (k, b) -> b + refund);
                            decrementReservationCount(cID, city);
                        }
                        waitlists.computeIfAbsent(vID, k -> new ConcurrentLinkedQueue<>())
                                .add(new Reservation(cID, start, end, target.vehicle));
                        promoteWaitlist(vID);
                        return "WAITLISTED: " + vID + " for " + start + "-" + end;
                    }
                }
            }
            finally {
                lock.unlock();
            }
        }
        catch (Exception e) {
            return "Update failed";
        }
    }
    
    @Override
	public String cancelReservation(String cID, String vID) {
        if (!isCustomer(cID)) {
            return "ERROR: Not a customer.";
        }

        String homeOffice = officeOfUser(cID);
        String targetOffice = officeOfVehicle(vID);
        if (!local(vID)) {
            return forwardCancel(targetOffice, cID, vID);
        }

        boolean refundHomeBudget = homeOffice.equalsIgnoreCase(city);

	    ReentrantLock lock = lockFor(vID);
	    lock.lock();

	    try {
	        List<Reservation> list = reservations.get(vID);
	        if (list != null) {
                Iterator<Reservation> it = list.iterator();
                while (it.hasNext()) {
                    Reservation r = it.next();
                    if (r.customerID.equals(cID)) {
                        it.remove();
                        if (refundHomeBudget) {
                            budgets.compute(cID, (k, b) -> b + r.vehicle.price);
                            decrementReservationCount(cID, city);
                            retryWaitlistsForCustomer(cID);
                        }
                        Logger.log(city,"Customer " + cID + " cancelled their reservation for " + vID, "customer");
                        promoteWaitlist(vID);
                        return "SUCCESS|" + (int) r.vehicle.price;
                    }
                }
            }

            // If not found in active reservations, check the waitlist
            Queue<Reservation> queue = waitlists.get(vID);
            if (queue != null) {
                Iterator<Reservation> it = queue.iterator();
                while (it.hasNext()) {
                    Reservation r = it.next();
                    if (r.customerID.equals(cID)) {
                        it.remove();
                        // No budget refund — waitlisted customers were never charged
                        Logger.log(city, "Customer " + cID + " removed from waitlist for " + vID, "customer");
                        return "SUCCESS|0";
                    }
                }
            }

	        return "ERROR: no reservation found.";
	    } finally {
	        lock.unlock();
	    }
	}

	public String findVehicle(String cID, String vtype) {
        if (!cID.endsWith("XXXX")) {
            if (!isCustomer(cID)) {
                return "ERROR: Not a customer.";
            }
        }

	    StringBuilder result = new StringBuilder();
        
        // local search
        result.append(findLocalVehicle(vtype));
        
        if (!city.equals("MTL"))
            result.append(queryRemote("MTL", vtype));

        if (!city.equals("WPG"))
            result.append(queryRemote("WPG", vtype));

        if (!city.equals("BNF"))
            result.append(queryRemote("BNF", vtype));

	    return result.toString();
	}

    public String displayCurrentBudget(String customerID) {
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equalsIgnoreCase(city)) {
            try {
                return getRemoteServer(homeOffice).displayCurrentBudget(customerID);
            } catch (Exception e) {
                return "ERROR: UDP failed to " + homeOffice + " (" + e.getMessage() + ")";
            }
        }

        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        double currentBudget = budgets.getOrDefault(customerID, 1000.0);
        Logger.log(city, "displayCurrentBudget customer=" + customerID + " budget=" + (int) currentBudget, "customer");
        return "Current budget: " + (int) currentBudget;
    }

    public String displayReservations(String customerID) {
        String homeOffice = officeOfUser(customerID);
        if (!homeOffice.equalsIgnoreCase(city)) {
            try {
                return getRemoteServer(homeOffice).displayReservations(customerID);
            } catch (Exception e) {
                return "ERROR: UDP failed to " + homeOffice + " (" + e.getMessage() + ")";
            }
        }

        String accessError = requireHomeCustomer(customerID);
        if (accessError != null) return accessError;

        StringBuilder result = new StringBuilder();
        result.append(displayReservationsLocal(customerID));

        for (String otherCity : Arrays.asList("MTL", "WPG", "BNF")) {
            if (otherCity.equalsIgnoreCase(city)) {
                continue;
            }
            try {
                String reply = getRemoteServer(otherCity).displayReservationsLocal(customerID);
                if (reply != null
                        && !reply.trim().isEmpty()
                        && !"No active reservations.".equals(reply.trim())) {
                    result.append(reply);
                    if (!reply.endsWith("\n")) {
                        result.append('\n');
                    }
                }
            } catch (Exception ignored) {
                // Keep display methods best-effort across replica2 city servers.
            }
        }

        String combined = result.toString().trim();
        Logger.log(city, "displayReservations customer=" + customerID + " combinedLen=" + combined.length(), "customer");
        return combined.isEmpty() ? "No active reservations." : (combined + "\n");
    }

    public String displayReservationsLocal(String customerID) {
        return listReservationsLocal(customerID);
    }

    public String retryCustomerWaitlistsLocal(String customerID) {
        retryWaitlistsLocal(customerID);
        return "SUCCESS";
    }

    private void retryWaitlistsForCustomer(String customerID) {
        retryWaitlistsLocal(customerID);
        for (String otherCity : Arrays.asList("MTL", "WPG", "BNF")) {
            if (otherCity.equalsIgnoreCase(city)) {
                continue;
            }
            try {
                getRemoteServer(otherCity).retryCustomerWaitlistsLocal(customerID);
            } catch (Exception ignored) {
                // Best-effort trigger only.
            }
        }
    }

    private void retryWaitlistsLocal(String customerID) {
        List<String> vehicleIds = new ArrayList<>();
        for (Map.Entry<String, Queue<Reservation>> entry : waitlists.entrySet()) {
            for (Reservation reservation : entry.getValue()) {
                if (reservation.customerID.equals(customerID)) {
                    vehicleIds.add(entry.getKey());
                    break;
                }
            }
        }
        for (String vehicleID : vehicleIds) {
            promoteWaitlist(vehicleID);
        }
    }

    private String listReservationsLocal(String customerID) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<Reservation>> entry : reservations.entrySet()) {
            for (Reservation reservation : entry.getValue()) {
                if (reservation.customerID.equals(customerID)) {
                    result.append(reservation.vehicle.vehicleID)
                            .append(" from ")
                            .append(formatDate(reservation.start))
                            .append(" to ")
                            .append(formatDate(reservation.end))
                            .append("\n");
                }
            }
        }
        return result.toString();
    }

    private String formatDate(String value) {
        return LocalDate.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
	
    public String findLocalVehicle(String vtype) {
        StringBuilder result = new StringBuilder();

        vehicles.values().stream()
                .filter(v -> v.vehicleType.equalsIgnoreCase(vtype))
                .forEach(v -> result.append(v.vehicleID).append(" ")
                        .append(v.vehicleType).append(" ")
                        .append(reservedCountForToday(v.vehicleID) == 0 ? "Available" : "Reserved").append(" ")
                        .append((int) v.price).append("\n"));

        return result.toString();
    }
    
    private void promoteWaitlist(String vID) {
        Queue<Reservation> queue = waitlists.get(vID);

        if (queue == null || queue.isEmpty())
            return;

        Iterator<Reservation> it = queue.iterator();
        while (it.hasNext()) {
            Reservation next = it.next();
            if (isAvailable(vID, next.start, next.end)
                    && budgets.getOrDefault(next.customerID, 0.0) >= next.vehicle.price) {
                it.remove();
                reservations.computeIfAbsent(vID, k -> new CopyOnWriteArrayList<>()).add(next);
                budgets.compute(next.customerID, (k, b) -> b - next.vehicle.price);
                incrementReservationCount(next.customerID, city);
                Logger.log(city, "Customer " + next.customerID + " promoted from waitlist for " + vID
                        + " from " + next.start + " to " + next.end, "customer");
                break;
            }
        }
    }

    private int reservedCountForToday(String vID) {
        LocalDate today = LocalDate.now();
        int count = 0;
        List<Reservation> list = reservations.get(vID);
        if (list == null) {
            return 0;
        }
        for (Reservation r : list) {
            LocalDate start = LocalDate.parse(r.start);
            LocalDate end = LocalDate.parse(r.end);
            if (!(today.isBefore(start) || today.isAfter(end))) {
                count++;
            }
        }
        return count;
    }
    
    private boolean isAvailable(String vID, String s, String e) {
        List<Reservation> list = reservations.get(vID);
        if (list == null)
            return true;

        LocalDate start1 = LocalDate.parse(s);
        LocalDate end1 = LocalDate.parse(e);

        for (Reservation r : list) {
            LocalDate start2 = LocalDate.parse(r.start);
            LocalDate end2 = LocalDate.parse(r.end);
            // Use inclusive overlap semantics so a reservation that starts on
            // the same day another reservation ends is still considered overlapping.
            if (!(end1.isBefore(start2) || start1.isAfter(end2))) {
                return false;
            }
        }
        return true;
    }

    // ================= CROSS SERVER =================

    private String forwardReserve(String targetCity, String cID, String vID, String start, String end) {
        try {
            String reply = getRemoteServer(targetCity).reserveVehicle(cID, vID, start, end);
            if (!reply.startsWith("SUCCESS|")) {
                return reply;
            }

            double cost = Double.parseDouble(reply.split("\\|")[1]);
            double currentBudget = budgets.getOrDefault(cID, 1000.0);
            if (currentBudget < cost) {
                getRemoteServer(targetCity).cancelReservation(cID, vID);
                return "ERROR: Over budget (need " + (int) cost + ", have " + (int) currentBudget + ")";
            }

            budgets.put(cID, currentBudget - cost);
            incrementReservationCount(cID, targetCity);
            return "SUCCESS: reserved " + vID + " cost=" + (int) cost + " remainingBudget=" + (int) (currentBudget - cost);
        } catch (Exception e) {
            return "ERROR: UDP failed to " + targetCity + " (" + e.getMessage() + ")";
        }
    }
    
    private String forwardUpdate(String targetCity, String cID, String vID, String start, String end) {
        try {
            String reply = getRemoteServer(targetCity).updateReservation(cID, vID, start, end);
            if (reply.startsWith("SUCCESS|")) {
                double cost = Double.parseDouble(reply.split("\\|")[1]);
                double currentBudget = budgets.getOrDefault(cID, 1000.0);
                if (currentBudget < cost) {
                    getRemoteServer(targetCity).cancelReservation(cID, vID);
                    return "ERROR: Over budget (need " + (int) cost + ", have " + (int) currentBudget + ")";
                }

                budgets.put(cID, currentBudget - cost);
                incrementReservationCount(cID, targetCity);
                return "SUCCESS: updated " + vID;
            }
            return reply;
        } catch (Exception e) {
            return "ERROR: UDP failed to " + targetCity + " (" + e.getMessage() + ")";
        }
    }

    private String forwardCancel(String targetCity, String cID, String vID) {
        try {
            String reply = getRemoteServer(targetCity).cancelReservation(cID, vID);
            if (!reply.startsWith("SUCCESS|")) {
                return reply;
            }

            int refund = Integer.parseInt(reply.split("\\|")[1]);
            if (refund > 0) {
                budgets.compute(cID, (k, b) -> (b == null ? 1000.0 : b) + refund);
                decrementReservationCount(cID, targetCity);
                retryWaitlistsForCustomer(cID);
            }
            return refund > 0
                    ? "SUCCESS: cancelled " + vID + " refund=" + refund
                    : "SUCCESS: cancelled waitlist for " + vID;
        } catch (Exception e) {
            return "ERROR: UDP failed to " + targetCity + " (" + e.getMessage() + ")";
        }
    }
	
	private String queryRemote(String city, String vtype) {
	    try {
	        return getRemoteServer(city).findVehicle(city + "UXXXX", vtype);
	    } catch (Exception e) {
	        return "";
	    }
	}

    // Host running the CORBA Name Service. Set via -Dorb.host=<ip> at launch.
    // Defaults to "localhost" for single-machine testing.
    private static final String ORB_HOST = System.getProperty("orb.host", "localhost");
 
    private DVRMS getRemoteServer(String targetCity) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", ORB_HOST);
        props.put("org.omg.CORBA.ORBInitialPort", "1050");
        ORB orb = ORB.init(new String[]{}, props);
        NamingContextExt nc = NamingContextExtHelper.narrow(
                orb.resolve_initial_references("NameService"));
        return DVRMSHelper.narrow(nc.resolve_str(targetCity + "_" + replicaId));
    }

    /**
     * Usage:
     *   java [-Dorb.host=<ns-ip>] [-Dlisten.port=<port>] \
     *        VehicleServer <city> <replicaId> <rmHost> <rmPort>
     *
     * Arguments:
     *   city       City code: MTL, WPG, or BNF
     *   replicaId  Integer 1-4 (replica group index; one per host)
     *   rmHost     IP/hostname of the Replica Manager for this replica
     *   rmPort     RM listen port: 7000 + replicaId (e.g. 7001 for RM1)
     *
     * JVM system properties:
     *   -Dorb.host=<ip>      Host running orbd/tnameserv (default: localhost)
     *   -Dlisten.port=<n>    Override computed UDP listen port (optional)
     *
     * Port formula (city offset + replicaId):
     *   MTL: 6001-6004   WPG: 6011-6014   BNF: 6021-6024
     *
     * Single-machine test:
     *   java VehicleServer MTL 1 localhost 7001
     *   java VehicleServer WPG 1 localhost 7001
     *   java VehicleServer BNF 1 localhost 7001
     *
     * Multi-host, 3 cities per host (host-a runs all city replicas for replicaId=1):
     *   java -Dorb.host=ns-ip VehicleServer MTL 1 rm1-ip 7001
     *   java -Dorb.host=ns-ip VehicleServer WPG 1 rm1-ip 7001
     *   java -Dorb.host=ns-ip VehicleServer BNF 1 rm1-ip 7001
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: VehicleServer <city> <replicaId> <rmHost> <rmPort>");
            System.exit(1);
        }
 
        String city      = args[0];
        int    replicaId = Integer.parseInt(args[1]);
        String rmHost    = args[2];
        int    rmPort    = Integer.parseInt(args[3]);
 
        // Port formula: city offset + replicaId
        //   MTL replicas: 6001, 6002, 6003, 6004
        //   WPG replicas: 6011, 6012, 6013, 6014
        //   BNF replicas: 6021, 6022, 6023, 6024
        //
        // This allows 3 city servers to run on the same host without port collisions.
        // Can be overridden by passing -Dlisten.port=<port> for non-standard deployments.
        int cityOffset;
        switch (city.toUpperCase()) {
            case "MTL": cityOffset = 6000; break;
            case "WPG": cityOffset = 6010; break;
            case "BNF": cityOffset = 6020; break;
            default:
                System.err.println("Unknown city: " + city + ". Must be MTL, WPG, or BNF.");
                System.exit(1);
                cityOffset = 0; // unreachable, keeps compiler happy
        }
        int listenPort = Integer.getInteger("listen.port", cityOffset + replicaId);
 
        // CORBA boilerplate.
        // Pass -Dorb.host=<nameservice-ip> to point at a remote Name Service.
        String orbHost = System.getProperty("orb.host", "localhost");
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", orbHost);
        props.put("org.omg.CORBA.ORBInitialPort", "1050");
        ORB orb = ORB.init(args, props);
 
        org.omg.CORBA.Object poaObj = orb.resolve_initial_references("RootPOA");
        org.omg.PortableServer.POA poa =
                org.omg.PortableServer.POAHelper.narrow(poaObj);
        poa.the_POAManager().activate();
 
        VehicleServer server = new VehicleServer(city, replicaId, listenPort, rmHost, rmPort);
 
        org.omg.CORBA.Object ref = poa.servant_to_reference(server);
        NamingContextExt nc = NamingContextExtHelper.narrow(
                orb.resolve_initial_references("NameService"));
 
        // Bound as "<city>_<replicaId>" so multiple replicas coexist in NameService
        String bindName = city + "_" + replicaId;
        nc.rebind(nc.to_name(bindName), ref);
        System.out.println("[Replica " + replicaId + "] Bound in NameService as " + bindName);
 
        server.startReplicaListener();
        server.registerWithRM();
 
        orb.run();
    }
}
