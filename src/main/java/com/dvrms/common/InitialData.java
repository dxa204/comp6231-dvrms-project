package com.dvrms.common;

import java.util.HashMap;
import java.util.Map;

public class InitialData {

    public static Map<String, VehicleRecord> getMTLData() {
        Map<String, VehicleRecord> data = new HashMap<>();
        data.put("MTL3012", new VehicleRecord("MTL3012", "Sedan", 2, 120));
        data.put("MTL3025", new VehicleRecord("MTL3025", "SUV", 1, 200));
        data.put("MTL3077", new VehicleRecord("MTL3077", "Truck", 3, 250));
        return data;
    }

    public static Map<String, VehicleRecord> getWPGData() {
        Map<String, VehicleRecord> data = new HashMap<>();
        data.put("WPG4421", new VehicleRecord("WPG4421", "Sedan", 1, 110));
        data.put("WPG4401", new VehicleRecord("WPG4401", "SUV", 3, 210));
        data.put("WPG4501", new VehicleRecord("WPG4501", "Truck", 4, 300));
        return data;
    }

    public static Map<String, VehicleRecord> getBNFData() {
        Map<String, VehicleRecord> data = new HashMap<>();
        data.put("BNF5534", new VehicleRecord("BNF5534", "Sedan", 1, 130));
        data.put("BNF5204", new VehicleRecord("BNF5204", "SUV", 2, 150));
        data.put("BNF5567", new VehicleRecord("BNF5567", "Truck", 1, 450));
        return data;
    }
}