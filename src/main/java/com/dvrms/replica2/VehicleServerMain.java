import org.omg.CORBA.ORB;
import org.omg.CosNaming.*;

public class VehicleServerMain {

    /**
     * Usage:
     *   java VehicleServer <city> <replicaId> <rmHost> <rmPort>
     *
     * listenPort is derived from replicaId: 6000 + replicaId
     * (Replica 1 → 6001, Replica 2 → 6002, etc.)
     *
     * Example — Replica 1 serving MTL:
     *   java VehicleServer MTL 1 localhost 7001
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
 
        // CORBA boilerplate
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", "localhost");
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