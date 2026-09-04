#!/usr/bin/env bash
set -euo pipefail
umask 077

command -v dirname >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v pwd >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v realpath >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly LOCAL_DIR="$SCRIPT_DIR/.local"
readonly OUTPUT_DIR="$SCRIPT_DIR/.local/tls"
readonly CERTIFICATE="$OUTPUT_DIR/localhost.crt"
readonly PRIVATE_KEY="$OUTPUT_DIR/localhost.key"
temporary_dir=
cleanup_outputs=false
completed=false

fail() {
  printf 'local TLS setup failed: %s\n' "$1" >&2
  exit 1
}

validate_directory_path() {
  local path=$1
  local expected=$2
  local resolved
  [[ ! -L "$path" ]] || fail 'approved output path must not be a symlink'
  resolved=$(realpath -m -- "$path") || fail 'approved output path cannot be resolved'
  [[ "$resolved" == "$expected" ]] || fail 'approved output path escaped its physical root'
}

cleanup() {
  if [[ -n "$temporary_dir" && "$temporary_dir" == "$OUTPUT_DIR/".tls-build.* && -d "$temporary_dir" && ! -L "$temporary_dir" ]]; then
    rm -f -- "$temporary_dir/localhost.crt" "$temporary_dir/localhost.key"
    rmdir -- "$temporary_dir" 2>/dev/null || true
  fi
  if [[ $cleanup_outputs == true && $completed != true ]]; then
    rm -f -- "$CERTIFICATE" "$PRIVATE_KEY"
  fi
}
trap cleanup EXIT

command -v openssl >/dev/null 2>&1 || fail 'openssl is required'
openssl version >/dev/null 2>&1 || fail 'openssl version check failed'
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/tls"
mkdir -p -- "$OUTPUT_DIR"
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/tls"
[[ ! -e "$CERTIFICATE" && ! -L "$CERTIFICATE" ]] || fail 'existing certificate blocks generation'
[[ ! -e "$PRIVATE_KEY" && ! -L "$PRIVATE_KEY" ]] || fail 'existing private key blocks generation'
cleanup_outputs=true

temporary_dir=$(mktemp -d "$OUTPUT_DIR/.tls-build.XXXXXXXX")
temporary_certificate="$temporary_dir/localhost.crt"
temporary_key="$temporary_dir/localhost.key"

openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
  -subj '/CN=localhost' \
  -addext 'subjectAltName=DNS:localhost' \
  -keyout "$temporary_key" -out "$temporary_certificate" >/dev/null 2>&1 \
  || fail 'certificate generation failed'

openssl x509 -in "$temporary_certificate" -noout >/dev/null 2>&1 \
  || fail 'certificate PEM validation failed'
openssl pkey -in "$temporary_key" -noout -check >/dev/null 2>&1 \
  || fail 'private key PEM validation failed'
openssl x509 -in "$temporary_certificate" -checkend 1 -noout >/dev/null 2>&1 \
  || fail 'certificate validity check failed'
san=$(openssl x509 -in "$temporary_certificate" -noout -ext subjectAltName 2>/dev/null) \
  || fail 'certificate SAN read failed'
san_entries=$(printf '%s\n' "$san" | tail -n +2 | tr -d '[:space:]')
[[ $san_entries == 'DNS:localhost' ]] || fail 'certificate SAN must be exactly DNS:localhost'
certificate_public=$(openssl x509 -in "$temporary_certificate" -pubkey -noout | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256)
key_public=$(openssl pkey -in "$temporary_key" -pubout -outform DER 2>/dev/null | openssl dgst -sha256)
[[ $certificate_public == "$key_public" ]] || fail 'certificate and private key do not match'

mv -- "$temporary_certificate" "$CERTIFICATE"
mv -- "$temporary_key" "$PRIVATE_KEY"
rmdir -- "$temporary_dir"
temporary_dir=
completed=true
printf 'created local TLS certificate: localhost.crt\n'
printf 'created local TLS private key: localhost.key\n'
