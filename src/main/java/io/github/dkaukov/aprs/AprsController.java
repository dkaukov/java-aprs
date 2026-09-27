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
     * Immutable result reported by a radio callback after an actual RF transmission.
     *
     * <p>The constructor copies both mutable inputs. A non-null instance means the caller may
     * record a physical TX packet; callbacks return {@code null} when no transmission occurred.</p>
     */
    public static final class Transmission {
        private final APRSPacket packet;
        /** RF frequency in Hz, or {@code null} when it was not available. */
        public final Long frequencyHz;
        private final byte[] rawAx25;

        /**
         * Creates a transmission result from snapshots of a parsed packet and its encoded frame.
         *
         * @param packet transmitted parser packet; must not be {@code null}
         * @param frequencyHz RF frequency in Hz, or {@code null}
         * @param rawAx25 transmitted AX.25 UI frame without FCS, flags, or KISS framing
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
         * Returns the current local station callsign.
         *
         * @return local callsign, or {@code null} when no station identity is active
         */
        String getCallsign();
        /**
         * Handles a newly created message addressed to {@link #getCallsign()}.
         *
         * <p>This is not invoked for duplicate packet copies collapsed into an existing event.</p>
         *
         * @param event immutable message event
         */
        void onIncomingMessage(AprsEvent event);
        /**
         * Sends the RF ACK for a received numbered message.
         *
         * @param destination message origin callsign
         * @param messageIdentifier APRS message number being acknowledged
         * @param eventId persistent identifier of the received message event
         */
        void sendAcknowledgement(String destination, String messageIdentifier, long eventId);
        /**
         * Attempts an RF retransmission for a pending reliable message.
         *
         * @param event immutable pending message event
         * @return actual transmission snapshot, or {@code null} when nothing was transmitted
         */
        Transmission retryMessage(AprsEvent event);
        /** Requests that the application build and transmit a position beacon. */
        void requestPositionBeacon();
        /**
         * Attempts an RF retransmission of a packet selected for digipeating.
         *
         * @param packet detached parser-packet snapshot that may be retained or modified
         * @return actual transmission snapshot, or {@code null} when nothing was transmitted
         */
        Transmission transmitDigipeatedPacket(APRSPacket packet);
        /**
         * Forwards an RF packet accepted by the controller's iGate policy to APRS-IS.
         *
         * @param tnc2 packet line without a terminator
         * @param eventId associated event ID, or {@code null} when no logical event was created
         * @return application-defined delivery indication; the controller currently ignores it
         */
        boolean gateToAprsIs(String tnc2, Long eventId);
    }

    private final AprsRepository repository;
    private final Callbacks callbacks;
    private final Clock clock;
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
     * @param enabled {@code true} to invoke {@link Callbacks#gateToAprsIs(String, Long)} for
     *                eligible RF packets
     */
    public synchronized void setIgateEnabled(boolean enabled) {
        igateEnabled = enabled;
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
        try {
            handleDecoded(Parser.parse(tnc2), AprsSource.RX_APRS_IS, null, null, tnc2);
        } catch (Exception ignored) {
            // Ignore malformed Internet input just as the RF parser ignores malformed frames.
        }
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
        AprsEvent event = repository.inTransaction(() -> persistPacket(packet, parsed));
        if (digipeated != null) {
            recordTransmissionNow(event == null ? null : event.getId(), digipeated, true);
        }
        if (AprsSource.RX_RF.equals(packet.getSource())) {
            maybeGateToAprsIs(frame, event == null ? null : event.getId());
        }
    }

    private AprsEvent persistPacket(AprsPacket packet, ParsedEvent parsed) {
        if (parsed == null) {
            repository.insert(packet);
            return null;
        }
        if (parsed.acknowledgement || parsed.rejection) {
            return persistDeliveryResponse(packet, parsed);
        }
        if (parsed.event != null) {
            return persistEvent(packet, parsed.event);
        }
        repository.insert(packet);
        return null;
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

    private AprsEvent persistEvent(AprsPacket packet, AprsEvent candidate) {
        AprsEvent event = repository.findRecentByDedupKey(candidate.getDedupKey(),
            candidate.getLastSeenMs() - duplicateWindowMs(candidate));
        boolean created = event == null;
        if (created) {
            candidate = candidate.toBuilder().packetCount(1).build();
            event = candidate.toBuilder().id(repository.insert(candidate)).build();
            packet = packet.toBuilder().eventId(event.getId()).build();
            repository.insert(packet);
        } else {
            event = mergeObservation(event, candidate);
            event = associatePacket(event, packet);
        }
        if (event.getType() == AprsEvent.MESSAGE_TYPE) {
            notifyAndAcknowledge(event, created, packet.getSource());
        }
        return event;
    }

    private AprsEvent mergeObservation(AprsEvent event, AprsEvent observation) {
        return event.toBuilder().lastSeenMs(observation.getLastSeenMs())
            .relayCallsign(observation.getRelayCallsign())
            .internetOnly(event.isInternetOnly() && observation.isInternetOnly()).build();
    }

    private AprsEvent associatePacket(AprsEvent event, AprsPacket packet) {
        packet = packet.toBuilder().eventId(event.getId()).build();
        repository.insert(packet);
        event = event.toBuilder().packetCount(event.getPacketCount() + 1)
            .lastSeenMs(Math.max(event.getLastSeenMs(), packet.getTimestampMs())).build();
        repository.update(event);
        updatePendingReliableEvent(event);
        return event;
    }

    private void notifyAndAcknowledge(AprsEvent event, boolean notifyUser, String source) {
        String callsign = callbacks.getCallsign();
        if (callsign == null || event.getToCallsign() == null || !event.getToCallsign().trim().equalsIgnoreCase(callsign.trim())) {
            return;
        }
        if (notifyUser) {
            callbacks.onIncomingMessage(event);
        }
        if (AprsSource.RX_RF.equals(source) && event.getMessageIdentifier() != null && !event.getMessageIdentifier().trim().isEmpty()) {
            callbacks.sendAcknowledgement(event.getFromCallsign().toUpperCase(Locale.ROOT), event.getMessageIdentifier(), event.getId());
        }
    }

    /**
     * Records a packet that the application has already transmitted on RF.
     *
     * <p>This method does not transmit RF. When {@code eventId} identifies an existing event, the
     * packet is associated with it and its count is updated; otherwise it is stored as unassociated
     * physical history.</p>
     *
     * @param eventId event identifier, or {@code null} when no event is associated
     * @param packet transmitted parser packet
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param rawAx25 transmitted AX.25 UI frame without FCS, flags, or KISS framing
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
        try {
            APRSPacket frame = Parser.parse(tnc2);
            AprsPacket packet = physicalPacket(frame, AprsSource.TX_APRS_IS, null, null, tnc2);
            repository.inTransaction(() -> {
                if (eventId == null) {
                    repository.insert(packet);
                } else {
                    AprsEvent event = repository.findById(eventId);
                    if (event == null) {
                        repository.insert(packet);
                    } else {
                        associatePacket(event, packet);
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Ignoring malformed transmitted APRS-IS line", ex);
        }
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission) {
        recordTransmissionNow(eventId, transmission, false);
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission, boolean digipeated) {
        AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF, transmission.frequencyHz, transmission.rawAx25);
        repository.inTransaction(() -> {
            if (eventId == null) {
                repository.insert(packet);
                return null;
            }
            AprsEvent event = repository.findById(eventId);
            if (event == null) {
                repository.insert(packet);
            } else {
                event = event.toBuilder().digipeated(event.isDigipeated() || digipeated).build();
                associatePacket(event, packet);
            }
            return null;
        });
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
            callbacks.requestPositionBeacon();
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
            Transmission transmission = callbacks.retryMessage(event);
            if (transmission == null) {
                event = event.toBuilder().nextRetryAtMs(now + RETRY_DELAYS_MS[0]).build();
            } else {
                AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF, transmission.frequencyHz, transmission.rawAx25);
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
        final AprsEvent updatedEvent = event;
        final AprsPacket packetToPersist = retryPacket;
        repository.inTransaction(() -> {
            if (packetToPersist != null) {
                repository.insert(packetToPersist);
            }
            repository.update(updatedEvent);
            return null;
        });
        updatePendingReliableEvent(event);
    }

    /**
     * Records a newly transmitted outgoing message and its first RF packet.
     *
     * <p>This method does not transmit RF. Numbered destinations that require acknowledgement are
     * entered into the reliable-message retry state; bulletin, ALL, QST, and CQ destinations are
     * recorded without retries.</p>
     *
     * @param from local source callsign
     * @param to destination callsign
     * @param text transmitted message body
     * @param messageIdentifier APRS message number, required for reliable destinations
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param packet transmitted parser packet
     * @param rawAx25 transmitted AX.25 UI frame without FCS, flags, or KISS framing
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
     * Records a newly transmitted outgoing position beacon and its first RF packet.
     *
     * <p>This method records a transmission; it does not request or perform one.</p>
     *
     * @param callsign local source callsign
     * @param latitude latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @param frequencyHz RF frequency in Hz, or {@code null}
     * @param packet transmitted parser packet
     * @param rawAx25 transmitted AX.25 UI frame without FCS, flags, or KISS framing
     */
    public synchronized void recordPositionBeacon(String callsign, double latitude, double longitude, Long frequencyHz, APRSPacket packet, byte[] rawAx25) {
        long now = clock.millis();
        AprsEvent.AprsEventBuilder event = AprsEvent.builder();
        event.type(AprsEvent.POSITION_TYPE);
        event.firstSeenMs(now);
        event.lastSeenMs(now);
        event.packetCount(1);
        event.fromCallsign(callsign);
        event.positionLat(latitude);
        event.positionLong(longitude);
        persistOutgoingEvent(event.build(), packet, frequencyHz, rawAx25);
    }

    private void persistOutgoingEvent(AprsEvent event, APRSPacket frame, Long frequencyHz, byte[] rawAx25) {
        event = event.toBuilder().dedupKey(logicalPacketKey(frame)).build();
        AprsPacket packet = physicalPacket(frame, AprsSource.TX_RF, frequencyHz, rawAx25);
        final AprsEvent eventToPersist = event;
        final AprsPacket packetToPersist = packet;
        event = repository.inTransaction(() -> {
            AprsEvent persistedEvent = eventToPersist.toBuilder()
                .id(repository.insert(eventToPersist)).build();
            repository.insert(packetToPersist.toBuilder().eventId(persistedEvent.getId()).build());
            return persistedEvent;
        });
        updatePendingReliableEvent(event);
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
        String localCallsign = callbacks.getCallsign();
        if (!digipeatingEnabled || localCallsign == null || localCallsign.trim().isEmpty()
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
        Transmission transmission = callbacks.transmitDigipeatedPacket(retransmit.copy());
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
        String callsign = normalizeAx25Address(callbacks.getCallsign());
        if (tnc2 != null && !callsign.isEmpty()) {
            callbacks.gateToAprsIs(appendIgateConstruct(tnc2, callsign), eventId);
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
