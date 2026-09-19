#!/usr/bin/env bash
set -euo pipefail

readonly TEST_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/.local/test-tls-$$
readonly SCRIPT_SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/setup-local-tls.sh
readonly REAL_OPENSSL=$(command -v openssl)
readonly EXPECTED_SUBJECT='CN=localhost'
readonly PEM_MARKERS=(
  '-----BEGIN CERTIFICATE-----'
  '-----BEGIN PRIVATE KEY-----'
  '-----BEGIN RSA PRIVATE KEY-----'
  '-----BEGIN ENCRYPTED PRIVATE KEY-----'
)
SYMLINK_PROBE_ROOT=
declare -A CASE_CATEGORY=()
declare -A CASE_RESULT=()
declare -a EXPECTED_CASES=()

registry_error() {
  printf 'case registry failed: %s\n' "$1" >&2
  return 1
}

reset_case_registry() {
  CASE_CATEGORY=()
  CASE_RESULT=()
  EXPECTED_CASES=()
}

register_case() {
  local name=$1 category=$2
  case $category in
    functional|symlink|junction|mutation|partial|generation-failure) ;;
    *) registry_error invalid-category; return 1 ;;
  esac
  [[ -z ${CASE_CATEGORY[$name]+registered} ]] || { registry_error duplicate-case-name; return 1; }
  CASE_CATEGORY[$name]=$category
  EXPECTED_CASES+=("$name")
}

record_case_result() {
  local name=$1 category=$2 result=$3
  [[ -n ${CASE_CATEGORY[$name]+registered} ]] || { registry_error unexpected-case-name; return 1; }
  [[ ${CASE_CATEGORY[$name]} == "$category" ]] || { registry_error wrong-category; return 1; }
  case $result in PASS|FAIL|DEFERRED|SKIPPED) ;; *) registry_error invalid-result; return 1 ;; esac
  [[ -z ${CASE_RESULT[$name]+recorded} ]] || { registry_error duplicate-result; return 1; }
  CASE_RESULT[$name]=$result
}

registry_count() {
  local category=$1 result=$2 name count=0
  for name in "${EXPECTED_CASES[@]}"; do
    [[ $category == all || ${CASE_CATEGORY[$name]} == "$category" ]] || continue
    [[ ${CASE_RESULT[$name]-} == "$result" ]] && count=$((count + 1))
  done
  printf '%d\n' "$count"
}

