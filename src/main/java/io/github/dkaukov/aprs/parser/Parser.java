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
 * AVRS - http://avrs.sourceforge.net/
 *
 * Copyright (C) 2011 John Gorkos, AB0OO
 *
 * AVRS is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * AVRS is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AVRS; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA

 * Please note that significant portions of this code were taken from the JAVA FAP
 * translation by Matti Aarnio at http://repo.ham.fi/websvn/java-aprs-fap/
 *
 */

package io.github.dkaukov.aprs.parser;

import java.util.ArrayList;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/**
 * Parses APRS TNC2 lines and AX.25 UI frames into {@link APRSPacket} values.
 *
 * <p>TNC2 syntax is {@code SOURCE>DEST,VIA,VIA:payload}. AX.25 input excludes HDLC flags, FCS,
 * and KISS transport framing. When wire bytes are represented as a {@link String}, ISO-8859-1 is
 * used so each byte is preserved.</p>
 *
 * @author johng
 */
public class Parser {


    /**
     * @param args
     */
    public static void main( String[] args ) {
        if ( args.length > 0 ) {
            try {
                APRSPacket packet = Parser.parse(args[0]);
                System.out.println("From:    "+packet.getSourceCall());
                System.out.println("To:    "+packet.getDestinationCall());
                System.out.println("Via:    "+packet.getDigiString());
                System.out.println("DTI:    "+packet.getDti());
                System.out.println("Valid:    "+packet.isAprs());
                InformationField data = packet.getPayload();
                System.out.println("Data:    " + data);
                if ( packet.isAprs() && data != null) {
                    System.out.println("    Type:    " + data.getClass().getName());
                    System.out.println("    Messaging:    " + data.canMessage);
                    System.out.println("    Comment:    " + data.getComment());
                    System.out.println("    Extension:    " + data.getExtension());
                }
            } catch ( Exception ex ) {
                System.err.println("Unable to parse:  "+ex);
                ex.printStackTrace();
            }
        }
    }


    /**
     * Convenience wrapper for {@link #parseAX25(byte[])} that converts checked parse failures.
     *
     * @param rawPacket AX.25 UI frame without flags, FCS, or KISS framing
     * @return parsed APRS packet
     * @throws IllegalArgumentException if the frame is invalid or cannot be parsed
     */
    public static APRSPacket parsePacket(byte[] rawPacket) {
        try {
            return parseAX25(rawPacket);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid AX.25 packet", ex);
        }
    }


    /**
     * Parses one TNC2 packet line.
     *
     * @param packet TNC2 text in {@code SOURCE>DEST[,PATH]:payload} form
     * @return parsed APRS packet retaining the original input text
     * @throws Exception if the TNC2 structure or APRS payload cannot be parsed
     */
    public static APRSPacket parse(final String packet) throws Exception {
        int cs = packet.indexOf('>');
        String source = packet.substring(0,cs).toUpperCase(Locale.ROOT);
        int ms = packet.indexOf(':');
        String digiList = packet.substring(cs+1,ms);
        String[] digiTemp = digiList.split(",");
        String dest = digiTemp[0].toUpperCase(Locale.ROOT);
        ArrayList<Digipeater> digis = Digipeater.parseList(digiList, false);
        String body = packet.substring(ms+1);
        APRSPacket ap = parseBody(source, dest, digis, body);
        ap.setOriginalString(packet);
        return ap;
    }


    /**
     * Parses a complete AX.25 UI frame.
     *
     * @param packet AX.25 UI bytes without flags, FCS, or KISS framing
     * @return parsed APRS packet
     * @throws IllegalArgumentException if the frame is null, malformed, or truncated
     * @throws Exception if the APRS payload cannot be parsed
     */
    public static APRSPacket parseAX25(byte[] packet) throws Exception {
            if (packet == null) {
                throw new IllegalArgumentException("AX.25 packet must not be null");
            }
            return parseAX25(packet, 0, packet.length);
        }

    /**
     * Parses an AX.25 UI frame contained in a slice of a backing buffer.
     *
     * @param packet backing buffer containing an AX.25 UI frame without flags, FCS, or KISS framing
     * @param offset first frame byte
     * @param len number of frame bytes
     * @return parsed APRS packet
     * @throws IllegalArgumentException if the slice or AX.25 header is invalid or truncated
     * @throws Exception if the APRS payload cannot be parsed
     */
    public static APRSPacket parseAX25(byte[] packet, int offset, int len) throws Exception {
            // Subtraction avoids overflow when callers supply very large offsets/lengths.
            if (packet == null || offset < 0 || offset > packet.length
                || len < 16 || len > packet.length - offset) {
                throw new IllegalArgumentException("Invalid AX.25 frame slice");
            }
            int frameEnd = offset + len;
            int pos = offset;
            requireFrameBytes(pos, 7, frameEnd, "destination address");
            String dest = new Callsign(packet, pos).toString();
            pos += 7;
            requireFrameBytes(pos, 7, frameEnd, "source address");
            String source = new Callsign(packet, pos).toString();
            pos += 7;
            ArrayList<Digipeater> digis = new ArrayList<Digipeater>();
            while ((packet[pos - 1] & 1) == 0) {
                requireFrameBytes(pos, 7, frameEnd, "digipeater address");
                Digipeater d = new Digipeater(packet, pos);
                digis.add(d);
                pos += 7;
            }
            requireFrameBytes(pos, 2, frameEnd, "control and PID");
            if (packet[pos] != 0x03 || packet[pos + 1] != -16 /*0xf0*/) {
                throw new IllegalArgumentException("control + pid must be 0x03 0xF0!");
            }
            pos += 2;
            requireFrameBytes(pos, 1, frameEnd, "APRS data type identifier");
            String body = new String(packet, pos, frameEnd - pos, StandardCharsets.ISO_8859_1);
            return parseBody(source, dest, digis, body);
        }

