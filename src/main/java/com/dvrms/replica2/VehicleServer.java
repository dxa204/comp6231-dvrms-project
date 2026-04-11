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
        System.out.println("[Replica " + replicaId + "] " + city + " ready on port " + listenPort);
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
 
                    // ACK|<msgID> back to Sequencer immediately
                    byte[] ack = ("ACK|" + msgID).getBytes();
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
                case "addVehicle":
                    // args: mID vnum vtype vID price
                    result = addVehicle(parts[6], parts[7], parts[8], parts[9],
                                        Double.parseDouble(parts[10]));
                    break;
                case "removeVehicle":
                    // args: mID vID
                    result = removeVehicle(parts[6], parts[7]);
                    break;
                case "listVehicles":
                    // args: mID
                    result = listAvailableVehicle(parts[6]);
                    break;
                case "reserve":
                    // args: cID vID start end
                    result = reserveVehicle(parts[6], parts[7], parts[8], parts[9]);
                    break;
                case "update":
                    // args: cID vID newStart newEnd
                    result = updateReservation(parts[6], parts[7], parts[8], parts[9]);
                    break;
                case "cancel":
                    // args: cID vID
                    result = cancelReservation(parts[6], parts[7]);
                    break;
                case "findVehicle":
                    // args: cID vtype
                    result = findVehicle(parts[6], parts[7]);
                    break;
                default:
                    result = "Unknown method: " + method;
            }
        } catch (Exception e) {
            result = "Execution error: " + e.getMessage();
        }
 
        // Send RESULT|<msgID>|<replicaID>|<resultString> to FE
        try {
            sendUDP(feHost, fePort, "RESULT|" + msgID + "|" + replicaId + "|" + result);
            Logger.log(city, "[R" + replicaId + "] " + method + " msgID=" + msgID + " → " + result, "replica");
        } catch (Exception e) {
            System.err.println("[Replica " + replicaId + "] Failed to send RESULT to FE: " + e.getMessage());
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

    // ================= MANAGER =================

    public String addVehicle(String mID, String vnum, String vtype, String vID, double price) {
        if (!mID.toLowerCase().startsWith(city.toLowerCase()) || mID.toLowerCase().charAt(3) != 'm')
            return "Invalid city or not a manager";

        if (!vID.toLowerCase().startsWith(city.toLowerCase()))
            return "Invalid city for this vehicle";

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            
            boolean isUpdate = vehicles.containsKey(vID);
            vehicles.put(vID, new Vehicle(vID, vtype, vnum, price));
 
            if (isUpdate) {
                Logger.log(city, mID + " updated vehicle " + vtype + " with license plate " + vnum
                        + " and vehicle ID " + vID + " and with a cost of " + price, "manager");
                return "Vehicle updated";
            } else {
                Logger.log(city, mID + " added vehicle " + vtype + " with license plate " + vnum
                        + " and vehicle ID " + vID + " and with a cost of " + price, "manager");
                return "Vehicle added";
            }
        } finally {
            lock.unlock();
        }
    }

    public String removeVehicle(String mID, String vID) {
        if (!mID.toLowerCase().startsWith(city.toLowerCase()) || mID.toLowerCase().charAt(3) != 'm')
            return "Invalid city or not a manager";

        if (!vID.toLowerCase().startsWith(city.toLowerCase()))
            return "Invalid city for this vehicle";

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            if(!vehicles.containsKey(vID) && !reservations.containsKey(vID)) {
                return "Vehicle does not exist";
            }
            vehicles.remove(vID);

            // Refund all customers who had active reservations for this vehicle
            List<Reservation> activeReservations = reservations.remove(vID);
            if (activeReservations != null) {
                for (Reservation r : activeReservations) {
                    budgets.compute(r.customerID, (k, b) -> (b == null ? 0.0 : b) + r.vehicle.price);
                    Logger.log(city, "Refunded customer " + r.customerID + " for removed vehicle " + vID, "manager");
                }
            }
 
            // Clear the waitlist for this vehicle
            Queue<Reservation> wl = waitlists.remove(vID);
            if (wl != null && !wl.isEmpty()) {
                Logger.log(city, "Cleared waitlist for removed vehicle " + vID, "manager");
            }

	        Logger.log(city, mID + " removed vehicle with vehicle ID " + vID, "manager");
            return "Vehicle removed";
        } finally {
            lock.unlock();
        }
    }

    public String listAvailableVehicle(String mID) {
        if (!mID.toLowerCase().startsWith(city.toLowerCase()) || mID.toLowerCase().charAt(3) != 'm')
            return "Invalid city or not a manager";

        StringBuilder sb = new StringBuilder();
        vehicles.values().forEach(v -> sb.append(v.vehicleID).append(" ")
                .append(v.vehicleType).append(" ").append(v.vehicleNumber).append(" ").append(v.price).append("\n"));
        return sb.toString();
    }

    // ================= CUSTOMER =================

    public String reserveVehicle(String cID, String vID, String start, String end) {
        if (cID.toLowerCase().charAt(3) != 'u')
            return "Not a user";

        budgets.putIfAbsent(cID, 1000.0);

        if (!local(vID))
            return forwardReserve(cID, vID, start, end);

        ReentrantLock lock = lockFor(vID);
        lock.lock();
        try {
            Vehicle v = vehicles.get(vID);
            
            if (v == null)
                return "Vehicle does not exist";

            double cost = v.price;
            if (budgets.get(cID) < cost)
                return "Insufficient budget";
            
            if (!isAvailable(vID, start, end)) {
                waitlists.computeIfAbsent(vID, k -> new ConcurrentLinkedQueue<>()).add(new Reservation(cID, start, end, v));
                Logger.log(city, "Customer " + cID + " added to waitlist for " + vID, "customer");
                return "Vehicle not in stock, because it is reserved for some time during that time period";
            }

            reservations.computeIfAbsent(vID, k -> new ArrayList<>()).add(new Reservation(cID, start, end, v));

            budgets.compute(cID, (k, b) -> b - cost);

            Logger.log(city,"Customer " + cID + " reserved " + vID + " from " + start + " to " + end, "customer");
            
            return "Reserved";
        } finally {
            lock.unlock();
        }
    }

    public String updateReservation(String cID, String vID, String start, String end) {
        if (cID.toLowerCase().charAt(3) != 'u')
            return "Not a user";
        
        if (!local(vID))
            return forwardUpdate(cID, vID, start, end);
        try {
            ReentrantLock lock = lockFor(vID);
            lock.lock();
            
            try {
                List<Reservation> list = reservations.get(vID);
                if (list == null) {
                    return "No reservation";
                }
                else {
                    Reservation target = null;
                    for (Reservation r : list)
                        if (r.customerID.equals(cID))
                            target = r;
                    if (target == null) {
                        Queue<Reservation> queue = waitlists.get(vID);
                        boolean foundInWaitlist = false;
                        if (queue != null) {
                            for (Reservation r : queue) {
                                if (r.customerID.equals(cID)) {
                                    r.start = start;
                                    r.end = end;
                                    foundInWaitlist = true;
                                    break;
                                }
                            }
                        }
                        return foundInWaitlist ? "Waitlist entry updated" : "No reservation or waitlist entry found";
                    }
                    else {
                        list.remove(target);
                        if (isAvailable(vID, start, end)) {
                            target.start = start;
                            target.end = end;
                            list.add(target);
                            return "Updated";
                        }
                        else {
                            list.add(target);
                            return "Update failed: new dates conflict with an existing reservation";
                        }
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
        if (cID.toLowerCase().charAt(3) != 'u')
            return "Not a user";

	    if (!local(vID))
	        return forwardCancel(cID, vID);

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
                        budgets.compute(cID, (k, b) -> b + r.vehicle.price);
                        Logger.log(city,"Customer " + cID + " cancelled their reservation for " + vID, "customer");
                        promoteWaitlist(vID);
                        return "Cancelled";
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
                        return "Removed from waitlist";
                    }
                }
            }

	        return "No reservation or waitlist entry found";
	    } finally {
	        lock.unlock();
	    }
	}

	public String findVehicle(String cID, String vtype) {
        if (cID.toLowerCase().charAt(3) != 'u')
            return "Not a user";

	    StringBuilder result = new StringBuilder();
        
        // local search
        result.append(findLocalVehicle(vtype));
        
        if (!cID.substring(4).equals("XXXX")) {
            if (!city.equals("MTL"))
                result.append(queryRemote("MTL", vtype));
 
            if (!city.equals("WPG"))
                result.append(queryRemote("WPG", vtype));
 
            if (!city.equals("BNF"))
                result.append(queryRemote("BNF", vtype));
        }

	    return result.toString();
	}
	
    public String findLocalVehicle(String vtype) {
        StringBuilder result = new StringBuilder();

        vehicles.values().stream()
                .filter(v -> v.vehicleType.equalsIgnoreCase(vtype))
                .forEach(v -> result.append(v.vehicleID).append(" "));

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
                Logger.log(city, "Customer " + next.customerID + " promoted from waitlist for " + vID
                        + " from " + next.start + " to " + next.end, "customer");
            }
        }
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
            if (start1.isBefore(end2) && start2.isBefore(end1)) {
                return false;
            }
        }
        return true;
    }

    // ================= CROSS SERVER =================

    private String forwardReserve(String cID, String vID, String start, String end) {
        try {
            return getRemoteServer(city).reserveVehicle(cID, vID, start, end);
        } catch (Exception e) {
            return "Forward reserve failed";
        }
    }
    
    private String forwardUpdate(String cID, String vID, String start, String end) {
        try {
            return getRemoteServer(city).updateReservation(cID, vID, start, end);
        } catch (Exception e) {
            return "Forward update failed";
        }
    }

    private String forwardCancel(String cID, String vID) {
        try {
            return getRemoteServer(city).cancelReservation(cID, vID);
        } catch (Exception e) {
            return "Forward cancel failed";
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
        return DVRMSHelper.narrow(nc.resolve_str(targetCity));
    }

    /**
     * Usage:
     *   java -Dorb.host=<nameservice-ip> VehicleServer <city> <replicaId> <rmHost> <rmPort>
     *
     * Arguments:
     *   city       City code this replica serves: MTL, WPG, or BNF
     *   replicaId  Integer 1-4 (determines UDP listen port: 6000 + replicaId)
     *   rmHost     IP/hostname of the Replica Manager for this replica
     *   rmPort     RM listen port: 7000 + replicaId (e.g. 7001 for RM1)
     *
     * JVM system property:
     *   -Dorb.host=<ip>   Host running orbd/tnameserv (default: localhost)
     *
     * Single-machine test (all on localhost):
     *   java VehicleServer MTL 1 localhost 7001
     *
     * Multi-host: Replica 1 on host-a, NameService on host-ns, RM1 on host-rm1:
     *   java -Dorb.host=host-ns VehicleServer MTL 1 host-rm1 7001
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: VehicleServer <city> <replicaId> <rmHost> <rmPort>");
            System.exit(1);
        }
 
        String city = args[0];
        int replicaId = Integer.parseInt(args[1]);
        String rmHost = args[2];
        int rmPort = Integer.parseInt(args[3]);
 
        // listenPort is fixed by the team protocol doc: 6000 + replicaId
        int listenPort = 6000 + replicaId;
 
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