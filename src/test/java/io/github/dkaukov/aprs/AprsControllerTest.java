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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.dkaukov.aprs.parser.APRSPacket;
import io.github.dkaukov.aprs.parser.APRSTypes;
import io.github.dkaukov.aprs.parser.Digipeater;
import io.github.dkaukov.aprs.parser.MessagePacket;
import io.github.dkaukov.aprs.parser.Parser;
import io.github.dkaukov.aprs.parser.PositionField;
import io.github.dkaukov.aprs.parser.WeatherField;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.Assert;
import org.junit.Test;

public class AprsControllerTest {
    @Test public void injectedClockControlsEventAndPacketTimestamps() {
        Fixture f = fixture(Clock.fixed(Instant.ofEpochMilli(1_234_567L), ZoneOffset.UTC));
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, 144_390_000L, packet.toAX25Frame());

        AprsEvent event = f.events.records.get(0);
        AprsPacket observation = f.packets.records.get(0);
        assertEquals(1_234_567L, event.getFirstSeenMs());
        assertEquals(1_234_567L, event.getLastSeenMs());
        assertEquals(1_234_567L, observation.getTimestampMs());
    }

    @Test public void relatedEventAndPacketWritesUseRepositoryTransaction() {
        Fixture f = fixture();
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, 144_390_000L, packet.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        assertEquals(2, f.repository.transactionCount);
        assertEquals(1, f.events.records.size());
        assertEquals(2, f.packets.records.size());
    }

    @Test public void repositoryProjectionRunsInsideTransactionWithFinalEvent() {
        Fixture f = fixture();
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");
        f.controller.setTxDestination(null);

        f.controller.handle(packet, AprsSource.RX_RF, null, packet.toAX25Frame());

        assertEquals(1, f.repository.projectedEvents.size());
        assertEquals(1L, f.repository.projectedEvents.get(0).getId());
        assertTrue(f.repository.projectionRanInTransaction);
    }

    @Test public void incomingMessageCallbacksRunAfterRepositoryTransaction() {
        Fixture f = fixture();
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, 144_390_000L, packet.toAX25Frame());

        assertFalse(f.callbacks.incomingMessageDuringTransaction);
        assertFalse(f.callbacks.acknowledgementDuringTransaction);
    }

    @Test public void incomingMessageCallbackMarksNonLocalMessages() {
        Fixture f = fixture();
        APRSPacket packet = directMessage("VK3ABC", "VK3OTHER", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, null, packet.toAX25Frame());

        assertEquals(1, f.callbacks.notificationCount);
        assertFalse(f.callbacks.lastIncomingMessageForLocal);
        assertEquals(0, f.callbacks.acknowledgementCount);
    }

    @Test public void incomingMessageTreatsExplicitZeroSsidAsLocal() {
        Fixture f = fixture();
        f.controller.setCallsign("VK3ME-0");
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, null, packet.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        assertTrue(f.callbacks.lastIncomingMessageForLocal);
        assertEquals(1, f.callbacks.acknowledgementCount);
    }

    @Test public void incomingNumberedMessageAcknowledgementIsDelayedOneSecond() {
        Fixture f = fixture(Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC));
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(packet, AprsSource.RX_RF, 144_390_000L, packet.toAX25Frame());
        f.controller.tick(10_999L);
        assertEquals(0, f.callbacks.acknowledgementCount);
        assertEquals(1, f.packets.records.size());

        f.controller.tick(11_000L);
        assertEquals(1, f.callbacks.acknowledgementCount);
        assertEquals(2, f.packets.records.size());
    }

    @Test public void controllerConstructsAcknowledgementFromConfiguredEnvelope() {
        Fixture f = fixture();
        f.controller.setTxDestination("apkva");
        f.controller.setTxPath(Collections.singletonList(new Digipeater("WIDE1-1")));
        APRSPacket incoming = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(incoming, AprsSource.RX_RF, 144_390_000L, incoming.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        APRSPacket acknowledgement = f.callbacks.lastTransmission.getPacket();
        MessagePacket message = new MessagePacket(acknowledgement.getPayload().getRawBytes(),
            acknowledgement.getDestinationCall());
        assertEquals("VK3ME", acknowledgement.getSourceCall());
        assertEquals("APKVA", acknowledgement.getDestinationCall());
        assertEquals("WIDE1-1", acknowledgement.getDigipeaters().get(0).toString());
        assertEquals("VK3ABC", message.getTargetCallsign());
        assertEquals("ack", message.getMessageBody());
        assertTrue(new String(acknowledgement.getPayload().getRawBytes(),
            StandardCharsets.ISO_8859_1).endsWith("ackA7"));
        assertEquals(AprsSource.TX_RF, f.packets.records.get(1).getSource());
        assertEquals(Long.valueOf(f.events.records.get(0).getId()),
            f.packets.records.get(1).getEventId());
        assertEquals(1, f.events.records.get(0).getPacketCount());
        assertEquals(Long.valueOf(144_390_000L), f.callbacks.lastRequestedFrequencyHz);
        assertEquals(AprsController.RfTransmissionPurpose.ACKNOWLEDGEMENT,
            f.callbacks.lastTransmissionPurpose);
    }

    @Test public void missingIdentityOrDestinationSkipsAutomaticAcknowledgementAndIgate() {
        Fixture f = fixture();
        APRSPacket incoming = directMessage("VK3ABC", "VK3ME", "hello", "A7");
        f.controller.setCallsign(null);
        f.controller.setIgateEnabled(true);
        f.controller.handle(incoming, AprsSource.RX_RF, null, incoming.toAX25Frame());
        assertEquals(0, f.callbacks.acknowledgementCount);
        assertEquals(0, f.callbacks.igateCount);

        f.controller.setCallsign("VK3ME");
        f.controller.setTxDestination(null);
        f.controller.handle(incoming, AprsSource.RX_RF, null, incoming.toAX25Frame());
        assertEquals(0, f.callbacks.acknowledgementCount);
    }

    @Test public void changingCallsignClearsDigipeatSuppression() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket packet = packetWithPath("WIDE1-1");
        f.controller.handle(packet, AprsSource.RX_RF, null, packet.toAX25Frame());
        f.controller.setCallsign("VK3NEW");
        f.controller.handle(packet, AprsSource.RX_RF, null, packet.toAX25Frame());
        assertEquals(2, f.callbacks.digipeatCount);
    }

    @Test public void failedCommitDoesNotRemoveReliableEventFromRetryCache() {
        Fixture f = fixture();
        f.events.records.add(pendingEvent("VK3ABC", "7", 0L, 1).toBuilder().id(1).build());
        f.controller.tick(-1L);
        f.repository.failCommitAfterBody = true;

        try {
            f.controller.handle(deliveryResponse("VK3ABC", "ack7"), AprsSource.RX_RF, null, null);
            throw new AssertionError("Expected transaction failure");
        } catch (IllegalStateException expected) {
            // The repository rolled back after executing the transaction body.
        }

        f.controller.tick(0L);
        assertEquals(1, f.callbacks.retryCount);
        assertEquals(AprsEvent.DELIVERY_PENDING, f.events.findById(1).getDeliveryState());
    }

    @Test public void retryAfterRestartUsesPersistedInitialRfFrame() {
        Fixture f = fixture();
        APRSPacket original = outgoingMessage("VK3ME", "VK3ABC", "hello", "7");
        f.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "7", 144_390_000L,
            original, null);
        long retryAt = f.events.records.get(0).getNextRetryAtMs();
        FakeCallbacks restartedCallbacks = new FakeCallbacks();
        restartedCallbacks.repository = f.repository;
        AprsController restarted = new AprsController(f.repository, restartedCallbacks,
            Clock.fixed(Instant.ofEpochMilli(retryAt), ZoneOffset.UTC));

        restarted.tick(retryAt);

        assertEquals(1, restartedCallbacks.retryCount);
        assertEquals(Long.valueOf(144_390_000L), restartedCallbacks.lastRequestedFrequencyHz);
        assertEquals(AprsController.RfTransmissionPurpose.RELIABLE_MESSAGE_RETRY,
            restartedCallbacks.lastTransmissionPurpose);
        assertArrayEquals(original.toAX25Frame(),
            restartedCallbacks.lastTransmission.getRawAx25());
    }

    @Test public void missingInitialRetryFrameFailsReliableMessage() {
        Fixture f = fixture();
        f.repository.allowSyntheticInitialFrame = false;
        f.events.records.add(pendingEvent("VK3ABC", "7", 0L, 1).toBuilder().id(1).build());

        f.controller.tick(0L);

        AprsEvent event = f.events.findById(1);
        assertEquals(AprsEvent.DELIVERY_FAILED, event.getDeliveryState());
        assertNull(event.getNextRetryAtMs());
        assertEquals(0, f.callbacks.retryCount);
    }

    @Test public void rawFallbackPreservesHighWireBytes() {
        Fixture f = fixture();
        byte[] payload = {'?', (byte) 0x80, (byte) 0xff};
        f.controller.handle(new APRSPacket("VK3ABC", "APRS", null, payload));
        assertEquals("Raw: ?\u0080\u00ff", f.events.records.get(0).getComment());
    }

    @Test public void concurrentIncomingPacketsHaveOneEventAndNoLostCounts() throws Exception {
        Fixture f = fixture();
        APRSPacket packet = directMessage("VK3ABC", "VK3ME", "hello", "A7");
        runConcurrently(16, () -> f.controller.handle(packet, AprsSource.RX_RF, null, null));
        f.controller.tick(Long.MAX_VALUE);
        assertEquals(1, f.events.records.size());
        assertEquals(16, f.events.records.get(0).getPacketCount());
        assertEquals(32, f.packets.records.size());
        assertEquals(1, f.callbacks.notificationCount);
        assertEquals(16, f.callbacks.acknowledgementCount);
    }

    @Test public void concurrentDigipeatingTransmitsOnlyOnce() throws Exception {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket packet = packetWithPath("WIDE1-1");
        runConcurrently(16, () -> f.controller.handle(packet, AprsSource.RX_RF, null, null));
        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals(1, f.events.records.size());
        assertEquals(17, f.events.records.get(0).getPacketCount());
        assertEquals(17, f.packets.records.size());
    }

    @Test public void concurrentTicksRetryAndBeaconOnlyOnce() throws Exception {
        Fixture f = fixture();
        f.events.insert(pendingEvent("VK3ABC", "7", 0L, 1));
        f.controller.setPositionBeaconingEnabled(true, 0L, 2_000L);
        runConcurrently(16, () -> f.controller.tick(0L));
        assertEquals(1, f.callbacks.retryCount);
        assertEquals(1, f.callbacks.beaconCount);
        assertEquals(2, f.events.findById(1).getTransmitAttempts());
        assertEquals(1, f.packets.records.size());
    }

    @Test public void concurrentTransmissionRecordsDoNotLoseCounts() throws Exception {
        Fixture f = fixture();
        long id = f.events.insert(AprsEvent.builder().build());
        APRSPacket packet = packetWithPath("WIDE1-1");
        runConcurrently(16, () -> f.controller.recordTransmission(id, packet, null, null));
        assertEquals(16, f.events.findById(id).getPacketCount());
        assertEquals(16, f.packets.records.size());
    }

    private static void runConcurrently(int count, Runnable operation) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    operation.run();
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void incomingMessageCreatesOneEventAndLinkedPacket() {
        Fixture f = fixture();
        APRSPacket frame = directMessage("VK3ABC", "VK3ME", "hello", "A7");
        byte[] raw = frame.toAX25Frame();

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, raw);
        f.controller.tick(Long.MAX_VALUE);

        assertEquals(1, f.events.records.size());
        assertEquals(2, f.packets.records.size());
        AprsEvent event = f.events.records.get(0);
        AprsPacket packet = f.packets.records.get(0);
        assertEquals(AprsEvent.MESSAGE_TYPE, event.getType());
        assertEquals("A7", event.getMessageIdentifier());
        assertEquals("hello", event.getBody());
        assertEquals(1, event.getPacketCount());
        assertEquals(Long.valueOf(event.getId()), packet.getEventId());
        assertEquals(AprsSource.RX_RF, packet.getSource());
        assertEquals(Long.valueOf(145_175_000L), packet.getFrequencyHz());
        assertEquals("APRS", packet.getAx25Destination());
        assertNull(packet.getPath());
        assertArrayEquals(raw, packet.getRawAx25());
    }

    @Test public void duplicateMessageCreatesPacketsButOnlyOneEventAndNotification() {
        Fixture f = fixture();
        APRSPacket frame = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        assertEquals(4, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(2, f.events.records.get(0).getPacketCount());
        assertEquals(1, f.callbacks.notificationCount);
        assertEquals(2, f.callbacks.acknowledgementCount);
        assertEquals("VK3ABC", f.callbacks.lastIncomingMessage.getFromCallsign());
        assertEquals("hello", f.callbacks.lastIncomingMessage.getBody());
        f.callbacks.lastIncomingMessage = f.callbacks.lastIncomingMessage.toBuilder().body("consumer edit").build();
        assertEquals("hello", f.events.records.get(0).getBody());
    }

    @Test public void numberedMessageCopiesUseLongerDuplicateWindow() {
        Fixture f = fixture();
        APRSPacket frame = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        AprsEvent earlier = f.events.records.get(0);
        f.events.update(earlier.toBuilder().lastSeenMs(earlier.getLastSeenMs() - 60_000L).build());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(2, f.events.records.get(0).getPacketCount());
    }

    @Test public void copiesOfPositionViaDifferentPathsCollapseIntoOneEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket direct = Parser.parse("VK3ABC>APRS,WIDE1-1:!3751.65S/14458.20E-Test");
        APRSPacket relayed = Parser.parse("VK3ABC>APRS,VK3DIG*:!3751.65S/14458.20E-Test");

        f.controller.handle(direct, AprsSource.RX_RF, 144_390_000L, direct.toAX25Frame());
        f.controller.handle(relayed, AprsSource.RX_RF, 144_390_000L, relayed.toAX25Frame());

        assertEquals(2, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(AprsEvent.POSITION_TYPE, f.events.records.get(0).getType());
        assertEquals(2, f.events.records.get(0).getPacketCount());
    }

    @Test public void unchangedPositionAfterThirtySecondsCreatesNewEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket frame = Parser.parse("VK3ABC>APRS:!3751.65S/14458.20E-Test");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        AprsEvent earlier = f.events.records.get(0);
        f.events.update(earlier.toBuilder().lastSeenMs(earlier.getLastSeenMs() - 31_000L).build());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(2, f.events.records.size());
        assertEquals(2, f.packets.records.size());
    }

    @Test public void separateMessagesRemainSeparateFeedRows() {
        Fixture f = fixture();

        f.controller.handle(directMessage("VK3ABC", "VK3ME", "first", "A7"),
            AprsSource.RX_RF, 144_390_000L, null);
        f.controller.handle(directMessage("VK3ABC", "VK3ME", "second", "A8"),
            AprsSource.RX_RF, 144_390_000L, null);

        assertEquals(2, f.events.records.size());
    }

    @Test public void weatherAndObjectEachCreateEvents() throws Exception {
        Fixture f = fixture();
        APRSPacket weather = Parser.parse("VK3WX>APRS:_10000000c090s010g015t070h50b10130");
        APRSPacket object = Parser.parse("VK3ABC>APRS:;TESTOBJ  *111111z3751.65S/14458.20E-Test");

        f.controller.handle(weather, AprsSource.RX_RF, 144_390_000L, weather.toAX25Frame());
        f.controller.handle(object, AprsSource.RX_RF, 144_390_000L, object.toAX25Frame());

        assertEquals(2, f.events.records.size());
        assertEquals(AprsEvent.WEATHER_TYPE, f.events.records.get(0).getType());
        AprsEvent objectEvent = f.events.records.get(1);
        assertEquals(AprsEvent.OBJECT_TYPE, objectEvent.getType());
        assertEquals(-37.86083, objectEvent.getPositionLat(), 0.00001);
        assertEquals(144.97, objectEvent.getPositionLong(), 0.00001);
        assertEquals("Test", objectEvent.getComment());
    }

    @Test public void internetPositionRetainsCoordinatesForMapAction() {
        Fixture f = fixture();

        f.controller.handleAprsIsPacket(
            "VK3SF-10>APDR16,TCPIP*,qAC,T2SYDNEY:=3746.38S/14504.75Eu211/003");

        assertEquals(1, f.events.records.size());
        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.POSITION_TYPE, event.getType());
        assertTrue(event.isInternetOnly());
        assertEquals(-37.773, event.getPositionLat(), 0.00001);
        assertEquals(145.07917, event.getPositionLong(), 0.00001);
    }

    @Test(expected = IllegalStateException.class)
    public void aprsIsReceivePropagatesTransactionFailure() {
        Fixture f = fixture();
        f.repository.failCommitAfterBody = true;

        f.controller.handleAprsIsPacket("VK3ABC>APRS:>test");
    }

    @Test(expected = IllegalStateException.class)
    public void aprsIsTransmissionRecordingPropagatesTransactionFailure() {
        Fixture f = fixture();
        f.repository.failCommitAfterBody = true;

        f.controller.recordAprsIsTransmission(null, "VK3ME>APRS:>test");
    }

    @Test public void validUnsupportedPacketCreatesUnknownEventWithRawText() {
        Fixture f = fixture();
        APRSPacket frame = new APRSPacket("VK3ABC", "APRS",
            Collections.singletonList(new Digipeater("WIDE2-1")),
            "?APRS?".getBytes(StandardCharsets.US_ASCII));

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.UNKNOWN_TYPE, event.getType());
        assertEquals("Raw: ?APRS?", event.getComment());
        assertEquals(Long.valueOf(event.getId()), f.packets.records.get(0).getEventId());
    }

    @Test public void statusPacketsExposeTextAndLatestStatusFeedRow() {
        Fixture f = fixture();

        f.controller.handleAprsIsPacket(
            "VK3VB-B>APCHP0,TCPIP*,qAC,VK3VB-BS:>First status");
        f.controller.handleAprsIsPacket(
            "VK3VB-B>APCHP0,TCPIP*,qAC,VK3VB-BS:>162255zPowered by WPSD "
                + "(https://wpsd.radio/)");

        assertEquals(2, f.events.records.size());
        AprsEvent latest = f.events.records.get(1);
        assertEquals(AprsEvent.STATUS_TYPE, latest.getType());
        assertEquals("Powered by WPSD (https://wpsd.radio/)", latest.getComment());
        assertTrue(latest.isInternetOnly());
    }

    @Test public void malformedPacketIsStoredWithoutEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket malformed = Parser.parse("VK3ABC>APRS:Ainvalid");

        f.controller.handle(malformed, AprsSource.RX_RF, 144_390_000L,
            malformed.toAX25Frame());

        assertTrue(malformed.hasFault());
        assertEquals(1, f.packets.records.size());
        assertNull(f.packets.records.get(0).getEventId());
        assertTrue(f.events.records.isEmpty());
    }

    @Test public void stationCapabilitiesExposeReadableLatestFeedRow() {
        Fixture f = fixture();

        f.controller.handleAprsIsPacket(
            "VK3RMC-A>APRS,TCPIP*,qAC,T2MELBOURNE:<IGATE,MSG_CNT=0,LOC_CNT=0");
        AprsEvent first = f.events.records.get(0);
        assertEquals("IGate · 0 messages · 0 local stations", first.getComment());
        f.controller.handleAprsIsPacket(
            "VK3RMC-A>APRS,TCPIP*,qAC,T2MELBOURNE:<IGATE,MSG_CNT=1,LOC_CNT=1");

        assertEquals(2, f.events.records.size());
        AprsEvent event = f.events.records.get(1);
        assertEquals(AprsEvent.STATION_CAPABILITIES_TYPE, event.getType());
        assertEquals("IGate · 1 message · 1 local station", event.getComment());
        assertTrue(event.isInternetOnly());
    }

    @Test public void thirdPartyPreservesOuterPacketAndInnerEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket outer = Parser.parse(
            "RELAY1>APKVPA,WIDE1-1:}VK3ABC>APRS::VK3ME    :hello{7");
        byte[] raw = outer.toAX25Frame();

        f.controller.handle(outer, AprsSource.RX_RF, 145_175_000L, raw);

        AprsPacket packet = f.packets.records.get(0);
        AprsEvent event = f.events.records.get(0);
        assertEquals("RELAY1", packet.getFromCallsign());
        assertEquals("APKVPA", packet.getAx25Destination());
        assertEquals("WIDE1-1", packet.getPath());
        assertArrayEquals(raw, packet.getRawAx25());
        assertEquals("VK3ABC", event.getFromCallsign());
        assertEquals("RELAY1", event.getRelayCallsign());
        assertEquals("VK3ME", event.getToCallsign());
        assertEquals("hello", event.getBody());
    }

    @Test public void physicalPacketPathPreservesUsedMarker() {
        Fixture f = fixture();
        Digipeater used = new Digipeater("VK3DIG");
        used.setUsed(true);
        APRSPacket frame = new APRSPacket("VK3ABC", "APRS", Collections.singletonList(used),
            MessagePacket.createMessagePayload("VK3ME", "hello", "7"));

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, frame.toAX25Frame());

        assertEquals("VK3DIG*", f.packets.records.get(0).getPath());
    }

    @Test public void outgoingChatCreatesEventAndPacket() {
        Fixture f = fixture();
        APRSPacket frame = outgoingMessage("VK3ME", "VK3ABC", "hello", "7");

        f.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "7",
            144_390_000L, frame, frame.toAX25Frame());

        AprsEvent event = f.events.records.get(0);
        AprsPacket packet = f.packets.records.get(0);
        assertEquals(AprsEvent.DELIVERY_PENDING, event.getDeliveryState());
        assertEquals(1, event.getTransmitAttempts());
        assertEquals(1, event.getPacketCount());
        assertEquals(Long.valueOf(event.getId()), packet.getEventId());
        assertEquals(AprsSource.TX_RF, packet.getSource());
    }

    @Test public void postMessageBuildsAndRecordsReliableMessage() {
        Fixture f = fixture();

        assertTrue(f.controller.postMessage("VK3ABC", "hello", 144_390_000L));

        AprsEvent event = f.events.records.get(0);
        MessagePacket message = new MessagePacket(f.callbacks.lastTransmission.getPacket()
            .getPayload().getRawBytes(), "APRS");
        assertEquals(AprsEvent.DELIVERY_PENDING, event.getDeliveryState());
        assertEquals("VK3ABC", event.getToCallsign());
        assertEquals("hello", event.getBody());
        assertEquals(event.getMessageIdentifier(), message.getMessageNumber());
        assertEquals(AprsController.RfTransmissionPurpose.OUTGOING_MESSAGE,
            f.callbacks.lastTransmissionPurpose);
        assertEquals(Long.valueOf(144_390_000L), f.callbacks.lastRequestedFrequencyHz);
    }

    @Test public void postMessageClassifiesBulletinAsBroadcast() {
        Fixture f = fixture();

        assertTrue(f.controller.postMessage("BLN1CQ", "net starts", 144_390_000L));

        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.DELIVERY_NONE, event.getDeliveryState());
        assertNull(event.getMessageIdentifier());
        assertEquals(AprsController.RfTransmissionPurpose.BROADCAST,
            f.callbacks.lastTransmissionPurpose);
    }

    @Test public void rejectedPostMessageDoesNotCreateHistory() {
        Fixture f = fixture();
        f.callbacks.retrySucceeds = false;

        assertFalse(f.controller.postMessage("VK3ABC", "hello", 144_390_000L));

        assertTrue(f.events.records.isEmpty());
        assertTrue(f.packets.records.isEmpty());
    }

    @Test public void digipeatedEchoAttachesToOutgoingChatEvent() {
        Fixture f = fixture();
        APRSPacket transmitted = outgoingMessage("VK3ME", "VK3ABC", "hello", "7");
        APRSPacket echoed = new APRSPacket("VK3ME", transmitted.getDestinationCall(),
            Collections.singletonList(new Digipeater("VK3DIG*")),
            MessagePacket.createMessagePayload("VK3ABC", "hello", "7"));

        f.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "7",
            144_390_000L, transmitted, transmitted.toAX25Frame());
        f.controller.handle(echoed, AprsSource.RX_RF, 144_390_000L, echoed.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(2, f.events.records.get(0).getPacketCount());
        assertEquals(2, f.packets.records.size());
        assertEquals(f.packets.records.get(0).getEventId(), f.packets.records.get(1).getEventId());
    }

    @Test public void outgoingPositionCreatesEventAndPacket() {
        Fixture f = fixture();
        APRSPacket frame = new APRSPacket("VK3ME", "DST",
            Collections.singletonList(new Digipeater("WIDE1-1")),
            "!3751.65S/14458.20E-Test".getBytes(StandardCharsets.US_ASCII));

        f.controller.recordPositionBeacon("VK3ME", -37.8608, 144.9700,
            144_390_000L, frame, frame.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(AprsEvent.POSITION_TYPE, f.events.records.get(0).getType());
        assertEquals(1, f.packets.records.size());
    }

    @Test public void digipeatedEchoAttachesToOutgoingPositionEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket transmitted = new APRSPacket("VK3ME", "DST",
            Collections.singletonList(new Digipeater("WIDE1-1")),
            "!3751.65S/14458.20E-Test".getBytes(StandardCharsets.US_ASCII));
        APRSPacket echoed = Parser.parse("VK3ME>" + transmitted.getDestinationCall()
            + ",VK3DIG*:!3751.65S/14458.20E-Test");

        f.controller.recordPositionBeacon("VK3ME", -37.8608, 144.9700,
            144_390_000L, transmitted, transmitted.toAX25Frame());
        f.controller.handle(echoed, AprsSource.RX_RF, 144_390_000L, echoed.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(2, f.events.records.get(0).getPacketCount());
        assertEquals(2, f.packets.records.size());
        assertEquals(f.packets.records.get(0).getEventId(), f.packets.records.get(1).getEventId());
    }

    @Test public void acknowledgementLinksPacketAndUpdatesOutgoingEvent() {
        Fixture f = fixture();
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending = pending.toBuilder().id(1)
            .packetCount(1).build();
        f.events.records.add(pending);

        f.controller.handle(deliveryResponse("VK3ABC", "ack7"), AprsSource.RX_RF,
            144_390_000L, null);

        assertEquals(AprsEvent.DELIVERY_PENDING, pending.getDeliveryState());
        pending = f.events.findById(pending.getId());
        assertEquals(AprsEvent.DELIVERY_DELIVERED, pending.getDeliveryState());
        assertNull(pending.getNextRetryAtMs());
        assertEquals(2, pending.getPacketCount());
        assertEquals(Long.valueOf(pending.getId()), f.packets.records.get(0).getEventId());
    }

    @Test public void rejectionLinksPacketAndStopsRetries() {
        Fixture f = fixture();
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending = pending.toBuilder().id(1).build();
        f.events.records.add(pending);

        f.controller.handle(deliveryResponse("VK3ABC", "rej7"), AprsSource.RX_RF,
            144_390_000L, null);

        pending = f.events.findById(pending.getId());
        assertEquals(AprsEvent.DELIVERY_REJECTED, pending.getDeliveryState());
        assertNull(pending.getNextRetryAtMs());
    }

    @Test public void successfulRetriesAddPacketsToSameEvent() {
        Fixture f = fixture();
        AprsEvent event = pendingEvent("VK3ABC", "7", 0L, 1);
        event = event.toBuilder().id(1)
            .packetCount(1).build();
        f.events.records.add(event);

        f.controller.tick(0L);

        event = f.events.findById(event.getId());
        assertEquals(2, event.getTransmitAttempts());
        assertEquals(2, event.getPacketCount());
        assertEquals(1, f.packets.records.size());
        assertEquals(Long.valueOf(event.getId()), f.packets.records.get(0).getEventId());
        assertEquals(Long.valueOf(30_000L), event.getNextRetryAtMs());
    }

    @Test public void retrySequenceEndsAfterFinalGracePeriod() {
        Fixture f = fixture();
        AprsEvent event = pendingEvent("VK3ABC", "7", 0L, 1);
        event = event.toBuilder().id(1).build();
        f.events.records.add(event);

        f.controller.tick(0L); assertRetry(f.events.findById(event.getId()), 2, 30_000L);
        f.controller.tick(30_000L); assertRetry(f.events.findById(event.getId()), 3, 90_000L);
        f.controller.tick(90_000L); assertRetry(f.events.findById(event.getId()), 4, 210_000L);
        f.controller.tick(210_000L); assertRetry(f.events.findById(event.getId()), 5, 450_000L);
        f.controller.tick(450_000L); assertRetry(f.events.findById(event.getId()), 6, 480_000L);
        f.controller.tick(480_000L);

        event = f.events.findById(event.getId());
        assertEquals(AprsEvent.DELIVERY_FAILED, event.getDeliveryState());
        assertNull(event.getNextRetryAtMs());
        assertEquals(5, f.callbacks.retryCount);
        assertEquals(5, f.packets.records.size());
    }

    @Test public void failedRfRetryAddsNoPacketOrAttempt() {
        Fixture f = fixture();
        f.callbacks.retrySucceeds = false;
        AprsEvent event = pendingEvent("VK3ABC", "7", 100L, 1);
        event = event.toBuilder().id(1).build();
        f.events.records.add(event);

        f.controller.tick(100L);

        event = f.events.findById(event.getId());
        assertEquals(1, event.getTransmitAttempts());
        assertEquals(0, f.packets.records.size());
        assertEquals(Long.valueOf(15_100L), event.getNextRetryAtMs());
    }

    @Test public void cancelledRfRetryFailsReliableMessageWithoutAddingPacket() {
        Fixture f = fixture();
        f.callbacks.retrySucceeds = false;
        f.callbacks.cancelRetry = true;
        AprsEvent event = pendingEvent("VK3ABC", "7", 100L, 1);
        event = event.toBuilder().id(1).build();
        f.events.records.add(event);

        f.controller.tick(100L);

        event = f.events.findById(event.getId());
        assertEquals(AprsEvent.DELIVERY_FAILED, event.getDeliveryState());
        assertNull(event.getNextRetryAtMs());
        assertEquals(1, event.getTransmitAttempts());
        assertTrue(f.packets.records.isEmpty());
    }

    @Test public void restartRetriesOnlyPersistedPendingEvents() {
        FakePacketRepository packets = new FakePacketRepository();
        FakeEventRepository events = new FakeEventRepository();
        FakeRepository repository = new FakeRepository(packets, events);
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending = pending.toBuilder().id(1).build();
        events.records.add(pending);
        events.records.add(terminalEvent(AprsEvent.DELIVERY_DELIVERED));
        events.records.add(terminalEvent(AprsEvent.DELIVERY_REJECTED));
        events.records.add(terminalEvent(AprsEvent.DELIVERY_FAILED));
        FakeCallbacks callbacks = new FakeCallbacks();

        new AprsController(repository, callbacks).tick(0L);

        assertEquals(1, callbacks.retryCount);
        assertEquals(2, repository.findById(pending.getId()).getTransmitAttempts());
    }

    @Test public void bulletinIsFireAndForget() {
        Fixture f = fixture();
        APRSPacket frame = outgoingMessage("VK3ME", "BLN1CQ", "net starts", null);

        f.controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts", null,
            144_390_000L, frame, frame.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.DELIVERY_NONE, event.getDeliveryState());
        assertNull(event.getMessageIdentifier());
        assertNull(event.getNextRetryAtMs());
        assertEquals(0, f.callbacks.retryCount);
    }

    @Test public void acknowledgementTransmissionAddsPacketWithoutAnotherEvent() {
        Fixture f = fixture();
        AprsEvent event = AprsEvent.builder().id(1)
            .packetCount(1).build();
        f.events.records.add(event);
        APRSPacket ack = outgoingMessage("VK3ME", "VK3ABC", "ack7", null);

        f.controller.recordTransmission(event.getId(), ack, 144_390_000L, ack.toAX25Frame());

        event = f.events.findById(event.getId());
        assertEquals(1, f.events.records.size());
        assertEquals(2, event.getPacketCount());
        assertEquals(Long.valueOf(event.getId()), f.packets.records.get(0).getEventId());
    }

    @Test public void beaconCadenceUsesControllerTick() {
        Fixture f = fixture();
        f.controller.setPositionBeaconingEnabled(true, 1_000L, 2_000L);
        f.controller.tick(1_000L);
        f.controller.tick(1_001L);
        f.controller.tick(2_999L);
        assertEquals(1, f.callbacks.beaconCount);
        f.controller.tick(3_000L);
        assertEquals(2, f.callbacks.beaconCount);
        f.controller.setPositionBeaconingEnabled(false, 3_000L, 0L);
        f.controller.tick(5_000L);
        assertEquals(2, f.callbacks.beaconCount);
    }

    @Test public void generatedWeatherBeaconUsesWeatherSymbolAndRoundTrips() throws Exception {
        Fixture f = fixture();
        f.callbacks.beaconData = BeaconData.builder().latitude(-37.8608).longitude(144.9700)
            .symbolCode('>').weather(BeaconData.WeatherData.builder()
                .windDirectionDegrees(225).windSpeedKnots(10).windGustKnots(15)
                .temperatureCelsius(20D).humidityPercent(60).pressureHectopascals(1013.2D)
                .build()).build();
        f.controller.setPositionBeaconingEnabled(true, 0L, 60_000L);

        f.controller.tick(0L);

        APRSPacket packet = Parser.parseAX25(f.callbacks.lastTransmission.getRawAx25());
        PositionField position = (PositionField) packet.getPayload().getAprsData(APRSTypes.T_POSITION);
        WeatherField weather = (WeatherField) packet.getPayload().getAprsData(APRSTypes.T_WX);
        assertEquals('_', position.getPosition().getSymbolCode());
        assertEquals(Integer.valueOf(225), weather.getWindDirection());
        assertEquals(Integer.valueOf(10), weather.getWindSpeed());
        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.WEATHER_TYPE, event.getType());
        assertEquals(-37.86083D, event.getPositionLat(), 0.00001D);
        assertEquals(144.97000D, event.getPositionLong(), 0.00001D);
        assertEquals(68D, event.getTemperature(), 0D);
        assertEquals(60D, event.getHumidity(), 0D);
        assertEquals(1013.2D, event.getPressure(), 0D);
    }

    @Test public void beaconCoordinatesRoundAcrossMinuteBoundary() {
        Fixture f = fixture();
        f.callbacks.beaconData = BeaconData.builder().latitude(37.999999D).longitude(144.9700D)
            .build();
        f.controller.setPositionBeaconingEnabled(true, 0L, 60_000L);

        f.controller.tick(0L);

        String payload = new String(f.callbacks.lastTransmission.getPacket().getPayload().getRawBytes(),
            StandardCharsets.US_ASCII);
        assertTrue(payload.startsWith("!3800.00N/14458.20E>"));
    }

    @Test public void messagingCapableCompressedBeaconUsesConfiguredPositionFormat() throws Exception {
        Fixture f = fixture();
        BeaconData beacon = BeaconData.builder().latitude(-37.8608D).longitude(144.9700D)
            .messagingCapable(true).compressed(true).build();

        assertTrue(f.controller.submitPositionBeacon(beacon));

        APRSPacket packet = Parser.parseAX25(f.callbacks.lastTransmission.getRawAx25());
        PositionField position = (PositionField) packet.getPayload().getAprsData(APRSTypes.T_POSITION);
        assertEquals('=', packet.getDti());
        assertEquals("Compressed", position.getPositionSource());
        assertEquals(-37.8608D, position.getPosition().getLatitude(), 0.00001D);
        assertEquals(144.9700D, position.getPosition().getLongitude(), 0.00001D);
    }

    @Test public void submitPositionBeaconReportsTransportAcceptance() {
        Fixture f = fixture();
        BeaconData beacon = BeaconData.builder().latitude(-37D).longitude(144D).build();

        assertTrue(f.controller.submitPositionBeacon(beacon));
        assertEquals(1, f.packets.records.size());
        assertNull(f.callbacks.lastRequestedFrequencyHz);
        assertEquals(AprsController.RfTransmissionPurpose.POSITION_BEACON,
            f.callbacks.lastTransmissionPurpose);
        f.callbacks.retrySucceeds = false;
        assertFalse(f.controller.submitPositionBeacon(beacon));
        assertEquals(1, f.packets.records.size());
    }

    @Test public void invalidBeaconValuesAreNotSubmitted() {
        assertInvalidBeaconIsSkipped(BeaconData.builder().build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(Double.NaN).longitude(144D).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(180.001D).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .courseDegrees(361).speedKnots(1D).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .courseDegrees(1).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .compressed(true).courseDegrees(1).speedKnots(1D).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .courseDegrees(1).speedKnots(999.6D).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .altitudeMeters(Double.POSITIVE_INFINITY).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .weather(BeaconData.WeatherData.builder().windGustKnots(1_000).build()).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .weather(BeaconData.WeatherData.builder().windDirectionDegrees(225).build()).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .courseDegrees(90).speedKnots(50D)
            .weather(BeaconData.WeatherData.builder().windDirectionDegrees(225)
                .windSpeedKnots(10).build()).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .weather(BeaconData.WeatherData.builder().temperatureCelsius(600D).build()).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .weather(BeaconData.WeatherData.builder().humidityPercent(0).build()).build());
        assertInvalidBeaconIsSkipped(BeaconData.builder().latitude(-37D).longitude(144D)
            .weather(BeaconData.WeatherData.builder().pressureHectopascals(10_000D).build()).build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void beaconIntervalMustBePositive() {
        fixture().controller.setPositionBeaconingEnabled(true, 0L, 0L);
    }

    private void assertInvalidBeaconIsSkipped(BeaconData beacon) {
        Fixture f = fixture();
        assertFalse(f.controller.submitPositionBeacon(beacon));

        assertNull(f.callbacks.lastTransmission);
        assertTrue(f.packets.records.isEmpty());
    }

    @Test public void futurePersistedRetryIsLoadedOnceAndRetriedAtItsDeadline() {
        Fixture f = fixture();
        f.events.records.add(pendingEvent("VK3ABC", "7", 10_000L, 1));

        f.controller.tick(1_000L);
        f.controller.tick(9_500L);
        assertEquals(1, f.events.pendingLoadCount);

        f.controller.tick(10_000L);
        assertEquals(1, f.events.pendingLoadCount);
        assertEquals(1, f.callbacks.retryCount);
    }

    @Test public void outgoingMessageUpdatesThePendingRetryCache() {
        Fixture f = fixture();
        f.controller.tick(0L);
        APRSPacket frame = outgoingMessage("VK3ME", "VK3ABC", "hello", "7");
        f.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "7",
            144_390_000L, frame, frame.toAX25Frame());
        long retryAt = f.events.records.get(0).getNextRetryAtMs();

        f.controller.tick(retryAt - 1L);
        assertEquals(1, f.events.pendingLoadCount);

        f.controller.tick(retryAt);
        assertEquals(1, f.events.pendingLoadCount);
        assertEquals(1, f.callbacks.retryCount);
    }

    @Test public void digipeatAddsTxPacketToSameEventAndSuppressesSecondTransmission() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket frame = directMessageWithPath("VK3ABC", "VK3ME", "hello", "7", "WIDE1-1");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals(5, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(3, f.events.records.get(0).getPacketCount());
        assertTrue(f.events.records.get(0).isDigipeated());
    }

    @Test public void fillInDigipeaterReplacesWideOneOneWithItsCallsign() {
        Fixture f = fixture();
        f.controller.setCallsign("VK3ME-9");
        f.controller.setDigipeatingEnabled(true);
        APRSPacket frame = packetWithPath("WIDE1-1");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals("VK3ME-9*", f.callbacks.lastDigipeatedPacket.getDigipeaters().get(0).toString());
        assertEquals(Long.valueOf(144_390_000L), f.callbacks.lastRequestedFrequencyHz);
        assertEquals(AprsController.RfTransmissionPurpose.DIGIPEATED_PACKET,
            f.callbacks.lastTransmissionPurpose);
    }

    @Test public void fillInDigipeaterRejectsWideOneTwo() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket frame = packetWithPath("WIDE1-2");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(0, f.callbacks.digipeatCount);
    }

    @Test public void explicitDigipeaterAddressRequiresMatchingSsid() {
        Fixture f = fixture();
        f.controller.setCallsign("VK3ME-9");
        f.controller.setDigipeatingEnabled(true);
        APRSPacket wrongSsid = packetWithPath("VK3ME-1");
        APRSPacket exactAddress = packetWithPath("VK3ME-9");

        f.controller.handle(wrongSsid, AprsSource.RX_RF, 144_390_000L,
            wrongSsid.toAX25Frame());
        f.controller.handle(exactAddress, AprsSource.RX_RF, 144_390_000L,
            exactAddress.toAX25Frame());

        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals("VK3ME-9*", f.callbacks.lastDigipeatedPacket.getDigipeaters().get(0).toString());
    }

    @Test public void duplicateSuppressionIgnoresChangingDigipeaterPath() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket direct = packetWithPath("WIDE1-1");
        Digipeater prior = new Digipeater("VK3D1");
        prior.setUsed(true);
        APRSPacket relayed = new APRSPacket("VK3ABC", "APRS",
            Arrays.asList(prior, new Digipeater("WIDE1-1")),
            ">test".getBytes(StandardCharsets.US_ASCII));

        f.controller.handle(direct, AprsSource.RX_RF, 144_390_000L, direct.toAX25Frame());
        f.controller.handle(relayed, AprsSource.RX_RF, 144_390_000L, relayed.toAX25Frame());

        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals(3, f.packets.records.size());
    }

    @Test public void malformedPacketIsNeverDigipeated() throws Exception {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket malformed = Parser.parse("VK3ABC>APRS,WIDE1-1:Ainvalid");

        f.controller.handle(malformed, AprsSource.RX_RF, 144_390_000L,
            malformed.toAX25Frame());

        assertTrue(malformed.hasFault());
        assertEquals(0, f.callbacks.digipeatCount);
        assertEquals(1, f.packets.records.size());
    }

    @Test public void rejectedDigipeatTransmissionDoesNotSetIndicator() {
        Fixture f = fixture();
        f.callbacks.digipeatSucceeds = false;
        f.controller.setDigipeatingEnabled(true);
        APRSPacket frame = packetWithPath("WIDE1-1");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.callbacks.digipeatCount);
        assertFalse(f.events.records.get(0).isDigipeated());
        assertEquals(1, f.packets.records.size());
    }

    @Test public void handleSnapshotsCallerInputsBeforeCallbacksRun() {
        Fixture f = fixture();
        f.controller.setIgateEnabled(true);
        APRSPacket frame = packetWithPath("WIDE1-1");
        byte[] raw = frame.toAX25Frame();
        byte[] expected = raw.clone();
        f.callbacks.onGetCallsign = () -> {
            frame.addDigipeater(new Digipeater("NOGATE"));
            raw[0] ^= 1;
        };

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, raw);
        f.controller.tick(Long.MAX_VALUE);
        frame.addDigipeater(new Digipeater("OTHER"));
        raw[1] ^= 1;

        assertEquals("VK3ABC>APRS,WIDE1-1,qAO,VK3ME:>test", f.callbacks.lastIgateLine);
        assertEquals("WIDE1-1", f.packets.records.get(0).getPath());
        assertArrayEquals(expected, f.packets.records.get(0).getRawAx25());
    }

    @Test public void digipeatCallbackMutationCannotChangeRecordedPacketOrEchoCache() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        f.callbacks.mutateTransmittedPacket = true;
        APRSPacket frame = packetWithPath("WIDE1-1");
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals("VK3ME*", f.packets.records.get(1).getPath());
        APRSPacket echo = f.callbacks.lastTransmission.getPacket();
        f.callbacks.lastDigipeatedPacket.addDigipeater(new Digipeater("LATER"));
        f.controller.handle(echo, AprsSource.RX_RF, 144_390_000L, echo.toAX25Frame());
        assertEquals(2, f.packets.records.size());
        assertEquals(1, f.callbacks.digipeatCount);
    }

    @Test public void retryCallbackRetainedObjectsCannotChangeStoredTransmission() {
        Fixture f = fixture();
        f.callbacks.mutateTransmittedPacket = true;
        AprsEvent original = pendingEvent("VK3ABC", "7", 0L, 1).toBuilder().id(1).build();
        f.events.records.add(original);
        f.controller.tick(0L);

        f.callbacks.lastTransmission.getPacket().addDigipeater(new Digipeater("LATER"));
        Assert.assertNotNull(f.callbacks.lastTransmission.getRawAx25());
        f.callbacks.lastTransmission.getRawAx25()[0] ^= 1;
        assertEquals("WIDE1-1", f.packets.records.get(0).getPath());
        assertArrayEquals(f.callbacks.lastTransmission.getRawAx25(), f.packets.records.get(0).getRawAx25());
        assertEquals(1, original.getTransmitAttempts());
        assertEquals(2, f.events.findById(1).getTransmitAttempts());
    }

    @Test public void igateForwardsEligibleRfPacketAndRecordsInternetTransmission() {
        Fixture f = fixture();
        f.controller.setIgateEnabled(true);
        APRSPacket frame = packetWithPath("WIDE1-1");

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, frame.toAX25Frame());

        assertEquals(1, f.callbacks.igateCount);
        assertEquals("VK3ABC>APRS,WIDE1-1,qAO,VK3ME:>test", f.callbacks.lastIgateLine);
        assertEquals(1, f.packets.records.size());
        f.callbacks.lastIgateSuccess.run();
        assertEquals(2, f.packets.records.size());
        AprsPacket uploaded = f.packets.records.get(1);
        assertEquals(AprsSource.TX_APRS_IS, uploaded.getSource());
        assertEquals(f.callbacks.lastIgateLine, uploaded.getRawTnc2());
        assertNull(uploaded.getFrequencyHz());
        assertNull(uploaded.getRawAx25());
    }

    @Test public void incomingAprsIsMessageIsDisplayedWithoutRfTransmissionOrAck() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        f.controller.setIgateEnabled(true);
        String line = "VK3ABC>APRS,TCPIP*,qAC,T2TEST::VK3ME   :hello{A7";

        f.controller.handleAprsIsPacket(line);

        assertEquals(1, f.events.records.size());
        assertEquals(1, f.packets.records.size());
        AprsEvent event = f.events.records.get(0);
        AprsPacket packet = f.packets.records.get(0);
        assertEquals(AprsEvent.MESSAGE_TYPE, event.getType());
        assertTrue(event.isInternetOnly());
        assertEquals(AprsSource.RX_APRS_IS, packet.getSource());
        assertEquals(line, packet.getRawTnc2());
        assertNull(packet.getRawAx25());
        assertEquals(1, f.callbacks.notificationCount);
        assertEquals(0, f.callbacks.acknowledgementCount);
        assertEquals(0, f.callbacks.digipeatCount);
        assertEquals(0, f.callbacks.igateCount);
    }

    @Test public void matchingRfCopyMergesWithInternetEventAndClearsInternetIndicator()
            throws Exception {
        Fixture f = fixture();
        String internet = "VK3ABC>APRS,TCPIP*,qAC,T2TEST:>same";
        APRSPacket rf = Parser.parse("VK3ABC>APRS,WIDE1-1:>same");

        f.controller.handleAprsIsPacket(internet);
        f.controller.handle(rf, AprsSource.RX_RF, 144_390_000L, rf.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(2, f.packets.records.size());
        assertEquals(2, f.events.records.get(0).getPacketCount());
        assertFalse(f.events.records.get(0).isInternetOnly());
    }

    @Test public void igateRejectsForbiddenPathsQueriesAndNonRfPackets() throws Exception {
        Fixture f = fixture();
        f.controller.setIgateEnabled(true);
        for (String path : Arrays.asList("TCPIP", "TCPXX", "NOGATE", "RFONLY", "qAR")) {
            APRSPacket frame = packetWithPath(path);
            f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        }
        APRSPacket query = new APRSPacket("VK3ABC", "APRS", Collections.emptyList(),
            "?APRS?".getBytes(StandardCharsets.US_ASCII));
        f.controller.handle(query, AprsSource.RX_RF, 144_390_000L, query.toAX25Frame());
        APRSPacket notRf = packetWithPath("WIDE1-1");
        f.controller.handle(notRf, AprsSource.UNKNOWN, null, notRf.toAX25Frame());
        APRSPacket internetThirdParty = Parser.parse(
            "VK3DIG>APRS:}VK3ABC>APRS,TCPIP*:>test");
        f.controller.handle(internetThirdParty, AprsSource.RX_RF, 144_390_000L,
            internetThirdParty.toAX25Frame());

        assertEquals(0, f.callbacks.igateCount);
    }

    @Test public void igateStripsNonInternetThirdPartyWrapper() throws Exception {
        Fixture f = fixture();
        f.controller.setIgateEnabled(true);
        APRSPacket frame = Parser.parse(
            "VK3DIG>APRS,WIDE1-1:}VK3ABC>APRS,WIDE2-1:>test");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.callbacks.igateCount);
        assertEquals("VK3ABC>APRS,WIDE2-1,qAO,VK3ME:>test", f.callbacks.lastIgateLine);
    }

    @Test public void stationAddressRulesRemainCompatible() {
        assertTrue(AprsController.requiresAcknowledgement("VK3ABC-7"));
        assertFalse(AprsController.requiresAcknowledgement("BLN1CQ"));
        assertFalse(AprsController.requiresAcknowledgement("QST"));
        assertFalse(AprsController.requiresAcknowledgement("ALL"));
        assertFalse(AprsController.requiresAcknowledgement("CQ"));
        assertFalse(AprsController.requiresAcknowledgement(null));
    }

    @Test public void rfTransmissionPurposeDescribesReliableMessageAcknowledgement() {
        assertTrue(AprsController.RfTransmissionPurpose.OUTGOING_MESSAGE.expectsAcknowledgement());
        assertTrue(AprsController.RfTransmissionPurpose.RELIABLE_MESSAGE_RETRY.expectsAcknowledgement());
        assertFalse(AprsController.RfTransmissionPurpose.ACKNOWLEDGEMENT.expectsAcknowledgement());
        assertFalse(AprsController.RfTransmissionPurpose.POSITION_BEACON.expectsAcknowledgement());
        assertFalse(AprsController.RfTransmissionPurpose.BROADCAST.expectsAcknowledgement());
        assertFalse(AprsController.RfTransmissionPurpose.DIGIPEATED_PACKET.expectsAcknowledgement());
    }

    private Fixture fixture() {
        return fixture(Clock.systemUTC());
    }

    private Fixture fixture(Clock clock) {
        FakePacketRepository packets = new FakePacketRepository();
        FakeEventRepository events = new FakeEventRepository();
        FakeRepository repository = new FakeRepository(packets, events);
        FakeCallbacks callbacks = new FakeCallbacks();
        callbacks.repository = repository;
        AprsController controller = new AprsController(repository, callbacks, clock);
        controller.setCallsign(callbacks.callsign);
        controller.setTxDestination("APRS");
        return new Fixture(repository, packets, events, callbacks, controller);
    }

    private APRSPacket directMessage(String from, String to, String body, String identifier) {
        return new APRSPacket(from, "APRS", Collections.emptyList(),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket directMessageWithPath(String from, String to, String body, String identifier, String path) {
        return new APRSPacket(from, "APRS", Collections.singletonList(new Digipeater(path)),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket outgoingMessage(String from, String to, String body, String identifier) {
        return new APRSPacket(from, "DST", Collections.singletonList(new Digipeater("WIDE1-1")),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket deliveryResponse(String from, String response) {
        return directMessage(from, "VK3ME", response, null);
    }

    private APRSPacket packetWithPath(String path) {
        return new APRSPacket("VK3ABC", "APRS", Collections.singletonList(new Digipeater(path)),
            ">test".getBytes(StandardCharsets.US_ASCII));
    }

    private static AprsEvent pendingEvent(String destination, String identifier,
                                          long nextRetryAt, int attempts) {
        AprsEvent event = AprsEvent.builder().type(AprsEvent.MESSAGE_TYPE)
            .fromCallsign("VK3ME")
            .toCallsign(destination)
            .messageIdentifier(identifier)
            .body("hello")
            .deliveryState(AprsEvent.DELIVERY_PENDING)
            .nextRetryAtMs(nextRetryAt)
            .transmitAttempts(attempts).build();
        return event;
    }

    private static AprsEvent terminalEvent(int state) {
        AprsEvent event = pendingEvent("VK3XYZ", "9", 0L, 1);
        event = event.toBuilder().deliveryState(state).build();
        return event;
    }

    private void assertRetry(AprsEvent event, int attempts, long nextRetryAt) {
        assertEquals(attempts, event.getTransmitAttempts());
        assertEquals(Long.valueOf(nextRetryAt), event.getNextRetryAtMs());
    }

    private static final class Fixture {
        final FakeRepository repository;
        final FakePacketRepository packets;
        final FakeEventRepository events;
        final FakeCallbacks callbacks;
        final AprsController controller;

        Fixture(FakeRepository repository, FakePacketRepository packets, FakeEventRepository events,
                FakeCallbacks callbacks, AprsController controller) {
            this.repository = repository;
            this.packets = packets;
            this.events = events;
            this.callbacks = callbacks;
            this.controller = controller;
        }
    }

    private static final class FakePacketRepository {
        final List<AprsPacket> records = new ArrayList<>();

        long insert(AprsPacket packet) {
            packet = packet.toBuilder().id(records.size() + 1L).build();
            records.add(packet);
            return packet.getId();
        }
    }

    private static final class FakeEventRepository {
        final List<AprsEvent> records = new ArrayList<>();
        int pendingLoadCount;

        List<AprsEvent> loadPendingReliableEvents() {
            pendingLoadCount++;
            List<AprsEvent> pending = new ArrayList<>();
            for (AprsEvent event : records) {
                if (event.getDeliveryState() == AprsEvent.DELIVERY_PENDING
                    && event.getNextRetryAtMs() != null) {
                    pending.add(event);
                }
            }
            return pending;
        }

        long insert(AprsEvent event) {
            event = event.toBuilder().id(records.size() + 1L).build();
            records.add(event);
            return event.getId();
        }

        void update(AprsEvent event) {
            // Persist the supplied snapshot; the controller no longer edits stored records.
            records.set(records.indexOf(findById(event.getId())), event);
        }

        AprsEvent findById(long id) {
            for (AprsEvent event : records) {
                if (event.getId() == id) {
                    return event;
                }
            }
            return null;
        }

        AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs) {
            for (int i = records.size() - 1; i >= 0; i--) {
                AprsEvent event = records.get(i);
                if (dedupKey.equals(event.getDedupKey()) && event.getLastSeenMs() >= sinceMs) {
                    return event;
                }
            }
            return null;
        }

        AprsEvent findPendingOutgoingEvent(String local, String remote, String identifier) {
            for (int i = records.size() - 1; i >= 0; i--) {
                AprsEvent event = records.get(i);
                if (event.getDeliveryState() == AprsEvent.DELIVERY_PENDING
                    && local.equals(event.getFromCallsign()) && remote.equals(event.getToCallsign())
                    && identifier.equals(event.getMessageIdentifier())) {
                    return event;
                }
            }
            return null;
        }
    }

    private static final class FakeRepository implements AprsRepository {
        private final FakePacketRepository packets;
        private final FakeEventRepository events;
        int transactionCount;
        int transactionDepth;
        boolean failCommitAfterBody;
        boolean allowSyntheticInitialFrame = true;
        final List<AprsEvent> projectedEvents = new ArrayList<>();
        boolean projectionRanInTransaction;

        FakeRepository(FakePacketRepository packets, FakeEventRepository events) {
            this.packets = packets;
            this.events = events;
        }

        @Override public <T> T inTransaction(Supplier<T> operation) {
            transactionCount++;
            List<AprsPacket> packetsBefore = new ArrayList<>(packets.records);
            List<AprsEvent> eventsBefore = new ArrayList<>(events.records);
            transactionDepth++;
            try {
                T result = operation.get();
                if (failCommitAfterBody) {
                    failCommitAfterBody = false;
                    packets.records.clear();
                    packets.records.addAll(packetsBefore);
                    events.records.clear();
                    events.records.addAll(eventsBefore);
                    throw new IllegalStateException("simulated commit failure");
                }
                return result;
            } finally {
                transactionDepth--;
            }
        }

        @Override public long insert(AprsPacket packet) { return packets.insert(packet); }
        @Override public long insert(AprsEvent event) { return events.insert(event); }
        @Override public void update(AprsEvent event) { events.update(event); }
        @Override public void onEventPersisted(AprsEvent event) {
            projectionRanInTransaction = transactionDepth > 0;
            projectedEvents.add(event);
        }
        @Override public List<AprsEvent> loadPendingReliableEvents() {
            return events.loadPendingReliableEvents();
        }
        @Override public AprsEvent findById(long id) { return events.findById(id); }
        @Override public AprsEvent findRecentByDedupKey(String key, long since) {
            return events.findRecentByDedupKey(key, since);
        }
        @Override public AprsEvent findPendingOutgoingEvent(String local, String remote,
                                                             String identifier) {
            return events.findPendingOutgoingEvent(local, remote, identifier);
        }
        @Override public AprsPacket findInitialRfTransmission(long eventId) {
            for (AprsPacket packet : packets.records) {
                if (Long.valueOf(eventId).equals(packet.getEventId())
                    && AprsSource.TX_RF.equals(packet.getSource())) {
                    return packet;
                }
            }
            AprsEvent event = events.findById(eventId);
            if (event == null || !allowSyntheticInitialFrame) {
                return null;
            }
            APRSPacket frame = new APRSPacket(event.getFromCallsign(), "DST",
                Collections.singletonList(new Digipeater("WIDE1-1")),
                MessagePacket.createMessagePayload(event.getToCallsign(), event.getBody(),
                    event.getMessageIdentifier()));
            return AprsPacket.builder().eventId(eventId).source(AprsSource.TX_RF)
                .rawAx25(frame.toAX25Frame()).build();
        }
    }

    private static final class FakeCallbacks implements AprsController.Callbacks {
        String callsign = "VK3ME";
        int retryCount;
        int beaconCount;
        int digipeatCount;
        int notificationCount;
        AprsEvent lastIncomingMessage;
        boolean lastIncomingMessageForLocal;
        int acknowledgementCount;
        int igateCount;
        boolean retrySucceeds = true;
        boolean cancelRetry;
        boolean digipeatSucceeds = true;
        APRSPacket lastDigipeatedPacket;
        String lastIgateLine;
        Long lastIgateEventId;
        Runnable lastIgateSuccess;
        Runnable onGetCallsign;
        boolean mutateTransmittedPacket;
        BeaconData beaconData;
        FakeRepository repository;
        boolean incomingMessageDuringTransaction;
        boolean acknowledgementDuringTransaction;
        AprsController.Transmission lastTransmission;
        Long lastRequestedFrequencyHz;
        AprsController.RfTransmissionPurpose lastTransmissionPurpose;

        @Override public void onIncomingMessage(AprsEvent event, boolean forLocal) {
            incomingMessageDuringTransaction = repository.transactionDepth > 0;
            notificationCount++;
            lastIncomingMessage = event;
            lastIncomingMessageForLocal = forLocal;
        }

        @Override public AprsController.RfTransmission submitRf(APRSPacket packet, Long frequencyHz,
                                                                 AprsController.RfTransmissionPurpose purpose) {
            String payload = new String(packet.getPayload().getRawBytes(), StandardCharsets.ISO_8859_1);
            lastRequestedFrequencyHz = frequencyHz;
            lastTransmissionPurpose = purpose;
            acknowledgementDuringTransaction = repository != null && repository.transactionDepth > 0;
            if (payload.contains(":ack")) {
                acknowledgementCount++;
                return accepted(packet);
            }
            if ("VK3ME".equals(packet.getSourceCall())) {
                retryCount++;
                return retrySucceeds ? accepted(packet)
                    : AprsController.RfTransmission.builder().retryAllowed(!cancelRetry).build();
            }
            digipeatCount++;
            if (!digipeatSucceeds) {
                return null;
            }
            lastDigipeatedPacket = packet;
            return accepted(packet);
        }

        @Override public BeaconData getBeaconData() {
            beaconCount++;
            return beaconData;
        }

        private AprsController.Transmission transmission(APRSPacket packet) {
            byte[] raw = packet.toAX25Frame();
            lastTransmission =  AprsController.Transmission.builder()
                    .packet(packet)
                    .rawAx25(raw)
                    .frequencyHz(144_390_000L)
                    .build();
            if (mutateTransmittedPacket) {
                packet.addDigipeater(new Digipeater("CHANGED"));
                raw[0] ^= 1;
            }
            return lastTransmission;
        }

        private AprsController.RfTransmission accepted(APRSPacket packet) {
            return AprsController.RfTransmission.builder().transmission(transmission(packet)).build();
        }

        @Override public boolean submitAprsIs(String tnc2, Runnable onSuccess) {
            if (onGetCallsign != null) {
                Runnable action = onGetCallsign;
                onGetCallsign = null;
                action.run();
            }
            igateCount++;
            lastIgateLine = tnc2;
            lastIgateSuccess = onSuccess;
            return true;
        }
    }
}
