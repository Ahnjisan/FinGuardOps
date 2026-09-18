Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# The browser is not this machine's browser.
#
# Chromium runs inside a prepared image built from the official Playwright Linux
# image, pinned by immutable digest to the exact Playwright version package.json
# depends on, with a per-run NSS database that holds the local `localhost` leaf
# and nothing else. This script therefore never reads, writes or even opens a
# Windows certificate store, never clicks a trust prompt and never relaxes TLS:
# the only trust it arranges lives inside a container started with --rm.
#
# Preparation, SERVICE verification and browser execution are separate modes.
#
# `-Mode Prepare` is the one place allowed to reach a registry or a package
# archive: it pulls every pinned Compose image, builds the two images this
# repository builds, and builds the browser image that carries `certutil`.
# `-Mode Service` and `-Mode Run` do none of that. They consume the immutable
# Prepared receipt, verify exact image ownership, and start Compose only with
# `--no-build --pull never`. A missing or mismatched image is a fixed error,
# never a pull, and neither mode falls back to preparing anything.
#
# `-Mode Run` also runs no npm and no npx. Both resolve scripts, lifecycle hooks
# and, on a miss, a registry; none of that belongs in a run whose whole claim is
# that it fetches nothing. Playwright and Vite are started as what they are:
# installed JavaScript entry points handed to this session's own Node
# executable, as an argument vector rather than a command line, so a path
# containing spaces or non-ASCII characters is data and never syntax.
$ProjectName = 'finguardops-keycloak-browser-e2e'
$BrowserContainerName = 'finguardops-keycloak-browser-e2e-chromium'
# The base the prepared image must be built from, and the assertion that it was.
$BrowserBaseDigest = 'sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e'
$BrowserBaseImage = "mcr.microsoft.com/playwright@$BrowserBaseDigest"
$BrowserImage = $null
# The exact `libnss3-tools` version, asserted when the image is built and again
# before it is run, so the tool that writes the NSS trust entry is the same tool
# on every machine. `libnss3` is the NSS runtime that `certutil` links against;
# the two are shipped together and are pinned together.
$LibNss3ToolsVersion = '2:3.98-1ubuntu0.2'
$LibNss3Version = '2:3.98-1ubuntu0.2'
# What the pinned base digest contains, so that "the image is the pinned base
# plus one known layer" is a checked statement rather than a label to be
# believed. The base is a single-platform linux/amd64 image with this many
# filesystem layers; `Dockerfile.playwright-e2e` adds exactly one, because it
# has exactly one `RUN` and neither `LABEL` nor `USER` produces a layer.
$BrowserPlatformOs = 'linux'
$BrowserPlatformArchitecture = 'amd64'
$BrowserBaseLayerCount = 7
$BrowserAddedLayerCount = 1
# The unprivileged account the base image provides, and the identifiers the
# kernel must actually report for it inside the running container.
$BrowserUser = 'pwuser'
$BrowserUserId = '1001'
$BrowserGroupId = '1001'
# The interpreter and the browser build the pinned base carries. Both are
# functions of the digest above, so a base that was swapped for another one
# fails here even if every label was copied across.
$BrowserNodeVersion = 'v24.18.1'
$BrowserChromiumBuild = 'Google Chrome for Testing 151.0.7922.34'
# The Playwright this checkout installs. The client, the browser server and the
# image's browser revisions must all be this one version.
$ExpectedPlaywrightVersion = '1.62.1'
$ExpectedViteVersion = '8.2.2'
$BrowserContainerPort = 3500
$BrowserHostPort = 14250
$FrontendRoot = Split-Path -Parent $PSScriptRoot
$RepositoryRoot = Split-Path -Parent $FrontendRoot
$BrowserDockerfile = Join-Path $FrontendRoot 'Dockerfile.playwright-e2e'
$CertificatePath = Join-Path $RepositoryRoot 'infra/keycloak/.local/tls/localhost.crt'
$PrivateKeyPath = Join-Path $RepositoryRoot 'infra/keycloak/.local/tls/localhost.key'
$ScriptsPath = $PSScriptRoot
$NodeModulesPath = Join-Path $FrontendRoot 'node_modules'
$PlaywrightCorePath = Join-Path $NodeModulesPath 'playwright-core'
$PlaywrightTestPath = Join-Path $NodeModulesPath '@playwright/test'
$VitePath = Join-Path $NodeModulesPath 'vite'
# The host paths the production browser container is created with, and the one
# container path each of them is allowed to appear at.
#
# Creation resolves every entry to an owned physical location and refuses a
# missing path or a reparse point anywhere under the repository. Removal-time
# ownership compares what the daemon recorded against the same canonical paths
# without re-reading the filesystem, because whether a repository file is
# present now says nothing about which container the daemon is holding. Both
# boundaries are written from this one declaration, so the mount set creation
# approves and the mount set removal insists on cannot drift apart.
$BrowserBindContract = @(
    [ordered]@{ HostPath = $CertificatePath; Destination = '/finguardops/tls/localhost.crt'; Directory = $false },
    [ordered]@{ HostPath = $ScriptsPath; Destination = '/finguardops/scripts'; Directory = $true },
    [ordered]@{ HostPath = $PlaywrightCorePath; Destination = '/finguardops/playwright-core'; Directory = $true }
)
$OutputDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("finguardops-playwright-{0}" -f [guid]::NewGuid().ToString('N'))
$ComposeArguments = @(
    'compose',
    '-p', $ProjectName,
    '--env-file', 'infra/.env.example',
    '-f', 'infra/compose.yml',
    '-f', 'infra/compose.keycloak-local-e2e.yml'
)
$StateDirectory = Join-Path $RepositoryRoot 'infra/keycloak/.local/state'
$PreparedReceiptPath = Join-Path $StateDirectory 'e2e-image-manifest.json'
$RecoveryReceiptPath = Join-Path $StateDirectory 'e2e-image-cleanup-required.json'
$PythonVerifierPath = Join-Path $RepositoryRoot 'infra/keycloak/verify_e2e.py'
$ProtectedImageReferences = @(
    'finguardops-backend:local',
    'finguardops-ai-service:local',
    'finguardops-playwright-e2e:local'
)
# The Compose contract, as names rather than as a query.
#
# Cleanup removes what this repository's two Compose files declare, under a
# project name this run owns, and nothing else. Holding the three lists here
# means the ownership contract and the removal targets are the same statement:
# a service, network or volume that is not spelled out below is never a
# removal target, however it is labelled.
$E2EComposeServices = @('postgresql', 'ai-service', 'external-risk-mock', 'backend', 'prometheus',
    'grafana', 'alertmanager', 'alertmanager-webhook', 'keycloak', 'keycloak-bootstrap',
    'keycloak-verify')
$E2EComposeNetworks = @('application', 'observability', 'prometheus-ui', 'grafana-ui')
$E2EComposeVolumes = @('keycloak-data', 'prometheus-data', 'alertmanager-data', 'grafana-data')

# The one lock that decides who owns the dedicated Compose project.
#
# The project name below is fixed, so two runs started at the same time would
# both find the project empty, both create containers, networks and volumes
# under it, and then the first one to finish would take the other one's
# resources down as part of its own cleanup. Ownership therefore has to be
# established before the emptiness check rather than inferred from it.
#
# A Windows system-wide named mutex is what says so. It is named after the
# fixed project and nothing else - no path, no user, no credential - so every
# execution that shares this Compose project shares this lock, including the
# cleanup mode, which removes exactly the resources a run owns. Whoever holds it
# holds it from before the first `docker ps` that looks for existing resources
# until after the last step of cleanup has finished; nobody else can be inside
# that window, so there is no moment at which one run can observe or remove
# another run's resources.
#
# Failing to take it is a fixed, non-sensitive error and stops the run. It is
# never a reason to proceed, and no Docker resource is removed on the way out of
# a run that never held it.
$RunLockName = "Global\$ProjectName"
$RunLockTimeoutMilliseconds = 0
# `[Console]::OutputEncoding` is process-global, so the window in which it is
# UTF-8 has to be one caller wide. This lock is named after this process, which
# is what "one caller at a time inside this process" means: parallel runspaces
# of the same process contend for it, a nested call on the same thread re-enters
# it, and no other process is affected by or waits on it.
$ConsoleEncodingLockName = "Local\finguardops-keycloak-e2e-console-encoding-$PID"
$ConsoleEncodingLockTimeoutMilliseconds = 15000

