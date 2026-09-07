#!/usr/bin/env bash
#
# Entry point for the isolated Chromium the Keycloak browser E2E drives, and the
# runtime proof that the image it runs in is the image this checkout prepared.
#
# It runs inside the prepared browser image, which is the official Playwright
# Linux image pinned by immutable digest to the exact Playwright version this
# repository depends on, plus the exact `libnss3-tools` version that provides
# `certutil`. Everything this script needs is already in that image: it
# installs nothing, downloads nothing and reaches no registry, and it fails
# loudly rather than fetching anything if something is missing.
#
# It reaches the host only through the two loopback ports the relay forwards,
# and the host certificate store is never consulted: the self-signed
# `localhost` leaf is trusted the way Chromium on Linux actually trusts one, by
# adding it to a per-run NSS database as a trusted peer.
#
# Nothing here weakens TLS. There is no --ignore-certificate-errors, no SPKI
# allowlist, no ignoreHTTPSErrors and no hostname override; Chromium performs
# the full handshake and name check against `localhost` exactly as a browser
# would against a public site.
#
# Every path this creates lives under a per-run temporary HOME inside a
# container started with --rm, so the NSS database, the browser profile and any
# artifact directory cease to exist when the container does.
#
# Two modes, because an image's labels are metadata and metadata is writable by
# whoever builds an image:
#
#   verify  Proves, from inside a network-isolated, read-only, capability-less
#           container, that this image really contains what the runner was told
#           it contains, and that the runner started it under the confinement it
#           claims. Every failure is a fixed sentence naming the rule that
#           broke; no observed value is ever echoed back.
#   serve   The browser server itself. This is the default.
set -Eeuo pipefail

CERTIFICATE_PATH=/finguardops/tls/localhost.crt
SCRIPTS_DIR=/finguardops/scripts
PLAYWRIGHT_CORE_DIR=/finguardops/playwright-core
VERIFY_WORK_DIR=/finguardops/work
BROWSERS_DIR=/ms-playwright

# A closed set of fixed sentences. The argument is a rule name written in this
# file, never a captured command output, a path or an environment value, so an
# image that fails a check cannot use the rejection to print anything of its own
# choosing into the run log.
reject() {
  printf 'finguardops: browser image runtime verification failed (%s)\n' "$1" >&2
  exit 1
}

# Every expectation is required. An absent one would otherwise turn its check
# into a comparison against the empty string, which any image would satisfy.
expectation() {
  local name="$1"
  local value="${!name-}"
  if [[ -z "$value" ]]; then
    reject 'missing expectation'
  fi
  printf '%s' "$value"
}

# The account is the one the image declares and the one the kernel actually gave
# this process. `Config.User` is checked on the host from the image metadata;
# this asks the same question of the running process, which is the answer that
# decides what the browser can touch.
verify_account() {
  local expected
  expected="$(expectation FINGUARDOPS_VERIFY_USER)"
  [[ "$(id -un)" == "$expected" ]] || reject 'container account name'
  expected="$(expectation FINGUARDOPS_VERIFY_UID)"
  [[ "$(id -u)" == "$expected" ]] || reject 'container account uid'
  expected="$(expectation FINGUARDOPS_VERIFY_GID)"
  [[ "$(id -g)" == "$expected" ]] || reject 'container account gid'
}

# --cap-drop ALL and --security-opt no-new-privileges are runner flags, and a
# flag is a request until the kernel confirms it. /proc/self/status is the
# confirmation.
verify_privileges() {
  local status=/proc/self/status
  [[ "$(awk '$1 == "NoNewPrivs:" { print $2 }' "$status")" == '1' ]] || reject 'no-new-privileges'
  local capability
  for capability in CapInh CapPrm CapEff CapBnd; do
    local observed
    observed="$(awk -v key="${capability}:" '$1 == key { print $2 }' "$status")"
    [[ "$observed" == '0000000000000000' ]] || reject 'capability set'
  done
}

