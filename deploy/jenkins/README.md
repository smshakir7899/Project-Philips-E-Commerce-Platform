# Jenkins setup for the `Jenkinsfile` pipeline

The pipeline in the repository root `Jenkinsfile` expects the plugin set, tool names, credential IDs
and environment variables below. **Never commit real credential values** - they live only in the
Jenkins credentials store (encrypted in `JENKINS_HOME`).

## 1. Start Jenkins locally (compose profile `ci`)

```bash
docker compose --profile ci up -d jenkins sonarqube
# Jenkins UI:   http://localhost:8070   (container listens on 8080; host port 8070 avoids the api-gateway)
# SonarQube UI: http://localhost:9002   (default login admin/admin - you are forced to change it)

# First-run unlock password:
docker compose --profile ci exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

The local image (`docker/jenkins/Dockerfile`) pre-installs the plugins from `docker/jenkins/plugins.txt`;
on a stock Jenkins choose "Install suggested plugins", then add the ones in section 2 that are missing.

## 2. Plugins

| Plugin | Used for |
|---|---|
| Pipeline (workflow-aggregator) | Declarative `pipeline { }` syntax |
| Git | `checkout scm` |
| Docker Pipeline | `docker.build` / registry helpers (the Jenkinsfile mostly uses the `docker` CLI via `sh`) |
| SonarQube Scanner | `withSonarQubeEnv('sonarqube')`, `waitForQualityGate` |
| SSH Agent | `sshagent(['ec2-ssh-key'])` for scp/ssh to EC2 |
| JUnit | `junit '**/target/surefire-reports/*.xml'` test trend |
| JaCoCo | `jacoco(...)` coverage trend |
| Credentials Binding | `withCredentials([...])` |
| Workspace Cleanup | `cleanWs()` in `post { always }` |
| Timestamper | `options { timestamps() }` |
| (optional) Amazon ECR | `docker.withRegistry('https://<ecr-uri>', 'ecr:eu-west-1:aws-creds')` |

## 3. Global Tool Configuration (Manage Jenkins > Tools)

The local Jenkins (compose profile `ci`) is built from `docker/jenkins/Dockerfile`
(`FROM jenkins/jenkins:lts-jdk17`), which bakes in JDK 8, Maven 3.9.9, the Docker CLI + compose plugin
and the plugins from `docker/jenkins/plugins.txt`. Register the tools with **"Install automatically"
unchecked** and these homes:

| Type | Name (must match `tools {}` / `tool '...'` in the Jenkinsfile) | Home in the local image | Purpose |
|---|---|---|---|
| JDK | `jdk8` | `JAVA_HOME=/opt/jdk8` (copied from `eclipse-temurin:8-jdk`) | The build JDK - bytecode is Java 8 |
| JDK | `jdk17` | `JAVA_HOME=/opt/java/openjdk` (the Jenkins image's own JDK) | Only the **runtime** for the SonarQube 10 scanner (it refuses to run on Java 8/11). The analysed bytecode stays Java 8 (`sonar.java.source=8`). |
| Maven | `maven-3.9` | `MAVEN_HOME=/opt/maven-3.9` | Maven 3.9.9 (the pipeline mostly uses `./mvnw`, which pins 3.9.9 anyway) |

On another Jenkins (no custom image) use the same names with the "Install automatically" installers
(Adoptium Temurin 8 / 17, Maven 3.9.9 from Apache).

## 4. Credentials (Manage Jenkins > Credentials > System > Global)

| ID | Kind | Content | Used by stage |
|---|---|---|---|
| `docker-registry-credentials` | Username with password | Registry user + password/token. For ECR: user `AWS`, password = output of `aws ecr get-login-password` (expires after 12 h - prefer the ECR login alternative below). | Docker Push |
| `ec2-ssh-key` | SSH Username with private key | Username `ec2-user` + the private key of the EC2 key pair | Deploy to EC2 |
| `ec2-prod-env` | Secret file | The production `.env` (template: `deploy/ec2/env.prod.example`) | Deploy to EC2 |
| `sonarqube-token` | Secret text | SonarQube user token (see `docker/sonarqube/README-quality-gate.md`) | SonarQube Analysis (via the server config) |

### Environment variables (Manage Jenkins > System > Global properties, or job-level)

| Variable | Example | Meaning |
|---|---|---|
| `DOCKER_REGISTRY` | `123456789012.dkr.ecr.eu-west-1.amazonaws.com` | Registry host; images are `${DOCKER_REGISTRY}/philips-ecommerce/<module>:${IMAGE_TAG}` |
| `EC2_HOST` | `ec2-203-0-113-25.eu-west-1.compute.amazonaws.com` | Target for scp/ssh and the smoke health check |
| `DOCKER_PLATFORM` | `linux/amd64` (optional) | Target CPU architecture for `docker build`. If Jenkins runs on Apple Silicon (arm64) and EC2 is x86_64 (t3.*), leave it unset and the containers fail with `exec format error`. Use `linux/arm64` for Graviton (t4g.*) |

### ECR login alternative (no long-lived registry password)

Give the Jenkins agent an IAM role (EC2 instance profile / ECS task role) with ECR push permissions and
log in right before pushing:

```bash
aws ecr get-login-password --region eu-west-1 \
  | docker login --username AWS --password-stdin "$DOCKER_REGISTRY"