verify_registry_complete() {
  local name
  for name in "${EXPECTED_CASES[@]}"; do
    [[ -n ${CASE_RESULT[$name]+recorded} ]] || { registry_error expected-case-missing; return 1; }
  done
  ((${#CASE_RESULT[@]} == ${#EXPECTED_CASES[@]})) || { registry_error unexpected-case-name; return 1; }
}

verify_summary_count() {
  local category=$1 result=$2 expected=$3 actual
  actual=$(registry_count "$category" "$result")
  [[ $actual -eq $expected ]] || { registry_error summary-counter-mismatch; return 1; }
}

verify_case_result() {
  local name=$1 expected=$2
  [[ -n ${CASE_RESULT[$name]+recorded} ]] || { registry_error expected-case-missing; return 1; }
  [[ ${CASE_RESULT[$name]} == "$expected" ]] || { registry_error wrong-result; return 1; }
}

expect_registry_failure() {
  local expected=$1 output
  shift
  if output=$("$@" 2>&1); then
    printf 'registry regression failed: expected rejection\n' >&2
    return 1
  fi
  [[ $output == "case registry failed: $expected" ]] || {
    printf 'registry regression failed: wrong fixed identity\n' >&2
    return 1
  }
}

run_registry_regressions() {
  reset_case_registry
  register_case one functional
  expect_registry_failure duplicate-case-name register_case one functional

  reset_case_registry
  expect_registry_failure unexpected-case-name record_case_result unknown functional PASS

  reset_case_registry
  register_case omitted functional
  expect_registry_failure expected-case-missing verify_registry_complete

  reset_case_registry
  register_case categorized mutation
  expect_registry_failure wrong-category record_case_result categorized partial PASS

  reset_case_registry
  register_case duplicate functional
  record_case_result duplicate functional PASS
  expect_registry_failure duplicate-result record_case_result duplicate functional PASS

  reset_case_registry
  register_case deferred functional
  record_case_result deferred functional DEFERRED
  expect_registry_failure wrong-result verify_case_result deferred PASS

  reset_case_registry
  register_case counted functional
  record_case_result counted functional PASS
  expect_registry_failure summary-counter-mismatch verify_summary_count all PASS 2

  reset_case_registry
  register_case common functional
  register_case symlink-case symlink
  [[ ${#EXPECTED_CASES[@]} -eq 2 ]]
  register_case junction-case junction
  [[ ${#EXPECTED_CASES[@]} -eq 3 ]]
  printf 'case registry regressions passed: passed=8 failed=0\n'
}
cleanup() {
  local original_exit=$? cleanup_exit=0
  trap - EXIT
  if [[ -n $SYMLINK_PROBE_ROOT ]]; then
    if ! rm -rf -- "$SYMLINK_PROBE_ROOT" >/dev/null 2>&1; then
      printf 'symlink capability probe cleanup failed: cleanup-command-failed\n' >&2
      cleanup_exit=1
    fi
    if [[ -e $SYMLINK_PROBE_ROOT || -L $SYMLINK_PROBE_ROOT ]]; then
      printf 'symlink capability probe cleanup failed: cleanup-residue\n' >&2
      cleanup_exit=1
    fi
  fi
  rm -rf -- "$TEST_ROOT" >/dev/null 2>&1 || cleanup_exit=1
  if (( original_exit != 0 )); then
    exit "$original_exit"
  fi
  exit "$cleanup_exit"
}
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

assert_no_pem_markers() {
  local output=$1 marker
  for marker in "${PEM_MARKERS[@]}"; do
    [[ $output != *"$marker"* ]] || {
      printf 'unexpected PEM marker in command output\n' >&2
      exit 1
    }
  done
}

process_environment_snapshot() {
  local name value_hash
  export -p | LC_ALL=C sort | sha256sum | sed 's/[[:space:]].*//'
  for name in PATH MSYS2_ARG_CONV_EXCL MSYS_NO_PATHCONV OPENSSL_CONF OPENSSL_CONF_INCLUDE; do
    if [[ -v $name ]]; then
      value_hash=$(printf '%s' "${!name}" | sha256sum | sed 's/[[:space:]].*//')
      printf '%s=present:%s\n' "$name" "$value_hash"
    else
      printf '%s=absent\n' "$name"
    fi
  done
}

windows_environment_snapshot() {
  if ! command -v powershell.exe >/dev/null 2>&1; then
    printf 'unavailable\n'
    return
  fi
  powershell.exe -NoProfile -NonInteractive -Command '
    function Get-Hash([string]$value) {
      $sha = [Security.Cryptography.SHA256]::Create()
      try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($value)))).Replace("-", "").ToLowerInvariant() }
      finally { $sha.Dispose() }
    }
    foreach ($target in @([EnvironmentVariableTarget]::User, [EnvironmentVariableTarget]::Machine)) {
      $values = [Environment]::GetEnvironmentVariables($target)
      $canonical = @($values.Keys | ForEach-Object { [string]$_ } | Sort-Object | ForEach-Object { $_ + "=" + [string]$values[$_] }) -join "`n"
      $flags = foreach ($name in @("Path", "PATH", "MSYS2_ARG_CONV_EXCL", "MSYS_NO_PATHCONV", "OPENSSL_CONF", "OPENSSL_CONF_INCLUDE")) {
        $name + "=" + [int]$values.Contains($name)
      }
      $target.ToString() + "=" + (Get-Hash $canonical) + ";" + ($flags -join ",")
    }
  ' | sed 's/\r$//'
}

readonly PROCESS_ENVIRONMENT_BEFORE=$(process_environment_snapshot)
readonly WINDOWS_ENVIRONMENT_BEFORE=$(windows_environment_snapshot)

case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*) readonly TEST_PLATFORM=windows-git-bash ;;
  Linux*)
    if [[ -r /proc/sys/kernel/osrelease ]] && grep -qi microsoft /proc/sys/kernel/osrelease; then
      readonly TEST_PLATFORM=wsl
    else
      readonly TEST_PLATFORM=linux
    fi
    ;;
  *)
    printf 'symlink capability probe failed: unsupported platform\n' >&2
    exit 1
    ;;
esac

repository_root=$(cd -- "$(dirname -- "$SCRIPT_SOURCE")/../.." 2>/dev/null && pwd -P 2>/dev/null) || {
  printf 'symlink capability probe failed: repository boundary unavailable\n' >&2
  exit 1
}

probe_symbolic_link() {
  local kind=$1 platform=$2 root=$3 source=$4 parent=$5 destination=$6
  local source_present=false parent_present=false destination_absent=false
  local ln_exit=not-run test_link=false ordinary=false reparse=not-applicable
  local destination_present=false destination_windows= probe_output= preliminary_state=ERROR
  local source_find_output= source_find_stderr= source_find_exit=not-run
  local destination_find_output= destination_find_stderr= destination_find_exit=not-run
  local native_stderr_file="$root/.probe-native-stderr"
  local cleanup_command_failed=false cleanup_residue=false

  PROBE_STATE=ERROR
  PROBE_ERROR_IDENTITY=
  PROBE_ERROR_MESSAGE=
  PROBE_CLEANUP_ERROR_IDENTITY=
  case $kind in
    directory|file) ;;
    *) PROBE_ERROR_IDENTITY=invalid-kind ;;
  esac
  case "$source|$parent|$destination" in
    "$root/"*"|$root/"*"|$root/"*) ;;
    *) [[ -n $PROBE_ERROR_IDENTITY ]] || PROBE_ERROR_IDENTITY=unsafe-probe-path ;;
  esac

  if [[ ! -e $source && ! -L $source ]]; then
    [[ -n $PROBE_ERROR_IDENTITY ]] || PROBE_ERROR_IDENTITY=source-missing
  else
    source_present=true
    if [[ $kind == directory && ! -d $source ]] || [[ $kind == file && ! -f $source ]]; then
      [[ -n $PROBE_ERROR_IDENTITY ]] || PROBE_ERROR_IDENTITY=unexpected-source-type
    fi
  fi
  if [[ ! -d $parent || -L $parent ]]; then
    [[ -n $PROBE_ERROR_IDENTITY ]] || PROBE_ERROR_IDENTITY=parent-missing
  else
    parent_present=true
  fi
  if [[ -e $destination || -L $destination ]]; then
    [[ -n $PROBE_ERROR_IDENTITY ]] || PROBE_ERROR_IDENTITY=destination-pre-existing
  else
    destination_absent=true
  fi

  if [[ -z $PROBE_ERROR_IDENTITY ]]; then
    if ln -s -- "$source" "$destination" >/dev/null 2>"$native_stderr_file"; then
      ln_exit=0
    else
      ln_exit=$?
      PROBE_ERROR_IDENTITY=link-command-failed
    fi
  fi
  if [[ -z $PROBE_ERROR_IDENTITY ]]; then
    if [[ -L $destination ]]; then test_link=true; fi
    if [[ -e $destination || -L $destination ]]; then
      destination_present=true
    else
      PROBE_ERROR_IDENTITY=destination-absent
    fi
  fi
  if [[ -z $PROBE_ERROR_IDENTITY ]]; then
    if [[ $kind == directory ]]; then
      [[ -d $destination && ! -L $destination ]] && ordinary=true
    else
      [[ -f $destination && ! -L $destination ]] && ordinary=true
    fi
    if [[ $test_link == false && $ordinary == false ]]; then
      PROBE_ERROR_IDENTITY=unexpected-object-type
    fi
  fi
  if [[ -z $PROBE_ERROR_IDENTITY && $platform == windows-git-bash ]]; then
    if ! command -v powershell.exe >/dev/null 2>&1 || ! command -v cygpath >/dev/null 2>&1; then
      PROBE_ERROR_IDENTITY=reparse-query-failed
    elif ! destination_windows=$(cygpath -w "$destination" 2>"$native_stderr_file"); then
      PROBE_ERROR_IDENTITY=reparse-query-failed
    elif probe_output=$(FINGUARDOPS_PROBE_DESTINATION="$destination_windows" powershell.exe -NoProfile -NonInteractive -Command '
      if (Test-Path -LiteralPath $env:FINGUARDOPS_PROBE_DESTINATION) {
        $item = Get-Item -LiteralPath $env:FINGUARDOPS_PROBE_DESTINATION -Force
        [bool]($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
      } else {
        "Absent"
      }
    ' 2>"$native_stderr_file"); then
      probe_output=${probe_output//$'\r'/}
      case $probe_output in
        True) reparse=true ;;
        False) reparse=false ;;
        *) PROBE_ERROR_IDENTITY=reparse-query-failed ;;
      esac
    else
      PROBE_ERROR_IDENTITY=reparse-query-failed
    fi
  fi

  if [[ -z $PROBE_ERROR_IDENTITY && $platform == windows-git-bash ]]; then
    if [[ $test_link != "$reparse" ]]; then
      PROBE_ERROR_IDENTITY=link-indicator-mismatch
    elif [[ $test_link == true ]]; then
      preliminary_state=CAPABLE
    elif [[ $ordinary != true ]]; then
      PROBE_ERROR_IDENTITY=unexpected-object-type
    elif [[ $kind == directory ]]; then
      if source_find_output=$(find "$source" -mindepth 1 -printf 'entry\n' -quit 2>"$native_stderr_file"); then
        source_find_exit=0
      else
        source_find_exit=$?
      fi
      source_find_stderr=$(<"$native_stderr_file")
      : > "$native_stderr_file"
      if destination_find_output=$(find "$destination" -mindepth 1 -printf 'entry\n' -quit 2>"$native_stderr_file"); then
        destination_find_exit=0
      else
        destination_find_exit=$?
      fi
      destination_find_stderr=$(<"$native_stderr_file")
      if [[ $source_find_exit -ne 0 || $destination_find_exit -ne 0 ]]; then
        PROBE_ERROR_IDENTITY=directory-inspection-failed
      elif [[ -n $source_find_stderr || -n $destination_find_stderr ]]; then
        PROBE_ERROR_IDENTITY=directory-inspection-failed
      elif [[ $source_find_output != '' && $source_find_output != entry ]] \
        || [[ $destination_find_output != '' && $destination_find_output != entry ]]; then
        PROBE_ERROR_IDENTITY=directory-inspection-failed
      elif [[ -n $source_find_output || -n $destination_find_output ]]; then
        PROBE_ERROR_IDENTITY=fallback-directory-content-mismatch
      else
        preliminary_state=UNAVAILABLE
      fi
    elif [[ $kind == file ]] && ! cmp -s -- "$source" "$destination"; then
      PROBE_ERROR_IDENTITY=fallback-file-content-mismatch
    else
      preliminary_state=UNAVAILABLE
    fi
  elif [[ -z $PROBE_ERROR_IDENTITY ]]; then
    if [[ $test_link == true ]]; then
      preliminary_state=CAPABLE
    else
      PROBE_ERROR_IDENTITY=unexpected-object-type
    fi
  fi

  if ! rm -rf -- "$root" >/dev/null 2>&1; then
    cleanup_command_failed=true
    PROBE_CLEANUP_ERROR_IDENTITY=cleanup-command-failed
  fi
  if [[ -e $root || -L $root ]]; then
    cleanup_residue=true
    [[ -n $PROBE_CLEANUP_ERROR_IDENTITY ]] || PROBE_CLEANUP_ERROR_IDENTITY=cleanup-residue
  fi
  if [[ -n $PROBE_CLEANUP_ERROR_IDENTITY ]]; then
    if [[ -z $PROBE_ERROR_IDENTITY ]]; then
      PROBE_ERROR_IDENTITY=$PROBE_CLEANUP_ERROR_IDENTITY
    else
      printf 'symlink capability probe cleanup failed: %s\n' "$PROBE_CLEANUP_ERROR_IDENTITY" >&2
    fi
  fi
  if [[ -n $PROBE_ERROR_IDENTITY ]]; then
    PROBE_STATE=ERROR
    PROBE_ERROR_MESSAGE="symlink capability probe failed: $PROBE_ERROR_IDENTITY"
    printf '%s\n' "$PROBE_ERROR_MESSAGE" >&2
  else
    PROBE_STATE=$preliminary_state
  fi
  printf 'symlink capability probe: case=%s state=%s source=%s parent=%s destination_before=%s ln_exit=%s test_L=%s ordinary=%s reparse=%s cleanup_command_failed=%s residue=%s\n' \
    "$kind" "$PROBE_STATE" "$source_present" "$parent_present" "$destination_absent" "$ln_exit" \
    "$test_link" "$ordinary" "$reparse" "$cleanup_command_failed" "$cleanup_residue"
  return 0
}

