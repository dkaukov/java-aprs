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

/**
 * Immutable APRS occurrence assembled from one or more physical packets.
 * Create with {@code builder()} and derive updated values with {@code toBuilder()}.
 */
@lombok.Getter
@Builder(toBuilder = true)
@lombok.AllArgsConstructor(access = lombok.AccessLevel.PRIVATE)
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

    private final long id;

    private final int type;
    /** Stable event time used for history filtering, ordering, and display. */
    private final long firstSeenMs;
    /** Latest associated packet time, used only for aggregation and duplicate detection. */
    private final long lastSeenMs;
    private final int packetCount;
    /** Whether this station retransmitted at least one packet associated with this event. */
    private final boolean digipeated;
    /** Whether every received copy associated with this event came from APRS-IS. */
    private final boolean internetOnly;
    /** Stable controller-generated key used to collapse recent duplicate observations. */
    private final String dedupKey;

    private final String fromCallsign;
    private final String toCallsign;
    private final String messageIdentifier;
    private final String body;
    private final double positionLat;
    private final double positionLong;
    private final String comment;
    private final String objectName;
    private final double temperature;
    private final double humidity;
    private final double pressure;
    private final double rain;
    private final double snow;
    private final int windForce;
    private final String windDirection;
    private final String relayCallsign;

    private final int deliveryState;
    private final int transmitAttempts;
    private final Long nextRetryAtMs;

    /** Mutable construction state; built events never retain a reference to it. */
    public static class AprsEventBuilder {
        // Lombok supplies the fluent field methods, build(), and toString().
    }

    /** Returns this immutable value; no defensive copy is necessary. */
    public AprsEvent copy() {
        return this;
    }
}
