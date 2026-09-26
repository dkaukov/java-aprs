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

package io.github.dkaukov.aprs;

import lombok.Builder;
import lombok.Data;

/**
 * Immutable record of one physical APRS packet received or transmitted by the controller.
 *
 * <p>An event may be associated with multiple packet records: for example, an RF packet and
 * its APRS-IS-gated copy. Build a record with {@code builder()} and derive a changed value
 * with {@code toBuilder()}. The AX.25 byte array is defensively copied on input and output.</p>
 */
@Data
@Builder(toBuilder = true)
public final class AprsPacket {
    /** Persistent identifier assigned by the packet repository; {@code 0} before insertion. */
    private final long id;
    /** Identifier of the logical {@link AprsEvent} represented by this packet, if associated. */
    private final Long eventId;
    /** Wall-clock time, in milliseconds since the Unix epoch, when the packet was observed. */
    private final long timestampMs;
    /** Direction and medium of the packet, normally one of the {@link AprsSource} values. */
    private final String source;
    /** RF frequency in Hz, or {@code null} for non-RF sources. */
    private final Long frequencyHz;
    /** Source callsign decoded from the AX.25 frame, or {@code null} when unavailable. */
    private final String fromCallsign;
    /** AX.25 destination callsign decoded from the frame, or {@code null} when unavailable. */
    private final String ax25Destination;
    /** Comma-separated AX.25 digipeater path, retaining repeated-hop markers. */
    private final String path;
    /** Exact AX.25 frame bytes without FCS, KISS, or serial transport framing. */
    private final byte[] rawAx25;
    /** Exact TNC2 packet line sent to or received from APRS-IS, without a line terminator. */
    private final String rawTnc2;

    /**
     * Returns an independent copy of the AX.25 frame bytes.
     *
     * @return the frame bytes, or {@code null} when the packet has no AX.25 representation
     */
    public byte[] getRawAx25() {
        return rawAx25 == null ? null : rawAx25.clone();
    }

    /** Mutable builder state; built packets never retain a caller-owned byte array. */
    public static class AprsPacketBuilder {
        /**
         * Stores an independent snapshot of AX.25 frame bytes.
         *
         * @param rawAx25 frame bytes without FCS or transport framing, or {@code null}
         * @return this builder
         */
        public AprsPacketBuilder rawAx25(byte[] rawAx25) {
            this.rawAx25 = rawAx25 == null ? null : rawAx25.clone();
            return this;
        }
    }

}