create_probe_fixture() {
  local kind=$1 platform=$2 root source parent destination probe_physical_root
  root=$(mktemp -d /tmp/finguardops-symlink-probe.XXXXXX 2>/dev/null) || {
    printf 'symlink capability probe failed: temporary-root-unavailable\n' >&2
    return 1
  }
  SYMLINK_PROBE_ROOT=$root
  probe_physical_root=$(cd -- "$root" 2>/dev/null && pwd -P 2>/dev/null) || {
    printf 'symlink capability probe failed: temporary-root-unavailable\n' >&2
    return 1
  }
  case "$probe_physical_root/" in
    "$repository_root/"*)
      printf 'symlink capability probe failed: unsafe-temporary-root\n' >&2
      return 1
      ;;
  esac
  source="$root/$kind-source"
  parent="$root/$kind-parent"
  destination="$parent/link"
  mkdir -p -- "$parent"
  if [[ $kind == directory ]]; then
    mkdir -p -- "$source"
  else
    printf 'probe' > "$source"
  fi
  probe_symbolic_link "$kind" "$platform" "$root" "$source" "$parent" "$destination"
  SYMLINK_PROBE_ROOT=
  return 0
}

write_probe_fake_tools() {
  local fake_bin=$1
  mkdir -p -- "$fake_bin"
  cat > "$fake_bin/ln" <<'FAKE_LN'
#!/usr/bin/env bash
while (( $# > 2 )); do shift; done
source_path=$1
destination_path=$2
case ${PROBE_FAKE_LN_MODE:?} in
  nonzero) exit 23 ;;
  noisy-nonzero) printf '%s\n' "${PROBE_RAW_SENTINEL:?}"; printf '%s\n' "${PROBE_RAW_PATH:?}" >&2; exit 23 ;;
  absent) exit 0 ;;
  unexpected) /usr/bin/mkdir -p -- "$destination_path" ;;
  copy-mismatch) printf 'mismatch' > "$destination_path" ;;
  copy-exact) /usr/bin/cp -- "$source_path" "$destination_path" ;;
  copy-directory) /usr/bin/cp -R -- "$source_path" "$destination_path" ;;
  *) exit 24 ;;
esac
FAKE_LN
  cat > "$fake_bin/cygpath" <<'FAKE_CYGPATH'
#!/usr/bin/env bash
while (( $# > 1 )); do shift; done
printf '%s\n' "$1"
FAKE_CYGPATH
  cat > "$fake_bin/powershell.exe" <<'FAKE_POWERSHELL'
#!/usr/bin/env bash
case ${PROBE_FAKE_REPARSE_MODE:?} in
  false) printf 'False\n' ;;
  true) printf 'True\n' ;;
  failure) exit 25 ;;
  noisy-failure) printf '%s\n' "${PROBE_RAW_SENTINEL:?}"; printf '%s\n' "${PROBE_RAW_PATH:?}" >&2; exit 25 ;;
  *) exit 26 ;;
esac
FAKE_POWERSHELL
  cat > "$fake_bin/rm" <<'FAKE_RM'
