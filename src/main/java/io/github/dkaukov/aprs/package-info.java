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
 * Controller, persistence, transport-history, and APRS-IS integration APIs.
 *
 * <p>{@link io.github.dkaukov.aprs.AprsController} turns parsed APRS frames into immutable
 * {@link io.github.dkaukov.aprs.AprsEvent logical events} and
 * {@link io.github.dkaukov.aprs.AprsPacket physical observations}. Applications provide the
 * synchronous persistence boundary through {@link io.github.dkaukov.aprs.AprsRepository} and
 * radio/application actions through {@link io.github.dkaukov.aprs.AprsController.Callbacks}.
 * {@link io.github.dkaukov.aprs.AprsIsClient} supplies an optional APRS-IS connection client.</p>
 *
 * <p>The library deliberately does not provide a database, UI, location service, radio hardware,
 * KISS transport, or audio modem.</p>
 */
package io.github.dkaukov.aprs;
