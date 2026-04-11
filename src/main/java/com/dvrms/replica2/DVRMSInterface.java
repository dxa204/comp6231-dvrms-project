package com.dvrms.replica2;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.time.LocalDate;

public interface DVRMSInterface extends Remote {

    String addVehicle(String managerID, String vehicleNumber, String vehicleType, String vehicleID, double price) throws RemoteException;

    String removeVehicle(String managerID, String vehicleID) throws RemoteException;

    String listAvailableVehicle(String managerID) throws RemoteException;

    String reserveVehicle(String customerID, String vehicleID, LocalDate start, LocalDate end) throws RemoteException;

    String updateReservation(String customerID, String vehicleID, LocalDate start, LocalDate end) throws RemoteException;

    String cancelReservation(String customerID, String vehicleID) throws RemoteException;

    String findVehicle(String customerID, String type) throws RemoteException;
}
