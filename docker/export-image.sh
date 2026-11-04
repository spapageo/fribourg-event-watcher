#!/usr/bin/env bash
#
# Build the service image and export it as a gzip archive for offline transfer to
# an Unraid (or any Docker) host:
#
#   ./docker/export-image.sh              # builds and exports fribourg-events-digest:1.0.0
#   ./docker/export-image.sh 1.2.0        # custom tag
#   SKIP_BUILD=1 ./docker/export-image.sh # export an image that is already built
#   PLATFORM=linux/arm64 ./docker/export-image.sh
#
# The archive lands in dist/ together with a SHA-256 file. On the target host:
#
#   docker load -i fribourg-events-digest-1.0.0.tar.gz
#   # then install docker/unraid/fribourg-events-digest.xml (see docs/runbook.md)
#
set -euo pipefail

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-fribourg-events-digest}"
TAG="${1:-${TAG:-1.0.0}}"
PLATFORM="${PLATFORM:-linux/amd64}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"
ARCHIVE="$OUT_DIR/${IMAGE_NAME}-${TAG}.tar.gz"
IMAGE="$IMAGE_NAME:$TAG"

die() {
  printf 'export-image: %s\n' "$*" >&2
  exit 1
}

case "$TAG" in
  *[!A-Za-z0-9_.-]*) die "invalid tag '$TAG' (allowed: letters, digits, '.', '_', '-')" ;;
esac

command -v docker >/dev/null 2>&1 || die "docker is not installed"
command -v gzip >/dev/null 2>&1 || die "gzip is not installed"
sudo docker info >/dev/null 2>&1 || die "cannot connect to the Docker daemon (is it running?)"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  printf '==> Building %s for %s\n' "$IMAGE" "$PLATFORM"
  sudo docker build \
    --platform "$PLATFORM" \
    -f "$ROOT/docker/Dockerfile" \
    -t "$IMAGE" \
    "$ROOT"
else
  printf '==> SKIP_BUILD=1, exporting the existing %s\n' "$IMAGE"
fi

sudo docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || die "image $IMAGE not found; run without SKIP_BUILD=1 first"

mkdir -p "$OUT_DIR"
printf '==> Writing %s\n' "$ARCHIVE"
sudo docker save "$IMAGE" | gzip -9 > "$ARCHIVE"

# Ship a template whose <Repository> matches this tag, so importing both files cannot
# accidentally point the Unraid container at a different image.
TEMPLATE_SRC="$ROOT/docker/unraid/${IMAGE_NAME}.xml"
TEMPLATE_OUT="$OUT_DIR/${IMAGE_NAME}-${TAG}.xml"
sed "s|<Repository>.*</Repository>|<Repository>${IMAGE}</Repository>|" \
  "$TEMPLATE_SRC" > "$TEMPLATE_OUT"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUT_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
elif command -v shasum >/dev/null 2>&1; then
  (cd "$OUT_DIR" && shasum -a 256 "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
else
  printf 'export-image: no sha256 tool found; skipping %s.sha256\n' "$ARCHIVE" >&2
fi

SIZE="$(du -h "$ARCHIVE" | cut -f1)"

cat <<EOF

Exported ${IMAGE} to ${ARCHIVE} (${SIZE})

1. Copy the archive to the Unraid host (avoid /boot: it is FAT32 and caps files at 4 GB):

     scp "${ARCHIVE}" root@<unraid>:/mnt/user/appdata/fribourg-digest/

2. On the Unraid terminal, import the image:

     docker load -i /mnt/user/appdata/fribourg-digest/$(basename "$ARCHIVE")
     docker image ls | grep '${IMAGE_NAME}'

3. Copy the matching Unraid template into the user templates folder:

     cp /path/to/${IMAGE_NAME}-${TAG}.xml \\
        /boot/config/plugins/dockerMan/templates-user/my-${IMAGE_NAME}.xml

   The generated template already points at ${IMAGE}. Then open the Unraid Docker tab,
   choose "Add Container", pick the fribourg-events-digest template, fill in the
   Telegram credentials, and apply.

   See docs/runbook.md section 2 for the full deployment and verification steps,
   including the docker run equivalent.

EOF