# The mount table, as the image itself sees it.
#
# This is not what decides whether a mount was asked for. That question is
# settled on the host, before this container is started, by comparing the
# daemon's own `HostConfig` record against the exact mount list the runner
# approves: from in here a `--tmpfs /dev/shm/x` looks exactly like the
# `/dev/shm` the daemon mounts itself, and a bind placed under `/proc` looks
# exactly like the kernel-virtual mounts runc creates there, so no rule written
# at this end could tell them apart.
#
# What this does is confirm, from inside, that the container the runner approved
# is the container this process is in: the root filesystem really is read-only,
# the two read-only mounts this image needs really are present and really are
# read-only, and the writable workspace really is a tmpfs. Everything else must
# be one of the mounts the container runtime creates for itself, named exactly.
# There is no prefix rule and no filesystem-type rule standing in for one, so a
# target that is merely *under* an allowed directory is not allowed.
verify_filesystem() {
  local mountinfo=/proc/self/mountinfo

  local root_options
  root_options="$(awk '$5 == "/" { print $6 }' "$mountinfo")"
  [[ ",${root_options}," == *',ro,'* ]] || reject 'read-only root'
  if touch /finguardops-runtime-write-probe 2> /dev/null; then
    rm -f /finguardops-runtime-write-probe
    reject 'read-only root'
  fi

  local scripts_options=''
  local core_options=''
  local work_seen=''
  local target options filesystem
  while read -r target options filesystem; do
    case "$target" in
      /) continue ;;
      "$SCRIPTS_DIR")
        scripts_options="$options"
        continue
        ;;
      "$PLAYWRIGHT_CORE_DIR")
        core_options="$options"
        continue
        ;;
      "$VERIFY_WORK_DIR")
        [[ "$filesystem" == 'tmpfs' ]] || reject 'unexpected mount'
        work_seen='yes'
        continue
        ;;
    esac
    # The complete set of targets the container runtime mounts on its own
    # account: the three files the daemon writes into every container, the
    # pseudo-filesystems, and the paths runc masks or remounts read-only.
    # Exact names, because an unexpected mount is a rule violation whatever it
    # is placed next to.
    case "$target" in
      /etc/resolv.conf | /etc/hostname | /etc/hosts) ;;
      /proc | /sys | /dev | /dev/pts | /dev/shm | /dev/mqueue | /dev/console | /run) ;;
      /proc/bus | /proc/fs | /proc/irq | /proc/sys | /proc/sysrq-trigger) ;;
      /proc/acpi | /proc/asound | /proc/interrupts | /proc/kcore | /proc/keys) ;;
      /proc/latency_stats | /proc/sched_debug | /proc/scsi | /proc/timer_list | /proc/timer_stats) ;;
      /sys/fs/cgroup | /sys/firmware | /sys/devices/virtual/powercap) ;;
      *) reject 'unexpected mount' ;;
    esac
  done < <(awk '{
    for (index_ = 7; index_ <= NF; index_++) {
      if ($index_ == "-") {
        print $5, $6, $(index_ + 1)
        next
      }
    }
    print $5, $6, "unknown"
  }' "$mountinfo")

  [[ -n "$scripts_options" ]] || reject 'missing scripts mount'
  [[ ",${scripts_options}," == *',ro,'* ]] || reject 'writable scripts mount'
  [[ -n "$core_options" ]] || reject 'missing playwright-core mount'
  [[ ",${core_options}," == *',ro,'* ]] || reject 'writable playwright-core mount'
  [[ -n "$work_seen" ]] || reject 'missing work mount'

  # Belt and braces over the allowlist above: the source side of every mount is
  # searched for the shapes that would matter even if one of them ever reached
  # an allowed target.
  local forbidden='(docker\.sock|/var/run/docker|/run/docker/|\.docker/|\.ssh/|\.aws/|\.npmrc|/\.git/|/\.local/|credential|secret|\.key( |$)|\.pem( |$)|\.p12( |$)|\.pfx( |$))'
  if grep -Eqi "$forbidden" "$mountinfo"; then
    reject 'forbidden mount source'
  fi
}

# --network none, confirmed from inside. Loopback is the only interface, so
# nothing this container does can reach a registry, a package archive or the
# host, however it was invoked.
verify_isolation() {
  local interfaces
  interfaces="$(awk 'NR > 2 { sub(/:.*/, "", $1); print $1 }' /proc/net/dev | LC_ALL=C sort | tr '\n' ' ')"
  [[ "${interfaces% }" == 'lo' ]] || reject 'network interface'
}

