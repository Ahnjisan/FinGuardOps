#!/usr/bin/env bash
set -euo pipefail

readonly ADMIN_SECRET_FILE=/run/secrets/keycloak_bootstrap_admin_secret
readonly CERTIFICATE_FILE=/run/secrets/keycloak_tls_certificate
readonly PRIVATE_KEY_FILE=/run/secrets/keycloak_tls_private_key

fail() {
  printf 'keycloak startup failed: %s\n' "$1" >&2
  exit 1
}

(( $# == 0 )) || fail 'ARGUMENTS_NOT_ALLOWED'

validate_regular_file() {
  local path=$1
  local label=$2
  [[ -e "$path" ]] || fail "$label is missing"
  [[ -f "$path" ]] || fail "$label is not a regular file"
  [[ ! -L "$path" ]] || fail "$label must not be a symlink"
  [[ -s "$path" ]] || fail "$label is empty"
}

validate_secret_file() {
  local path=$1
  local size
  validate_regular_file "$path" 'bootstrap admin secret'
  size=$(wc -c < "$path")
  (( size >= 32 && size <= 128 )) || fail 'bootstrap admin secret has invalid length'
  LC_ALL=C grep -Eq '^[A-Za-z0-9_-]+$' "$path" || fail 'bootstrap admin secret has invalid content'
  [[ $(LC_ALL=C tr -d '\r\n\000' < "$path" | wc -c) -eq size ]] || fail 'bootstrap admin secret contains a forbidden byte'
}

validate_secret_file "$ADMIN_SECRET_FILE"
validate_regular_file "$CERTIFICATE_FILE" 'TLS certificate'
validate_regular_file "$PRIVATE_KEY_FILE" 'TLS private key'

IFS= read -r bootstrap_secret < "$ADMIN_SECRET_FILE" || [[ -n ${bootstrap_secret:-} ]]
export KC_BOOTSTRAP_ADMIN_CLIENT_ID=temp-admin
export KC_BOOTSTRAP_ADMIN_CLIENT_SECRET="$bootstrap_secret"
unset bootstrap_secret

readonly -a KEYCLOAK_COMMAND=(
  /opt/keycloak/bin/kc.sh
  start
  --import-realm
)
exec "${KEYCLOAK_COMMAND[@]}"
