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
  user-password
)
readonly EXISTING_NAMES=(
  bootstrap-admin-client-secret
  transaction-service-client-secret
  behavior-service-client-secret
)
created=()
values=()
names_to_create=()
temporary_dir=
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
  if [[ -n "$temporary_dir" && "$temporary_dir" == "$OUTPUT_DIR/".secret-build.* && -d "$temporary_dir" && ! -L "$temporary_dir" ]]; then
    for path in "$temporary_dir"/*; do
      [[ ! -e "$path" && ! -L "$path" ]] || rm -f -- "$path"
    done
    rmdir -- "$temporary_dir" 2>/dev/null || true
  fi
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
  [[ ! -L "$OUTPUT_DIR/$name" ]] || fail "secret path must not be a symlink: $name"
done

existing_count=0
for name in "${NAMES[@]}"; do
  [[ ! -e "$OUTPUT_DIR/$name" ]] || existing_count=$((existing_count + 1))
done

if (( existing_count == 0 )); then
  names_to_create=("${NAMES[@]}")
elif (( existing_count == ${#EXISTING_NAMES[@]} )) && [[ ! -e "$OUTPUT_DIR/user-password" ]]; then
  for name in "${EXISTING_NAMES[@]}"; do
    [[ -f "$OUTPUT_DIR/$name" ]] || fail 'unexpected partial secret state'
    value=$(<"$OUTPUT_DIR/$name")
    bytes=$(wc -c < "$OUTPUT_DIR/$name")
    [[ ${#value} -ge 43 && ${#value} -le 96 && $bytes -eq ${#value} ]] \
      || fail "existing secret content is invalid: $name"
    [[ $value =~ ^[A-Za-z0-9_-]+$ ]] || fail "existing secret content is invalid: $name"
    for previous in "${values[@]}"; do
      [[ $value != "$previous" ]] || fail 'existing secrets must be distinct'
    done
    values+=("$value")
  done
  names_to_create=(user-password)
else
  fail 'unexpected partial or existing secret state'
fi

temporary_dir=$(mktemp -d "$OUTPUT_DIR/.secret-build.XXXXXXXX")
for name in "${names_to_create[@]}"; do
  value=$(openssl rand -hex 32) || fail "generator failed for $name"
  [[ ${#value} -eq 64 ]] || fail "generator returned invalid length for $name"
  [[ $value =~ ^[A-Za-z0-9_-]+$ ]] || fail "generator returned invalid characters for $name"
  for previous in "${values[@]}"; do
    [[ $value != "$previous" ]] || fail "generator returned a duplicate value for $name"
  done
  values+=("$value")
  path="$temporary_dir/$name"
  printf '%s' "$value" > "$path"
  bytes=$(wc -c < "$path")
  [[ $bytes -eq ${#value} ]] || fail "write verification failed for $name"
done

validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/secrets"
validate_directory_path "$STATE_DIR" "$SCRIPT_DIR/.local/state"
for name in "${NAMES[@]}"; do
  [[ ! -L "$OUTPUT_DIR/$name" ]] || fail "secret path changed during generation: $name"
done
if (( existing_count == 0 )); then
  for name in "${NAMES[@]}"; do
    [[ ! -e "$OUTPUT_DIR/$name" ]] || fail "secret path changed during generation: $name"
  done
else
  for index in "${!EXISTING_NAMES[@]}"; do
    name=${EXISTING_NAMES[$index]}
    [[ -f "$OUTPUT_DIR/$name" && $(<"$OUTPUT_DIR/$name") == "${values[$index]}" ]] \
      || fail "existing secret changed during generation: $name"
  done
  [[ ! -e "$OUTPUT_DIR/user-password" ]] || fail 'user password path changed during generation'
fi
for name in "${names_to_create[@]}"; do
  path="$OUTPUT_DIR/$name"
  mv -- "$temporary_dir/$name" "$path"
  created+=("$path")
done
rmdir -- "$temporary_dir"
temporary_dir=

completed=true
printf 'created local secret file: %s\n' "${names_to_create[@]}"