# The tool that writes the NSS trust entry is the exact package version this
# checkout pins, it is the binary that package owns, and the NSS runtime under
# it is the same version. Asked of dpkg's database rather than of a label.
verify_packages() {
  local expected
  expected="$(expectation FINGUARDOPS_VERIFY_LIBNSS3_TOOLS_VERSION)"
  [[ "$(dpkg-query -W -f='${Version}' libnss3-tools 2> /dev/null)" == "$expected" ]] || reject 'libnss3-tools version'
  expected="$(expectation FINGUARDOPS_VERIFY_LIBNSS3_VERSION)"
  [[ "$(dpkg-query -W -f='${Version}' libnss3 2> /dev/null)" == "$expected" ]] || reject 'libnss3 version'
  [[ "$(command -v certutil 2> /dev/null)" == '/usr/bin/certutil' ]] || reject 'certutil location'
  [[ "$(dpkg-query -S /usr/bin/certutil 2> /dev/null)" == 'libnss3-tools: /usr/bin/certutil' ]] || reject 'certutil provenance'
}

# Present and correct is not the same as working. This creates and reads the
# same kind of per-run NSS database the browser container will, on a tmpfs that
# exists only for this container.
verify_certutil_execution() {
  local database="$VERIFY_WORK_DIR/nssdb"
  mkdir -p "$database" 2> /dev/null || reject 'certutil workspace'
  certutil -N -d "sql:$database" --empty-password > /dev/null 2>&1 || reject 'certutil execution'
  certutil -L -d "sql:$database" > /dev/null 2>&1 || reject 'certutil execution'
  rm -rf "$database"
}

verify_node() {
  local expected
  expected="$(expectation FINGUARDOPS_VERIFY_NODE_VERSION)"
  [[ "$(node -v 2> /dev/null)" == "$expected" ]] || reject 'node version'
}

verify_browser_executable() {
  local path="$1"
  local expected_build="$2"
  local presence_rule="$3"
  local build_rule="$4"
  [[ -f "$path" && -x "$path" ]] || reject "$presence_rule"
  local reported
  reported="$("$path" --version 2> /dev/null | tr -d '\r' | sed -e 's/[[:space:]]*$//')"
  [[ "$reported" == "$expected_build" ]] || reject "$build_rule"
}

# The strongest binding this image has to this checkout.
#
# The browsers baked into the image are compared against the revisions the
# installed playwright-core itself declares, computed here from the read-only
# mounted package rather than passed in on the command line, so an image
# carrying a different Chromium cannot pass by being labelled well. The
# executables are then proven to exist, to be executable, and to run.
verify_playwright() {
  local expected_version
  expected_version="$(expectation FINGUARDOPS_VERIFY_PLAYWRIGHT_VERSION)"
  local core_version
  core_version="$(node -p "require('${PLAYWRIGHT_CORE_DIR}/package.json').version" 2> /dev/null)"
  [[ "$core_version" == "$expected_version" ]] || reject 'playwright-core version'

  local expected_inventory
  expected_inventory="$(node -e '
const { browsers } = require(process.argv[1] + "/browsers.json");
process.stdout.write(
  browsers
    .filter((browser) => browser.installByDefault)
    .map((browser) => browser.name.replace(/-/gu, "_") + "-" + browser.revision)
    .sort()
    .join(" "),
);
' "$PLAYWRIGHT_CORE_DIR" 2> /dev/null)"
  [[ -n "$expected_inventory" ]] || reject 'browser registry'

  local actual_inventory
  actual_inventory="$(ls -1 "$BROWSERS_DIR" 2> /dev/null | LC_ALL=C sort | tr '\n' ' ')"
  [[ "${actual_inventory% }" == "$expected_inventory" ]] || reject 'browser inventory'

  local revision
  revision="$(node -p "require('${PLAYWRIGHT_CORE_DIR}/browsers.json').browsers.find((browser) => browser.name === 'chromium').revision" 2> /dev/null)"
  [[ "$revision" =~ ^[0-9]+$ ]] || reject 'chromium revision'
  local shell_revision
  shell_revision="$(node -p "require('${PLAYWRIGHT_CORE_DIR}/browsers.json').browsers.find((browser) => browser.name === 'chromium-headless-shell').revision" 2> /dev/null)"
  [[ "$shell_revision" =~ ^[0-9]+$ ]] || reject 'chromium revision'

  local expected_build
  expected_build="$(expectation FINGUARDOPS_VERIFY_CHROMIUM_VERSION)"
  verify_browser_executable \
    "$BROWSERS_DIR/chromium-${revision}/chrome-linux64/chrome" \
    "$expected_build" \
    'chromium executable' \
    'chromium build'
  verify_browser_executable \
    "$BROWSERS_DIR/chromium_headless_shell-${shell_revision}/chrome-headless-shell-linux64/chrome-headless-shell" \
    "$expected_build" \
    'headless shell executable' \
    'headless shell build'
}

