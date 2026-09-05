#!/usr/bin/env bash
set -euo pipefail
umask 077

command -v dirname >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v pwd >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v realpath >/dev/null 2>&1 || { printf 'local TLS setup failed: required path tool is unavailable\n' >&2; exit 1; }
command -v date >/dev/null 2>&1 || { printf 'local TLS setup failed: required validation tool is unavailable\n' >&2; exit 1; }
command -v sed >/dev/null 2>&1 || { printf 'local TLS setup failed: required validation tool is unavailable\n' >&2; exit 1; }
command -v sort >/dev/null 2>&1 || { printf 'local TLS setup failed: required validation tool is unavailable\n' >&2; exit 1; }

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly LOCAL_DIR="$SCRIPT_DIR/.local"
readonly OUTPUT_DIR="$SCRIPT_DIR/.local/tls"
readonly CERTIFICATE="$OUTPUT_DIR/localhost.crt"
readonly PRIVATE_KEY="$OUTPUT_DIR/localhost.key"
temporary_dir=
created=()
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
  local path
  if [[ -n "$temporary_dir" && "$temporary_dir" == "$OUTPUT_DIR/".tls-build.* && -d "$temporary_dir" && ! -L "$temporary_dir" ]]; then
    rm -f -- "$temporary_dir/localhost.crt" "$temporary_dir/localhost.key" "$temporary_dir/openssl.cnf"
    rmdir -- "$temporary_dir" 2>/dev/null || true
  fi
  if [[ $completed != true ]]; then
    for path in "${created[@]}"; do
      [[ -n "$path" && "$path" == "$OUTPUT_DIR/"* ]] && rm -f -- "$path"
    done
  fi
}
trap cleanup EXIT INT TERM

