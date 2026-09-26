/*
 * This file is licensed under the GNU General Public License v3.0.
 *
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

/*
 * kv4p HT (see http://kv4p.com)
 * Copyright (C) 2024 Vance Vagell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.dkaukov.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AprsIsClientTest {
    @Test public void disablingTransmitDiscardsQueuedPacketsWithoutSuccess() throws Exception {
        try (AprsIsClient client = idleClient()) {
            AtomicInteger delivered = new AtomicInteger();
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>old", delivered::incrementAndGet));
            client.setTransmitEnabled(false);
            client.setTransmitEnabled(true);
            StringWriter output = new StringWriter();
            assertFalse(transmit(client, new BufferedWriter(output), invoke(client, "awaitConfiguration")));
            assertEquals("", output.toString());
            assertEquals(0, delivered.get());
        }
    }

    @Test public void callsignChangesDiscardOldQueueAndCallbacks() throws Exception {
        try (AprsIsClient client = idleClient()) {
            AtomicInteger oldDelivered = new AtomicInteger();
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>old", oldDelivered::incrementAndGet));
            client.setCallsign("VK3XYZ");
            StringWriter output = new StringWriter();
            assertFalse(transmit(client, new BufferedWriter(output), invoke(client, "awaitConfiguration")));
            assertTrue(client.send("VK3XYZ", "VK3XYZ>APRS:>also old", oldDelivered::incrementAndGet));
            AtomicInteger newDelivered = new AtomicInteger();
            // send() can also change the authentication identity and must clear old work.
            assertTrue(client.send("VK3NEW", "VK3NEW>APRS:>new", newDelivered::incrementAndGet));
            assertTrue(transmit(client, new BufferedWriter(output), invoke(client, "awaitConfiguration")));
            assertEquals("VK3NEW>APRS:>new\r\n", output.toString());
            assertEquals(0, oldDelivered.get());
            assertEquals(1, newDelivered.get());
        }
    }

    @Test public void failedWriteRetainsHeadPacketAndCallbackUntilSuccessfulFlush() throws Exception {
        try (AprsIsClient client = idleClient()) {
            AtomicInteger delivered = new AtomicInteger();
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>first", delivered::incrementAndGet));
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>second", delivered::incrementAndGet));
            Object configuration = invoke(client, "awaitConfiguration");
            long generation = (Long) invoke(client, "pendingPacketGeneration");
            BufferedWriter broken = new BufferedWriter(new Writer() {
                @Override public void write(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("injected write failure");
                }
                @Override public void flush() { }
                @Override public void close() { }
            });
            assertThrows(IOException.class, () -> transmit(client, broken, configuration));
            assertEquals(0, delivered.get());
            assertEquals(generation, ((Long) invoke(client, "pendingPacketGeneration")).longValue());
            StringWriter output = new StringWriter();
            BufferedWriter writer = new BufferedWriter(output);
            assertTrue(transmit(client, writer, configuration));
            assertTrue(transmit(client, writer, configuration));
            assertFalse(transmit(client, writer, configuration));
            assertEquals("VK3ABC>APRS:>first\r\nVK3ABC>APRS:>second\r\n", output.toString());
            assertEquals(2, delivered.get());
        }
    }

    @Test public void receiveOnlySessionCannotDrainNewTransmitQueue() throws Exception {
        try (AprsIsClient client = idleClient()) {
            client.setTransmitEnabled(false);
            client.setReceiveEnabled(true);
            Object receiveOnly = invoke(client, "awaitConfiguration");
            client.setTransmitEnabled(true);
            AtomicInteger delivered = new AtomicInteger();
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>queued", delivered::incrementAndGet));
            StringWriter output = new StringWriter();
            BufferedWriter writer = new BufferedWriter(output);
            transmit(client, writer, receiveOnly);
            assertEquals("", output.toString());
            assertEquals(0, delivered.get());
            assertTrue(transmit(client, writer, invoke(client, "awaitConfiguration")));
            assertEquals("VK3ABC>APRS:>queued\r\n", output.toString());
            assertEquals(1, delivered.get());
        }
    }

    @Test public void existingQueuedPacketDoesNotBypassReconnectBackoff() throws Exception {
        try (AprsIsClient client = idleClient()) {
            assertTrue(client.send("VK3ABC", "VK3ABC>APRS:>queued", null));
            long queued = (Long) invoke(client, "pendingPacketGeneration");
            long configuration = (Long) field(client, "configurationGeneration");
            long start = System.nanoTime();
            assertEquals(true, invoke(client, "awaitReconnect", 150L, configuration, queued));
            assertTrue("Existing queue must not skip backoff", System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(120));
            assertEquals(queued, ((Long) invoke(client, "pendingPacketGeneration")).longValue());
        }
    }

    // Unit-test the queue state machine without socket timing or new production test APIs.
    // Stop the initially disabled worker before enabling; loopback tests above/below cover sessions.
    private static AprsIsClient idleClient() throws Exception {
        AprsIsClient client = new AprsIsClient("test", "1.0");
        ExecutorService worker = (ExecutorService) field(client, "worker");
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        client.setCallsign("VK3ABC");
        client.setTransmitEnabled(true);
        client.setEnabled(true);
        return client;
    }

    private static Object field(AprsIsClient client, String name) throws Exception {
        Field field = AprsIsClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }

    private static boolean transmit(AprsIsClient client, BufferedWriter writer, Object configuration) throws Exception {
        return (Boolean) invoke(client, "transmitPendingPacket", writer, configuration);
    }

    private static Object invoke(AprsIsClient client, String name, Object... arguments) throws Exception {
        for (Method method : AprsIsClient.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                try {
                    return method.invoke(client, arguments);
                } catch (InvocationTargetException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw error;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test public void passcodeUsesBaseCallsign() {
        assertEquals(13023, AprsIsClient.passcode("N0CALL"));
        assertEquals(13023, AprsIsClient.passcode("n0call-10"));
        assertEquals(13023, AprsIsClient.passcode("N0CALL-0"));
    }

    @Test public void normalizesServerAndSuppliesDefaultPort() {
        assertEquals(AprsIsClient.DEFAULT_SERVER, AprsIsClient.normalizeServer(""));
        assertEquals("aunz.aprs2.net:14580",
            AprsIsClient.normalizeServer("aunz.aprs2.net"));
        assertEquals("example.net:12345",
            AprsIsClient.normalizeServer("example.net:12345"));
        assertEquals("[2001:db8::1]:14580",
            AprsIsClient.normalizeServer("[2001:db8::1]"));
        assertNull(AprsIsClient.normalizeServer("https://example.net:14580/"));
        assertNull(AprsIsClient.normalizeServer("user@example.net:14580"));
        assertNull(AprsIsClient.normalizeServer("example.net:70000"));
    }

    @Test public void persistentSessionLogsInAndSendsQueuedPackets() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch packetsReceived = new CountDownLatch(2);
        CountDownLatch callbacks = new CountDownLatch(2);
        ExecutorService serverWorker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            serverWorker.execute(() -> serveVerifiedSession(server, received, packetsReceived));
            try (AprsIsClient client = new AprsIsClient("test", "2.0 test")) {
                assertTrue(client.setServer("127.0.0.1:" + server.getLocalPort()));
                client.setCallsign("vk3abc-9");
                client.setTransmitEnabled(true);
                client.setEnabled(true);
                assertTrue(client.send("VK3ABC-9", "VK3RF>APRS,qAO,VK3ABC-9:>one", callbacks::countDown));
                assertTrue(client.send("VK3ABC-9", "VK3RF>APRS,qAO,VK3ABC-9:>two", callbacks::countDown));
                assertTrue(packetsReceived.await(5, TimeUnit.SECONDS));
                assertTrue(callbacks.await(5, TimeUnit.SECONDS));
                assertEquals("user VK3ABC-9 pass " + AprsIsClient.passcode("VK3ABC-9") + " vers test 2.0_test", received.get(0));
                assertEquals("VK3RF>APRS,qAO,VK3ABC-9:>one", received.get(1));
                assertEquals("VK3RF>APRS,qAO,VK3ABC-9:>two", received.get(2));
            }
        } finally {
            serverWorker.shutdownNow();
        }
    }

    @Test public void disabledInvalidCallsignAndMultilinePacketsAreRejected() {
        try (AprsIsClient client = new AprsIsClient("test","2.0")) {
            assertFalse(client.send("VK3ABC", "VK3RF>APRS:>disabled", null));
            client.setEnabled(true);
            assertFalse(client.send("VK3ABC", "VK3RF>APRS:>receive only", null));
            assertFalse(client.send("", "VK3RF>APRS:>test", null));
            assertFalse(client.send("VK3ABC", "VK3RF>APRS:>test\r\nsecond", null));
        }
    }

    @Test public void loginUsesAprsIsSyntax() {
        assertEquals("user VK3ABC pass 21675 vers test 2.0", AprsIsClient.loginLine("VK3ABC", 21675, "test", "2.0", false, null, null));
        assertEquals("user VK3ABC pass 21675 vers test 2.0 filter m/50", AprsIsClient.loginLine("VK3ABC", 21675, "test", "2.0", true, null, null));
        assertEquals("user VK3ABC pass -1 vers test 2.0 filter r/-37.81360/144.96310/50",
            AprsIsClient.loginLine("VK3ABC", -1, "test", "2.0", true, -37.8136, 144.9631));
    }

    @Test public void receiveModeRequestsNearbyFeedAndDeliversOnlyPackets() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch packetDelivered = new CountDownLatch(1);
        ExecutorService serverWorker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            serverWorker.execute(() -> serveIncomingPacket(server, received));
            try (AprsIsClient client = new AprsIsClient("test", "2.0", packet -> {
                received.add(packet);
                packetDelivered.countDown();
            })) {
                assertTrue(client.setServer("127.0.0.1:" + server.getLocalPort()));
                client.setCallsign("VK3ABC");
                client.setReceiveEnabled(true);
                client.setEnabled(true);
                assertTrue(packetDelivered.await(5, TimeUnit.SECONDS));
                assertEquals("user VK3ABC pass -1 vers test 2.0 filter m/50", received.get(0));
                assertEquals("VK3RF>APRS,qAO,VK3ABC:>nearby", received.get(1));
            }
        } finally {
            serverWorker.shutdownNow();
        }
    }

    private static void serveVerifiedSession(ServerSocket server, List<String> received,
                                             CountDownLatch packetsReceived) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.ISO_8859_1));
            writer.write("# aprsc test server\r\n");
            writer.flush();
            received.add(reader.readLine());
            writer.write("# logresp VK3ABC-9 verified, server TEST\r\n");
            writer.flush();
            for (int i = 0; i < 2; i++) {
                received.add(reader.readLine());
                packetsReceived.countDown();
            }
            // Keep the server side open until the client closes the session.
            while (reader.readLine() != null) {
                // Drain any final client traffic during shutdown.
            }
        } catch (Exception ignored) {
            // The assertions time out with the captured context if the test server fails.
        }
    }

    private static void serveIncomingPacket(ServerSocket server, List<String> received) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.ISO_8859_1));
            writer.write("# aprsc test server\r\n");
            writer.flush();
            received.add(reader.readLine());
            writer.write("# logresp VK3ABC unverified, server TEST\r\n");
            writer.write("# keepalive\r\n");
            writer.write("VK3RF>APRS,qAO,VK3ABC:>nearby\r\n");
            writer.flush();
            // The test owns client shutdown; avoid simulating an unexpected disconnect.
            while (reader.readLine() != null) {
                // Drain any final client traffic during shutdown.
            }
        } catch (Exception ignored) {
            // The assertion times out with the captured context if the test server fails.
        }
    }
}
