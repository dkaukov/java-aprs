# java-aprs

[![Java CI with Maven](https://github.com/dkaukov/java-aprs/actions/workflows/maven.yml/badge.svg)](https://github.com/dkaukov/java-aprs/actions/workflows/maven.yml)

`java-aprs` contains the platform-independent APRS protocol, controller, domain model, and
APRS-IS client used by KV4P HT. It builds as a standalone Maven library. Android persistence,
UI feed projection, location, radio transport, and notifications remain in the KV4P app.

Run `mvn clean install` to install `io.github.dkaukov:java-aprs:0.1.0-SNAPSHOT` in Maven Local
before building the Android app.

The parser under `src/main/java/io/github/dkaukov/aprs/parser` is derived from
javAPRSlib/java-aprs-fap. It is vendored intentionally and includes local KV4P changes for
status packets, station capabilities, third-party packets, and the controller/iGate flow.
Keep its existing license headers when modifying it.

Run `mvn clean verify` for tests and static analysis. SpotBugs findings fail the
build, with no exclusion filters in the verification build. Parser APIs return
deep snapshots; persistence shares immutable events and copies mutable packets. Create
events with `AprsEvent.builder()` and derive replacements with `event.toBuilder()`. See
[MIGRATING.md](MIGRATING.md) for Android integration changes. Raw APRS
payload conversions use ISO-8859-1 to preserve wire bytes; AX.25 callsigns and
numeric protocol fields use ASCII.

Run `mvn license:format` to insert missing headers and `mvn license:check` to
validate them. Existing KV4P and vendored parser attribution must be retained.

## Release preparation

Build with JDK 17 and Maven 3.9+; the library targets the Java 8 runtime. See
[RELEASING.md](RELEASING.md) for the `0.1.0` release procedure, signing, and
Sonatype Central configuration, following the `esp32-flash` release setup.