normalized_extension() {
  openssl x509 -in "$1" -noout -ext "$2" 2>/dev/null \
    | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

validate_certificate() {
  local certificate=$1
  local key=$2
  local actual_extensions expected_extensions basic_constraints key_usage extended_key_usage subject_alt_name
  local subject issuer certificate_public key_public public_key_text rsa_bits signature_algorithm
  local not_before not_after not_before_epoch not_after_epoch now_epoch

  openssl x509 -in "$certificate" -noout >/dev/null 2>&1 \
    || fail 'certificate PEM validation failed'
  openssl pkey -in "$key" -noout -check >/dev/null 2>&1 \
    || fail 'private key PEM validation failed'

  actual_extensions=$(openssl x509 -in "$certificate" -noout -text 2>/dev/null \
    | sed -n '/X509v3 extensions:/,/Signature Algorithm:/p' \
    | sed -n -e 's/^            X509v3 \([^:]*\):.*/\1/p' -e 's/^            \([0-9][0-9.]*\):.*/OID:\1/p' \
    | LC_ALL=C sort) || fail 'certificate extension list read failed'
  expected_extensions=$'Basic Constraints\nExtended Key Usage\nKey Usage\nSubject Alternative Name'
  [[ $actual_extensions == "$expected_extensions" ]] \
    || fail 'certificate extension set is not exact'

  basic_constraints=$(normalized_extension "$certificate" basicConstraints) \
    || fail 'certificate basic constraints read failed'
  key_usage=$(normalized_extension "$certificate" keyUsage) \
    || fail 'certificate key usage read failed'
  extended_key_usage=$(normalized_extension "$certificate" extendedKeyUsage) \
    || fail 'certificate extended key usage read failed'
  subject_alt_name=$(normalized_extension "$certificate" subjectAltName) \
    || fail 'certificate SAN read failed'
  [[ $basic_constraints == $'X509v3 Basic Constraints: critical\nCA:FALSE' ]] \
    || fail 'certificate basic constraints must be exactly critical CA:FALSE'
  [[ $key_usage == $'X509v3 Key Usage: critical\nDigital Signature, Key Encipherment' ]] \
    || fail 'certificate key usage is not exact'
  [[ $extended_key_usage == $'X509v3 Extended Key Usage:\nTLS Web Server Authentication' ]] \
    || fail 'certificate extended key usage is not exact'
  [[ $subject_alt_name == $'X509v3 Subject Alternative Name:\nDNS:localhost' ]] \
    || fail 'certificate SAN must be exactly DNS:localhost'

  subject=$(openssl x509 -in "$certificate" -noout -subject -nameopt RFC2253 2>/dev/null) \
    || fail 'certificate subject read failed'
  issuer=$(openssl x509 -in "$certificate" -noout -issuer -nameopt RFC2253 2>/dev/null) \
    || fail 'certificate issuer read failed'
  [[ ${subject#subject=} == "${issuer#issuer=}" ]] || fail 'certificate is not self-issued'
  openssl verify -check_ss_sig -CAfile "$certificate" "$certificate" >/dev/null 2>&1 \
    || fail 'certificate self-signature validation failed'

  certificate_public=$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | openssl dgst -sha256) || fail 'certificate public key read failed'
  key_public=$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null \
    | openssl dgst -sha256) || fail 'private key public component read failed'
  [[ $certificate_public == "$key_public" ]] || fail 'certificate and private key do not match'

  public_key_text=$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -text -noout 2>/dev/null) || fail 'certificate key size read failed'
  rsa_bits=$(printf '%s\n' "$public_key_text" | sed -n 's/.*Public-Key: (\([0-9][0-9]*\) bit).*/\1/p' | head -n 1)
  [[ $rsa_bits =~ ^[0-9]+$ && $rsa_bits -ge 3072 ]] || fail 'certificate must use RSA 3072 or stronger'
  signature_algorithm=$(openssl x509 -in "$certificate" -noout -text 2>/dev/null \
    | sed -n 's/^[[:space:]]*Signature Algorithm:[[:space:]]*//p' \
    | head -n 1)
  [[ $signature_algorithm =~ ^sha(256|384|512)WithRSAEncryption$ ]] \
    || fail 'certificate signature algorithm must be SHA-256 or stronger RSA'

  not_before=$(openssl x509 -in "$certificate" -noout -startdate 2>/dev/null) \
    || fail 'certificate start date read failed'
  not_after=$(openssl x509 -in "$certificate" -noout -enddate 2>/dev/null) \
    || fail 'certificate end date read failed'
  not_before_epoch=$(date -u -d "${not_before#notBefore=}" +%s 2>/dev/null) \
    || fail 'certificate start date parse failed'
  not_after_epoch=$(date -u -d "${not_after#notAfter=}" +%s 2>/dev/null) \
    || fail 'certificate end date parse failed'
  now_epoch=$(date -u +%s)
  (( not_before_epoch <= now_epoch + 300 )) || fail 'certificate is not valid yet'
  (( not_after_epoch > now_epoch )) || fail 'certificate is expired'
  (( not_after_epoch - not_before_epoch > 0 && not_after_epoch - not_before_epoch <= 2592000 )) \
    || fail 'certificate validity exceeds 30 days'
}

command -v openssl >/dev/null 2>&1 || fail 'openssl is required'
openssl version >/dev/null 2>&1 || fail 'openssl version check failed'
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/tls"
mkdir -p -- "$OUTPUT_DIR"
validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/tls"
[[ ! -e "$CERTIFICATE" && ! -L "$CERTIFICATE" ]] || fail 'existing certificate blocks generation'
[[ ! -e "$PRIVATE_KEY" && ! -L "$PRIVATE_KEY" ]] || fail 'existing private key blocks generation'

temporary_dir=$(mktemp -d "$OUTPUT_DIR/.tls-build.XXXXXXXX")
temporary_certificate="$temporary_dir/localhost.crt"
temporary_key="$temporary_dir/localhost.key"
temporary_config="$temporary_dir/openssl.cnf"

printf '%s\n' \
  '[req]' \
  'distinguished_name=subject' \
  'prompt=no' \
  '[subject]' \
  'CN=localhost' > "$temporary_config"

openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
  -config "$temporary_config" \
  -subj '/CN=localhost' \
  -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
  -addext 'extendedKeyUsage=serverAuth' \
  -addext 'subjectAltName=DNS:localhost' \
  -addext 'subjectKeyIdentifier=none' \
  -keyout "$temporary_key" -out "$temporary_certificate" >/dev/null 2>&1 \
  || fail 'certificate generation failed'

validate_certificate "$temporary_certificate" "$temporary_key"

validate_directory_path "$LOCAL_DIR" "$SCRIPT_DIR/.local"
validate_directory_path "$OUTPUT_DIR" "$SCRIPT_DIR/.local/tls"
[[ ! -e "$CERTIFICATE" && ! -L "$CERTIFICATE" ]] || fail 'certificate path changed during generation'
[[ ! -e "$PRIVATE_KEY" && ! -L "$PRIVATE_KEY" ]] || fail 'private key path changed during generation'
mv -- "$temporary_certificate" "$CERTIFICATE"
created+=("$CERTIFICATE")
mv -- "$temporary_key" "$PRIVATE_KEY"
created+=("$PRIVATE_KEY")
rm -f -- "$temporary_config"
rmdir -- "$temporary_dir"
temporary_dir=
completed=true
printf 'created local TLS certificate: localhost.crt\n'
printf 'created local TLS private key: localhost.key\n'