# Takes the run lock, or fails without having taken anything.
#
# Returns the held mutex, and returns it only when it is held: a caller that
# receives an object from here owns the lock, and a caller that does not receive
# one has nothing to release. Nothing here touches Docker.
function New-RunLock {
    $lock = $null
    try {
        $lock = New-Object System.Threading.Mutex($false, $RunLockName)
    }
    catch {
        throw 'The dedicated E2E run lock could not be created.'
    }
    $owned = $false
    try {
        $owned = $lock.WaitOne($RunLockTimeoutMilliseconds)
    }
    catch [System.Threading.AbandonedMutexException] {
        # .NET reports that the previous holder died without releasing, and in
        # the same breath hands the mutex to this wait: the lock *is* held now.
        # What that run may have left behind in Docker is a separate question
        # and is deliberately not answered here - the existing-resource checks
        # still run, unchanged, and still refuse to start on top of anything
        # they find. Nothing is removed on the strength of an abandoned lock.
        $owned = $true
    }
    catch {
        $primary = [System.InvalidOperationException]::new('The dedicated E2E run lock could not be acquired.')
        $actions = @([pscustomobject]@{
            Action = { $lock.Dispose() }.GetNewClosure()
            ErrorCode = 'RUN_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    if (-not $owned) {
        $primary = [System.InvalidOperationException]::new('The dedicated E2E run lock is held by another run.')
        $actions = @([pscustomobject]@{
            Action = { $lock.Dispose() }.GetNewClosure()
            ErrorCode = 'RUN_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    return $lock
}

function Enter-E2ELifecycleLock {
    param([string]$Name = $RunLockName)

    if ([string]::IsNullOrWhiteSpace($Name)) {
        throw 'E2E_LOCK_CREATE_FAILED'
    }
    $lock = $null
    try {
        $lock = [System.Threading.Mutex]::new($false, $Name)
    }
    catch {
        throw 'E2E_LOCK_CREATE_FAILED'
    }
    try {
        $owned = $lock.WaitOne(0)
    }
    catch [System.Threading.AbandonedMutexException] {
        $owned = $true
        Write-E2ESafeCleanupDiagnostic -Message 'The previous E2E lifecycle owner ended without releasing the lock.'
    }
    catch {
        $primary = [System.InvalidOperationException]::new('E2E_LOCK_ACQUIRE_FAILED')
        $actions = @([pscustomobject]@{
            Action = { $lock.Dispose() }.GetNewClosure()
            ErrorCode = 'LIFECYCLE_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    if (-not $owned) {
        $primary = [System.InvalidOperationException]::new('E2E_LOCK_BUSY')
        $actions = @([pscustomobject]@{
            Action = { $lock.Dispose() }.GetNewClosure()
            ErrorCode = 'LIFECYCLE_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    return $lock
}

function Exit-E2ELifecycleLock {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        $Primary,
        $Boundaries
    )

    if ($null -eq $Boundaries) {
        $Boundaries = @{
            Release = { param($value) $value.ReleaseMutex() }
            Dispose = { param($value) $value.Dispose() }
        }
    }
    $actions = @(
        [pscustomobject]@{
            Action = { & $Boundaries.Release $Lock }.GetNewClosure()
            ErrorCode = 'LIFECYCLE_LOCK_RELEASE_FAILED'
            SkipAfterCleanupFailure = $false
        },
        [pscustomobject]@{
            Action = { & $Boundaries.Dispose $Lock }.GetNewClosure()
            ErrorCode = 'LIFECYCLE_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        }
    )
    Invoke-E2ECleanupActions -Primary $Primary -Actions $actions
}

function Get-E2EReceiptKeys($Receipt) {
    if ($Receipt -is [System.Collections.Specialized.OrderedDictionary]) {
        return @($Receipt.Keys)
    }
    if ($Receipt -is [System.Collections.IDictionary]) {
        return @($Receipt.Keys)
    }
    return @($Receipt.PSObject.Properties.Name)
}

function Get-E2EReceiptValue($Receipt, [string]$Name) {
    if ($Receipt -is [System.Collections.IDictionary]) {
        return $Receipt[$Name]
    }
    $property = $Receipt.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Assert-E2EReceiptObject($Receipt) {
    $expectedKeys = @('schemaVersion', 'runId', 'repositoryId', 'commitSha', 'treeSha')
    $keys = @(Get-E2EReceiptKeys $Receipt)
    if ($keys.Count -ne $expectedKeys.Count) {
        throw 'RECEIPT_INVALID'
    }
    for ($index = 0; $index -lt $expectedKeys.Count; $index++) {
        if (-not [string]::Equals([string]$keys[$index], $expectedKeys[$index], [System.StringComparison]::Ordinal)) {
            throw 'RECEIPT_INVALID'
        }
    }
    $schemaVersion = Get-E2EReceiptValue $Receipt 'schemaVersion'
    if ($schemaVersion -isnot [int] -or $schemaVersion -ne 1) {
        throw 'RECEIPT_INVALID'
    }
    if ((Get-E2EReceiptValue $Receipt 'runId') -cnotmatch '^[0-9a-f]{32}$' -or
        (Get-E2EReceiptValue $Receipt 'repositoryId') -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-E2EReceiptValue $Receipt 'commitSha') -cnotmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$' -or
        (Get-E2EReceiptValue $Receipt 'treeSha') -cnotmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw 'RECEIPT_INVALID'
    }
}

function New-E2EReceipt {
    param(
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$RepositoryId,
        [Parameter(Mandatory = $true)][string]$CommitSha,
        [Parameter(Mandatory = $true)][string]$TreeSha
    )

    $receipt = [ordered]@{
        schemaVersion = [int]1
        runId = $RunId
        repositoryId = $RepositoryId
        commitSha = $CommitSha
        treeSha = $TreeSha
    }
    Assert-E2EReceiptObject $receipt
    return $receipt
}

function ConvertTo-E2EReceiptBytes {
    param([Parameter(Mandatory = $true)]$Receipt)

    Assert-E2EReceiptObject $Receipt
    $json = '{"schemaVersion":1,"runId":"' + (Get-E2EReceiptValue $Receipt 'runId') +
        '","repositoryId":"' + (Get-E2EReceiptValue $Receipt 'repositoryId') +
        '","commitSha":"' + (Get-E2EReceiptValue $Receipt 'commitSha') +
        '","treeSha":"' + (Get-E2EReceiptValue $Receipt 'treeSha') + '"}' + "`n"
    return [System.Text.UTF8Encoding]::new($false, $true).GetBytes($json)
}

function ConvertFrom-E2EReceiptBytes {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    if ($Bytes.Length -eq 0 -or
        ($Bytes.Length -ge 3 -and $Bytes[0] -eq 239 -and $Bytes[1] -eq 187 -and $Bytes[2] -eq 191) -or
        ($Bytes -contains [byte]13)) {
        throw 'RECEIPT_INVALID'
    }
    try {
        $text = [System.Text.UTF8Encoding]::new($false, $true).GetString($Bytes)
    }
    catch {
        throw 'RECEIPT_INVALID'
    }
    $pattern = '\A\{"schemaVersion":1,"runId":"(?<run>[0-9a-f]{32})","repositoryId":"(?<repo>[0-9a-f]{64})","commitSha":"(?<commit>(?:[0-9a-f]{40}|[0-9a-f]{64}))","treeSha":"(?<tree>(?:[0-9a-f]{40}|[0-9a-f]{64}))"\}\n\z'
    $match = [System.Text.RegularExpressions.Regex]::Match($text, $pattern, [System.Text.RegularExpressions.RegexOptions]::CultureInvariant)
    if (-not $match.Success) {
        throw 'RECEIPT_INVALID'
    }
    return New-E2EReceipt -RunId $match.Groups['run'].Value -RepositoryId $match.Groups['repo'].Value `
        -CommitSha $match.Groups['commit'].Value -TreeSha $match.Groups['tree'].Value
}

function Assert-E2EPathSafe {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    try {
        $root = [System.IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
        $candidate = [System.IO.Path]::GetFullPath($Path)
    }
    catch {
        throw 'RECEIPT_PATH_INVALID'
    }
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.Equals($root, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'RECEIPT_PATH_INVALID'
    }
    $current = $candidate
    while (-not [string]::IsNullOrEmpty($current)) {
        if ([System.IO.File]::Exists($current) -or [System.IO.Directory]::Exists($current)) {
            try {
                $attributes = [System.IO.File]::GetAttributes($current)
            }
            catch {
                throw 'RECEIPT_PATH_INVALID'
            }
            if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'RECEIPT_PATH_INVALID'
            }
        }
        if ($current.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $candidate
        }
        $parent = [System.IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrEmpty($parent) -or $parent.Equals($current, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $current = $parent
    }
    throw 'RECEIPT_PATH_INVALID'
}

function Get-E2EReceiptState {
    param(
        [Parameter(Mandatory = $true)][string]$PreparedPath,
        [Parameter(Mandatory = $true)][string]$RecoveryPath
    )

    $prepared = [System.IO.File]::Exists($PreparedPath)
    $recovery = [System.IO.File]::Exists($RecoveryPath)
    if ($prepared -and $recovery) {
        throw 'RECEIPT_STATE_INVALID'
    }
    if ($prepared) { return 'Prepared' }
    if ($recovery) { return 'Recovery' }
    return 'None'
}

function New-E2EReceiptFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $safePath = Assert-E2EPathSafe -Path $Path -RepositoryRoot $RepositoryRoot
    $parent = [System.IO.Path]::GetDirectoryName($safePath)
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    Assert-E2EPathSafe -Path $parent -RepositoryRoot $RepositoryRoot | Out-Null
    $bytes = ConvertTo-E2EReceiptBytes -Receipt $Receipt
    $stream = $null
    $primary = $null
    try {
        $stream = [System.IO.FileStream]::new(
            $safePath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    catch {
        $primary = $_.Exception
    }
    $actions = @()
    if ($null -ne $stream) {
        $actions = @([pscustomobject]@{
            Action = { $stream.Dispose() }.GetNewClosure()
            ErrorCode = 'RECEIPT_CREATE_FAILED'
            SkipAfterCleanupFailure = $false
        })
    }
    try {
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    catch {
        throw 'RECEIPT_CREATE_FAILED'
    }
}

function Read-E2EReceiptFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $safePath = Assert-E2EPathSafe -Path $Path -RepositoryRoot $RepositoryRoot
    if (-not [System.IO.File]::Exists($safePath)) {
        throw 'RECEIPT_MISSING'
    }
    try {
        $attributes = [System.IO.File]::GetAttributes($safePath)
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'RECEIPT_INVALID'
        }
        return ConvertFrom-E2EReceiptBytes -Bytes ([System.IO.File]::ReadAllBytes($safePath))
    }
    catch {
        if ($_.Exception.Message -match '^RECEIPT_') { throw }
        throw 'RECEIPT_READ_FAILED'
    }
}

function Move-E2EReceiptFile {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $safeSource = Assert-E2EPathSafe -Path $Source -RepositoryRoot $RepositoryRoot
    $safeDestination = Assert-E2EPathSafe -Path $Destination -RepositoryRoot $RepositoryRoot
    if (-not [string]::Equals([System.IO.Path]::GetDirectoryName($safeSource), [System.IO.Path]::GetDirectoryName($safeDestination), [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [System.IO.File]::Exists($safeSource) -or [System.IO.File]::Exists($safeDestination)) {
        throw 'RECEIPT_TRANSITION_FAILED'
    }
    try {
        [System.IO.File]::Move($safeSource, $safeDestination)
    }
    catch {
        throw 'RECEIPT_TRANSITION_FAILED'
    }
}

function Remove-E2EReceiptFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [scriptblock]$DeleteFile = { param([string]$Value) [System.IO.File]::Delete($Value) }
    )

    $safePath = Assert-E2EPathSafe -Path $Path -RepositoryRoot $RepositoryRoot
    if (-not [System.IO.File]::Exists($safePath)) {
        throw 'RECEIPT_DELETE_FAILED'
    }
    try {
        & $DeleteFile $safePath
    }
    catch {
        throw 'RECEIPT_DELETE_FAILED'
    }
    if ([System.IO.File]::Exists($safePath)) {
        throw 'RECEIPT_DELETE_FAILED'
    }
}

function Get-E2EImageSet {
    param([Parameter(Mandatory = $true)]$Receipt)

    Assert-E2EReceiptObject $Receipt
    $suffix = 'e2e-' + (Get-E2EReceiptValue $Receipt 'commitSha').Substring(0, 12) + '-' + (Get-E2EReceiptValue $Receipt 'runId')
    return [ordered]@{
        Backend = "finguardops-backend:$suffix"
        AiService = "finguardops-ai-service:$suffix"
        Browser = "finguardops-playwright-e2e:$suffix"
    }
}

function Get-E2EOwnershipLabels {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][ValidateSet('backend', 'ai-service', 'browser')][string]$Role
    )

    Assert-E2EReceiptObject $Receipt
    return [ordered]@{
        'org.opencontainers.image.revision' = Get-E2EReceiptValue $Receipt 'commitSha'
        'com.finguardops.e2e.source-tree' = Get-E2EReceiptValue $Receipt 'treeSha'
        'com.finguardops.e2e.run-id' = Get-E2EReceiptValue $Receipt 'runId'
        'com.finguardops.e2e.repository-id' = Get-E2EReceiptValue $Receipt 'repositoryId'
        'com.finguardops.e2e.image-role' = $Role
    }
}

function New-E2EComposeArguments {
    param([Parameter(Mandatory = $true)][string]$ProjectName)

    return @(
        'compose', '-p', $ProjectName, '--env-file', 'infra/.env.example',
        '-f', 'infra/compose.yml', '-f', 'infra/compose.keycloak-local-e2e.yml',
        'up', '-d', '--no-build', '--pull', 'never'
    )
}

function New-E2EDockerBuildArguments {
    param(
        [Parameter(Mandatory = $true)][string]$Reference,
        [Parameter(Mandatory = $true)]$Labels,
        [Parameter(Mandatory = $true)][string]$Context
    )

    if ($Reference -match ':local$') {
        throw 'IMAGE_REFERENCE_INVALID'
    }
    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add('build')
    $arguments.Add('--tag')
    $arguments.Add($Reference)
    foreach ($key in $Labels.Keys) {
        $arguments.Add('--label')
        $arguments.Add("$key=$($Labels[$key])")
    }
    $arguments.Add($Context)
    return $arguments.ToArray()
}

function New-E2EImageRemoveArguments {
    param([Parameter(Mandatory = $true)][string]$Reference)

    if ($ProtectedImageReferences -contains $Reference -or $Reference -match ':local$') {
        throw 'IMAGE_REFERENCE_INVALID'
    }
    return @('image', 'rm', '--no-prune', $Reference)
}

function Select-E2EFailure {
    param($Primary, $Cleanup)

    if ($null -ne $Primary) { return $Primary }
    return $Cleanup
}

function Get-E2ESafeCleanupFailure {
    param(
        [Parameter(Mandatory = $true)]$ErrorRecord,
        [Parameter(Mandatory = $true)][ValidatePattern('\A[A-Z][A-Z0-9_]{0,63}\z')][string]$FallbackCode
    )

    # `$` is not the end of the string. It also matches immediately before a
    # final line feed, so `RESOURCE_CLEANUP_FAILED<LF>` satisfied the old
    # pattern and was handed straight back as the operational failure - one
    # smuggled line break, in the one value this boundary exists to keep fixed,
    # and a line break is what turns one record in a run log into two. The
    # anchors are absolute here, and the candidate has to be a scalar this run
    # is willing to decide on at all before the pattern is applied to it.
    #
    # A candidate that is anything else is not repaired into a code and is not
    # echoed: the caller's own fixed fallback is raised instead, so nothing the
    # failing boundary was handed reaches the message.
    $exception = $ErrorRecord.Exception
    if ($null -ne $exception -and $exception.Message -is [string] -and
        (Test-E2ECleanScalar $exception.Message) -and
        $exception.Message -cmatch '\A[A-Z][A-Z0-9_]{0,63}\z') {
        return $exception
    }
    return [System.InvalidOperationException]::new($FallbackCode)
}

function Write-E2ESafeCleanupDiagnostic {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet(
            'E2E cleanup also failed; the primary failure is preserved.',
            'The previous E2E lifecycle owner ended without releasing the lock.'
        )]
        [string]$Message,
        [scriptblock]$Writer = {
            param([string]$Value)
            Microsoft.PowerShell.Utility\Write-Warning -Message $Value -WarningAction Continue
        }
    )

    try {
        & $Writer $Message | Out-Null
    }
    catch {
        # Diagnostics are best effort and can never replace an operational failure.
    }
}

function Invoke-E2ECleanupActions {
    param(
        $Primary,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Actions,
        [scriptblock]$DiagnosticWriter
    )

    $failure = $Primary
    $cleanupFailed = $false
    foreach ($entry in $Actions) {
        $skipProperty = $entry.PSObject.Properties['SkipAfterCleanupFailure']
        $skipAfterCleanupFailure = $null -ne $skipProperty -and [bool]$skipProperty.Value
        if ($cleanupFailed -and $skipAfterCleanupFailure) {
            continue
        }
        try {
            & $entry.Action | Out-Null
        }
        catch {
            $cleanupFailed = $true
            $safeFailure = Get-E2ESafeCleanupFailure -ErrorRecord $_ -FallbackCode $entry.ErrorCode
            $failure = Select-E2EFailure -Primary $failure -Cleanup $safeFailure
        }
    }
    if ($null -ne $Primary -and $cleanupFailed) {
        $diagnosticArguments = @{
            Message = 'E2E cleanup also failed; the primary failure is preserved.'
        }
        if ($null -ne $DiagnosticWriter) { $diagnosticArguments.Writer = $DiagnosticWriter }
        Write-E2ESafeCleanupDiagnostic @diagnosticArguments
    }
    if ($null -ne $failure) {
        throw $failure
    }
}

function Invoke-E2ERunCoreCleanup {
    param(
        $Primary,
        [Parameter(Mandatory = $true)]$Boundaries
    )

    $actions = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in @(
        @('RestoreOutputEnvironment', 'ENVIRONMENT_RESTORE_FAILED'),
        @('RestoreProjectEnvironment', 'ENVIRONMENT_RESTORE_FAILED'),
        @('RestoreBrowserEnvironment', 'ENVIRONMENT_RESTORE_FAILED'),
        @('RemoveBrowser', 'BROWSER_CONTAINER_CLEANUP_FAILED'),
        @('RemoveProjectResources', 'RESOURCE_CLEANUP_FAILED'),
        @('RemoveOutput', 'OUTPUT_DIRECTORY_CLEANUP_FAILED'),
        @('DisposeCertificate', 'CERTIFICATE_DISPOSE_FAILED'),
        @('ReleaseRunMutex', 'RUN_LOCK_RELEASE_FAILED'),
        @('DisposeRunMutex', 'RUN_LOCK_DISPOSE_FAILED')
    )) {
        if ($Boundaries.ContainsKey($definition[0])) {
            $actions.Add([pscustomobject]@{
                Action = $Boundaries[$definition[0]]
                ErrorCode = $definition[1]
                SkipAfterCleanupFailure = $false
            })
        }
    }
    Invoke-E2ECleanupActions -Primary $Primary -Actions $actions.ToArray()
}

function Invoke-E2EPrepareBrowserBuildLifecycle {
    param([Parameter(Mandatory = $true)]$Boundaries)

    $primary = $null
    try {
        & $Boundaries.Build | Out-Null
    }
    catch {
        $primary = $_.Exception
    }
    $actions = @(
        [pscustomobject]@{
            Action = $Boundaries.RemoveTemp
            ErrorCode = 'TEMP_DIRECTORY_CLEANUP_FAILED'
            SkipAfterCleanupFailure = $false
        }
    )
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
}

function Test-E2EReceiptEqual($Expected, $Actual) {
    foreach ($key in @('schemaVersion', 'runId', 'repositoryId', 'commitSha', 'treeSha')) {
        if (-not [object]::Equals((Get-E2EReceiptValue $Expected $key), (Get-E2EReceiptValue $Actual $key))) {
            return $false
        }
    }
    return $true
}

function Test-E2ECleanupTarget {
    param(
        [Parameter(Mandatory = $true)][string]$Reference,
        [Parameter(Mandatory = $true)][string]$ExpectedId,
        [Parameter(Mandatory = $true)]$Document,
        [Parameter(Mandatory = $true)]$ExpectedLabels
    )

    if ($Reference -match ':local$' -or $Document.Id -ne $ExpectedId) {
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    foreach ($key in $ExpectedLabels.Keys) {
        $actual = if ($Document.Labels -is [System.Collections.IDictionary]) { $Document.Labels[$key] } else { $Document.Labels.PSObject.Properties[$key].Value }
        if (-not [string]::Equals([string]$actual, [string]$ExpectedLabels[$key], [System.StringComparison]::Ordinal)) {
            throw 'IMAGE_OWNERSHIP_INVALID'
        }
    }
    if ($Document.InUse) {
        throw 'IMAGE_IN_USE'
    }
    return $true
}

function Invoke-E2EPrepareLifecycle {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)]$Boundaries
    )

    $before = & $Boundaries.GetSource
    if (-not (Test-E2EReceiptEqual $Receipt $before)) { throw 'SOURCE_IDENTITY_INVALID' }
    & $Boundaries.CreateRecovery $Receipt | Out-Null
    $primary = $null
    try {
        & $Boundaries.BuildImages $Receipt | Out-Null
        $after = & $Boundaries.GetSource
        if (-not (Test-E2EReceiptEqual $Receipt $after)) { throw 'SOURCE_IDENTITY_INVALID' }
        & $Boundaries.RenameRecoveryToPrepared | Out-Null
    }
    catch {
        $primary = $_.Exception
    }
    if ($null -ne $primary) {
        $actions = @([pscustomobject]@{
            Action = { & $Boundaries.Cleanup $Receipt | Out-Null }.GetNewClosure()
            ErrorCode = 'PREPARE_CLEANUP_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
}

function Invoke-E2EServiceLifecycle {
    param([Parameter(Mandatory = $true)]$Boundaries)

    $receipt = & $Boundaries.ReadPrepared
    & $Boundaries.RenamePreparedToRecovery | Out-Null
    # The image record preflight, deliberately outside the cleanup boundary.
    #
    # Nothing below has happened yet: no child process, no container runtime
    # check, no Docker resource lifecycle. A record rejected here therefore
    # describes a state this run never created, and the answer is a fixed error
    # with the prepared images and the receipt left exactly as they are - not a
    # mutation-capable cleanup driven by the very record the validator has just
    # refused to believe. Cleanup arbitration begins after the child.
    $imageValues = @(& $Boundaries.AssertImages $receipt)
    Assert-E2EImageRecordSet -Values $imageValues -Receipt $receipt
    # The first step of this mode that can create anything, and it runs only
    # here: after the validator above has accepted the record, and still
    # outside the cleanup boundary. A browser runtime failure therefore leaves
    # no child, no container check and no receipt deletion behind either - the
    # throwaway container it created answers for itself.
    & $Boundaries.AssertBrowserRuntime $receipt | Out-Null
    $primary = $null
    try {
        & $Boundaries.RunChild $receipt | Out-Null
        & $Boundaries.AssertContainers $receipt | Out-Null
        & $Boundaries.CleanupResources | Out-Null
        & $Boundaries.RenameRecoveryToPrepared | Out-Null
    }
    catch {
        $primary = $_.Exception
    }
    if ($null -ne $primary) {
        $actions = @([pscustomobject]@{
            Action = { & $Boundaries.Cleanup $receipt | Out-Null }.GetNewClosure()
            ErrorCode = 'SERVICE_CLEANUP_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
}

function Invoke-E2ERunLifecycle {
    param([Parameter(Mandatory = $true)]$Boundaries)

    $receipt = & $Boundaries.ReadPrepared
    & $Boundaries.RenamePreparedToRecovery | Out-Null
    $primary = $null
    try {
        & $Boundaries.AssertImages $receipt | Out-Null
        & $Boundaries.RunBrowser $receipt | Out-Null
    }
    catch {
        $primary = $_.Exception
    }
    $actions = @([pscustomobject]@{
        Action = { & $Boundaries.Cleanup $receipt | Out-Null }.GetNewClosure()
        ErrorCode = 'RUN_CLEANUP_FAILED'
        SkipAfterCleanupFailure = $false
    })
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
}

function Invoke-E2ECleanupLifecycle {
    param([Parameter(Mandatory = $true)]$Boundaries)

    $state = & $Boundaries.ReadSingleReceipt
    & $Boundaries.FullCleanup $state | Out-Null
}

function Assert-Success([string]$Operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed."
    }
}

# Runs a native command for its standard output without letting its diagnostics
# become the failure.
#
# Windows PowerShell turns every stderr line of a native command into an
# ErrorRecord, and under `$ErrorActionPreference = 'Stop'` that is terminating.
# Docker writes ordinary progress lines, and its `No such image` answer, to
# stderr; whether an image is present locally is a question this script asks on
# purpose. The exit code, which every caller checks, stays the only verdict.
#
# The output is also decoded as what Docker actually wrote, which is UTF-8.
# Windows PowerShell decodes a native command's standard output with
# `[Console]::OutputEncoding`, and that is the console code page - 949 on this
# Korean Windows, 437 or 850 elsewhere - not UTF-8. Every byte above 0x7F in a
# path therefore came back as a different character than the daemon recorded,
# so a repository under a directory named in Hangul, or under any other
# non-ASCII name, produced an observed bind source that could not equal the
# approved one no matter how the container had been created.
#
# The correction belongs here, at the decoder, and nowhere near the comparison.
# Nothing below is relaxed by it, and every way it can go wrong stops the run
# rather than changing what a comparison is given to read:
#
#   * the code page is process-global, so the window in which it is UTF-8 is
#     held under `$ConsoleEncodingLockName` for exactly one caller at a time.
#     A lock this call cannot take means the command is not run at all;
#   * whether the previous encoding was read at all is tracked separately from
#     what was read, because a value that was never read is not a value to
#     restore;
#   * a console that refuses UTF-8 fails here, before the command runs. A run
#     that continued would be decoding paths with a code page that cannot spell
#     them, which is the defect this exists to remove;
#   * the previous code page is restored on every path, success or failure, and
#     the restoration is read back and compared rather than assumed. A console
#     that will not go back is a failure of this run.
#
# Cleanup runs to completion before any of that is reported: the encoding, the
# lock and the mutex object are each dealt with first, and only then is a
# failure raised. Every message is a fixed sentence. Nothing a native command
# wrote, and no path, identifier or credential, is reflected into one.
function Invoke-NativeStdout([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $lock = $null
    $state = [pscustomobject]@{ LockOwned = $false }
    $previousEncoding = $null
    $readEncoding = $false
    $changedEncoding = $false
    $result = $null
    $primary = $null
    try {
        try {
            $lock = New-Object System.Threading.Mutex($false, $ConsoleEncodingLockName)
        }
        catch {
            throw 'The console encoding lock could not be created.'
        }
        try {
            $state.LockOwned = $lock.WaitOne($ConsoleEncodingLockTimeoutMilliseconds)
        }
        catch [System.Threading.AbandonedMutexException] {
            # Held by this call now. The encoding is read and compared below
            # regardless, so nothing is assumed about what the abandoning caller
            # left the console in.
            $state.LockOwned = $true
        }
        catch {
            throw 'The console encoding lock could not be acquired.'
        }
        if (-not $state.LockOwned) {
            throw 'The console encoding lock could not be acquired.'
        }

        try {
            $previousEncoding = [Console]::OutputEncoding
            $readEncoding = $true
        }
        catch {
            throw 'The console output encoding could not be read.'
        }

        if ($previousEncoding.CodePage -ne 65001) {
            try {
                # Marked as changed before the assignment, so a setter that
                # fails half way is still put back by the cleanup below.
                $changedEncoding = $true
                # Without a preamble: a BOM would be written into this console's
                # own output, not just used to decode Docker's.
                [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
            }
            catch {
                throw 'The console output encoding could not be set to UTF-8.'
            }
            if ([Console]::OutputEncoding.CodePage -ne 65001) {
                throw 'The console output encoding could not be set to UTF-8.'
            }
        }

        $ErrorActionPreference = 'Continue'
        $result = (& $Command | Out-String)
    }
    catch {
        $primary = $_.Exception
    }
    $ErrorActionPreference = $previous
    $actions = @(
        [pscustomobject]@{
            Action = {
                if ($changedEncoding -and $readEncoding) {
                    if ([Console]::OutputEncoding.CodePage -ne $previousEncoding.CodePage) {
                        [Console]::OutputEncoding = $previousEncoding
                    }
                    if ([Console]::OutputEncoding.CodePage -ne $previousEncoding.CodePage) {
                        throw 'CONSOLE_ENCODING_RESTORE_FAILED'
                    }
                }
            }.GetNewClosure()
            ErrorCode = 'CONSOLE_ENCODING_RESTORE_FAILED'
            SkipAfterCleanupFailure = $false
        },
        [pscustomobject]@{
            Action = {
                if ($state.LockOwned) {
                    $state.LockOwned = $false
                    $lock.ReleaseMutex()
                }
            }.GetNewClosure()
            ErrorCode = 'CONSOLE_LOCK_RELEASE_FAILED'
            SkipAfterCleanupFailure = $false
        },
        [pscustomobject]@{
            Action = { if ($null -ne $lock) { $lock.Dispose() } }.GetNewClosure()
            ErrorCode = 'CONSOLE_LOCK_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        }
    )
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    return $result
}

# The same, for a command run for its exit code and its console output rather
# than for a value. Docker and Compose write their progress to stderr, so a
# caller that captures this script's output would otherwise turn a build's
# ordinary progress lines into a terminating error.
function Invoke-Native([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $primary = $null
    try {
        & $Command
    }
    catch { $primary = $_.Exception }
    $ErrorActionPreference = $previous
    if ($null -ne $primary) { throw $primary }
}

# Reads a member of a `ConvertFrom-Json` result without assuming it is there.
# Under `Set-StrictMode -Version Latest` a missing member is a terminating
# error, and several of the documents read below legitimately omit members: the
# base image, for one, declares no `Config.User` at all.
function Get-JsonMember($Object, [string]$Name) {
    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Test-ByteEquality([byte[]]$Left, [byte[]]$Right) {
    if ($Left.Length -ne $Right.Length) {
        return $false
    }
    $difference = 0
    for ($index = 0; $index -lt $Left.Length; $index++) {
        $difference = $difference -bor ($Left[$index] -bxor $Right[$index])
    }
    return $difference -eq 0
}

function Get-TrimmedPath([string]$Path) {
    return $Path.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
}

# The one physical location a path this repository owns actually denotes.
#
# Every host path this script hands to Docker goes through here first, and what
# comes back is what the approved mount configuration is written in terms of.
# A junction, a symbolic link or any other reparse point anywhere between the
# repository root and the target stops the run, because a link is a name that
# can be repointed after it was checked: the physical path is the only thing a
# mount comparison can be honest about.
#
# Errors name the rule and nothing else. No path, no attribute and no observed
# value is echoed back into the run log.
function Get-OwnedPhysicalPath([string]$Path, [switch]$Directory) {
    $repository = Get-TrimmedPath ([System.IO.Path]::GetFullPath($RepositoryRoot))
    $candidate = Get-TrimmedPath ([System.IO.Path]::GetFullPath($Path))
    $prefix = $repository + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'An owned path escaped the repository.'
    }
    if ($Directory) {
        if (-not [System.IO.Directory]::Exists($candidate)) {
            throw 'An owned directory is missing.'
        }
    }
    elseif (-not [System.IO.File]::Exists($candidate)) {
        throw 'An owned file is missing.'
    }

    $current = Get-Item -LiteralPath $candidate -Force
    while ($true) {
        if (($current.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'An owned path contains a link or reparse point.'
        }
        if ((Get-TrimmedPath $current.FullName).Equals($repository, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $parent = Split-Path -Parent $current.FullName
        if ([string]::IsNullOrEmpty($parent)) {
            throw 'An owned path escaped the repository.'
        }
        $current = Get-Item -LiteralPath $parent -Force
    }
    return $candidate
}

function Get-UniqueExtension(
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
    [string]$Oid
) {
    $matches = @($Certificate.Extensions | Where-Object { $_.Oid.Value -eq $Oid })
    if ($matches.Count -ne 1) {
        throw 'The certificate extension set is invalid.'
    }
    return $matches[0]
}

# Reads and inspects the certificate file only. No store of any kind is opened,
# and the container repeats these checks, plus the self-signature and the
# private-key match, before anything is trusted.
function Assert-SafeCertificate([string]$Path) {
    $physical = Get-OwnedPhysicalPath $Path
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        [System.IO.File]::ReadAllBytes($physical)
    )
    try {
        if ($certificate.HasPrivateKey) {
            throw 'The public certificate file unexpectedly contains a private key.'
        }

        $basicRaw = Get-UniqueExtension $certificate '2.5.29.19'
        $basic = [System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new(
            $basicRaw,
            $basicRaw.Critical
        )
        if (-not $basic.Critical -or $basic.CertificateAuthority -or $basic.HasPathLengthConstraint) {
            throw 'The certificate basic constraints are unsafe.'
        }

        $usageRaw = Get-UniqueExtension $certificate '2.5.29.15'
        $usage = [System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
            $usageRaw,
            $usageRaw.Critical
        )
        $expectedUsage = [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor
            [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
        if (-not $usage.Critical -or [int]$usage.KeyUsages -ne [int]$expectedUsage) {
            throw 'The certificate key usage is unsafe.'
        }

        $ekuRaw = Get-UniqueExtension $certificate '2.5.29.37'
        $eku = [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new(
            $ekuRaw,
            $ekuRaw.Critical
        )
        $ekuValues = @($eku.EnhancedKeyUsages | ForEach-Object { $_.Value })
        if ($eku.Critical -or $ekuValues.Count -ne 1 -or $ekuValues[0] -ne '1.3.6.1.5.5.7.3.1') {
            throw 'The certificate extended key usage is unsafe.'
        }

        $san = Get-UniqueExtension $certificate '2.5.29.17'
        $expectedSan = [byte[]](0x30, 0x0b, 0x82, 0x09, 0x6c, 0x6f, 0x63, 0x61, 0x6c, 0x68, 0x6f, 0x73, 0x74)
        if ($san.Critical -or -not (Test-ByteEquality $san.RawData $expectedSan)) {
            throw 'The certificate subject alternative name is unsafe.'
        }

        if (-not $certificate.Subject.Equals($certificate.Issuer, [System.StringComparison]::Ordinal)) {
            throw 'The certificate is not self-issued.'
        }

        $rsa = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($certificate)
        if ($null -eq $rsa) {
            throw 'The certificate public key is not RSA.'
        }
        $rsaPrimary = $null
        try {
            if ($rsa.KeySize -lt 3072) {
                throw 'The certificate RSA key is too small.'
            }
        }
        catch { $rsaPrimary = $_.Exception }
        $rsaActions = @([pscustomobject]@{
            Action = { $rsa.Dispose() }.GetNewClosure()
            ErrorCode = 'CERTIFICATE_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $rsaPrimary -Actions $rsaActions

        $allowedSignatures = @(
            '1.2.840.113549.1.1.11',
            '1.2.840.113549.1.1.12',
            '1.2.840.113549.1.1.13'
        )
        if ($certificate.SignatureAlgorithm.Value -notin $allowedSignatures) {
            throw 'The certificate signature algorithm is too weak.'
        }

        $now = [datetime]::UtcNow
        $notBefore = $certificate.NotBefore.ToUniversalTime()
        $notAfter = $certificate.NotAfter.ToUniversalTime()
        if ($notBefore -gt $now -or $notAfter -le $now) {
            throw 'The certificate is not currently valid.'
        }
        if (($notAfter - $notBefore) -gt [timespan]::FromDays(30)) {
            throw 'The certificate lifetime exceeds 30 days.'
        }

        return $certificate
    }
    catch {
        $primary = $_.Exception
        $actions = @([pscustomobject]@{
            Action = { $certificate.Dispose() }.GetNewClosure()
            ErrorCode = 'CERTIFICATE_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
}

function Invoke-E2EInLocation {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][scriptblock]$Body
    )

    Push-Location $Path
    $primary = $null
    try {
        & $Body
    }
    catch {
        $primary = $_.Exception
    }
    $actions = @([pscustomobject]@{
        Action = { Pop-Location }
        ErrorCode = 'LOCATION_RESTORE_FAILED'
        SkipAfterCleanupFailure = $false
    })
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
}

# The Node executable every JavaScript entry point in this run is handed to.
#
# Resolved once, as a path, and then passed to PowerShell's call operator with
# its arguments as separate array elements. Nothing parses that as a command
# line, so a path holding spaces or non-ASCII characters is data rather than
# syntax and needs no quoting to stay safe.
function Get-NodeExecutable {
    $command = @(Get-Command node -CommandType Application -ErrorAction SilentlyContinue) |
        Select-Object -First 1
    if ($null -eq $command -or -not [System.IO.File]::Exists($command.Source)) {
        throw 'Node is not available on PATH.'
    }
    return $command.Source
}

# An installed package's own version, read from its manifest. No process is
# started to ask, so this answers the same on a machine where npm, npx and the
# network are all unavailable.
function Get-InstalledPackageVersion([string]$PackagePath) {
    $manifest = Join-Path $PackagePath 'package.json'
    if (-not [System.IO.File]::Exists($manifest)) {
        return $null
    }
    try {
        $document = [System.IO.File]::ReadAllText($manifest) | ConvertFrom-Json
    }
    catch {
        return $null
    }
    $version = Get-JsonMember $document 'version'
    if ($version -isnot [string] -or $version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
        return $null
    }
    return $version
}

# Resolves an installed entry point to an absolute file, or stops.
#
# A package name, a bin shim or an npm script would each be a name that
# something else gets to resolve, and the last resort of every one of those
# resolvers is a registry. A file path under `frontend/node_modules` has no
# fallback: it is either the installed file or a fixed error.
function Assert-LocalNodeEntrypoint(
    [string]$PackagePath,
    [string]$RelativeEntrypoint,
    [string]$ExpectedVersion,
    [string]$MissingMessage,
    [string]$VersionMessage
) {
    if ((Get-InstalledPackageVersion $PackagePath) -ne $ExpectedVersion) {
        throw $VersionMessage
    }
    $entrypoint = Join-Path $PackagePath $RelativeEntrypoint
    if (-not [System.IO.File]::Exists($entrypoint)) {
        throw $MissingMessage
    }
    return [System.IO.Path]::GetFullPath($entrypoint)
}

# The browser server is this repository's own pinned playwright-core, mounted
# read-only, so the server and the client are the same installed package rather
# than two versions that merely agree on a tag. The script inside refuses to
# start if those versions ever disagree, and the prepared image has to carry
# this same version, and the browser revisions it implies, before the container
# is started at all.
function Get-PlaywrightVersion {
    if ((Get-InstalledPackageVersion $PlaywrightCorePath) -ne $ExpectedPlaywrightVersion) {
        throw ("playwright-core {0} is not installed. Run npm ci in frontend first." -f $ExpectedPlaywrightVersion)
    }
    if (-not [System.IO.File]::Exists((Join-Path $PlaywrightCorePath 'cli.js'))) {
        throw 'The installed playwright-core carries no CLI entry point. Run npm ci in frontend first.'
    }
    if ((Get-InstalledPackageVersion $PlaywrightTestPath) -ne $ExpectedPlaywrightVersion) {
        throw ("@playwright/test {0} is not installed. Run npm ci in frontend first." -f $ExpectedPlaywrightVersion)
    }
    return $ExpectedPlaywrightVersion
}

# Every image the merged Compose configuration names, read from that merged
# configuration rather than from a list kept in parallel with it, so a service
# added to Compose cannot slip past the presence check below.
function Get-ComposeServiceImages {
    $merged = Invoke-E2EInLocation -Path $RepositoryRoot -Body {
        $value = Invoke-NativeStdout { & docker @ComposeArguments config --format json }
        Assert-Success 'Dedicated Compose configuration read'
        return $value
    }
    $configuration = $merged | ConvertFrom-Json
    $images = [ordered]@{}
    foreach ($service in $configuration.services.PSObject.Properties) {
        $declared = $service.Value.PSObject.Properties['image']
        if ($null -eq $declared -or [string]::IsNullOrWhiteSpace($declared.Value)) {
            throw "Compose service $($service.Name) declares no image."
        }
        $images[$service.Name] = $declared.Value
    }
    if ($images.Count -eq 0) {
        throw 'The merged Compose configuration declares no service.'
    }
    return $images
}

# `docker image inspect` is answered entirely from the local image store and
# never contacts a registry, so asking this question costs no network request,
# no registry metadata lookup and no authentication, even when the answer is no.
function Get-LocalImageIdentifier([string]$Reference) {
    $identifier = (Invoke-NativeStdout { & docker image inspect --format '{{.Id}}' $Reference }).Trim()
    if ($LASTEXITCODE -ne 0 -or $identifier -notmatch '\Asha256:[0-9a-f]{64}\z') {
        return $null
    }
    return $identifier
}

# The official run reaches no registry, so a missing image has to become a fixed
# error here rather than an implicit pull three commands later.
function Assert-ComposeImagesPresent {
    $images = Get-ComposeServiceImages
    $missing = @()
    foreach ($service in $images.GetEnumerator()) {
        if ($null -eq (Get-LocalImageIdentifier $service.Value)) {
            $missing += "$($service.Key) -> $($service.Value)"
        }
    }
    if ($missing.Count -ne 0) {
        throw ("These Compose images are not present locally: {0}. Run -Mode Prepare first." -f ($missing -join '; '))
    }
}

# A label is either present and exactly right, or the run stops. A missing
# label reads as "prepared by something else" here, not as "unknown".
#
# Labels alone decide nothing. Anyone can write any label onto any image, so
# every claim a label makes below is also checked against the thing it claims:
# the base digest against the base image's own filesystem layers, the Playwright
# version against the browser revisions actually installed in the image, the
# `certutil` version against dpkg's database inside a running container.
function Assert-ImageLabel($Labels, [string]$Name, [string]$Expected, [string]$Message) {
    $declared = Get-JsonMember $Labels $Name
    if ($null -eq $declared -or -not [string]::Equals($declared, $Expected, [System.StringComparison]::Ordinal)) {
        throw $Message
    }
}

# The full `docker image inspect` document for a local reference, or `$null`.
# Answered entirely from the local image store: no registry request, no metadata
# lookup and no authentication, even when the answer is no.
function Get-LocalImageDocument([string]$Reference) {
    $encoded = Invoke-NativeStdout { & docker image inspect --format '{{json .}}' $Reference }
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    try {
        $document = $encoded | ConvertFrom-Json
    }
    catch {
        return $null
    }
    if ($document -is [array]) {
        if ($document.Count -ne 1) {
            return $null
        }
        $document = $document[0]
    }
    return $document
}

# One platform, named explicitly. An image for another architecture would be
# emulated or refused at `docker run`, and either way it is not the image whose
# contents were checked here.
function Assert-ImagePlatform($Document, [string]$Message) {
    $variant = Get-JsonMember $Document 'Variant'
    if ((Get-JsonMember $Document 'Os') -ne $BrowserPlatformOs -or
        (Get-JsonMember $Document 'Architecture') -ne $BrowserPlatformArchitecture -or
        -not [string]::IsNullOrEmpty($variant)) {
        throw $Message
    }
}

# The image's filesystem, as the ordered list of layer digests it is composed
# of. This is the part of an image that cannot be forged by relabelling: two
# images share a layer digest only when they share those exact bytes.
function Get-ImageLayers($Document, [string]$Message) {
    $rootFs = Get-JsonMember $Document 'RootFS'
    if ($null -eq $rootFs -or (Get-JsonMember $rootFs 'Type') -ne 'layers') {
        throw $Message
    }
    $layers = @(Get-JsonMember $rootFs 'Layers')
    if ($layers.Count -eq 0) {
        throw $Message
    }
    foreach ($layer in $layers) {
        if ($layer -isnot [string] -or $layer -notmatch '\Asha256:[0-9a-f]{64}\z') {
            throw $Message
        }
    }
    return $layers
}

# Resolves the prepared browser image to the exact image ID this run will use,
# having proved that it is the pinned base plus this repository's one layer.
#
# Returning the ID rather than the tag is the point. A tag is a mutable name:
# between this check and `docker run` it can be moved to a different image, and
# the run would then start something that was never verified. Everything
# downstream addresses the image by the identifier proven here, and the started
# container is checked against it again.
#
# The labels are still read, because a label that disagrees is a useful early
# stop. They are not what makes this safe. An image built from any base at all
# can carry every label this repository writes, so the questions that actually
# decide the answer are about the image's own filesystem: is the pinned base
# digest present locally, is this image linux/amd64 like that base, is that
# base's layer list an exact ordered prefix of this image's layer list, and does
# this image add exactly the one layer `Dockerfile.playwright-e2e` produces.
# A copied label survives none of those.
function Assert-BrowserImage([string]$PlaywrightVersion) {
    $baseDocument = Get-LocalImageDocument $BrowserBaseImage
    if ($null -eq $baseDocument) {
        throw 'The pinned Playwright base image is not present locally. Run -Mode Prepare first.'
    }
    $customDocument = Get-LocalImageDocument $BrowserImage
    if ($null -eq $customDocument) {
        throw "The prepared browser image $BrowserImage is not present locally. Run -Mode Prepare first."
    }

    $identifier = Get-JsonMember $customDocument 'Id'
    if ($identifier -isnot [string] -or $identifier -notmatch '\Asha256:[0-9a-f]{64}\z') {
        throw 'The prepared browser image identifier could not be read.'
    }

    # The local base is addressed by digest, so the daemon resolved the pinned
    # content; this confirms the resolution rather than trusting the lookup.
    $repositoryDigests = @(Get-JsonMember $baseDocument 'RepoDigests')
    if ($repositoryDigests -notcontains $BrowserBaseImage) {
        throw 'The local Playwright base image does not carry the pinned base digest.'
    }

    Assert-ImagePlatform $baseDocument 'The pinned Playwright base image is not a linux/amd64 image.'
    Assert-ImagePlatform $customDocument 'The prepared browser image is not a linux/amd64 image.'

    $baseLayers = Get-ImageLayers $baseDocument 'The pinned Playwright base image has no readable filesystem layers.'
    $customLayers = Get-ImageLayers $customDocument 'The prepared browser image has no readable filesystem layers.'
    if ($baseLayers.Count -ne $BrowserBaseLayerCount) {
        throw 'The pinned Playwright base image does not carry the expected filesystem layers.'
    }
    if ($customLayers.Count -ne ($BrowserBaseLayerCount + $BrowserAddedLayerCount)) {
        throw 'The prepared browser image adds an unexpected number of filesystem layers to the pinned base.'
    }
    for ($index = 0; $index -lt $baseLayers.Count; $index++) {
        if (-not [string]::Equals($customLayers[$index], $baseLayers[$index], [System.StringComparison]::Ordinal)) {
            throw 'The prepared browser image was not built on the pinned Playwright base filesystem.'
        }
    }

    $configuration = Get-JsonMember $customDocument 'Config'
    if ($null -eq $configuration) {
        throw 'The prepared browser image carries no configuration.'
    }
    $user = Get-JsonMember $configuration 'User'
    if (-not [string]::Equals($user, $BrowserUser, [System.StringComparison]::Ordinal)) {
        throw 'The prepared browser image does not run as the unprivileged pwuser account.'
    }

    $labels = Get-JsonMember $configuration 'Labels'
    if ($null -eq $labels) {
        throw 'The prepared browser image carries no labels.'
    }
    Assert-ImageLabel $labels 'org.opencontainers.image.base.name' 'mcr.microsoft.com/playwright' `
        'The prepared browser image names an unexpected base image.'
    Assert-ImageLabel $labels 'org.opencontainers.image.base.digest' $BrowserBaseDigest `
        'The prepared browser image was not built from the pinned Playwright base digest.'
    Assert-ImageLabel $labels 'com.finguardops.e2e.playwright.version' $PlaywrightVersion `
        ("The prepared browser image is not labelled Playwright {0}, which is what this checkout installs. Run -Mode Prepare again." -f $PlaywrightVersion)
    Assert-ImageLabel $labels 'com.finguardops.e2e.certutil.package' 'libnss3-tools' `
        'The prepared browser image names an unexpected certutil package.'
    Assert-ImageLabel $labels 'com.finguardops.e2e.certutil.version' $LibNss3ToolsVersion `
        'The prepared browser image carries an unexpected libnss3-tools version.'

    return $identifier
}

# --- Container confinement, decided before anything is allowed to run -------
#
# `docker run` is a single step: by the time there is a container to inspect, it
# is already executing. Everything below therefore takes three:
#
#   1. `docker create`, which produces a stopped container and an identifier.
#   2. `docker container inspect`, read back from the daemon's own record.
#   3. an exact comparison against the configuration approved in this file,
#      after which the exact identifier that was checked - never a name, which
#      can be moved onto another container in between - is started.
#
# The comparison is exhaustive rather than a hunt for known-bad shapes. Mounts,
# tmpfs entries, published ports, network mode, capabilities, security options
# and privilege each have to equal the approved value; anything this file does
# not name is rejected because it was not named. A configuration nobody thought
# of is therefore rejected too, which is the property a list of forbidden shapes
# cannot have.
#
# This is also the only place where "was this mount asked for?" can be answered.
# Inside a container a `--tmpfs /dev/shm/x` is indistinguishable from the
# `/dev/shm` the daemon mounts itself, and a bind under `/proc` or `/sys` is
# indistinguishable from the kernel-virtual mounts runc creates there. The mount
# table the image sees is checked as well, but as defence in depth about the
# image's own view of the world; whether a user mount exists is settled here,
# from `HostConfig`, before the first instruction runs.
#
# Every rejection is a fixed sentence naming a rule. No source path, no mount
# value, no image or container identifier is ever reflected back into the log.

# A JSON boolean, as a boolean. `docker container inspect` omits several of
# these rather than writing `false`, and an absent member has to read as "not
# set" rather than as a comparison that quietly succeeds.
# The member names of a `ConvertFrom-Json` object, as a plain array.
#
# Read one member at a time rather than through member enumeration: under
# `Set-StrictMode -Version Latest`, projecting a property across an empty
# collection is an error, and an empty JSON object - `PortBindings` on a
# container that publishes nothing, for one - is exactly that case.
function Get-JsonMemberNames($Object) {
    if ($null -eq $Object) {
        return @()
    }
    $names = @()
    foreach ($property in $Object.PSObject.Properties) {
        $names += $property.Name
    }
    return $names
}

function Test-JsonFlag($Value) {
    return ($Value -is [bool]) -and $Value
}

# The comparison every ownership decision below is made with.
#
# PowerShell's string operators compare through a culture, and on this platform
# the invariant culture treats a Unicode Format character as no character at
# all. `-ceq`, `-cne`, `-ccontains`, `-cnotcontains`, `-cin` and `-cnotin` each
# answer that `finguardops-kc241-e2e-local` and the same name carrying a
# SOFT HYPHEN, a ZERO WIDTH NON-JOINER, a ZERO WIDTH JOINER, a WORD JOINER or a
# ZERO WIDTH NO-BREAK SPACE are one string - and `-eq`/`-ne` additionally
# answer that they are one string in either letter case. Every value these
# decisions are taken on comes from the daemon, so a foreign container,
# network, volume or image could be spelled into carrying a label, a name or an
# identifier this run would otherwise have refused.
#
# So the decision is taken on the UTF-16 sequence itself. Nothing is trimmed,
# stripped or normalized first and nothing already approved becomes refused: a
# candidate whose sequence differs from the expected one by a single character
# is a different value and is refused as the value it is. A value that is not a
# string, on either side, is a difference as well.
function Test-E2EOrdinalEqual($Left, $Right) {
    if ($Left -isnot [string] -or $Right -isnot [string]) {
        return $false
    }
    return [string]::Equals([string]$Left, [string]$Right, [System.StringComparison]::Ordinal)
}

# The same decision for a canonical Windows path, where letter case is the one
# difference Windows itself does not treat as a difference. Everything else,
# including a smuggled Format character, still is one.
function Test-E2EOrdinalPathEqual($Left, $Right) {
    if ($Left -isnot [string] -or $Right -isnot [string]) {
        return $false
    }
    return [string]::Equals([string]$Left, [string]$Right, [System.StringComparison]::OrdinalIgnoreCase)
}

# Membership, decided one element at a time by the comparison above rather than
# by the collection operators, which compare through the same culture.
function Test-E2EOrdinalContains($Values, $Candidate) {
    if ($Candidate -isnot [string]) {
        return $false
    }
    foreach ($value in @($Values)) {
        if (Test-E2EOrdinalEqual $value $Candidate) {
            return $true
        }
    }
    return $false
}

# Two sequences: the same count, and the same value at each position.
function Test-E2EOrdinalSequenceEqual($Expected, $Actual) {
    $left = @($Expected)
    $right = @($Actual)
    if ($left.Count -ne $right.Count) {
        return $false
    }
    for ($index = 0; $index -lt $left.Count; $index++) {
        if (-not (Test-E2EOrdinalEqual $left[$index] $right[$index])) {
            return $false
        }
    }
    return $true
}

# Two sets, compared through an explicit ordinal comparer. A repeated value on
# either side is not a set and is refused rather than collapsed.
function Test-E2EOrdinalSetEqual($Expected, $Actual) {
    $left = @($Expected)
    $right = @($Actual)
    if ($left.Count -ne $right.Count) {
        return $false
    }
    $remaining = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($value in $left) {
        if ($value -isnot [string] -or -not $remaining.Add([string]$value)) {
            return $false
        }
    }
    foreach ($value in $right) {
        if ($value -isnot [string] -or -not $remaining.Remove([string]$value)) {
            return $false
        }
    }
    return $remaining.Count -eq 0
}

function Assert-NoEntries($Value, [string]$Message) {
    if ($null -eq $Value) {
        return
    }
    if (@($Value).Count -ne 0) {
        throw $Message
    }
}

function Assert-ExactStrings($Value, [string[]]$Expected, [string]$Message) {
    $observed = @()
    if ($null -ne $Value) {
        $observed = @($Value)
    }
    if (-not (Test-E2EOrdinalSequenceEqual $Expected $observed)) {
        throw $Message
    }
}

# A JSON object compared as a complete map: the same keys, no more, and the
# exact same value under each one.
function Assert-ExactMap($Value, $Expected, [string]$Message) {
    if (@(Get-JsonMemberNames $Value).Count -ne $Expected.Count) {
        throw $Message
    }
    foreach ($key in $Expected.Keys) {
        $observed = Get-JsonMember $Value $key
        if ($observed -isnot [string] -or
            -not [string]::Equals($observed, $Expected[$key], [System.StringComparison]::Ordinal)) {
            throw $Message
        }
    }
}

# A scalar this run is about to decide a security question on, examined before
# anything has had a chance to normalize it.
#
# `[System.IO.Path]::GetFullPath` drops a trailing control character on this
# platform: `C:\repo\infra` and `C:\repo\infra<LF>` normalize to one string, so
# a Compose label, a bind source or a mount source carrying a smuggled line
# break would compare equal to the approved path and be approved as it. A line
# break is also what turns one value into two for anything that reads a label
# or a daemon's answer a line at a time.
#
# So the spelling is decided here rather than repaired later. The value is not
# trimmed and it is not rewritten: a scalar carrying a control character, a
# Unicode line separator or a Unicode paragraph separator anywhere in it is
# refused as the value it is, and each boundary stops on the fixed error it
# already had without echoing what it was handed.
#
# Nothing else is refused, and nothing already approved becomes refused. Each
# character is decided by its own Unicode category, so a space, a non-ASCII
# letter, a path separator and a drive colon each still mean what they meant,
# and the canonical whole-path comparison that follows is unchanged.
function Test-E2ECleanScalar($Value) {
    if ($Value -isnot [string]) {
        return $false
    }
    foreach ($character in $Value.ToCharArray()) {
        $category = [System.Globalization.CharUnicodeInfo]::GetUnicodeCategory($character)
        if ($category -eq [System.Globalization.UnicodeCategory]::Control -or
            $category -eq [System.Globalization.UnicodeCategory]::LineSeparator -or
            $category -eq [System.Globalization.UnicodeCategory]::ParagraphSeparator) {
            return $false
        }
    }
    return $true
}

#
# `GetFullPath` will happily turn `C:\review\sibling\..\approved` into
# `C:\review\approved`, which would make a bind of a *different* directory
# compare equal to the approved one. So the spelling is decided before
# `GetFullPath` is allowed to touch it, and only three differences survive that
# decision - each of them a difference Windows itself treats as no difference:
# which separator was written, the letter case, and a single trailing separator.
#
# Everything else is refused as a spelling rather than repaired into one: a `.`
# or `..` segment, a repeated separator, an empty segment, a drive-relative
# path (`C:approved`), a UNC path, and a colon anywhere but after the drive
# letter.
#
# The segments are split on the separators and examined one at a time rather
# than searched for as substrings. `..` is a segment, not a sequence of
# characters: a directory honestly named `notes..archive` is not a traversal and
# is not rejected for containing two dots.
function Test-CanonicalWindowsPath([string]$Path) {
    # A drive letter, a colon, and the root separator that must follow it. This
    # is what refuses a UNC path, a drive-relative path and a malformed colon
    # before anything else is looked at.
    if ($Path -notmatch '^[A-Za-z]:[\\/]') {
        return $false
    }
    $rest = $Path.Substring(3)
    if ($rest.Length -gt 0) {
        # Exactly one trailing separator is a spelling of the same directory. A
        # second one is an empty segment and is caught below.
        $last = $rest[$rest.Length - 1]
        if ($last -eq '\' -or $last -eq '/') {
            $rest = $rest.Substring(0, $rest.Length - 1)
        }
    }
    if ($rest.Length -eq 0) {
        # The root itself. Its separator is structural rather than trailing.
        return $true
    }
    foreach ($segment in $rest.Split([char[]]('\', '/'))) {
        if ($segment.Length -eq 0) {
            return $false
        }
        if ($segment -eq '.' -or $segment -eq '..') {
            return $false
        }
        if ($segment.Contains(':')) {
            return $false
        }
    }
    return $true
}

# Windows path equality, on the physical path.
#
# Both sides have already been resolved to a link-free location under this
# repository, so this is a comparison of one full path against another: a path
# that merely shares a prefix, a junction that happens to lead to the same
# directory, or a path on a different drive is a different string and is
# rejected.
#
# Exactly three spellings are canonicalized away, and each of them is a spelling
# Windows itself treats as the same path:
#
#   * the separator, because `C:/a/b` and `C:\a\b` name one directory - the
#     daemon records a bind source verbatim, so which one comes back is decided
#     by how the argument happened to be written;
#   * a single trailing separator, for the same reason;
#   * letter case, because that is what "the same path" means on this platform.
#
# `Test-CanonicalWindowsPath` decides that the observed path is written in one
# of those spellings and nothing else; `GetFullPath` then settles the first two
# and `OrdinalIgnoreCase` the third, and the result is still one whole string
# compared against another whole string. Nothing else is relaxed: this is never
# a prefix, suffix or substring test, and a path that resolves through a link is
# not resolved for the comparison, so a junction or other reparse point cannot
# be spelled to look like the approved source.
function Test-SamePhysicalPath($Observed, [string]$Expected) {
    # Decided before `GetFullPath` is allowed to touch either side, because
    # normalizing is what would hide a smuggled line break rather than reveal it.
    if (-not (Test-E2ECleanScalar $Observed) -or -not (Test-E2ECleanScalar $Expected)) {
        return $false
    }
    if ($Observed -isnot [string] -or [string]::IsNullOrWhiteSpace($Observed)) {
        return $false
    }
    if (-not (Test-CanonicalWindowsPath $Observed)) {
        return $false
    }
    $normalized = $null
    try {
        $normalized = Get-TrimmedPath ([System.IO.Path]::GetFullPath($Observed))
    }
    catch {
        return $false
    }
    return Test-E2EOrdinalPathEqual $normalized $Expected
}

# The one non-Windows spelling of a Windows host path this script recognises.
#
# Docker Desktop runs the daemon inside a Linux virtual machine, and a Windows
# host path bound into a container is recorded there as that machine's view of
# it: `/run/desktop/mnt/host/<drive-letter>/<rest>`, with every separator a
# forward slash. That is a spelling of one Windows path, and it is the only
# non-Windows spelling accepted anywhere below.
#
# What is recognised is the literal prefix and nothing else. The prefix is
# matched in full and anchored at the start; the drive letter is the single
# character the prefix itself carries, so a path on another drive carries
# another letter and becomes another Windows path; the remainder is turned back
# into a Windows path and handed to the same canonical whole-path comparison
# every other source goes through. Nothing here is a prefix, suffix or
# substring test of the observed value against the expected one, and a
# remainder that is empty, that is itself absolute, or that carries a backslash
# is not this representation and is refused.
function ConvertFrom-E2EDockerDesktopHostPath($Observed) {
    if ($Observed -isnot [string]) {
        return $null
    }
    # Before the prefix is matched and before the remainder is turned back into
    # a Windows path: a contaminated value is not this representation.
    if (-not (Test-E2ECleanScalar $Observed)) {
        return $null
    }
    if ($Observed -cnotmatch '^/run/desktop/mnt/host/(?<drive>[A-Za-z])/(?<rest>[^/\\].*)$') {
        return $null
    }
    $rest = $Matches['rest']
    if ($rest.Contains('\')) {
        return $null
    }
    return $Matches['drive'] + ':\' + $rest.Replace('/', '\')
}

# A bind source, in either spelling the daemon can record it in.
#
# The Windows spelling is decided exactly as before. The Docker Desktop
# spelling is first turned back into the Windows path it denotes and then
# decided by that same comparison, so the set of paths this approves is the set
# `Test-SamePhysicalPath` approves and not one path more: a different drive, a
# different physical source, an unknown prefix, or a path that merely ends in
# the same segments, each fail here.
function Test-SameBindSourcePath($Observed, [string]$Expected) {
    if (Test-SamePhysicalPath $Observed $Expected) {
        return $true
    }
    $converted = ConvertFrom-E2EDockerDesktopHostPath $Observed
    if ($null -eq $converted) {
        return $false
    }
    return (Test-SamePhysicalPath $converted $Expected)
}

# `HostConfig.Binds`, as the daemon recorded them, against the exact bind list
# approved for this container.
#
# Each entry is split on the two separators a Windows bind actually has, so the
# host path, the container path and the mode are three compared values rather
# than one string in which a difference could hide. An entry that is not shaped
# like an approved bind at all is rejected without being parsed further.
#
# The host path may be written with either separator after the drive letter,
# because the daemon stores what it was given rather than a canonical form. That
# is a question about how the entry is *parsed*; what the parsed source is then
# compared against is unchanged, and `Test-SamePhysicalPath` decides it on the
# full canonical path.
function Assert-ExactBinds($Value, $Expected, [string]$Message) {
    $observed = @()
    if ($null -ne $Value) {
        $observed = @($Value)
    }
    if ($observed.Count -ne $Expected.Count) {
        throw $Message
    }
    $matched = @{}
    foreach ($entry in $observed) {
        # The whole entry is decided before it is split, so a line break cannot
        # ride along inside a group and be normalized away afterwards.
        if ($entry -isnot [string] -or -not (Test-E2ECleanScalar $entry) -or
            $entry -notmatch '\A(?<source>(?:[A-Za-z]:[\\/]|/run/desktop/mnt/host/[A-Za-z]/)[^:]*):(?<destination>/[^:]+):(?<mode>[a-z,]+)\z') {
            throw $Message
        }
        $source = $Matches['source']
        $destination = $Matches['destination']
        $mode = $Matches['mode']
        if (-not (Test-E2ECleanScalar $source) -or -not (Test-E2ECleanScalar $destination)) {
            throw $Message
        }
        $approved = $null
        foreach ($candidate in $Expected) {
            if ([string]::Equals($candidate.Destination, $destination, [System.StringComparison]::Ordinal)) {
                $approved = $candidate
                break
            }
        }
        if ($null -eq $approved -or $matched.ContainsKey($destination)) {
            throw $Message
        }
        # Read-only is the only mode this script ever mounts anything in.
        if (-not [string]::Equals($mode, 'ro', [System.StringComparison]::Ordinal)) {
            throw $Message
        }
        if (-not (Test-SameBindSourcePath $source $approved.Source)) {
            throw $Message
        }
        $matched[$destination] = $true
    }
    if ($matched.Count -ne $Expected.Count) {
        throw $Message
    }
}

# The resolved mount list, which is where a named volume, an anonymous volume or
# a mount the image itself declares would appear even though `Binds` names none
# of them. Type, physical source, destination, writability and propagation are
# each required to be the approved value.
function Assert-ExactMounts($Value, $Expected, [string]$Message) {
    $observed = @()
    if ($null -ne $Value) {
        $observed = @($Value)
    }
    if ($observed.Count -ne $Expected.Count) {
        throw $Message
    }
    $matched = @{}
    foreach ($mount in $observed) {
        $destination = Get-JsonMember $mount 'Destination'
        if ($destination -isnot [string] -or -not (Test-E2ECleanScalar $destination)) {
            throw $Message
        }
        $approved = $null
        foreach ($candidate in $Expected) {
            if ([string]::Equals($candidate.Destination, $destination, [System.StringComparison]::Ordinal)) {
                $approved = $candidate
                break
            }
        }
        if ($null -eq $approved -or $matched.ContainsKey($destination)) {
            throw $Message
        }
        if (-not [string]::Equals((Get-JsonMember $mount 'Type'), 'bind', [System.StringComparison]::Ordinal)) {
            throw $Message
        }
        if (-not [string]::Equals((Get-JsonMember $mount 'Mode'), 'ro', [System.StringComparison]::Ordinal)) {
            throw $Message
        }
        # Read-only, as a boolean the daemon actually wrote. `Test-JsonFlag`
        # answers "is this a true flag", which reads an absent member, a JSON
        # null, the string "false" and the number 0 alike as "not writable" -
        # every one of which is a document this script failed to understand
        # rather than a mount it has confirmed is read-only. The type is
        # required here as well as the value.
        $readWrite = Get-JsonMember $mount 'RW'
        if ($readWrite -isnot [bool] -or $readWrite) {
            throw $Message
        }
        if (-not [string]::Equals((Get-JsonMember $mount 'Propagation'), 'rprivate', [System.StringComparison]::Ordinal)) {
            throw $Message
        }
        $source = Get-JsonMember $mount 'Source'
        if (-not (Test-E2ECleanScalar $source)) {
            throw $Message
        }
        if (-not (Test-SameBindSourcePath $source $approved.Source)) {
            throw $Message
        }
        $matched[$destination] = $true
    }
    if ($matched.Count -ne $Expected.Count) {
        throw $Message
    }
}

# Published ports, compared as the complete map the daemon holds. An extra
# container port, an extra binding under an approved port, a second host
# interface or a different host port each fail here, before the daemon has been
# asked to listen on anything.
function Assert-ExactPortBindings($Value, $Expected, [string]$Message) {
    if (@(Get-JsonMemberNames $Value).Count -ne $Expected.Count) {
        throw $Message
    }
    foreach ($port in $Expected.Keys) {
        $observed = @(Get-JsonMember $Value $port)
        $approved = @($Expected[$port])
        if ($observed.Count -ne $approved.Count) {
            throw $Message
        }
        for ($index = 0; $index -lt $approved.Count; $index++) {
            if (-not [string]::Equals(
                    (Get-JsonMember $observed[$index] 'HostIp'),
                    $approved[$index].HostIp,
                    [System.StringComparison]::Ordinal) -or
                -not [string]::Equals(
                    (Get-JsonMember $observed[$index] 'HostPort'),
                    $approved[$index].HostPort,
                    [System.StringComparison]::Ordinal)) {
                throw $Message
            }
        }
    }
}

# One approved bind: the physical host path, the container path it is allowed to
# appear at, and the exact argument the create call is allowed to carry.
# Read-only is not a parameter. Nothing this script mounts is ever writable.
function New-ApprovedBind([string]$HostPath, [string]$ContainerPath, [switch]$Directory) {
    $physical = if ($Directory) {
        Get-OwnedPhysicalPath $HostPath -Directory
    }
    else {
        Get-OwnedPhysicalPath $HostPath
    }
    return [ordered]@{
        Source      = $physical
        Destination = $ContainerPath
        Argument    = "${physical}:${ContainerPath}:ro"
    }
}

function New-ContainerExpectation {
    param(
        [Parameter(Mandatory = $true)][string]$NetworkMode,
        [Parameter(Mandatory = $true)][bool]$ReadOnlyRootFilesystem,
        [string[]]$CapabilityDrop = @(),
        [string[]]$SecurityOptions = @(),
        [array]$Binds = @(),
        $Tmpfs = ([ordered]@{}),
        $PortBindings = ([ordered]@{}),
        [string[]]$ExtraHosts = @(),
        [bool]$Init = $false
    )
    return [ordered]@{
        NetworkMode            = $NetworkMode
        ReadOnlyRootFilesystem = $ReadOnlyRootFilesystem
        CapabilityDrop         = $CapabilityDrop
        SecurityOptions        = $SecurityOptions
        Binds                  = $Binds
        Tmpfs                  = $Tmpfs
        PortBindings           = $PortBindings
        ExtraHosts             = $ExtraHosts
        Init                   = $Init
    }
}

# The daemon's own record of a container this run created. Asked of
# `docker container inspect` rather than `docker inspect`, so the identifier is
# resolved as a container and can never be answered by an image that happens to
# share it.
function Assert-E2ENoDuplicateJsonKeys([string]$Encoded) {
    $frames = [System.Collections.Generic.Stack[object]]::new()
    for ($index = 0; $index -lt $Encoded.Length; $index++) {
        $character = $Encoded[$index]
        if ($character -eq '"') {
            $start = $index
            $escaped = $false
            do {
                $index++
                if ($index -ge $Encoded.Length) { throw 'RESOURCE_CLEANUP_FAILED' }
                if ($escaped) { $escaped = $false; continue }
                if ($Encoded[$index] -eq '\') { $escaped = $true; continue }
            } while ($Encoded[$index] -ne '"')
            if ($frames.Count -ne 0 -and $frames.Peek().Kind -ceq 'object' -and $frames.Peek().ExpectKey) {
                try { $key = ConvertFrom-Json -InputObject $Encoded.Substring($start, $index - $start + 1) }
                catch { throw 'RESOURCE_CLEANUP_FAILED' }
                if ($key -isnot [string] -or -not $frames.Peek().Keys.Add($key)) {
                    throw 'RESOURCE_CLEANUP_FAILED'
                }
                $frames.Peek().ExpectKey = $false
            }
            continue
        }
        if ($character -eq '{') {
            $frames.Push([pscustomobject]@{ Kind='object'; ExpectKey=$true
                Keys=[System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal) })
        }
        elseif ($character -eq '[') { $frames.Push([pscustomobject]@{ Kind='array' }) }
        elseif ($character -eq '}' -or $character -eq ']') {
            if ($frames.Count -eq 0) { throw 'RESOURCE_CLEANUP_FAILED' }
            [void]$frames.Pop()
        }
        elseif ($character -eq ',' -and $frames.Count -ne 0 -and $frames.Peek().Kind -ceq 'object') {
            $frames.Peek().ExpectKey = $true
        }
    }
    if ($frames.Count -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
}

function Get-ContainerDocument([string]$ContainerId) {
    # The operand is a full 64-character identifier or nothing is asked at all.
    # An abbreviated identifier is a prefix query, which the daemon is free to
    # answer with whichever container happens to match it, so it is never what
    # this script inspects - and the answer is required to name the identifier
    # that was asked for, so a document about another container is refused
    # rather than read.
    if ($ContainerId -cnotmatch '\A[0-9a-f]{64}\z') {
        throw 'A container this run created could not be inspected.'
    }
    $encoded = Invoke-NativeStdout { & docker container inspect --format '{{json .}}' $ContainerId }
    if ($LASTEXITCODE -ne 0) {
        throw 'A container this run created could not be inspected.'
    }
    Assert-E2ENoDuplicateJsonKeys $encoded
    try {
        $document = $encoded | ConvertFrom-Json
    }
    catch {
        throw 'A container this run created could not be inspected.'
    }
    if ($document -is [array]) {
        if ($document.Count -ne 1) {
            throw 'A container this run created could not be inspected.'
        }
        $document = $document[0]
    }
    if ($null -eq $document) {
        throw 'A container this run created could not be inspected.'
    }
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $ContainerId)) {
        throw 'A container this run created could not be inspected.'
    }
    return $document
}

# The whole decision, taken while the container is still stopped.
function Assert-ContainerConfinement([string]$ContainerId, [string]$ImageId, $Expected) {
    $document = Get-ContainerDocument $ContainerId

    if (-not [string]::Equals((Get-JsonMember $document 'Id'), $ContainerId, [System.StringComparison]::Ordinal)) {
        throw 'The inspected container is not the container that was created.'
    }
    if (-not [string]::Equals((Get-JsonMember $document 'Image'), $ImageId, [System.StringComparison]::Ordinal)) {
        throw 'The created container was not created from the verified image.'
    }
    $imageConfiguration = Get-JsonMember $document 'Config'
    if ($null -eq $imageConfiguration -or
        -not [string]::Equals((Get-JsonMember $imageConfiguration 'Image'), $ImageId, [System.StringComparison]::Ordinal)) {
        throw 'The created container image reference is not the verified image identifier.'
    }
    # Approval happens before execution or not at all.
    $state = Get-JsonMember $document 'State'
    if (-not [string]::Equals((Get-JsonMember $state 'Status'), 'created', [System.StringComparison]::Ordinal)) {
        throw 'The container was already started before it was approved.'
    }

    $configuration = Get-JsonMember $document 'HostConfig'
    if ($null -eq $configuration) {
        throw 'The created container carries no host configuration.'
    }

    if (-not [string]::Equals(
            (Get-JsonMember $configuration 'NetworkMode'),
            $Expected.NetworkMode,
            [System.StringComparison]::Ordinal)) {
        throw 'The created container network mode is not the approved one.'
    }
    $settings = Get-JsonMember $document 'NetworkSettings'
    $attached = @(Get-JsonMemberNames (Get-JsonMember $settings 'Networks'))
    Assert-ExactStrings $attached @($Expected.NetworkMode) `
        'The created container is attached to an unapproved network.'

    if ((Test-JsonFlag (Get-JsonMember $configuration 'ReadonlyRootfs')) -ne $Expected.ReadOnlyRootFilesystem) {
        throw 'The created container root filesystem is not the approved one.'
    }
    if (Test-JsonFlag (Get-JsonMember $configuration 'Privileged')) {
        throw 'The created container is privileged.'
    }
    if ((Test-JsonFlag (Get-JsonMember $configuration 'Init')) -ne $Expected.Init) {
        throw 'The created container init setting is not the approved one.'
    }
    if (Test-JsonFlag (Get-JsonMember $configuration 'PublishAllPorts')) {
        throw 'The created container publishes unapproved ports.'
    }

    Assert-NoEntries (Get-JsonMember $configuration 'CapAdd') `
        'The created container adds a capability.'
    Assert-ExactStrings (Get-JsonMember $configuration 'CapDrop') $Expected.CapabilityDrop `
        'The created container capability set is not the approved one.'
    Assert-ExactStrings (Get-JsonMember $configuration 'SecurityOpt') $Expected.SecurityOptions `
        'The created container security options are not the approved ones.'
    Assert-ExactStrings (Get-JsonMember $configuration 'ExtraHosts') $Expected.ExtraHosts `
        'The created container resolves an unapproved host.'

    Assert-NoEntries (Get-JsonMember $configuration 'Devices') `
        'The created container is given a device.'
    Assert-NoEntries (Get-JsonMember $configuration 'DeviceRequests') `
        'The created container requests a device.'
    Assert-NoEntries (Get-JsonMember $configuration 'DeviceCgroupRules') `
        'The created container is given a device rule.'
    Assert-NoEntries (Get-JsonMember $configuration 'VolumesFrom') `
        'The created container inherits volumes from another container.'
    # Every mount this script asks for is a `-v` bind, so the structured mount
    # list must be empty; anything here is a mount nobody approved.
    Assert-NoEntries (Get-JsonMember $configuration 'Mounts') `
        'The created container declares an unapproved mount.'

    Assert-ExactBinds (Get-JsonMember $configuration 'Binds') $Expected.Binds `
        'The created container bind mounts are not the approved ones.'
    Assert-ExactMap (Get-JsonMember $configuration 'Tmpfs') $Expected.Tmpfs `
        'The created container tmpfs mounts are not the approved ones.'
    Assert-ExactPortBindings (Get-JsonMember $configuration 'PortBindings') $Expected.PortBindings `
        'The created container port bindings are not the approved ones.'
    Assert-ExactMounts (Get-JsonMember $document 'Mounts') $Expected.Binds `
        'The created container mounts are not the approved ones.'
}

# The argument vector is the whole command, `create` included, so what the
# daemon is asked for is one reviewable list rather than a verb here and its
# operands somewhere else.
function New-CreatedContainer([string[]]$Arguments) {
    $created = (Invoke-NativeStdout { & docker @Arguments }).Trim()
    if ($LASTEXITCODE -ne 0 -or $created -notmatch '\A[0-9a-f]{64}\z') {
        throw 'A container for this run could not be created.'
    }
    return $created
}

# Whether the exact identifier is still a container the daemon knows about.
#
# Asked by full identifier and answered by full identifier: an answer naming
# anything else is not an answer about this container, and is refused rather
# than counted.
function Get-OwnedContainerPresence([string]$ContainerId) {
    $output = Invoke-NativeStdout {
        & docker ps -a --no-trunc --filter "id=$ContainerId" --format '{{.ID}}'
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'A container this run created could not be accounted for.'
    }
    $found = @()
    if ($null -ne $output) {
        $found = @(($output -split '\r?\n') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    }
    if (@($found | Where-Object { -not (Test-E2EOrdinalEqual $_ $ContainerId) }).Count -ne 0) {
        throw 'A container this run created could not be accounted for.'
    }
    return $found.Count
}

# The approved identifier, still this run's container, on the approved image,
# and carrying no volume mount at all - plus, when the caller supplies the
# browser ownership contract, the whole of that contract.
#
# `docker rm` without `--volumes` leaves a volume behind, so a container that
# acquired one is a container this cleanup cannot finish, and it says so
# instead of removing half of it. The document it read is returned, so the
# state below is the state this check just approved rather than a second read.
function Assert-OwnedContainerRemovable([string]$ContainerId, [string]$ImageId, $BrowserContract) {
    $document = Get-ContainerDocument $ContainerId
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $ContainerId) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Image') $ImageId)) {
        throw 'A container removal was asked for an identifier this run did not create.'
    }
    # A volume mount is refused by its type and, independently, by the fact that
    # it carries a volume name at all. The type is one daemon-supplied scalar,
    # and a decision that removes a container should not rest on a single
    # comparison of it: a named or anonymous volume is recorded with a `Name`,
    # and a bind or a tmpfs mount is not.
    foreach ($mount in @(Get-JsonMember $document 'Mounts')) {
        if ((Get-JsonMember $mount 'Type') -ceq 'volume' -or
            -not [string]::IsNullOrEmpty([string](Get-JsonMember $mount 'Name'))) {
            throw 'A container this run created carries a volume mount it was not approved with.'
        }
    }
    # The dedicated browser container is additionally required to be the
    # dedicated browser container, and that is one validator both of its
    # callers go through rather than a check each of them writes for itself.
    if ($null -ne $BrowserContract) {
        Assert-E2EOwnedBrowserContainer -Document $document -ContainerId $ContainerId `
            -ImageId $ImageId -Contract $BrowserContract
    }
    return $document
}

# Removes exactly one container this run created, by the identifier it was
# created under, and proves it is gone.
#
# Nothing here is forced and nothing here takes storage with it. `--force`
# kills a container that is running, and a browser container that is still
# running is exactly the case that means the world is not what this cleanup
# believes; `--volumes` removes storage this function never looked at. So the
# identifier is proved to still be this run's container on the approved image
# with no volume mount, stopped normally if it is running, proved once more
# because stopping is itself a window, and only then removed - after which the
# same identifier is asked for again and has to be gone. A container that is
# already absent is success: there is nothing left for this to do.
#
# A caller that supplies the browser ownership contract gets two more things
# for the same identifier: every ownership check that contract names, applied
# at each of the two approval points below, and a closing audit of the one
# fixed name a browser container is ever given. The dedicated browser
# container has exactly two callers and they both arrive here, so what may be
# removed is decided in one place for both of them.
function Remove-OwnedContainer([string]$ContainerId, [string]$ImageId, $BrowserContract) {
    if ($ContainerId -cnotmatch '\A[0-9a-f]{64}\z' -or $ImageId -cnotmatch '\Asha256:[0-9a-f]{64}\z') {
        throw 'A container removal was asked for an identifier this run did not create.'
    }
    if ((Get-OwnedContainerPresence $ContainerId) -ne 0) {
        $document = Assert-OwnedContainerRemovable $ContainerId $ImageId $BrowserContract
        if ((Get-JsonMember (Get-JsonMember $document 'State') 'Running') -eq $true) {
            Invoke-Native { & docker stop $ContainerId | Out-Null }
            if ($LASTEXITCODE -ne 0) {
                throw 'A container this run created could not be removed.'
            }
            $stopped = Assert-OwnedContainerRemovable $ContainerId $ImageId $BrowserContract
            if ((Get-JsonMember (Get-JsonMember $stopped 'State') 'Running') -ne $false) {
                throw 'A container this run created could not be removed.'
            }
        }
        Invoke-Native { & docker rm $ContainerId | Out-Null }
        if ($LASTEXITCODE -ne 0) {
            throw 'A container this run created could not be removed.'
        }
        if ((Get-OwnedContainerPresence $ContainerId) -ne 0) {
            throw 'A container this run created could not be removed.'
        }
    }
    # A browser container also has exactly one name it is ever given, so the
    # audit asks for that name too.
    if ($null -ne $BrowserContract) {
        Assert-E2ENoOwnedBrowserResidue -Contract $BrowserContract
    }
}

# The container that ran is the container that was approved.
#
# `docker start` was given an identifier, but an exit code alone would also be
# produced by a container that was never this one. This asks the daemon what
# happened to the exact approved identifier: it, and not something else, is what
# ran, from the verified image, to completion, successfully.
function Assert-ContainerCompletion([string]$ContainerId, [string]$ImageId) {
    $document = Get-ContainerDocument $ContainerId
    if (-not [string]::Equals((Get-JsonMember $document 'Id'), $ContainerId, [System.StringComparison]::Ordinal) -or
        -not [string]::Equals((Get-JsonMember $document 'Image'), $ImageId, [System.StringComparison]::Ordinal)) {
        throw 'The container that ran is not the container that was approved.'
    }
    $state = Get-JsonMember $document 'State'
    if (-not [string]::Equals((Get-JsonMember $state 'Status'), 'exited', [System.StringComparison]::Ordinal) -or
        (Get-JsonMember $state 'ExitCode') -ne 0) {
        throw 'The container that ran is not the container that was approved.'
    }
}

# Create, inspect, approve, start, and remove exactly what was created. The
# `finally` names the identifier this call produced and nothing else, so a
# failure at any point leaves no container behind and touches no other.
function Invoke-ApprovedContainer([string]$ImageId, $Plan, [string]$Operation) {
    $container = New-CreatedContainer $Plan.Arguments
    $primary = $null
    try {
        Assert-ContainerConfinement $container $ImageId $Plan.Expectation
        Invoke-Native { & docker start --attach $container }
        Assert-Success $Operation
        Assert-ContainerCompletion $container $ImageId
    }
    catch { $primary = $_.Exception }
    $removeOwnedContainer = { param($activeContainer, $activeImage) Remove-OwnedContainer $activeContainer $activeImage }
    $actions = @([pscustomobject]@{
        Action = { & $removeOwnedContainer $container $ImageId }.GetNewClosure()
        ErrorCode = 'CONTAINER_CLEANUP_FAILED'
        SkipAfterCleanupFailure = $false
    })
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
}

# Proves the prepared image from the inside, and proves the confinement it will
# be started under.
#
# Layer digests establish that this image is the pinned base plus one layer.
# They say nothing about what that layer put in the image, or about what the
# base's own layers contain, and both are things the browser will depend on: the
# `certutil` that writes the NSS trust entry, the Chromium that performs the TLS
# handshake, the Node that runs the browser server.
#
# So the image is asked, in a container with no network at all, a read-only root
# filesystem, every capability dropped and no path to acquiring one - each of
# which is checked against the daemon's own record before that container is
# allowed to start. dpkg's database answers for the packages, /proc answers for
# the account and the privileges, and the installed playwright-core mounted
# read-only answers for which browser revisions this image is required to hold.
# Every rejection is a fixed sentence naming a rule; none of them echoes an
# observed value back into the log.
function Get-BrowserRuntimePlan([string]$BrowserImageId, [string]$PlaywrightVersion) {
    $binds = @(
        (New-ApprovedBind $ScriptsPath '/finguardops/scripts' -Directory),
        (New-ApprovedBind $PlaywrightCorePath '/finguardops/playwright-core' -Directory)
    )
    $workTmpfs = 'rw,noexec,nosuid,nodev,size=16m'
    return [ordered]@{
        Expectation = New-ContainerExpectation `
            -NetworkMode 'none' `
            -ReadOnlyRootFilesystem $true `
            -CapabilityDrop @('ALL') `
            -SecurityOptions @('no-new-privileges') `
            -Binds $binds `
            -Tmpfs ([ordered]@{ '/finguardops/work' = $workTmpfs })
        Arguments   = @(
            'create',
            '--network', 'none',
            '--pull', 'never',
            '--read-only',
            '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges',
            '--tmpfs', "/finguardops/work:$workTmpfs",
            '-v', $binds[0].Argument,
            '-v', $binds[1].Argument,
            '-e', "FINGUARDOPS_VERIFY_USER=$BrowserUser",
            '-e', "FINGUARDOPS_VERIFY_UID=$BrowserUserId",
            '-e', "FINGUARDOPS_VERIFY_GID=$BrowserGroupId",
            '-e', "FINGUARDOPS_VERIFY_LIBNSS3_TOOLS_VERSION=$LibNss3ToolsVersion",
            '-e', "FINGUARDOPS_VERIFY_LIBNSS3_VERSION=$LibNss3Version",
            '-e', "FINGUARDOPS_VERIFY_NODE_VERSION=$BrowserNodeVersion",
            '-e', "FINGUARDOPS_VERIFY_PLAYWRIGHT_VERSION=$PlaywrightVersion",
            '-e', "FINGUARDOPS_VERIFY_CHROMIUM_VERSION=$BrowserChromiumBuild",
            '--entrypoint', 'bash',
            $BrowserImageId,
            '/finguardops/scripts/playwright-browser-server.sh', 'verify'
        )
    }
}

function Assert-BrowserRuntime([string]$BrowserImageId, [string]$PlaywrightVersion) {
    Invoke-ApprovedContainer `
        $BrowserImageId `
        (Get-BrowserRuntimePlan $BrowserImageId $PlaywrightVersion) `
        'Prepared browser image runtime verification' | Out-Null
}

# Proves the certificate is validly self-signed and matches its private key, in
# a throwaway container with no network at all, no writable filesystem and no
# capabilities. The key is mounted read-only here and nowhere else, so it never
# shares a container with a browser, and the container is approved against this
# exact mount list before it starts.
function Get-CertificateKeyPairPlan([string]$BrowserImageId) {
    $binds = @(
        (New-ApprovedBind $CertificatePath '/finguardops/tls/localhost.crt'),
        (New-ApprovedBind $PrivateKeyPath '/finguardops/tls/localhost.key'),
        (New-ApprovedBind $ScriptsPath '/finguardops/scripts' -Directory)
    )
    return [ordered]@{
        Expectation = New-ContainerExpectation `
            -NetworkMode 'none' `
            -ReadOnlyRootFilesystem $true `
            -CapabilityDrop @('ALL') `
            -SecurityOptions @('no-new-privileges') `
            -Binds $binds
        Arguments   = @(
            'create',
            '--network', 'none',
            '--pull', 'never',
            '--read-only',
            '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges',
            '-v', $binds[0].Argument,
            '-v', $binds[1].Argument,
            '-v', $binds[2].Argument,
            '--entrypoint', 'node',
            $BrowserImageId,
            '/finguardops/scripts/verify-localhost-certificate.mjs',
            '/finguardops/tls/localhost.crt',
            '/finguardops/tls/localhost.key'
        )
    }
}

function Assert-CertificateKeyPair([string]$BrowserImageId) {
    if (-not [System.IO.File]::Exists($PrivateKeyPath)) {
        throw 'The localhost private key is missing.'
    }
    Invoke-ApprovedContainer `
        $BrowserImageId `
        (Get-CertificateKeyPairPlan $BrowserImageId) `
        'Isolated localhost certificate verification'
}


# The approved bind list, resolved to owned physical host paths. This is the
# creation-time list: every path must exist and must be link-free before the
# daemon is handed it.
function Get-BrowserServerApprovedBinds {
    $binds = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $BrowserBindContract) {
        if ($entry.Directory) {
            $binds.Add((New-ApprovedBind $entry.HostPath $entry.Destination -Directory))
        }
        else {
            $binds.Add((New-ApprovedBind $entry.HostPath $entry.Destination))
        }
    }
    return $binds.ToArray()
}

# The same bind list, as the expectation a removal is judged against.
#
# `New-ApprovedBind` returns the trimmed full path of an owned location, so what
# this produces is the identical `Source` string whenever creation would have
# succeeded. What it does not do is re-assert that the host path still exists,
# because a container the daemon is already holding is judged against the
# contract it was created under rather than against the world as it is now.
# Nothing in the comparison itself is relaxed: `Test-SamePhysicalPath` still
# decides one whole canonical path against another, and a reparse point still
# cannot be spelled to look like the approved source.
function Get-BrowserServerExpectedBinds {
    $binds = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $BrowserBindContract) {
        $binds.Add([ordered]@{
            Source      = Get-TrimmedPath ([System.IO.Path]::GetFullPath($entry.HostPath))
            Destination = $entry.Destination
        })
    }
    return $binds.ToArray()
}

# The confinement the production browser creation boundary approves, written
# once. The create call is checked against it before the container starts, and
# the removal boundary re-checks the same contract before anything is stopped,
# so neither statement can be weakened without the other.
function Get-BrowserServerExpectation($Binds) {
    $publication = [ordered]@{ HostIp = '127.0.0.1'; HostPort = "$BrowserHostPort" }
    return New-ContainerExpectation `
        -NetworkMode 'bridge' `
        -ReadOnlyRootFilesystem $false `
        -Binds $Binds `
        -Init $true `
        -ExtraHosts @('host.docker.internal:host-gateway') `
        -PortBindings ([ordered]@{ "$BrowserContainerPort/tcp" = @($publication) })
}

# The expected values a browser container removal is judged against, derived
# from this run's receipt and from the browser creation contract above, and from
# nothing else.
#
# The receipt names the browser image reference; that reference is resolved
# through the authoritative image record, which is what establishes the image
# identifier and the five ownership labels this run's browser container has to
# carry. A reference that names no local image, or an image that is not
# labelled as this run's browser image, is a fixed error here - before any
# candidate container has been looked at, let alone mutated.
#
# The container itself is never compared against the reference, because the
# create call above names the image by identifier and the daemon records what
# it was given. The reference is what the identifier is *derived from*, so a
# retagged or rebuilt browser image resolves to a different identifier and
# every container on the old one stops being owned.
function Get-E2EBrowserOwnershipContract {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $record = Get-E2ENormalizedImageRecord -Reference $images.Browser -Role 'browser' -Receipt $Receipt
    if (-not (Test-E2EOrdinalEqual $record.Reference $images.Browser) -or
        -not (Test-E2EOrdinalEqual $record.Role 'browser') -or
        $record.Id -cnotmatch '\Asha256:[0-9a-f]{64}\z') {
        throw 'BROWSER_OWNERSHIP_INVALID'
    }
    return [ordered]@{
        Name        = $BrowserContainerName
        Reference   = $record.Reference
        ImageId     = $record.Id
        Labels      = $record.Labels
        Role        = $record.Role
        Expectation = Get-BrowserServerExpectation (Get-BrowserServerExpectedBinds)
    }
}

# The one production statement that a candidate container is this run's own
# dedicated browser container. Both the Run-end cleanup and the explicit
# Cleanup mode ask exactly this question, of exactly this function, before
# anything is stopped or removed.
#
# An exact full identifier fixes which container a mutation would touch; it
# does not say that container is this run's. The fixed name does not say so
# either: a container carrying that name, and even this run's image identifier,
# but with a volume, an extra bind, a device, an added capability or a
# published port nobody approved is a foreign container, and a foreign
# container is refused rather than finished.
#
# Every expected value comes from the receipt or from the creation contract.
# Nothing is taken from the document being judged, so a container cannot
# describe itself into being owned, and every rejection is one fixed sentence
# that echoes no observed value.
function Assert-E2EOwnedBrowserContainer {
    param(
        [Parameter(Mandatory = $true)]$Document,
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)]$Contract
    )

    $message = 'A browser container removal was asked for a container this run does not own.'
    # The identifier the caller pinned, the image the receipt resolves to, and
    # the container the daemon answered about are all required to be one
    # identifier and one image.
    if ($ContainerId -cnotmatch '\A[0-9a-f]{64}\z' -or
        -not (Test-E2EOrdinalEqual $Contract.ImageId $ImageId)) {
        throw $message
    }
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $Document 'Id') $ContainerId) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $Document 'Image') $Contract.ImageId) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $Document 'Name') ('/' + $Contract.Name))) {
        throw $message
    }

    $configuration = Get-JsonMember $Document 'Config'
    if ($null -eq $configuration -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $configuration 'Image') $Contract.ImageId)) {
        throw $message
    }
    # The five receipt-derived ownership labels the prepared browser image was
    # built with, read off the container the daemon created from that image.
    # The role is one of them, so a container built from this run's backend or
    # ai-service image fails here as well.
    $labels = Get-JsonMember $configuration 'Labels'
    if ($null -eq $labels) { throw $message }
    foreach ($key in $Contract.Labels.Keys) {
        if (-not [string]::Equals(
                [string](Get-JsonMember $labels $key),
                [string]$Contract.Labels[$key],
                [System.StringComparison]::Ordinal)) {
            throw $message
        }
    }

    $expected = $Contract.Expectation
    $hostConfiguration = Get-JsonMember $Document 'HostConfig'
    if ($null -eq $hostConfiguration) { throw $message }
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $hostConfiguration 'NetworkMode') $expected.NetworkMode)) {
        throw $message
    }
    $attached = @(Get-JsonMemberNames (Get-JsonMember (Get-JsonMember $Document 'NetworkSettings') 'Networks'))
    Assert-ExactStrings $attached @($expected.NetworkMode) $message
    if ((Test-JsonFlag (Get-JsonMember $hostConfiguration 'ReadonlyRootfs')) -ne $expected.ReadOnlyRootFilesystem -or
        (Test-JsonFlag (Get-JsonMember $hostConfiguration 'Init')) -ne $expected.Init -or
        (Test-JsonFlag (Get-JsonMember $hostConfiguration 'Privileged')) -or
        (Test-JsonFlag (Get-JsonMember $hostConfiguration 'PublishAllPorts'))) {
        throw $message
    }
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'CapAdd') $message
    Assert-ExactStrings (Get-JsonMember $hostConfiguration 'CapDrop') $expected.CapabilityDrop $message
    Assert-ExactStrings (Get-JsonMember $hostConfiguration 'SecurityOpt') $expected.SecurityOptions $message
    Assert-ExactStrings (Get-JsonMember $hostConfiguration 'ExtraHosts') $expected.ExtraHosts $message
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'Devices') $message
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'DeviceRequests') $message
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'DeviceCgroupRules') $message
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'VolumesFrom') $message
    Assert-NoEntries (Get-JsonMember $hostConfiguration 'Mounts') $message
    Assert-ExactBinds (Get-JsonMember $hostConfiguration 'Binds') $expected.Binds $message
    Assert-ExactMap (Get-JsonMember $hostConfiguration 'Tmpfs') $expected.Tmpfs $message
    Assert-ExactPortBindings (Get-JsonMember $hostConfiguration 'PortBindings') $expected.PortBindings $message
    # The resolved mount list, which is where a named volume, an anonymous
    # volume or a mount the image itself declares would appear even though
    # `Binds` names none of them.
    Assert-ExactMounts (Get-JsonMember $Document 'Mounts') $expected.Binds $message
}

