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

/** Transport direction/source values stored with physical {@link AprsPacket} records. */
public final class AprsSource {
    /** Transport direction is unavailable. */
    public static final String UNKNOWN = "UNKNOWN";
    /** Packet was received from RF. */
    public static final String RX_RF = "RX_RF";
    /** Packet was transmitted on RF. */
    public static final String TX_RF = "TX_RF";
    /** Packet was received from APRS-IS. */
    public static final String RX_APRS_IS = "RX_APRS_IS";
    /** Packet was transmitted to APRS-IS. */
    public static final String TX_APRS_IS = "TX_APRS_IS";

    private AprsSource() {
    }
}