#!/usr/bin/env bash
case ${PROBE_FAKE_RM_MODE:-real} in
  failure) exit 27 ;;
  noisy-failure) printf '%s\n' "${PROBE_RAW_SENTINEL:?}"; printf '%s\n' "${PROBE_RAW_PATH:?}" >&2; exit 27 ;;
  residue) exit 0 ;;
  real) exec /usr/bin/rm "$@" ;;
  *) exit 28 ;;
esac
FAKE_RM
  cat > "$fake_bin/find" <<'FAKE_FIND'
#!/usr/bin/env bash
target=${1:?}
mode=real
case ${target##*/} in
  directory-source) mode=${PROBE_FAKE_SOURCE_FIND_MODE:-real} ;;
  link) mode=${PROBE_FAKE_DESTINATION_FIND_MODE:-real} ;;
esac
case $mode in
  real) exec /usr/bin/find "$@" ;;
  exit42) exit 42 ;;
  output-exit42) printf 'entry\n'; exit 42 ;;
  noisy-exit42) printf '%s\n' "${PROBE_RAW_SENTINEL:?}"; printf '%s\n' "${PROBE_RAW_PATH:?}" >&2; exit 42 ;;
  malformed) printf '%s\n' "${PROBE_RAW_SENTINEL:?}" ;;
  *) exit 43 ;;
esac
FAKE_FIND
  chmod +x "$fake_bin/ln" "$fake_bin/cygpath" "$fake_bin/powershell.exe" "$fake_bin/rm" "$fake_bin/find"
}

assert_probe_regression_error() {
  local case_name=$1 expected_identity=$2 root=$3 expected_fixture_residue=${4:-false}
  [[ $PROBE_STATE == ERROR ]]
  [[ $PROBE_ERROR_IDENTITY == "$expected_identity" ]]
  [[ $PROBE_ERROR_MESSAGE == "symlink capability probe failed: $expected_identity" ]]
  [[ $deferred_count -eq 0 && $symlink_passed_count -eq 0 ]]
  [[ ${CAPTURED_PROBE_OUTPUT-} != *"${PROBE_RAW_SENTINEL-}"* ]]
  [[ ${CAPTURED_PROBE_OUTPUT-} != *"${PROBE_RAW_PATH-}"* ]]
  [[ ${CAPTURED_PROBE_OUTPUT-} != *"$root"* ]]
  if [[ $expected_fixture_residue == true ]]; then
    [[ -e $root && $PROBE_CLEANUP_ERROR_IDENTITY == "$expected_identity" ]]
    /usr/bin/rm -rf -- "$root"
  else
    [[ ! -e $root && ! -L $root ]]
  fi
  [[ ! -e $root && ! -L $root ]]
  printf 'passed: probe-regression-%s identity=%s\n' "$case_name" "$expected_identity"
}

invoke_probe_captured() {
  local kind=$1 platform=$2 root=$3 source=$4 parent=$5 destination=$6
  local capture_file="${root}.captured"
  probe_symbolic_link "$kind" "$platform" "$root" "$source" "$parent" "$destination" \
    >"$capture_file" 2>&1
  CAPTURED_PROBE_OUTPUT=$(<"$capture_file")
  /usr/bin/rm -f -- "$capture_file"
}

