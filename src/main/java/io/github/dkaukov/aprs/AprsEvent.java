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
 * Immutable logical APRS occurrence assembled from one or more physical packets.
 *
 * <p>Use {@code builder()} to create an event and {@code toBuilder()} to derive an updated
 * replacement. Values such as position, weather, and message fields apply according to
 * {@code type}; fields not present in the APRS payload retain their default values.</p>
 */
@Data
@Builder(toBuilder = true)
public final class AprsEvent {
    /** Unrecognised or unsupported APRS payload. */
    public static final int UNKNOWN_TYPE = 0;
    /** APRS message payload. */
    public static final int MESSAGE_TYPE = 1;
    /** APRS object payload. */
    public static final int OBJECT_TYPE = 2;
    /** APRS position payload. */
    public static final int POSITION_TYPE = 3;
    /** APRS weather payload. */
    public static final int WEATHER_TYPE = 4;
    /** APRS status payload. */
    public static final int STATUS_TYPE = 5;
    /** APRS station-capabilities payload. */
    public static final int STATION_CAPABILITIES_TYPE = 6;

    /** No reliable-message delivery state applies. */
    public static final int DELIVERY_NONE = 0;
    /** A reliable message awaits acknowledgement or rejection. */
    public static final int DELIVERY_PENDING = 1;
    /** A reliable message was acknowledged. */
    public static final int DELIVERY_DELIVERED = 2;
    /** A reliable message was explicitly rejected. */
    public static final int DELIVERY_REJECTED = 3;
    /** A reliable message exhausted its retry attempts. */
    public static final int DELIVERY_FAILED = 4;

    /** Persistent identifier assigned by the event repository; {@code 0} before insertion. */
    private final long id;

    /** One of the {@code *_TYPE} constants that describes this occurrence. */
    private final int type;
    /** Stable event time used for history filtering, ordering, and display. */
    private final long firstSeenMs;
    /** Latest associated packet time, used only for aggregation and duplicate detection. */
    private final long lastSeenMs;
    /** Number of physical packets associated with this logical occurrence. */
    private final int packetCount;
    /** Whether this station retransmitted at least one packet associated with this event. */
    private final boolean digipeated;
    /** Whether every received copy associated with this event came from APRS-IS. */
    private final boolean internetOnly;
    /** Stable controller-generated key used to collapse recent duplicate observations. */
    private final String dedupKey;

    /** Callsign that originated the APRS payload. */
    private final String fromCallsign;
    /** Recipient callsign for a message, or {@code null} for non-message events. */
    private final String toCallsign;
    /** APRS message number used for acknowledgement and retry, if present. */
    private final String messageIdentifier;
    /** Message body for a {@link #MESSAGE_TYPE message event}, or {@code null} otherwise. */
    private final String body;
    /** Decoded latitude in decimal degrees. */
    private final double positionLat;
    /** Decoded longitude in decimal degrees. */
    private final double positionLong;
    /** Free-form APRS comment, when available. */
    private final String comment;
    /** APRS object name for an {@link #OBJECT_TYPE object event}. */
    private final String objectName;
    /** Decoded temperature in degrees Fahrenheit. */
    private final double temperature;
    /** Decoded relative humidity percentage. */
    private final double humidity;
    /** Decoded barometric pressure in hectopascals. */
    private final double pressure;
    /** Decoded rainfall over the reported 24-hour interval, in inches. */
    private final double rain;
    /** Decoded snowfall over the reported 24-hour interval, in inches. */
    private final double snow;
    /** Decoded wind speed in miles per hour. */
    private final int windForce;
    /** Cardinal wind direction, or an empty string when it was not reported. */
    private final String windDirection;
    /** Callsign of the station that relayed the received packet, when known. */
    private final String relayCallsign;

    /** One of the {@code DELIVERY_*} constants. */
    private final int deliveryState;
    /** Number of attempted transmissions for a reliable outgoing message. */
    private final int transmitAttempts;
    /** Scheduled Unix-epoch retry time in milliseconds, or {@code null} when no retry is due. */
    private final Long nextRetryAtMs;

    /**
     * Mutable construction state used by {@code builder()}.
     *
     * <p>This source declaration lets Maven Javadoc resolve the Lombok-generated builder type
     * used internally by {@link AprsController}. Lombok supplies its fields and methods.</p>
     */
    public static class AprsEventBuilder {
        // Lombok supplies the fluent field methods, build(), and toString().
    }
}
