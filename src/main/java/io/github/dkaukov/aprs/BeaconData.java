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
 * Immutable application-supplied content for one controller-generated position beacon.
 *
 * <p>Latitude and longitude are required. Other boxed extension fields are optional: {@code null}
 * means do not include that APRS extension in the generated packet. Symbol values default to
 * {@code '/'} and {@code '>'} when omitted. Supplying weather produces an APRS
 * positioned-weather beacon and therefore uses {@code '_'} as its symbol code.</p>
 */
@Data
@Builder(toBuilder = true)
public final class BeaconData {
    /** Required latitude in decimal degrees; must be finite and in the range -90 through 90. */
    private final Double latitude;
    /** Required longitude in decimal degrees; must be finite and in the range -180 through 180. */
    private final Double longitude;
    /** APRS symbol-table identifier, or {@code null} for the primary table. */
    private final Character symbolTable;
    /**
     * APRS symbol code, or {@code null} for the car symbol. Ignored for positioned-weather
     * beacons, which use {@code '_'} as required by APRS.
     */
    private final Character symbolCode;
    /** Altitude in metres, or {@code null} to omit altitude; it must fit APRS's six-digit feet field. */
    private final Double altitudeMeters;
    /** Ground speed in knots, or {@code null} when course/speed is unavailable; supply with course and rounds to 0 through 999. */
    private final Double speedKnots;
    /** Course in degrees true, or {@code null} when course/speed is unavailable; supply with speed and valid range is 0 through 360. */
    private final Integer courseDegrees;
    /** Optional APRS comment appended after extensions. */
    private final String comment;
    /** Optional weather extension, or {@code null} to omit weather. */
    private final WeatherData weather;

    /**
     * Optional immutable weather measurements encoded with this beacon.
     *
     * <p>Each value is independently optional. {@code null} omits its corresponding APRS weather
     * component; wind direction and speed are emitted only when both are supplied.</p>
     */
    @Data
    @Builder(toBuilder = true)
    public static final class WeatherData {
        /** Wind direction in degrees true, or {@code null}; supply with wind speed and valid range is 0 through 360. */
        private final Integer windDirectionDegrees;
        /** Wind speed in knots, or {@code null}; supply with wind direction and valid range is 0 through 999. */
        private final Integer windSpeedKnots;
        /** Wind gust in knots, or {@code null}; valid range is 0 through 999. */
        private final Integer windGustKnots;
        /** Air temperature in degrees Celsius, or {@code null}; it must fit APRS's Fahrenheit field. */
        private final Double temperatureCelsius;
        /** Relative humidity percentage, or {@code null}; valid range is 1 through 100. */
        private final Integer humidityPercent;
        /** Barometric pressure in hectopascals, or {@code null}; it must fit APRS's five-digit field. */
        private final Double pressureHectopascals;
    }
}
