# Repository Guidelines

## Project Structure & Module Organization

`java-aprs` is a standalone Maven library for KV4P HT. Production code lives in
`src/main/java/io/github/dkaukov/aprs/`: the controller, domain models, and APRS-IS
client. The `parser/` subpackage contains the intentionally vendored
javAPRSlib/java-aprs-fap parser, including local KV4P changes. Tests mirror the
package structure under `src/test/java/`. `pom.xml` defines the build; generated
output belongs in the ignored `target/` directory.

Keep Android persistence, UI, location, radio transport, and notifications in the
consuming app. Use the controller's repository and callback interfaces for those
integration boundaries.

## Build, Test, and Development Commands

Use Maven with a JDK supporting the Java 8 target.

- `mvn test`: run the JUnit suite.
- `mvn -Dtest=AprsControllerTest test`: run one test class.
- `mvn clean verify`: build the JAR, run tests, and execute configured checks.
- `mvn clean install`: install `io.github.dkaukov:java-aprs:0.1.0-SNAPSHOT`
  into Maven Local before building the Android app.
- `mvn license:format`: insert missing license headers while retaining recognized
  upstream attribution; review the diff before committing.

This project is a library; local development primarily uses tests rather than an
application server.

## Coding Style & Naming Conventions

Keep code Java 8 compatible and UTF-8 encoded. Use four-space indentation in
the main APRS package, `UpperCamelCase` class names, `lowerCamelCase` methods and
fields, and `UPPER_SNAKE_CASE` constants. Preserve surrounding formatting in the
legacy parser, which also uses tabs; avoid unrelated reformatting. Retain all
existing license and attribution headers.

The build inherits Basepom OSS checks. Checkstyle is explicitly skipped.
License and SpotBugs findings fail verification, with no SpotBugs
exclusion filters. Preserve snapshot ownership when changing parser APIs; see
`MIGRATING.md` for consumer integration requirements.

## Testing Guidelines

Use JUnit 4.13.2, `*Test.java` class names, and descriptive behavior-based methods
such as `passcodeUsesBaseCallsign`. Add regression tests for changed parsing,
deduplication, retry, and gating behavior. Follow existing in-memory controller
fixtures and loopback socket tests; keep tests independent of public APRS-IS
servers. No coverage threshold is explicitly declared in this project's POM.

## Commit & Pull Request Guidelines

Git history is unavailable in this checkout, so no established commit convention
can be verified. Use concise imperative subjects, such as `Fix duplicate message
acknowledgements`. Keep changes focused. Pull requests should explain the behavior
change, link relevant issues, and record validation commands and results. Include
representative packet inputs when protocol behavior changes.
