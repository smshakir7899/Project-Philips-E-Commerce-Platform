# Deployment Guide: Docker, AWS (EC2 + S3), Jenkins, SonarQube

> **Status:** the local Docker setup was executed and verified (see the README, "Verification status").
> The AWS, Jenkins and SonarQube steps below are **documented and scripted but were not executed**, because
> they need your AWS account, a Jenkins controller and a SonarQube token. No real credentials exist anywhere
> in this repository.

---

## 1. Local Docker deployment (verified)

```bash
cp .env.example .env            # safe local defaults, never commit .env
./mvnw -B clean package -DskipTests
docker compose build            # 9 images on eclipse-temurin:8-jre
scripts/start.sh                # layered start + health waits (or: docker compose up -d)
scripts/health-check.sh
scripts/smoke-test.sh
scripts/stop.sh                 # add --volumes to wipe MySQL/Kafka/Redis/MinIO data
```

Startup order (enforced by `depends_on.condition`): `mysql` → `mysql-init` (grants, one-shot) and `redis`,
`zookeeper` → `kafka` → `kafka-init` (topics, one-shot), `minio` → `minio-init` (bucket, one-shot) →
`config-server` → `discovery-server` → business services → `api-gateway`.

Memory: about 5.5 GB for the whole stack (each JVM has a 400 MB container limit and `-Xmx256m`). In Docker Desktop,
give the VM at least 6 GB (Settings → Resources).

---

## 2. AWS architecture (target)

```
Internet ──► (optional ALB :443) ──► EC2 t3.large (Amazon Linux 2023, Docker)
                                       ├─ api-gateway :8080   (only port reachable from outside / ALB)
                                       ├─ config, discovery, 6 services, MySQL*, Redis, Kafka, ZooKeeper
                                       └─ IAM instance profile ──► S3 bucket philips-product-images (private)
                                                                └► ECR (pull images)
* For production use Amazon RDS for MySQL, ElastiCache for Redis and Amazon MSK for Kafka.
  The single-host compose setup is a learning and demo deployment.
```

### 2.1 S3 bucket (private, encrypted)

```bash
# Uses an admin/infra AWS CLI profile, NOT the application role
deploy/aws/s3-bucket-setup.sh --bucket philips-product-images --region eu-west-1 \
    --cors-origin https://your-frontend.example --with-lifecycle --create-iam
```

This turns on Block Public Access, SSE-S3 encryption and BucketOwnerEnforced ownership, adds CORS for GET, and
(with `--create-iam`) creates the role `philips-ecommerce-ec2` with:
* the trust policy `deploy/iam/ec2-trust-policy.json` (only `ec2.amazonaws.com` can assume it)
* the inline least-privilege policy `deploy/iam/product-service-s3-policy.json`: `s3:PutObject`, `GetObject` and
  `DeleteObject` on `arn:aws:s3:::philips-product-images/products/*`, plus `ListBucket` restricted to that prefix
* `AmazonEC2ContainerRegistryReadOnly` (pull images from ECR)

Bucket names are global. If you change the name, update both JSON policies and `S3_BUCKET`.

### 2.2 How the application gets AWS credentials (no keys anywhere)

`product-service` builds its `S3Client` with `DefaultCredentialsProvider` (`product-service/.../config/S3Config.java`).
On EC2, the environment variables `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are **not set**, so the
provider chain falls through to the **instance profile** (IMDSv2) and receives short-lived, auto-rotated credentials.
Because the app runs inside a container, one extra network hop away from the instance, raise the IMDSv2 hop limit:

```bash
aws ec2 modify-instance-metadata-options --instance-id i-XXXXXXXX \
    --http-tokens required --http-put-response-hop-limit 2
