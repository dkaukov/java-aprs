package io.github.dkaukov.aprs;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RepositoryAccessTest {
    @Test public void writesCannotExposeControllerRecordsToTheRepository() {
        Store store = new Store();
        RepositoryAccess access = new RepositoryAccess(store, store);
        AprsEvent event = new AprsEvent();
        event.setBody("original");
        assertEquals(1, access.insert(event));
        store.event.setBody("repository mutation");
        assertEquals("original", event.getBody());
        access.update(event);
        event.setBody("controller mutation");
        assertEquals("original", store.event.getBody());

        AprsPacket packet = new AprsPacket();
        packet.setRawAx25(new byte[] {1, 2});
        assertEquals(2, access.insert(packet));
        store.packet.rawAx25[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, packet.getRawAx25());
    }

    @Test public void readsDoNotLetTheControllerEditRepositoryRecords() {
        Store store = new Store();
        RepositoryAccess access = new RepositoryAccess(store, store);
        store.event = new AprsEvent();
        store.event.setBody("persisted");
        access.findById(1).setBody("changed");
        access.findRecentByDedupKey("key", 0).setBody("changed");
        access.findPendingOutgoingEvent("local", "remote", "id").setBody("changed");
        List<AprsEvent> due = access.loadDueReliableEvents(0);
        due.get(0).setBody("changed");
        due.clear();
        assertEquals("persisted", store.event.getBody());
        assertEquals(1, access.loadDueReliableEvents(0).size());
        assertEquals(Long.valueOf(10), access.loadNextReliableRetryAt());
        store.event = null;
        assertNull(access.findById(1));
        assertNull(access.findRecentByDedupKey("key", 0));
        assertNull(access.findPendingOutgoingEvent("local", "remote", "id"));
    }

    private static final class Store implements AprsController.PacketRepository,
            AprsController.EventRepository {
        AprsPacket packet;
        AprsEvent event;
        @Override public long insert(AprsPacket value) { packet = value; return 2; }
        @Override public long insert(AprsEvent value) { event = value; return 1; }
        @Override public void update(AprsEvent value) { event = value; }
        @Override public List<AprsEvent> loadDueReliableEvents(long now) {
            return new ArrayList<>(List.of(event));
        }
        @Override public Long loadNextReliableRetryAt() { return 10L; }
        @Override public AprsEvent findById(long id) { return event; }
        @Override public AprsEvent findRecentByDedupKey(String key, long since) { return event; }
        @Override public AprsEvent findPendingOutgoingEvent(String local, String remote,
                                                           String identifier) { return event; }
    }
}
