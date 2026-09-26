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
import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Controller-owned persistence boundary. Method bindings stay fixed while the
 * backing store remains shared. Every mutable record crosses as a snapshot.
 */
final class RepositoryAccess {
    private final ToLongFunction<AprsPacket> insertPacket;
    private final ToLongFunction<AprsEvent> insertEvent;
    private final Consumer<AprsEvent> updateEvent;
    private final LongFunction<List<AprsEvent>> loadDueEvents;
    private final Supplier<Long> loadNextRetry;
    private final LongFunction<AprsEvent> findEvent;
    private final BiFunction<String, Long, AprsEvent> findRecent;
    private final PendingLookup findPending;

    private interface PendingLookup {
        AprsEvent find(String local, String remote, String identifier);
    }

    RepositoryAccess(AprsController.PacketRepository packets,
                     AprsController.EventRepository events) {
        insertPacket = packets::insert;
        insertEvent = events::insert;
        updateEvent = events::update;
        loadDueEvents = events::loadDueReliableEvents;
        loadNextRetry = events::loadNextReliableRetryAt;
        findEvent = events::findById;
        findRecent = events::findRecentByDedupKey;
        findPending = events::findPendingOutgoingEvent;
    }

    long insert(AprsPacket packet) {
        return insertPacket.applyAsLong(packet.copy());
    }

    long insert(AprsEvent event) {
        return insertEvent.applyAsLong(event);
    }

    void update(AprsEvent event) {
        updateEvent.accept(event);
    }

    List<AprsEvent> loadDueReliableEvents(long now) {
        return new ArrayList<>(loadDueEvents.apply(now));
    }

    Long loadNextReliableRetryAt() {
        return loadNextRetry.get();
    }

    AprsEvent findById(long id) {
        return findEvent.apply(id);
    }

    AprsEvent findRecentByDedupKey(String key, long since) {
        return findRecent.apply(key, since);
    }

    AprsEvent findPendingOutgoingEvent(String local, String remote, String identifier) {
        return findPending.find(local, remote, identifier);
    }
}
