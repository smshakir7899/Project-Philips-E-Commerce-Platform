#!/bin/sh
# =============================================================================================
# One-shot `minio-init` container (image bitnamilegacy/minio, which bundles the `mc` client). Creates the product-image bucket in MinIO,
# the S3-compatible object store used LOCALLY in place of AWS S3.
#
# LOCAL DEV ONLY: the bucket gets an anonymous "download" (read-only GET) policy so the image
# URLs returned by product-service (S3_PUBLIC_BASE_URL=http://localhost:9000/philips-product-images)
# open directly in a browser. In AWS the bucket stays PRIVATE (Block Public Access on, see
# deploy/aws/s3-bucket-setup.sh) and clients get presigned URLs or go through CloudFront (OAC).
#
# POSIX sh on purpose: keeps the script portable to any image that ships `mc`.
# Env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, S3_BUCKET (default philips-product-images).
# =============================================================================================
set -eu

BUCKET="${S3_BUCKET:-philips-product-images}"

echo "[minio-init] configuring alias for http://minio:9000"
i=0
until mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 30 ]; then echo "[minio-init] MinIO not reachable" >&2; exit 1; fi
  sleep 2
done

mc mb --ignore-existing "local/${BUCKET}"
mc anonymous set download "local/${BUCKET}"
echo "[minio-init] bucket policy:"
mc anonymous get "local/${BUCKET}"
mc ls local
echo "[minio-init] done"