verify_image_runtime() {
  verify_account
  verify_privileges
  verify_filesystem
  verify_isolation
  verify_packages
  verify_certutil_execution
  verify_node
  verify_playwright
  printf 'finguardops: browser image runtime verification passed\n'
}

# The browser server is this repository's own installed playwright-core, mounted
# in read-only, not something fetched at run time. Resolving the CLI through the
# registry would silently start a newer server than the client, which is a
# protocol mismatch rather than a test result. playwright-core is pure
# JavaScript, so the copy installed on the host runs here unchanged, and the
# browsers it drives are the ones baked into this pinned image.
serve_browser() {
  local browser_port="${FINGUARDOPS_BROWSER_PORT:?FINGUARDOPS_BROWSER_PORT is required}"
  local expected_version="${FINGUARDOPS_PLAYWRIGHT_VERSION:?FINGUARDOPS_PLAYWRIGHT_VERSION is required}"

  local actual_version
  actual_version="$(node -p "require('${PLAYWRIGHT_CORE_DIR}/package.json').version")"
  if [[ "$actual_version" != "$expected_version" ]]; then
    echo "finguardops: browser server ${actual_version} does not match client ${expected_version}" >&2
    exit 1
  fi

  # Prepared, not installed. If the image were ever run without having been
  # built by frontend/Dockerfile.playwright-e2e, this stops the run instead of
  # quietly turning into the apt step this design exists to remove.
  if ! command -v certutil > /dev/null 2>&1; then
    echo "finguardops: certutil is missing from the prepared browser image" >&2
    exit 1
  fi

  local run_home
  run_home="$(mktemp -d /tmp/finguardops-browser-XXXXXXXXXX)"
  export HOME="$run_home"
  local nss_db="$HOME/.pki/nssdb"
  local artifacts_dir="$HOME/artifacts"

  # Re-validated here, in the same container and immediately before it is
  # trusted, so the bytes added to the store are the bytes just checked.
  node "$SCRIPTS_DIR/verify-localhost-certificate.mjs" "$CERTIFICATE_PATH"

  mkdir -p "$nss_db" "$artifacts_dir"
  certutil -N -d "sql:$nss_db" --empty-password

  # `P,,` is the NSS trust attribute for a peer certificate trusted for SSL, and
  # it is how Chromium on Linux is meant to be told about a self-signed *server*
  # leaf. It grants nothing beyond that: no CA role, no email trust, no code
  # signing, and no authority to vouch for any other name than its own SAN.
  certutil -A -n finguardops-localhost -t "P,," -d "sql:$nss_db" -i "$CERTIFICATE_PATH"
  certutil -L -d "sql:$nss_db"

  # The application and the Authorization Server must answer on the container's
  # own loopback under their real names and ports. The relay takes no arguments
  # and forwards exactly 5173 and 8443, so the Backend management listener and
  # the Keycloak HTTP listener stay exactly as unreachable from here as they are
  # from anywhere else.
  node "$SCRIPTS_DIR/loopback-forwarder.mjs" &

  echo "finguardops: browser server ${actual_version} starting on ${browser_port}"
  exec node "$PLAYWRIGHT_CORE_DIR/cli.js" run-server \
    --host 0.0.0.0 \
    --port "$browser_port" \
    --artifacts-dir "$artifacts_dir"
}

if [[ $# -gt 1 ]]; then
  echo 'finguardops: this entry point takes at most one mode argument' >&2
  exit 1
fi

case "${1:-serve}" in
  verify) verify_image_runtime ;;
  serve) serve_browser ;;
  *)
    echo 'finguardops: unknown mode' >&2
    exit 1
    ;;
esac
