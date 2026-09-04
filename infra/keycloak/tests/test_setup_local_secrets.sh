#!/usr/bin/env bash
set -euo pipefail

readonly TEST_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/.local/test-secrets-$$
readonly SCRIPT_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/setup-local-secrets.sh
cleanup() { rm -rf -- "$TEST_ROOT"; }
trap cleanup EXIT
mkdir -p -- "$TEST_ROOT"
cp -- "$SCRIPT_SOURCE" "$TEST_ROOT/setup-local-secrets.sh"

output=$(bash "$TEST_ROOT/setup-local-secrets.sh" 2>&1)
readonly SECRET_DIR="$TEST_ROOT/.local/secrets"
readonly NAMES=(bootstrap-admin-client-secret transaction-service-client-secret behavior-service-client-secret)
declare -a values=()
for name in "${NAMES[@]}"; do
  path="$SECRET_DIR/$name"
  [[ -f "$path" && ! -L "$path" ]]
  value=$(<"$path")
  [[ ${#value} -ge 43 && ${#value} -le 96 ]]
  [[ $value =~ ^[A-Za-z0-9_-]+$ ]]
  [[ $(wc -c < "$path") -eq ${#value} ]]
  [[ $output != *"$value"* ]]
  values+=("$value")
done
[[ ${values[0]} != "${values[1]}" && ${values[0]} != "${values[2]}" && ${values[1]} != "${values[2]}" ]]

before=$(sha256sum "$SECRET_DIR"/*)
if bash "$TEST_ROOT/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected overwrite refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$SECRET_DIR"/*)" ]]

readonly SYMLINK_CASES="$TEST_ROOT/symlink-cases"
mkdir -p -- "$SYMLINK_CASES"

case_root="$SYMLINK_CASES/local-link"
external="$SYMLINK_CASES/local-link-target"
mkdir -p -- "$case_root" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local"
if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected .local symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$SYMLINK_CASES/output-link"
external="$SYMLINK_CASES/output-link-target"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local/secrets"
if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected secrets directory symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$SYMLINK_CASES/artifact-link"
external="$SYMLINK_CASES/artifact-link-target"
mkdir -p -- "$case_root/.local/secrets" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
printf 'NeverPrintSymlinkSecret' > "$external/target"
before=$(sha256sum "$external/target")
ln -s -- "$external/target" "$case_root/.local/secrets/bootstrap-admin-client-secret"
if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected secret artifact symlink rejection\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$external/target")" ]]
[[ $output != *NeverPrintSymlinkSecret* ]]

case_root="$SYMLINK_CASES/prefix-boundary"
external="$case_root/.local-evil"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local/secrets"
if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected prefix boundary rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$SYMLINK_CASES/partial-parent-link"
external="$SYMLINK_CASES/partial-parent-target"
mkdir -p -- "$case_root/.local/secrets" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local/state"
if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected partial parent symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]
[[ -z $(find "$case_root/.local/secrets" -type f -print -quit) ]]

for invalid in empty crlf bom whitespace; do
  case "$invalid" in
    empty) printf '' > "$SECRET_DIR/bootstrap-admin-client-secret" ;;
    crlf) printf '%s\r\n' "${values[0]}" > "$SECRET_DIR/bootstrap-admin-client-secret" ;;
    bom) printf '\357\273\277%s' "${values[0]}" > "$SECRET_DIR/bootstrap-admin-client-secret" ;;
    whitespace) printf '%s ' "${values[0]}" > "$SECRET_DIR/bootstrap-admin-client-secret" ;;
  esac
  if bash "$TEST_ROOT/setup-local-secrets.sh" >/dev/null 2>&1; then
    printf 'expected fail-closed existing state: %s\n' "$invalid" >&2
    exit 1
  fi
done

rm -rf -- "$SECRET_DIR"
mkdir -p -- "$TEST_ROOT/fake-bin"
cat > "$TEST_ROOT/fake-bin/openssl" <<'FAKE'
#!/usr/bin/env bash
counter_file=${FAKE_COUNTER:?}
count=0
[[ ! -f "$counter_file" ]] || count=$(<"$counter_file")
count=$((count + 1))
printf '%s' "$count" > "$counter_file"
if (( count == 1 )); then
  printf 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-'
  exit 0
fi
exit 1
FAKE
chmod +x "$TEST_ROOT/fake-bin/openssl"
if FAKE_COUNTER="$TEST_ROOT/.local/fake-counter" PATH="$TEST_ROOT/fake-bin:$PATH" bash "$TEST_ROOT/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected injected generator failure\n' >&2
  exit 1
fi
[[ ! -e "$SECRET_DIR/bootstrap-admin-client-secret" ]]
[[ ! -e "$SECRET_DIR/transaction-service-client-secret" ]]
[[ ! -e "$SECRET_DIR/behavior-service-client-secret" ]]

printf 'setup-local-secrets tests passed\n'
