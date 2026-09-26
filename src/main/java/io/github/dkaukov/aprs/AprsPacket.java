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

/** Mutable transport history for one received or transmitted APRS packet. */
@lombok.Getter
@lombok.Setter
public final class AprsPacket {
    long id;
    Long eventId;
    long timestampMs;
    String source;
    /** RF frequency in Hz, or {@code null} for non-RF sources. */
    Long frequencyHz;
    String fromCallsign;
    String ax25Destination;
    /** Comma-separated AX.25 digipeater path, retaining repeated-hop markers. */
    String path;
    /** Exact AX.25 frame bytes without FCS, KISS, or serial transport framing. */
    byte[] rawAx25;
    /** Exact TNC2 packet line sent to or received from APRS-IS, without a line terminator. */
    String rawTnc2;
    public byte[] getRawAx25() {
        return rawAx25 == null ? null : rawAx25.clone();
    }

    public void setRawAx25(byte[] value) {
        rawAx25 = value == null ? null : value.clone();
    }
    public AprsPacket() {}

    public AprsPacket(AprsPacket source) {
        id = source.id;
        eventId = source.eventId;
        timestampMs = source.timestampMs;
        this.source = source.source;
        frequencyHz = source.frequencyHz;
        fromCallsign = source.fromCallsign;
        ax25Destination = source.ax25Destination;
        path = source.path;
        rawAx25 = source.rawAx25 == null ? null : source.rawAx25.clone();
        rawTnc2 = source.rawTnc2;
    }

    public AprsPacket copy() {
        return new AprsPacket(this);
    }
}
