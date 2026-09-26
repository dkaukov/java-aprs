package io.github.dkaukov.aprs;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

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
        return insertEvent.applyAsLong(event.copy());
    }

    void update(AprsEvent event) {
        updateEvent.accept(event.copy());
    }

    List<AprsEvent> loadDueReliableEvents(long now) {
        return loadDueEvents.apply(now).stream().map(AprsEvent::copy)
            .collect(Collectors.toList());
    }

    Long loadNextReliableRetryAt() {
        return loadNextRetry.get();
    }

    AprsEvent findById(long id) {
        return snapshot(findEvent.apply(id));
    }

    AprsEvent findRecentByDedupKey(String key, long since) {
        return snapshot(findRecent.apply(key, since));
    }

    AprsEvent findPendingOutgoingEvent(String local, String remote, String identifier) {
        return snapshot(findPending.find(local, remote, identifier));
    }

    private static AprsEvent snapshot(AprsEvent event) {
        return event == null ? null : event.copy();
    }
}
