package io.github.dkaukov.aprs.api;

import static org.junit.Assert.*;

import io.github.dkaukov.aprs.AprsEvent;
import io.github.dkaukov.aprs.AprsPacket;
import io.github.dkaukov.aprs.parser.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OwnershipTest {
    @Test public void modelAccessorsAndCopiesPreserveDataWithoutSharingBuffers() {
        AprsPacket packet = new AprsPacket();
        byte[] frame = {1, 2, 3};
        packet.setRawAx25(frame);
        packet.setSource("RF");
        packet.setId(7);
        frame[0] = 9;
        packet.getRawAx25()[1] = 9;
        AprsPacket copy = packet.copy();
        copy.setRawAx25(new byte[] {4});
        assertArrayEquals(new byte[] {1, 2, 3}, packet.getRawAx25());
        assertEquals("RF", copy.getSource());
        assertEquals(7, copy.getId());

        AprsEvent event = new AprsEvent();
        event.setBody("original");
        event.setDeliveryState(AprsEvent.DELIVERY_PENDING);
        event.setInternetOnly(true);
        AprsEvent snapshot = event.copy();
        snapshot.setBody("changed");
        assertEquals("original", event.getBody());
        assertEquals(AprsEvent.DELIVERY_PENDING, snapshot.getDeliveryState());
        assertTrue(snapshot.isInternetOnly());
    }

    @Test public void packetOwnsItsDigipeatersAndReturnsDetachedElements() {
        Digipeater digi = new Digipeater("WIDE1-1");
        List<Digipeater> path = new ArrayList<>();
        path.add(digi);
        APRSPacket packet = new APRSPacket("VK3ABC", "APRS", path,
            ">hello".getBytes(StandardCharsets.US_ASCII));
        digi.setCallsign("OTHER");
        path.clear();
        packet.getDigipeaters().get(0).setUsed(true);
        assertEquals("WIDE1-1", packet.getDigiString());
        assertThrows(UnsupportedOperationException.class,
            () -> packet.getDigipeaters().clear());

        Digipeater extra = new Digipeater("VK3XYZ");
        packet.addDigipeater(extra);
        extra.setUsed(true);
        assertEquals("WIDE1-1,VK3XYZ", packet.getDigiString());
    }

    @Test public void positionAndExtensionCopiesPreserveAllState() {
        Position position = new Position(49, -72, 2, '/', '>');
        position.setAltitude(123);
        position.setCsTField("abc");
        PositionField field = new PositionField(position, "test");
        position.setLatitude(0);
        field.getPosition().setAltitude(0);
        assertEquals(49, field.getPosition().getLatitude(), 0);
        assertEquals(123, field.getPosition().getAltitude());
        assertEquals(2, field.getPosition().getPositionAmbiguity());
        assertEquals("abc", field.getPosition().getCsTField());

        CourseAndSpeedExtension extension = new CourseAndSpeedExtension();
        extension.setCourse(90);
        extension.setSpeed(12);
        field.setExtension(extension);
        extension.setSpeed(0);
        ((CourseAndSpeedExtension) field.getExtension()).setCourse(0);
        PositionField copy = field.copy();
        assertEquals(90, ((CourseAndSpeedExtension) copy.getExtension()).getCourse());
        assertEquals(12, ((CourseAndSpeedExtension) copy.getExtension()).getSpeed());
        copy.setPosition(new Position(1, 2));
        assertEquals(49, field.getPosition().getLatitude(), 0);
    }

    @Test public void payloadSnapshotsAreDeepAndUpdatesAreExplicit() throws Exception {
        APRSPacket packet = Parser.parse("VK3ABC>APRS:!4903.50N/07201.75W-Test");
        InformationField payload = packet.getPayload();
        PositionField position = (PositionField) payload.getAprsData(APRSTypes.T_POSITION);
        position.setPosition(new Position(1, 2));
        assertEquals(49.05833, ((PositionField) payload.getAprsData(APRSTypes.T_POSITION))
            .getPosition().getLatitude(), 0.00001);
        payload.addAprsData(APRSTypes.T_POSITION, position);
        position.setPosition(new Position(3, 4));
        assertEquals(49.05833, ((PositionField) packet.getPayload().getAprsData(APRSTypes.T_POSITION))
            .getPosition().getLatitude(), 0.00001);
        packet.setPayload(payload);
        ((PositionField) payload.getAprsData().get(APRSTypes.T_POSITION))
            .setPosition(new Position(5, 6));
        assertEquals(1, ((PositionField) packet.getPayload().getAprsData(APRSTypes.T_POSITION))
            .getPosition().getLatitude(), 0);
        assertThrows(UnsupportedOperationException.class, () -> payload.getAprsData().clear());
        assertThrows(UnsupportedOperationException.class, () -> payload.getTypes().clear());
    }

    @Test public void thirdPartyCopiesRetainParsedStateAndIsolateInnerPackets() throws Exception {
        APRSPacket inner = Parser.parse("VK3ABC>APRS::VK3ME    :hello{A7");
        ThirdPartyField field = new ThirdPartyField(new byte[] {'}'}, inner);
        inner.addDigipeater(new Digipeater("OTHER"));
        APRSPacket snapshot = field.getInnerPacket();
        snapshot.setComment("changed");
        snapshot.addDigipeater(new Digipeater("ANOTHER"));
        APRSPacket copy = field.copy().getInnerPacket();
        assertEquals("", copy.getDigiString());
        assertNull(copy.getComment());
        assertEquals(inner.getReceivedTimestamp(), copy.getReceivedTimestamp());
        assertEquals(inner.getOriginalString(), copy.getOriginalString());
        MessagePacket message = (MessagePacket) copy.getPayload();
        assertEquals("hello", message.getMessageBody());
        assertEquals("A7", message.getMessageNumber());
        message.setMessageBody("changed");
        assertEquals("hello", ((MessagePacket) copy.getPayload()).getMessageBody());
    }

    @Test public void objectPositionGetterReturnsAnIndependentSnapshot() throws Exception {
        APRSPacket packet = Parser.parse("VK3ABC>APRS:;TEST     *111111z4903.50N/07201.75W-Test");
        ObjectField object = (ObjectField) packet.getPayload().getAprsData(APRSTypes.T_OBJECT);
        object.getPosition().setPosition(new Position(0, 0));
        assertEquals(49.05833, object.copy().getPosition().getPosition().getLatitude(), 0.00001);
    }
}
