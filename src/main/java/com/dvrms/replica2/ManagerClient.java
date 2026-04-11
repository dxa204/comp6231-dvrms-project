package com.dvrms.replica2;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

public class ManagerClient {
    private static DVRMS connect(String city) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", "localhost");
        props.put("org.omg.CORBA.ORBInitialPort", "1050");
        
        ORB orb = ORB.init(new String[]{}, props);
        org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
        NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
        return DVRMSHelper.narrow(ncRef.resolve_str(city));
    }
    public static void main(String[] args)throws Exception{
        DVRMS managerMTL = connect("MTL");
        DVRMS managerWPG = connect("WPG");
        DVRMS managerBNF = connect("BNF");

        System.out.println(managerMTL.addVehicle("MTLM1111", "AAA 111", "Sedan", "MTL1001", 120));
        System.out.println(managerMTL.addVehicle("MTLM1111", "DEF 456", "SUV", "MTL1002", 200));
        System.out.println(managerMTL.addVehicle("MTLM1111", "DEF 789", "SUV", "MTL1003", 200));
        System.out.println(managerMTL.addVehicle("MTLM1111", "GHI 654", "Truck", "MTL1004", 250));
        System.out.println(managerMTL.addVehicle("MTLM1111", "JKL 012", "Sedan", "MTL1005", 130));
        System.out.println(managerMTL.addVehicle("MTLM1111", "MNO 345", "SUV", "MTL1006", 210));
        
        System.out.println(managerWPG.addVehicle("WPGM2222", "PQR 678", "Sedan", "WPG2001", 110));
        System.out.println(managerWPG.addVehicle("WPGM2222", "STU 901", "Truck", "WPG2002", 240));
        System.out.println(managerWPG.addVehicle("WPGM2222", "VWX2 34", "SUV", "WPG2003", 190));
        System.out.println(managerWPG.addVehicle("WPGM2222", "YZA 567", "Sedan", "WPG2004", 125));
        System.out.println(managerWPG.addVehicle("WPGM2222", "BCD 890", "Truck", "WPG2005", 260));
        
        System.out.println(managerBNF.addVehicle("BNFM3333", "EFG 123", "SUV", "BNF3001", 220));
        System.out.println(managerBNF.addVehicle("BNFM3333", "HIJ 456", "Sedan", "BNF3002", 130));
        System.out.println(managerBNF.addVehicle("BNFM3333", "KLM 789", "Truck", "BNF3003", 270));
        System.out.println(managerBNF.addVehicle("BNFM3333", "NOP 012", "SUV", "BNF3004", 215));
        System.out.println(managerBNF.addVehicle("BNFM3333", "QRS 345", "Sedan", "BNF3005", 140));


        System.out.println();
        System.out.println(managerMTL.listAvailableVehicle("MTLM1111"));
        System.out.println(managerWPG.listAvailableVehicle("WPGM2222"));
        System.out.println(managerBNF.listAvailableVehicle("BNFM3333"));
        
        
        // other test cases for manager role
        System.out.println(managerMTL.removeVehicle("MTLM1111", "MTL1001"));
        System.out.println(managerMTL.removeVehicle("MTLM1111", "MTL1009"));
        System.out.println(managerMTL.listAvailableVehicle("MTLM1111"));

        System.out.println(managerMTL.reserveVehicle("MTLM1111", "MTL1002", "2026-02-11", "2026-02-12"));
    }
}