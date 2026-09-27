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

import io.github.dkaukov.aprs.parser.APRSPacket;
import io.github.dkaukov.aprs.parser.APRSTypes;
import io.github.dkaukov.aprs.parser.Digipeater;
import io.github.dkaukov.aprs.parser.InformationField;
import io.github.dkaukov.aprs.parser.MessagePacket;
import io.github.dkaukov.aprs.parser.ObjectField;
import io.github.dkaukov.aprs.parser.Parser;
import io.github.dkaukov.aprs.parser.Position;
import io.github.dkaukov.aprs.parser.PositionField;
import io.github.dkaukov.aprs.parser.StationCapabilitiesField;
import io.github.dkaukov.aprs.parser.StatusField;
import io.github.dkaukov.aprs.parser.ThirdPartyField;
import io.github.dkaukov.aprs.parser.Utilities;
import io.github.dkaukov.aprs.parser.WeatherField;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Coordinates APRS parsing, event aggregation, packet history, retries, beacon cadence,
 * digipeating, and RF-to-APRS-IS gating.
 *
 * <p>{@link AprsPacket} records immutable transport facts. {@link AprsEvent}
 * records one user-visible occurrence and may aggregate multiple received copies, retries, and a
 * delivery response. The normal UI observes events; packet history remains available for future
 * diagnostics and iGate work.</p>
 *
 * <p>All state-changing operations are synchronous and serialized on this instance. Repository
 * calls and {@link Callbacks callbacks} run on the calling thread while that serialization is
 * held. This class creates no threads. A callback must not wait for another thread to enter this
 * controller. Applications that need background work should call it from their own ordered
 * executor. Serialization is per controller, not across external repository writers.</p>
 */
public final class AprsController {
    private static final Logger LOG = Logger.getLogger(AprsController.class.getName());
    private static final long[] RETRY_DELAYS_MS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
    private static final long FINAL_ACK_GRACE_MS = 30_000L;
    private static final long EVENT_DUPLICATE_WINDOW_MS = 30_000L;
    private static final long NUMBERED_MESSAGE_DUPLICATE_WINDOW_MS = 30 * 60_000L;
    private static final long DIGIPEAT_DEDUP_MS = 28_000L;

    /**
     * Immutable result reported after successful submission to a TNC or radio transport.
     *
     * <p>The constructor copies both mutable inputs. A non-null instance means the transport
     * accepted the frame and the controller may record a TX submission. It does not prove that
     * the frame was sent over RF or received by another station. For reliable messages, only a
     * matching APRS ACK establishes delivery; callbacks return {@code null} when submission did
     * not occur.</p>
     */
    public static final class Transmission {
        private final APRSPacket packet;
        /** RF frequency in Hz, or {@code null} when it was not available. */
        public final Long frequencyHz;
        private final byte[] rawAx25;

        /**
         * Creates a transport-submission result from snapshots of a parsed packet and its frame.
         *
         * @param packet parser packet accepted by the transport; must not be {@code null}
         * @param frequencyHz RF frequency in Hz, or {@code null}
         * @param rawAx25 accepted AX.25 UI frame without FCS, flags, or KISS framing
         * @throws NullPointerException if {@code packet} is {@code null}
         */
        public Transmission(APRSPacket packet, Long frequencyHz, byte[] rawAx25) {
            this.packet = Objects.requireNonNull(packet, "packet").copy();
            this.frequencyHz = frequencyHz;
            this.rawAx25 = rawAx25 == null ? null : Arrays.copyOf(rawAx25, rawAx25.length);
        }

        /**
         * Returns an independent parser-packet snapshot.
         *
         * @return a deep copy that callers may modify
         */
        public APRSPacket getPacket() {
            return packet.copy();
        }

        /**
         * Returns an independent encoded-frame snapshot.
         *
         * @return AX.25 UI bytes without FCS, flags, or KISS framing; {@code null} if unavailable
         */
        public byte[] getRawAx25() {
            return rawAx25 == null ? null : Arrays.copyOf(rawAx25, rawAx25.length);
        }
    }

    /**
     * Synchronous application and radio boundary used by {@link AprsController}.
     *
     * <p>Methods execute while the controller is serialized. Implementations should return
     * promptly and must not wait for another thread to call the same controller.</p>
     */
    public interface Callbacks {
        /**
         * Handles a newly created message addressed to the configured local callsign.
         *
         * <p>This is invoked after the event and its associated physical packet have been
         * persisted. It is not invoked for duplicate packet copies collapsed into an existing
         * event.</p>
         *
         * @param event immutable message event
         */
        void onIncomingMessage(AprsEvent event);
        /**
         * Submits the exact APRS packet selected by the controller to local RF transport.
         *
         * <p>A non-null result means only that the local TNC/radio transport accepted the frame;
         * it does not confirm on-air transmission or peer receipt.</p>
         *
         * @param packet controller-selected packet snapshot
         * @return submission snapshot, or {@code null} when the transport did not accept it
         */
        Transmission submitRf(APRSPacket packet);
        /** Returns current application-owned location/content, or {@code null} to skip a beacon. */
        BeaconData getBeaconData();
        /**
         * Submits an APRS-IS line and invokes {@code onSuccess} after socket submission succeeds.
         * The callback must run later, after this method returns, rather than synchronously.
         *
         * @param tnc2 controller-selected TNC2 line without a terminator
         * @param onSuccess action to run after successful socket submission
         * @return {@code true} when the line was accepted for asynchronous submission
         */
        boolean submitAprsIs(String tnc2, Runnable onSuccess);
    }

    private final AprsRepository repository;
    private final Callbacks callbacks;
    private final Clock clock;
    private String callsign = "";
    private String txDestination = "";
    private List<Digipeater> txPath = Collections.emptyList();
    private final Map<String, Long> digipeatInputCache = new ConcurrentHashMap<>();
    private final Map<String, Long> digipeatOutputCache = new ConcurrentHashMap<>();
    private final Map<Long, AprsEvent> pendingReliableEvents = new HashMap<>();
    @Getter
    private volatile boolean positionBeaconingEnabled;
    private volatile long nextPositionBeaconAt;
    private volatile long positionBeaconIntervalMs;
    private volatile boolean digipeatingEnabled;
    private volatile boolean igateEnabled;
    private boolean pendingReliableEventsLoaded;

