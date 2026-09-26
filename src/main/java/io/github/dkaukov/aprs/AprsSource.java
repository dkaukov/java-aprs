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

/** Transport direction/source values stored with physical APRS packet records. */
public final class AprsSource {
    public static final String UNKNOWN = "UNKNOWN";
    public static final String RX_RF = "RX_RF";
    public static final String TX_RF = "TX_RF";
    public static final String RX_APRS_IS = "RX_APRS_IS";
    public static final String TX_APRS_IS = "TX_APRS_IS";

    private AprsSource() {
    }
}
