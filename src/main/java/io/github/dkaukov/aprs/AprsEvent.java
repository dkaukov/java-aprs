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

/** User-facing APRS occurrence assembled from one or more physical packets. */
@lombok.Getter
@lombok.Setter
public final class AprsEvent {
    public static final int UNKNOWN_TYPE = 0;
    public static final int MESSAGE_TYPE = 1;
    public static final int OBJECT_TYPE = 2;
    public static final int POSITION_TYPE = 3;
    public static final int WEATHER_TYPE = 4;
    public static final int STATUS_TYPE = 5;
    public static final int STATION_CAPABILITIES_TYPE = 6;

    public static final int DELIVERY_NONE = 0;
    public static final int DELIVERY_PENDING = 1;
    public static final int DELIVERY_DELIVERED = 2;
    public static final int DELIVERY_REJECTED = 3;
    public static final int DELIVERY_FAILED = 4;

    long id;

    int type;
    /** Stable event time used for history filtering, ordering, and display. */
    long firstSeenMs;
    /** Latest associated packet time, used only for aggregation and duplicate detection. */
    long lastSeenMs;
    int packetCount;
    /** Whether this station retransmitted at least one packet associated with this event. */
    boolean digipeated;
    /** Whether every received copy associated with this event came from APRS-IS. */
    boolean internetOnly;
    /** Stable controller-generated key used to collapse recent duplicate observations. */
    String dedupKey;

    String fromCallsign;
    String toCallsign;
    String messageIdentifier;
    String body;
    double positionLat;
    double positionLong;
    String comment;
    String objectName;
    double temperature;
    double humidity;
    double pressure;
    double rain;
    double snow;
    int windForce;
    String windDirection;
    String relayCallsign;

    int deliveryState;
    int transmitAttempts;
    Long nextRetryAtMs;
    public AprsEvent() {}

    public AprsEvent(AprsEvent source) {
        copyFrom(source);
    }

    void copyFrom(AprsEvent source) {
        id = source.id;
        type = source.type;
        firstSeenMs = source.firstSeenMs;
        lastSeenMs = source.lastSeenMs;
        packetCount = source.packetCount;
        digipeated = source.digipeated;
        internetOnly = source.internetOnly;
        dedupKey = source.dedupKey;
        fromCallsign = source.fromCallsign;
        toCallsign = source.toCallsign;
        messageIdentifier = source.messageIdentifier;
        body = source.body;
        positionLat = source.positionLat;
        positionLong = source.positionLong;
        comment = source.comment;
        objectName = source.objectName;
        temperature = source.temperature;
        humidity = source.humidity;
        pressure = source.pressure;
        rain = source.rain;
        snow = source.snow;
        windForce = source.windForce;
        windDirection = source.windDirection;
        relayCallsign = source.relayCallsign;
        deliveryState = source.deliveryState;
        transmitAttempts = source.transmitAttempts;
        nextRetryAtMs = source.nextRetryAtMs;
    }

    public AprsEvent copy() {
        return new AprsEvent(this);
    }
}
