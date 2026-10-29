#!/usr/bin/env bash
# =============================================================================================
# deploy/aws/s3-bucket-setup.sh - create the PRIVATE product-image bucket (and optionally the IAM
# role / instance profile for the EC2 host) with the AWS CLI v2.
#
# Usage:
#   deploy/aws/s3-bucket-setup.sh [--bucket NAME] [--region REGION] [--cors-origin URL]
#                                 [--with-lifecycle] [--create-iam] [--help]
#
# Defaults: bucket philips-product-images, region eu-west-1, CORS origin http://localhost:8080.
# Env overrides: BUCKET, REGION, CORS_ORIGIN, ROLE_NAME (philips-ecommerce-ec2).
#
# What it configures
#   * Block Public Access (all four settings ON) - unlike local MinIO, the AWS bucket is never public;
#     images are served via presigned URLs or CloudFront + Origin Access Control.
#   * Default encryption SSE-S3 (AES256) with S3 Bucket Keys.
#   * Object Ownership = BucketOwnerEnforced (ACLs disabled; access only through IAM/bucket policies).
#   * CORS: GET/HEAD from the given origin (browser loading images / presigned URLs).
#   * --with-lifecycle: abort incomplete multipart uploads after 7 days (stops paying for orphan parts).
#   * --create-iam: role with deploy/iam/ec2-trust-policy.json + inline product-service-s3-policy.json,
#     AmazonEC2ContainerRegistryReadOnly (ECR pulls in deploy.sh), and an instance profile.
#
# Assumptions: aws cli v2 configured with an admin/infra profile (NOT the app role); bucket names are
# global, so change BUCKET if philips-product-images is taken (and update the IAM policy ARNs).
# =============================================================================================
set -euo pipefail

BUCKET="${BUCKET:-philips-product-images}"
REGION="${REGION:-eu-west-1}"
CORS_ORIGIN="${CORS_ORIGIN:-http://localhost:8080}"
ROLE_NAME="${ROLE_NAME:-philips-ecommerce-ec2}"
WITH_LIFECYCLE=false
CREATE_IAM=false
IAM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../iam" && pwd)"

usage() { sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --bucket) BUCKET="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --cors-origin) CORS_ORIGIN="$2"; shift 2 ;;
    --with-lifecycle) WITH_LIFECYCLE=true; shift ;;
    --create-iam) CREATE_IAM=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

log() { printf '\033[0;34m[s3-setup]\033[0m %s\n' "$*"; }

command -v aws >/dev/null 2>&1 || { echo "aws cli not found" >&2; exit 1; }
log "Caller identity: $(aws sts get-caller-identity --query Arn --output text)"

# ---- Bucket -----------------------------------------------------------------------------------
if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  log "Bucket $BUCKET already exists (and is ours) - updating its settings"
else
  log "Creating bucket $BUCKET in $REGION"
  if [ "$REGION" = "us-east-1" ]; then
    # us-east-1 is the default and REJECTS an explicit LocationConstraint.
    aws s3api create-bucket --bucket "$BUCKET" --region "$REGION"
  else
    aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
      --create-bucket-configuration "LocationConstraint=$REGION"
  fi
  aws s3api wait bucket-exists --bucket "$BUCKET"
fi

log "Blocking all public access"
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'

log "Disabling ACLs (BucketOwnerEnforced)"
aws s3api put-bucket-ownership-controls --bucket "$BUCKET" \
  --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'

log "Enabling default encryption SSE-S3 (AES256) with bucket keys"
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'

log "Setting CORS (GET/HEAD from $CORS_ORIGIN)"
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration "{
  \"CORSRules\": [{
    \"AllowedOrigins\": [\"$CORS_ORIGIN\"],
    \"AllowedMethods\": [\"GET\", \"HEAD\"],
    \"AllowedHeaders\": [\"*\"],
    \"ExposeHeaders\": [\"ETag\"],
    \"MaxAgeSeconds\": 3600
  }]
}"

if [ "$WITH_LIFECYCLE" = true ]; then
  log "Adding lifecycle rule: abort incomplete multipart uploads after 7 days"
  aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
    '{"Rules":[{"ID":"abort-incomplete-mpu","Status":"Enabled","Filter":{"Prefix":""},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
fi

# ---- IAM role + instance profile (optional) ---------------------------------------------------
if [ "$CREATE_IAM" = true ]; then
  if [ "$BUCKET" != "philips-product-images" ]; then
    echo "WARNING: $IAM_DIR/product-service-s3-policy.json references philips-product-images - edit the ARNs for $BUCKET" >&2
  fi
  log "Creating IAM role $ROLE_NAME (trust: ec2.amazonaws.com)"
  if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
    aws iam create-role --role-name "$ROLE_NAME" \
      --assume-role-policy-document "file://$IAM_DIR/ec2-trust-policy.json" \
      --description "Philips e-commerce EC2 host: S3 product images + ECR pull"
  fi
  aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name product-service-s3 \
    --policy-document "file://$IAM_DIR/product-service-s3-policy.json"
  aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
  # Optional: allow SSM Session Manager instead of opening port 22.
  # aws iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
  if ! aws iam get-instance-profile --instance-profile-name "$ROLE_NAME" >/dev/null 2>&1; then
    aws iam create-instance-profile --instance-profile-name "$ROLE_NAME"
    aws iam add-role-to-instance-profile --instance-profile-name "$ROLE_NAME" --role-name "$ROLE_NAME"
  fi
  log "Instance profile $ROLE_NAME ready. Attach it at launch (--iam-instance-profile Name=$ROLE_NAME) or:"
  echo "  aws ec2 associate-iam-instance-profile --instance-id i-XXXX --iam-instance-profile Name=$ROLE_NAME"
  echo "  aws ec2 modify-instance-metadata-options --instance-id i-XXXX --http-tokens required --http-put-response-hop-limit 2"
fi

log "Done. Verify: aws s3api get-public-access-block --bucket $BUCKET && aws s3api get-bucket-encryption --bucket $BUCKET"
