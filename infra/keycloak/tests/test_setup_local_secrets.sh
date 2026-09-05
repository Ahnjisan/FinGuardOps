#!/usr/bin/env bash
set -euo pipefail

readonly TEST_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/.local/test-secrets-$$
readonly SCRIPT_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/setup-local-secrets.sh
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
cleanup() { rm -rf -- "$TEST_ROOT"; }
trap cleanup EXIT

new_case() {
  local name=$1
  local root="$TEST_ROOT/$name"
  mkdir -p -- "$root"
  cp -- "$SCRIPT_SOURCE" "$root/setup-local-secrets.sh"
  printf '%s\n' "$root"
}

write_existing_three() {
  local root=$1
  local index=0
  mkdir -p -- "$root/.local/secrets"
  for name in "${EXISTING_NAMES[@]}"; do
    index=$((index + 1))
    printf '%064d' "$index" > "$root/.local/secrets/$name"
  done
}

assert_no_secret_in_output() {
  local output=$1
  local directory=$2
  local name value
  for name in "${NAMES[@]}"; do
    [[ ! -f "$directory/$name" ]] || {
      value=$(<"$directory/$name")
      [[ $output != *"$value"* ]]
    }
  done
}

mkdir -p -- "$TEST_ROOT"

fresh_root=$(new_case fresh)
output=$(bash "$fresh_root/setup-local-secrets.sh" 2>&1)
fresh_secret_dir="$fresh_root/.local/secrets"
declare -a values=()
for name in "${NAMES[@]}"; do
  path="$fresh_secret_dir/$name"
  [[ -f "$path" && ! -L "$path" ]]
  value=$(<"$path")
  [[ ${#value} -eq 64 ]]
  [[ $value =~ ^[A-Za-z0-9_-]+$ ]]
  [[ $(wc -c < "$path") -eq ${#value} ]]
  [[ $output != *"$value"* ]]
  values+=("$value")
done
[[ $(printf '%s\n' "${values[@]}" | LC_ALL=C sort -u | wc -l) -eq 4 ]]

before=$(sha256sum "$fresh_secret_dir"/*)
if output=$(bash "$fresh_root/setup-local-secrets.sh" 2>&1); then
  printf 'expected overwrite refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$fresh_secret_dir"/*)" ]]
assert_no_secret_in_output "$output" "$fresh_secret_dir"

extension_root=$(new_case extension)
write_existing_three "$extension_root"
existing_before=$(sha256sum "$extension_root/.local/secrets"/* | LC_ALL=C sort)
output=$(bash "$extension_root/setup-local-secrets.sh" 2>&1)
existing_after=$(sha256sum "$extension_root/.local/secrets"/{bootstrap-admin-client-secret,transaction-service-client-secret,behavior-service-client-secret} | LC_ALL=C sort)
[[ $existing_before == "$existing_after" ]]
[[ -f "$extension_root/.local/secrets/user-password" && ! -L "$extension_root/.local/secrets/user-password" ]]
user_password=$(<"$extension_root/.local/secrets/user-password")
[[ ${#user_password} -eq 64 && $user_password =~ ^[A-Za-z0-9_-]+$ ]]
[[ $output != *"$user_password"* ]]

for present in bootstrap-admin-client-secret transaction-service-client-secret behavior-service-client-secret user-password; do
  partial_root=$(new_case "partial-$present")
  mkdir -p -- "$partial_root/.local/secrets"
  printf '%064d' 9 > "$partial_root/.local/secrets/$present"
  before=$(sha256sum "$partial_root/.local/secrets/$present")
  if output=$(bash "$partial_root/setup-local-secrets.sh" 2>&1); then
    printf 'expected partial-state refusal: %s\n' "$present" >&2
    exit 1
  fi
  [[ $before == "$(sha256sum "$partial_root/.local/secrets/$present")" ]]
  assert_no_secret_in_output "$output" "$partial_root/.local/secrets"
done

for invalid in empty crlf bom whitespace nul; do
  invalid_root=$(new_case "invalid-$invalid")
  write_existing_three "$invalid_root"
  invalid_path="$invalid_root/.local/secrets/bootstrap-admin-client-secret"
  case "$invalid" in
    empty) printf '' > "$invalid_path" ;;
    crlf) printf '%064d\r\n' 1 > "$invalid_path" ;;
    bom) printf '\357\273\277%064d' 1 > "$invalid_path" ;;
    whitespace) printf '%063d ' 1 > "$invalid_path" ;;
    nul) printf '%063d\0' 1 > "$invalid_path" ;;
  esac
  before=$(sha256sum "$invalid_root/.local/secrets"/*)
  if output=$(bash "$invalid_root/setup-local-secrets.sh" 2>&1); then
    printf 'expected invalid existing state refusal: %s\n' "$invalid" >&2
    exit 1
  fi
  [[ $before == "$(sha256sum "$invalid_root/.local/secrets"/*)" ]]
  [[ ! -e "$invalid_root/.local/secrets/user-password" ]]
done

readonly LINK_CASES="$TEST_ROOT/link-cases"
mkdir -p -- "$LINK_CASES"

case_root="$LINK_CASES/local-link"
external="$LINK_CASES/local-link-target"
mkdir -p -- "$case_root" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local"
if bash "$case_root/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected .local symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$LINK_CASES/output-link"
external="$LINK_CASES/output-link-target"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local/secrets"
if bash "$case_root/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected secrets directory symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

for artifact in "${NAMES[@]}"; do
  case_root="$LINK_CASES/artifact-$artifact"
  external="$LINK_CASES/artifact-$artifact-target"
  mkdir -p -- "$case_root/.local/secrets" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
  printf 'NeverPrintSymlinkSecret' > "$external/target"
  before=$(sha256sum "$external/target")
  ln -s -- "$external/target" "$case_root/.local/secrets/$artifact"
  if output=$(bash "$case_root/setup-local-secrets.sh" 2>&1); then
    printf 'expected secret artifact symlink rejection: %s\n' "$artifact" >&2
    exit 1
  fi
  [[ $before == "$(sha256sum "$external/target")" ]]
  [[ $output != *NeverPrintSymlinkSecret* ]]
done

case_root="$LINK_CASES/prefix-boundary"
external="$case_root/.local-evil"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
ln -s -- "$external" "$case_root/.local/secrets"
if bash "$case_root/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected prefix boundary rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

junction_count=0
if command -v powershell.exe >/dev/null 2>&1 \
  && (command -v cygpath >/dev/null 2>&1 || command -v wslpath >/dev/null 2>&1); then
  case_root="$LINK_CASES/output-junction"
  external="$LINK_CASES/output-junction-target"
  mkdir -p -- "$case_root/.local" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-secrets.sh"
  if command -v cygpath >/dev/null 2>&1; then
    FINGUARDOPS_JUNCTION_LINK=$(cygpath -w "$case_root/.local/secrets")
    FINGUARDOPS_JUNCTION_TARGET=$(cygpath -w "$external")
  else
    FINGUARDOPS_JUNCTION_LINK=$(wslpath -w "$case_root/.local/secrets")
    FINGUARDOPS_JUNCTION_TARGET=$(wslpath -w "$external")
  fi
  powershell.exe -NoProfile -NonInteractive -Command \
    "New-Item -ItemType Junction -Path '$FINGUARDOPS_JUNCTION_LINK' -Target '$FINGUARDOPS_JUNCTION_TARGET' | Out-Null"
  if bash "$case_root/setup-local-secrets.sh" >/dev/null 2>&1; then
    printf 'expected secrets directory junction rejection\n' >&2
    exit 1
  fi
  [[ -z $(find "$external" -type f -print -quit) ]]
  powershell.exe -NoProfile -NonInteractive -Command \
    "[IO.Directory]::Delete('$FINGUARDOPS_JUNCTION_LINK', \$false)"
  junction_count=1
fi

failure_root=$(new_case generator-failure)
mkdir -p -- "$failure_root/fake-bin"
cat > "$failure_root/fake-bin/openssl" <<'FAKE'
#!/usr/bin/env bash
counter_file=${FAKE_COUNTER:?}
count=0
[[ ! -f "$counter_file" ]] || count=$(<"$counter_file")
count=$((count + 1))
printf '%s' "$count" > "$counter_file"
if (( count == 1 )); then
  printf 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789'
  exit 0
fi
exit 1
FAKE
chmod +x "$failure_root/fake-bin/openssl"
if FAKE_COUNTER="$failure_root/.local/fake-counter" PATH="$failure_root/fake-bin:$PATH" \
  bash "$failure_root/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected injected generator failure\n' >&2
  exit 1
fi
for name in "${NAMES[@]}"; do
  [[ ! -e "$failure_root/.local/secrets/$name" ]]
done

extension_failure_root=$(new_case extension-generator-failure)
write_existing_three "$extension_failure_root"
printf '1' > "$extension_failure_root/.local/fake-counter"
before=$(sha256sum "$extension_failure_root/.local/secrets"/*)
if FAKE_COUNTER="$extension_failure_root/.local/fake-counter" PATH="$failure_root/fake-bin:$PATH" \
  bash "$extension_failure_root/setup-local-secrets.sh" >/dev/null 2>&1; then
  printf 'expected existing-three generator failure\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$extension_failure_root/.local/secrets"/*)" ]]
[[ ! -e "$extension_failure_root/.local/secrets/user-password" ]]

printf 'setup-local-secrets tests passed: fresh=1 extend=1 overwrite=1 partial=4 invalid=5 links=7 junctions=%d failures=2\n' "$junction_count"