run_probe_regressions() {
  local original_path=$PATH root source parent destination fake_bin
  deferred_count=0
  symlink_passed_count=0
  PROBE_FAKE_LN_MODE=
  PROBE_FAKE_REPARSE_MODE=
  PROBE_FAKE_RM_MODE=
  PROBE_FAKE_SOURCE_FIND_MODE=real
  PROBE_FAKE_DESTINATION_FIND_MODE=real
  PROBE_RAW_SENTINEL='RAW-NATIVE-SENTINEL-MUST-NOT-LEAK'
  PROBE_RAW_PATH='/raw/private/path/must-not-leak'
  CAPTURED_PROBE_OUTPUT=
  export PROBE_FAKE_LN_MODE PROBE_FAKE_REPARSE_MODE PROBE_FAKE_RM_MODE
  export PROBE_FAKE_SOURCE_FIND_MODE PROBE_FAKE_DESTINATION_FIND_MODE PROBE_RAW_SENTINEL PROBE_RAW_PATH

  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"
  mkdir -p -- "$parent"
  invoke_probe_captured file linux "$root" "$source" "$parent" "$destination"
  assert_probe_regression_error source-missing source-missing "$root"

  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"
  printf 'probe' > "$source"
  invoke_probe_captured file linux "$root" "$source" "$parent" "$destination"
  assert_probe_regression_error parent-missing parent-missing "$root"

  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"
  mkdir -p -- "$parent"; printf 'probe' > "$source"; printf 'existing' > "$destination"
  invoke_probe_captured file linux "$root" "$source" "$parent" "$destination"
  assert_probe_regression_error destination-pre-existing destination-pre-existing "$root"

  for PROBE_FAKE_LN_MODE in nonzero absent unexpected; do
    root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
    source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
    mkdir -p -- "$parent"; printf 'probe' > "$source"; write_probe_fake_tools "$fake_bin"
    PATH="$fake_bin:$original_path" PROBE_FAKE_RM_MODE=real PROBE_FAKE_REPARSE_MODE=false \
      invoke_probe_captured file linux "$root" "$source" "$parent" "$destination"
    PATH=$original_path
    case $PROBE_FAKE_LN_MODE in
      nonzero) assert_probe_regression_error ln-nonzero link-command-failed "$root" ;;
      absent) assert_probe_regression_error destination-absent destination-absent "$root" ;;
      unexpected) assert_probe_regression_error unexpected-type unexpected-object-type "$root" ;;
    esac
  done

  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
  mkdir -p -- "$parent"; printf 'probe' > "$source"; write_probe_fake_tools "$fake_bin"
  PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-exact PROBE_FAKE_REPARSE_MODE=failure PROBE_FAKE_RM_MODE=real \
    invoke_probe_captured file windows-git-bash "$root" "$source" "$parent" "$destination"
  PATH=$original_path
  assert_probe_regression_error reparse-query-failure reparse-query-failed "$root"

  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
  mkdir -p -- "$parent"; printf 'probe' > "$source"; write_probe_fake_tools "$fake_bin"
  PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-mismatch PROBE_FAKE_REPARSE_MODE=false PROBE_FAKE_RM_MODE=real \
    invoke_probe_captured file windows-git-bash "$root" "$source" "$parent" "$destination"
  PATH=$original_path
  assert_probe_regression_error file-byte-mismatch fallback-file-content-mismatch "$root"

  for PROBE_FAKE_RM_MODE in failure residue; do
    root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
    source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
    mkdir -p -- "$parent"; printf 'probe' > "$source"; write_probe_fake_tools "$fake_bin"
    PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-exact PROBE_FAKE_REPARSE_MODE=false \
      invoke_probe_captured file windows-git-bash "$root" "$source" "$parent" "$destination"
    PATH=$original_path
    case $PROBE_FAKE_RM_MODE in
      failure) assert_probe_regression_error cleanup-command-failure cleanup-command-failed "$root" true ;;
      residue) assert_probe_regression_error cleanup-residue cleanup-residue "$root" true ;;
    esac
  done

  for PROBE_FAKE_SOURCE_FIND_MODE in exit42 output-exit42 noisy-exit42 malformed; do
    PROBE_FAKE_DESTINATION_FIND_MODE=real
    root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
    source="$root/directory-source"; parent="$root/directory-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
    mkdir -p -- "$source" "$parent"; write_probe_fake_tools "$fake_bin"
    PROBE_RAW_PATH="$root/raw-private-path"; export PROBE_RAW_PATH
    PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-directory PROBE_FAKE_REPARSE_MODE=false PROBE_FAKE_RM_MODE=real \
      invoke_probe_captured directory windows-git-bash "$root" "$source" "$parent" "$destination"
    PATH=$original_path
    case $PROBE_FAKE_SOURCE_FIND_MODE in
      exit42) assert_probe_regression_error source-find-exit directory-inspection-failed "$root" ;;
      output-exit42) assert_probe_regression_error source-find-output-nonzero directory-inspection-failed "$root" ;;
      noisy-exit42) assert_probe_regression_error source-find-noisy directory-inspection-failed "$root" ;;
      malformed) assert_probe_regression_error source-find-malformed directory-inspection-failed "$root" ;;
    esac
  done

  PROBE_FAKE_SOURCE_FIND_MODE=real
  PROBE_FAKE_DESTINATION_FIND_MODE=exit42
  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/directory-source"; parent="$root/directory-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
  mkdir -p -- "$source" "$parent"; write_probe_fake_tools "$fake_bin"
  PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-directory PROBE_FAKE_REPARSE_MODE=false PROBE_FAKE_RM_MODE=real \
    invoke_probe_captured directory windows-git-bash "$root" "$source" "$parent" "$destination"
  PATH=$original_path
  assert_probe_regression_error destination-find-exit directory-inspection-failed "$root"

  PROBE_FAKE_DESTINATION_FIND_MODE=real
  root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
  source="$root/directory-source"; parent="$root/directory-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
  mkdir -p -- "$source" "$parent"; printf 'content' > "$source/entry"; write_probe_fake_tools "$fake_bin"
  PATH="$fake_bin:$original_path" PROBE_FAKE_LN_MODE=copy-directory PROBE_FAKE_REPARSE_MODE=false PROBE_FAKE_RM_MODE=real \
    invoke_probe_captured directory windows-git-bash "$root" "$source" "$parent" "$destination"
  PATH=$original_path
  assert_probe_regression_error directory-content fallback-directory-content-mismatch "$root"

  for noisy_leaf in ln reparse cleanup; do
    root=$(mktemp -d /tmp/finguardops-probe-regression.XXXXXX)
    source="$root/file-source"; parent="$root/file-parent"; destination="$parent/link"; fake_bin="$root/fake-bin"
    mkdir -p -- "$parent"; printf 'probe' > "$source"; write_probe_fake_tools "$fake_bin"
    PROBE_RAW_PATH="$root/raw-private-path"; export PROBE_RAW_PATH
    PROBE_FAKE_LN_MODE=copy-exact PROBE_FAKE_REPARSE_MODE=false PROBE_FAKE_RM_MODE=real
    case $noisy_leaf in
      ln) PROBE_FAKE_LN_MODE=noisy-nonzero ;;
      reparse) PROBE_FAKE_REPARSE_MODE=noisy-failure ;;
      cleanup) PROBE_FAKE_RM_MODE=noisy-failure ;;
    esac
    PATH="$fake_bin:$original_path" invoke_probe_captured file windows-git-bash "$root" "$source" "$parent" "$destination"
    PATH=$original_path
    case $noisy_leaf in
      ln) assert_probe_regression_error noisy-ln link-command-failed "$root" ;;
      reparse) assert_probe_regression_error noisy-reparse reparse-query-failed "$root" ;;
      cleanup) assert_probe_regression_error noisy-cleanup cleanup-command-failed "$root" true ;;
    esac
  done
  unset PROBE_FAKE_LN_MODE PROBE_FAKE_REPARSE_MODE PROBE_FAKE_RM_MODE
  unset PROBE_FAKE_SOURCE_FIND_MODE PROBE_FAKE_DESTINATION_FIND_MODE PROBE_RAW_SENTINEL PROBE_RAW_PATH
  [[ $(process_environment_snapshot) == "$PROCESS_ENVIRONMENT_BEFORE" ]]
  [[ $(windows_environment_snapshot) == "$WINDOWS_ENVIRONMENT_BEFORE" ]]
  printf 'probe regressions passed: passed=19 failed=0 deferred=0 symlinks=0 residue=0 raw_leaks=0\n'
}

if [[ ${1-} == --probe-regressions ]]; then
  [[ $# -eq 1 ]]
  run_probe_regressions
  exit 0
elif [[ ${1-} == --registry-regressions ]]; then
  [[ $# -eq 1 ]]
  run_registry_regressions
  exit 0
elif (( $# != 0 )); then
  printf 'unsupported test argument\n' >&2
  exit 2
fi

create_probe_fixture directory "$TEST_PLATFORM" || exit 1
SYMLINK_DIRECTORY_STATE=$PROBE_STATE
case $SYMLINK_DIRECTORY_STATE in
  CAPABLE) ;;
  UNAVAILABLE) [[ $TEST_PLATFORM == windows-git-bash ]] || exit 1 ;;
  ERROR) exit 1 ;;
  *) printf 'symlink capability probe failed: invalid-state\n' >&2; exit 1 ;;
esac
create_probe_fixture file "$TEST_PLATFORM" || exit 1
SYMLINK_FILE_STATE=$PROBE_STATE
case $SYMLINK_FILE_STATE in
  CAPABLE) ;;
  UNAVAILABLE) [[ $TEST_PLATFORM == windows-git-bash ]] || exit 1 ;;
  ERROR) exit 1 ;;
  *) printf 'symlink capability probe failed: invalid-state\n' >&2; exit 1 ;;
esac

SYMLINK_DIRECTORY_CAPABLE=false
SYMLINK_FILE_CAPABLE=false
if [[ $SYMLINK_DIRECTORY_STATE == CAPABLE ]]; then
  SYMLINK_DIRECTORY_CAPABLE=true
fi
if [[ $SYMLINK_FILE_STATE == CAPABLE ]]; then
  SYMLINK_FILE_CAPABLE=true
fi
if [[ $TEST_PLATFORM != windows-git-bash ]] \
  && { [[ $SYMLINK_DIRECTORY_STATE != CAPABLE ]] || [[ $SYMLINK_FILE_STATE != CAPABLE ]]; }; then
  printf 'symlink capability probe failed: Linux/WSL-requires-symbolic-links\n' >&2
  exit 1
