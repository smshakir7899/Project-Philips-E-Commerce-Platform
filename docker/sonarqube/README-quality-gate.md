# SonarQube: quality gate and token (local learning setup)

SonarQube runs in the `ci` compose profile:

```bash
docker compose --profile ci up -d sonarqube      # http://localhost:9002 (inside philips-net: http://sonarqube:9000)
```

First login is `admin` / `admin`; SonarQube forces a password change. That password is yours, keep it out of the repo.

## Recommended quality gate "Philips Way"

Use *Quality Gates > Create* and add these conditions on **New Code**. They follow the "Clean as You Code" approach: old code doesn't block a build, every change must be clean.

| Metric (New Code) | Operator | Value | Why |
|---|---|---|---|
| Coverage | is less than | **70 %** | New logic must come with tests (JaCoCo XML from `target/site/jacoco/jacoco.xml`) |
| Duplicated Lines (%) | is greater than | **3 %** | Copy-paste between services belongs in `common-lib` |
| Blocker Issues | is greater than | **0** | No new blocker bugs or vulnerabilities |
| Critical Issues | is greater than | **0** | No new critical issues |
| Security Hotspots Reviewed | is less than | **100 %** | Every hotspot gets a human decision |
| Reliability Rating | is worse than | **A** | (optional, stricter) |

Then *Set as Default* (or assign it to the `philips-ecommerce` project only).

Define **New Code** under *Project Settings > New Code*. Use "Previous version" (driven by `sonar.projectVersion`, which the Jenkinsfile sets to the image tag) or "Reference branch = main" if you use branches.

Coverage is computed only on code that isn't excluded. The pom's `sonar.coverage.exclusions` leave out `*Application`, `config`, `dto`, `entity`, `event` and plain exception classes, so the 70 % applies to services, controllers, mappers, Kafka listeners and outbox code.

## Creating a token (never committed)

1. *My Account > Security > Generate Tokens*. Pick type **Project Analysis Token** for project `philips-ecommerce` (or **Global Analysis Token** for several projects) and set an expiry date.
2. Copy it once; SonarQube won't show it again.
3. Store it only where it is needed:
   * **Jenkins**: *Manage Jenkins > Credentials*, kind *Secret text*, ID `sonarqube-token`. Then go to *Manage Jenkins > System > SonarQube servers*, set Name `sonarqube`, URL `http://sonarqube:9000` and the token credential. `withSonarQubeEnv('sonarqube')` in the Jenkinsfile injects it.
   * **Your shell** (manual run): `export SONAR_TOKEN=...` (not in `.env`, not in any file under git), then:

     ```bash
     ./mvnw -B verify sonar:sonar -Dsonar.host.url=http://localhost:9002 -Dsonar.token="$SONAR_TOKEN"
     ```

     The Maven scanner on SonarQube 10 needs Maven to run on **JDK 17**. Point `JAVA_HOME` at a JDK 17 for the `sonar:sonar` invocation. The bytecode stays Java 8 because compilation already happened in `verify`. If you must use JDK 8, run `./mvnw -B verify` first with JDK 8, then `sonar:sonar` alone with JDK 17.

## Quality gate in Jenkins

`waitForQualityGate abortPipeline: true` doesn't poll. It waits for SonarQube to call Jenkins back, so you need to create a webhook under *Administration > Configuration > Webhooks*:

* Name `jenkins`, URL `http://jenkins:8080/sonarqube-webhook/`. Use the compose network name; Jenkins listens on 8080 inside the container, and the host port 8070 isn't reachable from SonarQube's container as `localhost`.

Without the webhook the *Quality Gate* stage hits its 10-minute timeout.