    /**
     * Enables or disables the controller's fill-in digipeating policy.
     *
     * @param enabled {@code true} to consider eligible RF frames for retransmission
     */
    public synchronized void setDigipeatingEnabled(boolean enabled) {
        digipeatingEnabled = enabled;
    }

    /**
     * Enables or disables standards-filtered, one-way forwarding from RF to APRS-IS.
     *
     * @param enabled {@code true} to invoke {@link Callbacks#submitAprsIs(String, Runnable)} for
     *                eligible RF packets
     */
    public synchronized void setIgateEnabled(boolean enabled) {
        igateEnabled = enabled;
    }

    /** Configures the local APRS callsign used for addressed messages, digipeating, and qAO. */
    public synchronized void setCallsign(String value) {
        String normalized = normalizeCallsign(value);
        if (!callsign.equals(normalized)) {
            callsign = normalized;
            digipeatInputCache.clear();
            digipeatOutputCache.clear();
        }
    }

    /** Returns the configured local callsign, or an empty string when none is configured. */
    public synchronized String getCallsign() {
        return callsign;
    }

    /** Sets the AX.25 destination used by controller-generated RF packets. */
    public synchronized void setTxDestination(String value) {
        txDestination = normalizeCallsign(value);
    }

    /** Sets a defensively copied AX.25 path for controller-generated RF packets. */
    public synchronized void setTxPath(List<Digipeater> path) {
        if (path == null || path.isEmpty()) {
            txPath = Collections.emptyList();
            return;
        }
        List<Digipeater> copy = new ArrayList<>();
        for (Digipeater digipeater : path) {
            copy.add(Objects.requireNonNull(digipeater, "path entry").copy());
        }
        txPath = Collections.unmodifiableList(copy);
    }

    /**
     * Creates a controller backed by application-supplied persistence and transport callbacks.
     *
     * @param repository synchronous storage boundary for immutable events and packets
     * @param callbacks synchronous application/radio boundary
     * @throws NullPointerException if either argument is {@code null}
     */
    public AprsController(AprsRepository repository, Callbacks callbacks) {
        this(repository, callbacks, Clock.systemUTC());
    }

