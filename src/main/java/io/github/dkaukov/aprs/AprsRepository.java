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

import java.util.List;

/**
 * Persistence contract for APRS events and their physical packet records.
 *
 * <p>Applications provide an implementation when constructing an {@link AprsController}.
 * Records passed to and returned from this interface are immutable. {@link #insert(AprsEvent)}
 * and {@link #insert(AprsPacket)} return the persistent identifier assigned to the record.</p>
 */
public interface AprsRepository {
    /** Stores a physical packet record and returns its assigned identifier. */
    long insert(AprsPacket packet);

    /** Stores a logical event record and returns its assigned identifier. */
    long insert(AprsEvent event);

    /** Replaces the stored event with the same identifier. */
    void update(AprsEvent event);

    /** Returns all pending reliable-message events with a scheduled retry. */
    List<AprsEvent> loadPendingReliableEvents();

    /** Returns an event by identifier, or {@code null} when it is not stored. */
    AprsEvent findById(long id);

    /** Returns a recently observed event with the supplied duplicate key, or {@code null}. */
    AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs);

    /** Returns a pending outgoing message matching its local/remote callsigns and identifier. */
    AprsEvent findPendingOutgoingEvent(String localCallsign, String remoteCallsign, String messageIdentifier);
}