fi

reset_case_registry
register_case fresh-generation functional
register_case overwrite-refusal functional
for case_name in symlink-local-directory symlink-output-directory symlink-artifact-crt symlink-artifact-key symlink-prefix-boundary; do
  register_case "$case_name" symlink
done
if [[ $TEST_PLATFORM == windows-git-bash ]]; then
  register_case junction-output-directory junction
fi
register_case partial-certificate partial
register_case partial-private-key partial
for mutation in ca-true eku-missing eku-extra key-usage-missing key-usage-extra san-extra extension-extra extension-oid-extra rsa-2048 sha1 key-mismatch not-self-signed; do
  register_case "mutation-$mutation" mutation
done
register_case generation-failure generation-failure

mkdir -p -- "$TEST_ROOT"

fresh_root=$(new_case fresh)
output=$(bash "$fresh_root/setup-local-tls.sh" 2>&1)
assert_no_pem_markers "$output"
certificate="$fresh_root/.local/tls/localhost.crt"
private_key="$fresh_root/.local/tls/localhost.key"
[[ -f "$certificate" && ! -L "$certificate" && -f "$private_key" && ! -L "$private_key" ]]
output_entries=$(find "$fresh_root/.local/tls" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort)
[[ $output_entries == $'localhost.crt\nlocalhost.key' ]]
subject=$(openssl x509 -in "$certificate" -noout -subject -nameopt RFC2253 2>/dev/null)
[[ ${subject#subject=} == "$EXPECTED_SUBJECT" ]]

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
record_case_result fresh-generation functional PASS

before=$(sha256sum "$certificate" "$private_key")
if output=$(bash "$fresh_root/setup-local-tls.sh" 2>&1); then
  printf 'expected overwrite refusal\n' >&2
  exit 1
fi
[[ $before == "$(sha256sum "$certificate" "$private_key")" ]]
[[ $output != *"$private_marker"* ]]
assert_no_pem_markers "$output"
record_case_result overwrite-refusal functional PASS

readonly LINK_CASES="$TEST_ROOT/link-cases"
mkdir -p -- "$LINK_CASES"
deferred_count=0
symlink_passed_count=0

if [[ $SYMLINK_DIRECTORY_CAPABLE == true ]]; then
  case_root="$LINK_CASES/local-link"
  external="$LINK_CASES/local-link-target"
  mkdir -p -- "$case_root" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  ln -s -- "$external" "$case_root/.local"
  [[ -L $case_root/.local ]]
  if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected .local symlink rejection\n' >&2
    exit 1
  fi
  assert_no_pem_markers "$output"
  [[ -z $(find "$external" -type f -print -quit) ]]
  symlink_passed_count=$((symlink_passed_count + 1))
  record_case_result symlink-local-directory symlink PASS
  printf 'passed: symlink-local-directory\n'
else
  deferred_count=$((deferred_count + 1))
  record_case_result symlink-local-directory symlink DEFERRED
  printf 'deferred: symlink-local-directory: Windows Git Bash directory test -L capability is unavailable\n'
fi

junction_count=0
if [[ $TEST_PLATFORM == windows-git-bash ]]; then
  command -v powershell.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1 || {
    printf 'junction fixture failed: required Windows path tools unavailable\n' >&2
    exit 1
  }
  case_root="$LINK_CASES/output-junction"
  external="$LINK_CASES/output-junction-target"
  mkdir -p -- "$case_root/.local" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  FINGUARDOPS_JUNCTION_LINK=$(cygpath -w "$case_root/.local/tls")
  FINGUARDOPS_JUNCTION_TARGET=$(cygpath -w "$external")
  powershell.exe -NoProfile -NonInteractive -Command \
    "New-Item -ItemType Junction -Path '$FINGUARDOPS_JUNCTION_LINK' -Target '$FINGUARDOPS_JUNCTION_TARGET' | Out-Null"
  if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS directory junction rejection\n' >&2
    exit 1
  fi
  assert_no_pem_markers "$output"
  [[ -z $(find "$external" -type f -print -quit) ]]
  powershell.exe -NoProfile -NonInteractive -Command \
    "[IO.Directory]::Delete('$FINGUARDOPS_JUNCTION_LINK', \$false)"
  junction_count=1
  record_case_result junction-output-directory junction PASS
  printf 'passed: junction-output-directory\n'
fi

if [[ $SYMLINK_DIRECTORY_CAPABLE == true ]]; then
  case_root="$LINK_CASES/output-link"
  external="$LINK_CASES/output-link-target"
  mkdir -p -- "$case_root/.local" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  ln -s -- "$external" "$case_root/.local/tls"
  [[ -L $case_root/.local/tls ]]
  if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS directory symlink rejection\n' >&2
    exit 1
  fi
  assert_no_pem_markers "$output"
  [[ -z $(find "$external" -type f -print -quit) ]]
  symlink_passed_count=$((symlink_passed_count + 1))
  record_case_result symlink-output-directory symlink PASS
  printf 'passed: symlink-output-directory\n'
else
  deferred_count=$((deferred_count + 1))
  record_case_result symlink-output-directory symlink DEFERRED
  printf 'deferred: symlink-output-directory: Windows Git Bash directory test -L capability is unavailable\n'
fi

for artifact in localhost.crt localhost.key; do
  case_name="symlink-artifact-${artifact##*.}"
  if [[ $SYMLINK_FILE_CAPABLE == true ]]; then
    case_root="$LINK_CASES/artifact-${artifact##*.}"
    external="$LINK_CASES/artifact-${artifact##*.}-target"
    mkdir -p -- "$case_root/.local/tls" "$external"
    cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
    printf 'NeverPrintSymlinkPrivateMaterial' > "$external/target"
    before=$(sha256sum "$external/target")
    ln -s -- "$external/target" "$case_root/.local/tls/$artifact"
    [[ -L $case_root/.local/tls/$artifact ]]
    if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
      printf 'expected TLS artifact symlink rejection\n' >&2
      exit 1
    fi
    [[ $before == "$(sha256sum "$external/target")" ]]
    [[ $output != *NeverPrintSymlinkPrivateMaterial* ]]
    assert_no_pem_markers "$output"
    symlink_passed_count=$((symlink_passed_count + 1))
    record_case_result "$case_name" symlink PASS
    printf 'passed: %s\n' "$case_name"
  else
    deferred_count=$((deferred_count + 1))
    record_case_result "$case_name" symlink DEFERRED
    printf 'deferred: %s: Windows Git Bash file test -L capability is unavailable\n' "$case_name"
  fi
done

if [[ $SYMLINK_DIRECTORY_CAPABLE == true ]]; then
  case_root="$LINK_CASES/prefix-boundary"
  external="$case_root/.local-evil"
  mkdir -p -- "$case_root/.local" "$external"
  cp -- "$SCRIPT_SOURCE" "$case_root/setup-local-tls.sh"
  ln -s -- "$external" "$case_root/.local/tls"
  [[ -L $case_root/.local/tls ]]
  if output=$(bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS prefix boundary rejection\n' >&2
    exit 1
  fi
  assert_no_pem_markers "$output"
  [[ -z $(find "$external" -type f -print -quit) ]]
  symlink_passed_count=$((symlink_passed_count + 1))
  record_case_result symlink-prefix-boundary symlink PASS
  printf 'passed: symlink-prefix-boundary\n'
else
  deferred_count=$((deferred_count + 1))
  record_case_result symlink-prefix-boundary symlink DEFERRED
  printf 'deferred: symlink-prefix-boundary: Windows Git Bash directory test -L capability is unavailable\n'
fi

if [[ $TEST_PLATFORM != windows-git-bash && $deferred_count -ne 0 ]]; then
  printf 'symlink fixture failed: Linux/WSL deferred count must be zero\n' >&2
  exit 1
fi

partial_root=$(new_case partial)
mkdir -p -- "$partial_root/.local/tls"
printf 'existing-certificate' > "$partial_root/.local/tls/localhost.crt"
before=$(sha256sum "$partial_root/.local/tls/localhost.crt")
if output=$(bash "$partial_root/setup-local-tls.sh" 2>&1); then
  printf 'expected partial-state refusal\n' >&2
  exit 1
fi
[[ $output == 'local TLS setup failed: existing certificate blocks generation' ]]
assert_no_pem_markers "$output"
[[ $before == "$(sha256sum "$partial_root/.local/tls/localhost.crt")" ]]
[[ ! -e "$partial_root/.local/tls/localhost.key" ]]
[[ $(find "$partial_root/.local/tls" -mindepth 1 -maxdepth 1 -printf '%f\n') == 'localhost.crt' ]]
record_case_result partial-certificate partial PASS

private_partial_root=$(new_case private-partial)
mkdir -p -- "$private_partial_root/.local/tls"
printf 'existing-private-key' > "$private_partial_root/.local/tls/localhost.key"
before=$(sha256sum "$private_partial_root/.local/tls/localhost.key")
if output=$(bash "$private_partial_root/setup-local-tls.sh" 2>&1); then
  printf 'expected private-key-only partial-state refusal\n' >&2
  exit 1
fi
[[ $output == 'local TLS setup failed: existing private key blocks generation' ]]
assert_no_pem_markers "$output"
[[ $before == "$(sha256sum "$private_partial_root/.local/tls/localhost.key")" ]]
[[ ! -e "$private_partial_root/.local/tls/localhost.crt" ]]
[[ $(find "$private_partial_root/.local/tls" -mindepth 1 -maxdepth 1 -printf '%f\n') == 'localhost.key' ]]
record_case_result partial-private-key partial PASS

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
mutation_config="$work/mutation-subject.cnf"
test_ca_config="$work/test-ca-subject.cnf"
leaf_config="$work/leaf-subject.cnf"
printf '%s\n' '[req]' 'distinguished_name=subject' 'prompt=no' '[subject]' 'CN=localhost' > "$mutation_config"
printf '%s\n' '[req]' 'distinguished_name=subject' 'prompt=no' '[subject]' 'CN=local-test-ca' > "$test_ca_config"
printf '%s\n' '[req]' 'distinguished_name=subject' 'prompt=no' '[subject]' 'CN=localhost' > "$leaf_config"
mkdir -p -- "${TLS_MUTATION_EVIDENCE:?}"

generate_self_signed() {
  "$real" req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
    -config "$mutation_config" "$@" \
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
      -config "$mutation_config" \
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
      -config "$mutation_config" \
      -addext 'basicConstraints=critical,CA:FALSE' \
      -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
      -addext 'extendedKeyUsage=serverAuth' \
      -addext 'subjectAltName=DNS:localhost' \
      -addext 'subjectKeyIdentifier=none' -keyout "$key" -out "$certificate"
    ;;
  sha1)
    "$real" req -x509 -newkey rsa:3072 -sha1 -nodes -days 30 \
      -config "$mutation_config" \
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
      -config "$test_ca_config" \
      -addext 'basicConstraints=critical,CA:TRUE' \
      -keyout "$work/ca.key" -out "$work/ca.crt"
    "$real" req -new -newkey rsa:3072 -sha256 -nodes \
      -config "$leaf_config" -keyout "$key" -out "$work/leaf.csr"
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

: > "$TLS_MUTATION_EVIDENCE/fixture-generated"
subject=$("$real" x509 -in "$certificate" -noout -subject -nameopt RFC2253 2>/dev/null | sed 's/\r$//')
[[ ${subject#subject=} == 'CN=localhost' ]]

normalize_test_extension() {
  "$real" x509 -in "$certificate" -noout -ext "$1" 2>/dev/null \
    | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

extension_names=$("$real" x509 -in "$certificate" -noout -text 2>/dev/null \
  | sed -n '/X509v3 extensions:/,/Signature Algorithm:/p' \
  | sed -n -e 's/^            X509v3 \([^:]*\):.*/\1/p' -e 's/^            \([0-9][0-9.]*\):.*/OID:\1/p' \
  | LC_ALL=C sort)

case "$mutation" in
  ca-true)
    [[ $(normalize_test_extension basicConstraints) == $'X509v3 Basic Constraints: critical\nCA:TRUE' ]]
    ;;
  eku-missing)
    [[ $extension_names == $'Basic Constraints\nKey Usage\nSubject Alternative Name' ]]
    ;;
  eku-extra)
    [[ $(normalize_test_extension extendedKeyUsage) == $'X509v3 Extended Key Usage:\nTLS Web Server Authentication, TLS Web Client Authentication' ]]
    ;;
  key-usage-missing)
    [[ $(normalize_test_extension keyUsage) == $'X509v3 Key Usage: critical\nDigital Signature' ]]
    ;;
  key-usage-extra)
    [[ $(normalize_test_extension keyUsage) == $'X509v3 Key Usage: critical\nDigital Signature, Key Encipherment, Key Agreement' ]]
    ;;
  san-extra)
    [[ $(normalize_test_extension subjectAltName) == $'X509v3 Subject Alternative Name:\nDNS:localhost, DNS:example.invalid' ]]
    ;;
  extension-extra)
    [[ $extension_names == *'Subject Key Identifier'* ]]
    ;;
  extension-oid-extra)
    [[ $extension_names == *'OID:1.2.3.4'* ]]
    ;;
  rsa-2048)
    rsa_bits=$("$real" x509 -in "$certificate" -pubkey -noout \
      | "$real" pkey -pubin -text -noout 2>/dev/null \
      | sed -n 's/.*Public-Key: (\([0-9][0-9]*\) bit).*/\1/p' | head -n 1)
    [[ $rsa_bits == 2048 ]]
    ;;
  sha1)
    signature_algorithm=$("$real" x509 -in "$certificate" -noout -text 2>/dev/null \
      | sed -n 's/^[[:space:]]*Signature Algorithm:[[:space:]]*//p' | head -n 1)
    [[ $signature_algorithm == sha1WithRSAEncryption ]]
    ;;
  key-mismatch)
    certificate_public=$("$real" x509 -in "$certificate" -pubkey -noout \
      | "$real" pkey -pubin -outform DER 2>/dev/null | "$real" dgst -sha256)
    key_public=$("$real" pkey -in "$key" -pubout -outform DER 2>/dev/null | "$real" dgst -sha256)
    [[ $certificate_public != "$key_public" ]]
    ;;
  not-self-signed)
    issuer=$("$real" x509 -in "$certificate" -noout -issuer -nameopt RFC2253 2>/dev/null | sed 's/\r$//')
    [[ ${issuer#issuer=} == 'CN=local-test-ca' ]]
    ! "$real" verify -check_ss_sig -CAfile "$certificate" "$certificate" >/dev/null 2>&1
    ;;
esac
rm -f -- "$mutation_config" "$test_ca_config" "$leaf_config" \
  "$work/ca.key" "$work/ca.crt" "$work/leaf.csr" "$work/leaf.cnf"
: > "$TLS_MUTATION_EVIDENCE/mutation-confirmed"
FAKE
chmod +x "$mutation_root/fake-bin/openssl"

for mutation in ca-true eku-missing eku-extra key-usage-missing key-usage-extra san-extra extension-extra extension-oid-extra rsa-2048 sha1 key-mismatch not-self-signed; do
  case_root=$(new_case "mutation-$mutation")
  evidence="$case_root/mutation-evidence"
  case "$mutation" in
    ca-true) expected_error='local TLS setup failed: certificate basic constraints must be exactly critical CA:FALSE' ;;
    eku-missing|extension-extra|extension-oid-extra) expected_error='local TLS setup failed: certificate extension set is not exact' ;;
    eku-extra) expected_error='local TLS setup failed: certificate extended key usage is not exact' ;;
    key-usage-missing|key-usage-extra) expected_error='local TLS setup failed: certificate key usage is not exact' ;;
    san-extra) expected_error='local TLS setup failed: certificate SAN must be exactly DNS:localhost' ;;
    rsa-2048) expected_error='local TLS setup failed: certificate must use RSA 3072 or stronger' ;;
    sha1) expected_error='local TLS setup failed: certificate signature algorithm must be SHA-256 or stronger RSA' ;;
    key-mismatch) expected_error='local TLS setup failed: certificate and private key do not match' ;;
    not-self-signed) expected_error='local TLS setup failed: certificate is not self-issued' ;;
  esac
  if output=$(REAL_OPENSSL_BIN="$REAL_OPENSSL" TLS_MUTATION="$mutation" TLS_MUTATION_EVIDENCE="$evidence" \
    PATH="$mutation_root/fake-bin:$PATH" bash "$case_root/setup-local-tls.sh" 2>&1); then
    printf 'expected TLS mutation rejection: %s\n' "$mutation" >&2
    exit 1
  fi
  [[ -f "$evidence/fixture-generated" && -f "$evidence/mutation-confirmed" ]]
  [[ $(find "$evidence" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort) == $'fixture-generated\nmutation-confirmed' ]]
  [[ $output == "$expected_error" ]]
  [[ ! -e "$case_root/.local/tls/localhost.crt" && ! -e "$case_root/.local/tls/localhost.key" ]]
  [[ -z $(find "$case_root/.local/tls" -mindepth 1 -print -quit) ]]
  assert_no_pem_markers "$output"
  record_case_result "mutation-$mutation" mutation PASS
  printf 'passed: mutation-%s\n' "$mutation"
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
if output=$(PATH="$failure_root/fake-bin:$PATH" bash "$failure_root/setup-local-tls.sh" 2>&1); then
  printf 'expected injected TLS generation failure\n' >&2
  exit 1