    /**
     * Creates a controller with an application-supplied wall-clock source.
     *
     * <p>The clock supplies Unix-epoch milliseconds for event and packet timestamps, retry
     * scheduling, and digipeat suppression. Supplying a deterministic clock makes controller
     * tests reproducible; production callers normally use the two-argument constructor.</p>
     *
     * @param repository synchronous storage boundary for immutable events and packets
     * @param callbacks synchronous application/radio boundary
     * @param clock source of Unix-epoch milliseconds
     * @throws NullPointerException if any argument is {@code null}
     */
    public AprsController(AprsRepository repository, Callbacks callbacks, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Processes one decoded packet with unknown transport provenance.
     *
     * @param packet parsed APRS packet; copied before processing
     * @throws NullPointerException if {@code packet} is {@code null}
     */
    public synchronized void handle(APRSPacket packet) {
        handle(packet, AprsSource.UNKNOWN, null, null);
    }

    /**
     * Processes one decoded packet with physical transport metadata.
     *
     * <p>The controller records physical history, creates or aggregates a logical event, and may
     * synchronously invoke callbacks for ACKs, digipeating, or iGate forwarding. Use an
     * {@link AprsSource} value for {@code source}; RF frequency is in Hz.</p>
     *
     * @param packet parsed APRS packet; copied before processing
     * @param source transport source, normally an {@link AprsSource} value
     * @param frequencyHz RF frequency in Hz, or {@code null} for non-RF/unknown sources
     * @param rawAx25 AX.25 UI bytes without FCS, flags, or KISS framing; copied if non-null
     * @throws NullPointerException if {@code packet} is {@code null}
     */
    public synchronized void handle(APRSPacket packet, String source, Long frequencyHz, byte[] rawAx25) {
        handleDecoded(packet, source, frequencyHz, rawAx25, null);
    }

    /**
     * Parses and processes one APRS-IS TNC2 line without making it eligible for RF transmission.
     *
     * <p>Blank and malformed lines are ignored. Valid lines become RX_APRS_IS packet history and
     * may create or aggregate an event, but are never automatically gated or digipeated.</p>
     *
     * @param tnc2 APRS-IS packet line without a line terminator; may be {@code null}
     */
    public synchronized void handleAprsIsPacket(String tnc2) {
        if (tnc2 == null || tnc2.trim().isEmpty()) {
            return;
        }
        APRSPacket frame;
        try {
            frame = Parser.parse(tnc2);
        } catch (Exception ignored) {
            // Ignore malformed Internet input just as the RF parser ignores malformed frames.
            return;
        }
        handleDecoded(frame, AprsSource.RX_APRS_IS, null, null, tnc2);
    }

    private void handleDecoded(APRSPacket packet, String source, Long frequencyHz,
                               byte[] rawAx25, String rawTnc2) {
        packet = Objects.requireNonNull(packet, "packet").copy();
        rawAx25 = rawAx25 == null ? null : Arrays.copyOf(rawAx25, rawAx25.length);
        boolean receivedFromRf = AprsSource.RX_RF.equals(source);
        if (receivedFromRf && isRecentlyDigipeated(packet)) {
            return;
        }
        Transmission digipeated = receivedFromRf ? maybeDigipeat(packet) : null;
        AprsPacket packetRecord = physicalPacket(packet, source, frequencyHz, rawAx25, rawTnc2);
        PacketContext context = unwrap(packet);
        ParsedEvent parsed = context == null ? null : parseEvent(context);
        if (parsed != null && parsed.event != null) {
            parsed = ParsedEvent.event(parsed.event.toBuilder()
                .internetOnly(AprsSource.RX_APRS_IS.equals(source)).build());
        }
        persistIncoming(packet, packetRecord, parsed, digipeated);
    }

    private void persistIncoming(APRSPacket frame, AprsPacket packet, ParsedEvent parsed,
                                 Transmission digipeated) {
        PersistedEvent persisted = repository.inTransaction(() -> persistPacket(packet, parsed));
        AprsEvent event = persisted.event;
        if (event != null) {
            updatePendingReliableEvent(event);
        }
        if (event != null && event.getType() == AprsEvent.MESSAGE_TYPE) {
            notifyAndAcknowledge(event, persisted.created, packet.getSource());
        }
        if (digipeated != null) {
            recordTransmissionNow(event == null ? null : event.getId(), digipeated, true);
        }
        if (AprsSource.RX_RF.equals(packet.getSource())) {
            maybeGateToAprsIs(frame, event == null ? null : event.getId());
        }
    }

    private PersistedEvent persistPacket(AprsPacket packet, ParsedEvent parsed) {
        if (parsed == null) {
            repository.insert(packet);
            return PersistedEvent.none();
        }
        if (parsed.acknowledgement || parsed.rejection) {
            return PersistedEvent.existing(persistDeliveryResponse(packet, parsed));
        }
        if (parsed.event != null) {
            return persistEvent(packet, parsed.event);
        }
        repository.insert(packet);
        return PersistedEvent.none();
    }

    private AprsEvent persistDeliveryResponse(AprsPacket packet, ParsedEvent response) {
        AprsEvent event = repository.findPendingOutgoingEvent(response.targetCallsign,
            response.fromCallsign, response.messageIdentifier);
        if (event == null) {
            repository.insert(packet);
            return null;
        }
        event = event.toBuilder().deliveryState(response.acknowledgement
            ? AprsEvent.DELIVERY_DELIVERED : AprsEvent.DELIVERY_REJECTED)
            .nextRetryAtMs(null).build();
        return associatePacket(event, packet);
    }

    private PersistedEvent persistEvent(AprsPacket packet, AprsEvent candidate) {
        AprsEvent event = repository.findRecentByDedupKey(candidate.getDedupKey(),
            candidate.getLastSeenMs() - duplicateWindowMs(candidate));
        boolean created = event == null;
        if (created) {
            candidate = candidate.toBuilder().packetCount(1).build();
            event = candidate.toBuilder().id(repository.insert(candidate)).build();
            packet = packet.toBuilder().eventId(event.getId()).build();
            repository.insert(packet);
            repository.onEventPersisted(event);
        } else {
            event = mergeObservation(event, candidate);
            event = associatePacket(event, packet);
        }
        return new PersistedEvent(event, created);
    }

    private AprsEvent mergeObservation(AprsEvent event, AprsEvent observation) {
        return event.toBuilder().lastSeenMs(observation.getLastSeenMs())
            .relayCallsign(observation.getRelayCallsign())
            .internetOnly(event.isInternetOnly() && observation.isInternetOnly()).build();
    }

    private AprsEvent associatePacket(AprsEvent event, AprsPacket packet) {
        return associatePacket(event, packet, true);
    }

    private AprsEvent associatePacket(AprsEvent event, AprsPacket packet,
                                      boolean countAsObservation) {
        packet = packet.toBuilder().eventId(event.getId()).build();
        repository.insert(packet);
        if (countAsObservation) {
            event = event.toBuilder().packetCount(event.getPacketCount() + 1)
                .lastSeenMs(Math.max(event.getLastSeenMs(), packet.getTimestampMs())).build();
            repository.update(event);
        }
        repository.onEventPersisted(event);
        return event;
    }

    private void notifyAndAcknowledge(AprsEvent event, boolean notifyUser, String source) {
        if (callsign.isEmpty() || event.getToCallsign() == null
            || !event.getToCallsign().trim().equalsIgnoreCase(callsign)) {
            return;
        }
        if (notifyUser) {
            callbacks.onIncomingMessage(event);
        }
        if (AprsSource.RX_RF.equals(source) && event.getMessageIdentifier() != null
            && !event.getMessageIdentifier().trim().isEmpty()) {
            submitAcknowledgement(event);
        }
    }

    private void submitAcknowledgement(AprsEvent event) {
        if (txDestination.isEmpty()) {
            LOG.fine("Skipping APRS acknowledgement because no TX destination is configured");
            return;
        }
        APRSPacket acknowledgement = new APRSPacket(callsign, txDestination, txPath,
            MessagePacket.createMessagePayload(event.getFromCallsign(), "ack" + event.getMessageIdentifier(), null));
        Transmission transmission = callbacks.submitRf(acknowledgement.copy());
        if (transmission != null) {
            recordTransmissionNow(event.getId(), transmission, false, false);
        }
    }

    /**
     * Records a packet accepted by the application's TNC or radio transport for RF submission.
     *
     * <p>This method does not transmit RF. When {@code eventId} identifies an existing event, the
     * packet is associated with it and its count is updated; otherwise it is stored as unassociated
     * physical history. It does not establish that the packet was sent over RF or received by a
     * peer.</p>
     *
     * @param eventId event identifier, or {@code null} when no event is associated
     * @param packet parser packet accepted by the transport
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param rawAx25 AX.25 UI frame accepted by the transport, without FCS, flags, or KISS framing
     * @throws NullPointerException if {@code packet} is {@code null}
     */
    public synchronized void recordTransmission(Long eventId, APRSPacket packet, Long frequencyHz, byte[] rawAx25) {
        Transmission transmission = new Transmission(packet, frequencyHz, rawAx25);
        recordTransmissionNow(eventId, transmission);
    }

    /**
     * Records a TNC2 packet after the application has written it to a verified APRS-IS session.
     *
     * <p>This method records history only; it neither opens a connection nor transmits the line.
     * Malformed input is ignored.</p>
     *
     * @param eventId associated event identifier, or {@code null} for unassociated history
     * @param tnc2 transmitted TNC2 line without a line terminator
     */
    public synchronized void recordAprsIsTransmission(Long eventId, String tnc2) {
        APRSPacket frame;
        try {
            frame = Parser.parse(tnc2);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Ignoring malformed transmitted APRS-IS line", ex);
            return;
        }
        AprsPacket packet = physicalPacket(frame, AprsSource.TX_APRS_IS, null, null, tnc2);
        AprsEvent updated = repository.inTransaction(() -> {
            if (eventId == null) {
                repository.insert(packet);
                return null;
            } else {
                AprsEvent event = repository.findById(eventId);
                if (event == null) {
                    repository.insert(packet);
                    return null;
                } else {
                    return associatePacket(event, packet);
                }
            }
        });
        if (updated != null) {
            updatePendingReliableEvent(updated);
        }
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission) {
        recordTransmissionNow(eventId, transmission, false, true);
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission, boolean digipeated) {
        recordTransmissionNow(eventId, transmission, digipeated, true);
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission, boolean digipeated,
                                       boolean countAsObservation) {
        AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF, transmission.frequencyHz, transmission.rawAx25);
        AprsEvent updated = repository.inTransaction(() -> {
            if (eventId == null) {
                repository.insert(packet);
                return null;
            }
            AprsEvent event = repository.findById(eventId);
            if (event == null) {
                repository.insert(packet);
            } else {
                event = event.toBuilder().digipeated(event.isDigipeated() || digipeated).build();
                return associatePacket(event, packet, countAsObservation);
            }
            return null;
        });
        if (updated != null) {
            updatePendingReliableEvent(updated);
        }
    }

