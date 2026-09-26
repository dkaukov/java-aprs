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

/**
 * APRS, TNC2, and AX.25 UI-frame parsing and encoding.
 *
 * <p>{@link io.github.dkaukov.aprs.parser.Parser} accepts TNC2 lines and AX.25 UI frames without
 * flags, FCS, or KISS framing. {@link io.github.dkaukov.aprs.parser.APRSPacket} represents the
 * parsed protocol packet and can encode an AX.25 UI frame. This vendored parser is derived from
 * javAPRSlib/java-aprs-fap and includes local changes; retain its existing provenance headers.</p>
 */
package io.github.dkaukov.aprs.parser;