fi
[[ $output == 'local TLS setup failed: certificate generation failed' ]]
assert_no_pem_markers "$output"
[[ ! -e "$failure_root/.local/tls/localhost.crt" && ! -e "$failure_root/.local/tls/localhost.key" ]]
[[ -z $(find "$failure_root/.local/tls" -mindepth 1 -print -quit) ]]
record_case_result generation-failure generation-failure PASS

[[ $(process_environment_snapshot) == "$PROCESS_ENVIRONMENT_BEFORE" ]]
[[ $(windows_environment_snapshot) == "$WINDOWS_ENVIRONMENT_BEFORE" ]]

verify_registry_complete
passed_count=$(registry_count all PASS)
failed_count=$(registry_count all FAIL)
deferred_count=$(registry_count all DEFERRED)
skipped_count=$(registry_count all SKIPPED)
symlink_passed_count=$(registry_count symlink PASS)
junction_count=$(registry_count junction PASS)
mutation_count=$(registry_count mutation PASS)
partial_count=$(registry_count partial PASS)
generation_failure_count=$(registry_count generation-failure PASS)
exact_count=0
overwrite_count=0
[[ ${CASE_RESULT[fresh-generation]} == PASS ]] && exact_count=1
[[ ${CASE_RESULT[overwrite-refusal]} == PASS ]] && overwrite_count=1

verify_summary_count all FAIL 0
verify_summary_count all SKIPPED 0
verify_summary_count mutation PASS 12
verify_summary_count partial PASS 2
verify_summary_count generation-failure PASS 1
if [[ $TEST_PLATFORM == windows-git-bash ]]; then
  verify_summary_count symlink DEFERRED 5
  verify_summary_count symlink PASS 0
  verify_summary_count junction PASS 1
else
  verify_summary_count symlink DEFERRED 0
  verify_summary_count symlink PASS 5
  verify_summary_count junction PASS 0
fi
printf 'case registry completeness: PASS expected=%d observed=%d\n' "${#EXPECTED_CASES[@]}" "${#CASE_RESULT[@]}"
printf 'setup-local-tls tests passed: platform=%s passed=%d failed=%d deferred=%d skipped=%d exact=%d overwrite=%d partial=%d symlinks=%d junctions=%d mutations=%d failure=%d\n' \
  "$TEST_PLATFORM" "$passed_count" "$failed_count" "$deferred_count" "$skipped_count" \
  "$exact_count" "$overwrite_count" "$partial_count" "$symlink_passed_count" "$junction_count" \
  "$mutation_count" "$generation_failure_count"
