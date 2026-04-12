import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class RentalOfficeUDPServer implements Runnable {

    private final String officeID;
    private final int udpPort;
    private final RentalOfficeImpl office; // concrete servant (same JVM)

    public RentalOfficeUDPServer(String officeID, int udpPort, RentalOfficeImpl office) {
        this.officeID = officeID;
        this.udpPort = udpPort;
        this.office = office; // safe cast: same JVM
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket(udpPort)) {

            System.out.println(
                    "Rental Office UDP Server [" + officeID + "] listening on port " + udpPort
            );

            while (true) {
                // Allocate buffer per request (prevents stale data issues)
                byte[] buffer = new byte[4096];

                DatagramPacket requestPacket =
                        new DatagramPacket(buffer, buffer.length);
                socket.receive(requestPacket);

                String request =
                        new String(
                                requestPacket.getData(),
                                0,
                                requestPacket.getLength(),
                                StandardCharsets.UTF_8
                        ).trim();

                String response = dispatchRequest(request);

                byte[] replyData = response.getBytes(StandardCharsets.UTF_8);
                DatagramPacket replyPacket =
                        new DatagramPacket(
                                replyData,
                                replyData.length,
                                requestPacket.getAddress(),
                                requestPacket.getPort()
                        );

                socket.send(replyPacket);
            }

        } catch (SocketException e) {
            System.err.println("UDP socket error at office " + officeID);
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("UDP server failure at office " + officeID);
            e.printStackTrace();
        }
    }

    /**
     * Centralized dispatcher for UDP requests.
     * Keeps protocol logic clean and easy to extend.
     */
    private String dispatchRequest(String message) {

        if (message == null || message.isEmpty()) {
            return "Error: Empty request";
        }

        try {
            if (message.startsWith("FIND;")) {
                return office.handleUDPFindRequest(message);
            }

            if (message.startsWith("RESERVE;")) {
                return office.handleUDPReservationRequest(message);
            }

            if (message.startsWith("WAITLIST;")) {
                return office.handleUDPWaitlistRequest(message);
            }

            if (message.startsWith("CANCEL;")) {
                return office.handleUDPCancellationRequest(message);
            }

            if (message.startsWith("UPDATE;")) {
                return office.handleUDPUpdateRequest(message);
            }

            if (message.startsWith("FORCE_CANCEL;")) {
                return office.handleUDPForceCancellation(message);
            }

            if (message.startsWith("AUTO-RESERVE;")) {
                return office.handleUDPAutoReservation(message);
            }

            return "Error: Unknown request type [" + message + "]";

        } catch (Exception e) {
            // Never crash the UDP loop
            return "Error processing request: " + e.getMessage();
        }
    }
}
