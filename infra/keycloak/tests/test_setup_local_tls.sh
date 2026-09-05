#!/usr/bin/env bash
set -euo pipefail

readonly TEST_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/.local/test-tls-$$
readonly SCRIPT_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/setup-local-tls.sh
readonly REAL_OPENSSL=$(command -v openssl)
cleanup() { rm -rf -- "$TEST_ROOT"; }
trap cleanup EXIT

new_case() {
  local name=$1
  local root="$TEST_ROOT/$name"
  mkdir -p -- "$root"
  cp -- "$SCRIPT_SOURCE" "$root/setup-local-tls.sh"
  printf '%s\n' "$root"
}

normalize_extension() {
  openssl x509 -in "$1" -noout -ext "$2" 2>/dev/null \
    | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

mkdir -p -- "$TEST_ROOT"

fresh_root=$(new_case fresh)
output=$(bash "$fresh_root/setup-local-tls.sh" 2>&1)
certificate="$fresh_root/.local/tls/localhost.crt"
private_key="$fresh_root/.local/tls/localhost.key"
[[ -f "$certificate" && ! -L "$certificate" && -f "$private_key" && ! -L "$private_key" ]]

extension_names=$(openssl x509 -in "$certificate" -noout -text 2>/dev/null \
  | sed -n '/X509v3 extensions:/,/Signature Algorithm:/p' \
  | sed -n -e 's/^            X509v3 \([^:]*\):.*/\1/p' -e 's/^            \([0-9][0-9.]*\):.*/OID:\1/p' \
  | LC_ALL=C sort)
[[ $extension_names == $'Basic Constraints\nExtended Key Usage\nKey Usage\nSubject Alternative Name' ]]
[[ $(normalize_extension "$certificate" basicConstraints) == $'X509v3 Basic Constraints: critical\nCA:FALSE' ]]
[[ $(normalize_extension "$certificate" keyUsage) == $'X509v3 Key Usage: critical\nDigital Signature, Key Encipherment' ]]
[[ $(normalize_extension "$certificate" extendedKeyUsage) == $'X509v3 Extended Key Usage:\nTLS Web Server Authentication' ]]
[[ $(normalize_extension "$certificate" subjectAltName) == $'X509v3 Subject Alternative Name:\nDNS:localhost' ]]
openssl verify -check_ss_sig -CAfile "$certificate" "$certificate" >/dev/null 2>&1

certificate_public=$(openssl x509 -in "$certificate" -pubkey -noout \
  | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256)
key_public=$(openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null | openssl dgst -sha256)
[[ $certificate_public == "$key_public" ]]
rsa_bits=$(openssl x509 -in "$certificate" -pubkey -noout \
  | openssl pkey -pubin -text -noout 2>/dev/null \
  | sed -n 's/.*Public-Key: (\([0-9][0-9]*\) bit).*/\1/p' | head -n 1)
[[ $rsa_bits -ge 3072 ]]
signature_algorithm=$(openssl x509 -in "$certificate" -noout -text 2>/dev/null \
  | sed -n 's/^[[:space:]]*Signature Algorithm:[[:space:]]*//p' | head -n 1)
[[ $signature_algorithm =~ ^sha(256|384|512)WithRSAEncryption$ ]]
not_before=$(openssl x509 -in "$certificate" -noout -startdate)
not_after=$(openssl x509 -in "$certificate" -noout -enddate)
not_before_epoch=$(date -u -d "${not_before#notBefore=}" +%s)
not_after_epoch=$(date -u -d "${not_after#notAfter=}" +%s)
(( not_after_epoch - not_before_epoch > 0 && not_after_epoch - not_before_epoch <= 2592000 ))
private_marker=$(head -n 1 "$private_key")
[[ $output != *"$private_marker"* ]]

before=$(sha256sum "$certificate" "$private_key")
if output=$(bash "$fresh_root/setup-local-tls.sh" 2>&1); then
  printf 'expected overwrite refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$certificate" "$private_key")" ]]
[[ $output != *"$private_marker"* ]]

readonly LINK_CASES="$TEST_ROOT/link-cases"
mkdir -p -- "$LINK_CASES"

case_root="$LINK_CASES/local-link"
external="$LINK_CASES/local-link-target"
mkdir -p -- "$case_root" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local"
if bash "$case_root/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected .local symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

junction_count=0
if command -v powershell.exe >/dev/null 2>&1 \
  && (command -v cygpath >/dev/null 2>&1 || command -v wslpath >/dev/null 2>&1); then
  case_root="$LINK_CASES/output-junction"
  external="$LINK_CASES/output-junction-target"
  mkdir -p -- "$case_root/.local" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  if command -v cygpath >/dev/null 2>&1; then
    FINGUARDOPS_JUNCTION_LINK=$(cygpath -w "$case_root/.local/tls")
    FINGUARDOPS_JUNCTION_TARGET=$(cygpath -w "$external")
  else
    FINGUARDOPS_JUNCTION_LINK=$(wslpath -w "$case_root/.local/tls")
    FINGUARDOPS_JUNCTION_TARGET=$(wslpath -w "$external")
  fi
  powershell.exe -NoProfile -NonInteractive -Command \
    "New-Item -ItemType Junction -Path '$FINGUARDOPS_JUNCTION_LINK' -Target '$FINGUARDOPS_JUNCTION_TARGET' | Out-Null"
  if bash "$case_root/setup-local-tls.sh" >/dev/null 2>&1; then
    printf 'expected TLS directory junction rejection\n' >&2
    exit 1
  fi
  [[ -z $(find "$external" -type f -print -quit) ]]
  powershell.exe -NoProfile -NonInteractive -Command \
    "[IO.Directory]::Delete('$FINGUARDOPS_JUNCTION_LINK', \$false)"
  junction_count=1
fi

case_root="$LINK_CASES/output-link"
external="$LINK_CASES/output-link-target"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local/tls"
if bash "$case_root/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected TLS directory symlink rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

for artifact in localhost.crt localhost.key; do
  case_root="$LINK_CASES/artifact-${artifact##*.}"
  external="$LINK_CASES/artifact-${artifact##*.}-target"
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

case_root="$LINK_CASES/prefix-boundary"
external="$case_root/.local-evil"
mkdir -p -- "$case_root/.local" "$external"
cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
ln -s -- "$external" "$case_root/.local/tls"
if bash "$case_root/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected TLS prefix boundary rejection\n' >&2
  exit 1
fi
[[ -z $(find "$external" -type f -print -quit) ]]

partial_root=$(new_case partial)
mkdir -p -- "$partial_root/.local/tls"
printf 'existing-certificate' > "$partial_root/.local/tls/localhost.crt"
before=$(sha256sum "$partial_root/.local/tls/localhost.crt")
if bash "$partial_root/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected partial-state refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$partial_root/.local/tls/localhost.crt")" ]]
[[ ! -e "$partial_root/.local/tls/localhost.key" ]]

