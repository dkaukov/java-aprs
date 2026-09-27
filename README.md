# java-aprs

[![Java CI with Maven](https://github.com/dkaukov/java-aprs/actions/workflows/maven.yml/badge.svg)](https://github.com/dkaukov/java-aprs/actions/workflows/maven.yml)

`java-aprs` is a platform-independent Java 8 APRS library. It parses APRS/TNC2 and
AX.25 UI frames, builds AX.25 frames, aggregates packet observations into events, and
provides controller and APRS-IS integration points. It does not require Android or an
Android SDK.

## Features

- TNC2 APRS and AX.25 UI-frame parsing and AX.25 frame generation
- Positions, messages, ACK/REJ, weather, objects, status, station capabilities, and
  third-party packets
- Physical RX/TX packet history and logical event aggregation
- Reliable-message retries, fill-in digipeating, and RF-to-APRS-IS iGate filtering
- APRS-IS receive/transmit client with nearby filtering and bounded outbound queue

## Requirements

The library runs on Java 8 or newer. Builds and releases currently use JDK 17 and
Maven 3.9+. Android consumers with minSdk 26 do not need Java 9+ collection
desugaring specifically for this library.

## Installation

After `0.2.0` is released, use the Central artifact:

```xml
<dependency>
    <groupId>io.github.dkaukov</groupId>
    <artifactId>java-aprs</artifactId>
    <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("io.github.dkaukov:java-aprs:0.2.0")
```

For snapshot development, run `mvn clean install` and depend on
`io.github.dkaukov:java-aprs:0.2.0-SNAPSHOT` from Maven Local. Snapshots are not
published to Maven Central.

## Parse APRS packets

Use `Parser.parse(...)` for a TNC2 line:

```java
APRSPacket packet = Parser.parse(
    "VK3ABC>APRS,WIDE1-1:!3751.65S/14458.20E-Test");
```

Use `Parser.parseAX25(...)` for an AX.25 UI frame. Input and output exclude HDLC
flags, FCS, and KISS transport framing:

```java
APRSPacket packet = Parser.parseAX25(frame);
byte[] encoded = packet.toAX25Frame();
```

Where text bridges APRS wire bytes, the parser uses ISO-8859-1 semantics to preserve
every byte.

## Use `AprsController`

The controller turns physical observations into logical occurrences:

```text
physical packet(s) -> AprsController -> AprsEvent
```

`AprsPacket` records one RX/TX observation. `AprsEvent` is the logical occurrence
and can aggregate multiple packets: a direct position and its digipeated copy become
two packet records associated with one event.

```java
AprsController controller = new AprsController(repository, callbacks);

APRSPacket packet = Parser.parseAX25(frame);
controller.handle(packet, AprsSource.RX_RF, 144_390_000L, frame);
```

### Persistence and callbacks

Applications supply `AprsRepository` for storage and `AprsController.Callbacks` for
radio and application integration. Both `AprsEvent` and `AprsPacket` are immutable;
`insert(...)` returns the assigned persistent ID, and `update(event)` replaces the
event with that ID. On its first `tick()`, a controller loads pending reliable events
to rebuild retry state. Do not rewrite pending reliable-message state behind a running
controller. Repository calls are synchronous, so implementations may use an in-memory
store, JDBC, Room, or another persistence layer. `inTransaction(...)` defaults to a direct
call for simple stores; override it to atomically persist related event and packet changes.

### Repository failures

The default transaction boundary cannot roll back a write that already succeeded. For example,
if a packet insert succeeds and the related event update fails, the packet remains stored while
the event retains its previous count/state. A transactional repository must roll back both
writes. Do not blindly retry a controller call after a repository failure: it can create another
packet or event. Reconcile persisted records first, or use an atomic `inTransaction(...)`
implementation.

Configure protocol identity on the controller, then provide transport-only callbacks:

```java
controller.setCallsign("VK3ME-9");
controller.setTxDestination("APKVPA"); // application-specific example, never a library default
controller.setTxPath(Collections.singletonList(new Digipeater("WIDE1-1")));
```

The controller constructs ACKs, retries, digipeated frames, and RF-to-APRS-IS qAO lines.
`Callbacks.submitRf(packet, frequencyHz)` receives the concrete packet selected for submission
and an optional RF frequency request. Retries use the initial persisted TX frequency; transport
implementations may tune to it, decline the request, or ignore it. Return a `Transmission` only
when the TNC/radio accepted the frame. Return an `AprsController.RfTransmission` without a transmission and
with `retryAllowed=false` to cancel a reliable-message retry; otherwise a refused retry remains
scheduled. This records submission, not on-air transmission or peer receipt. For reliable
messages, only a matching APRS ACK establishes delivery.
`submitAprsIs(line, onSuccess)` must invoke `onSuccess` only after successful socket submission
so the controller can record TX_APRS_IS history.

### Reliable messages and `tick()`

The controller creates no scheduler thread. Call it periodically from the application:

```java
controller.tick(System.currentTimeMillis());
```

`tick()` sends controller-scheduled ACKs one second after a numbered local RF message arrives,
drives reliable-message retries and final failure handling, and runs optional controller-managed
beacon cadence. Reliable messages retry after 15, 30, 60, 120, and
240 seconds, followed by a 30-second final ACK grace period. Applications may instead
schedule position beacons themselves and call `submitPositionBeacon(beacon)`. It builds the
packet from `BeaconData`, submits it through `submitRf`, and records accepted submissions. Its
boolean result means the local transport accepted the beacon, not that it was transmitted on air.
Use `recordPositionBeacon(...)` only when the application has already submitted its own packet.

For a messaging-capable compressed position beacon, configure the content explicitly:

```java
BeaconData beacon = BeaconData.builder()
    .latitude(-37.8608)
    .longitude(144.9700)
    .messagingCapable(true)
    .compressed(true)
    .build();
```

The default remains an uncompressed non-messaging position (`!`). Compressed beacons cannot use
the uncompressed course/speed or altitude extensions.

### Digipeating and iGate

```java
controller.setDigipeatingEnabled(true);
controller.setIgateEnabled(true);
```

Digipeating handles fill-in `WIDE1-1` and explicit local-address cases with built-in
duplicate/echo suppression. iGate forwarding is one-way RF to APRS-IS; APRS-IS traffic
is never automatically sent to RF. The controller filters NOGATE, RFONLY, Internet,
q-construct, and query traffic before requesting a gate.

## APRS-IS

`AprsIsClient` owns a connection worker and can deliver received TNC2 lines through
the controller:

```java
try (AprsIsClient aprsIs =
        new AprsIsClient("MyApp", "1.0", controller::handleAprsIsPacket)) {
    aprsIs.setCallsign("VK3ABC-9");
    aprsIs.setServer(AprsIsClient.DEFAULT_SERVER);
    aprsIs.setNearbyFilterRadiusKm(50);
    aprsIs.setFilterMovementThresholdKm(5);
    aprsIs.setFilterLocation(-37.8, 145.0);
    aprsIs.setReceiveEnabled(true);
    aprsIs.setTransmitEnabled(false);
    aprsIs.setEnabled(true);
    // Keep the client open while the application runs.
}
```

The default server is `rotate.aprs2.net:14580`. Receive-only sessions log in with
`pass -1`; transmitting requires APRS-IS passcode verification. Nearby filters default
to a 50 km radius and a 5 km movement threshold. Without a location the client requests
`m/<radius>`; with one it requests `r/lat/lon/radius`. Connection-affecting changes
reconnect as required. `close()` stops the worker, and the intentionally bounded outbound
queue rejects packets when full.

## Threading and ownership

`AprsController` creates no threads. Its state-changing methods are synchronous and
serialized per controller instance; repository calls and callbacks run on the caller's
thread while that serialization is held. Callbacks must not block waiting for another
thread to enter the same controller. Use an application-owned ordered executor or thread
for background processing; producer arrival order is the application's responsibility.

`AprsEvent` and `AprsPacket` are immutable, and `AprsPacket.rawAx25` is defensively
copied. Parser APIs return detached/deep snapshots where documented. `Transmission`
also snapshots its parser packet and raw-frame inputs.

## Android integration

Keep all controller calls on one application-owned worker. Dispatch only UI projection to the
main thread; do not move controller calls or its synchronous callbacks there. This abbreviated
adapter uses application-defined `tnc` and `viewModel` objects:

```java
ExecutorService aprsWorker = Executors.newSingleThreadExecutor();
Handler mainHandler = new Handler(Looper.getMainLooper());

// Radio ingress may come from any thread. Snapshot first, then preserve arrival order.
void onAx25Frame(byte[] frame) {
    byte[] snapshot = frame.clone();
    aprsWorker.execute(() -> {
        try {
            APRSPacket packet = Parser.parseAX25(snapshot);
            controller.handle(packet, AprsSource.RX_RF, 144_390_000L, snapshot);
        } catch (Exception e) {
            Log.w("Aprs", "Ignoring malformed AX.25 frame", e);
        }
    });
}

abstract class AndroidCallbacks implements AprsController.Callbacks {
    @Override public void onIncomingMessage(AprsEvent event, boolean forLocal) {
        mainHandler.post(() -> viewModel.onIncomingMessage(event, forLocal));
    }

    @Override public AprsController.RfTransmission submitRf(APRSPacket packet, Long frequencyHz) {
        return submitToTnc(packet, frequencyHz);
    }

    private AprsController.RfTransmission submitToTnc(APRSPacket packet, Long requestedFrequencyHz) {
        byte[] frame = packet.toAX25Frame();
        long frequencyHz = requestedFrequencyHz == null ? 144_390_000L : requestedFrequencyHz;
        return tnc.writeAx25(frame, frequencyHz)
            ? AprsController.RfTransmission.builder()
                .transmission(new AprsController.Transmission(packet, frequencyHz, frame)).build()
            : AprsController.RfTransmission.builder().build();
    }

    // Implement submitAprsIs(...) and getBeaconData(); protocol packet selection
    // remains in AprsController and transport work runs synchronously here.
}
```

`tnc.writeAx25(...)` is an application method: it should return only after the local TNC/radio
transport accepts or rejects the frame. Do not enqueue work back onto `aprsWorker` from a
transmission callback. A successful `Transmission` does not confirm on-air RF transmission or
delivery. Schedule `controller.tick(now)` by submitting it to the same worker.

## Scope

This library does not provide an audio modem/AFSK implementation, KISS transport,
hardware radio control, Android UI, or a database implementation.

## Parser provenance and license

The parser under `src/main/java/io/github/dkaukov/aprs/parser` is derived from
javAPRSlib/java-aprs-fap and is vendored intentionally with local changes. Preserve its
existing license and attribution headers when modifying it.

## Building and contributing

Run `mvn -B -ntp clean verify -Dbasepom.javadoc.skip=false` for tests, Javadocs, and
static analysis. Run `mvn license:format` to insert missing headers and
`mvn license:check` to validate them. See [MIGRATING.md](MIGRATING.md) for migration
from the pre-0.1 snapshot API and [RELEASING.md](RELEASING.md) for the release process.
