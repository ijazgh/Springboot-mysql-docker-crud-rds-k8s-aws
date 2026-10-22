# Validation record

Verified on 2026-10-21 local time using Corretto 26.0.2.1, Maven 3.9.6 and Docker Desktop's Linux engine.

* `mvn verify`: BUILD SUCCESS, 4 tests, 0 failures, 0 errors.
* Existing JSON/entity persistence test: submitted fields survive an H2 database round trip and generated IDs are present.
* HTTP integration test: POST an order, read it by ID, and find it in the order list.
* Health integration test: liveness and readiness endpoints report UP against the test database.
* Context startup test passes.
* Public AWS RDS CA bundle downloaded and successfully converted to a PKCS12 truststore (114 public CA certificates at verification time).
* Docker image build succeeds with Eclipse Temurin 26 JRE. Image user inspected: `10001:10001`.
* Local Docker Compose configuration validates.

Tests use H2, not an RDS instance. The Docker image was built, but no cloud rollout or live RDS authentication/network/restore test was performed. These checks remain in the walkthrough. No AWS resource or GitHub repository was created, changed or deleted for this project.

The generated public truststore and build outputs are ignored by Git. The workflow regenerates the truststore and jar before publishing. Certificate authorities change over time; regenerate rather than relying on an old downloaded bundle indefinitely.
