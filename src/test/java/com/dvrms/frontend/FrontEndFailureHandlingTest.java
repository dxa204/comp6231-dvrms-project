package com.dvrms.frontend;

import com.dvrms.common.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Self-checking executable harness for FE mixed failure handling.
 *
 * Scenario:
 *   - R1 and R2 return the majority result
 *   - R3 returns an incorrect result
 *   - R4 never responds
 *
 * Expected:
 *   - FE returns the majority result immediately
 *   - FE reports FAULT for R3 once the third fault threshold is hit
 *   - FE later reports CRASH for R4 after the reply deadline expires
 */
public class FrontEndFailureHandlingTest {

    public static void main(String[] args) throws Exception {
        new FrontEndFailureHandlingTest().run();
    }

    private void run() throws Exception {
        List<MockRM> rms = new ArrayList<>();
        try {
            for (int rmId = 1; rmId <= 4; rmId++) {
                MockRM rm = new MockRM(Config.rmPort(rmId));
                rm.start();
                rms.add(rm);
            }

            FrontEndImpl frontEnd = new FrontEndImpl();

            String requestId = "REQ-MIXED-FAILURE";
            seedRequest(frontEnd, requestId);
            seedFaultCount(frontEnd, "R3", 2);

            Thread injector = new Thread(() -> injectResponses(frontEnd, requestId), "fe-mixed-failure-injector");
            injector.start();

            String result = invokeWaitForMajority(frontEnd, requestId);
            if (!"SUCCESS".equals(result)) {
                throw new AssertionError("Expected majority result SUCCESS, but got: " + result);
            }

            waitUntil(() -> allRmsSaw(rms, "FAULT|R3|" + requestId), 2000,
                    "Expected FE to report FAULT for R3 to all RMs");

            long timeout = Config.ACK_TIMEOUT_MS * Config.MAX_RETRIES * 2L;
            waitUntil(() -> allRmsSaw(rms, "CRASH|R4"), timeout + 1500,
                    "Expected FE to report CRASH for R4 to all RMs");

            System.out.println("PASS: FE handled mixed software + crash failure in one request");
        } finally {
            for (MockRM rm : rms) {
                rm.stop();
            }
        }
    }

    private void seedRequest(FrontEndImpl frontEnd, String requestId) throws Exception {
        Field responseMapField = FrontEndImpl.class.getDeclaredField("responseMap");
        responseMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<Response>> responseMap = (Map<String, List<Response>>) responseMapField.get(frontEnd);
        responseMap.put(requestId, new CopyOnWriteArrayList<Response>());

        Field requestStartTimeField = FrontEndImpl.class.getDeclaredField("requestStartTime");
        requestStartTimeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> requestStartTime = (Map<String, Long>) requestStartTimeField.get(frontEnd);
        requestStartTime.put(requestId, System.currentTimeMillis());
    }

    private void seedFaultCount(FrontEndImpl frontEnd, String replicaId, int count) throws Exception {
        Field faultCountField = FrontEndImpl.class.getDeclaredField("faultCount");
        faultCountField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> faultCount = (Map<String, Integer>) faultCountField.get(frontEnd);
        faultCount.put(replicaId, count);
    }

    private void injectResponses(FrontEndImpl frontEnd, String requestId) {
        try {
            Thread.sleep(150);

            Field responseMapField = FrontEndImpl.class.getDeclaredField("responseMap");
            responseMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, List<Response>> responseMap = (Map<String, List<Response>>) responseMapField.get(frontEnd);

            List<Response> responses = responseMap.get(requestId);
            responses.add(new Response(requestId, "R1", "SUCCESS"));
            responses.add(new Response(requestId, "R2", "SUCCESS"));
            responses.add(new Response(requestId, "R3", "ERROR"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject FE responses", e);
        }
    }

    private String invokeWaitForMajority(FrontEndImpl frontEnd, String requestId) throws Exception {
        Method waitForMajority = FrontEndImpl.class.getDeclaredMethod("waitForMajority", String.class);
        waitForMajority.setAccessible(true);
        return (String) waitForMajority.invoke(frontEnd, requestId);
    }

    private boolean allRmsSaw(List<MockRM> rms, String expectedMessage) {
        for (MockRM rm : rms) {
            if (!rm.seen(expectedMessage)) {
                return false;
            }
        }
        return true;
    }

    private void waitUntil(Check check, long timeoutMs, String failureMessage) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (check.evaluate()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(failureMessage);
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private static final class MockRM {
        private final DatagramSocket socket;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;
        private Thread listener;

        private MockRM(int port) throws Exception {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(250);
        }

        private void start() {
            listener = new Thread(() -> {
                byte[] buffer = new byte[Config.BUFFER_SIZE];
                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        messages.add(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim());
                    } catch (SocketTimeoutException ignored) {
                        // Poll until stopped.
                    } catch (Exception e) {
                        if (running) {
                            throw new RuntimeException("MockRM listener failed", e);
                        }
                    }
                }
            }, "mock-rm-" + socket.getLocalPort());
            listener.start();
        }

        private boolean seen(String expected) {
            return messages.contains(expected);
        }

        private void stop() {
            running = false;
            socket.close();
            if (listener != null) {
                try {
                    listener.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
