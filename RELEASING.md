# Releasing java-aprs

The release setup follows `dkaukov/esp32-flash`: Basepom OSS 63, Maven Release
Plugin, GPG signatures, and the `basepom.central-release` profile. The planned
release is `0.2.0`; development remains on `0.2.0-SNAPSHOT` until release
preparation. CI verifies changes but does not deploy artifacts.

## Prerequisites

- Use JDK 17 and Maven 3.9 or newer. Artifacts target the Java 8 runtime.
- Have SSH push access to `git@github.com:dkaukov/java-aprs.git`.
- Have a verified `io.github.dkaukov` namespace in Sonatype Central Portal.
- Configure a Central user token in your local Maven settings under server ID
  `central`. The template's environment names can be reused:

  ```xml
  <server>
    <id>central</id>
    <username>${env.CENTRAL_USERNAME}</username>
    <password>${env.CENTRAL_CENTRAL_TOKEN}</password>
  </server>
  ```

- Configure a GPG signing key and agent on the release machine, with its public
  key available for signature verification. Keep credentials and private keys
  outside this repository.

## Validate

Start on an up-to-date, clean `main` branch with passing GitHub Actions checks:

```bash
mvn -B -ntp clean verify -Dbasepom.javadoc.skip=false
mvn -B release:prepare -DdryRun=true -DreleaseVersion=0.2.0 \
  -DdevelopmentVersion=0.2.1-SNAPSHOT -Dtag=java-aprs-0.2.0
mvn release:clean
```

Review the generated release POMs before cleanup. The dry run does not create a
tag or push commits. Preserve existing vendored parser license headers.

## Publish when ready

These commands change versions, push release commits and a tag, and upload signed
artifacts. Run them only when ready to release:

```bash
mvn -B release:prepare -DreleaseVersion=0.2.0 \
  -DdevelopmentVersion=0.2.1-SNAPSHOT -Dtag=java-aprs-0.2.0
mvn -B release:perform
```

Basepom's Central profile generates sources and Javadoc JARs, signs artifacts,
and uploads them to Central Portal. Automatic publication is disabled by default;
review the validated deployment and publish it in the portal. Verify that
`io.github.dkaukov:java-aprs:0.2.0` resolves from Maven Central, then create a GitHub
release for `java-aprs-0.2.0` with release notes. Confirm `main` is at
`0.2.1-SNAPSHOT` before resuming development.
