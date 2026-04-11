package com.dvrms.replica2;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

import java.util.Scanner;

public class Client {

	private static DVRMS connect(String city) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("org.omg.CORBA.ORBInitialHost", "localhost");
        props.put("org.omg.CORBA.ORBInitialPort", "1050");

        ORB orb = ORB.init(new String[]{}, props);
        org.omg.CORBA.Object objRef = orb.resolve_initial_references("NameService");
        NamingContextExt ncRef = NamingContextExtHelper.narrow(objRef);
        return DVRMSHelper.narrow(ncRef.resolve_str(city));

	}
	public static void main(String[] args) {
		
		try {
			Scanner scanner = new Scanner(System.in);
			System.out.print("Enter User ID (e.g., MTLM1111 or MTLU1111): ");
			String userID = scanner.nextLine().trim();
			
			// Extract Server Name from ID (first 3 chars)
			String serverName = userID.substring(0, 3).toUpperCase();
			String userType = userID.substring(3, 4).toUpperCase(); // M for Manager, U for User (client)
			
			DVRMS server = connect(serverName);
			
			System.out.println("Connected to " + serverName + " Server.");
			
			boolean running = true;
			while (running) {
				
				
				if (userType.equals("M")) {
					System.out.print("\nWhat do you want to do?\n"
							+ "1. Add Vehicle\n"
        					+ "2. Remove Vehicle\n"
        					+ "3. List Available Vehicles\n"
        					+ "4. Exit\n");
					int choice = Integer.parseInt(scanner.nextLine());
					switch(choice) {
					case 1:
                            System.out.print("Enter Vehicle ID, Type, License Plate, Price: ");
                            // Simplifying input parsing for the demo
                            String[] inputs = scanner.nextLine().split(" ");
                            System.out.println(server.addVehicle(userID, inputs[2], inputs[1], inputs[0], Double.parseDouble(inputs[3])));
                            break;
                        case 2:
                            System.out.print("Enter Vehicle ID: ");
                            System.out.println(server.removeVehicle(userID, scanner.nextLine()));
                            break;
                        case 3:
                            System.out.println(server.listAvailableVehicle(userID));
                            break;
                        case 4: running = false; break;
                    }
                }
                else if (userType.equals("U")) {
                	System.out.print("\nWhat do you want to do?\n"
        					+ "1. Reserve Vehicle\n"
        					+ "2. Update Reservation\n"
        					+ "3. Cancel Reservation\n"
        					+ "4. Find Vehicle\n"
        					+ "5. Exit\n");
                    int choice = Integer.parseInt(scanner.nextLine());
                    switch(choice) {
                        case 1:
                        	System.out.print("Enter Vehicle ID: ");
                            String vIDreserve = scanner.nextLine();
                            
                            System.out.print("Enter start date: ");
                            String startReservation = scanner.nextLine();
                            
                            System.out.print("Enter end date: ");
                            String endReservation = scanner.nextLine();
                            
                            String response = server.reserveVehicle(userID, vIDreserve, startReservation, endReservation);
                            if(response.equals("Vehicle not in stock, because it is reserved for some time during that time period")) {
                            	System.out.println(response + "\nWould you like to be waitlisted ?");
                            	String ans = scanner.next();
                            	if(!ans.toLowerCase().equals("yes")) {
                                	System.out.println(server.cancelReservation(userID, vIDreserve));
                            		scanner.nextLine();
                            	}
                            	else {
                            		scanner.nextLine();
                                	System.out.println("You are now in the wailist for the vehicle.");
                            	}
                            }
                            else {
                            	System.out.println(response);
                            }
                            
                            break;
                        case 2:
                        	System.out.print("Enter Vehicle ID: ");
                            String vIDupdate = scanner.nextLine();
                            
                            System.out.print("Enter start date: ");
                            String startUpdate = scanner.nextLine();
                            
                            System.out.print("Enter end date: ");
                            String endUpdate = scanner.nextLine();
                            
                            System.out.println(server.updateReservation(userID, vIDupdate, startUpdate, endUpdate));
                            break;
                        case 3:
                        	System.out.print("Enter Vehicle ID: ");
                            String vID = scanner.nextLine();
                            System.out.println(server.cancelReservation(userID, vID));
                            break;
                        case 4:
                        	System.out.print("Enter Vehicle Type: ");
                        	System.out.println(server.findVehicle(userID, scanner.nextLine()));
                            break;
                        case 5: running = false; break;
                    }
                }
			}
			scanner.close();
		}
		catch (Exception e) {
        	e.printStackTrace();
        }
		
		System.out.print("Exiting program");
	}
}