    /**
     * Runs due reliable-message retries and optional position-beacon scheduling.
     *
     * <p>The first invocation loads pending reliable events from the repository. Subsequent calls
     * use controller-maintained retry state. This class creates no scheduler thread; applications
     * must invoke this method periodically.</p>
     *
     * @param now current wall-clock time in milliseconds since the Unix epoch
     */
    public synchronized void tick(long now) {
        loadPendingReliableEvents();
        for (AprsEvent event : new ArrayList<>(pendingReliableEvents.values())) {
            if (event.getNextRetryAtMs() != null && event.getNextRetryAtMs() <= now) {
                retryOrFail(event, now);
            }
        }
        if (positionBeaconingEnabled && now >= nextPositionBeaconAt) {
            nextPositionBeaconAt = now + positionBeaconIntervalMs;
            submitPositionBeacon(callbacks.getBeaconData());
        }
    }

    /**
     * Configures beacon scheduling; enabling makes the next tick request a beacon immediately.
     * Disabling ignores the interval. Consumers scheduling externally can leave this disabled.
     * @param enabled whether controller ticks should request beacons
     * @param now current wall-clock time in milliseconds since the Unix epoch
     * @param intervalMs positive interval between requests when enabled
     * @throws IllegalArgumentException if enabling with a non-positive interval
     */
    public synchronized void setPositionBeaconingEnabled(boolean enabled, long now, long intervalMs) {
        if (enabled && intervalMs <= 0) {
            throw new IllegalArgumentException("Beacon interval must be positive");
        }
        positionBeaconIntervalMs = intervalMs;
        positionBeaconingEnabled = enabled;
        nextPositionBeaconAt = enabled ? now : 0;
    }