```

or install the **Amazon ECR** plugin and use credentials of kind "AWS Credentials". On the EC2
target, `deploy/ec2/deploy.sh` does the same login with the instance profile
(`AmazonEC2ContainerRegistryReadOnly`).

## 5. SonarQube server + webhook

1. SonarQube (http://localhost:9002): *My Account > Security > Generate token* (type "Global analysis"
   or "Project analysis"). Store it as `sonarqube-token` in Jenkins.
2. Jenkins: *Manage Jenkins > System > SonarQube servers* > Add:
   - Name: **`sonarqube`** (must match `withSonarQubeEnv('sonarqube')`)
   - Server URL: `http://sonarqube:9000` (container-to-container on the compose network; from the host it is `http://localhost:9002`)
   - Server authentication token: `sonarqube-token`
3. SonarQube: *Administration > Configuration > Webhooks* > Create:
   - URL: `http://jenkins:8080/sonarqube-webhook/` (Jenkins listens on 8080 **inside** the network; 8070 is only the host mapping)
   - Without this webhook `waitForQualityGate` never receives the result and the Quality Gate stage times out.

## 6. Agent requirements

| Stage | Needs |
|---|---|
| Checkout, Build, Unit Tests, Package | JDK 8 + network access to Maven Central - **any agent** |
| Integration Tests | Docker daemon reachable (Testcontainers starts MySQL/Kafka/Redis) |
| SonarQube Analysis, Quality Gate | JDK 17 tool, SonarQube server + webhook |
| Docker Build | Docker CLI + daemon |
| Docker Push | Docker + `docker-registry-credentials` (or ECR role) + `DOCKER_REGISTRY` |
| Deploy to EC2 | `ssh`/`scp` + `ec2-ssh-key`, `ec2-prod-env`, `EC2_HOST`; SG allows 22 from the agent |

**Docker socket mount** (`/var/run/docker.sock` into the Jenkins container, done by the `ci` compose
profile with `group_add: "${DOCKER_GID:-0}"` so the `jenkins` user may use it; Docker Desktop's socket
is `root:root 660`, so the default GID 0 works there; on Linux set
`DOCKER_GID=$(stat -c %g /var/run/docker.sock)` in `.env`): a **local-only shortcut**. Anyone who can run a job can control the host's Docker daemon,
which is equivalent to **root on the host** (mount `/` into a container, etc.). For shared/real Jenkins
use dedicated build agents (VMs, or ephemeral Kubernetes/ECS agents), rootless Docker, or
daemonless builders (Kaniko/Buildah) instead. The stock `jenkins/jenkins:lts-jdk17` image has no
Docker CLI - that is why the local setup uses the derived `docker/jenkins/Dockerfile` (CLI copied from `docker:cli`).

## 7. Create the pipeline job

1. *New Item* > name `philips-ecommerce` > **Pipeline** (or Multibranch Pipeline for PR builds).
2. *Pipeline* > Definition: **Pipeline script from SCM** > Git > repository URL (+ credentials if private) > branch `*/main`.
3. Script Path: `Jenkinsfile`.
4. Save > *Build with Parameters*. First run: leave `RUN_SONAR`, `PUSH_IMAGES`, `DEPLOY_TO_EC2` off
   to validate the plain Maven stages, then enable them one by one as the credentials above exist.

The first build after creation may show no parameters (Jenkins learns them from the Jenkinsfile on
the first run) - just run it once.