mutation_root="$TEST_ROOT/mutations"
mkdir -p -- "$mutation_root/fake-bin"
cat > "$mutation_root/fake-bin/openssl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
real=${REAL_OPENSSL_BIN:?}
mutation=${TLS_MUTATION:?}
[[ ${1:-} == req ]] || exec "$real" "$@"

key=
certificate=
while (( $# )); do
  case "$1" in
    -keyout) shift; key=$1 ;;
    -out) shift; certificate=$1 ;;
  esac
  shift
done
[[ -n "$key" && -n "$certificate" ]]
work=${certificate%/*}
config="$work/mutation.cnf"
printf '%s\n' '[req]' 'distinguished_name=subject' 'prompt=no' '[subject]' 'CN=localhost' > "$config"

generate_self_signed() {
  "$real" req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
    -config "$config" -subj '/CN=localhost' "$@" \
    -addext 'subjectKeyIdentifier=none' -keyout "$key" -out "$certificate"
}

case "$mutation" in
  ca-true)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:TRUE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost'
    ;;
  eku-missing)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'subjectAltName=DNS:localhost'
    ;;
  eku-extra)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth,clientAuth' \
      -addext 'subjectAltName=DNS:localhost'
    ;;
  key-usage-missing)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost'
    ;;
  key-usage-extra)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment,keyAgreement' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost'
    ;;
  san-extra)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost,DNS:example.invalid'
    ;;
  extension-extra)
    "$real" req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
      -config "$config" -subj '/CN=localhost' \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost' \
      -keyout "$key" -out "$certificate"
    ;;
  extension-oid-extra)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost' \
      -addext '1.2.3.4=ASN1:UTF8String:unexpected'
    ;;
  rsa-2048)
    "$real" req -x509 -newkey rsa:2048 -sha256 -nodes -days 30 \
      -config "$config" -subj '/CN=localhost' \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost' \
      -addext 'subjectKeyIdentifier=none' -keyout "$key" -out "$certificate"
    ;;
  sha1)
    "$real" req -x509 -newkey rsa:3072 -sha1 -nodes -days 30 \
      -config "$config" -subj '/CN=localhost' \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost' \
      -addext 'subjectKeyIdentifier=none' -keyout "$key" -out "$certificate"
    ;;
  key-mismatch)
    generate_self_signed \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost'
    "$real" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$work/unrelated.key"
    mv -- "$work/unrelated.key" "$key"
    ;;
  not-self-signed)
    "$real" req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
      -config "$config" -subj '/CN=local-test-ca' \
      -addext 'basicConstraints=critical,CA:TRUE' \
      -keyout "$work/ca.key" -out "$work/ca.crt"
    "$real" req -new -newkey rsa:3072 -sha256 -nodes \
      -config "$config" -subj '/CN=localhost' -keyout "$key" -out "$work/leaf.csr"
    printf '%s\n' \
      '[leaf]' \
      'basicConstraints=critical,CA:FALSE' \
      'keyUsage=critical,digitalSignature,keyEncipherment' \
      'extendedKeyUsage=serverAuth' \
      'subjectAltName=DNS:localhost' \
      'subjectKeyIdentifier=none' \
      'authorityKeyIdentifier=none' > "$work/leaf.cnf"
    "$real" x509 -req -in "$work/leaf.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" \
      -set_serial 1 -days 30 -sha256 -extfile "$work/leaf.cnf" -extensions leaf -out "$certificate"
    ;;
  *) exit 2 ;;
esac
FAKE
chmod +x "$mutation_root/fake-bin/openssl"

for mutation in ca-true eku-missing eku-extra key-usage-missing key-usage-extra san-extra extension-extra extension-oid-extra rsa-2048 sha1 key-mismatch not-self-signed; do
  case_root=$(new_case "mutation-$mutation")
  if output=$(REAL_OPENSSL_BIN="$REAL_OPENSSL" TLS_MUTATION="$mutation" \
    PATH="$mutation_root/fake-bin:$PATH" bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS mutation rejection: %s\n' "$mutation" >&2
    exit 1
  fi
  [[ ! -e "$case_root/.local/tls/localhost.crt" && ! -e "$case_root/.local/tls/localhost.key" ]]
  [[ $output != *'BEGIN PRIVATE KEY'* ]]
done

failure_root=$(new_case generation-failure)
mkdir -p -- "$failure_root/fake-bin"
cat > "$failure_root/fake-bin/openssl" <<'FAKE'
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
chmod +x "$failure_root/fake-bin/openssl"
if PATH="$failure_root/fake-bin:$PATH" bash "$failure_root/setup-local-tls.sh" >/dev/null 2>&1; then
  printf 'expected injected TLS generation failure\n' >&2
  exit 1
fi
[[ ! -e "$failure_root/.local/tls/localhost.crt" && ! -e "$failure_root/.local/tls/localhost.key" ]]
[[ -z $(find "$failure_root/.local/tls" -mindepth 1 -print -quit) ]]

printf 'setup-local-tls tests passed: exact=1 overwrite=1 partial=1 links=5 junctions=%d mutations=12 failure=1\n' "$junction_count"
