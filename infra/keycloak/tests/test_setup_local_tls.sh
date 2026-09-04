#!/usr/bin/env bash
set -euo pipefail

readonly TEST_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/.local/test-tls-$$
readonly SCRIPT_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/setup-local-tls.sh
cleanup() { rm -rf -- "$TEST_ROOT"; }
trap cleanup EXIT
mkdir -p -- "$TEST_ROOT"
cp -- "$SCRIPT_SOURCE" "$TEST_ROOT/setup-local-tls.sh"

output=$(bash "$TEST_ROOT/setup-local-tls.sh" 2>&1)
readonly CERTIFICATE="$TEST_ROOT/.local/tls/localhost.crt"
readonly PRIVATE_KEY="$TEST_ROOT/.local/tls/localhost.key"
[[ -f "$CERTIFICATE" && -f "$PRIVATE_KEY" ]]
san=$(openssl x509 -in "$CERTIFICATE" -noout -ext subjectAltName)
[[ $san == *'DNS:localhost'* ]]
certificate_public=$(openssl x509 -in "$CERTIFICATE" -pubkey -noout | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256)
key_public=$(openssl pkey -in "$PRIVATE_KEY" -pubout -outform DER 2>/dev/null | openssl dgst -sha256)
[[ $certificate_public == "$key_public" ]]
openssl x509 -in "$CERTIFICATE" -checkend 1 -noout >/dev/null
private_marker=$(head -n 1 "$PRIVATE_KEY")
[[ $output != *"$private_marker"* ]]

before=$(sha256sum "$CERTIFICATE" "$PRIVATE_KEY")
if bash "$TEST_ROOT/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected overwrite refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$CERTIFICATE" "$PRIVATE_KEY")" ]]

readonly SYMLINK_CASES="$TEST_ROOT/symlink-cases"
mkdir -p -- "$SYMLINK_CASES"

case_root="$SYMLINK_CASES/local-link"
external="$SYMLINK_CASES/local-link-target"
mkdir -p -- "$case_root" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local"
if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
  printf 'expected .local symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$SYMLINK_CASES/output-link"
external="$SYMLINK_CASES/output-link-target"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local/tls"
if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
  printf 'expected TLS directory symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

for artifact in localhost.crt localhost.key; do
  case_root="$SYMLINK_CASES/artifact-${artifact##*.}"
  external="$SYMLINK_CASES/artifact-${artifact##*.}-target"
  mkdir -p -- "$case_root/.local/tls" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  printf 'NeverPrintSymlinkPrivateMaterial' > "$external/target"
  before=$(sha256sum "$external/target")
  ln -s -- "$external/target" "$case_root/.local/tls/$artifact"
  if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS artifact symlink rejection\n' >&2
    exit 1
  fi
  [[ $before == "$(sha256sum "$external/target")" ]]
  [[ $output != *NeverPrintSymlinkPrivateMaterial* ]]
done

case_root="$SYMLINK_CASES/prefix-boundary"
external="$case_root/.local-evil"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local/tls"
if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
  printf 'expected TLS prefix boundary rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

case_root="$SYMLINK_CASES/partial-output-link"
external="$SYMLINK_CASES/partial-output-target"
mkdir -p -- "$case_root/.local" "$external/existing-directory"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local/tls"
if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
  printf 'expected partial TLS directory symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

rm -f -- "$PRIVATE_KEY"
certificate_before=$(sha256sum "$CERTIFICATE")
if bash "$TEST_ROOT/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected partial-state refusal\n' >&2
  exit 1
fi
[[ $certificate_before == "$(sha256sum "$CERTIFICATE")" && ! -e "$PRIVATE_KEY" ]]

rm -rf -- "$TEST_ROOT/.local/tls"
mkdir -p -- "$TEST_ROOT/fake-bin"
cat > "$TEST_ROOT/fake-bin/openssl" <<'FAKE'
#!/usr/bin/env bash
if [[ ${1:-} == version ]]; then
  exit 0
fi
if [[ ${1:-} == req ]]; then
  shift
  while (( $# )); do
    case "$1" in
      -keyout) shift; key=$1 ;;
      -out) shift; certificate=$1 ;;
    esac
    shift
  done
  printf 'partial-private-material' > "$key"
  printf 'partial-certificate-material' > "$certificate"
fi
exit 1
FAKE
chmod +x "$TEST_ROOT/fake-bin/openssl"
if PATH="$TEST_ROOT/fake-bin:$PATH" bash "$TEST_ROOT/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected injected TLS generation failure\n' >&2
  exit 1
fi
[[ ! -e "$CERTIFICATE" && ! -e "$PRIVATE_KEY" ]]
[[ -z $(find "$TEST_ROOT/.local/tls" -mindepth 1 -print -quit) ]]

dirname() { printf '%s\n' "${1%/*}"; }
export -f dirname
if PATH="$TEST_ROOT/empty-path" /usr/bin/bash "$TEST_ROOT/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected openssl absence failure\n' >&2
  exit 1
fi

printf 'setup-local-tls tests passed\n'
