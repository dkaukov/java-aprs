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

/** User-facing APRS occurrence assembled from one or more physical packets. */
public class AprsEvent {
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

    public long id;

    public int type;
    /** Stable event time used for history filtering, ordering, and display. */
    public long firstSeenMs;
    /** Latest associated packet time, used only for aggregation and duplicate detection. */
    public long lastSeenMs;
    public int packetCount;
    /** Whether this station retransmitted at least one packet associated with this event. */
    public boolean digipeated;
    /** Whether every received copy associated with this event came from APRS-IS. */
    public boolean internetOnly;
    /** Stable controller-generated key used to collapse recent duplicate observations. */
    public String dedupKey;

    public String fromCallsign;
    public String toCallsign;
    public String messageIdentifier;
    public String body;
    public double positionLat;
    public double positionLong;
    public String comment;
    public String objectName;
    public double temperature;
    public double humidity;
    public double pressure;
    public double rain;
    public double snow;
    public int windForce;
    public String windDirection;
    public String relayCallsign;

    public int deliveryState;
    public int transmitAttempts;
    public Long nextRetryAtMs;
}
