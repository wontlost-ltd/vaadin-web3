# Releasing

Four artifacts are published to Maven Central: `com.wontlost:vaadin-web3`,
`com.wontlost:vaadin-web3-walletconnect`, `com.wontlost:vaadin-web3-server`, and
`com.wontlost:vaadin-web3-onramp`. The parent POM and the demo are never
deployed.

## Prerequisites

- A Central Portal token in `~/.m2/settings.xml` under the server id `central`.
- A GPG key that can sign. Its public key must be on a public keyserver.

## Checklist

1. Make sure `main` is green in CI.
2. Check the release build locally. This step neither signs nor deploys
   anything:

   ```bash
   mvn -B -Prelease -Dgpg.skip verify -pl addon,walletconnect,server,onramp -am
   ```

3. Set the version, move the `Unreleased` heading in `CHANGELOG.md` to today's
   date, and commit:

   ```bash
   mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
   ```

4. Deploy the four published modules:

   ```bash
   mvn -B -Prelease deploy -pl addon,walletconnect,server,onramp -am
   ```

5. Tag the release and push the tag (`git tag v1.0.0 && git push origin v1.0.0`),
   then create a GitHub release from the CHANGELOG section.
6. Build the Vaadin Directory zip and upload it with the text from
   [`vaadin-directory.md`](vaadin-directory.md):

   ```bash
   mvn clean package -Pdirectory -pl addon,walletconnect -am -DskipTests
   ```

7. Move to the next development version, for example
   `mvn versions:set -DnewVersion=1.1.0-SNAPSHOT`.

Each component module produces a Directory zip. The server module's
documentation lives in the README.
