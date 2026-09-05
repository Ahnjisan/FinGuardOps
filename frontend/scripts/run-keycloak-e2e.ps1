[CmdletBinding()]
param(
    [ValidateSet('Run', 'Validate', 'Cleanup')]
    [string]$Mode = 'Run'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ProjectName = 'finguardops-keycloak-browser-e2e'
$FrontendRoot = Split-Path -Parent $PSScriptRoot
$RepositoryRoot = Split-Path -Parent $FrontendRoot
$CertificatePath = Join-Path $RepositoryRoot 'infra/keycloak/.local/tls/localhost.crt'
$MarkerDirectory = Join-Path ([System.IO.Path]::GetTempPath()) 'FinGuardOps'
$MarkerPath = Join-Path $MarkerDirectory 'keycloak-browser-e2e-certificate.json'
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

function Get-HexSha256([byte[]]$Value) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return -join @($sha256.ComputeHash($Value) | ForEach-Object { $_.ToString('X2') })
    }
    finally {
        $sha256.Dispose()
    }
}

function Test-PathEntry([string]$Path) {
    return $null -ne (Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue)
}

function Assert-PhysicalPathChain(
    [string]$Path,
    [string]$AllowedRoot,
    [bool]$LeafMustBeFile
) {
    $root = [System.IO.Path]::GetFullPath($AllowedRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $candidate = [System.IO.Path]::GetFullPath($Path)
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.Equals($root, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'A protected path escaped its approved root.'
    }

    $current = Get-Item -LiteralPath $candidate -Force -ErrorAction Stop
    $isLeaf = $true
    while ($true) {
        if (($current.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'A protected path contains a link, junction or reparse point.'
        }
        if ($isLeaf -and $LeafMustBeFile -and $current.PSIsContainer) {
            throw 'The protected file path is not a regular file.'
        }
        if ((-not $isLeaf -or -not $LeafMustBeFile) -and -not $current.PSIsContainer) {
            throw 'A protected parent path is not a directory.'
        }
        if ($current.FullName.TrimEnd(
                [System.IO.Path]::DirectorySeparatorChar,
                [System.IO.Path]::AltDirectorySeparatorChar
            ).Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $parent = Split-Path -Parent $current.FullName
        if ([string]::IsNullOrEmpty($parent)) {
            throw 'A protected path escaped its approved root.'
        }
        $current = Get-Item -LiteralPath $parent -Force -ErrorAction Stop
        $isLeaf = $false
    }
}

function Assert-OwnedPhysicalFile([string]$Path) {
    $repository = [System.IO.Path]::GetFullPath($RepositoryRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $candidate = [System.IO.Path]::GetFullPath($Path)
    $prefix = $repository + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'The certificate path escaped the repository.'
    }
    if (-not [System.IO.File]::Exists($candidate)) {
        throw 'The localhost certificate is missing.'
    }

    $current = Get-Item -LiteralPath $candidate -Force
    while ($true) {
        if (($current.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'The certificate path contains a link or reparse point.'
        }
        if ($current.FullName.Equals($repository, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $parent = Split-Path -Parent $current.FullName
        if ([string]::IsNullOrEmpty($parent)) {
            throw 'The certificate path escaped the repository.'
        }
        $current = Get-Item -LiteralPath $parent -Force
    }
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

function Assert-SafeCertificate([string]$Path) {
    Assert-OwnedPhysicalFile $Path
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        [System.IO.File]::ReadAllBytes($Path)
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

function Open-RootStore([bool]$Writable) {
    $store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
        [System.Security.Cryptography.X509Certificates.StoreName]::Root,
        [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
    )
    $flags = if ($Writable) {
        [System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite
    }
    else {
        [System.Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly
    }
    $store.Open($flags)
    return $store
}

function Find-ExactCertificates(
    [System.Security.Cryptography.X509Certificates.X509Store]$Store,
    [byte[]]$RawData
) {
    return @($Store.Certificates | Where-Object { Test-ByteEquality $_.RawData $RawData })
}

function Assert-ExactStoreState(
    [System.Security.Cryptography.X509Certificates.X509Store]$Store,
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
    [int]$ExpectedCount
) {
    $thumbprintMatches = @($Store.Certificates | Where-Object {
        $_.Thumbprint.Equals($Certificate.Thumbprint, [System.StringComparison]::Ordinal)
    })
    $exactMatches = @(Find-ExactCertificates $Store $Certificate.RawData)
    if ($thumbprintMatches.Count -ne $exactMatches.Count) {
        throw 'The certificate thumbprint does not identify only the exact repository DER.'
    }
    if ($ExpectedCount -eq -1 -and $exactMatches.Count -gt 1) {
        throw 'The exact localhost certificate appears more than once.'
    }
    if ($ExpectedCount -ne -1 -and $exactMatches.Count -ne $ExpectedCount) {
        throw 'The exact localhost certificate store state is invalid.'
    }
    return $exactMatches
}

function Assert-MarkerParent {
    $temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $expectedDirectory = Join-Path $temporaryRoot 'FinGuardOps'
    $expectedMarker = Join-Path $expectedDirectory 'keycloak-browser-e2e-certificate.json'
    if (-not [System.IO.Path]::GetFullPath($MarkerDirectory).Equals(
            [System.IO.Path]::GetFullPath($expectedDirectory),
            [System.StringComparison]::OrdinalIgnoreCase
        ) -or
        -not [System.IO.Path]::GetFullPath($MarkerPath).Equals(
            [System.IO.Path]::GetFullPath($expectedMarker),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'The certificate cleanup marker path is not approved.'
    }
    Assert-PhysicalPathChain $MarkerDirectory $temporaryRoot $false
}

function Assert-MarkerFile {
    Assert-MarkerParent
    Assert-PhysicalPathChain $MarkerPath $MarkerDirectory $true
}

function Read-CleanupMarker {
    Assert-MarkerFile
    $bytes = [System.IO.File]::ReadAllBytes($MarkerPath)
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 1024) {
        throw 'The certificate cleanup marker size is invalid.'
    }
    $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
    $json = $utf8.GetString($bytes)
    $pattern = '^\{"version":2,"project":"finguardops-keycloak-browser-e2e","addedByRun":true,"runId":"[0-9a-f]{32}","thumbprint":"[0-9A-F]{40}","derSha256":"[0-9A-F]{64}"\}$'
    if ($json -cnotmatch $pattern) {
        throw 'The certificate cleanup marker format is invalid.'
    }
    try {
        $marker = $json | ConvertFrom-Json
    }
    catch {
        throw 'The certificate cleanup marker format is invalid.'
    }
    return [pscustomobject]@{
        Value = $marker
        Bytes = $bytes
    }
}

function Write-CleanupMarker(
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
    [string]$RunId
) {
    if ($RunId -cnotmatch '^[0-9a-f]{32}$') {
        throw 'The certificate cleanup ownership identifier is invalid.'
    }
    if (-not (Test-PathEntry $MarkerDirectory)) {
        [System.IO.Directory]::CreateDirectory($MarkerDirectory) | Out-Null
    }
    Assert-MarkerParent
    if (Test-PathEntry $MarkerPath) {
        throw 'A certificate cleanup marker already exists.'
    }
    $marker = [ordered]@{
        version = 2
        project = $ProjectName
        addedByRun = $true
        runId = $RunId
        thumbprint = $Certificate.Thumbprint.ToUpperInvariant()
        derSha256 = Get-HexSha256 $Certificate.RawData
    }
    $json = $marker | ConvertTo-Json -Compress
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($json)
    $stream = [System.IO.FileStream]::new(
        $MarkerPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None,
        4096,
        [System.IO.FileOptions]::WriteThrough
    )
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose()
    }
    $written = Read-CleanupMarker
    if (-not (Test-ByteEquality $written.Bytes $bytes)) {
        throw 'The certificate cleanup marker changed while it was written.'
    }
}

function Remove-MarkedCertificate([string]$ExpectedRunId = '') {
    if (-not (Test-PathEntry $MarkerPath)) {
        return
    }
    $readMarker = Read-CleanupMarker
    $marker = $readMarker.Value
    if (-not [string]::IsNullOrEmpty($ExpectedRunId) -and
        -not $marker.runId.Equals($ExpectedRunId, [System.StringComparison]::Ordinal)) {
        throw 'The certificate cleanup marker belongs to a different run.'
    }

    $certificate = Assert-SafeCertificate $CertificatePath
    try {
        if (-not $certificate.Thumbprint.ToUpperInvariant().Equals(
                $marker.thumbprint,
                [System.StringComparison]::Ordinal
            ) -or
            -not (Get-HexSha256 $certificate.RawData).Equals(
                $marker.derSha256,
                [System.StringComparison]::Ordinal
            )) {
            throw 'The repository certificate no longer matches the cleanup marker.'
        }

        $store = Open-RootStore $true
        try {
            $matches = @(Assert-ExactStoreState $store $certificate -1)
            if ($matches.Count -eq 1) {
                $store.Remove($matches[0])
            }
            Assert-ExactStoreState $store $certificate 0 | Out-Null
        }
        finally {
            $store.Dispose()
        }
    }
    finally {
        $certificate.Dispose()
    }

    $confirmedMarker = Read-CleanupMarker
    if (-not (Test-ByteEquality $confirmedMarker.Bytes $readMarker.Bytes)) {
        throw 'The certificate cleanup marker changed during cleanup.'
    }
    [System.IO.File]::Delete($MarkerPath)
    if (Test-PathEntry $MarkerPath) {
        throw 'The certificate cleanup marker could not be removed.'
    }
}

function Invoke-ComposeDown {
    Push-Location $RepositoryRoot
    try {
        & docker @ComposeArguments down --volumes --remove-orphans
        Assert-Success 'Dedicated Compose cleanup'
    }
    finally {
        Pop-Location
    }
}

if ($Mode -eq 'Cleanup') {
    try {
        Remove-MarkedCertificate
    }
    finally {
        Invoke-ComposeDown
    }
    Write-Output 'Dedicated Keycloak browser E2E cleanup completed.'
    exit 0
}

if ($Mode -eq 'Validate') {
    $validatedCertificate = Assert-SafeCertificate $CertificatePath
    $validatedCertificate.Dispose()
    Write-Output 'Localhost certificate validation completed without changing trust.'
    exit 0
}

if (Test-PathEntry $MarkerPath) {
    throw 'A prior certificate cleanup marker exists. Run this script with -Mode Cleanup first.'
}

$certificate = $null
$certificateAdded = $false
$runId = [guid]::NewGuid().ToString('N')
$composeStarted = $false
$previousOutput = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', 'Process')
$previousProject = [System.Environment]::GetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', 'Process')

try {
    $certificate = Assert-SafeCertificate $CertificatePath

    $store = Open-RootStore $true
    try {
        $existing = @(Assert-ExactStoreState $store $certificate -1)
        if ($existing.Count -eq 0) {
            Write-CleanupMarker $certificate $runId
            $certificateAdded = $true
            Import-Certificate `
                -FilePath $CertificatePath `
                -CertStoreLocation 'Cert:\CurrentUser\Root' `
                -Confirm:$false `
                -ErrorAction Stop | Out-Null
        }
        Assert-ExactStoreState $store $certificate 1 | Out-Null
    }
    finally {
        $store.Dispose()
    }

    $existingContainers = @(& docker ps -a --filter "label=com.docker.compose.project=$ProjectName" --format '{{.ID}}')
    Assert-Success 'Dedicated Compose ownership check'
    $existingVolumes = @(& docker volume ls --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Name}}')
    Assert-Success 'Dedicated Compose volume ownership check'
    $existingNetworks = @(& docker network ls --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Name}}')
    Assert-Success 'Dedicated Compose network ownership check'
    if ($existingContainers.Count -ne 0 -or $existingVolumes.Count -ne 0 -or $existingNetworks.Count -ne 0) {
        throw 'The dedicated Compose project already has resources. Run cleanup mode first.'
    }

    [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_OUTPUT_DIR', $OutputDirectory, 'Process')
    [System.Environment]::SetEnvironmentVariable('FINGUARDOPS_E2E_COMPOSE_PROJECT', $ProjectName, 'Process')

    Push-Location $RepositoryRoot
    try {
        $composeStarted = $true
        & docker @ComposeArguments up -d --build keycloak-verify
        Assert-Success 'Dedicated Compose startup'
        & docker @ComposeArguments wait keycloak-verify
        Assert-Success 'Keycloak bootstrap and verifier'
    }
    finally {
        Pop-Location
    }

    Push-Location $FrontendRoot
    try {
        & npm run e2e:keycloak
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
    try {
        if ($composeStarted) {
            Invoke-ComposeDown
        }
    }
    finally {
        try {
            if ($certificateAdded) {
                Remove-MarkedCertificate $runId
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
