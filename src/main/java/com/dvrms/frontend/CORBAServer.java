package com.dvrms.frontend;

import org.omg.CORBA.*;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.*;

public class CORBAServer {
    public static void main(String[] args) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.put("org.omg.CORBA.ORBInitialHost", System.getProperty("orb.host", "localhost"));
            props.put("org.omg.CORBA.ORBInitialPort", System.getProperty("orb.port", "1050"));
            ORB orb = ORB.init(args, props);

            POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
            rootPOA.the_POAManager().activate();

            FrontEndImpl fe = new FrontEndImpl();
            org.omg.CORBA.Object ref = rootPOA.servant_to_reference(fe);
            org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
            NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
            NameComponent[] path = ncRef.to_name("FrontEnd");
            ncRef.rebind(path, ref);

            System.out.println("Front-End is running and bound as FrontEnd");

            orb.run();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
