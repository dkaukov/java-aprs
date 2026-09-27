# Consumer migration guide before 0.1.0

These changes require updating and recompiling consumers of the snapshot library,
including the Android app. Update constructor calls, event persistence mappers,
callback implementations, and beacon configuration before rebuilding.

## Android and Java runtime compatibility

The library now targets the Java 8 runtime and does not require Android core-library
desugaring for Java 9+ collection factories. `APRSData` and parser snapshots use
Java-8-compatible unmodifiable collection wrappers. Android consumers with minSdk
26 can include the library without enabling core-library desugaring for it.

## Controller construction and threading

Remove the executor argument from controller construction:

```java
AprsController controller = new AprsController(repository, callbacks);
```

All state-changing calls are synchronous and internally serialized per controller
instance, including settings, repository operations, and callbacks. Concurrent
callers block until the current operation ends. No internal executor or worker
thread is created; calls now finish processing before returning rather than
queuing persistence for later.

Schedule calls externally when background execution is needed. Any executor is
safe for serialization, but use an ordered executor if arrival order matters;
monitor acquisition does not guarantee FIFO ordering. Snapshot mutable inputs
**before** placing them on an app-owned queue:

```java
APRSPacket queuedPacket = packet.copy();
byte[] queuedFrame = rawAx25 == null ? null : rawAx25.clone();
executor.execute(() -> controller.handle(queuedPacket, source, frequencyHz, queuedFrame));
```

The controller's ingress copy happens when `handle()` executes, not when the app
queues the call. Do not modify the queued snapshots while awaiting execution.

Callbacks and repository methods run on the calling thread while the controller
monitor is held. Keep them short; do not wait for another thread to enter the
controller or re-enter state-changing operations from a callback. Queue follow-up
work to run after the callback returns (not through an inline/direct executor).
Callbacks returning a `Transmission` must still return the actual result
synchronously; posting work and reporting success early changes their contract.

The lock protects one controller only. Separate controllers and external writers
sharing a repository still require repository-level coordination. The app remains
responsible for invoking `tick(nowMs)`; the controller creates no timer.

## Event and packet models

`AprsEvent` is immutable: all instance fields are private and final. Its no-arg
and copy constructors and setters are removed. Create events with `builder()`;
derive replacements with `toBuilder()` and retain the returned value:

```java
AprsEvent event = AprsEvent.builder()
    .fromCallsign("VK3ME").body("hello").build();
event = event.toBuilder().deliveryState(AprsEvent.DELIVERY_DELIVERED).build();
long id = event.getId();
boolean internetOnly = event.isInternetOnly();
```

Boolean getters use `is`, including `isDigipeated()`. Update persistence mappers
to build events instead of populating them with setters. Unspecified builder
fields retain Java defaults (zero, false, or null). `toBuilder()` preserves every
field.

`AprsPacket` is also immutable: its no-arg and copy constructors and setters are
removed. Create it with `builder()` and derive a replacement with `toBuilder()`:

```java
AprsPacket packet = AprsPacket.builder()
    .source(AprsSource.RX_RF).rawAx25(frame).build();
packet = packet.toBuilder().eventId(eventId).build();
```

`rawAx25` is copied when a packet is built and whenever `getRawAx25()` is called.
Do not expect a repository to assign an ID by mutating its insert argument; store a
replacement built with the assigned ID.

## Parser ownership

`APRSData` no longer implements `Comparable`; hash-based `compareTo()` methods
were removed from it and its subclasses. Supply an explicit semantic comparator
if your application sorts parser fields or stores them in sorted collections.

Passing `null` for an `APRSPacket` digipeater list now means an empty path, not
`TCPIP*`. Supply that Internet path explicitly where appropriate. Parsing no longer
writes diagnostics to standard error; inspect fault flags/comments or handle the
reported exception. The controller's `Raw: ...` fallback decodes bytes as
ISO-8859-1, consistent with the parser's byte-preserving wire representation.

`APRSPacket.getPayload()`, `ThirdPartyField.getInnerPacket()`, position getters,
and extension getters return independent copies. Digipeater lists, field maps,
and type sets are unmodifiable snapshots; their mutable elements are also copied.
Constructors, setters, and `addAprsData()` copy mutable inputs.

Replace `packet.getDigipeaters().add(digi)` with `packet.addDigipeater(digi)`.
To replace or reorder a complete path, construct a new packet with that path.
To edit parsed position metadata, explicitly commit each updated snapshot:

```java
InformationField payload = packet.getPayload();
PositionField field = (PositionField) payload.getAprsData(APRSTypes.T_POSITION);
Position position = field.getPosition();
position.setAltitude(100);
field.setPosition(position);
payload.addAprsData(APRSTypes.T_POSITION, field);
packet.setPayload(payload);
```

`setPayload()` requires the original wire bytes to match; it updates parsed
metadata, not encoded AX.25 bytes. Build a new packet when changing on-air content.
`copy()` preserves parsed state, packet timestamps, and original input text.

Concrete APRS data fields, data extensions, message/unsupported information
fields, and `APRSPacket` are now final. Use composition instead of subclassing
those classes. Custom `APRSData` and `DataExtension` implementations must implement
`copy()`; custom `InformationField` subclasses must override it to preserve their
state and independently copy mutable children.

