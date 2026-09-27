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
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Persistence contract for APRS events and their physical packet records.
 *
 * <p>Applications provide an implementation when constructing an {@link AprsController}. Calls
 * are synchronous and occur while the controller is serialized. Records passed to and returned
 * from this interface are immutable. {@link #insert(AprsEvent)} and {@link #insert(AprsPacket)}
 * return the persistent identifier assigned to the record.</p>
 *
 * <p>A controller reads {@link #loadPendingReliableEvents()} once to rebuild retry state, then
 * maintains that state from its own writes. Do not alter pending reliable-message events through
 * another controller or direct repository access while that controller is running.</p>
 */
public interface AprsRepository {
    /**
     * Runs related repository reads and writes as one atomic unit when supported.
     *
     * <p>The default implementation executes {@code operation} directly, keeping this optional
     * for simple in-memory repositories. Database-backed implementations should override this
     * method to begin, commit, and roll back a transaction around the operation. The controller
     * uses this boundary for related event and packet persistence, but never for application or
     * radio callbacks.</p>
     *
     * <p>The default implementation cannot undo work that completed before an exception. For
     * example, if inserting a packet succeeds but updating its associated event fails, the
     * packet remains stored while the event retains its former packet count and state. An
     * implementation that provides atomic transactions must roll back both changes when the
     * operation fails.</p>
     *
     * <p>Controller operations are not automatically idempotent after a repository failure.
     * Callers must not blindly retry {@link AprsController} methods: a retry can insert another
     * physical packet or allocate another event. Reconcile the stored records first, or use an
     * atomic implementation of this method.</p>
     *
     * @param operation repository operation to execute
     * @param <T> result type
     * @return result returned by {@code operation}
     * @throws NullPointerException if {@code operation} is {@code null}
     * @throws RuntimeException if the operation or transaction implementation fails
     */
    default <T> T inTransaction(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }

    /**
     * Stores one immutable physical RX/TX packet record.
     *
     * @param packet packet record to persist
     * @return persistent identifier assigned to the record
     */
    long insert(AprsPacket packet);

    /**
     * Stores one immutable logical event record.
     *
     * @param event event to persist
     * @return persistent identifier assigned to the event
     */
    long insert(AprsEvent event);

    /**
     * Replaces the stored event with the same identifier.
     *
     * @param event updated immutable event; its ID identifies the record to replace
     */
    void update(AprsEvent event);

    /**
     * Loads all reliable messages awaiting a scheduled retry.
     *
     * @return pending immutable events; later repository mutations must not affect iteration
     */
    List<AprsEvent> loadPendingReliableEvents();

    /**
     * Finds an event by persistent identifier.
     *
     * @param id persistent event identifier
     * @return immutable event, or {@code null} when it is not stored
     */
    AprsEvent findById(long id);

    /**
     * Finds a recent event with the controller-generated duplicate key.
     *
     * @param dedupKey duplicate key from an event candidate
     * @param sinceMs inclusive lower bound for last-seen time, in Unix-epoch milliseconds
     * @return immutable matching event, or {@code null}
     */
    AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs);

    /**
     * Finds a pending outgoing message for an incoming ACK or REJ.
     *
     * @param localCallsign original local sender callsign
     * @param remoteCallsign original destination callsign
     * @param messageIdentifier APRS message number
     * @return immutable pending event, or {@code null}
     */
    AprsEvent findPendingOutgoingEvent(String localCallsign, String remoteCallsign, String messageIdentifier);
}
