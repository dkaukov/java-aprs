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

After `0.1.0` is released, use the Central artifact:

```xml
<dependency>
    <groupId>io.github.dkaukov</groupId>
    <artifactId>java-aprs</artifactId>
    <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.dkaukov:java-aprs:0.1.0")
```

For snapshot development, run `mvn clean install` and depend on
`io.github.dkaukov:java-aprs:0.1.0-SNAPSHOT` from Maven Local. Snapshots are not
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

Callbacks describe operations the application performs: obtaining the local callsign,
handling a newly created addressed message, transmitting ACKs/retries/digipeats, making
position beacons, and forwarding an accepted iGate line. A retry or digipeat callback
returns a `Transmission` only when it actually transmitted; `null` means no
transmission occurred.

### Reliable messages and `tick()`

The controller creates no scheduler thread. Call it periodically from the application:

```java
controller.tick(System.currentTimeMillis());
```

`tick()` drives reliable-message retries and final failure handling, plus optional
controller-managed beacon cadence. Reliable messages retry after 15, 30, 60, 120, and
240 seconds, followed by a 30-second final ACK grace period. Applications may instead
schedule position beacons themselves and call `recordPositionBeacon(...)` after a real
transmission.

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