## Repository contract

Replace the separate `PacketRepository` and `EventRepository` constructor arguments
with one `AprsRepository` implementation. The controller shares immutable events and
packets at this boundary. Implement `update()` to replace/persist the supplied event by ID;
do not rely on edits to previously returned objects becoming visible by identity.
`insert()` must return the assigned ID. If the repository stores events in memory,
store `event.toBuilder().id(assignedId).build()` rather than changing the input.
Existing event references remain unchanged after updates; reload by ID for the
latest state. On its first `tick()`, the controller loads pending reliable events
once and keeps its own retry cache current as it inserts or updates events. Do not
modify pending reliable events through another controller or direct repository
access while that controller is running. `inTransaction(...)` is an optional boundary for
repositories that can atomically group related event and packet writes; its default
implementation simply runs the supplied operation. It cannot roll back writes completed before
an exception: a packet insert may remain if its related event update fails. Do not blindly retry
a controller call after such a failure, because it may insert another packet or event; reconcile
storage first or implement atomic transactions.

For repository-local materialized state, such as a database feed projection, override
`onEventPersisted(AprsEvent)`. The controller invokes it inside `inTransaction(...)` after the
event has its final ID/state and related packet writes are complete. Use it only for database
work that must commit atomically with the event. Do not submit transport work, update UI, or
re-enter the controller from this hook; an exception rolls back the repository transaction.

## Callback and packet ownership

`handle()` snapshots the input packet and raw AX.25 bytes before invoking any
consumer callback. Do not mutate inputs concurrently while they are being
copied. Digipeater callbacks receive detached packet snapshots, so edits cannot
change the controller's echo-suppression key.

`Transmission` now snapshots both constructor inputs. Replace direct access to
`transmission.packet` and `transmission.rawAx25` with `getPacket()` and
`getRawAx25()`; these return fresh defensive copies. Construct a new transmission
to report different data. `frequencyHz` remains a public immutable value.
Incoming-message and retry callbacks receive immutable `AprsEvent` values;
`toBuilder()` creates a replacement without changing controller state.

## Application-owned policy

Supply the APRS-IS identity explicitly: `new AprsIsClient("MyApp", "1.0")`.
The client no longer advertises KV4P HT automatically. Software name and version
are normalized to single tokens before transmission.

The APRS-IS nearby receive filter defaults to a 50 km radius and refreshes its
center after 5 km of movement. Configure other values before enabling the client:
`setNearbyFilterRadiusKm(radiusKm)` and
`setFilterMovementThresholdKm(thresholdKm)`. Both require positive finite values;
changing the radius reconnects an active receive session.

Construct RF packets with an explicit destination:
`new APRSPacket(source, tocall, path, payload)`. The implicit KV4P constructor
and `KV4P_HT_VENDOR_TOCALL` constant are removed; KV4P consumers must supply
`"APKVPA"` themselves. `APRSIconType` is removed; keep UI symbol/icon mappings
in the consuming application.

Replace `Callbacks.showNotification(title, message)` with
`Callbacks.onIncomingMessage(AprsEvent event, boolean forLocal)`. This receives an immutable
value for each new incoming message, not duplicate receptions. `forLocal` identifies messages
addressed to the configured local callsign; the app chooses notification text and presentation.

## Controller-owned RF protocol operations

`Callbacks.getCallsign()`, `sendAcknowledgement(...)`, `retryMessage(...)`, and
`transmitDigipeatedPacket(...)` are removed. Configure identity and the envelope for
controller-generated RF packets with `setCallsign(...)`, `setTxDestination(...)`, and
`setTxPath(...)`. A blank callsign or missing destination disables automatic ACK generation;
the library never supplies a vendor tocall default.

Implement `submitRf(APRSPacket)` instead. It receives the concrete ACK, retry, or digipeated
packet selected by the controller and returns a `Transmission` only when the local TNC/radio
accepted it. It must not infer the packet's protocol purpose. A successful result is not RF
delivery; matching APRS ACK packets still establish reliable-message delivery.

Replace `gateToAprsIs(tnc2, eventId)` with `submitAprsIs(tnc2, onSuccess)`. Queue the supplied
line, then run `onSuccess` only after APRS-IS socket submission succeeds. Do not record it merely
because it was accepted into an application queue.

Enable controller scheduling with
`setPositionBeaconingEnabled(true, nowMs, intervalMs)`, using a positive interval.
The first tick requests a beacon immediately. Disable with
`setPositionBeaconingEnabled(false, nowMs, 0)`; scheduling is disabled initially.
Alternatively, schedule beacons entirely in the app and record transmissions
with `recordPositionBeacon(...)`.

## Verification

Run `mvn clean verify -Dbasepom.javadoc.skip=false`, then `mvn install` to update
Maven Local before rebuilding the consuming app. Verification includes tests,
Checkstyle, SpotBugs, license checks, and Javadoc generation. The build disables
Basepom's inherited SpotBugs exclusion list as well as the project-specific filter.

In the app, verify duplicate receptions, acknowledgements/rejections, retries,
beacon cadence, digipeater echo suppression, and RF-to-APRS-IS forwarding. Confirm
repository updates replace events by ID, UI work is dispatched to the appropriate
thread, and callback follow-up calls do not block or re-enter the controller.