```

Environment configuration for AWS (in `/opt/philips-ecommerce/.env`, template `deploy/ec2/env.prod.example`):

| Variable | AWS value |
|---|---|
| `S3_ENDPOINT` | *(empty)*: the SDK uses the regional AWS endpoint |
| `S3_PATH_STYLE_ACCESS` | `false` |
| `S3_PRESIGN_ENDPOINT` | *(empty)* |
| `S3_PUBLIC_BASE_URL` | CloudFront URL (with Origin Access Control); otherwise clients use the presigned-URL endpoint |
| `AWS_REGION` | e.g. `eu-west-1` |

### 2.3 Security groups (`deploy/aws/security-groups.md`)

| Port | Source | Why |
|---|---|---|
| 22 | your admin IP /32 only (or use SSM Session Manager and close 22) | SSH for Jenkins deploy |
| 8080 | ALB security group (or your IP for a demo) | API gateway: the **only** public application port |
| 3306, 6379, 9092, 2181, 8761, 8888, 8081-8086 | **never** from 0.0.0.0/0 | internal only; containers talk over the Docker network |
| egress | 443 to the internet / VPC endpoints | ECR, S3, OS updates |

### 2.4 Launch the instance

1. EC2 → Launch instance: Amazon Linux 2023, **t3.large** (8 GB; t3.medium is too small for 9 JVMs + Kafka + MySQL),
   30 GB gp3, the security group above, IAM instance profile `philips-ecommerce-ec2`.
2. User data: paste `deploy/ec2/user-data.sh` (installs Docker, the compose plugin, curl, jq, AWS CLI, and creates
   `/opt/philips-ecommerce`).
3. Push images to ECR. Jenkins does this; the manual equivalent is:
   ```bash
   aws ecr get-login-password --region eu-west-1 | docker login --username AWS --password-stdin <acct>.dkr.ecr.eu-west-1.amazonaws.com
   for m in config-server discovery-server api-gateway product-service inventory-service cart-service order-service payment-service notification-service; do
     aws ecr create-repository --repository-name philips-ecommerce/$m >/dev/null 2>&1 || true
     docker tag philips-ecommerce/$m:local <acct>.dkr.ecr.eu-west-1.amazonaws.com/philips-ecommerce/$m:v1
     docker push <acct>.dkr.ecr.eu-west-1.amazonaws.com/philips-ecommerce/$m:v1
   done
   ```
4. Copy the files to the instance (the layout is documented at the top of `deploy/docker-compose.prod.yml`):
   `docker-compose.prod.yml`, `deploy.sh`, `.env` (from `env.prod.example`, chmod 600), `config-repo/`,
   `docker/mysql/init/`, `docker/mysql/grant-app-user.sh`, `docker/kafka/create-topics.sh`.
5. On the instance: `cd /opt/philips-ecommerce && ./deploy.sh v1`. It logs in to ECR with the instance role, pulls,
   runs `up -d`, waits for health, and records the tag in `.deploy-history`.
6. Rollback: `./deploy.sh <previous-tag>` (see `.deploy-history`).

### 2.5 Troubleshooting on EC2

| Symptom | Check |
|---|---|
| `S3 ... Unable to load credentials` | instance profile attached? IMDSv2 hop limit 2? `curl -s http://169.254.169.254/latest/meta-data/iam/info` (needs an IMDSv2 token) |
| `AccessDenied` on upload | the object key must start with `products/` (the policy is prefix-scoped); bucket name matches the policy ARN |
| Containers restarting / OOMKilled | `docker stats`; use t3.large or larger; lower `JAVA_OPTS -Xmx` |
| Services not in Eureka | `docker compose logs config-server discovery-server`; services start only after both are healthy |
| Gateway 503 `<SERVICE>_UNAVAILABLE` | the target is not registered yet or is down: `curl localhost:8761/eureka/apps -H 'Accept: application/json'` |

---

## 3. Jenkins CI/CD

Pipeline file: `Jenkinsfile` (declarative). Its header table lists which stages run anywhere and which need credentials.

| Stage | Runs on a plain agent? | Needs |
|---|---|---|
| Checkout, Build, Unit Tests, Package | yes | JDK tool `jdk8` |
| Integration Tests (`RUN_INTEGRATION_TESTS`) | needs Docker on the agent | Testcontainers |
| SonarQube Analysis + Quality Gate (`RUN_SONAR`) | no | SonarQube server named `sonarqube`, token, JDK tool `jdk17`, webhook for the quality gate |
| Docker Build | needs Docker | |
| Docker Push (`PUSH_IMAGES`) | no | credential `docker-registry-credentials`, env `DOCKER_REGISTRY` |
| Deploy to EC2 (`DEPLOY_TO_EC2`) | no | credentials `ec2-ssh-key` (SSH) and `ec2-prod-env` (secret file), env `EC2_HOST` |

Setup steps (details in `deploy/jenkins/README.md`):
1. Start a local Jenkins with `docker compose --profile ci up -d jenkins` → http://localhost:8070 (the image from
   `docker/jenkins/Dockerfile` has the plugins from `plugins.txt` preinstalled). The compose file mounts the Docker
   socket; that is a **local-only shortcut**, because it gives Jenkins root-equivalent access to the host.
2. Manage Jenkins → Tools: JDK `jdk8`, JDK `jdk17`, Maven `maven-3.9` (names must match).
3. Credentials: add the IDs above. Never put secrets in the Jenkinsfile.
4. New item → Pipeline from SCM → this repository → script path `Jenkinsfile`.

## 4. SonarQube

```bash
docker compose --profile ci up -d sonarqube        # http://localhost:9002 (admin/admin, change on first login)
# My Account → Security → generate a token (never commit it)
./mvnw -B clean verify                              # produces target/site/jacoco/jacoco.xml per module
# Run the scanner on JDK 17 (SonarQube 10 requirement). The code itself stays Java 8 (sonar.java.source=8):
JAVA_HOME=/path/to/jdk17 ./mvnw -B sonar:sonar -Dsonar.host.url=http://localhost:9002 -Dsonar.token=<token>
```

* Configuration: `pom.xml` (`sonar.projectKey`, `sonar.exclusions`, `sonar.coverage.exclusions`, JaCoCo XML path) or
  `sonar-project.properties` for the stand-alone `sonar-scanner` CLI.
* Exclusions: application classes, `config`, `dto`, `entity` and `event` packages are excluded from coverage
  (plain data or wiring). Business logic (services, handlers, controllers) is measured.
* Recommended quality gate: `docker/sonarqube/README-quality-gate.md` (new-code coverage ≥ 70%, no new
  blocker/critical issues, duplication < 3%).
* Not executed in this environment: no SonarQube server was started here.