    private static void requireFrameBytes(int pos, int count, int frameEnd, String field) {
            if (count > frameEnd - pos) {
                throw new IllegalArgumentException("Truncated AX.25 " + field);
            }
        }

    /**
     * Parses an APRS information field after link-layer addresses have already been decoded.
     *
     * <p>This lower-level entry point expects {@code body} to begin with the APRS data type
     * identifier. ISO-8859-1 conversion preserves its wire bytes.</p>
     *
     * @param source source callsign
     * @param dest destination callsign; it can affect compressed-position decoding
     * @param digis digipeater path
     * @param body APRS information field beginning with its data type identifier
     * @return parsed APRS packet
     * @throws Exception if a typed APRS field cannot be parsed
     */

    public static APRSPacket parseBody(String source, String dest, ArrayList<Digipeater> digis, String body) throws Exception {
        // Preserve each wire byte, including non-ASCII payloads, across TNC2 conversion.
        byte[] msgBody = body.getBytes(StandardCharsets.ISO_8859_1);
        APRSPacket packet = new APRSPacket(source,dest,digis, msgBody);
        byte dti = msgBody[0];
        // get the invalid crap out of the way right away.
        if ( (dti >='A' && dti <= 'S') ||
             (dti >='U' && dti <= 'Z') ||
             (dti >='0' && dti <= '9') ) {
            packet.setHasFault(true);
            packet.setComment("Invalid DTI");
            return packet;
        }
        InformationField infoField = packet.getPayload();
        int cursor = 0;
        switch ( dti ) {
            case '/':
            case '@':
                // These have timestamps, so we need to parse those, advance the cursor, and then look for
                // the position data
                TimeField timeField = new TimeField(msgBody, cursor);
                infoField.addAprsData(APRSTypes.T_TIMESTAMP, timeField);
                cursor = timeField.getLastCursorPosition();
                PositionField pf = new PositionField(msgBody, dest, cursor+1);
                infoField.addAprsData(APRSTypes.T_POSITION,pf);
                cursor = pf.getLastCursorPosition();
                if ( pf.getPosition().getSymbolCode() == '_' ) {
                    // this is a weather packet, so pull the weather info from it
                    WeatherField wf = WeatherParser.parseWeatherData(msgBody, cursor);
                    wf.setType(APRSTypes.T_WX);
                    infoField.addAprsData(APRSTypes.T_WX, wf);
                    cursor = wf.getLastCursorPosition();
                }
                break;
            case '!':
            case '=':
            case '`':
            case '\'':
            case '$':
                if ( body.startsWith("$ULTW") ) {
                    // Ultimeter II weather packet
                } else {
                    // these are non-timestamped packets with position.
                    PositionField posField = new PositionField(msgBody, dest, cursor+1);
                    cursor = posField.getLastCursorPosition();
                    infoField.addAprsData(APRSTypes.T_POSITION, posField );
                    if ( posField.getPosition().getSymbolCode() == '_' && msgBody.length > 20) {
                        // with weather...
                        WeatherField wf = WeatherParser.parseWeatherData(msgBody, cursor);
                        infoField.addAprsData(APRSTypes.T_WX, wf);
                        cursor = wf.getLastCursorPosition();
                    }
                }
                break;
            case ':':
                // APRSPacket constructs and retains the typed message payload.
                break;
            case ';':
                if (msgBody.length > 29) {
                    //System.out.println("Parsing an OBJECT");
                    ObjectField of = new ObjectField(msgBody);
                    infoField.addAprsData(APRSTypes.T_OBJECT, of);
                    packet.setComment(of.getComment());
                } else {
                    packet.setHasFault(true); // too short for an object
                }
                break;
        case '>':
                infoField.addAprsData(APRSTypes.T_STATUS, new StatusField(msgBody));
            break;
            case '<':
                infoField.addAprsData(APRSTypes.T_STATCAPA,
                    new StationCapabilitiesField(msgBody));
                break;
            case '?':
//                packet.setType(APRSTypes.T_QUERY);
                break;
            case ')':
//                packet.setType(APRSTypes.T_ITEM);
//                if (msgBody.length > 18) {
//                infoField = new ItemPacket(msgBody);
//                } else {
//                    packet.hasFault = true; // too short
//                }
                break;
            case 'T':
                if (msgBody.length > 18) {
                    //System.out.println("Parsing TELEMETRY");
                    //parseTelem(bodyBytes);
                } else {
                    packet.setHasFault(true); // too short
                   }
                break;
            case '#': // Peet Bros U-II Weather Station
            case '*': // Peet Bros U-II Weather Station
            case '_': // Weather report without position
                WeatherField  wf = WeatherParser.parseWeatherData(msgBody, cursor);
                infoField.addAprsData(APRSTypes.T_WX, wf);
                cursor = wf.getLastCursorPosition();
                break;
            case '{':
//                packet.setType(APRSTypes.T_USERDEF);
                break;
            case '}': // 3rd-party
                try {
                    String innerBody = body.substring(1);
                    APRSPacket innerPacket = Parser.parse(innerBody);
                innerPacket.addDigipeater(new Digipeater(source));
                    ThirdPartyField thirdPartyField = new ThirdPartyField(msgBody, innerPacket);
                    infoField.addAprsData(APRSTypes.T_THIRDPARTY, thirdPartyField);
                    cursor = msgBody.length;
                } catch (Exception e) {
                    packet.setHasFault(true);
                }
                break;

            default:
                packet.setHasFault(true); // UNKNOWN!
                break;

        }
        packet.setPayload(infoField);
        return packet;
    }

}
