# Security groups for the single-EC2 deployment

Principle: **only the API gateway is reachable from clients, and only SSH from an admin IP.**
Everything else (databases, brokers, registry, config, individual services) talks over the
private Docker network `philips-net` on the instance and is never published.

## `sg-philips-app` (attached to the EC2 instance)

### Inbound

| Port | Protocol | Source | Why |
|---|---|---|---|
| 22 | TCP | admin IP `/32` only (e.g. `203.0.113.10/32`) | SSH for Jenkins deploy + troubleshooting. Prefer **SSM Session Manager** (no inbound port at all, attach `AmazonSSMManagedInstanceCore`). If Jenkins runs in AWS, allow its security group instead of an IP. |
| 8080 | TCP | `sg-philips-alb` (security-group reference) | API gateway behind an Application Load Balancer. For the learning setup without an ALB: your client IP `/32`, never `0.0.0.0/0` longer than a demo. |

### NEVER open these publicly

| Port(s) | Component | Why it must stay private |
|---|---|---|
| 3306 | MySQL | Brute-force target; data exfiltration |
| 6379 | Redis | No auth by default; known RCE abuse via `CONFIG SET` |
| 2181 | ZooKeeper | Unauthenticated cluster metadata control |
| 9092 / 29092 | Kafka | PLAINTEXT, unauthenticated: anyone could produce fake `order-confirmed` events |
| 8761 | Eureka | Lists every instance/IP; rogue registrations could hijack traffic |
| 8888 | Config server | Serves the full configuration (and `/actuator/env`) |
| 8081-8086 | Business services | Bypass the gateway (rate limiting, routing, CORS); actuator endpoints exposed |
| 9000 / 9001 | MinIO | Local only - not deployed on EC2 (real S3 is used) |
| 8090 / 9002 / 8070 | Kafka UI / SonarQube / Jenkins | Admin UIs with powerful actions |

In `deploy/docker-compose.prod.yml` these ports are not published at all (Eureka is bound to
`127.0.0.1` only - use `ssh -L 8761:localhost:8761 ec2-user@<host>`). The security group is the
second layer: even if someone adds a `ports:` entry by mistake, the SG still blocks it.

### Outbound

| Port | Destination | Why |
|---|---|---|
| 443 | `0.0.0.0/0` (or VPC endpoints) | ECR (API + layers on S3), S3 product images, STS, GitHub (compose plugin download), dnf repos |
| 80 | `0.0.0.0/0` | Some dnf mirrors (optional; AL2023 repos use HTTPS) |

Tighter: put the instance in a private subnet with **VPC endpoints** (`com.amazonaws.<region>.s3`
gateway endpoint, `ecr.api`, `ecr.dkr`, `sts`, `ssm`) and remove general internet egress.

## `sg-philips-alb` (if an ALB is used)

| Direction | Port | Source / destination |
|---|---|---|
| Inbound | 443 (and 80 -> redirect to 443) | `0.0.0.0/0` |
| Outbound | 8080 | `sg-philips-app` |

Health check target: `GET /actuator/health` on port 8080.

## Example AWS CLI

```bash
VPC_ID=vpc-xxxxxxxx
ADMIN_IP=203.0.113.10/32          # your public IP: curl -s https://checkip.amazonaws.com

APP_SG=$(aws ec2 create-security-group --group-name sg-philips-app \
  --description "Philips e-commerce EC2 host" --vpc-id "$VPC_ID" --query GroupId --output text)
ALB_SG=$(aws ec2 create-security-group --group-name sg-philips-alb \
  --description "Philips e-commerce ALB" --vpc-id "$VPC_ID" --query GroupId --output text)

# SSH from the admin IP only
aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --protocol tcp --port 22 --cidr "$ADMIN_IP"
# Gateway only from the ALB security group (SG-to-SG reference, no CIDR)
aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --protocol tcp --port 8080 --source-group "$ALB_SG"
# ALB public HTTPS
aws ec2 authorize-security-group-ingress --group-id "$ALB_SG" --protocol tcp --port 443 --cidr 0.0.0.0/0

# Default SGs allow all egress; to restrict, revoke the default rule and add 443 only:
aws ec2 revoke-security-group-egress --group-id "$APP_SG" --protocol -1 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-egress --group-id "$APP_SG" --protocol tcp --port 443 --cidr 0.0.0.0/0
```

## Managed services (recommended evolution)

When MySQL moves to **RDS**, Redis to **ElastiCache** and Kafka to **MSK**, give each its own
security group and allow ingress **from `sg-philips-app` only** (SG-to-SG references, no CIDRs):

| SG | Inbound port | Source |
|---|---|---|
| `sg-philips-rds` | 3306 | `sg-philips-app` |
| `sg-philips-redis` | 6379 | `sg-philips-app` |
| `sg-philips-msk` | 9094 (TLS) / 9098 (IAM auth) | `sg-philips-app` |

SG references keep working when instance IPs change (Auto Scaling) and document intent
("the app tier may talk to the DB tier") better than IP ranges.
