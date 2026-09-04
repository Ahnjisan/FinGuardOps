#!/usr/bin/env bash
set -euo pipefail
umask 077

command -v dirname >/dev/null 2>&1 || { printf 'local secret setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v pwd >/dev/null 2>&1 || { printf 'local secret setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v realpath >/dev/null 2>&1 || { printf 'local secret setup failed: required path tool is unavailable\n' >&2; exit 1; }

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly LOCAL_DIR="$SCRIPT_DIR/.local"
readonly OUTPUT_DIR="$SCRIPT_DIR/.local/secrets"
readonly STATE_DIR="$SCRIPT_DIR/.local/state"
readonly NAMES=(
  bootstrap-admin-client-secret
  transaction-service-client-secret
  behavior-service-client-secret
)
created=()
values=()
completed=false

fail() {
  printf 'local secret setup failed: %s\n' "$1" >&2
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
  local path
  if [[ $completed != true ]] && (( ${#created[@]} > 0 )); then
    for path in "${created[@]}"; do
      [[ -n "$path" && "$path" == "$OUTPUT_DIR/"* ]] && rm -f -- "$path"
    done
  fi
}
trap cleanup EXIT INT TERM

command -v openssl >/dev/null 2>&1 || fail 'openssl is required'
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/secrets"
validate_directory_path "$STATE_DIR" "$SCRIPT_DIR/.local/state"
mkdir -p -- "$OUTPUT_DIR" "$STATE_DIR"
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/secrets"
validate_directory_path "$STATE_DIR" "$SCRIPT_DIR/.local/state"

for name in "${NAMES[@]}"; do
  [[ ! -e "$OUTPUT_DIR/$name" && ! -L "$OUTPUT_DIR/$name" ]] || fail "existing file blocks generation: $name"
done

for name in "${NAMES[@]}"; do
  value=$(openssl rand -base64 48 | tr -d '=+/\r\n' | tr '+/' '_-')
  [[ ${#value} -ge 43 && ${#value} -le 96 ]] || fail "generator returned invalid length for $name"
  [[ $value =~ ^[A-Za-z0-9_-]+$ ]] || fail "generator returned invalid characters for $name"
  for previous in "${values[@]}"; do
    [[ $value != "$previous" ]] || fail "generator returned a duplicate value for $name"
  done
  values+=("$value")
  path="$OUTPUT_DIR/$name"
  printf '%s' "$value" > "$path"
  created+=("$path")
  bytes=$(wc -c < "$path")
  [[ $bytes -eq ${#value} ]] || fail "write verification failed for $name"
done

completed=true
printf 'created local secret file: %s\n' "${NAMES[@]}"