    private void retryOrFail(AprsEvent event, long now) {
        AprsPacket retryPacket = null;
        if (event.getTransmitAttempts() >= RETRY_DELAYS_MS.length + 1) {
            event = event.toBuilder().deliveryState(AprsEvent.DELIVERY_FAILED)
                .nextRetryAtMs(null).build();
        } else {
            APRSPacket original = loadRetryPacket(event);
            if (original == null) {
                event = event.toBuilder().deliveryState(AprsEvent.DELIVERY_FAILED)
                    .nextRetryAtMs(null).build();
            } else {
                Transmission transmission = callbacks.submitRf(original.copy());
                if (transmission == null) {
                    event = event.toBuilder().nextRetryAtMs(now + RETRY_DELAYS_MS[0]).build();
                } else {
                    AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF,
                        transmission.frequencyHz, transmission.rawAx25);
                    packet = packet.toBuilder().eventId(event.getId()).build();
                    retryPacket = packet;
                    int attempts = event.getTransmitAttempts() + 1;
                    event = event.toBuilder().packetCount(event.getPacketCount() + 1)
                        .lastSeenMs(Math.max(event.getLastSeenMs(), packet.getTimestampMs()))
                        .transmitAttempts(attempts)
                        .nextRetryAtMs(attempts >= RETRY_DELAYS_MS.length + 1
                            ? now + FINAL_ACK_GRACE_MS : now + RETRY_DELAYS_MS[attempts - 1]).build();
                }
            }
        }
        final AprsEvent updatedEvent = event;
        final AprsPacket packetToPersist = retryPacket;
        repository.inTransaction(() -> {
            if (packetToPersist != null) {
                repository.insert(packetToPersist);
            }
            repository.update(updatedEvent);
            repository.onEventPersisted(updatedEvent);
            return null;
        });
        updatePendingReliableEvent(event);
    }

    private APRSPacket loadRetryPacket(AprsEvent event) {
        AprsPacket original = repository.findInitialRfTransmission(event.getId());
        if (original == null || original.getRawAx25() == null) {
            LOG.warning("Failing reliable message because its initial RF frame is unavailable");
            return null;
        }
        try {
            return Parser.parseAX25(original.getRawAx25());
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failing reliable message because its initial RF frame is invalid", ex);
            return null;
        }
    }

    /**
     * Records a newly submitted outgoing message and its first RF packet.
     *
     * <p>This method does not transmit RF. Numbered destinations that require acknowledgement are
     * entered into the reliable-message retry state; bulletin, ALL, QST, and CQ destinations are
     * recorded without retries. Recording a submission does not establish message delivery; a
     * matching APRS ACK does.</p>
     *
     * @param from local source callsign
     * @param to destination callsign
     * @param text transmitted message body
     * @param messageIdentifier APRS message number, required for reliable destinations
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param packet parser packet accepted by the transport
     * @param rawAx25 AX.25 UI frame accepted by the transport, without FCS, flags, or KISS framing
     */
    public synchronized void recordOutgoingMessage(String from, String to, String text, String messageIdentifier,
                                      Long frequencyHz, APRSPacket packet, byte[] rawAx25) {
        long now = clock.millis();
        AprsEvent.AprsEventBuilder event = AprsEvent.builder();
        event.type(AprsEvent.MESSAGE_TYPE);
        event.firstSeenMs(now);
        event.lastSeenMs(now);
        event.packetCount(1);
        event.fromCallsign(from.toUpperCase(Locale.ROOT).trim());
        event.toCallsign(to.toUpperCase(Locale.ROOT).trim());
        event.body(text.trim());
        if (requiresAcknowledgement(to)) {
            event.messageIdentifier(messageIdentifier);
            event.deliveryState(AprsEvent.DELIVERY_PENDING);
            event.transmitAttempts(1);
            event.nextRetryAtMs(now + RETRY_DELAYS_MS[0]);
        }
        persistOutgoingEvent(event.build(), packet, frequencyHz, rawAx25);
    }

    /**
     * Determines whether a destination is eligible for APRS reliable-message acknowledgement.
     *
     * @param destination destination callsign or bulletin/query address
     * @return {@code false} for {@code null}, BLN*, ALL, QST, and CQ; {@code true} otherwise
     */
    public static boolean requiresAcknowledgement(String destination) {
        if (destination == null) {
            return false;
        }
        String normalized = destination.trim().toUpperCase(Locale.ROOT);
        return !normalized.startsWith("BLN") && !normalized.equals("ALL")
            && !normalized.equals("QST") && !normalized.equals("CQ");
    }

    /**
     * Records a newly submitted outgoing position or positioned-weather beacon and its first RF
     * packet.
     *
     * <p>This method records a transport submission; it does not request or perform one, and does
     * not confirm on-air transmission.</p>
     *
     * @param callsign local source callsign
     * @param latitude latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param packet parser packet accepted by the transport
     * @param rawAx25 AX.25 UI frame accepted by the transport, without FCS, flags, or KISS framing
     */
    public synchronized void recordPositionBeacon(String callsign, double latitude, double longitude, Long frequencyHz, APRSPacket packet, byte[] rawAx25) {
        persistOutgoingEvent(outgoingPositionEvent(callsign, latitude, longitude, packet, rawAx25),
            packet, frequencyHz, rawAx25);
    }

    private AprsEvent outgoingPositionEvent(String callsign, double latitude, double longitude,
                                            APRSPacket packet, byte[] rawAx25) {
        try {
            APRSPacket decoded = Parser.parseAX25(rawAx25 == null ? packet.toAX25Frame() : rawAx25);
            PacketContext context = unwrap(decoded);
            ParsedEvent parsed = context == null ? null : parseEvent(context);
            if (parsed != null && parsed.event != null) {
                return parsed.event.toBuilder().packetCount(1).build();
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to decode submitted position beacon", ex);
        }
        long now = clock.millis();
        return AprsEvent.builder().type(AprsEvent.POSITION_TYPE).firstSeenMs(now).lastSeenMs(now)
            .packetCount(1).fromCallsign(callsign).positionLat(latitude).positionLong(longitude)
            .build();
    }

    private void persistOutgoingEvent(AprsEvent event, APRSPacket frame, Long frequencyHz, byte[] rawAx25) {
        event = event.toBuilder().dedupKey(logicalPacketKey(frame)).build();
        AprsPacket packet = physicalPacket(frame, AprsSource.TX_RF, frequencyHz,
            rawAx25 == null ? frame.toAX25Frame() : rawAx25);
        final AprsEvent eventToPersist = event;
        final AprsPacket packetToPersist = packet;
        event = repository.inTransaction(() -> {
            AprsEvent persistedEvent = eventToPersist.toBuilder()
                .id(repository.insert(eventToPersist)).build();
            repository.insert(packetToPersist.toBuilder().eventId(persistedEvent.getId()).build());
            repository.onEventPersisted(persistedEvent);
            return persistedEvent;
        });
        updatePendingReliableEvent(event);
    }

    private void submitPositionBeacon(BeaconData beacon) {
        if (beacon == null || callsign.isEmpty() || txDestination.isEmpty()) {
            return;
        }
        if (!isValidBeacon(beacon)) {
            return;
        }
        APRSPacket packet = new APRSPacket(callsign, txDestination, txPath,
            encodeBeacon(beacon).getBytes(StandardCharsets.US_ASCII));
        Transmission transmission = callbacks.submitRf(packet.copy());
        if (transmission != null) {
            recordPositionBeacon(callsign, beacon.getLatitude(), beacon.getLongitude(),
                transmission.frequencyHz, transmission.packet, transmission.rawAx25);
        }
    }

    private String encodeBeacon(BeaconData beacon) {
        char table = beacon.getSymbolTable() == null ? '/' : beacon.getSymbolTable();
        char code = beacon.getWeather() == null
            ? beacon.getSymbolCode() == null ? '>' : beacon.getSymbolCode() : '_';
        Position coordinateFormatter = new Position(0D, 0D);
        StringBuilder value = new StringBuilder("!")
            .append(coordinateFormatter.getDMS(beacon.getLatitude(), true))
            .append(table).append(coordinateFormatter.getDMS(beacon.getLongitude(), false))
            .append(code);
        if (beacon.getCourseDegrees() != null && beacon.getSpeedKnots() != null) {
            value.append(String.format(Locale.ROOT, "%03d/%03d", beacon.getCourseDegrees(),
                Math.round(beacon.getSpeedKnots())));
        }
        if (beacon.getAltitudeMeters() != null) {
            value.append(String.format(Locale.ROOT, "/A=%06d",
                Math.round(beacon.getAltitudeMeters() * 3.28084D)));
        }
        appendWeather(value, beacon.getWeather());
        if (beacon.getComment() != null) {
            value.append(beacon.getComment());
        }
        return value.toString();
    }

    private void appendWeather(StringBuilder value, BeaconData.WeatherData weather) {
        if (weather == null) {
            return;
        }
        if (weather.getWindDirectionDegrees() != null && weather.getWindSpeedKnots() != null) {
            value.append(String.format(Locale.ROOT, "%03d/%03d", weather.getWindDirectionDegrees(),
                weather.getWindSpeedKnots()));
        }
        if (weather.getWindGustKnots() != null) {
            value.append(String.format(Locale.ROOT, "g%03d", weather.getWindGustKnots()));
        }
        if (weather.getTemperatureCelsius() != null) {
            value.append(String.format(Locale.ROOT, "t%03d",
                Math.round(weather.getTemperatureCelsius() * 9D / 5D + 32D)));
        }
        if (weather.getHumidityPercent() != null) {
            int humidity = weather.getHumidityPercent() == 100 ? 0 : weather.getHumidityPercent();
            value.append(String.format(Locale.ROOT, "h%02d", humidity));
        }
        if (weather.getPressureHectopascals() != null) {
            value.append(String.format(Locale.ROOT, "b%05d",
                Math.round(weather.getPressureHectopascals() * 10D)));
        }
    }

    private boolean isValidBeacon(BeaconData beacon) {
        if (!isFiniteInRange(beacon.getLatitude(), -90D, 90D)
            || !isFiniteInRange(beacon.getLongitude(), -180D, 180D)) {
            return rejectBeacon("latitude/longitude must be finite and within APRS bounds");
        }
        if (!isIntegerInRange(beacon.getCourseDegrees(), 0, 360)
            || !isRoundedInRange(beacon.getSpeedKnots(), 0, 999)) {
            return rejectBeacon("course must be 0..360 and speed must encode as 0..999 knots");
        }
        if (beacon.getAltitudeMeters() != null
            && !isRoundedInRange(beacon.getAltitudeMeters() * 3.28084D, 0, 999_999)) {
            return rejectBeacon("altitude must encode as 0..999999 feet");
        }
        return isValidWeather(beacon.getWeather());
    }

    private boolean isValidWeather(BeaconData.WeatherData weather) {
        if (weather == null) {
            return true;
        }
        if (!isIntegerInRange(weather.getWindDirectionDegrees(), 0, 360)
            || !isIntegerInRange(weather.getWindSpeedKnots(), 0, 999)
            || !isIntegerInRange(weather.getWindGustKnots(), 0, 999)) {
            return rejectBeacon("weather wind fields must fit their APRS ranges");
        }
        if (weather.getTemperatureCelsius() != null
            && !isRoundedInRange(weather.getTemperatureCelsius() * 9D / 5D + 32D, -99, 999)) {
            return rejectBeacon("weather temperature must encode as -99..999 Fahrenheit");
        }
        if (!isIntegerInRange(weather.getHumidityPercent(), 1, 100)) {
            return rejectBeacon("weather humidity must be 1..100 percent");
        }
        if (weather.getPressureHectopascals() != null
            && !isRoundedInRange(weather.getPressureHectopascals() * 10D, 0, 99_999)) {
            return rejectBeacon("weather pressure must encode as 0..99999 tenths of a hectopascal");
        }
        return true;
    }

    private boolean isFiniteInRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private boolean isIntegerInRange(Integer value, int minimum, int maximum) {
        return value == null || value >= minimum && value <= maximum;
    }

    private boolean isRoundedInRange(Double value, long minimum, long maximum) {
        return value == null || Double.isFinite(value) && Math.round(value) >= minimum
            && Math.round(value) <= maximum;
    }

    private boolean rejectBeacon(String reason) {
        LOG.warning("Skipping invalid position beacon: " + reason);
        return false;
    }

    private void loadPendingReliableEvents() {
        if (pendingReliableEventsLoaded) {
            return;
        }
        for (AprsEvent event : repository.loadPendingReliableEvents()) {
            updatePendingReliableEvent(event);
        }
        pendingReliableEventsLoaded = true;
    }

    private void updatePendingReliableEvent(AprsEvent event) {
        if (event.getDeliveryState() == AprsEvent.DELIVERY_PENDING
            && event.getNextRetryAtMs() != null) {
            pendingReliableEvents.put(event.getId(), event);
        } else {
            pendingReliableEvents.remove(event.getId());
        }
    }

    private AprsPacket physicalPacket(APRSPacket frame, String source, Long frequencyHz, byte[] rawAx25) {
        return physicalPacket(frame, source, frequencyHz, rawAx25, null);
    }

    private AprsPacket physicalPacket(APRSPacket frame, String source, Long frequencyHz, byte[] rawAx25, String rawTnc2) {
        List<Digipeater> digipeaters = frame.getDigipeaters();
        return AprsPacket.builder().timestampMs(clock.millis())
            .source(source == null ? AprsSource.UNKNOWN : source).frequencyHz(frequencyHz)
            .fromCallsign(frame.getSourceCall()).ax25Destination(frame.getDestinationCall())
            .path(digipeaters.isEmpty() ? null : digipeaters.stream()
                .map(Digipeater::toString).collect(Collectors.joining(",")))
            .rawAx25(rawAx25).rawTnc2(rawTnc2).build();
    }

    private ParsedEvent parseEvent(PacketContext context) {
        APRSPacket packet = context.packet;
        InformationField info = context.info;
        if (info.getDataTypeIdentifier() == ':') {
            MessagePacket message = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
            if (message.isAck() || message.isRej()) {
                return ParsedEvent.delivery(message.isAck(), message.isRej(), packet.getSourceCall(),
                    message.getTargetCallsign(), message.getMessageNumber());
            }
        }
        AprsEvent.AprsEventBuilder event = AprsEvent.builder();
        long now = clock.millis();
        event.firstSeenMs(now);
        event.lastSeenMs(now);
        event.fromCallsign(packet.getSourceCall());
        event.relayCallsign(context.relayCallsign);
        WeatherField weather = (WeatherField) info.getAprsData(APRSTypes.T_WX);
        PositionField position = (PositionField) info.getAprsData(APRSTypes.T_POSITION);
        ObjectField object = (ObjectField) info.getAprsData(APRSTypes.T_OBJECT);
        StatusField status = (StatusField) info.getAprsData(APRSTypes.T_STATUS);
        StationCapabilitiesField capabilities = (StationCapabilitiesField) info.getAprsData(APRSTypes.T_STATCAPA);
        applyPosition(event, position);
        if (position == null && object != null) {
            applyPosition(event, object.getPosition());
        }
        applyComment(event, packet, info, position, object, weather);
        applyPayload(event, packet, info, object, weather, status, capabilities);
        if (packet.hasFault()) {
            return null;
        }
        if (event.build().getType() == AprsEvent.UNKNOWN_TYPE) {
            event.comment("Raw: " + new String(info.getRawBytes(), StandardCharsets.ISO_8859_1));
        }
        event.dedupKey(logicalPacketKey(packet));
        return ParsedEvent.event(event.build());
    }

    private void applyPosition(AprsEvent.AprsEventBuilder event, PositionField position) {
        if (position == null) {
            return;
        }
        event.type(AprsEvent.POSITION_TYPE);
        event.positionLat(Objects.requireNonNull(position.getPosition()).getLatitude());
        event.positionLong(Objects.requireNonNull(position.getPosition()).getLongitude());
    }

    private void applyComment(AprsEvent.AprsEventBuilder event, APRSPacket packet, InformationField info, PositionField position, ObjectField object, WeatherField weather) {
        String comment = firstComment(packet.getComment(), info.getComment());
        comment = firstComment(comment, position == null ? null : position.getComment());
        comment = firstComment(comment, object == null ? null : object.getComment());
        event.comment(firstComment(comment, weather == null ? null : weather.getComment()));
    }

    private String firstComment(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred;
    }

    private void applyPayload(AprsEvent.AprsEventBuilder event, APRSPacket packet, InformationField info, ObjectField object, WeatherField weather, StatusField status, StationCapabilitiesField capabilities) {
        if (weather != null) {
            applyWeather(event, weather);
            return;
        }
        if (info.getDataTypeIdentifier() == ';') {
            applyObject(event, object);
        }
        if (info.getDataTypeIdentifier() == ':') {
            applyMessage(event, packet, info);
        }
        if (status != null) {
            applyStatus(event, status);
        }
        if (capabilities != null) {
            applyCapabilities(event, capabilities);
        }
    }

    private void applyWeather(AprsEvent.AprsEventBuilder event, WeatherField weather) {
        event.type(AprsEvent.WEATHER_TYPE);
        event.temperature(valueOrZero(weather.getTemp()));
        event.humidity(valueOrZero(weather.getHumidity()));
        event.pressure(valueOrZero(weather.getPressure()));
        event.rain(valueOrZero(weather.getRainLast24Hours()));
        event.snow(valueOrZero(weather.getSnowfallLast24Hours()));
        event.windForce(valueOrZero(weather.getWindSpeed()));
        event.windDirection(cardinalDirection(weather.getWindDirection()));
    }

    private void applyObject(AprsEvent.AprsEventBuilder event, ObjectField object) {
        event.type(AprsEvent.OBJECT_TYPE);
        if (object != null) {
            event.objectName(object.getObjectName());
        }
    }

    private void applyMessage(AprsEvent.AprsEventBuilder event, APRSPacket packet, InformationField info) {
        event.type(AprsEvent.MESSAGE_TYPE);
        MessagePacket message = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
        event.toCallsign(message.getTargetCallsign());
        event.messageIdentifier(message.getMessageNumber());
        event.body(message.getMessageBody());
    }

    private void applyStatus(AprsEvent.AprsEventBuilder event, StatusField status) {
        event.type(AprsEvent.STATUS_TYPE);
        event.comment(status.getStatusText());
    }

    private void applyCapabilities(AprsEvent.AprsEventBuilder event, StationCapabilitiesField capabilities) {
        event.type(AprsEvent.STATION_CAPABILITIES_TYPE);
        event.comment(capabilities.getDisplayText());
    }

    private double valueOrZero(Double value) {
        return value == null ? 0 : value;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String cardinalDirection(Integer direction) {
        return direction == null ? "" : Utilities.degressToCardinal(direction);
    }

    private long duplicateWindowMs(AprsEvent event) {
        return event.getType() == AprsEvent.MESSAGE_TYPE
                && event.getMessageIdentifier() != null && !event.getMessageIdentifier().trim().isEmpty()
            ? NUMBERED_MESSAGE_DUPLICATE_WINDOW_MS : EVENT_DUPLICATE_WINDOW_MS;
    }

    private String logicalPacketKey(APRSPacket packet) {
        return packet.getSourceCall() + "|" + packet.getDestinationCall() + "|"
            + Base64.getEncoder().encodeToString(packet.getPayload().getRawBytes());
    }

    private String normalizeCallsign(String callsign) {
        return callsign == null ? "" : callsign.trim().toUpperCase(Locale.ROOT);
    }

    private Transmission maybeDigipeat(APRSPacket packet) {
        String localCallsign = callsign;
        if (!digipeatingEnabled || localCallsign.isEmpty()
            || packet == null || packet.getPayload() == null || packet.hasFault()) {
            return null;
        }
        String key = logicalPacketKey(packet);
        long now = clock.millis();
        pruneDigipeatCache(digipeatInputCache, now);
        if (digipeatInputCache.containsKey(key)) {
            return null;
        }
        List<Digipeater> digis = packet.getDigipeaters();
        if (digis == null || digis.isEmpty()) {
            return null;
        }
        int index = firstUnusedDigipeater(digis);
        if (index < 0) {
            return null;
        }
        Digipeater next = digis.get(index);
        String baseCall = APRSPacket.getBaseCall(next.getCallsign());
        int ssid = parseSsid(next);
        boolean ours = normalizeAx25Address(next.toString())
            .equals(normalizeAx25Address(localCallsign));
        boolean wide1 = baseCall.equalsIgnoreCase("WIDE1") && ssid == 1;
        if (!ours && !wide1) {
            return null;
        }
        List<Digipeater> replacement = new ArrayList<>(digis);
        if (ours) {
            replacement.set(index, usedDigipeater(next.toString()));
        } else {
            replacement.set(index, usedDigipeater(localCallsign));
        }
        APRSPacket retransmit = new APRSPacket(packet.getSourceCall(), packet.getDestinationCall(), replacement, packet.getPayload().getRawBytes());
        retransmit.setComment(packet.getComment());
        Transmission transmission = callbacks.submitRf(retransmit.copy());
        if (transmission != null) {
            digipeatInputCache.put(key, now);
            digipeatOutputCache.put(digipeatOutputKey(retransmit), now);
        }
        return transmission;
    }

    private boolean isRecentlyDigipeated(APRSPacket packet) {
        long now = clock.millis();
        pruneDigipeatCache(digipeatOutputCache, now);
        Long previous = digipeatOutputCache.get(digipeatOutputKey(packet));
        return previous != null && now - previous < DIGIPEAT_DEDUP_MS;
    }

    private void pruneDigipeatCache(Map<String, Long> cache, long now) {
        cache.entrySet().removeIf(entry -> now - entry.getValue() >= DIGIPEAT_DEDUP_MS);
    }

    private String digipeatOutputKey(APRSPacket packet) {
        String path = packet.getDigipeaters().stream()
            .map(Digipeater::toString).collect(Collectors.joining(","));
        return packet.getSourceCall() + "|" + packet.getDestinationCall() + "|" + path + "|"
            + Base64.getEncoder().encodeToString(packet.getPayload().getRawBytes());
    }

    private int firstUnusedDigipeater(List<Digipeater> digis) {
        for (int i = 0; i < digis.size(); i++) {
            if (!digis.get(i).isUsed()) {
                return i;
            }
        }
        return -1;
    }

    private int parseSsid(Digipeater digipeater) {
        try {
            return Integer.parseInt(APRSPacket.getSsid(digipeater.toString()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String normalizeAx25Address(String address) {
        String normalized = normalizeCallsign(address);
        String baseCall = APRSPacket.getBaseCall(normalized);
        try {
            int ssid = Integer.parseInt(APRSPacket.getSsid(normalized));
            return ssid == 0 ? baseCall : baseCall + "-" + ssid;
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    private void maybeGateToAprsIs(APRSPacket packet, Long eventId) {
        if (!igateEnabled) {
            return;
        }
        String tnc2 = igateLine(packet, 0);
        String callsign = normalizeAx25Address(this.callsign);
        if (tnc2 != null && !callsign.isEmpty()) {
            String gated = appendIgateConstruct(tnc2, callsign);
            if (gated != null) {
                callbacks.submitAprsIs(gated, () -> recordAprsIsTransmission(eventId, gated));
            }
        }
    }

    private String igateLine(APRSPacket packet, int depth) {
        if (packet == null || packet.getPayload() == null || depth > 4
            || containsForbiddenGatePath(packet) || packet.getDti() == '?') {
            return null;
        }
        if (packet.getDti() != '}') {
            return toTnc2(packet);
        }

        byte[] payload = packet.getPayload().getRawBytes();
        if (payload.length < 2) {
            return null;
        }
        String innerLine = new String(payload, 1, payload.length - 1,
            StandardCharsets.ISO_8859_1);
        try {
            return igateLine(Parser.parse(innerLine), depth + 1);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean containsForbiddenGatePath(APRSPacket packet) {
        List<Digipeater> path = packet.getDigipeaters();
        for (Digipeater digipeater : path) {
            String callsign = digipeater.getCallsign().toUpperCase(Locale.ROOT);
            if ("TCPIP".equals(callsign) || "TCPXX".equals(callsign)
                    || "NOGATE".equals(callsign) || "RFONLY".equals(callsign)
                    || "I".equals(callsign)
                    || APRSPacket.Q_CONSTRUCTS.contains(callsign.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String toTnc2(APRSPacket packet) {
        StringBuilder line = new StringBuilder(packet.getSourceCall()).append('>')
            .append(packet.getDestinationCall());
        List<Digipeater> path = packet.getDigipeaters();
        if (path != null && !path.isEmpty()) {
            line.append(',').append(path.stream().map(Digipeater::toString)
                .collect(Collectors.joining(",")));
        }
        return line.append(':').append(new String(packet.getPayload().getRawBytes(),
            StandardCharsets.ISO_8859_1)).toString();
    }

    private String appendIgateConstruct(String tnc2, String callsign) {
        int payloadSeparator = tnc2.indexOf(':');
        if (payloadSeparator < 0) {
            return null;
        }
        return tnc2.substring(0, payloadSeparator) + ",qAO," + callsign
            + tnc2.substring(payloadSeparator);
    }

    private Digipeater usedDigipeater(String callsign) {
        Digipeater digipeater = new Digipeater(callsign);
        digipeater.setUsed(true);
        return digipeater;
    }

    private PacketContext unwrap(APRSPacket raw) {
        InformationField info = raw.getPayload();
        ThirdPartyField thirdParty = (ThirdPartyField) info.getAprsData(APRSTypes.T_THIRDPARTY);
        if (thirdParty == null) {
            return new PacketContext(raw, info, null);
        }
        APRSPacket inner = thirdParty.getInnerPacket();
        return inner == null || inner.hasFault() ? null
            : new PacketContext(inner, inner.getPayload(), raw.getSourceCall());
    }

    private static final class PacketContext {
        private final APRSPacket packet;
        private final InformationField info;
        private final String relayCallsign;

        private PacketContext(APRSPacket packet, InformationField info, String relayCallsign) {
            this.packet = packet;
            this.info = info;
            this.relayCallsign = relayCallsign;
        }
    }

    /** Result of persistence work, retained until application callbacks can run after commit. */
    private static final class PersistedEvent {
        private final AprsEvent event;
        private final boolean created;

        private PersistedEvent(AprsEvent event, boolean created) {
            this.event = event;
            this.created = created;
        }

        private static PersistedEvent none() {
            return new PersistedEvent(null, false);
        }

        private static PersistedEvent existing(AprsEvent event) {
            return new PersistedEvent(event, false);
        }
    }

    private static final class ParsedEvent {
        private final AprsEvent event;
        private final boolean acknowledgement;
        private final boolean rejection;
        private final String fromCallsign;
        private final String targetCallsign;
        private final String messageIdentifier;

        private ParsedEvent(AprsEvent event, boolean acknowledgement, boolean rejection,
                            String fromCallsign, String targetCallsign, String messageIdentifier) {
            this.event = event;
            this.acknowledgement = acknowledgement;
            this.rejection = rejection;
            this.fromCallsign = fromCallsign;
            this.targetCallsign = targetCallsign;
            this.messageIdentifier = messageIdentifier;
        }

        private static ParsedEvent event(AprsEvent event) {
            return new ParsedEvent(event, false, false, null, null, null);
        }

        private static ParsedEvent delivery(boolean acknowledgement, boolean rejection,
                                            String from, String target, String identifier) {
            return new ParsedEvent(null, acknowledgement, rejection, from, target, identifier);
        }
    }
}
