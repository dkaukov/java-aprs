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

package io.github.dkaukov.aprs.parser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Calendar;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import org.junit.Test;

public class ParserRegressionTest {
    @Test public void aprsDataDoesNotAdvertiseHashBasedOrdering() {
        assertFalse(Comparable.class.isAssignableFrom(APRSData.class));
    }

    @Test public void nullPathMeansNoDigipeaters() throws Exception {
        APRSPacket packet = new APRSPacket("VK3ABC", "APRS", null, new byte[] {'>', 'x'});
        assertTrue(packet.getDigipeaters().isEmpty());
        assertArrayEquals(Parser.parse("VK3ABC>APRS:>x").toAX25Frame(), packet.toAX25Frame());
    }

    @Test public void parsingDoesNotWriteDiagnosticsToStderr() throws Exception {
        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8.name())) {
            System.setErr(stream);
            Parser.parse("VK3ABC>APRS:/111111z4903.50N/07201.75W-Test");
            // Exercise the legacy timestamp branch directly, including its error path.
            assertThrows(Exception.class, () -> PositionParser.parseUncompressed(
                "/111111z4903.50N/07201.75W-Test".getBytes(StandardCharsets.US_ASCII), 1));
            assertThrows(UnparsablePositionException.class,
                () -> PositionParser.parseUncompressed("1234567".getBytes(StandardCharsets.US_ASCII), 0));
            Parser.parse("VK3ABC>APRS:;short");
            Parser.parse("VK3ABC>APRS:!12345678901234567890123");
            assertThrows(IndexOutOfBoundsException.class, () -> new InformationField(new byte[0]));
        } finally {
            System.setErr(previous);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8.name()));
    }

    @Test public void protocolCasingIsIndependentOfTurkishDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Callsign callsign = new Callsign("iz1abc-9");
            assertEquals("IZ1ABC-9", callsign.toString());
            callsign.setCallsign("iz2abc");
            assertEquals("IZ2ABC-9", callsign.toString());
            assertArrayEquals(new Callsign("IZ2ABC-9").toAX25(), callsign.toAX25());

            APRSPacket constructed = new APRSPacket("iz1abc", "apzizi",
                Collections.emptyList(), new byte[] {'>', 'I'});
            assertEquals("IZ1ABC", constructed.getSourceCall());
            assertEquals("APZIZI", constructed.getDestinationCall());
            assertArrayEquals(constructed.toAX25Frame(), Parser.parseAX25(constructed.toAX25Frame()).toAX25Frame());

            APRSPacket parsed = Parser.parse("iz1abc>apzizi,qAR,igate:>Istanbul");
            assertEquals("IZ1ABC", parsed.getSourceCall());
            assertEquals("APZIZI", parsed.getDestinationCall());
            assertEquals("IGATE", parsed.getIgate());
            MessagePacket message = (MessagePacket) Parser.parse(
                "iz1abc>aprs::iz2abc   :Istanbul{I7").getPayload();
            assertEquals("IZ2ABC", message.getTargetCallsign());
            assertEquals("Istanbul", message.getMessageBody());
            assertEquals("I7", message.getMessageNumber());
            MessagePacket ack = (MessagePacket) Parser.parse(
                "iz1abc>aprs::iz2abc   :ACKI7").getPayload();
            assertTrue(ack.isAck());
            assertEquals("I7", ack.getMessageNumber());
            assertEquals(Utilities.doHash("IZ1ABC"), Utilities.doHash("iz1abc-9"));
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test public void ax25RejectsInvalidSliceBoundsWithoutOverflow() {
        byte[] buffer = new byte[64];
        assertThrows(IllegalArgumentException.class, () -> Parser.parseAX25(null));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseAX25(null, 0, 16));
        int[][] invalid = {{-1, 17}, {0, -1}, {0, 0}, {0, 15}, {65, 17},
            {64, 17}, {48, 17}, {0, 65}, {1, Integer.MAX_VALUE},
            {Integer.MAX_VALUE, 17}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
        for (int[] slice : invalid) {
            assertThrows(IllegalArgumentException.class,
                () -> Parser.parseAX25(buffer, slice[0], slice[1]));
        }
    }

    @Test public void ax25RejectsEveryHeaderTruncationEvenWithValidBytesBeyondSlice() throws Exception {
        for (String path : new String[] {"", ",WIDE1-1", ",WIDE1-1,WIDE2-1"}) {
            byte[] frame = Parser.parse("VK3ABC>APRS" + path + ":>test").toAX25Frame();
            int headerLength = frame.length - 5;
            byte[] padded = new byte[frame.length + 8];
            System.arraycopy(frame, 0, padded, 3, frame.length);
            // Includes partial destination/source/path addresses, absent control/PID,
            // and a complete header with no APRS data type identifier.
            for (int length = 0; length <= headerLength; length++) {
                final int sliceLength = length;
                assertThrows("Truncated header length " + length + " path " + path,
                    IllegalArgumentException.class, () -> Parser.parseAX25(padded, 3, sliceLength));
                byte[] truncated = Arrays.copyOf(frame, length);
                assertThrows(IllegalArgumentException.class, () -> Parser.parseAX25(truncated));
            }
            assertArrayEquals(frame, Parser.parseAX25(padded, 3, frame.length).toAX25Frame());
        }
    }

    @Test public void ax25RejectsUnterminatedAddressChainInsideSlice() throws Exception {
        byte[] frame = Parser.parse("VK3ABC>APRS,WIDE1-1:>test").toAX25Frame();
        frame[20] &= (byte) 0xfe;
        byte[] padded = Arrays.copyOf(frame, frame.length + 32);
        assertThrows(IllegalArgumentException.class, () -> Parser.parseAX25(padded, 0, frame.length));
    }

    @Test public void ax25PayloadDoesNotIncludeBufferSuffix() throws Exception {
        byte[] frame = Parser.parse("VK3ABC>APRS:>test").toAX25Frame();
        byte[] padded = new byte[frame.length + 9];
        Arrays.fill(padded, (byte) 'X');
        System.arraycopy(frame, 0, padded, 4, frame.length);
        assertArrayEquals(new byte[] {'>'}, Parser.parseAX25(padded, 4, 17).getPayload().getRawBytes());
        assertArrayEquals(frame, Parser.parseAX25(padded, 4, frame.length).toAX25Frame());
    }

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
