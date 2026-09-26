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

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RepositoryAccessTest {
    @Test public void writesCannotExposeControllerRecordsToTheRepository() {
        Store store = new Store();
        RepositoryAccess access = new RepositoryAccess(store, store);
        AprsEvent event = AprsEvent.builder().body("original").build();
        assertEquals(1, access.insert(event));
        store.event = store.event.toBuilder().body("repository mutation").build();
        assertEquals("original", event.getBody());
        access.update(event);
        AprsEvent changed = event.toBuilder().body("controller mutation").build();
        assertEquals("controller mutation", changed.getBody());
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
        store.event = AprsEvent.builder().body("persisted").build();
        assertEquals("changed", access.findById(1).toBuilder().body("changed").build().getBody());
        assertEquals("persisted", access.findRecentByDedupKey("key", 0).getBody());
        assertEquals("persisted", access.findPendingOutgoingEvent("local", "remote", "id").getBody());
        List<AprsEvent> due = access.loadDueReliableEvents(0);
        due.set(0, due.get(0).toBuilder().body("changed").build());
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
            return new ArrayList<>(Collections.singletonList(event));
        }
        @Override public Long loadNextReliableRetryAt() { return 10L; }
        @Override public AprsEvent findById(long id) { return event; }
        @Override public AprsEvent findRecentByDedupKey(String key, long since) { return event; }
        @Override public AprsEvent findPendingOutgoingEvent(String local, String remote,
                                                           String identifier) { return event; }
    }
}