# The fixed name, after the exact identifier is gone. An identifier that is
# absent says nothing about a second container that took the name while this
# removal was running, so the name is asked for as well. Nothing here removes
# anything.
function Assert-E2ENoOwnedBrowserResidue {
    param([Parameter(Mandatory = $true)]$Contract)

    $filters = Get-E2EExactNameFilters -Names @($Contract.Name) -Prefix '/'
    $output = Invoke-NativeStdout { & docker ps -aq --no-trunc @filters }
    if ($LASTEXITCODE -ne 0) {
        throw 'A container this run created could not be accounted for.'
    }
    $found = @()
    if ($null -ne $output) {
        $found = @(($output -split '\r?\n') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    }
    if ($found.Count -ne 0) {
        throw 'A container this run created could not be removed.'
    }
}

# The browser container is the one container in this run that is reachable from
# the host, so its mounts, its network and the single loopback port it publishes
# are approved the same way and for the same reason: before it starts, from the
# daemon's record, by exact comparison.
function Get-BrowserServerPlan([string]$BrowserImageId, [string]$PlaywrightVersion) {
    $binds = Get-BrowserServerApprovedBinds
    return [ordered]@{
        Expectation = Get-BrowserServerExpectation $binds
        Arguments   = @(
            'create',
            '--name', $BrowserContainerName,
            '--pull', 'never',
            '--init',
            '--add-host', 'host.docker.internal:host-gateway',
            '-p', "127.0.0.1:${BrowserHostPort}:${BrowserContainerPort}",
            '-e', "FINGUARDOPS_BROWSER_PORT=$BrowserContainerPort",
            '-e', "FINGUARDOPS_PLAYWRIGHT_VERSION=$PlaywrightVersion",
            '-v', $binds[0].Argument,
            '-v', $binds[1].Argument,
            '-v', $binds[2].Argument,
            '--entrypoint', 'bash',
            $BrowserImageId,
            '/finguardops/scripts/playwright-browser-server.sh', 'serve'
        )
    }
}

# Approves the created browser container and then starts that exact identifier.
#
# The last link in the chain. Everything above proved things about an image ID
# and about a stopped container; this starts the container that was approved,
# and then asks the daemon which container is running and which image it is
# running, so that a name moved, a container swapped or an image retagged
# between the two steps stops the run.
function Start-BrowserContainer([string]$ContainerId, [string]$BrowserImageId, $Expectation) {
    Assert-ContainerConfinement $ContainerId $BrowserImageId $Expectation
    Invoke-Native { & docker start $ContainerId | Out-Null }
    Assert-Success 'Dedicated browser container startup'

    $document = Get-ContainerDocument $ContainerId
    if (-not [string]::Equals((Get-JsonMember $document 'Id'), $ContainerId, [System.StringComparison]::Ordinal) -or
        -not [string]::Equals((Get-JsonMember $document 'Image'), $BrowserImageId, [System.StringComparison]::Ordinal) -or
        -not (Test-JsonFlag (Get-JsonMember (Get-JsonMember $document 'State') 'Running'))) {
        throw 'The running browser container is not the container that was approved.'
    }
}

# Reads the container log without letting it become a failure of its own. The
# browser server writes ordinary progress lines to stderr, and under this
# script's error preference every one of them would otherwise be terminating.
function Get-BrowserLog([string]$ContainerId) {
    return (Invoke-NativeStdout { & docker logs $ContainerId })
}

# Readiness is the server saying so, not the socket answering.
#
# Docker's published-port proxy starts listening the moment the container does,
# so a bare TCP connect succeeds long before the browser server has bound inside
# the container: the connection is accepted and then dropped, which reaches the
# client as `socket hang up` rather than as "not ready yet". The in-container
# `Listening on ws://` line is the only signal that means what it says, and the
# socket check afterwards confirms the published path reaches it.
function Wait-BrowserServer {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [scriptblock]$ClientFactory = { [System.Net.Sockets.TcpClient]::new() }
    )

    $deadline = (Get-Date).AddSeconds(300)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        $state = (& docker container inspect -f '{{.State.Running}}' $ContainerId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $state -ne 'true') {
            Write-Output (Get-BrowserLog $ContainerId)
            throw 'The dedicated browser container exited before it was ready.'
        }
        if (-not $ready) {
            $log = Get-BrowserLog $ContainerId
            if ($log -match 'Listening on ws://') {
                $ready = $true
            }
        }
        if ($ready) {
            $client = & $ClientFactory
            $connected = $false
            $primary = $null
            try {
                $connect = $client.BeginConnect('127.0.0.1', $BrowserHostPort, $null, $null)
                if ($connect.AsyncWaitHandle.WaitOne(1000) -and $client.Connected) {
                    $client.EndConnect($connect)
                    $connected = $true
                }
            }
            catch {
                $connectionFailure = $_.Exception
                while ($connectionFailure -is [System.Management.Automation.MethodInvocationException] -and
                    $null -ne $connectionFailure.InnerException) {
                    $methodFailure = $connectionFailure.InnerException
                    if ($methodFailure -is [System.Management.Automation.RuntimeException] -and
                        $null -ne $methodFailure.ErrorRecord -and
                        $null -ne $methodFailure.ErrorRecord.Exception -and
                        -not [object]::ReferenceEquals($methodFailure, $methodFailure.ErrorRecord.Exception)) {
                        $methodFailure = $methodFailure.ErrorRecord.Exception
                    }
                    $connectionFailure = $methodFailure
                }
                if ($connectionFailure -isnot [System.Net.Sockets.SocketException]) {
                    $primary = $connectionFailure
                }
                # A SocketException means the published path is not usable yet;
                # cleanup still precedes the next retry.
            }
            $actions = @([pscustomobject]@{
                Action = { $client.Close() }.GetNewClosure()
                ErrorCode = 'BROWSER_CLIENT_CLEANUP_FAILED'
                SkipAfterCleanupFailure = $false
            })
            Invoke-E2ECleanupActions -Primary $primary -Actions $actions
            if ($connected) { return }
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Output (Get-BrowserLog $ContainerId)
    throw 'The dedicated browser server did not become ready.'
}

function Invoke-E2EBrowserRunCore {
param([Parameter(Mandatory = $true)]$Receipt)

$certificate = $null
$runState = [pscustomobject]@{ ComposeStarted = $false }
# The identifier of the one browser container this run creates, and the only
# container the cleanup below is allowed to remove, together with the image
# identifier it was approved against - the cleanup re-proves both.
$browserContainer = $null
$browserContainerImage = $null
# The run lock, and whether this run actually holds it. The two are tracked
# separately on purpose: a mutex object that exists is not a mutex that was
# acquired, and only an acquired one may be released.
$runLock = $null
$runLockOwned = $false
$previousOutput = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', 'Process')
$previousProject = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', 'Process')
$previousBrowser = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_BROWSER_WS', 'Process')

# The lock is taken before the first question this run asks Docker about
# existing resources, and released only after the last step of cleanup below
# has finished. Between those two points this run is the only one that can be
# looking at, creating or removing anything under the fixed Compose project.
# Nothing before this point has asked Docker anything. A failure to take
# the lock therefore leaves nothing to undo.
$runLock = New-RunLock
$runLockOwned = $true

$runPrimary = $null
try {
        # Everything this run needs must already exist locally, and must be what
        # this checkout expects, before a single container starts. The image checks
        # read the local image store only; the runtime check that follows them runs
        # one throwaway container with no network at all.
        $playwrightVersion = Get-PlaywrightVersion
        $nodeExecutable = Get-NodeExecutable
        $playwrightCli = Assert-LocalNodeEntrypoint $PlaywrightTestPath 'cli.js' $ExpectedPlaywrightVersion `
            'The installed @playwright/test carries no CLI entry point. Run npm ci in frontend first.' `
            ("@playwright/test {0} is not installed. Run npm ci in frontend first." -f $ExpectedPlaywrightVersion)
        # Resolved here rather than only inside the Playwright configuration, so a
        # missing or mismatched Vite is a fixed error before any container starts
        # instead of a web server failure five minutes into the run.
        Assert-LocalNodeEntrypoint $VitePath 'bin/vite.js' $ExpectedViteVersion `
            'The installed vite carries no CLI entry point. Run npm ci in frontend first.' `
            ("vite {0} is not installed. Run npm ci in frontend first." -f $ExpectedViteVersion) | Out-Null

        $browserImageId = Assert-BrowserImage $playwrightVersion
        Assert-BrowserRuntime $browserImageId $playwrightVersion
        Assert-ComposeImagesPresent

        $certificate = Assert-SafeCertificate $CertificatePath
        Assert-CertificateKeyPair $browserImageId

        $existingContainers = @(& docker ps -a --filter "label=com.docker.compose.project=$ProjectName" --format '{{.ID}}')
        Assert-Success 'Dedicated Compose ownership check'
        $existingVolumes = @(& docker volume ls --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Name}}')
        Assert-Success 'Dedicated Compose volume ownership check'
        $existingNetworks = @(& docker network ls --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Name}}')
        Assert-Success 'Dedicated Compose network ownership check'
        $existingBrowser = @(& docker ps -a --filter "name=^/$BrowserContainerName$" --format '{{.ID}}')
        Assert-Success 'Dedicated browser container ownership check'
        if ($existingContainers.Count -ne 0 -or $existingVolumes.Count -ne 0 -or
            $existingNetworks.Count -ne 0 -or $existingBrowser.Count -ne 0) {
            throw 'The dedicated E2E project already has resources. Run cleanup mode first.'
        }

        [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', $OutputDirectory, 'Process')
        [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', $ProjectName, 'Process')
        [System.Environment]::SetEnvironmentVariable(
            'FINGUARDOPS_E2E_BROWSER_WS',
            "ws://127.0.0.1:$BrowserHostPort/",
            'Process'
        )

        Invoke-E2EInLocation -Path $RepositoryRoot -Body {
            $runState.ComposeStarted = $true
            # Every image was proven present above, so there is nothing left for
            # Compose to fetch or build. Were one missing after all, Compose fails
            # here rather than asking a registry for metadata or authentication.
            Invoke-Native { & docker @ComposeArguments up -d --no-build --pull never keycloak-verify }
            Assert-Success 'Dedicated Compose startup'
            Invoke-Native { & docker @ComposeArguments wait keycloak-verify }
            Assert-Success 'Keycloak bootstrap and verifier'
        }

        Assert-E2EContainerImages -Receipt $Receipt -Project $ProjectName

        # Created stopped, approved against the exact configuration this file
        # names, and only then started - by the identifier that was approved.
        $browserPlan = Get-BrowserServerPlan $browserImageId $playwrightVersion
        $browserContainerImage = $browserImageId
        $browserContainer = New-CreatedContainer $browserPlan.Arguments
        Start-BrowserContainer $browserContainer $browserImageId $browserPlan.Expectation
        Wait-BrowserServer $browserContainer

        Invoke-E2EInLocation -Path $FrontendRoot -Body {
            # The installed Playwright CLI, handed to this session's Node
            # executable. Not `npm run`, not `npx`: an npm script is a command line
            # a shell parses, npm runs lifecycle hooks around it, and npx resolves a
            # missing binary by fetching it. None of those belong in a run whose
            # contract is that it reaches no registry. Both paths below are absolute
            # and both are separate elements of the argument vector, so a directory
            # name containing a space or a non-ASCII character stays a directory
            # name.
            Invoke-Native { & $nodeExecutable $playwrightCli test --config playwright.config.ts }
            Assert-Success 'Playwright Keycloak E2E'
        }

        Write-Output 'Keycloak browser E2E completed.'
}
catch {
    $runPrimary = $_.Exception
}
$cleanupBoundaries = @{
        RestoreOutputEnvironment = {
            [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', $previousOutput, 'Process')
        }
        RestoreProjectEnvironment = {
            [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', $previousProject, 'Process')
        }
        RestoreBrowserEnvironment = {
            [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_BROWSER_WS', $previousBrowser, 'Process')
        }
        RemoveBrowser = {
            if ($null -ne $browserContainer) {
                # Named by the identifier this run created, so nothing else can
                # be removed even if the name were moved onto another container,
                # and judged by the same production browser ownership validator
                # the explicit Cleanup mode goes through, against expected
                # values derived from this run's receipt and from the browser
                # creation contract rather than from the container itself. The
                # per-run NSS database, the browser profile and the artifacts
                # directory are tmpfs and binds inside this container, so
                # removing it is enough; no volume is named, forced or taken
                # along.
                Remove-OwnedContainer $browserContainer $browserContainerImage `
                    (Get-E2EBrowserOwnershipContract -Receipt $Receipt)
            }
    }
    RemoveProjectResources = {
            if ($runState.ComposeStarted) {
                # The same exact-resource cleanup the Cleanup mode uses. A
                # project-wide `compose down` would also answer for whatever
                # else happened to carry this project's label by the time it
                # ran, which is not a question this run is entitled to answer.
                Invoke-E2EProjectCleanup -Project $ProjectName -Receipt $Receipt
            }
        }
        RemoveOutput = {
            if ([System.IO.Directory]::Exists($OutputDirectory)) {
                [System.IO.Directory]::Delete($OutputDirectory, $true)
            }
        }
    DisposeCertificate = {
        if ($null -ne $certificate) {
            $certificate.Dispose()
        }
    }
    ReleaseRunMutex = {
        if ($runLockOwned) {
            $runLockOwned = $false
            $runLock.ReleaseMutex()
        }
    }
    DisposeRunMutex = {
        if ($null -ne $runLock) {
            $runLock.Dispose()
        }
    }
}
Invoke-E2ERunCoreCleanup -Primary $runPrimary -Boundaries $cleanupBoundaries
}

function Get-E2ERepositoryId {
    param([Parameter(Mandatory = $true)][string]$Root)

    try {
        $canonical = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/').ToUpperInvariant()
        $bytes = [System.Text.UTF8Encoding]::new($false, $true).GetBytes($canonical)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $hashPrimary = $null
        try { $digest = $sha.ComputeHash($bytes) } catch { $hashPrimary = $_.Exception }
        $hashActions = @([pscustomobject]@{
            Action = { $sha.Dispose() }.GetNewClosure()
            ErrorCode = 'SOURCE_IDENTITY_INVALID'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $hashPrimary -Actions $hashActions
        return ([System.BitConverter]::ToString($digest)).Replace('-', '').ToLowerInvariant()
    }
    catch {
        throw 'SOURCE_IDENTITY_INVALID'
    }
}

function Get-E2ESourceIdentity {
    $identity = Invoke-E2EInLocation -Path $RepositoryRoot -Body {
        $status = Invoke-NativeStdout { & git status --porcelain=v1 --untracked-files=all }
        Assert-Success 'Source cleanliness check'
        if (-not [string]::IsNullOrEmpty($status)) {
            throw 'SOURCE_NOT_CLEAN'
        }
        $commit = (Invoke-NativeStdout { & git rev-parse --verify HEAD }).Trim()
        Assert-Success 'Source commit read'
        $tree = (Invoke-NativeStdout { & git rev-parse 'HEAD^{tree}' }).Trim()
        Assert-Success 'Source tree read'
        return [pscustomobject]@{ Commit = $commit; Tree = $tree }
    }
    $repositoryId = Get-E2ERepositoryId -Root $RepositoryRoot
    return New-E2EReceipt -RunId ('0' * 32) -RepositoryId $repositoryId -CommitSha $identity.Commit -TreeSha $identity.Tree
}

function Assert-E2ESourceMatchesReceipt {
    param([Parameter(Mandatory = $true)]$Receipt)

    $source = Get-E2ESourceIdentity
    if ((Get-E2EReceiptValue $Receipt 'repositoryId') -ne (Get-E2EReceiptValue $source 'repositoryId') -or
        (Get-E2EReceiptValue $Receipt 'commitSha') -ne (Get-E2EReceiptValue $source 'commitSha') -or
        (Get-E2EReceiptValue $Receipt 'treeSha') -ne (Get-E2EReceiptValue $source 'treeSha')) {
        throw 'SOURCE_IDENTITY_INVALID'
    }
}

function Set-E2EOwnerEnvironment {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $values = [ordered]@{
        FINGUARDOPS_E2E_BACKEND_IMAGE = $images.Backend
        FINGUARDOPS_E2E_AI_SERVICE_IMAGE = $images.AiService
        FINGUARDOPS_E2E_REVISION = Get-E2EReceiptValue $Receipt 'commitSha'
        FINGUARDOPS_E2E_SOURCE_TREE = Get-E2EReceiptValue $Receipt 'treeSha'
        FINGUARDOPS_E2E_RUN_ID = Get-E2EReceiptValue $Receipt 'runId'
        FINGUARDOPS_E2E_REPOSITORY_ID = Get-E2EReceiptValue $Receipt 'repositoryId'
    }
    $previous = [ordered]@{}
    foreach ($key in $values.Keys) {
        $previous[$key] = [System.Environment]::GetEnvironmentVariable($key, 'Process')
        [System.Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    $script:BrowserImage = $images.Browser
    return $previous
}

function Restore-E2EOwnerEnvironment {
    param([Parameter(Mandatory = $true)]$Previous)

    foreach ($key in $Previous.Keys) {
        [System.Environment]::SetEnvironmentVariable($key, $Previous[$key], 'Process')
    }
    $script:BrowserImage = $null
}

function Invoke-E2EOwnerEnvironmentScope {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][scriptblock]$Body,
        $Boundaries
    )

    if ($null -eq $Boundaries) {
        $Boundaries = @{
            SetOwnerEnvironment = { param($value) Set-E2EOwnerEnvironment -Receipt $value }
            RestoreOwnerEnvironment = { param($value) Restore-E2EOwnerEnvironment -Previous $value }
        }
    }
    $previous = & $Boundaries.SetOwnerEnvironment $Receipt
    $primary = $null
    try {
        & $Body
    }
    catch {
        $primary = $_.Exception
    }
    $actions = @([pscustomobject]@{
        Action = { & $Boundaries.RestoreOwnerEnvironment $previous }.GetNewClosure()
        ErrorCode = 'ENVIRONMENT_RESTORE_FAILED'
        SkipAfterCleanupFailure = $false
    })
    Invoke-E2ECleanupActions -Primary $primary -Actions $actions
}

function Get-E2EServiceProjectName {
    param([Parameter(Mandatory = $true)]$Receipt)
    return 'finguardops-kc241-e2e-' + (Get-E2EReceiptValue $Receipt 'runId').Substring(0, 12)
}

function Get-E2ENormalizedImageRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Reference,
        [Parameter(Mandatory = $true)][ValidateSet('backend', 'ai-service', 'browser')][string]$Role,
        [Parameter(Mandatory = $true)]$Receipt,
        [switch]$AllowMissing
    )

    if ($ProtectedImageReferences -contains $Reference -or $Reference -match ':local$') {
        throw 'IMAGE_REFERENCE_INVALID'
    }
    $document = Get-LocalImageDocument $Reference
    if ($null -eq $document) {
        if ($AllowMissing) { return $null }
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    $identifier = Get-JsonMember $document 'Id'
    if ($identifier -isnot [string] -or $identifier -notmatch '\Asha256:[0-9a-f]{64}\z') {
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    $configuration = Get-JsonMember $document 'Config'
    $labels = if ($null -ne $configuration) { Get-JsonMember $configuration 'Labels' } else { $null }
    if ($null -eq $labels) {
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    $expected = Get-E2EOwnershipLabels -Receipt $Receipt -Role $Role
    foreach ($key in $expected.Keys) {
        $actual = Get-JsonMember $labels $key
        if (-not [string]::Equals([string]$actual, [string]$expected[$key], [System.StringComparison]::Ordinal)) {
            throw 'IMAGE_OWNERSHIP_INVALID'
        }
    }
    return [pscustomobject]@{
        Reference = $Reference
        Id = $identifier
        Labels = $expected
        Role = $Role
        InUse = $false
    }
}

# The prepared image records, read from the local image store and from nowhere
# else.
#
# Every question this asks is a read: `docker image inspect` is answered from
# the local store and reaches no registry, and nothing here creates, starts or
# removes anything. That is the whole point of the split below it. A record
# this has produced is still only a candidate, so the authoritative validator
# runs on what comes back before any caller is allowed to act on it, and the
# one mutation-capable preflight - the browser runtime check, which starts a
# throwaway container - is a separate boundary that runs only after that
# validator has accepted the record.
function Assert-E2EOwnedImages {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $records = [ordered]@{
        Backend = Get-E2ENormalizedImageRecord -Reference $images.Backend -Role 'backend' -Receipt $Receipt
        AiService = Get-E2ENormalizedImageRecord -Reference $images.AiService -Role 'ai-service' -Receipt $Receipt
        Browser = Get-E2ENormalizedImageRecord -Reference $images.Browser -Role 'browser' -Receipt $Receipt
    }
    $browserId = Assert-BrowserImage (Get-PlaywrightVersion)
    if (-not (Test-E2EOrdinalEqual $browserId $records.Browser.Id)) {
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    Assert-E2EImageRecordSet -Values @($records) -Receipt $Receipt | Out-Null
    return $records
}

# The prepared browser image, proved from the inside.
#
# This is the one preflight step that runs a container, and it is deliberately
# not part of the record reader above. A caller reaches it only with a record
# the authoritative validator has already accepted, so a record describing some
# other image can never be the thing that causes a container to be created,
# started or removed. It re-derives the browser identifier from the daemon
# rather than trusting a record it was handed, and it writes nothing to the
# pipeline, so a success here cannot be mistaken for - or mixed into - an image
# record.
function Assert-E2EPreparedBrowserRuntime {
    param([Parameter(Mandatory = $true)]$Receipt)

    $playwrightVersion = Get-PlaywrightVersion
    $browserId = Assert-BrowserImage $playwrightVersion
    $images = Get-E2EImageSet -Receipt $Receipt
    $record = Get-E2ENormalizedImageRecord -Reference $images.Browser -Role 'browser' -Receipt $Receipt
    if (-not (Test-E2EOrdinalEqual $browserId $record.Id)) {
        throw 'IMAGE_OWNERSHIP_INVALID'
    }
    Assert-BrowserRuntime $browserId $playwrightVersion | Out-Null
}

# What the three prepared images actually are, according to the receipt and the
# local image store rather than according to the record being checked.
#
# `docker image inspect` is answered entirely from the local store and reaches
# no registry, so asking costs nothing but a read. What comes back is the only
# thing a candidate record may be compared against: the exact reference the
# receipt names, the exact image ID the daemon reports for that reference, the
# five ownership labels the receipt implies, and the role. A record carrying a
# well-formed `sha256:` string that is not this ID describes some other image,
# and a format check on its own would accept it.
function Get-E2EAuthoritativeImageIdentity {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $identity = [ordered]@{}
    foreach ($entry in @(
        @('Backend', 'backend', $images.Backend),
        @('AiService', 'ai-service', $images.AiService),
        @('Browser', 'browser', $images.Browser)
    )) {
        $document = Get-LocalImageDocument $entry[2]
        if ($null -eq $document) { throw 'IMAGE_RECORD_INVALID' }
        $identifier = Get-JsonMember $document 'Id'
        if ($identifier -isnot [string] -or $identifier -cnotmatch '\Asha256:[0-9a-f]{64}\z') {
            throw 'IMAGE_RECORD_INVALID'
        }
        $identity[$entry[0]] = [pscustomobject]@{
            Reference = [string]$entry[2]
            Id = [string]$identifier
            Role = [string]$entry[1]
            Labels = Get-E2EOwnershipLabels -Receipt $Receipt -Role $entry[1]
        }
    }
    return $identity
}

function Assert-E2EImageRecordSet {
    param([Parameter(Mandatory = $true)][object[]]$Values, [Parameter(Mandatory = $true)]$Receipt)

    if ($Values.Count -ne 1 -or $Values[0] -isnot [System.Collections.Specialized.OrderedDictionary]) {
        throw 'IMAGE_RECORD_INVALID'
    }
    $records = $Values[0]
    $keys = @($records.Keys)
    $expectedKeys = @('Backend', 'AiService', 'Browser')
    if (-not (Test-E2EOrdinalSequenceEqual $expectedKeys $keys)) { throw 'IMAGE_RECORD_INVALID' }
    $authoritative = Get-E2EAuthoritativeImageIdentity -Receipt $Receipt
    foreach ($key in $expectedKeys) {
        $expected = $authoritative[$key]
        $record = $records[$key]
        if ($record -isnot [pscustomobject] -or
            @($record.PSObject.Properties.Name).Count -ne 5 -or
            @($record.PSObject.Properties.Name | Where-Object { $_ -notin @('Reference','Id','Labels','Role','InUse') }).Count -ne 0 -or
            -not (Test-E2EOrdinalEqual $record.Reference $expected.Reference) -or
            $record.Id -cnotmatch '\Asha256:[0-9a-f]{64}\z' -or
            -not (Test-E2EOrdinalEqual $record.Id $expected.Id) -or
            -not (Test-E2EOrdinalEqual $record.Role $expected.Role) -or
            $record.InUse -isnot [bool] -or $record.InUse -or
            $record.Labels -isnot [System.Collections.IDictionary]) {
            throw 'IMAGE_RECORD_INVALID'
        }
        $labels = $expected.Labels
        if ($record.Labels.Count -ne $labels.Count) { throw 'IMAGE_RECORD_INVALID' }
        foreach ($name in $labels.Keys) {
            if (-not $record.Labels.Contains($name) -or
                -not [string]::Equals([string]$record.Labels[$name], [string]$labels[$name], [System.StringComparison]::Ordinal)) {
                throw 'IMAGE_RECORD_INVALID'
            }
        }
    }
}

function Invoke-E2EPrepareBuild {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $playwrightVersion = Get-PlaywrightVersion
    Invoke-E2EInLocation -Path $RepositoryRoot -Body {
        Invoke-Native { & docker @ComposeArguments pull --ignore-buildable }
        Assert-Success 'Dedicated Compose image pull'
        Invoke-Native { & docker @ComposeArguments build ai-service backend }
        Assert-Success 'Dedicated Compose image build'
    }
    Invoke-Native { & docker pull $BrowserBaseImage }
    Assert-Success 'Pinned Playwright base image pull'

    $buildContext = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-browser-build-' + [guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($buildContext) | Out-Null
    $browserBuildBoundaries = @{
        Build = {
            $arguments = [System.Collections.Generic.List[string]]::new()
            $arguments.Add('build')
            $arguments.Add('--file')
            $arguments.Add($BrowserDockerfile)
            $arguments.Add('--tag')
            $arguments.Add($images.Browser)
            foreach ($key in (Get-E2EOwnershipLabels -Receipt $Receipt -Role 'browser').Keys) {
                $labels = Get-E2EOwnershipLabels -Receipt $Receipt -Role 'browser'
                $arguments.Add('--label')
                $arguments.Add("$key=$($labels[$key])")
            }
            $arguments.Add('--build-arg')
            $arguments.Add("PLAYWRIGHT_VERSION=$playwrightVersion")
            $arguments.Add('--build-arg')
            $arguments.Add("LIBNSS3_TOOLS_VERSION=$LibNss3ToolsVersion")
            $arguments.Add($buildContext)
            Invoke-Native { & docker @($arguments.ToArray()) }
            Assert-Success 'Prepared browser image build'
        }
        RemoveTemp = {
            if ([System.IO.Directory]::Exists($buildContext)) {
                [System.IO.Directory]::Delete($buildContext, $true)
            }
        }
    }
    Invoke-E2EPrepareBrowserBuildLifecycle -Boundaries $browserBuildBoundaries
    Assert-E2EOwnedImages -Receipt $Receipt | Out-Null
    Assert-E2EPreparedBrowserRuntime -Receipt $Receipt | Out-Null
}

function Get-E2EComposeBaseArguments {
    param([Parameter(Mandatory = $true)][string]$Project)
    return @(
        'compose', '-p', $Project, '--env-file', 'infra/.env.example',
        '-f', 'infra/compose.yml', '-f', 'infra/compose.keycloak-local-e2e.yml'
    )
}

# The two Compose files this run is defined by, as full paths.
#
# The argument vector spells them relative to the repository root, because that
# is the directory the production Compose invocation runs in. What the daemon
# records is what Compose resolved them to, so the expectation is written here
# as the same two files resolved the same way, and is never read back off the
# container being judged.
function Get-E2EComposeProjectFiles {
    return @(
        [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'infra/compose.yml')),
        [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'infra/compose.keycloak-local-e2e.yml'))
    )
}

# The directory Compose calls the project's own.
#
# `com.docker.compose.project.working_dir` is not the directory the command ran
# in. Compose records the directory of the first `-f` file, and this run's first
# file is `infra/compose.yml`, so a container this project created carries the
# repository's `infra` directory here. The repository root is where the command
# runs and is a different directory: a container carrying it is not one of
# this project's, and is refused.
#
# The value is computed from the file list above - the same declaration the
# argument vector is built from - and from nothing a candidate reports about
# itself.
function Get-E2EComposeWorkingDirectory {
    $files = Get-E2EComposeProjectFiles
    $directory = [System.IO.Path]::GetDirectoryName($files[0])
    if ([string]::IsNullOrEmpty($directory)) { throw 'RESOURCE_CLEANUP_FAILED' }
    $canonical = Get-TrimmedPath ([System.IO.Path]::GetFullPath($directory))
    if (-not (Test-CanonicalWindowsPath $canonical)) { throw 'RESOURCE_CLEANUP_FAILED' }
    return $canonical
}

function Get-E2EComposeOwnershipContract {
    param([Parameter(Mandatory = $true)][string]$Project, [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string[]]$PresentServices)

    $arguments = Get-E2EComposeBaseArguments -Project $Project
    $encoded = Invoke-E2EInLocation -Path $RepositoryRoot -Body {
        $output = Invoke-NativeStdout { & docker @arguments config --format json }
        if ($LASTEXITCODE -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        return $output
    }
    try { $config = $encoded | ConvertFrom-Json } catch { throw 'RESOURCE_CLEANUP_FAILED' }
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $config 'name') $Project)) { throw 'RESOURCE_CLEANUP_FAILED' }
    $services = Get-JsonMember $config 'services'
    $expectedServices = $E2EComposeServices
    $actualServices = @(Get-JsonMemberNames $services)
    if (-not (Test-E2EOrdinalSetEqual $expectedServices $actualServices)) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    $images = Get-E2EImageSet -Receipt $Receipt
    $unique = @{
        backend = [pscustomobject]@{ Reference=$images.Backend; Role='backend' }
        'ai-service' = [pscustomobject]@{ Reference=$images.AiService; Role='ai-service' }
        'external-risk-mock' = [pscustomobject]@{ Reference=$images.AiService; Role='ai-service' }
        'alertmanager-webhook' = [pscustomobject]@{ Reference=$images.AiService; Role='ai-service' }
    }
    $records = @{}
    foreach ($service in $expectedServices) {
        $definition = Get-JsonMember $services $service
        $reference = Get-JsonMember $definition 'image'
        $id = $null
        if ($reference -isnot [string]) { throw 'RESOURCE_CLEANUP_FAILED' }
        if ($unique.ContainsKey($service)) {
            if (-not (Test-E2EOrdinalEqual $reference $unique[$service].Reference)) { throw 'RESOURCE_CLEANUP_FAILED' }
            if (Test-E2EOrdinalContains $PresentServices $service) {
                $role = $unique[$service].Role
                $image = Get-E2ENormalizedImageRecord -Reference $reference -Role $role -Receipt $Receipt
                $id = $image.Id
            }
        }
        else {
            if ($reference -cnotmatch '\A[^\s]+@sha256:[0-9a-f]{64}\z') { throw 'RESOURCE_CLEANUP_FAILED' }
            if (Test-E2EOrdinalContains $PresentServices $service) {
                $image = Get-LocalImageDocument $reference
                $id = Get-JsonMember $image 'Id'
                if ($id -isnot [string] -or $id -cnotmatch '\Asha256:[0-9a-f]{64}\z') {
                    throw 'RESOURCE_CLEANUP_FAILED'
                }
            }
        }
        $imagePorts = @()
        if (Test-E2EOrdinalContains $PresentServices $service) {
            $sourceImage = Get-LocalImageDocument $reference
            if (-not (Test-E2EOrdinalEqual (Get-JsonMember $sourceImage 'Id') $id)) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
            $sourceConfig = Get-JsonMember $sourceImage 'Config'
            $declaredImagePorts = Get-JsonMember $sourceConfig 'ExposedPorts'
            if ($null -ne $declaredImagePorts) { $imagePorts = @(Get-JsonMemberNames $declaredImagePorts) }
        }
        $records[$service] = [pscustomobject]@{ Reference=$reference; Id=$id; Definition=$definition
            ImageExposedPorts=$imagePorts }
    }
    return $records
}

function Get-E2EExactMember($Object, [string]$Name) {
    if ($null -eq $Object -or $Object -is [array] -or $Object -is [string]) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    if ($Object -is [System.Collections.IDictionary]) {
        foreach ($key in $Object.Keys) {
            if (Test-E2EOrdinalEqual $key $Name) { return ,$Object[$key] }
        }
    }
    else {
        foreach ($property in $Object.PSObject.Properties) {
            if (Test-E2EOrdinalEqual $property.Name $Name) { return ,$property.Value }
        }
    }
    throw 'RESOURCE_CLEANUP_FAILED'
}

function Get-E2EExactKeys($Object) {
    if ($null -eq $Object -or $Object -is [array] -or $Object -is [string] -or
        $Object -is [bool] -or $Object -is [ValueType]) { throw 'RESOURCE_CLEANUP_FAILED' }
    if ($Object -is [System.Collections.IDictionary]) { return @($Object.Keys) }
    $names = @()
    foreach ($property in $Object.PSObject.Properties) { $names += [string]$property.Name }
    return $names
}

function Assert-E2EContainerState($State) {
    if ($null -eq $State -or $State -is [array] -or $State -is [string]) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    $running = Get-E2EExactMember $State 'Running'
    $status = Get-E2EExactMember $State 'Status'
    $paused = Get-E2EExactMember $State 'Paused'
    $restarting = Get-E2EExactMember $State 'Restarting'
    $dead = Get-E2EExactMember $State 'Dead'
    if ($running -isnot [bool] -or $paused -isnot [bool] -or $restarting -isnot [bool] -or
        $dead -isnot [bool] -or $paused -or $restarting -or $dead -or
        $status -isnot [string] -or
        $(if ($running) { -not (Test-E2EOrdinalEqual $status 'running') }
            else { -not (Test-E2EOrdinalContains @('created','exited') $status) })) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
}

function Assert-E2EExactContractValue($Expected, $Actual) {
    if ($null -eq $Expected) {
        if ($null -ne $Actual) { throw 'RESOURCE_CLEANUP_FAILED' }
        return
    }
    if ($Expected -is [bool]) {
        if ($Actual -isnot [bool] -or $Actual -ne $Expected) { throw 'RESOURCE_CLEANUP_FAILED' }
        return
    }
    if ($Expected -is [string]) {
        if (-not (Test-E2EOrdinalEqual $Expected $Actual)) { throw 'RESOURCE_CLEANUP_FAILED' }
        return
    }
    if ($Expected -is [array]) {
        if ($Actual -isnot [array] -or $Actual.Count -ne $Expected.Count) { throw 'RESOURCE_CLEANUP_FAILED' }
        for ($index = 0; $index -lt $Expected.Count; $index++) {
            Assert-E2EExactContractValue $Expected[$index] $Actual[$index]
        }
        return
    }
    if ($Expected -is [System.Collections.IDictionary]) {
        $expectedKeys = @($Expected.Keys)
        $actualKeys = @(Get-E2EExactKeys $Actual)
        if (-not (Test-E2EOrdinalSetEqual $expectedKeys $actualKeys)) { throw 'RESOURCE_CLEANUP_FAILED' }
        foreach ($key in $expectedKeys) {
            Assert-E2EExactContractValue $Expected[$key] (Get-E2EExactMember $Actual $key)
        }
        return
    }
    throw 'RESOURCE_CLEANUP_FAILED'
}

function Assert-E2EExactContractSet($Expected, $Actual) {
    if ($null -eq $Expected) {
        if ($null -ne $Actual) { throw 'RESOURCE_CLEANUP_FAILED' }
        return
    }
    if ($Actual -isnot [array] -or -not (Test-E2EOrdinalSetEqual $Expected $Actual)) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
}

function Get-E2EComposeStringSet($Definition, [string]$Name) {
    $member = $Definition.PSObject.Properties[$Name]
    if ($null -eq $member) { return $null }
    if ($member.Value -isnot [array]) { throw 'RESOURCE_CLEANUP_FAILED' }
    $values = @($member.Value)
    if (-not (Test-E2EOrdinalSetEqual $values $values)) { throw 'RESOURCE_CLEANUP_FAILED' }
    return ,$values
}

function Assert-E2EComposePortSecurityContract($Document, $Contract) {
    $definition = $Contract.Definition
    $config = Get-E2EExactMember $Document 'Config'
    $host = Get-E2EExactMember $Document 'HostConfig'
    $exposed = [ordered]@{}
    foreach ($port in @($Contract.ImageExposedPorts)) {
        if ($port -isnot [string] -or $port -cnotmatch '\A[0-9]+/(?:tcp|udp|sctp)\z' -or $exposed.Contains($port)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
        $exposed[$port] = [ordered]@{}
    }
    $exposeProperty = $definition.PSObject.Properties['expose']
    $declaredExpose = $null
    if ($null -ne $exposeProperty) { $declaredExpose = $exposeProperty.Value }
    if ($null -ne $declaredExpose) {
        if ($declaredExpose -isnot [array]) { throw 'RESOURCE_CLEANUP_FAILED' }
        foreach ($item in $declaredExpose) {
            if ($item -isnot [string] -or $item -cnotmatch '\A[0-9]+(?:/(?:tcp|udp|sctp))?\z') { throw 'RESOURCE_CLEANUP_FAILED' }
            $key = if ($item.Contains('/')) { $item } else { $item + '/tcp' }
            $exposed[$key] = [ordered]@{}
        }
    }
    $bindings = [ordered]@{}
    $portsProperty = $definition.PSObject.Properties['ports']
    $declaredPorts = $null
    if ($null -ne $portsProperty) { $declaredPorts = $portsProperty.Value }
    if ($null -ne $declaredPorts) {
        if ($declaredPorts -isnot [array]) { throw 'RESOURCE_CLEANUP_FAILED' }
        foreach ($port in $declaredPorts) {
            $target = Get-E2EExactMember $port 'target'
            $protocol = Get-E2EExactMember $port 'protocol'
            $hostIp = Get-E2EExactMember $port 'host_ip'
            $published = Get-E2EExactMember $port 'published'
            if ($target -isnot [int] -or $target -lt 1 -or $target -gt 65535 -or
                $protocol -isnot [string] -or $protocol -cnotmatch '\A(?:tcp|udp|sctp)\z' -or
                $hostIp -isnot [string] -or $published -isnot [string] -or
                $published -cnotmatch '\A[0-9]+\z') { throw 'RESOURCE_CLEANUP_FAILED' }
            $key = [string]$target + '/' + $protocol
            if ($bindings.Contains($key)) { throw 'RESOURCE_CLEANUP_FAILED' }
            $exposed[$key] = [ordered]@{}
            $bindings[$key] = @([ordered]@{ HostIp=$hostIp; HostPort=$published })
        }
    }
    $expectedExposed = if ($exposed.Count -eq 0) { $null } else { $exposed }
    if ($null -eq $expectedExposed) {
        foreach ($key in @(Get-E2EExactKeys $config)) {
            if ([string]::Equals($key, 'ExposedPorts', [System.StringComparison]::OrdinalIgnoreCase)) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
        }
    }
    else { Assert-E2EExactContractValue $expectedExposed (Get-E2EExactMember $config 'ExposedPorts') }
    Assert-E2EExactContractValue $bindings (Get-E2EExactMember $host 'PortBindings')
    foreach ($pair in @(
        @('PublishAllPorts', $false), @('Privileged', $false),
        @('ReadonlyRootfs', [bool](Get-JsonMember $definition 'read_only')),
        @('PidMode', ''), @('IpcMode', 'private'), @('UTSMode', ''),
        @('UsernsMode', ''), @('CgroupnsMode', 'private'),
        @('Devices', $null), @('DeviceRequests', $null),
        @('ExtraHosts', @()), @('GroupAdd', $null),
        @('AutoRemove', $false)
    )) {
        Assert-E2EExactContractValue $pair[1] (Get-E2EExactMember $host $pair[0])
    }
    foreach ($key in @(Get-E2EExactKeys $host)) {
        if ([string]::Equals($key, 'Init', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    foreach ($pair in @(
        @('CapAdd', 'cap_add'), @('CapDrop', 'cap_drop'),
        @('SecurityOpt', 'security_opt')
    )) {
        $expected = Get-E2EComposeStringSet $definition $pair[1]
        Assert-E2EExactContractSet $expected (Get-E2EExactMember $host $pair[0])
    }
    $tmpfs = [ordered]@{}
    $tmpfsProperty = $definition.PSObject.Properties['tmpfs']
    $declaredTmpfs = $null
    if ($null -ne $tmpfsProperty) { $declaredTmpfs = $tmpfsProperty.Value }
    if ($null -ne $declaredTmpfs) {
        if ($declaredTmpfs -isnot [array]) { throw 'RESOURCE_CLEANUP_FAILED' }
        foreach ($entry in $declaredTmpfs) {
            if ($entry -isnot [string] -or $entry -cnotmatch '\A(/[^:]+)(?::(.*))?\z') { throw 'RESOURCE_CLEANUP_FAILED' }
            if ($tmpfs.Contains($Matches[1])) { throw 'RESOURCE_CLEANUP_FAILED' }
            $tmpfs[$Matches[1]] = [string]$Matches[2]
        }
    }
    if ($tmpfs.Count -eq 0) {
        foreach ($key in @(Get-E2EExactKeys $host)) {
            if ([string]::Equals($key, 'Tmpfs', [System.StringComparison]::OrdinalIgnoreCase)) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
        }
    }
    else { Assert-E2EExactContractValue $tmpfs (Get-E2EExactMember $host 'Tmpfs') }
}

function Assert-E2EComposeContainerIdentity {
    param($Document, [string]$Id, [string]$Project, [string]$Service, $Contract, $Receipt,
        [string[]]$AllIds, [string]$PriorBackendId)

    $config = Get-JsonMember $Document 'Config'
    $labels = Get-JsonMember $config 'Labels'
    $expectedFiles = Get-E2EComposeProjectFiles
    $observedFiles = Get-JsonMember $labels 'com.docker.compose.project.config_files'
    $observedRoot = Get-JsonMember $labels 'com.docker.compose.project.working_dir'
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $Document 'Id') $Id) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.project') $Project) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.service') $Service) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.container-number') '1') -or
        -not (Test-E2EOrdinalContains @('False','false') (Get-JsonMember $labels 'com.docker.compose.oneoff')) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $config 'Image') $Contract.Reference) -or
        -not (Test-E2EOrdinalEqual (Get-JsonMember $Document 'Image') $Contract.Id)) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    # The two Compose path labels, each decided as a whole canonical path
    # against a value computed from this repository rather than from the
    # container. A label that is absent, that carries more than one value, or
    # that carries anything besides the exact expected path, is not a spelling
    # of that path: it is not a string, or it is a different string, and either
    # way it fails here. The repository root, a sibling directory, a directory
    # underneath the expected one, and a path on another drive that merely ends
    # in the same segments are each a different string as well.
    # The raw label is decided before the comma split, because the split is
    # what would turn one smuggled line break into a value that looks clean.
    if ($observedFiles -isnot [string] -or -not (Test-E2ECleanScalar $observedFiles)) { throw 'RESOURCE_CLEANUP_FAILED' }
    $observedFileList = @($observedFiles -split ',')
    if ($observedFileList.Count -ne $expectedFiles.Count) { throw 'RESOURCE_CLEANUP_FAILED' }
    for ($index = 0; $index -lt $expectedFiles.Count; $index++) {
        if (-not (Test-E2ECleanScalar $observedFileList[$index]) -or
            -not (Test-SamePhysicalPath $observedFileList[$index] $expectedFiles[$index])) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    if (-not (Test-E2ECleanScalar $observedRoot) -or
        -not (Test-SamePhysicalPath $observedRoot (Get-E2EComposeWorkingDirectory))) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    if ($Service -in @('backend','ai-service','external-risk-mock','alertmanager-webhook')) {
        $role = if ($Service -eq 'backend') { 'backend' } else { 'ai-service' }
        $expectedLabels = Get-E2EOwnershipLabels -Receipt $Receipt -Role $role
        foreach ($key in $expectedLabels.Keys) {
            if (-not (Test-E2EOrdinalEqual (Get-JsonMember $labels $key) $expectedLabels[$key])) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
    }
    $host = Get-JsonMember $Document 'HostConfig'
    $networkMode = Get-JsonMember $host 'NetworkMode'
    $networks = Get-JsonMember (Get-JsonMember $Document 'NetworkSettings') 'Networks'
    $definition = $Contract.Definition
    $shared = Get-JsonMember $definition 'network_mode'
    if ($null -ne $shared) {
        if (-not (Test-E2EOrdinalEqual $shared 'service:backend')) { throw 'RESOURCE_CLEANUP_FAILED' }
        $actualNetworks = @(Get-JsonMemberNames $networks)
        if ($networkMode -isnot [string] -or $networkMode -cnotmatch '\Acontainer:[0-9a-f]{64}\z' -or
            (-not (Test-E2EOrdinalContains $AllIds $networkMode.Substring(10)) -and
                -not (Test-E2EOrdinalEqual $networkMode.Substring(10) $PriorBackendId)) -or
            $actualNetworks.Count -ne 0) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    else {
        $expectedNetworks = @((Get-JsonMemberNames (Get-JsonMember $definition 'networks')) | ForEach-Object { $Project + '_' + $_ })
        $actualNetworks = @(Get-JsonMemberNames $networks)
        if ($expectedNetworks.Count -eq 0 -or
            -not (Test-E2EOrdinalEqual $expectedNetworks[0] $networkMode)) { throw 'RESOURCE_CLEANUP_FAILED' }
        if (-not (Test-E2EOrdinalSetEqual $expectedNetworks $actualNetworks)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    Assert-E2EComposePortSecurityContract -Document $Document -Contract $Contract
    $mounts = @(Get-JsonMember $Document 'Mounts')
    # Every mount scalar this identity is decided on, before any of them is
    # canonicalized, split or compared. The comparisons below are the ones this
    # boundary already made; what is new is that a value carrying a smuggled
    # control character never reaches them, because normalizing a path and
    # comparing a label are each a step that would treat such a character as no
    # difference rather than as the difference it is.
    foreach ($mount in $mounts) {
        foreach ($key in @('Source', 'Destination', 'Name')) {
            $scalar = Get-JsonMember $mount $key
            if ($scalar -is [string] -and -not (Test-E2ECleanScalar $scalar)) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
    }
    if ($Service -eq 'postgresql') {
        $volumes = @($mounts | Where-Object { Test-E2EOrdinalEqual (Get-JsonMember $_ 'Type') 'volume' })
        if ($volumes.Count -ne 1 -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $volumes[0] 'Destination') '/var/lib/postgresql/data') -or
            (Get-JsonMember $volumes[0] 'Name') -cnotmatch '\A[0-9a-f]{64}\z') { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    if ($Service -eq 'keycloak') {
        $volume = @($mounts | Where-Object { Test-E2EOrdinalEqual (Get-JsonMember $_ 'Type') 'volume' })
        if ($volume.Count -ne 1 -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $volume[0] 'Name') ($Project + '_keycloak-data')) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $volume[0] 'Destination') '/opt/keycloak/data')) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    if ($Service -eq 'keycloak-bootstrap') {
        $bootstrap = @($mounts | Where-Object { (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Type') 'bind') -and
            (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Destination') '/opt/finguardops/bootstrap.py') })
        $bootstrapSource = Get-TrimmedPath ([System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'infra/keycloak/bootstrap.py')))
        if ($bootstrap.Count -ne 1 -or
            -not (Test-SameBindSourcePath (Get-JsonMember $bootstrap[0] 'Source') $bootstrapSource)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    $declared = Get-JsonMember $definition 'volumes'
    $declaredMounts = if ($null -eq $declared) { @() } else { @($declared) }
    $approvedMounts = [System.Collections.Generic.List[object]]::new()
    foreach ($volume in $declaredMounts) {
        $type = Get-JsonMember $volume 'type'
        $source = Get-JsonMember $volume 'source'
        $target = Get-JsonMember $volume 'target'
        if (-not (Test-E2EOrdinalContains @('volume','bind') $type) -or $target -isnot [string]) { throw 'RESOURCE_CLEANUP_FAILED' }
        # A bind source is compared as one whole canonical path, computed from
        # what this repository declares rather than taken from the document
        # being judged, and accepted in either spelling the daemon records it
        # in. A named volume is still compared as the exact project-qualified
        # name it is.
        $expectedSource = $null
        if ($type -ceq 'volume') {
            $expectedSource = $Project + '_' + $source
        }
        else {
            if ($source -isnot [string] -or -not (Test-CanonicalWindowsPath $source)) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
            $expectedSource = Get-TrimmedPath ([System.IO.Path]::GetFullPath($source))
        }
        $matches = @($mounts | Where-Object {
            (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Type') $type) -and
            (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Destination') $target) -and
            $(if ($type -ceq 'volume') { Test-E2EOrdinalEqual (Get-JsonMember $_ 'Name') $expectedSource }
                else { Test-SameBindSourcePath (Get-JsonMember $_ 'Source') $expectedSource })
        })
        if ($matches.Count -ne 1) { throw 'RESOURCE_CLEANUP_FAILED' }
        # A mount this repository declares read-only is required to be
        # read-only where the daemon recorded it, as a boolean the daemon
        # actually wrote.
        if ((Get-JsonMember $volume 'read_only') -eq $true) {
            $writable = Get-JsonMember $matches[0] 'RW'
            if ($writable -isnot [bool] -or $writable) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
        $approvedMounts.Add($matches[0])
    }
    $declaredSecrets = Get-JsonMember $definition 'secrets'
    $secretEntries = if ($null -eq $declaredSecrets) { @() } else { @($declaredSecrets) }
    foreach ($secret in $secretEntries) {
        $target = Get-JsonMember $secret 'target'
        if ($target -isnot [string] -or $target -match '[/\\]') { throw 'RESOURCE_CLEANUP_FAILED' }
        $matches = @($mounts | Where-Object {
            (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Type') 'bind') -and
            (Test-E2EOrdinalEqual (Get-JsonMember $_ 'Destination') ('/run/secrets/' + $target))
        })
        if ($matches.Count -ne 1) { throw 'RESOURCE_CLEANUP_FAILED' }
        $approvedMounts.Add($matches[0])
    }
    foreach ($mount in $mounts) {
        if ($approvedMounts.Contains($mount)) { continue }
        if ($Service -eq 'postgresql' -and
            (Test-E2EOrdinalEqual (Get-JsonMember $mount 'Type') 'volume') -and
            (Test-E2EOrdinalEqual (Get-JsonMember $mount 'Destination') '/var/lib/postgresql/data')) { continue }
        throw 'RESOURCE_CLEANUP_FAILED'
    }
}

function Invoke-E2EProjectCleanup {
    param([Parameter(Mandatory = $true)][string]$Project, [Parameter(Mandatory = $true)]$Receipt)

    # One path, and it is the exact one.
    #
    # `docker compose down --volumes --remove-orphans` used to run here, with
    # exact removal kept as a fallback. Two things were wrong with that. It is
    # project-wide: `--remove-orphans` removes whatever carries the project
    # label, which is a set an outside actor can add to, and `--volumes` takes
    # volumes the verification above never looked at. And it is a second read
    # of the world: whatever the inventory proved, the state `down` acts on is
    # the state at the moment `down` runs, so the gap between the two could
    # never be closed by checking harder beforehand. Removing only pinned
    # identifiers, each re-verified immediately before it is used, closes it -
    # a resource that changed underneath is no longer the resource that was
    # approved, and the command names an identifier that no longer matches.
    try {
        $before = Get-E2EProjectResourceInventory -Project $Project -Receipt $Receipt
        Invoke-E2EExactResourceCleanup -Before $before -Receipt $Receipt
        $after = Get-E2EProjectResourceInventory -Project $Project -Receipt $Receipt -PreviousInventory $before
        Assert-E2EInventorySubset -Before $before -After $after
        if ($after.Containers.Count -ne 0 -or $after.Networks.Count -ne 0 -or
            @(Get-E2EExistingVolumeNames -Names @($before.Volumes | ForEach-Object { [string]$_.Name })).Count -ne 0) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    catch { throw 'RESOURCE_CLEANUP_FAILED' }
}

function Get-E2EExistingVolumeNames {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $found = @(Get-E2EDockerLines { & docker volume ls -q --filter "name=^$name$" })
        if ($found.Count -gt 1 -or
            ($found.Count -eq 1 -and -not (Test-E2EOrdinalEqual $found[0] $name))) { throw 'RESOURCE_CLEANUP_FAILED' }
        if ($found.Count -eq 1) { Write-Output $name }
    }
}

# A JSON object of string values as an ordered map with ordinal keys.
#
# An absent member and an empty object are kept apart: a volume that declares
# no `Options` at all is not the same volume as one whose options were emptied,
# and the comparison below is entitled to notice the difference.
function Get-E2EStringMap($Value) {
    if ($null -eq $Value) { return $null }
    $map = [System.Collections.Specialized.OrderedDictionary]::new([System.StringComparer]::Ordinal)
    foreach ($name in @(Get-JsonMemberNames $Value)) {
        $entry = Get-JsonMember $Value $name
        if ($name -isnot [string] -or $entry -isnot [string] -or $map.Contains([string]$name)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
        $map[[string]$name] = [string]$entry
    }
    return $map
}

function Test-E2EStringMapEqual($Expected, $Actual) {
    if ($null -eq $Expected -or $null -eq $Actual) {
        return ($null -eq $Expected -and $null -eq $Actual)
    }
    if ($Expected.Count -ne $Actual.Count) { return $false }
    foreach ($name in @($Expected.Keys)) {
        if (-not $Actual.Contains($name)) { return $false }
        if (-not [string]::Equals([string]$Expected[$name], [string]$Actual[$name], [System.StringComparison]::Ordinal)) {
            return $false
        }
    }
    return $true
}

# Everything Docker's Volume API can be asked about one volume, as one record.
#
# `docker volume rm` takes a name and nothing else: there is no immutable
# volume ID and no compare-and-delete, so "is this still the volume that was
# approved?" can only be answered from the fields the daemon does report. All
# of them are captured here - the creation timestamp, the driver, the scope,
# the mountpoint, every label and every driver option - and compared exactly
# immediately before a removal. A volume that was replaced under the same name
# between the inventory and the removal is a different record and is refused.
#
# This is the strongest identity the API offers. It does not claim to settle a
# race run by an actor with direct daemon access between the last inspect and
# the single delete command, which no caller of this API can close.
function Get-E2EVolumeIdentity {
    param([Parameter(Mandatory = $true)][string]$Name)

    $encoded = Invoke-NativeStdout { & docker volume inspect --format '{{json .}}' $Name }
    if ($LASTEXITCODE -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
    try { $document = $encoded | ConvertFrom-Json } catch { throw 'RESOURCE_CLEANUP_FAILED' }
    if ($null -eq $document -or $document -is [array] -or $document -is [string]) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Name') $Name)) { throw 'RESOURCE_CLEANUP_FAILED' }
    $identity = [ordered]@{ Name = [string]$Name }
    foreach ($field in @('CreatedAt', 'Driver', 'Scope', 'Mountpoint')) {
        $value = Get-JsonMember $document $field
        if ($value -isnot [string] -or $value.Length -eq 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        $identity[$field] = [string]$value
    }
    foreach ($field in @('Labels', 'Options')) {
        $identity[$field] = Get-E2EStringMap (Get-JsonMember $document $field)
    }
    return [pscustomobject]$identity
}

function Test-E2EVolumeIdentityEqual($Expected, $Actual) {
    if ($null -eq $Expected -or $null -eq $Actual) { return $false }
    foreach ($field in @('Name', 'CreatedAt', 'Driver', 'Scope', 'Mountpoint')) {
        if (-not [string]::Equals([string]$Expected.$field, [string]$Actual.$field, [System.StringComparison]::Ordinal)) {
            return $false
        }
    }
    return (Test-E2EStringMapEqual $Expected.Labels $Actual.Labels) -and
        (Test-E2EStringMapEqual $Expected.Options $Actual.Options)
}

# The argument vector for "exactly these names and nothing else".
#
# Docker's `name` filter is a regular expression, so an unanchored value would
# also match a longer name that merely contains it. Every value produced here
# is anchored at both ends, and repeated `name` values are ORed by Docker, so
# the whole vector asks for the given names exactly.
function Get-E2EExactNameFilters {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Names,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Prefix
    )

    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $Names) {
        $arguments.Add('--filter')
        $arguments.Add('name=^' + $Prefix + [regex]::Escape($name) + '$')
    }
    return $arguments.ToArray()
}

# A container discovery's answer, as the full identifiers it is required to be.
#
# One place decides what a container discovery is allowed to have returned, so
# every boundary that goes on to inspect, stop or remove by identifier is
# working from the same statement: each line is a full 64-character lower-case
# identifier, no line repeats, and anything else is the caller's own fixed
# error rather than a target.
function Get-E2EFullContainerIdentifiers {
    param(
        [AllowNull()][AllowEmptyString()]$Output,
        [int]$ExitCode,
        [Parameter(Mandatory = $true)][string]$ErrorCode
    )

    if ($ExitCode -ne 0) { throw $ErrorCode }
    $identifiers = [System.Collections.Generic.List[string]]::new()
    if ($Output -isnot [string]) {
        if ($null -eq $Output) { return @() }
        throw $ErrorCode
    }
    foreach ($line in ($Output -split '\r?\n')) {
        $value = $line.Trim()
        if ($value.Length -eq 0) { continue }
        if ($value -cnotmatch '\A[0-9a-f]{64}\z' -or (Test-E2EOrdinalContains $identifiers $value)) { throw $ErrorCode }
        $identifiers.Add($value)
    }
    return @($identifiers.ToArray())
}

function Get-E2EDockerLines([scriptblock]$Command) {
    $output = Invoke-NativeStdout $Command
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
    if ([string]::IsNullOrEmpty($output)) { return }
    foreach ($line in ($output -split '\r?\n')) {
        if ($line.Length -ne 0) { Write-Output $line }
    }
}

function Get-E2EProjectResourceInventory {
    param([Parameter(Mandatory = $true)][string]$Project, [Parameter(Mandatory = $true)]$Receipt, $PreviousInventory)
    if (-not (Test-E2EOrdinalEqual $Project $ProjectName) -and $Project -cnotmatch '\Afinguardops-kc241-e2e-[0-9a-f]{12}\z') {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    $services = $E2EComposeServices
    $networkNames = $E2EComposeNetworks
    $volumeNames = $E2EComposeVolumes
    $projectFilter = "label=com.docker.compose.project=$Project"
    # What may be removed is decided by name, not by label.
    #
    # A label query answers "what currently carries this project's label",
    # and that is a set an outside actor can add to between the answer and the
    # removal. The names below are fixed by the Compose contract and by the
    # project this run owns, so looking them up asks a question whose answer
    # nobody else can extend. The label query still runs, immediately after and
    # read-only, but only to refuse a project holding something these names do
    # not account for - never to contribute a removal target.
    $expectedNames = [ordered]@{}
    foreach ($service in $services) { $expectedNames[$service] = $Project + '-' + $service + '-1' }
    $nameFilters = Get-E2EExactNameFilters -Names @($expectedNames.Values) -Prefix '/'
    $candidates = @(Get-E2EDockerLines { & docker ps -aq --no-trunc @nameFilters })
    $ids = @()
    $serviceDocuments = @{}
    $presentServices = [System.Collections.Generic.List[string]]::new()
    foreach ($id in $candidates) {
        if ($id -cnotmatch '\A[0-9a-f]{64}\z' -or (Test-E2EOrdinalContains $ids $id)) { throw 'RESOURCE_CLEANUP_FAILED' }
        $document = Get-ContainerDocument $id
        $observedName = Get-JsonMember $document 'Name'
        if ($observedName -isnot [string]) { throw 'RESOURCE_CLEANUP_FAILED' }
        $service = $null
        foreach ($candidate in $services) {
            if (Test-E2EOrdinalEqual $observedName ('/' + $expectedNames[$candidate])) { $service = $candidate; break }
        }
        if ($null -eq $service -or (Test-E2EOrdinalContains $presentServices $service)) { throw 'RESOURCE_CLEANUP_FAILED' }
        $declaredService = Get-JsonMember (Get-JsonMember (Get-JsonMember $document 'Config') 'Labels') 'com.docker.compose.service'
        if (-not (Test-E2EOrdinalEqual $declaredService $service)) { throw 'RESOURCE_CLEANUP_FAILED' }
        $ids += $id
        $serviceDocuments[$id] = $document
        $presentServices.Add($service)
    }
    $labelled = @(Get-E2EDockerLines { & docker ps -aq --no-trunc --filter $projectFilter })
    if ($labelled.Count -ne @($labelled | Sort-Object -Unique).Count) { throw 'RESOURCE_CLEANUP_FAILED' }
    foreach ($id in $labelled) {
        if ($id -cnotmatch '\A[0-9a-f]{64}\z' -or -not (Test-E2EOrdinalContains $ids $id)) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    $contract = if ($ids.Count -ne 0) { Get-E2EComposeOwnershipContract -Project $Project -Receipt $Receipt -PresentServices $presentServices.ToArray() } else { @{} }
    $containers = [System.Collections.Generic.List[object]]::new()
    $foundServices = [System.Collections.Generic.List[string]]::new()
    $priorBackend = @()
    if ($null -ne $PreviousInventory) {
        if (-not (Test-E2EOrdinalEqual $PreviousInventory.Project $Project)) { throw 'RESOURCE_CLEANUP_FAILED' }
        $priorBackend = @($PreviousInventory.Containers | Where-Object { Test-E2EOrdinalEqual $_.Service 'backend' })
        if ($priorBackend.Count -gt 1) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    $priorBackendId = if ($priorBackend.Count -eq 1) { [string]$priorBackend[0].Id } else { '' }
    $anonymous = [System.Collections.Generic.List[string]]::new()
    $mountedNamed = [System.Collections.Generic.List[string]]::new()
    foreach ($id in $ids) {
        if ($id -cnotmatch '\A[0-9a-f]{64}\z') { throw 'RESOURCE_CLEANUP_FAILED' }
        $document = $serviceDocuments[$id]
        $config = Get-JsonMember $document 'Config'
        $labels = Get-JsonMember $config 'Labels'
        $service = Get-JsonMember $labels 'com.docker.compose.service'
        $state = Get-E2EExactMember $document 'State'
        if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $id) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.project') $Project) -or
            -not (Test-E2EOrdinalContains $services $service) -or
            $null -eq $state) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
        Assert-E2EContainerState $state
        if (Test-E2EOrdinalContains $foundServices $service) { throw 'RESOURCE_CLEANUP_FAILED' }
        $foundServices.Add($service)
        Assert-E2EComposeContainerIdentity -Document $document -Id $id -Project $Project `
            -Service $service -Contract $contract[$service] -Receipt $Receipt -AllIds $ids -PriorBackendId $priorBackendId
        foreach ($mount in @(Get-JsonMember $document 'Mounts')) {
            # Every mount on this container has already been decided by the
            # identity check above, which approves a mount only when its type is
            # ordinally the declared one - so `volume` here is the type the
            # daemon recorded and this run approved, not a spelling of it.
            if (-not (Test-E2EOrdinalEqual (Get-JsonMember $mount 'Type') 'volume')) { continue }
            $name = Get-JsonMember $mount 'Name'
            if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name)) { throw 'RESOURCE_CLEANUP_FAILED' }
            if ($name -cmatch ('\A' + [regex]::Escape($Project) + '_(?:' + ($volumeNames -join '|') + ')\z')) {
                if (-not (Test-E2EOrdinalContains $mountedNamed $name)) { $mountedNamed.Add($name) }
            }
            else {
                if ($name -cnotmatch '\A[0-9a-f]{64}\z') { throw 'RESOURCE_CLEANUP_FAILED' }
                if (-not (Test-E2EOrdinalContains $anonymous $name)) { $anonymous.Add($name) }
            }
        }
        $containers.Add([pscustomobject]@{ Id=$id; Service=$service; Image=(Get-JsonMember $document 'Image');
            ImageReference=(Get-JsonMember $config 'Image'); Running=(Get-JsonMember $state 'Running');
            NetworkMode=(Get-JsonMember (Get-JsonMember $document 'HostConfig') 'NetworkMode');
            NetworkAttachments=(Get-JsonMember (Get-JsonMember $document 'NetworkSettings') 'Networks') })
    }
    $backendEntries = @($containers | Where-Object { Test-E2EOrdinalEqual $_.Service 'backend' })
    foreach ($entry in $containers) {
        if (Test-E2EOrdinalContains @('external-risk-mock','keycloak','keycloak-bootstrap','keycloak-verify') $entry.Service) {
            $expectedBackendId = if ($backendEntries.Count -eq 1) { $backendEntries[0].Id } else { $priorBackendId }
            if ([string]::IsNullOrEmpty($expectedBackendId) -or
                -not (Test-E2EOrdinalEqual $entry.NetworkMode ('container:' + $expectedBackendId))) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
        }
    }
    $expectedNetworks = [ordered]@{}
    foreach ($networkName in $networkNames) { $expectedNetworks[$networkName] = $Project + '_' + $networkName }
    $networkFilters = Get-E2EExactNameFilters -Names @($expectedNetworks.Values) -Prefix ''
    $networkCandidates = @(Get-E2EDockerLines { & docker network ls -q --no-trunc @networkFilters })
    $networks = [System.Collections.Generic.List[object]]::new()
    $networkIdentifiers = [System.Collections.Generic.List[string]]::new()
    foreach ($id in $networkCandidates) {
        if ($id -cnotmatch '\A[0-9a-f]{64}\z' -or (Test-E2EOrdinalContains $networkIdentifiers $id)) { throw 'RESOURCE_CLEANUP_FAILED' }
        $networkIdentifiers.Add($id)
        $encoded = Invoke-NativeStdout { & docker network inspect --format '{{json .}}' $id }
        if ($LASTEXITCODE -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        try { $document = $encoded | ConvertFrom-Json } catch { throw 'RESOURCE_CLEANUP_FAILED' }
        $labels = Get-JsonMember $document 'Labels'
        $network = Get-JsonMember $labels 'com.docker.compose.network'
        $attached = @(Get-JsonMemberNames (Get-JsonMember $document 'Containers'))
        $observedName = Get-JsonMember $document 'Name'
        if (-not (Test-E2EOrdinalContains $networkNames $network) -or
            -not (Test-E2EOrdinalEqual $observedName $expectedNetworks[$network]) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $id) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.project') $Project) -or
            @($networks | Where-Object { Test-E2EOrdinalEqual $_.Name $network }).Count -ne 0 -or
            @($attached | Where-Object { -not (Test-E2EOrdinalContains $ids $_) }).Count -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        $networks.Add([pscustomobject]@{ Id=$id; Name=$network; Attached=$attached })
    }
    $networkIds = @($networkIdentifiers.ToArray())
    $labelledNetworks = @(Get-E2EDockerLines { & docker network ls -q --no-trunc --filter $projectFilter })
    if ($labelledNetworks.Count -ne @($labelledNetworks | Sort-Object -Unique).Count) { throw 'RESOURCE_CLEANUP_FAILED' }
    foreach ($id in $labelledNetworks) {
        if ($id -cnotmatch '\A[0-9a-f]{64}\z' -or -not (Test-E2EOrdinalContains $networkIds $id)) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    foreach ($entry in $containers) {
        foreach ($name in @(Get-JsonMemberNames $entry.NetworkAttachments)) {
            if ($name -isnot [string] -or $name.Length -le $Project.Length -or
                -not (Test-E2EOrdinalEqual $name.Substring(0, $Project.Length + 1) ($Project + '_'))) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
            $matches = @($networks | Where-Object { Test-E2EOrdinalEqual $_.Name $name.Substring($Project.Length + 1) })
            $attachment = Get-JsonMember $entry.NetworkAttachments $name
            # 실행 중인 container는 network 쪽 `.Containers`에도 exact full ID로 있어야
            # 한다. 정지된 container는 Docker가 container 쪽 network identity를 그대로
            # 둔 채 network 쪽에서만 빼므로 그 부재만 허용하고, container 쪽 network
            # 집합과 NetworkID 검사는 상태와 관계없이 똑같이 적용한다.
            if ($matches.Count -ne 1 -or
                -not (Test-E2EOrdinalEqual (Get-JsonMember $attachment 'NetworkID') $matches[0].Id) -or
                ($entry.Running -and -not (Test-E2EOrdinalContains $matches[0].Attached $entry.Id))) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
    }
    # 반대 방향. network 쪽에 실제로 남아 있는 entry는 실행 중이든 정지됐든 이 run이
    # 소유한 container 하나를 가리켜야 하고, 그 container가 container 쪽에서도 바로 이
    # network ID에 붙어 있어야 한다. 위의 부재 허용은 이 관계가 없는 entry를 받아들이는
    # 근거가 되지 못한다.
    foreach ($network in $networks) {
        foreach ($attachedId in @($network.Attached)) {
            $owners = @($containers | Where-Object { Test-E2EOrdinalEqual $_.Id $attachedId })
            $attachment = if ($owners.Count -eq 1) { Get-JsonMember $owners[0].NetworkAttachments ($Project + '_' + $network.Name) } else { $null }
            if ($owners.Count -ne 1 -or
                -not (Test-E2EOrdinalEqual (Get-JsonMember $attachment 'NetworkID') $network.Id)) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
    }
    $expectedVolumes = @($volumeNames | ForEach-Object { $Project + '_' + $_ })
    $named = @(Get-E2EExistingVolumeNames -Names $expectedVolumes)
    if ($named.Count -ne @($named | Sort-Object -Unique).Count) { throw 'RESOURCE_CLEANUP_FAILED' }
    $labelledVolumes = @(Get-E2EDockerLines { & docker volume ls -q --filter $projectFilter })
    if ($labelledVolumes.Count -ne @($labelledVolumes | Sort-Object -Unique).Count) { throw 'RESOURCE_CLEANUP_FAILED' }
    foreach ($name in $labelledVolumes) {
        if (-not (Test-E2EOrdinalContains $named $name)) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    if (@($mountedNamed | Where-Object { -not (Test-E2EOrdinalContains $named $_) }).Count -ne 0) {
        throw 'RESOURCE_CLEANUP_FAILED'
    }
    # Each volume is pinned as its whole identity rather than as a name, so the
    # removal step has something to compare against that a same-name volume
    # created in the meantime does not satisfy.
    $volumes = [System.Collections.Generic.List[object]]::new()
    $volumeIdentityNames = [System.Collections.Generic.List[string]]::new()
    foreach ($name in @($named) + @($anonymous)) {
        $identity = Get-E2EVolumeIdentity -Name $name
        $labels = $identity.Labels
        if ($null -eq $labels) { throw 'RESOURCE_CLEANUP_FAILED' }
        if (Test-E2EOrdinalContains $named $name) {
            if (-not $labels.Contains('com.docker.compose.volume') -or
                -not $labels.Contains('com.docker.compose.project')) { throw 'RESOURCE_CLEANUP_FAILED' }
            $volume = [string]$labels['com.docker.compose.volume']
            if (-not (Test-E2EOrdinalContains $volumeNames $volume) -or
                -not (Test-E2EOrdinalEqual $name ($Project + '_' + $volume)) -or
                -not (Test-E2EOrdinalEqual ([string]$labels['com.docker.compose.project']) $Project)) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
        elseif (-not $labels.Contains('com.docker.volume.anonymous') -or
            -not (Test-E2EOrdinalEqual ([string]$labels['com.docker.volume.anonymous']) '')) { throw 'RESOURCE_CLEANUP_FAILED' }
        if (Test-E2EOrdinalContains $volumeIdentityNames $name) { throw 'RESOURCE_CLEANUP_FAILED' }
        $volumeIdentityNames.Add($name)
        $volumes.Add($identity)
    }
    # A volume seen only in an owned mount must never also serve another container.
    if ($volumes.Count -ne 0) {
        $allIds = @(Get-E2EDockerLines { & docker ps -aq --no-trunc })
        foreach ($id in $allIds) {
            $document = Get-ContainerDocument $id
            # A mount that names one of these volumes is a user of it,
            # whatever it calls its own type: the name is the identity the
            # Volume API removes by, and the type is one more daemon-supplied
            # scalar this refusal deliberately does not depend on.
            foreach ($mount in @(Get-JsonMember $document 'Mounts')) {
                if ((Test-E2EOrdinalContains $volumeIdentityNames (Get-JsonMember $mount 'Name')) -and
                    -not (Test-E2EOrdinalContains $ids $id)) {
                    throw 'RESOURCE_CLEANUP_FAILED'
                }
            }
        }
    }
    return [pscustomobject]@{ Project=$Project; Containers=@($containers.ToArray());
        Networks=@($networks.ToArray()); Volumes=@($volumes.ToArray()) }
}

function Assert-E2EInventorySubset {
    param($Before, $After)
    if (-not (Test-E2EOrdinalEqual $Before.Project $After.Project)) { throw 'RESOURCE_CLEANUP_FAILED' }
    foreach ($current in $After.Containers) {
        $prior = @($Before.Containers | Where-Object { Test-E2EOrdinalEqual $_.Id $current.Id })
        if ($prior.Count -ne 1 -or
            -not (Test-E2EOrdinalEqual $prior[0].Service $current.Service) -or
            -not (Test-E2EOrdinalEqual $prior[0].Image $current.Image) -or
            -not (Test-E2EOrdinalEqual $prior[0].ImageReference $current.ImageReference)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    foreach ($current in $After.Networks) {
        $prior = @($Before.Networks | Where-Object { Test-E2EOrdinalEqual $_.Id $current.Id })
        if ($prior.Count -ne 1 -or -not (Test-E2EOrdinalEqual $prior[0].Name $current.Name)) { throw 'RESOURCE_CLEANUP_FAILED' }
    }
    foreach ($current in $After.Volumes) {
        $prior = @($Before.Volumes | Where-Object { Test-E2EOrdinalEqual $_.Name $current.Name })
        if ($prior.Count -ne 1 -or -not (Test-E2EVolumeIdentityEqual $prior[0] $current)) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
}

# The only path by which this script removes a Compose resource.
#
# Every target here is an identifier that the inventory pinned and approved,
# and every target is re-established immediately before the command that acts
# on it: the inventory is taken again, proved to be a subset of what was
# approved, and the entry looked up by its exact full identifier. A container
# that was replaced under the same name is a different identifier and is simply
# not found; a network that acquired an attachment, or a volume that acquired a
# user, fails rather than being removed. Nothing is matched by prefix, glob,
# project label or service label, nothing is forced, and nothing this run did
# not approve is named.
function Invoke-E2EExactResourceCleanup {
    param($Before, [Parameter(Mandatory = $true)]$Receipt)
    foreach ($entry in $Before.Containers) {
        $current = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
        Assert-E2EInventorySubset -Before $Before -After $current
        $owned = @($current.Containers | Where-Object { Test-E2EOrdinalEqual $_.Id $entry.Id })
        if ($owned.Count -eq 0) { continue }
        if ($owned[0].Running) {
            Invoke-Native { & docker stop $entry.Id 2>$null | Out-Null }
            $code = $LASTEXITCODE
            if ($code -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
            $stopped = Get-ContainerDocument $entry.Id
            $stoppedState = Get-E2EExactMember $stopped 'State'
            if (-not (Test-E2EOrdinalEqual (Get-JsonMember $stopped 'Id') $entry.Id) -or
                (Get-JsonMember $stoppedState 'Running') -ne $false) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
            Assert-E2EContainerState $stoppedState
            # Stopping is itself a window, so ownership of this exact identifier
            # is established once more before anything is removed.
            $current = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
            Assert-E2EInventorySubset -Before $Before -After $current
            if (@($current.Containers | Where-Object { Test-E2EOrdinalEqual $_.Id $entry.Id }).Count -ne 1) {
                throw 'RESOURCE_CLEANUP_FAILED'
            }
        }
        Invoke-Native { & docker rm $entry.Id 2>$null | Out-Null }
        $code = $LASTEXITCODE
        if ($code -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        $remaining = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
        Assert-E2EInventorySubset -Before $Before -After $remaining
        if (@($remaining.Containers | Where-Object { Test-E2EOrdinalEqual $_.Id $entry.Id }).Count -ne 0) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    foreach ($entry in $Before.Networks) {
        $current = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
        Assert-E2EInventorySubset -Before $Before -After $current
        $owned = @($current.Networks | Where-Object { Test-E2EOrdinalEqual $_.Id $entry.Id })
        if ($owned.Count -eq 0) { continue }
        if ($owned[0].Attached.Count -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        Invoke-Native { & docker network rm $entry.Id 2>$null | Out-Null }
        $code = $LASTEXITCODE
        if ($code -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        $remaining = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
        Assert-E2EInventorySubset -Before $Before -After $remaining
        if (@($remaining.Networks | Where-Object { Test-E2EOrdinalEqual $_.Id $entry.Id }).Count -ne 0) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
    foreach ($entry in $Before.Volumes) {
        $name = [string]$entry.Name
        $current = Get-E2EProjectResourceInventory -Project $Before.Project -Receipt $Receipt -PreviousInventory $Before
        Assert-E2EInventorySubset -Before $Before -After $current
        # A volume that is simply gone is nothing to do. A volume that is still
        # there has to be the same volume: the exact name is inspected once
        # more and every field the Volume API reports - creation time, driver,
        # scope, mountpoint, labels, options - has to equal what the inventory
        # pinned. One difference, and this leaves it alone rather than removing
        # whatever now answers to that name.
        if (@(Get-E2EExistingVolumeNames -Names @($name)).Count -eq 0) { continue }
        if (-not (Test-E2EVolumeIdentityEqual $entry (Get-E2EVolumeIdentity -Name $name))) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
        $allIds = @(Get-E2EDockerLines { & docker ps -aq --no-trunc })
        foreach ($id in $allIds) {
            $document = Get-ContainerDocument $id
            if (@(Get-JsonMember $document 'Mounts' | Where-Object {
                    Test-E2EOrdinalEqual (Get-JsonMember $_ 'Name') $name
                }).Count -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        }
        # The exact name, without `--force`, without a prefix, a glob or a
        # label. This is the only volume removal this script performs.
        Invoke-Native { & docker volume rm $name 2>$null | Out-Null }
        $code = $LASTEXITCODE
        if ($code -ne 0) { throw 'RESOURCE_CLEANUP_FAILED' }
        if (@(Get-E2EExistingVolumeNames -Names @($name)).Count -ne 0) {
            throw 'RESOURCE_CLEANUP_FAILED'
        }
    }
}

function Assert-E2EExistingProjectOwnership {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$Project
    )

    # Full identifiers, asked for as such and required to be such.
    #
    # Without `--no-trunc` the client answers with twelve characters, and a
    # twelve-character identifier handed to `inspect` is a prefix query the
    # daemon resolves to whichever container matches it. Every answer is
    # therefore required to be a full 64-character lower-case identifier, and a
    # short one, a longer prefix, a differently-cased one or a repeated one is
    # a discovery this run does not understand rather than a container it owns.
    $ids = @(Get-E2EFullContainerIdentifiers `
        -Output (Invoke-NativeStdout { & docker ps -aq --no-trunc --filter "label=com.docker.compose.project=$Project" }) `
        -ExitCode $LASTEXITCODE -ErrorCode 'RESOURCE_OWNERSHIP_INVALID')
    if ($ids.Count -eq 0) { return }
    $images = Get-E2EImageSet -Receipt $Receipt
    $records = @{}
    foreach ($id in $ids) {
        $document = Get-ContainerDocument $id
        if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $id)) { throw 'RESOURCE_OWNERSHIP_INVALID' }
        $configuration = Get-JsonMember $document 'Config'
        $labels = Get-JsonMember $configuration 'Labels'
        if (-not (Test-E2EOrdinalEqual (Get-JsonMember $labels 'com.docker.compose.project') $Project)) {
            throw 'RESOURCE_OWNERSHIP_INVALID'
        }
        # The service label decides which image this container is required to be
        # running, so a label that is not exactly one of the services this
        # repository declares is a container this project does not account for
        # rather than one to pass over: skipping it is what would let a label
        # spelled with a smuggled character escape the check below entirely.
        $service = Get-JsonMember $labels 'com.docker.compose.service'
        if (-not (Test-E2EOrdinalContains $E2EComposeServices $service)) {
            throw 'RESOURCE_OWNERSHIP_INVALID'
        }
        $expectedReference = $null
        $role = $null
        if (Test-E2EOrdinalEqual $service 'backend') {
            $expectedReference = $images.Backend
            $role = 'backend'
        }
        elseif (Test-E2EOrdinalContains @('ai-service', 'external-risk-mock', 'alertmanager-webhook') $service) {
            $expectedReference = $images.AiService
            $role = 'ai-service'
        }
        if ($null -eq $expectedReference) { continue }
        if (-not $records.ContainsKey($role)) {
            $records[$role] = Get-E2ENormalizedImageRecord -Reference $expectedReference -Role $role -Receipt $Receipt
        }
        if (-not (Test-E2EOrdinalEqual (Get-JsonMember $configuration 'Image') $expectedReference) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Image') $records[$role].Id)) {
            throw 'RESOURCE_OWNERSHIP_INVALID'
        }
    }
}

# The explicit Cleanup mode's browser container.
#
# Discovery is all this does on its own: the one fixed name this script ever
# gives a browser container answers with a candidate full identifier, and every
# decision about whether that identifier may be stopped or removed belongs to
# the boundary the Run-end cleanup goes through - `Remove-OwnedContainer`,
# handed the receipt-derived browser ownership contract. There is no second,
# weaker browser deletion for this mode to use.
#
# A name that nothing carries is success: there is nothing here to finish, and
# the contract is deliberately not built in that case, so a mode that runs
# after the owned images are already gone stays idempotent rather than failing
# on an image nothing needs.
function Remove-E2EOwnedBrowserContainer {
    param([Parameter(Mandatory = $true)]$Receipt)

    $filters = Get-E2EExactNameFilters -Names @($BrowserContainerName) -Prefix '/'
    $ids = @(Get-E2EDockerLines { & docker ps -aq --no-trunc @filters })
    if ($ids.Count -eq 0) { return }
    if ($ids.Count -ne 1 -or $ids[0] -cnotmatch '\A[0-9a-f]{64}\z') {
        throw 'RESOURCE_OWNERSHIP_INVALID'
    }
    $contract = Get-E2EBrowserOwnershipContract -Receipt $Receipt
    Remove-OwnedContainer $ids[0] $contract.ImageId $contract
}

function Invoke-E2EResourceCleanup {
    param([Parameter(Mandatory = $true)]$Receipt)

    $serviceProject = Get-E2EServiceProjectName -Receipt $Receipt
    # Every read-only question about both projects is asked before the first
    # command that changes anything, so a world this run does not recognise
    # stops it while there is still nothing to undo.
    Get-E2EProjectResourceInventory -Project $ProjectName -Receipt $Receipt | Out-Null
    Get-E2EProjectResourceInventory -Project $serviceProject -Receipt $Receipt | Out-Null
    Assert-E2EExistingProjectOwnership -Receipt $Receipt -Project $ProjectName
    Assert-E2EExistingProjectOwnership -Receipt $Receipt -Project $serviceProject
    Remove-E2EOwnedBrowserContainer -Receipt $Receipt
    Invoke-E2EProjectCleanup -Project $ProjectName -Receipt $Receipt
    Invoke-E2EProjectCleanup -Project $serviceProject -Receipt $Receipt
}

# The read-only statement that cleanup actually finished.
#
# Every removal above proved its own target gone, but "each target is gone" is
# not "nothing owned is left": a resource that appeared under either project
# while cleanup was running, or an owned image the image step did not remove,
# satisfies every individual proof. The receipt is what lets a later run find
# and finish this work, so it is deleted only after this says there is nothing
# left to find. Nothing here removes anything.
function Invoke-E2EResidueAudit {
    param([Parameter(Mandatory = $true)]$Receipt)

    foreach ($project in @($ProjectName, (Get-E2EServiceProjectName -Receipt $Receipt))) {
        $inventory = $null
        try {
            $inventory = Get-E2EProjectResourceInventory -Project $project -Receipt $Receipt
        }
        catch { throw 'CLEANUP_RESIDUE_DETECTED' }
        if ($inventory.Containers.Count -ne 0 -or $inventory.Networks.Count -ne 0 -or
            $inventory.Volumes.Count -ne 0) {
            throw 'CLEANUP_RESIDUE_DETECTED'
        }
    }
    $browserFilter = 'name=^/' + $BrowserContainerName + '$'
    $remaining = $null
    try {
        $remaining = @(Get-E2EDockerLines { & docker ps -aq --no-trunc --filter $browserFilter })
    }
    catch { throw 'CLEANUP_RESIDUE_DETECTED' }
    if ($remaining.Count -ne 0) { throw 'CLEANUP_RESIDUE_DETECTED' }
    $images = Get-E2EImageSet -Receipt $Receipt
    foreach ($reference in @($images.Backend, $images.AiService, $images.Browser)) {
        if ($null -ne (Get-LocalImageDocument $reference)) { throw 'CLEANUP_RESIDUE_DETECTED' }
    }
}

function Invoke-E2EImageCleanup {
    param([Parameter(Mandatory = $true)]$Receipt)

    $images = Get-E2EImageSet -Receipt $Receipt
    $targets = @(
        [pscustomobject]@{ Reference = $images.Backend; Role = 'backend' },
        [pscustomobject]@{ Reference = $images.AiService; Role = 'ai-service' },
        [pscustomobject]@{ Reference = $images.Browser; Role = 'browser' }
    )
    $owned = [System.Collections.Generic.List[object]]::new()
    foreach ($target in $targets) {
        $record = Get-E2ENormalizedImageRecord -Reference $target.Reference -Role $target.Role -Receipt $Receipt -AllowMissing
        if ($null -eq $record) { continue }
        $users = @(Invoke-NativeStdout { & docker ps -aq --filter "ancestor=$($record.Id)" })
        if ($LASTEXITCODE -ne 0) { throw 'IMAGE_CLEANUP_FAILED' }
        $record.InUse = @($users | Where-Object { $_ }).Count -ne 0
        Test-E2ECleanupTarget -Reference $record.Reference -ExpectedId $record.Id -Document $record -ExpectedLabels $record.Labels | Out-Null
        $owned.Add($record)
    }
    foreach ($record in $owned) {
        $current = Get-E2ENormalizedImageRecord -Reference $record.Reference -Role $record.Role -Receipt $Receipt
        $currentUsers = @(Invoke-NativeStdout { & docker ps -aq --filter "ancestor=$($current.Id)" })
        if ($LASTEXITCODE -ne 0) { throw 'IMAGE_CLEANUP_FAILED' }
        $current.InUse = @($currentUsers | Where-Object { $_ }).Count -ne 0
        Test-E2ECleanupTarget -Reference $current.Reference -ExpectedId $record.Id -Document $current -ExpectedLabels $record.Labels | Out-Null
        $removeArguments = New-E2EImageRemoveArguments -Reference $record.Reference
        Invoke-Native { & docker @removeArguments | Out-Null }
        Assert-Success 'Owned E2E image cleanup'
    }
}

function Invoke-E2EFullCleanup {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [string]$RepositoryRootPath = $RepositoryRoot,
        $LeafBoundaries,
        [switch]$RequireLeafBoundaries
    )

    if ($RequireLeafBoundaries -and $null -eq $LeafBoundaries) {
        throw 'CLEANUP_BOUNDARY_INVALID'
    }
    if ($null -eq $LeafBoundaries) {
        $LeafBoundaries = @{
            ResourceCleanup = { param($value) Invoke-E2EResourceCleanup -Receipt $value }
            ImageCleanup = { param($value) Invoke-E2EImageCleanup -Receipt $value }
            FinalAudit = { param($value) Invoke-E2EResidueAudit -Receipt $value }
            DeleteFile = { param([string]$value) [System.IO.File]::Delete($value) }
        }
    }
    $resourceCleanup = $LeafBoundaries['ResourceCleanup']
    $imageCleanup = $LeafBoundaries['ImageCleanup']
    $finalAudit = $LeafBoundaries['FinalAudit']
    $deleteFile = $LeafBoundaries['DeleteFile']
    if ($resourceCleanup -isnot [scriptblock] -or $imageCleanup -isnot [scriptblock] -or
        $finalAudit -isnot [scriptblock] -or $deleteFile -isnot [scriptblock]) {
        throw 'CLEANUP_BOUNDARY_INVALID'
    }
    $actions = @(
        [pscustomobject]@{
            Action = { & $resourceCleanup $Receipt }.GetNewClosure()
            ErrorCode = 'RESOURCE_CLEANUP_FAILED'
            SkipAfterCleanupFailure = $false
        },
        [pscustomobject]@{
            Action = { & $imageCleanup $Receipt }.GetNewClosure()
            ErrorCode = 'IMAGE_CLEANUP_FAILED'
            SkipAfterCleanupFailure = $true
        },
        [pscustomobject]@{
            Action = { & $finalAudit $Receipt }.GetNewClosure()
            ErrorCode = 'CLEANUP_RESIDUE_DETECTED'
            SkipAfterCleanupFailure = $true
        },
        [pscustomobject]@{
            Action = { Remove-E2EReceiptFile -Path $ReceiptPath -RepositoryRoot $RepositoryRootPath -DeleteFile $deleteFile }.GetNewClosure()
            ErrorCode = 'RECEIPT_DELETE_FAILED'
            SkipAfterCleanupFailure = $true
        }
    )
    Invoke-E2ECleanupActions -Primary $null -Actions $actions
}

function Assert-E2EContainerImages {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$Project
    )

    $values = @(Assert-E2EOwnedImages -Receipt $Receipt)
    Assert-E2EImageRecordSet -Values $values -Receipt $Receipt
    $records = $values[0]
    $images = Get-E2EImageSet -Receipt $Receipt
    $expected = @{
        backend = [pscustomobject]@{ Reference = $images.Backend; Id = $records.Backend.Id }
        'ai-service' = [pscustomobject]@{ Reference = $images.AiService; Id = $records.AiService.Id }
        'external-risk-mock' = [pscustomobject]@{ Reference = $images.AiService; Id = $records.AiService.Id }
        'alertmanager-webhook' = [pscustomobject]@{ Reference = $images.AiService; Id = $records.AiService.Id }
    }
    foreach ($service in $expected.Keys) {
        $ids = @(Get-E2EFullContainerIdentifiers `
            -Output (Invoke-NativeStdout { & docker ps -aq --no-trunc --filter "label=com.docker.compose.project=$Project" --filter "label=com.docker.compose.service=$service" }) `
            -ExitCode $LASTEXITCODE -ErrorCode 'CONTAINER_OWNERSHIP_INVALID')
        if ($ids.Count -eq 0) {
            if ($service -eq 'alertmanager-webhook') { continue }
            throw 'CONTAINER_OWNERSHIP_INVALID'
        }
        if ($ids.Count -ne 1) { throw 'CONTAINER_OWNERSHIP_INVALID' }
        $document = Get-ContainerDocument $ids[0]
        $config = Get-JsonMember $document 'Config'
        if (-not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Id') $ids[0]) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $config 'Image') $expected[$service].Reference) -or
            -not (Test-E2EOrdinalEqual (Get-JsonMember $document 'Image') $expected[$service].Id)) {
            throw 'CONTAINER_OWNERSHIP_INVALID'
        }
    }
}

function Invoke-E2EServiceChild {
    param([Parameter(Mandatory = $true)]$Receipt)

    $project = Get-E2EServiceProjectName -Receipt $Receipt
    Invoke-E2EInLocation -Path $RepositoryRoot -Body {
        Invoke-Native { & python -B $PythonVerifierPath all --repo-root $RepositoryRoot --project $project }
        Assert-Success 'Keycloak SERVICE verification'
    }
}

function Get-E2EPreparedReceipt {
    if ((Get-E2EReceiptState -PreparedPath $PreparedReceiptPath -RecoveryPath $RecoveryReceiptPath) -ne 'Prepared') {
        throw 'PREPARED_RECEIPT_REQUIRED'
    }
    return Read-E2EReceiptFile -Path $PreparedReceiptPath -RepositoryRoot $RepositoryRoot
}

function Get-E2ESingleCleanupReceipt {
    param(
        [string]$RepositoryRootPath = $RepositoryRoot,
        [string]$PreparedPath = $PreparedReceiptPath,
        [string]$RecoveryPath = $RecoveryReceiptPath
    )

    $state = Get-E2EReceiptState -PreparedPath $PreparedPath -RecoveryPath $RecoveryPath
    if ($state -eq 'Prepared') {
        return [pscustomobject]@{ Receipt = Read-E2EReceiptFile -Path $PreparedPath -RepositoryRoot $RepositoryRootPath; Path = $PreparedPath }
    }
    if ($state -eq 'Recovery') {
        return [pscustomobject]@{ Receipt = Read-E2EReceiptFile -Path $RecoveryPath -RepositoryRoot $RepositoryRootPath; Path = $RecoveryPath }
    }
    throw 'CLEANUP_RECEIPT_REQUIRED'
}

function Invoke-E2EPrepareMode {
    if ((Get-E2EReceiptState -PreparedPath $PreparedReceiptPath -RecoveryPath $RecoveryReceiptPath) -ne 'None') {
        throw 'RECEIPT_ALREADY_EXISTS'
    }
    $source = Get-E2ESourceIdentity
    $receipt = New-E2EReceipt -RunId ([guid]::NewGuid().ToString('N')) `
        -RepositoryId (Get-E2EReceiptValue $source 'repositoryId') `
        -CommitSha (Get-E2EReceiptValue $source 'commitSha') `
        -TreeSha (Get-E2EReceiptValue $source 'treeSha')
    $moduleBody = {
        param($activeReceipt)
        $boundaries = @{
            GetSource = {
                $identity = Get-E2ESourceIdentity
                New-E2EReceipt -RunId (Get-E2EReceiptValue $activeReceipt 'runId') `
                    -RepositoryId (Get-E2EReceiptValue $identity 'repositoryId') `
                    -CommitSha (Get-E2EReceiptValue $identity 'commitSha') `
                    -TreeSha (Get-E2EReceiptValue $identity 'treeSha')
            }
            CreateRecovery = { param($value) New-E2EReceiptFile -Path $RecoveryReceiptPath -Receipt $value -RepositoryRoot $RepositoryRoot }
            BuildImages = { param($value) Invoke-E2EPrepareBuild -Receipt $value }
            RenameRecoveryToPrepared = { Move-E2EReceiptFile -Source $RecoveryReceiptPath -Destination $PreparedReceiptPath -RepositoryRoot $RepositoryRoot }
            Cleanup = { param($value) Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $RecoveryReceiptPath }
        }
        Invoke-E2EPrepareLifecycle -Receipt $activeReceipt -Boundaries $boundaries
    }
    $body = {
        & $moduleBody $receipt
    }.GetNewClosure()
    Invoke-E2EOwnerEnvironmentScope -Receipt $receipt -Body $body
    Write-Output 'Keycloak E2E images prepared with an isolated ownership receipt.'
}

function Invoke-E2EServiceMode {
    $receipt = Get-E2EPreparedReceipt
    Assert-E2ESourceMatchesReceipt -Receipt $receipt
    $moduleBody = {
        param($activeReceipt)
        $boundaries = @{
            ReadPrepared = { return $activeReceipt }
            RenamePreparedToRecovery = { Move-E2EReceiptFile -Source $PreparedReceiptPath -Destination $RecoveryReceiptPath -RepositoryRoot $RepositoryRoot }
            AssertImages = { param($value) Assert-E2EOwnedImages -Receipt $value }
            AssertBrowserRuntime = { param($value) Assert-E2EPreparedBrowserRuntime -Receipt $value }
            RunChild = { param($value) Invoke-E2EServiceChild -Receipt $value }
            AssertContainers = { param($value) Assert-E2EContainerImages -Receipt $value -Project (Get-E2EServiceProjectName -Receipt $value) }
            CleanupResources = { Invoke-E2EProjectCleanup -Project (Get-E2EServiceProjectName -Receipt $activeReceipt) -Receipt $activeReceipt }
            RenameRecoveryToPrepared = { Move-E2EReceiptFile -Source $RecoveryReceiptPath -Destination $PreparedReceiptPath -RepositoryRoot $RepositoryRoot }
            Cleanup = { param($value) Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $RecoveryReceiptPath }
        }
        Invoke-E2EServiceLifecycle -Boundaries $boundaries
    }
    $body = {
        & $moduleBody $receipt
    }.GetNewClosure()
    Invoke-E2EOwnerEnvironmentScope -Receipt $receipt -Body $body
    Write-Output 'Keycloak SERVICE verification completed and the prepared receipt was restored.'
}

function Invoke-E2ERunMode {
    $receipt = Get-E2EPreparedReceipt
    Assert-E2ESourceMatchesReceipt -Receipt $receipt
    $moduleBody = {
        param($activeReceipt)
        $boundaries = @{
            ReadPrepared = { return $activeReceipt }
            RenamePreparedToRecovery = { Move-E2EReceiptFile -Source $PreparedReceiptPath -Destination $RecoveryReceiptPath -RepositoryRoot $RepositoryRoot }
            AssertImages = { param($value) Assert-E2EOwnedImages -Receipt $value | Out-Null }
            RunBrowser = { param($value) Invoke-E2EBrowserRunCore -Receipt $value }
            Cleanup = { param($value) Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $RecoveryReceiptPath }
        }
        Invoke-E2ERunLifecycle -Boundaries $boundaries
    }
    $body = {
        & $moduleBody $receipt
    }.GetNewClosure()
    Invoke-E2EOwnerEnvironmentScope -Receipt $receipt -Body $body
}

function Invoke-E2EValidateMode {
    $receipt = Get-E2EPreparedReceipt
    Assert-E2ESourceMatchesReceipt -Receipt $receipt
    $moduleBody = {
        param($activeReceipt)
        $values = @(Assert-E2EOwnedImages -Receipt $activeReceipt)
        Assert-E2EImageRecordSet -Values $values -Receipt $activeReceipt
        Assert-E2EPreparedBrowserRuntime -Receipt $activeReceipt | Out-Null
        $records = $values[0]
        $certificate = Assert-SafeCertificate $CertificatePath
        $primary = $null
        try { Assert-CertificateKeyPair $records.Browser.Id } catch { $primary = $_.Exception }
        $actions = @([pscustomobject]@{
            Action = { $certificate.Dispose() }.GetNewClosure()
            ErrorCode = 'CERTIFICATE_DISPOSE_FAILED'
            SkipAfterCleanupFailure = $false
        })
        Invoke-E2ECleanupActions -Primary $primary -Actions $actions
    }
    $body = {
        & $moduleBody $receipt
    }.GetNewClosure()
    Invoke-E2EOwnerEnvironmentScope -Receipt $receipt -Body $body
    Write-Output 'Prepared Keycloak E2E ownership and certificate validation completed.'
}

function New-E2EProductionCleanupModeBoundaries {
    param($CleanupContext)

    $settings = [pscustomobject]@{
        RepositoryRoot = $RepositoryRoot
        PreparedReceiptPath = $PreparedReceiptPath
        RecoveryReceiptPath = $RecoveryReceiptPath
        LeafBoundaries = $null
        RequireInjectedLeaves = $false
    }
    if ($null -ne $CleanupContext) {
        $settings = [pscustomobject]@{
            RepositoryRoot = [string]$CleanupContext.RepositoryRoot
            PreparedReceiptPath = [string]$CleanupContext.PreparedReceiptPath
            RecoveryReceiptPath = [string]$CleanupContext.RecoveryReceiptPath
            LeafBoundaries = $CleanupContext.LeafBoundaries
            RequireInjectedLeaves = $true
        }
    }
    return @{
        ReadSingleReceipt = {
            Get-E2ESingleCleanupReceipt -RepositoryRootPath $settings.RepositoryRoot `
                -PreparedPath $settings.PreparedReceiptPath -RecoveryPath $settings.RecoveryReceiptPath
        }.GetNewClosure()
        FullCleanup = {
            param($state)
            $activeRoot = [string]$settings.RepositoryRoot
            $activeLeaves = $settings.LeafBoundaries
            $requireInjectedLeaves = [bool]$settings.RequireInjectedLeaves
            $body = {
                Invoke-E2EFullCleanup -Receipt $state.Receipt -ReceiptPath $state.Path `
                    -RepositoryRootPath $activeRoot -LeafBoundaries $activeLeaves `
                    -RequireLeafBoundaries:$requireInjectedLeaves
            }.GetNewClosure()
            Invoke-E2EOwnerEnvironmentScope -Receipt $state.Receipt -Body $body
        }.GetNewClosure()
    }
}

function Invoke-E2ECleanupMode {
    param($Boundaries, $CleanupContext)

    if ($null -eq $Boundaries) {
        $Boundaries = New-E2EProductionCleanupModeBoundaries -CleanupContext $CleanupContext
    }
    Invoke-E2ECleanupLifecycle -Boundaries $Boundaries
    Write-Output 'Owned Keycloak E2E resources, images, and receipt were cleaned.'
}

function Invoke-KeycloakE2E {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Prepare', 'Service', 'Run', 'Validate', 'Cleanup')][string]$Mode,
        $CleanupContext,
        $LifecycleBoundaries
    )

    if ($null -eq $LifecycleBoundaries) {
        $LifecycleBoundaries = @{
            EnterLock = { Enter-E2ELifecycleLock }
            ReleaseLock = { param($value) $value.ReleaseMutex() }
            DisposeLock = { param($value) $value.Dispose() }
        }
    }
    $lock = & $LifecycleBoundaries.EnterLock
    $primary = $null
    try {
        switch ($Mode) {
            'Prepare' { Invoke-E2EPrepareMode; break }
            'Service' { Invoke-E2EServiceMode; break }
            'Run' { Invoke-E2ERunMode; break }
            'Validate' { Invoke-E2EValidateMode; break }
            'Cleanup' { Invoke-E2ECleanupMode -CleanupContext $CleanupContext; break }
        }
    }
    catch {
        $primary = $_.Exception
    }
    $exitBoundaries = @{
        Release = $LifecycleBoundaries.ReleaseLock
        Dispose = $LifecycleBoundaries.DisposeLock
    }
    Exit-E2ELifecycleLock -Lock $lock -Primary $primary -Boundaries $exitBoundaries
}

Export-ModuleMember -Function @(
    'New-E2EReceipt',
    'ConvertTo-E2EReceiptBytes',
    'ConvertFrom-E2EReceiptBytes',
    'Assert-E2EPathSafe',
    'Get-E2EImageSet',
    'Get-E2EOwnershipLabels',
    'New-E2EComposeArguments',
    'New-E2EDockerBuildArguments',
    'New-E2EImageRemoveArguments',
    'Select-E2EFailure',
    'Write-E2ESafeCleanupDiagnostic',
    'Invoke-E2ECleanupActions',
    'Invoke-E2ERunCoreCleanup',
    'Invoke-E2EPrepareBrowserBuildLifecycle',
    'Get-E2EReceiptState',
    'Enter-E2ELifecycleLock',
    'Exit-E2ELifecycleLock',
    'New-E2EReceiptFile',
    'Move-E2EReceiptFile',
    'Remove-E2EReceiptFile',
    'Invoke-E2EOwnerEnvironmentScope',
    'Test-E2ECleanupTarget',
    'Invoke-E2EPrepareLifecycle',
    'Invoke-E2EServiceLifecycle',
    'Invoke-E2ERunLifecycle',
    'Invoke-E2ECleanupLifecycle',
    'Get-E2ESingleCleanupReceipt',
    'Invoke-E2EFullCleanup',
    'New-E2EProductionCleanupModeBoundaries',
    'Invoke-E2ECleanupMode',
    'Invoke-KeycloakE2E'
)
