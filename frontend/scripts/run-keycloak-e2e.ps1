[CmdletBinding()]
param(
    [ValidateSet('Run', 'Prepare', 'Validate', 'Cleanup')]
    [string]$Mode = 'Run'
)

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
# Preparation and running are two separate modes on purpose.
#
# `-Mode Prepare` is the one place allowed to reach a registry or a package
# archive: it pulls every pinned Compose image, builds the two images this
# repository builds, and builds the browser image that carries `certutil`.
# `-Mode Run` is the official E2E and does none of that. It first proves that
# every image it needs already exists locally and that the browser image really
# is the image this checkout prepared, then starts everything with
# `--no-build --pull never`. A missing or mismatched image is a fixed error,
# never a pull, and `-Mode Run` never falls back to preparing anything.
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
$BrowserImage = 'finguardops-playwright-e2e:local'
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
$OutputDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("finguardops-playwright-{0}" -f [guid]::NewGuid().ToString('N'))
$ComposeArguments = @(
    'compose',
    '-p', $ProjectName,
    '--env-file', 'infra/.env.example',
    '-f', 'infra/compose.yml',
    '-f', 'infra/compose.keycloak-local-e2e.yml'
)

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
function Invoke-NativeStdout([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $Command | Out-String)
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

# The same, for a command run for its exit code and its console output rather
# than for a value. Docker and Compose write their progress to stderr, so a
# caller that captures this script's output would otherwise turn a build's
# ordinary progress lines into a terminating error.
function Invoke-Native([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command
    }
    finally {
        $ErrorActionPreference = $previous
    }
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
        try {
            if ($rsa.KeySize -lt 3072) {
                throw 'The certificate RSA key is too small.'
            }
        }
        finally {
            $rsa.Dispose()
        }

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
        $certificate.Dispose()
        throw
    }
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
    Push-Location $RepositoryRoot
    try {
        $merged = Invoke-NativeStdout { & docker @ComposeArguments config --format json }
        Assert-Success 'Dedicated Compose configuration read'
    }
    finally {
        Pop-Location
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
    if ($LASTEXITCODE -ne 0 -or $identifier -notmatch '^sha256:[0-9a-f]{64}$') {
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
        if ($layer -isnot [string] -or $layer -notmatch '^sha256:[0-9a-f]{64}$') {
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
    if ($identifier -isnot [string] -or $identifier -notmatch '^sha256:[0-9a-f]{64}$') {
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
    if ($observed.Count -ne $Expected.Count) {
        throw $Message
    }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($observed[$index] -isnot [string] -or
            -not [string]::Equals($observed[$index], $Expected[$index], [System.StringComparison]::Ordinal)) {
            throw $Message
        }
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

# Windows path equality, on the physical path.
#
# Both sides have already been resolved to a link-free location under this
# repository, so this is a comparison of one full path against another: a path
# that merely shares a prefix, a junction that happens to lead to the same
# directory, or a path on a different drive is a different string and is
# rejected. The comparison ignores case because that is what "the same path"
# means on this platform, and nothing else about it is relaxed.
function Test-SamePhysicalPath($Observed, [string]$Expected) {
    if ($Observed -isnot [string] -or [string]::IsNullOrWhiteSpace($Observed)) {
        return $false
    }
    $normalized = $null
    try {
        $normalized = Get-TrimmedPath ([System.IO.Path]::GetFullPath($Observed))
    }
    catch {
        return $false
    }
    return [string]::Equals($normalized, $Expected, [System.StringComparison]::OrdinalIgnoreCase)
}

# `HostConfig.Binds`, as the daemon recorded them, against the exact bind list
# approved for this container.
#
# Each entry is split on the two separators a Windows bind actually has, so the
# host path, the container path and the mode are three compared values rather
# than one string in which a difference could hide. An entry that is not shaped
# like an approved bind at all is rejected without being parsed further.
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
        if ($entry -isnot [string] -or
            $entry -notmatch '^(?<source>[A-Za-z]:\\[^:]*):(?<destination>/[^:]+):(?<mode>[a-z,]+)$') {
            throw $Message
        }
        $source = $Matches['source']
        $destination = $Matches['destination']
        $mode = $Matches['mode']
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
        if (-not (Test-SamePhysicalPath $source $approved.Source)) {
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
        if ($destination -isnot [string]) {
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
        if (Test-JsonFlag (Get-JsonMember $mount 'RW')) {
            throw $Message
        }
        if (-not [string]::Equals((Get-JsonMember $mount 'Propagation'), 'rprivate', [System.StringComparison]::Ordinal)) {
            throw $Message
        }
        if (-not (Test-SamePhysicalPath (Get-JsonMember $mount 'Source') $approved.Source)) {
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
function Get-ContainerDocument([string]$ContainerId) {
    $encoded = Invoke-NativeStdout { & docker container inspect --format '{{json .}}' $ContainerId }
    if ($LASTEXITCODE -ne 0) {
        throw 'A container this run created could not be inspected.'
    }
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
    if ($LASTEXITCODE -ne 0 -or $created -notmatch '^[0-9a-f]{64}$') {
        throw 'A container for this run could not be created.'
    }
    return $created
}

# Removes exactly one container this run created, by the identifier it was
# created under, and proves it is gone. `--volumes` takes any anonymous volume
# that container itself owns with it; nothing outside this run is named, matched
# or pruned.
function Remove-OwnedContainer([string]$ContainerId) {
    if ($ContainerId -notmatch '^[0-9a-f]{64}$') {
        throw 'A container removal was asked for an identifier this run did not create.'
    }
    Invoke-Native { & docker rm --force --volumes $ContainerId | Out-Null }
    $remaining = @(Invoke-NativeStdout {
            & docker ps -a --no-trunc --filter "id=$ContainerId" --format '{{.ID}}'
        } -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    if ($remaining.Count -ne 0) {
        throw 'A container this run created could not be removed.'
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
    try {
        Assert-ContainerConfinement $container $ImageId $Plan.Expectation
        Invoke-Native { & docker start --attach $container }
        Assert-Success $Operation
        Assert-ContainerCompletion $container $ImageId
    }
    finally {
        Remove-OwnedContainer $container
    }
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
        'Prepared browser image runtime verification'
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

# Removes a browser container left behind by an earlier run, by name. Used by
# cleanup mode; the run itself owns its container by identifier and never
# removes anything it did not create.
function Remove-BrowserContainer {
    $existing = @(& docker ps -a --filter "name=^/$BrowserContainerName$" --format '{{.ID}}')
    if ($LASTEXITCODE -eq 0 -and $existing.Count -ne 0) {
        & docker rm -f $BrowserContainerName | Out-Null
    }
    $remaining = @(& docker ps -a --filter "name=^/$BrowserContainerName$" --format '{{.ID}}')
    Assert-Success 'Dedicated browser container cleanup check'
    if ($remaining.Count -ne 0) {
        throw 'The dedicated browser container could not be removed.'
    }
}

# The browser container is the one container in this run that is reachable from
# the host, so its mounts, its network and the single loopback port it publishes
# are approved the same way and for the same reason: before it starts, from the
# daemon's record, by exact comparison.
function Get-BrowserServerPlan([string]$BrowserImageId, [string]$PlaywrightVersion) {
    $binds = @(
        (New-ApprovedBind $CertificatePath '/finguardops/tls/localhost.crt'),
        (New-ApprovedBind $ScriptsPath '/finguardops/scripts' -Directory),
        (New-ApprovedBind $PlaywrightCorePath '/finguardops/playwright-core' -Directory)
    )
    $publication = [ordered]@{ HostIp = '127.0.0.1'; HostPort = "$BrowserHostPort" }
    return [ordered]@{
        Expectation = New-ContainerExpectation `
            -NetworkMode 'bridge' `
            -ReadOnlyRootFilesystem $false `
            -Binds $binds `
            -Init $true `
            -ExtraHosts @('host.docker.internal:host-gateway') `
            -PortBindings ([ordered]@{ "$BrowserContainerPort/tcp" = @($publication) })
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
function Wait-BrowserServer([string]$ContainerId) {
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
            $client = [System.Net.Sockets.TcpClient]::new()
            try {
                $connect = $client.BeginConnect('127.0.0.1', $BrowserHostPort, $null, $null)
                if ($connect.AsyncWaitHandle.WaitOne(1000) -and $client.Connected) {
                    $client.EndConnect($connect)
                    return
                }
            }
            catch {
                # Published path not usable yet; the deadline owns the failure.
            }
            finally {
                $client.Close()
            }
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Output (Get-BrowserLog $ContainerId)
    throw 'The dedicated browser server did not become ready.'
}

function Invoke-ComposeDown {
    Push-Location $RepositoryRoot
    try {
        Invoke-Native { & docker @ComposeArguments down --volumes --remove-orphans }
        Assert-Success 'Dedicated Compose cleanup'
    }
    finally {
        Pop-Location
    }
}

# The only mode allowed to reach a registry or a package archive.
#
# Deliberately not something `-Mode Run` falls back to. A run that quietly
# prepared whatever it was missing would be a run whose network behaviour
# depends on the state of the machine, which is exactly what this separation
# removes: preparation is an explicit, separately reviewable step, and the
# official run is the part that provably fetches nothing.
function Invoke-Prepare {
    $playwrightVersion = Get-PlaywrightVersion

    Push-Location $RepositoryRoot
    try {
        Invoke-Native { & docker @ComposeArguments pull --ignore-buildable }
        Assert-Success 'Dedicated Compose image pull'
        Invoke-Native { & docker @ComposeArguments build }
        Assert-Success 'Dedicated Compose image build'
    }
    finally {
        Pop-Location
    }

    Invoke-Native { & docker pull $BrowserBaseImage }
    Assert-Success 'Pinned Playwright base image pull'

    # An empty build context. The Dockerfile copies nothing, so sending the
    # frontend directory would only ship node_modules to the daemon.
    $buildContext = Join-Path ([System.IO.Path]::GetTempPath()) ("finguardops-browser-build-{0}" -f [guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($buildContext) | Out-Null
    try {
        Invoke-Native {
            & docker build `
                --file $BrowserDockerfile `
                --tag $BrowserImage `
                --build-arg "PLAYWRIGHT_VERSION=$playwrightVersion" `
                --build-arg "LIBNSS3_TOOLS_VERSION=$LibNss3ToolsVersion" `
                $buildContext
        }
        Assert-Success 'Prepared browser image build'
    }
    finally {
        [System.IO.Directory]::Delete($buildContext, $true)
    }

    $identifier = Assert-BrowserImage $playwrightVersion
    Assert-BrowserRuntime $identifier $playwrightVersion
    Assert-ComposeImagesPresent
    Write-Output "Prepared browser image $BrowserImage ($identifier)."
    Write-Output 'Preparation completed. The E2E itself runs with -Mode Run and pulls and builds nothing.'
}

if ($Mode -eq 'Cleanup') {
    try {
        Remove-BrowserContainer
    }
    finally {
        Invoke-ComposeDown
    }
    Write-Output 'Dedicated Keycloak browser E2E cleanup completed.'
    exit 0
}

if ($Mode -eq 'Prepare') {
    Invoke-Prepare
    exit 0
}

if ($Mode -eq 'Validate') {
    $validatedCertificate = Assert-SafeCertificate $CertificatePath
    $validatedCertificate.Dispose()
    $validatedPlaywrightVersion = Get-PlaywrightVersion
    $validatedBrowserImageId = Assert-BrowserImage $validatedPlaywrightVersion
    Assert-BrowserRuntime $validatedBrowserImageId $validatedPlaywrightVersion
    Assert-CertificateKeyPair $validatedBrowserImageId
    Write-Output 'Localhost certificate validation completed without changing any trust store.'
    exit 0
}

$certificate = $null
$composeStarted = $false
# The identifier of the one browser container this run creates, and the only
# container the cleanup below is allowed to remove.
$browserContainer = $null
$previousOutput = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', 'Process')
$previousProject = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', 'Process')
$previousBrowser = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_BROWSER_WS', 'Process')

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

    Push-Location $RepositoryRoot
    try {
        $composeStarted = $true
        # Every image was proven present above, so there is nothing left for
        # Compose to fetch or build. Were one missing after all, Compose fails
        # here rather than asking a registry for metadata or authentication.
        Invoke-Native { & docker @ComposeArguments up -d --no-build --pull never keycloak-verify }
        Assert-Success 'Dedicated Compose startup'
        Invoke-Native { & docker @ComposeArguments wait keycloak-verify }
        Assert-Success 'Keycloak bootstrap and verifier'
    }
    finally {
        Pop-Location
    }

    # Created stopped, approved against the exact configuration this file
    # names, and only then started - by the identifier that was approved.
    $browserPlan = Get-BrowserServerPlan $browserImageId $playwrightVersion
    $browserContainer = New-CreatedContainer $browserPlan.Arguments
    Start-BrowserContainer $browserContainer $browserImageId $browserPlan.Expectation
    Wait-BrowserServer $browserContainer

    Push-Location $FrontendRoot
    try {
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
    finally {
        Pop-Location
    }

    Write-Output 'Keycloak browser E2E completed.'
}
finally {
    [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', $previousOutput, 'Process')
    [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', $previousProject, 'Process')
    [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_BROWSER_WS', $previousBrowser, 'Process')
    try {
        if ($null -ne $browserContainer) {
            # Takes the per-run NSS database, the browser profile and the
            # artifacts directory with it: all of them live only inside here.
            # Named by the identifier this run created, so nothing else can be
            # removed even if the name were moved onto another container.
            Remove-OwnedContainer $browserContainer
        }
    }
    finally {
        try {
            if ($composeStarted) {
                Invoke-ComposeDown
            }
        }
        finally {
            if ([System.IO.Directory]::Exists($OutputDirectory)) {
                [System.IO.Directory]::Delete($OutputDirectory, $true)
            }
            if ($null -ne $certificate) {
                $certificate.Dispose()
            }
        }
    }
}
