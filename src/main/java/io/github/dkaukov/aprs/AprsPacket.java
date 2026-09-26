/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package io.github.dkaukov.aprs;

/** Immutable transport history for one received or transmitted APRS packet. */
public class AprsPacket {
    public long id;
    public Long eventId;
    public long timestampMs;
    public String source;
    /** RF frequency in Hz, or {@code null} for non-RF sources. */
    public Long frequencyHz;
    public String fromCallsign;
    public String ax25Destination;
    /** Comma-separated AX.25 digipeater path, retaining repeated-hop markers. */
    public String path;
    /** Exact AX.25 frame bytes without FCS, KISS, or serial transport framing. */
    public byte[] rawAx25;
    /** Exact TNC2 packet line sent to or received from APRS-IS, without a line terminator. */
    public String rawTnc2;
}
