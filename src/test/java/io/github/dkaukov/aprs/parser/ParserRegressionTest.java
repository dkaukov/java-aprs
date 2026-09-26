package io.github.dkaukov.aprs.parser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import org.junit.Test;

public class ParserRegressionTest {
    @Test public void parsedMessageRetainsRecipientBodyAndIdentifier() throws Exception {
        APRSPacket packet = Parser.parse("VK3ABC>APRS::VK3ME    :hello{A7");
        assertTrue(packet.getPayload() instanceof MessagePacket);
        MessagePacket message = (MessagePacket) packet.getPayload();
        assertEquals("VK3ME", message.getTargetCallsign());
        assertEquals("hello", message.getMessageBody());
        assertEquals("A7", message.getMessageNumber());
        assertFalse(packet.hasFault());
    }

    @Test public void parsedAcknowledgementRetainsItsType() throws Exception {
        MessagePacket message = (MessagePacket) Parser.parse(
            "VK3ABC>APRS::VK3ME    :ackA7").getPayload();
        assertTrue(message.isAck());
        assertEquals("A7", message.getMessageNumber());
    }

    @Test public void unpaddedRecipientDoesNotTruncateTheMessageBody() throws Exception {
        MessagePacket message = (MessagePacket) Parser.parse(
            "VK3ABC>APRS::VK3ME   :hello{A7").getPayload();
        assertEquals("VK3ME", message.getTargetCallsign());
        assertEquals("hello", message.getMessageBody());
        assertFalse(message.hasFault());
    }

    @Test public void malformedMessageIsMarkedFaulty() throws Exception {
        assertTrue(Parser.parse("VK3ABC>APRS::short").hasFault());
        assertTrue(Parser.parse("VK3ABC>APRS::VK3ME    xhello").hasFault());
    }

    @Test public void ax25RoundTripPreservesHighPayloadBytesAndOffsets() throws Exception {
        byte[] payload = {'>', (byte) 0x80, (byte) 0xff, (byte) 0xc3, (byte) 0xa9};
        APRSPacket original = new APRSPacket("VK3ABC-9", "APRS",
            Collections.emptyList(), payload);
        byte[] frame = original.toAX25Frame();
        APRSPacket parsed = Parser.parsePacket(frame);
        assertEquals("VK3ABC-9", parsed.getSourceCall());
        assertArrayEquals(payload, parsed.getPayload().getRawBytes());
        assertArrayEquals(frame, parsed.toAX25Frame());

        byte[] padded = new byte[frame.length + 6];
        System.arraycopy(frame, 0, padded, 3, frame.length);
        assertArrayEquals(frame, Parser.parseAX25(padded, 3, frame.length).toAX25Frame());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidAx25InputProducesAnArgumentError() {
        Parser.parsePacket(new byte[0]);
    }

    @Test public void emptyStringsAreComparedByValue() {
        Callsign callsign = new Callsign("VK3ABC");
        callsign.setSsid(new String(new char[0]));
        assertEquals("VK3ABC", callsign.toString());
        Position position = new Position(0, 0);
        position.setCsTField(new String(new char[0]));
        assertEquals(" sT", position.getCsTField());
    }

    @Test public void positionConvenienceConstructorPopulatesThisInstance() throws Exception {
        PositionField position = new PositionField(
            "!4903.50N/07201.75W-Test".getBytes(StandardCharsets.US_ASCII), "APRS");
        assertFalse(position.hasFault());
        assertEquals("Uncompressed", position.getPositionSource());
        assertEquals(49.05833, position.getPosition().getLatitude(), 0.00001);
        assertEquals(-72.02917, position.getPosition().getLongitude(), 0.00001);
        assertEquals("Test", position.getComment());
    }

    @Test public void unsupportedPositionTypeIsMarkedFaulty() throws Exception {
        PositionField position = new PositionField(
            ">status".getBytes(StandardCharsets.US_ASCII), "APRS", 1);
        assertTrue(position.hasFault());
    }

    @Test public void rawBuffersAreOwnedByTheParsedFields() throws Exception {
        byte[] payload = ">hello".getBytes(StandardCharsets.US_ASCII);
        InformationField information = new InformationField(payload);
        StatusField status = new StatusField(payload);
        status.setRawBytes(payload);
        payload[1] = 'X';
        information.getRawBytes()[1] = 'Y';
        status.getRawBytes()[1] = 'Y';
        assertEquals('h', information.getRawBytes()[1]);
        assertEquals('h', status.getRawBytes()[1]);

        byte[] itemBytes = ")ITEM!comment".getBytes(StandardCharsets.US_ASCII);
        ItemField item = new ItemField(itemBytes);
        itemBytes[1] = 'X';
        item.getRawBytes()[1] = 'Y';
        assertEquals(")ITEM!comment", item.toString());
    }

    @Test public void timestampAccessorsReturnIndependentValues() {
        APRSPacket packet = new APRSPacket("VK3ABC", "APRS", Collections.emptyList(),
            ">hello".getBytes(StandardCharsets.US_ASCII));
        long received = packet.getReceivedTimestamp().getTime();
        Date exposed = packet.getReceivedTimestamp();
        exposed.setTime(0);
        assertEquals(received, packet.getReceivedTimestamp().getTime());

        TimeField time = new TimeField();
        long reported = time.getReportedTimestamp().getTimeInMillis();
        Calendar calendar = time.getReportedTimestamp();
        calendar.setTimeInMillis(0);
        assertEquals(reported, time.getReportedTimestamp().getTimeInMillis());
    }

    @Test public void thirdPartyParsingRetainsTheAppendedDigipeater() throws Exception {
        APRSPacket packet = Parser.parse("VK3OUT>APRS:}VK3IN>APRS:>hello");
        ThirdPartyField thirdParty = (ThirdPartyField) packet.getPayload()
            .getAprsData(APRSTypes.T_THIRDPARTY);
        assertEquals("VK3OUT", thirdParty.getInnerPacket().getDigiString());
    }
}
