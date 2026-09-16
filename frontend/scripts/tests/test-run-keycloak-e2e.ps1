[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'MajorFixPreflight', 'MajorFixFixture11', 'MajorFixTargeted', 'OwnerFixPreflight', 'OwnerFixTargeted', 'WaitBrowserPreflight', 'WaitBrowserTargeted', 'SessionStateTargeted', 'Formal')]
    [string]$Mode = 'Formal'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ModulePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\keycloak-e2e-lib.psm1'))

function Assert-Parsed([string]$Path) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) {
        throw "HARNESS_PARSE_FAILED: $Path"
    }
}

function Invoke-FixtureSelfTest {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-e2e-harness-' + [guid]::NewGuid().ToString('N'))
    $child = Join-Path $root 'child.ps1'
    $marker = Join-Path $root 'marker.txt'
    [System.IO.Directory]::CreateDirectory($root) | Out-Null
    try {
        $source = @'
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Marker)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[System.IO.File]::WriteAllText($Marker, 'fixture-ok', [System.Text.UTF8Encoding]::new($false))
'@
        [System.IO.File]::WriteAllText($child, ($source -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
        Assert-Parsed $child
        & powershell.exe -NoProfile -NonInteractive -File $child -Marker $marker
        if ($LASTEXITCODE -ne 0 -or -not [System.IO.File]::Exists($marker)) {
            throw 'HARNESS_CHILD_FAILED'
        }
        if ([System.IO.File]::ReadAllText($marker, [System.Text.Encoding]::UTF8) -ne 'fixture-ok') {
            throw 'HARNESS_CHILD_OUTPUT_INVALID'
        }
    }
    finally {
        if ([System.IO.Directory]::Exists($root)) {
            [System.IO.Directory]::Delete($root, $true)
        }
    }
    if ([System.IO.Directory]::Exists($root)) {
        throw 'HARNESS_TEMP_CLEANUP_FAILED'
    }
}

function New-TestReceipt {
    return [ordered]@{
        schemaVersion = [int]1
        runId = '0123456789abcdef0123456789abcdef'
        repositoryId = ('a' * 64)
        commitSha = ('b' * 40)
        treeSha = ('c' * 40)
    }
}

function Invoke-TestCase([string]$Name, [scriptblock]$Body) {
    try {
        & $Body
        Write-Output "PASS $Name"
    }
    catch {
        $script:Failures.Add("$Name :: $($_.Exception.Message)")
        Write-Output "FAIL $Name :: $($_.Exception.Message)"
    }
}

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -is [array] -or $Actual -is [array]) {
        $expectedJson = ConvertTo-Json @($Expected) -Compress
        $actualJson = ConvertTo-Json @($Actual) -Compress
        if (-not [string]::Equals($expectedJson, $actualJson, [System.StringComparison]::Ordinal)) {
            throw "$Message expected=$expectedJson actual=$actualJson"
        }
        return
    }
    if (-not [object]::Equals($Expected, $Actual)) {
        throw "$Message expected=$Expected actual=$Actual"
    }
}

function Assert-Throws([scriptblock]$Body, [string]$Pattern, [string]$Message) {
    try {
        & $Body
    }
    catch {
        if ($_.Exception.Message -match $Pattern) {
            return
        }
        throw "$Message wrong-error=$($_.Exception.Message)"
    }
    throw "$Message no-error"
}

function Get-CapturedException([scriptblock]$Body) {
    try {
        & $Body | Out-Null
    }
    catch {
        return $_.Exception
    }
    return $null
}

function Assert-NoRawCleanupDetail($Error, [string]$Message) {
    if ($null -ne $Error -and $Error.Message -match 'NeverReflect|credential|stderr|C:\\sensitive') {
        throw $Message
    }
}

function New-FakeBrowserClient {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][ValidateSet('Success', 'ExpectedFailure', 'UnexpectedFailure')][string]$ConnectBehavior,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Events,
        $Primary,
        [switch]$CleanupFailure
    )

    $waitHandle = [pscustomobject]@{}
    $waitHandle | Add-Member -MemberType ScriptMethod -Name WaitOne -Value { param([int]$Milliseconds) return $true }
    $asyncResult = [pscustomobject]@{ AsyncWaitHandle = $waitHandle }
    $client = [pscustomobject]@{
        Id = $Id
        Connected = ($ConnectBehavior -eq 'Success')
        ConnectBehavior = $ConnectBehavior
        Events = $Events
        Primary = $Primary
        CleanupFailure = [bool]$CleanupFailure
        AsyncResult = $asyncResult
    }
    $client | Add-Member -MemberType ScriptMethod -Name BeginConnect -Value {
        param([string]$HostName, [int]$Port, $RequestCallback, $State)
        if ($this.ConnectBehavior -eq 'ExpectedFailure') {
            $this.Events.Add("connect-fail:$($this.Id)")
            throw [System.Net.Sockets.SocketException]::new(10061)
        }
        if ($this.ConnectBehavior -eq 'UnexpectedFailure') {
            $this.Events.Add("connect-primary:$($this.Id)")
            throw $this.Primary
        }
        $this.Events.Add("connect:$($this.Id)")
        return $this.AsyncResult
    }
    $client | Add-Member -MemberType ScriptMethod -Name EndConnect -Value { param($AsyncResult) }
    $client | Add-Member -MemberType ScriptMethod -Name Close -Value {
        if ($this.CleanupFailure) {
            $this.Events.Add("cleanup-fail:$($this.Id)")
            throw 'NeverReflect 127.0.0.1:14250 internal client cleanup detail'
        }
        $this.Events.Add("cleanup:$($this.Id)")
    }
    return $client
}

function Invoke-WaitBrowserFixture {
    param(
        [Parameter(Mandatory = $true)][object[]]$Clients,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Events
    )

    $factoryState = [pscustomobject]@{ Index = 0 }
    $factory = {
        if ($factoryState.Index -ge $Clients.Count) {
            throw 'FAKE_CLIENT_FACTORY_EXHAUSTED'
        }
        $client = $Clients[$factoryState.Index]
        $factoryState.Index++
        $Events.Add("create:$($client.Id)")
        return $client
    }.GetNewClosure()

    & $script:E2EModule {
        param($InjectedFactory, $FixtureEvents)
        function docker {
            Set-Variable -Name LASTEXITCODE -Scope 1 -Value 0
            return 'true'
        }
        function Get-BrowserLog([string]$ContainerId) { return 'Listening on ws://' }
        function Get-Date { return [datetime]'2026-09-16T00:00:00Z' }
        function Start-Sleep { param([int]$Milliseconds) $FixtureEvents.Add('retry') }
        Wait-BrowserServer -ContainerId 'fake-browser-container' -ClientFactory $InjectedFactory
    } $factory $Events
}

function Invoke-WaitBrowserPreflight {
    Assert-Parsed $ModulePath
    Assert-Parsed $PSCommandPath
    Assert-True ($null -ne $script:E2EModule) 'Module import was not available to the wait-browser harness.'
    $events = [System.Collections.Generic.List[string]]::new()
    $client = New-FakeBrowserClient -Id 'preflight' -ConnectBehavior Success -Events $events
    $asyncResult = $client.BeginConnect('fixture.invalid', 1, $null, $null)
    Assert-True $asyncResult.AsyncWaitHandle.WaitOne(1) 'Fake client wait handle did not report completion.'
    $client.EndConnect($asyncResult)
    $client.Close()
    Assert-Equal @('connect:preflight','cleanup:preflight') @($events) 'Fake client fixture order differs.'
    Write-Output 'wait-browser harness preflight passed network=0 git=0 docker=0 python=0 process=0 residue=0'
}

function Invoke-WaitBrowserTargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()

    Invoke-TestCase 'WaitBrowser 01 success cleans once before return' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @((New-FakeBrowserClient -Id 'one' -ConnectBehavior Success -Events $events))
        Invoke-WaitBrowserFixture -Clients $clients -Events $events
        $events.Add('return')
        Assert-Equal @('create:one','connect:one','cleanup:one','return') @($events) 'Success cleanup/return order differs.'
    }

    Invoke-TestCase 'WaitBrowser 02 expected failure cleans once then retries' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @(
            (New-FakeBrowserClient -Id 'one' -ConnectBehavior ExpectedFailure -Events $events),
            (New-FakeBrowserClient -Id 'two' -ConnectBehavior Success -Events $events)
        )
        Invoke-WaitBrowserFixture -Clients $clients -Events $events
        $events.Add('return')
        Assert-Equal @('create:one','connect-fail:one','cleanup:one','retry','create:two','connect:two','cleanup:two','return') @($events) 'Expected-failure retry order differs.'
    }

    Invoke-TestCase 'WaitBrowser 03 success plus cleanup failure returns fixed error' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @((New-FakeBrowserClient -Id 'one' -ConnectBehavior Success -Events $events -CleanupFailure))
        $failure = Get-CapturedException { Invoke-WaitBrowserFixture -Clients $clients -Events $events }
        Assert-Equal 'BROWSER_CLIENT_CLEANUP_FAILED' $failure.Message 'Cleanup-only failure did not return the fixed error.'
        Assert-Equal @('create:one','connect:one','cleanup-fail:one') @($events) 'Cleanup-only failure order differs.'
        Assert-NoRawCleanupDetail $failure 'Cleanup-only failure reflected raw endpoint or internal detail.'
    }

    Invoke-TestCase 'WaitBrowser 04 unexpected primary plus cleanup failure preserves identity' {
        $events = [System.Collections.Generic.List[string]]::new()
        $primary = [System.InvalidOperationException]::new('CONNECT_PRIMARY')
        $clients = @((New-FakeBrowserClient -Id 'one' -ConnectBehavior UnexpectedFailure -Events $events -Primary $primary -CleanupFailure))
        $failure = Get-CapturedException { Invoke-WaitBrowserFixture -Clients $clients -Events $events }
        $events.Add('primary')
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Unexpected connection primary identity was replaced.'
        Assert-Equal @('create:one','connect-primary:one','cleanup-fail:one','primary') @($events) 'Primary/cleanup arbitration order differs.'
    }

    Invoke-TestCase 'WaitBrowser 05 every retry client cleans exactly once' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @(
            (New-FakeBrowserClient -Id 'one' -ConnectBehavior ExpectedFailure -Events $events),
            (New-FakeBrowserClient -Id 'two' -ConnectBehavior ExpectedFailure -Events $events),
            (New-FakeBrowserClient -Id 'three' -ConnectBehavior Success -Events $events)
        )
        Invoke-WaitBrowserFixture -Clients $clients -Events $events
        $events.Add('return')
        Assert-Equal @('create:one','connect-fail:one','cleanup:one','retry','create:two','connect-fail:two','cleanup:two','retry','create:three','connect:three','cleanup:three','return') @($events) 'Multi-retry cleanup order differs.'
        foreach ($id in @('one','two','three')) {
            Assert-Equal 1 @($events | Where-Object { $_ -eq "cleanup:$id" }).Count "Client $id cleanup count differs."
        }
    }

    Invoke-TestCase 'WaitBrowser 06 success cannot return before cleanup' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @((New-FakeBrowserClient -Id 'one' -ConnectBehavior Success -Events $events))
        Invoke-WaitBrowserFixture -Clients $clients -Events $events
        $events.Add('return')
        $cleanupIndex = $events.IndexOf('cleanup:one')
        $returnIndex = $events.IndexOf('return')
        Assert-True ($cleanupIndex -ge 0 -and $cleanupIndex -lt $returnIndex) 'Success returned before client cleanup.'
    }

    Invoke-TestCase 'WaitBrowser 07 raw endpoint and cleanup detail are not reflected' {
        $events = [System.Collections.Generic.List[string]]::new()
        $clients = @((New-FakeBrowserClient -Id 'one' -ConnectBehavior Success -Events $events -CleanupFailure))
        $failure = Get-CapturedException { Invoke-WaitBrowserFixture -Clients $clients -Events $events }
        Assert-Equal 'BROWSER_CLIENT_CLEANUP_FAILED' $failure.Message 'Raw-detail scenario did not return the fixed error.'
        Assert-True ($failure.Message -notmatch '127\.0\.0\.1|14250|NeverReflect|internal') 'Raw host, port, or internal detail was reflected.'
    }

    Invoke-TestCase 'WaitBrowser 08 ordinary exception preserves inner and primary identity' {
        $events = [System.Collections.Generic.List[string]]::new()
        $primary = [System.InvalidOperationException]::new('OUTER', [System.ArgumentException]::new('INNER'))
        $clients = @(
            (New-FakeBrowserClient -Id 'one' -ConnectBehavior UnexpectedFailure -Events $events -Primary $primary),
            (New-FakeBrowserClient -Id 'two' -ConnectBehavior Success -Events $events)
        )
        $failure = Get-CapturedException { Invoke-WaitBrowserFixture -Clients $clients -Events $events }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Ordinary exception primary identity was replaced.'
        Assert-Equal @('create:one','connect-primary:one','cleanup:one') @($events) 'Ordinary exception retried or cleanup count differs.'
        Assert-Equal 1 @($events | Where-Object { $_ -eq 'cleanup:one' }).Count 'Ordinary exception client cleanup count differs.'
        Assert-Equal 0 @($events | Where-Object { $_ -eq 'retry' -or $_ -eq 'create:two' }).Count 'Ordinary exception was retried.'
    }

    Invoke-TestCase 'WaitBrowser 09 aggregate socket exception preserves primary identity' {
        $events = [System.Collections.Generic.List[string]]::new()
        $primary = [System.AggregateException]::new([System.Net.Sockets.SocketException]::new(10061))
        $clients = @(
            (New-FakeBrowserClient -Id 'one' -ConnectBehavior UnexpectedFailure -Events $events -Primary $primary),
            (New-FakeBrowserClient -Id 'two' -ConnectBehavior Success -Events $events)
        )
        $failure = Get-CapturedException { Invoke-WaitBrowserFixture -Clients $clients -Events $events }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'AggregateException primary identity was replaced.'
        Assert-Equal @('create:one','connect-primary:one','cleanup:one') @($events) 'AggregateException retried or cleanup count differs.'
        Assert-Equal 1 @($events | Where-Object { $_ -eq 'cleanup:one' }).Count 'AggregateException client cleanup count differs.'
        Assert-Equal 0 @($events | Where-Object { $_ -eq 'retry' -or $_ -eq 'create:two' }).Count 'AggregateException was retried.'
    }

    Invoke-TestCase 'WaitBrowser 10 direct socket exception cleans each client before retry' {
        $events = [System.Collections.Generic.List[string]]::new()
        $socketFailure = [System.Net.Sockets.SocketException]::new(10061)
        $clients = @(
            (New-FakeBrowserClient -Id 'one' -ConnectBehavior UnexpectedFailure -Events $events -Primary $socketFailure),
            (New-FakeBrowserClient -Id 'two' -ConnectBehavior Success -Events $events)
        )
        Invoke-WaitBrowserFixture -Clients $clients -Events $events
        $events.Add('return')
        Assert-Equal @('create:one','connect-primary:one','cleanup:one','retry','create:two','connect:two','cleanup:two','return') @($events) 'Direct SocketException retry order differs.'
        foreach ($id in @('one','two')) {
            Assert-Equal 1 @($events | Where-Object { $_ -eq "cleanup:$id" }).Count "Client $id cleanup count differs."
        }
    }

    if ($script:Failures.Count -ne 0) {
        Write-Output ('wait-browser targeted failures: ' + $script:Failures.Count)
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Wait-browser targeted contract tests passed count=10'
}

function Remove-OwnerFixFixtureRoot([string]$Root) {
    if (-not [System.IO.Directory]::Exists($Root)) { return }
    $candidate = [System.IO.Path]::GetFullPath($Root)
    $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($temp, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($candidate) -notmatch '^finguardops-owner-fix-[0-9a-f]{32}$') {
        throw 'HARNESS_TEMP_PATH_INVALID'
    }
    foreach ($entry in @([System.IO.Directory]::EnumerateFileSystemEntries($candidate, '*', [System.IO.SearchOption]::AllDirectories))) {
        if (([System.IO.File]::GetAttributes($entry) -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            if ([System.IO.Directory]::Exists($entry)) { [System.IO.Directory]::Delete($entry, $false) }
            elseif ([System.IO.File]::Exists($entry)) { [System.IO.File]::Delete($entry) }
        }
    }
    [System.IO.Directory]::Delete($candidate, $true)
}

function New-OwnerFixFixture {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-owner-fix-' + [guid]::NewGuid().ToString('N'))
    $state = Join-Path $root 'infra\keycloak\.local\state'
    [System.IO.Directory]::CreateDirectory($state) | Out-Null
    return [pscustomobject]@{
        Root = $root
        Prepared = Join-Path $state 'e2e-image-manifest.json'
        Recovery = Join-Path $state 'e2e-image-cleanup-required.json'
    }
}

function New-OwnerFixCleanupContext {
    param(
        [Parameter(Mandatory = $true)]$Fixture,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Markers,
        [scriptblock]$ResourceCleanup,
        [scriptblock]$ImageCleanup,
        [scriptblock]$DeleteFile
    )

    if ($null -eq $ResourceCleanup) {
        $ResourceCleanup = { param($receipt) $Markers.Add('resource') }.GetNewClosure()
    }
    if ($null -eq $ImageCleanup) {
        $ImageCleanup = { param($receipt) $Markers.Add('image') }.GetNewClosure()
    }
    if ($null -eq $DeleteFile) {
        $DeleteFile = {
            param([string]$path)
            $Markers.Add('receipt')
            [System.IO.File]::Delete($path)
        }.GetNewClosure()
    }
    return [pscustomobject]@{
        RepositoryRoot = $Fixture.Root
        PreparedReceiptPath = $Fixture.Prepared
        RecoveryReceiptPath = $Fixture.Recovery
        LeafBoundaries = @{
            ResourceCleanup = $ResourceCleanup
            ImageCleanup = $ImageCleanup
            DeleteFile = $DeleteFile
        }
    }
}

function Invoke-OwnerFixPreflight {
    $fixture = New-OwnerFixFixture
    try {
        Assert-Parsed $ModulePath
        Assert-Parsed $PSCommandPath
        $receipt = New-TestReceipt
        New-E2EReceiptFile -Path $fixture.Prepared -Receipt $receipt -RepositoryRoot $fixture.Root
        Assert-Equal 'Prepared' (Get-E2EReceiptState -PreparedPath $fixture.Prepared -RecoveryPath $fixture.Recovery) 'Preflight receipt state differs.'
        Remove-E2EReceiptFile -Path $fixture.Prepared -RepositoryRoot $fixture.Root
        Assert-Equal 'None' (Get-E2EReceiptState -PreparedPath $fixture.Prepared -RecoveryPath $fixture.Recovery) 'Preflight receipt cleanup differs.'
    }
    finally {
        Remove-OwnerFixFixtureRoot $fixture.Root
    }
    Assert-True (-not [System.IO.Directory]::Exists($fixture.Root)) 'Owner-fix preflight fixture remains.'
    Write-Output 'owner-fix harness preflight passed receipt=actual residue=0'
}

function Invoke-OwnerFixTargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()

    Invoke-TestCase 'OwnerFix 01 WarningPreference Stop preserves primary' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_WARNING_FAILURE')
        $actions = @([pscustomobject]@{ Action = { throw 'NeverReflect C:\sensitive\warning credential' }; ErrorCode = 'WARNING_CLEANUP_FAILED'; SkipAfterCleanupFailure = $false })
        $previous = $global:WarningPreference
        try {
            $global:WarningPreference = 'Stop'
            $failure = Get-CapturedException { Invoke-E2ECleanupActions -Primary $primary -Actions $actions }
        }
        finally { $global:WarningPreference = $previous }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'WarningPreference Stop replaced primary.'
        Assert-NoRawCleanupDetail $failure 'WarningPreference Stop reflected cleanup detail.'
    }

    Invoke-TestCase 'OwnerFix 02 diagnostic writer failure preserves primary' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_DIAGNOSTIC_FAILURE')
        $calls = [System.Collections.Generic.List[string]]::new()
        $writer = { param($message) $calls.Add('diagnostic'); throw 'NeverReflect diagnostic writer credential' }.GetNewClosure()
        $actions = @([pscustomobject]@{ Action = { throw 'CLEANUP_FAILURE' }; ErrorCode = 'CLEANUP_FAILURE'; SkipAfterCleanupFailure = $false })
        $failure = Get-CapturedException { Invoke-E2ECleanupActions -Primary $primary -Actions $actions -DiagnosticWriter $writer }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Diagnostic writer failure replaced primary.'
        Assert-Equal @('diagnostic') @($calls) 'Diagnostic writer call sequence differs.'
    }

    foreach ($case in @(
        [pscustomobject]@{ Name = 'OwnerFix 03 Run mutex release failure preserves primary'; Fail = 'release' },
        [pscustomobject]@{ Name = 'OwnerFix 04 Run mutex dispose failure preserves primary'; Fail = 'dispose' }
    )) {
        Invoke-TestCase $case.Name {
            $primary = [System.InvalidOperationException]::new('PRIMARY_RUN_FAILURE')
            $markers = [System.Collections.Generic.List[string]]::new()
            $fail = $case.Fail
            $boundaries = @{
                ReleaseRunMutex = { $markers.Add('release'); if ($fail -eq 'release') { throw 'NeverReflect release credential' } }.GetNewClosure()
                DisposeRunMutex = { $markers.Add('dispose'); if ($fail -eq 'dispose') { throw 'NeverReflect dispose credential' } }.GetNewClosure()
            }
            $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $primary -Boundaries $boundaries }
            Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Run mutex cleanup replaced primary.'
            Assert-Equal @('release','dispose') @($markers) 'Run mutex cleanup order differs.'
        }
    }

    foreach ($scopeName in @('Prepare', 'Run')) {
        Invoke-TestCase ("OwnerFix {0} owner environment restore failure preserves primary" -f $scopeName) {
            $state = [pscustomobject]@{ Primary = [System.InvalidOperationException]::new("PRIMARY_${scopeName}_FAILURE") }
            $markers = [System.Collections.Generic.List[string]]::new()
            $boundaries = @{
                SetOwnerEnvironment = { param($receipt) $markers.Add('set'); return [ordered]@{} }.GetNewClosure()
                RestoreOwnerEnvironment = { param($previous) $markers.Add('restore'); throw 'NeverReflect environment credential' }.GetNewClosure()
            }
            $ownerBody = { $markers.Add('body'); throw $state.Primary }.GetNewClosure()
            $ownerBoundaries = $boundaries
            $invocation = {
                Invoke-E2EOwnerEnvironmentScope -Receipt (New-TestReceipt) `
                    -Boundaries $ownerBoundaries -Body $ownerBody
            }.GetNewClosure()
            $failure = Get-CapturedException $invocation
            Assert-True ([object]::ReferenceEquals($state.Primary, $failure)) "$scopeName environment restore replaced primary."
            Assert-Equal @('set','body','restore') @($markers) "$scopeName environment scope order differs."
        }
    }

    foreach ($case in @(
        [pscustomobject]@{ Name = 'OwnerFix 07 top-level lock release failure preserves primary'; Fail = 'release' },
        [pscustomobject]@{ Name = 'OwnerFix 08 top-level lock dispose failure preserves primary'; Fail = 'dispose' }
    )) {
        Invoke-TestCase $case.Name {
            $fixture = New-OwnerFixFixture
            try {
                New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
                $primaryState = [pscustomobject]@{ Primary = [System.InvalidOperationException]::new('PRIMARY_TOP_LEVEL_FAILURE') }
                $markers = [System.Collections.Generic.List[string]]::new()
                $resource = { param($receipt) $markers.Add('resource'); throw $primaryState.Primary }.GetNewClosure()
                $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers -ResourceCleanup $resource
                $fail = $case.Fail
                $locks = @{
                    EnterLock = { $markers.Add('enter'); return [pscustomobject]@{ Name = 'fake-lock' } }.GetNewClosure()
                    ReleaseLock = { param($lock) $markers.Add('release'); if ($fail -eq 'release') { throw 'NeverReflect top release credential' } }.GetNewClosure()
                    DisposeLock = { param($lock) $markers.Add('dispose'); if ($fail -eq 'dispose') { throw 'NeverReflect top dispose credential' } }.GetNewClosure()
                }
                $failure = Get-CapturedException { Invoke-KeycloakE2E -Mode Cleanup -CleanupContext $context -LifecycleBoundaries $locks }
                Assert-True ([object]::ReferenceEquals($primaryState.Primary, $failure)) 'Top-level lock cleanup replaced primary.'
                Assert-Equal @('enter','resource','image','release','dispose') @($markers) 'Top-level cleanup order differs.'
                Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'Failed top-level cleanup deleted recovery receipt.'
            }
            finally { Remove-OwnerFixFixtureRoot $fixture.Root }
        }
    }

    Invoke-TestCase 'OwnerFix 09 no primary returns first cleanup failure' {
        $markers = [System.Collections.Generic.List[string]]::new()
        $boundaries = @{
            Release = { param($lock) $markers.Add('release'); throw 'NeverReflect release credential' }.GetNewClosure()
            Dispose = { param($lock) $markers.Add('dispose'); throw 'NeverReflect dispose credential' }.GetNewClosure()
        }
        $failure = Get-CapturedException { Exit-E2ELifecycleLock -Lock ([pscustomobject]@{}) -Boundaries $boundaries }
        Assert-Equal 'LIFECYCLE_LOCK_RELEASE_FAILED' $failure.Message 'First lock cleanup failure was not returned.'
        Assert-Equal @('release','dispose') @($markers) 'Lock cleanup did not continue after failure.'
    }

    foreach ($stateName in @('Prepared', 'Recovery')) {
        Invoke-TestCase ("OwnerFix actual $stateName receipt Cleanup" ) {
            $fixture = New-OwnerFixFixture
            try {
                $path = if ($stateName -eq 'Prepared') { $fixture.Prepared } else { $fixture.Recovery }
                New-E2EReceiptFile -Path $path -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
                $markers = [System.Collections.Generic.List[string]]::new()
                $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
                Invoke-E2ECleanupMode -CleanupContext $context
                Assert-True (-not [System.IO.File]::Exists($path)) "$stateName receipt remained after Cleanup."
                Assert-Equal @('resource','image','receipt') @($markers) "$stateName cleanup order differs."
            }
            finally { Remove-OwnerFixFixtureRoot $fixture.Root }
        }
    }

    Invoke-TestCase 'OwnerFix 12 actual dual receipt rejected' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $markers = [System.Collections.Generic.List[string]]::new()
            $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
            Assert-Throws { Invoke-E2ECleanupMode -CleanupContext $context } '^RECEIPT_STATE_INVALID$' 'Actual dual receipt state was accepted.'
            Assert-Equal 0 $markers.Count 'Cleanup ran for dual receipt state.'
            Assert-True ([System.IO.File]::Exists($fixture.Prepared) -and [System.IO.File]::Exists($fixture.Recovery)) 'Dual receipt state changed.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'OwnerFix 13 actual missing receipt rejected' {
        $fixture = New-OwnerFixFixture
        try {
            $markers = [System.Collections.Generic.List[string]]::new()
            $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
            Assert-Throws { Invoke-E2ECleanupMode -CleanupContext $context } '^CLEANUP_RECEIPT_REQUIRED$' 'Actual missing receipt state was accepted.'
            Assert-Equal 0 $markers.Count 'Cleanup ran without a receipt.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'OwnerFix 14 actual cleanup success deletes receipt' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $markers = [System.Collections.Generic.List[string]]::new()
            Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers)
            Assert-True (-not [System.IO.File]::Exists($fixture.Prepared)) 'Successful actual cleanup retained receipt.'
            Assert-Equal @('resource','image','receipt') @($markers) 'Successful actual cleanup order differs.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'OwnerFix 15 actual cleanup failure retains receipt' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $markers = [System.Collections.Generic.List[string]]::new()
            $resource = { param($receipt) $markers.Add('resource'); throw 'NeverReflect C:\sensitive\resource credential' }.GetNewClosure()
            $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers -ResourceCleanup $resource) }
            Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message 'Actual cleanup returned wrong safe failure.'
            Assert-Equal @('resource','image') @($markers) 'Actual cleanup failure order differs.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'Actual cleanup failure deleted receipt.'
            Assert-NoRawCleanupDetail $failure 'Actual cleanup failure reflected raw detail.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'OwnerFix 16 actual receipt deletion failure retains receipt' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $markers = [System.Collections.Generic.List[string]]::new()
            $delete = { param([string]$path) $markers.Add('receipt'); throw 'NeverReflect C:\sensitive\receipt credential' }.GetNewClosure()
            $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers -DeleteFile $delete) }
            Assert-Equal 'RECEIPT_DELETE_FAILED' $failure.Message 'Actual receipt deletion returned wrong safe failure.'
            Assert-Equal @('resource','image','receipt') @($markers) 'Receipt deletion failure order differs.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'Receipt deletion failure removed receipt.'
            Assert-NoRawCleanupDetail $failure 'Receipt deletion failure reflected raw detail.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'OwnerFix 17 cleanup exact order after multiple failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_ORDER_FAILURE')
        $markers = [System.Collections.Generic.List[string]]::new()
        $boundaries = @{
            RestoreOutputEnvironment = { $markers.Add('output-env') }.GetNewClosure()
            RestoreProjectEnvironment = { $markers.Add('project-env'); throw 'PROJECT_ENV_FAILURE' }.GetNewClosure()
            RestoreBrowserEnvironment = { $markers.Add('browser-env') }.GetNewClosure()
            RemoveBrowser = { $markers.Add('browser'); throw 'BROWSER_FAILURE' }.GetNewClosure()
            ComposeDown = { $markers.Add('compose') }.GetNewClosure()
            RemoveOutput = { $markers.Add('output') }.GetNewClosure()
            DisposeCertificate = { $markers.Add('certificate') }.GetNewClosure()
            ReleaseRunMutex = { $markers.Add('release') }.GetNewClosure()
            DisposeRunMutex = { $markers.Add('dispose') }.GetNewClosure()
        }
        $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $primary -Boundaries $boundaries }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Exact-order cleanup replaced primary.'
        Assert-Equal @('output-env','project-env','browser-env','browser','compose','output','certificate','release','dispose') @($markers) 'Cleanup exact order differs.'
    }

    if ($script:Failures.Count -ne 0) {
        Write-Output ('owner-fix targeted failures: ' + $script:Failures.Count)
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Owner-fix targeted contract tests passed count=17'
}

function Invoke-RunCleanupContractCase {
    param(
        [Parameter(Mandatory = $true)][string[]]$FailActions,
        $Primary,
        [Parameter(Mandatory = $true)][string]$ExpectedCleanupCode
    )

    $calls = [ordered]@{ Browser = 0; Compose = 0; Output = 0 }
    $markers = [System.Collections.Generic.List[string]]::new()
    $browser = {
        $markers.Add('browser')
        $calls.Browser++
        if ($FailActions -contains 'Browser') { throw 'NeverReflect C:\sensitive\browser credential' }
    }.GetNewClosure()
    $compose = {
        $markers.Add('compose')
        $calls.Compose++
        if ($FailActions -contains 'Compose') { throw 'NeverReflect compose stderr credential' }
    }.GetNewClosure()
    $output = {
        $markers.Add('output')
        $calls.Output++
        if ($FailActions -contains 'Output') { throw 'NeverReflect C:\sensitive\output credential' }
    }.GetNewClosure()
    $boundaries = @{
        RemoveBrowser = $browser
        ComposeDown = $compose
        RemoveOutput = $output
    }

    $failure = Get-CapturedException {
        Invoke-E2ERunCoreCleanup -Primary $Primary -Boundaries $boundaries
    }
    Assert-True ($null -ne $failure) 'Run cleanup scenario returned no failure.'
    if ($null -ne $Primary) {
        Assert-True ([object]::ReferenceEquals($Primary, $failure)) 'Run cleanup replaced the primary failure identity.'
    }
    else {
        Assert-Equal $ExpectedCleanupCode $failure.Message 'Run cleanup did not return the first fixed cleanup code.'
    }
    Assert-Equal 1 $calls.Browser 'Browser cleanup call count differs.'
    Assert-Equal 1 $calls.Compose 'Compose cleanup call count differs.'
    Assert-Equal 1 $calls.Output 'Output cleanup call count differs.'
    Assert-Equal @('browser','compose','output') @($markers) 'Run cleanup exact order differs.'
    Assert-NoRawCleanupDetail $failure 'Run cleanup reflected an internal cleanup detail.'
}

function Invoke-MajorFixPreflight {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-major-fix-preflight-' + [guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($root) | Out-Null
    try {
        Assert-Parsed $ModulePath
        Assert-Parsed $PSCommandPath
        $calls = [System.Collections.Generic.List[string]]::new()
        $boundaries = @{
            ReadSingleReceipt = { $calls.Add('read'); return [pscustomobject]@{ Receipt = New-TestReceipt; Path = 'prepared' } }
            FullCleanup = { param($state) $calls.Add('cleanup:' + $state.Path) }
        }
        $state = & $boundaries.ReadSingleReceipt
        & $boundaries.FullCleanup $state
        Assert-Equal @('read', 'cleanup:prepared') @($calls) 'Injected cleanup boundary fixture differs.'
    }
    finally {
        if ([System.IO.Directory]::Exists($root)) {
            [System.IO.Directory]::Delete($root, $true)
        }
    }
    Assert-True (-not [System.IO.Directory]::Exists($root)) 'Major-fix preflight fixture remains.'
    Write-Output 'major-fix harness preflight passed git=0 docker=0 python=0 process=0 residue=0'
}

function Invoke-MajorFixTargeted11Case {
    Invoke-TestCase 'Targeted 11 actual receipt deletion failure' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $receiptDeleteCalls = [System.Collections.Generic.List[string]]::new()
            $receiptDeleteMarker = 'receipt-delete-attempted'
            $delete = {
                param([string]$path)
                $receiptDeleteCalls.Add($receiptDeleteMarker)
                throw 'NeverReflect C:\sensitive\receipt credential'
            }.GetNewClosure()
            $failure = Get-CapturedException {
                Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers ([System.Collections.Generic.List[string]]::new()) -DeleteFile $delete)
            }
            Assert-Equal 'RECEIPT_DELETE_FAILED' $failure.Message 'Receipt deletion failure returned wrong fixed code.'
            Assert-Equal 1 $receiptDeleteCalls.Count 'Receipt deletion call count differs.'
            Assert-Equal $receiptDeleteMarker $receiptDeleteCalls[0] 'Receipt deletion marker differs.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'Failed receipt deletion changed receipt state.'
            Assert-NoRawCleanupDetail $failure 'Cleanup reflected receipt deletion details.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }
}

function Invoke-MajorFixFixture11 {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-MajorFixTargeted11Case
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Major-fix fixture 11 passed git=0 docker=0 python=0 process=0 residue=0'
}

function Invoke-MajorFixTargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()

    Invoke-TestCase 'Targeted 01 Run primary plus browser removal failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_FAILURE')
        Invoke-RunCleanupContractCase -FailActions @('Browser') -Primary $primary -ExpectedCleanupCode 'BROWSER_CONTAINER_CLEANUP_FAILED'
    }

    Invoke-TestCase 'Targeted 02 Run primary plus Compose down failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_FAILURE')
        Invoke-RunCleanupContractCase -FailActions @('Compose') -Primary $primary -ExpectedCleanupCode 'COMPOSE_CLEANUP_FAILED'
    }

    Invoke-TestCase 'Targeted 03 Run primary plus output removal failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_FAILURE')
        Invoke-RunCleanupContractCase -FailActions @('Output') -Primary $primary -ExpectedCleanupCode 'OUTPUT_DIRECTORY_CLEANUP_FAILED'
    }

    Invoke-TestCase 'Targeted 04 Run primary plus multiple cleanup failures' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_FAILURE')
        Invoke-RunCleanupContractCase -FailActions @('Browser', 'Compose', 'Output') -Primary $primary -ExpectedCleanupCode 'BROWSER_CONTAINER_CLEANUP_FAILED'
    }

    Invoke-TestCase 'Targeted 05 Run success plus cleanup-only failure' {
        Invoke-RunCleanupContractCase -FailActions @('Compose', 'Output') -Primary $null -ExpectedCleanupCode 'COMPOSE_CLEANUP_FAILED'
    }

    Invoke-TestCase 'Targeted 06 Prepare build primary plus temp removal failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_BUILD_FAILURE')
        $calls = [ordered]@{ Build = 0; Temp = 0 }
        $markers = [System.Collections.Generic.List[string]]::new()
        $boundaries = @{
            Build = { $markers.Add('build'); $calls.Build++; throw $primary }.GetNewClosure()
            RemoveTemp = { $markers.Add('temp'); $calls.Temp++; throw 'NeverReflect C:\sensitive\browser-build credential' }.GetNewClosure()
        }
        $failure = Get-CapturedException { Invoke-E2EPrepareBrowserBuildLifecycle -Boundaries $boundaries }
        Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Prepare temp cleanup replaced the build primary.'
        Assert-Equal 1 $calls.Build 'Prepare browser build call count differs.'
        Assert-Equal 1 $calls.Temp 'Prepare temp cleanup call count differs.'
        Assert-Equal @('build','temp') @($markers) 'Prepare browser build cleanup exact order differs.'
        Assert-NoRawCleanupDetail $failure 'Prepare cleanup reflected an internal cleanup detail.'
    }

    Invoke-TestCase 'Targeted 07 production Cleanup mode calls shared lifecycle' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $calls = [System.Collections.Generic.List[string]]::new()
            Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $calls)
            Assert-Equal @('resource','image','receipt') @($calls) 'Cleanup mode did not dispatch through the production lifecycle.'
            Assert-True (-not [System.IO.File]::Exists($fixture.Prepared)) 'Cleanup mode did not delete the actual receipt.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'Targeted 08 prepared receipt Cleanup orchestration' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $calls = [System.Collections.Generic.List[string]]::new()
            Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $calls)
            Assert-True (-not [System.IO.File]::Exists($fixture.Prepared)) 'Prepared receipt remained after successful cleanup.'
            Assert-Equal @('resource','image','receipt') @($calls) 'Prepared receipt cleanup order differs.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'Targeted 09 recovery receipt Cleanup orchestration' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $calls = [System.Collections.Generic.List[string]]::new()
            Invoke-E2ECleanupMode -CleanupContext (New-OwnerFixCleanupContext -Fixture $fixture -Markers $calls)
            Assert-True (-not [System.IO.File]::Exists($fixture.Recovery)) 'Recovery receipt remained after successful cleanup.'
            Assert-Equal @('resource','image','receipt') @($calls) 'Recovery receipt cleanup order differs.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-TestCase 'Targeted 10 dual receipt state is rejected by Cleanup mode' {
        $fixture = New-OwnerFixFixture
        try {
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $calls = [System.Collections.Generic.List[string]]::new()
            $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $calls
            Assert-Throws { Invoke-E2ECleanupMode -CleanupContext $context } '^RECEIPT_STATE_INVALID$' 'Cleanup accepted dual receipts.'
            Assert-Equal 0 $calls.Count 'Cleanup ran after dual receipt rejection.'
            Assert-True ([System.IO.File]::Exists($fixture.Prepared) -and [System.IO.File]::Exists($fixture.Recovery)) 'Dual receipt rejection changed receipt state.'
        }
        finally { Remove-OwnerFixFixtureRoot $fixture.Root }
    }

    Invoke-MajorFixTargeted11Case

    if ($script:Failures.Count -ne 0) {
        Write-Output ('targeted failures: ' + $script:Failures.Count)
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Major-fix targeted contract tests passed count=11'
}

function New-LockChildSource {
    return @'
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ModulePath,
    [Parameter(Mandatory = $true)][string]$MutexName,
    [Parameter(Mandatory = $true)][string]$ReadyEventName,
    [Parameter(Mandatory = $true)][string]$ReleaseEventName,
    [Parameter(Mandatory = $true)][string]$DoneEventName,
    [Parameter(Mandatory = $true)][string]$ReceiptPath,
    [Parameter(Mandatory = $true)][string]$RepositoryRoot,
    [Parameter(Mandatory = $true)][string]$ResultPath,
    [switch]$Hold
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ready = [System.Threading.EventWaitHandle]::OpenExisting($ReadyEventName)
$release = [System.Threading.EventWaitHandle]::OpenExisting($ReleaseEventName)
$done = [System.Threading.EventWaitHandle]::OpenExisting($DoneEventName)
$lock = $null
try {
    Import-Module $ModulePath -Force
    if (-not $Hold -and -not $ready.WaitOne(10000)) {
        throw 'LOCK_RENDEZVOUS_TIMEOUT'
    }
    $lock = Enter-E2ELifecycleLock -Name $MutexName
    $receipt = New-E2EReceipt -RunId '0123456789abcdef0123456789abcdef' -RepositoryId ('a' * 64) -CommitSha ('b' * 40) -TreeSha ('c' * 40)
    New-E2EReceiptFile -Path $ReceiptPath -Receipt $receipt -RepositoryRoot $RepositoryRoot
    [System.IO.File]::WriteAllText($ResultPath, 'winner', [System.Text.UTF8Encoding]::new($false))
    if ($Hold) {
        $ready.Set() | Out-Null
        if (-not $release.WaitOne(10000)) {
            throw 'LOCK_RELEASE_TIMEOUT'
        }
    }
}
catch {
    [System.IO.File]::WriteAllText($ResultPath, ('loser:' + $_.Exception.Message), [System.Text.UTF8Encoding]::new($false))
}
finally {
    if ($null -ne $lock) {
        try { Exit-E2ELifecycleLock -Lock $lock } catch {}
    }
    $done.Set() | Out-Null
    $ready.Dispose()
    $release.Dispose()
    $done.Dispose()
}
'@
}

function Invoke-LockProcessTest([switch]$UseHarnessModule) {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-e2e-lock-' + [guid]::NewGuid().ToString('N'))
    $child = Join-Path $root 'lock-child.ps1'
    $receiptOne = Join-Path $root 'winner-one.json'
    $receiptTwo = Join-Path $root 'winner-two.json'
    $resultOne = Join-Path $root 'result-one.txt'
    $resultTwo = Join-Path $root 'result-two.txt'
    $stdoutOne = Join-Path $root 'stdout-one.txt'
    $stderrOne = Join-Path $root 'stderr-one.txt'
    $stdoutTwo = Join-Path $root 'stdout-two.txt'
    $stderrTwo = Join-Path $root 'stderr-two.txt'
    $id = [guid]::NewGuid().ToString('N')
    $mutexName = "Local\finguardops-e2e-test-$id"
    $readyName = "Local\finguardops-e2e-ready-$id"
    $releaseName = "Local\finguardops-e2e-release-$id"
    $doneOneName = "Local\finguardops-e2e-done-one-$id"
    $doneTwoName = "Local\finguardops-e2e-done-two-$id"
    $ready = $null
    $release = $null
    $doneOne = $null
    $doneTwo = $null
    $first = $null
    $second = $null
    [System.IO.Directory]::CreateDirectory($root) | Out-Null
    try {
        $source = New-LockChildSource
        [System.IO.File]::WriteAllText($child, ($source -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
        Assert-Parsed $child
        $effectiveModulePath = $ModulePath
        if ($UseHarnessModule) {
            $effectiveModulePath = Join-Path $root 'harness-lock.psm1'
            $harnessModule = @'
function Enter-E2ELifecycleLock([string]$Name) {
    $lock = [System.Threading.Mutex]::new($false, $Name)
    if (-not $lock.WaitOne(0)) { $lock.Dispose(); throw 'E2E_LOCK_HELD' }
    return $lock
}
function Exit-E2ELifecycleLock($Lock) { $Lock.ReleaseMutex(); $Lock.Dispose() }
function New-E2EReceipt { return [ordered]@{ schemaVersion = 1 } }
function New-E2EReceiptFile([string]$Path) {
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try { $stream.WriteByte(120); $stream.Flush($true) } finally { $stream.Dispose() }
}
Export-ModuleMember -Function Enter-E2ELifecycleLock,Exit-E2ELifecycleLock,New-E2EReceipt,New-E2EReceiptFile
'@
            [System.IO.File]::WriteAllText($effectiveModulePath, ($harnessModule -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
            Assert-Parsed $effectiveModulePath
        }
        $created = $false
        $ready = [System.Threading.EventWaitHandle]::new($false, [System.Threading.EventResetMode]::ManualReset, $readyName, [ref]$created)
        $release = [System.Threading.EventWaitHandle]::new($false, [System.Threading.EventResetMode]::ManualReset, $releaseName, [ref]$created)
        $doneOne = [System.Threading.EventWaitHandle]::new($false, [System.Threading.EventResetMode]::ManualReset, $doneOneName, [ref]$created)
        $doneTwo = [System.Threading.EventWaitHandle]::new($false, [System.Threading.EventResetMode]::ManualReset, $doneTwoName, [ref]$created)
        $firstArgs = @(
            '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $child + '"'),
            '-ModulePath', ('"' + $effectiveModulePath + '"'), '-MutexName', $mutexName,
            '-ReadyEventName', $readyName, '-ReleaseEventName', $releaseName,
            '-DoneEventName', $doneOneName, '-ReceiptPath', ('"' + $receiptOne + '"'),
            '-RepositoryRoot', ('"' + $root + '"'), '-ResultPath', ('"' + $resultOne + '"'), '-Hold'
        )
        $first = Start-Process -FilePath 'powershell.exe' -ArgumentList $firstArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutOne -RedirectStandardError $stderrOne
        $firstSignal = [System.Threading.WaitHandle]::WaitAny(@($ready, $doneOne), 15000)
        if ($firstSignal -eq [System.Threading.WaitHandle]::WaitTimeout) {
            $detail = if ([System.IO.File]::Exists($stderrOne)) { [System.IO.File]::ReadAllText($stderrOne).Trim() } else { '' }
            throw ('LOCK_FIRST_CHILD_TIMEOUT ' + $detail)
        }
        $secondArgs = @(
            '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $child + '"'),
            '-ModulePath', ('"' + $effectiveModulePath + '"'), '-MutexName', $mutexName,
            '-ReadyEventName', $readyName, '-ReleaseEventName', $releaseName,
            '-DoneEventName', $doneTwoName, '-ReceiptPath', ('"' + $receiptTwo + '"'),
            '-RepositoryRoot', ('"' + $root + '"'), '-ResultPath', ('"' + $resultTwo + '"')
        )
        $second = Start-Process -FilePath 'powershell.exe' -ArgumentList $secondArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutTwo -RedirectStandardError $stderrTwo
        if (-not $doneTwo.WaitOne(15000)) {
            throw 'LOCK_SECOND_CHILD_TIMEOUT'
        }
        $release.Set() | Out-Null
        if (-not $doneOne.WaitOne(15000)) {
            throw 'LOCK_FIRST_CHILD_RELEASE_TIMEOUT'
        }
        if (-not $first.WaitForExit(5000) -or -not $second.WaitForExit(5000)) {
            throw 'LOCK_CHILD_EXIT_TIMEOUT'
        }
        $results = @(
            if ([System.IO.File]::Exists($resultOne)) { [System.IO.File]::ReadAllText($resultOne) } else { 'missing-one' }
            if ([System.IO.File]::Exists($resultTwo)) { [System.IO.File]::ReadAllText($resultTwo) } else { 'missing-two' }
        )
        Assert-Equal 1 @($results | Where-Object { $_ -eq 'winner' }).Count 'Exactly one process must win the lock.'
        Assert-Equal 1 @(@($receiptOne, $receiptTwo) | Where-Object { [System.IO.File]::Exists($_) }).Count 'Only the winner may create a receipt.'
        Assert-True ($results -contains 'winner') 'A lock winner was not recorded.'
        Assert-Equal 1 @($results | Where-Object { $_ -eq 'loser:E2E_LOCK_BUSY' }).Count 'The competing process did not return the fixed busy result.'
        if ($results[0] -eq 'loser:E2E_LOCK_BUSY') {
            Assert-True (-not [System.IO.File]::Exists($receiptOne)) 'The losing first process wrote a receipt.'
        }
        if ($results[1] -eq 'loser:E2E_LOCK_BUSY') {
            Assert-True (-not [System.IO.File]::Exists($receiptTwo)) 'The losing second process wrote a receipt.'
        }
        Write-Output 'EVIDENCE production lock winner=1 loser=E2E_LOCK_BUSY loser-receipt-writes=0'
    }
    finally {
        if ($null -ne $release) { $release.Set() | Out-Null }
        foreach ($process in @($first, $second)) {
            if ($null -ne $process) {
                if (-not $process.HasExited) { $process.Kill() }
                $process.Dispose()
            }
        }
        foreach ($handle in @($ready, $release, $doneOne, $doneTwo)) {
            if ($null -ne $handle) { $handle.Dispose() }
        }
        if ([System.IO.Directory]::Exists($root)) {
            [System.IO.Directory]::Delete($root, $true)
        }
    }
    Assert-True (-not [System.IO.Directory]::Exists($root)) 'Lock fixture artifacts remain.'
}

function Get-SessionStateEnvironmentDigest {
    $pairs = @([System.Environment]::GetEnvironmentVariables('Process').GetEnumerator() |
            ForEach-Object { [string]$_.Key + '=' + [string]$_.Value } |
            Sort-Object)
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes(($pairs -join "`n"))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally { $sha.Dispose() }
}

function Get-SessionStateGitSnapshot {
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'HARNESS_GIT_HEAD_FAILED' }
    $tree = (& git rev-parse 'HEAD^{tree}').Trim()
    if ($LASTEXITCODE -ne 0) { throw 'HARNESS_GIT_TREE_FAILED' }
    $index = (& git write-tree).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'HARNESS_GIT_INDEX_FAILED' }
    $status = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'HARNESS_GIT_STATUS_FAILED' }
    return [pscustomobject]@{
        Head = $head
        Tree = $tree
        Index = $index
        Status = $status -join "`n"
    }
}

function New-SessionStateChildSource {
    return @'
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ModulePath,
    [Parameter(Mandatory = $true)][string]$ResultPath,
    [Parameter(Mandatory = $true)][string]$DockerShimDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$env:PATH = $DockerShimDirectory + [System.IO.Path]::PathSeparator + $env:PATH
$events = [System.Collections.Generic.List[string]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
$privateNames = @(
    'Get-E2ESourceIdentity',
    'Get-E2EReceiptValue',
    'Invoke-E2EPrepareBuild',
    'Invoke-E2EPrepareMode',
    'Invoke-E2EServiceMode',
    'Invoke-E2ERunMode',
    'Invoke-E2EValidateMode',
    'Assert-E2EOwnedImages',
    'Invoke-E2EServiceChild',
    'Assert-E2EContainerImages',
    'Invoke-E2EProjectCleanup',
    'Invoke-E2EBrowserRunCore',
    'Assert-SafeCertificate',
    'Assert-CertificateKeyPair',
    'Remove-OwnedContainer'
)
$privateExportCount = 0
$commandNotFoundCount = 0
$dispatchCount = 0
$sourceCallbackCount = 0
$certificateDisposed = $false
$approvedSuccessRemovals = 0
$approvedPrimaryCleanupRemovals = 0
$approvedCleanupOnlyRemovals = 0
$approvedPrimaryIdentityPreserved = $false
$approvedCleanupOnlyError = $null
$dockerShimResolved = [string]::Equals(
    (Get-Command docker -ErrorAction Stop).Source,
    (Join-Path $DockerShimDirectory 'docker.cmd'),
    [System.StringComparison]::OrdinalIgnoreCase
)

function New-ChildReceipt {
    return [ordered]@{
        schemaVersion = [int]1
        runId = '0123456789abcdef0123456789abcdef'
        repositoryId = ('a' * 64)
        commitSha = ('b' * 40)
        treeSha = ('c' * 40)
    }
}

function Get-ChildEnvironmentDigest {
    $pairs = @([System.Environment]::GetEnvironmentVariables('Process').GetEnumerator() |
            ForEach-Object { [string]$_.Key + '=' + [string]$_.Value } |
            Sort-Object)
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes(($pairs -join "`n"))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally { $sha.Dispose() }
}

function Test-ChildCommandNotFound($ErrorRecord) {
    if ($null -eq $ErrorRecord) { return $false }
    if ($ErrorRecord.FullyQualifiedErrorId -match 'CommandNotFound') { return $true }
    $exception = $ErrorRecord.Exception
    while ($null -ne $exception) {
        if ($exception -is [System.Management.Automation.CommandNotFoundException] -or
            $exception.GetType().FullName -eq 'System.Management.Automation.CommandNotFoundException') {
            return $true
        }
        $exception = $exception.InnerException
    }
    return $false
}

$childEnvironmentBefore = Get-ChildEnvironmentDigest
foreach ($mode in @('Prepare', 'Service', 'Run', 'Validate')) {
    $module = $null
    try {
        $module = Import-Module $ModulePath -Force -PassThru
        $privateExportCount += @(Get-Command -Module $module.Name |
                Where-Object { $privateNames -contains $_.Name }).Count
        $receipt = New-ChildReceipt
        & $module {
            param($activeMode, $activeReceipt, $eventSink)
            $script:SessionStateMode = $activeMode
            $script:SessionStateReceipt = $activeReceipt
            $script:SessionStateEvents = $eventSink
            $script:SessionStateCertificate = $null

            function script:Enter-E2ELifecycleLock {
                $value = [System.Threading.Mutex]::new($false)
                if (-not $value.WaitOne(0)) { $value.Dispose(); throw 'HARNESS_LOCK_FAILED' }
                return $value
            }
            function script:Get-E2EReceiptState {
                if ($script:SessionStateMode -eq 'Prepare') { return 'None' }
                return 'Prepared'
            }
            function script:Read-E2EReceiptFile {
                $script:SessionStateEvents.Add('receipt-read')
                return $script:SessionStateReceipt
            }
            function script:Get-E2ESourceIdentity {
                $script:SessionStateEvents.Add(('source:' + $script:SessionStateMode))
                return $script:SessionStateReceipt
            }
            function script:Set-E2EOwnerEnvironment {
                param($Receipt)
                $script:SessionStateEvents.Add(('owner-set:' + $script:SessionStateMode))
                return [ordered]@{}
            }
            function script:Restore-E2EOwnerEnvironment {
                param($Previous)
                $script:SessionStateEvents.Add(('owner-restore:' + $script:SessionStateMode))
            }
            function script:New-E2EReceiptFile {
                param($Path, $Receipt, $RepositoryRoot)
                $script:SessionStateEvents.Add('prepare-create-recovery')
            }
            function script:Move-E2EReceiptFile {
                param($Source, $Destination, $RepositoryRoot)
                $script:SessionStateEvents.Add(('receipt-move:' + $script:SessionStateMode))
            }
            function script:Invoke-E2EPrepareBuild {
                param($Receipt)
                $script:SessionStateEvents.Add('prepare-build')
            }
            function script:Invoke-E2EFullCleanup {
                param($Receipt, $ReceiptPath, $RepositoryRootPath, $LeafBoundaries, [switch]$RequireLeafBoundaries)
                $script:SessionStateEvents.Add(('full-cleanup:' + $script:SessionStateMode))
            }
            function script:Assert-E2EOwnedImages {
                param($Receipt)
                $script:SessionStateEvents.Add(('assert-images:' + $script:SessionStateMode))
                return [ordered]@{ Browser = [pscustomobject]@{ Id = 'sha256:' + ('d' * 64) } }
            }
            function script:Invoke-E2EServiceChild {
                param($Receipt)
                $script:SessionStateEvents.Add('service-child')
            }
            function script:Assert-E2EContainerImages {
                param($Receipt, $Project)
                $script:SessionStateEvents.Add('service-containers')
            }
            function script:Invoke-E2EProjectCleanup {
                param($Project)
                $script:SessionStateEvents.Add('service-project-cleanup')
            }
            function script:Invoke-E2EBrowserRunCore {
                param($Receipt)
                $script:SessionStateEvents.Add('run-browser')
            }
            function script:Assert-SafeCertificate {
                param($Path)
                $script:SessionStateEvents.Add('validate-certificate')
                $script:SessionStateCertificate = [System.IO.MemoryStream]::new()
                return $script:SessionStateCertificate
            }
            function script:Assert-CertificateKeyPair {
                param($BrowserImageId)
                $script:SessionStateEvents.Add('validate-key-pair')
            }
        } $mode $receipt $events

        $command = Get-Command Invoke-KeycloakE2E -Module $module.Name -ErrorAction Stop
        & $command -Mode $mode | Out-Null
        $dispatchCount++
        if ($mode -eq 'Prepare') {
            $sourceCallbackCount = @($events | Where-Object { $_ -eq 'source:Prepare' }).Count
        }
        if ($mode -eq 'Validate') {
            $certificateDisposed = & $module {
                $null -ne $script:SessionStateCertificate -and -not $script:SessionStateCertificate.CanRead
            }
        }
    }
    catch {
        if (Test-ChildCommandNotFound $_) { $commandNotFoundCount++ }
        $failures.Add(('{0}:{1}:{2}' -f $mode, $_.Exception.GetType().FullName, $_.Exception.Message))
    }
    finally {
        if ($null -ne $module) { Remove-Module $module -Force }
    }
}

$module = $null
try {
    $module = Import-Module $ModulePath -Force -PassThru
    $privateExportCount += @(Get-Command -Module $module.Name |
            Where-Object { $privateNames -contains $_.Name }).Count
    $approved = & $module {
        param($eventSink)
        $script:ApprovedEvents = $eventSink
        $script:ApprovedCase = 'success'
        $script:ApprovedPrimary = [System.InvalidOperationException]::new('APPROVED_PRIMARY')
        $script:ApprovedRemoveCounts = [ordered]@{ Success = 0; PrimaryCleanup = 0; CleanupOnly = 0 }

        function script:New-CreatedContainer { return ('e' * 64) }
        function script:Assert-ContainerConfinement {
            if ($script:ApprovedCase -eq 'primary-cleanup') { throw $script:ApprovedPrimary }
        }
        function script:Invoke-Native { param([scriptblock]$Command) }
        function script:Assert-Success { param([string]$Operation) }
        function script:Assert-ContainerCompletion {}
        function script:Remove-OwnedContainer {
            param([string]$ContainerId)
            if ($script:ApprovedCase -eq 'success') {
                $script:ApprovedRemoveCounts.Success++
                return
            }
            if ($script:ApprovedCase -eq 'primary-cleanup') {
                $script:ApprovedRemoveCounts.PrimaryCleanup++
            }
            else {
                $script:ApprovedRemoveCounts.CleanupOnly++
            }
            throw 'NeverReflect approved cleanup credential'
        }

        $plan = [pscustomobject]@{ Arguments = @('create'); Expectation = [pscustomobject]@{} }
        Invoke-ApprovedContainer -ImageId ('sha256:' + ('f' * 64)) -Plan $plan -Operation 'Approved success'

        $script:ApprovedCase = 'primary-cleanup'
        $primaryIdentity = $false
        try {
            Invoke-ApprovedContainer -ImageId ('sha256:' + ('f' * 64)) -Plan $plan -Operation 'Approved primary'
        }
        catch {
            $primaryIdentity = [object]::ReferenceEquals($script:ApprovedPrimary, $_.Exception)
        }

        $script:ApprovedCase = 'cleanup-only'
        $cleanupOnlyError = $null
        try {
            Invoke-ApprovedContainer -ImageId ('sha256:' + ('f' * 64)) -Plan $plan -Operation 'Approved cleanup only'
        }
        catch { $cleanupOnlyError = $_.Exception.Message }

        return [pscustomobject]@{
            SuccessRemovals = $script:ApprovedRemoveCounts.Success
            PrimaryCleanupRemovals = $script:ApprovedRemoveCounts.PrimaryCleanup
            CleanupOnlyRemovals = $script:ApprovedRemoveCounts.CleanupOnly
            PrimaryIdentityPreserved = $primaryIdentity
            CleanupOnlyError = $cleanupOnlyError
        }
    } $events
    $approvedSuccessRemovals = $approved.SuccessRemovals
    $approvedPrimaryCleanupRemovals = $approved.PrimaryCleanupRemovals
    $approvedCleanupOnlyRemovals = $approved.CleanupOnlyRemovals
    $approvedPrimaryIdentityPreserved = $approved.PrimaryIdentityPreserved
    $approvedCleanupOnlyError = $approved.CleanupOnlyError
}
catch {
    if (Test-ChildCommandNotFound $_) { $commandNotFoundCount++ }
    $failures.Add(('Approved:{0}:{1}' -f $_.Exception.GetType().FullName, $_.Exception.Message))
}
finally {
    if ($null -ne $module) { Remove-Module $module -Force }
}

$childEnvironmentAfter = Get-ChildEnvironmentDigest
$result = [ordered]@{
    PowerShellVersion = $PSVersionTable.PSVersion.ToString()
    DispatchCount = $dispatchCount
    SourceCallbackCount = $sourceCallbackCount
    CommandNotFoundCount = $commandNotFoundCount
    PrivateExportCount = $privateExportCount
    CertificateDisposed = $certificateDisposed
    ApprovedSuccessRemovals = $approvedSuccessRemovals
    ApprovedPrimaryCleanupRemovals = $approvedPrimaryCleanupRemovals
    ApprovedCleanupOnlyRemovals = $approvedCleanupOnlyRemovals
    ApprovedPrimaryIdentityPreserved = $approvedPrimaryIdentityPreserved
    ApprovedCleanupOnlyError = $approvedCleanupOnlyError
    DockerShimResolved = $dockerShimResolved
    ChildEnvironmentBefore = $childEnvironmentBefore
    ChildEnvironmentAfter = $childEnvironmentAfter
    Failures = @($failures)
    Events = @($events)
}
[System.IO.File]::WriteAllText(
    $ResultPath,
    ($result | ConvertTo-Json -Depth 6 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
'@
}

function Invoke-SessionStateChild {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-session-state-' + [guid]::NewGuid().ToString('N'))
    $child = Join-Path $root 'session-state-child.ps1'
    $resultPath = Join-Path $root 'result.json'
    $dockerShim = Join-Path $root 'docker.cmd'
    $dockerSentinel = Join-Path $root 'docker-calls.txt'
    $process = $null
    [System.IO.Directory]::CreateDirectory($root) | Out-Null
    try {
        $source = New-SessionStateChildSource
        [System.IO.File]::WriteAllText($child, ($source -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
        $dockerSource = "@echo off`r`n>>`"$dockerSentinel`" echo called`r`nexit /b 97`r`n"
        [System.IO.File]::WriteAllText($dockerShim, $dockerSource, [System.Text.Encoding]::ASCII)
        Assert-Parsed $child

        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = 'powershell.exe'
        $startInfo.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" -ModulePath "{1}" -ResultPath "{2}" -DockerShimDirectory "{3}"' -f `
            $child, $ModulePath, $resultPath, $root
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        if (-not $process.Start()) { throw 'SESSION_STATE_CHILD_START_FAILED' }
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            throw 'SESSION_STATE_CHILD_TIMEOUT'
        }
        $stdoutDetail = $process.StandardOutput.ReadToEnd().Trim()
        $stderrDetail = $process.StandardError.ReadToEnd().Trim()
        if ($process.ExitCode -ne 0 -or -not [System.IO.File]::Exists($resultPath)) {
            throw ('SESSION_STATE_CHILD_FAILED exit={0} stdout={1} stderr={2}' -f $process.ExitCode, $stdoutDetail, $stderrDetail)
        }
        $result = [System.IO.File]::ReadAllText($resultPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        $result | Add-Member -NotePropertyName DockerSentinelCalls `
            -NotePropertyValue $(if ([System.IO.File]::Exists($dockerSentinel)) { @([System.IO.File]::ReadAllLines($dockerSentinel)).Count } else { 0 })
        return $result
    }
    finally {
        $processId = if ($null -ne $process) { $process.Id } else { $null }
        if ($null -ne $process) {
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
        }
        if ([System.IO.Directory]::Exists($root)) {
            [System.IO.Directory]::Delete($root, $true)
        }
        if ([System.IO.Directory]::Exists($root)) { throw 'SESSION_STATE_TEMP_RESIDUE' }
        if ($null -ne $processId -and $null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            throw 'SESSION_STATE_PROCESS_RESIDUE'
        }
    }
}

function Invoke-SessionStateTargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()

    Invoke-TestCase 'D185-D194 production callback session-state' {
        $gitBefore = Get-SessionStateGitSnapshot
        $environmentBefore = Get-SessionStateEnvironmentDigest
        $preparedPath = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ModulePath))) 'infra\keycloak\.local\state\e2e-image-manifest.json'
        $recoveryPath = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ModulePath))) 'infra\keycloak\.local\state\e2e-image-cleanup-required.json'
        $preparedBefore = [System.IO.File]::Exists($preparedPath)
        $recoveryBefore = [System.IO.File]::Exists($recoveryPath)

        $result = Invoke-SessionStateChild

        $gitAfter = Get-SessionStateGitSnapshot
        $environmentAfter = Get-SessionStateEnvironmentDigest
        Assert-True ($result.PowerShellVersion -like '5.1.*') 'Targeted child did not use Windows PowerShell 5.1.'
        Assert-Equal 0 ([int]$result.CommandNotFoundCount) ('Production callback CommandNotFoundException count differs. failures=' + (@($result.Failures) -join '; '))
        Assert-Equal 4 ([int]$result.DispatchCount) 'Not every production mode crossed the exported dispatch boundary.'
        Assert-Equal 3 ([int]$result.SourceCallbackCount) 'Prepare GetSource did not use the actual production boundary twice.'
        Assert-Equal 0 ([int]$result.PrivateExportCount) 'A private production helper was exported.'
        Assert-Equal @(
            'source:Prepare','owner-set:Prepare','source:Prepare','prepare-create-recovery','prepare-build',
            'source:Prepare','receipt-move:Prepare','owner-restore:Prepare',
            'receipt-read','source:Service','owner-set:Service','receipt-move:Service',
            'assert-images:Service','service-child','service-containers','service-project-cleanup',
            'receipt-move:Service','owner-restore:Service',
            'receipt-read','source:Run','owner-set:Run','receipt-move:Run','assert-images:Run',
            'run-browser','full-cleanup:Run','owner-restore:Run',
            'receipt-read','source:Validate','owner-set:Validate','assert-images:Validate',
            'validate-certificate','validate-key-pair','owner-restore:Validate'
        ) @($result.Events) 'Production callback ordering or private helper resolution differs.'
        Assert-True ([bool]$result.CertificateDisposed) 'Validate did not dispose its certificate through the production boundary.'
        Assert-Equal 1 ([int]$result.ApprovedSuccessRemovals) 'Approved-container success cleanup did not reach the safe remover exactly once.'
        Assert-Equal 1 ([int]$result.ApprovedPrimaryCleanupRemovals) 'Approved-container primary cleanup did not reach the safe remover exactly once.'
        Assert-Equal 1 ([int]$result.ApprovedCleanupOnlyRemovals) 'Approved-container cleanup-only path did not reach the safe remover exactly once.'
        Assert-True ([bool]$result.ApprovedPrimaryIdentityPreserved) 'Approved-container cleanup replaced the primary exception object.'
        Assert-Equal 'CONTAINER_CLEANUP_FAILED' ([string]$result.ApprovedCleanupOnlyError) 'Approved-container cleanup-only fixed error changed.'
        Assert-True ([bool]$result.DockerShimResolved) 'The child did not resolve Docker to its safe sentinel.'
        Assert-Equal 0 ([int]$result.DockerSentinelCalls) 'The targeted child invoked Docker.'
        Assert-Equal ([string]$result.ChildEnvironmentBefore) ([string]$result.ChildEnvironmentAfter) 'The targeted child changed its environment.'
        Assert-Equal 0 @($result.Failures).Count ('Production child failures: ' + (@($result.Failures) -join '; '))
        Assert-Equal $environmentBefore $environmentAfter 'The targeted run changed the parent environment.'
        Assert-Equal $gitBefore.Head $gitAfter.Head 'The targeted run changed HEAD.'
        Assert-Equal $gitBefore.Tree $gitAfter.Tree 'The targeted run changed the repository tree.'
        Assert-Equal $gitBefore.Index $gitAfter.Index 'The targeted run changed the index.'
        Assert-Equal $gitBefore.Status $gitAfter.Status 'The targeted run changed repository status.'
        Assert-Equal $preparedBefore ([System.IO.File]::Exists($preparedPath)) 'The targeted run changed prepared receipt state.'
        Assert-Equal $recoveryBefore ([System.IO.File]::Exists($recoveryPath)) 'The targeted run changed recovery receipt state.'
        Write-Output ('EVIDENCE session-state pwsh={0} dispatch=4 get-source=3 command-not-found=0 private-export=0 docker=0 residue=0' -f $result.PowerShellVersion)
    }

    if ($script:Failures.Count -ne 0) {
        Write-Output ('session-state targeted failures: ' + $script:Failures.Count)
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Session-state targeted contract tests passed count=1'
}

function Invoke-FormalTests {
    Invoke-SessionStateTargetedTests
    Invoke-WaitBrowserTargetedTests
    Invoke-OwnerFixTargetedTests
    Invoke-MajorFixTargetedTests
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    $receipt = New-TestReceipt

    Invoke-TestCase 'L1 strict canonical receipt schema' {
        $created = New-E2EReceipt -RunId $receipt.runId -RepositoryId $receipt.repositoryId -CommitSha $receipt.commitSha -TreeSha $receipt.treeSha
        $bytes = [byte[]](ConvertTo-E2EReceiptBytes -Receipt $created)
        $expected = '{"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n"
        Assert-Equal $expected ([System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)) 'Canonical receipt bytes differ.'
        $parsed = ConvertFrom-E2EReceiptBytes -Bytes $bytes
        Assert-Equal 1 $parsed.schemaVersion 'schemaVersion is not integer 1.'
        Assert-True ($parsed.schemaVersion -is [int]) 'schemaVersion CLR type is not Int32.'
        Write-Output 'EVIDENCE canonical receipt round-trip=success'
        $utf8 = [System.Text.UTF8Encoding]::new($false)
        $negativeCases = [System.Collections.Generic.List[object]]::new()
        $negativeCases.Add([pscustomobject]@{ Name = 'duplicate-key'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1,"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'unknown-key'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '","extra":1}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'missing-key'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'reordered-key'; Bytes = [byte[]]($utf8.GetBytes('{"runId":"' + $receipt.runId + '","schemaVersion":1,"repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'trailing-content'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`ntrailing")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'contains-cr'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`r`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'schema-string'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":"1","runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'schema-boolean'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":true,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'schema-floating-point'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":1.0,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $negativeCases.Add([pscustomobject]@{ Name = 'schema-null'; Bytes = [byte[]]($utf8.GetBytes('{"schemaVersion":null,"runId":"' + $receipt.runId + '","repositoryId":"' + $receipt.repositoryId + '","commitSha":"' + $receipt.commitSha + '","treeSha":"' + $receipt.treeSha + '"}' + "`n")); ExpectedError = 'RECEIPT_INVALID' })
        $bomBytes = [byte[]]::new($bytes.Length + 3)
        $bomBytes[0] = 239
        $bomBytes[1] = 187
        $bomBytes[2] = 191
        [System.Array]::Copy($bytes, 0, $bomBytes, 3, $bytes.Length)
        $negativeCases.Add([pscustomobject]@{ Name = 'utf8-bom'; Bytes = $bomBytes; ExpectedError = 'RECEIPT_INVALID' })

        Assert-Equal 11 $negativeCases.Count 'Strict schema case count differs.'
        $negativeSuccessCount = 0
        foreach ($case in $negativeCases) {
            $parserCalls = 0
            $rejected = $false
            $actualError = $null
            try {
                $parserCalls++
                ConvertFrom-E2EReceiptBytes -Bytes $case.Bytes | Out-Null
            }
            catch {
                $rejected = $true
                $actualError = $_.Exception.Message
            }
            Assert-Equal 1 $parserCalls ("Strict schema parser call count differs for case {0}." -f $case.Name)
            Assert-True $rejected ("Strict schema case {0} was accepted." -f $case.Name)
            Assert-Equal $case.ExpectedError $actualError ("Strict schema error differs for case {0}." -f $case.Name)
            $negativeSuccessCount++
            Write-Output ("EVIDENCE strict schema case={0} result=success calls=1" -f $case.Name)
        }
        Assert-Equal 11 $negativeSuccessCount 'Strict schema success count differs.'
        Write-Output 'EVIDENCE strict schema negatives=success count=11'
    }

    Invoke-TestCase 'L1 image references and ownership labels' {
        $images = Get-E2EImageSet -Receipt $receipt
        $suffix = 'e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef'
        Assert-Equal "finguardops-backend:$suffix" $images.Backend 'Backend image reference differs.'
        Assert-Equal "finguardops-ai-service:$suffix" $images.AiService 'AI image reference differs.'
        Assert-Equal "finguardops-playwright-e2e:$suffix" $images.Browser 'Browser image reference differs.'
        $labels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend'
        Assert-Equal @('org.opencontainers.image.revision','com.finguardops.e2e.source-tree','com.finguardops.e2e.run-id','com.finguardops.e2e.repository-id','com.finguardops.e2e.image-role') @($labels.Keys) 'Ownership label order differs.'
        Assert-Equal 'backend' $labels['com.finguardops.e2e.image-role'] 'Image role label differs.'
        Assert-True (-not (@($images.Values) -contains 'finguardops-backend:local')) 'A protected local tag was generated.'
    }

    Invoke-TestCase 'L1 Docker and Compose argv' {
        $compose = New-E2EComposeArguments -ProjectName 'finguardops-keycloak-browser-e2e'
        Assert-True ($compose -contains '--no-build') 'Compose argv omits --no-build.'
        Assert-True ($compose -contains 'never') 'Compose argv omits pull=never.'
        Assert-True (-not ($compose -contains '--build')) 'Compose argv contains raw --build.'
        $build = New-E2EDockerBuildArguments -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef' -Labels (Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend') -Context '.'
        Assert-True ($build -contains '--tag') 'Build argv omits the exact tag.'
        Assert-True (-not ($build -contains 'finguardops-backend:local')) 'Build argv targets a protected local tag.'
        $remove = New-E2EImageRemoveArguments -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef'
        Assert-Equal @('image','rm','--no-prune','finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef') $remove 'Image removal argv differs.'
        Assert-Throws { New-E2EImageRemoveArguments -Reference 'finguardops-backend:local' } 'IMAGE_REFERENCE_INVALID' 'Protected local image removal was accepted.'
    }

    Invoke-TestCase 'L1 primary failure precedence' {
        $primary = [System.InvalidOperationException]::new('PRIMARY')
        $cleanup = [System.InvalidOperationException]::new('CLEANUP')
        Assert-True ([object]::ReferenceEquals($primary, (Select-E2EFailure -Primary $primary -Cleanup $cleanup))) 'Cleanup replaced primary failure.'
        Assert-True ([object]::ReferenceEquals($cleanup, (Select-E2EFailure -Primary $null -Cleanup $cleanup))) 'Dedicated cleanup failure was not selected.'
    }

    Invoke-TestCase 'L1 containment and receipt state transitions' {
        $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-e2e-path-' + [guid]::NewGuid().ToString('N'))
        $outside = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-e2e-outside-' + [guid]::NewGuid().ToString('N'))
        $state = Join-Path $root 'infra\keycloak\.local\state'
        [System.IO.Directory]::CreateDirectory($state) | Out-Null
        [System.IO.Directory]::CreateDirectory($outside) | Out-Null
        $targetProbe = Join-Path $outside 'target-probe.txt'
        $targetContent = 'junction-target-must-remain-unchanged'
        [System.IO.File]::WriteAllText($targetProbe, $targetContent, [System.Text.UTF8Encoding]::new($false))
        $targetFingerprint = (Get-FileHash -LiteralPath $targetProbe -Algorithm SHA256).Hash
        $link = Join-Path $root 'state-link'
        $targetExistsAfterRootCleanup = $false
        $targetContentAfterRootCleanup = $null
        $targetFingerprintAfterRootCleanup = $null
        try {
            $prepared = Join-Path $state 'e2e-image-manifest.json'
            $recovery = Join-Path $state 'e2e-image-cleanup-required.json'
            Assert-E2EPathSafe -Path $prepared -RepositoryRoot $root
            Assert-Throws { Assert-E2EPathSafe -Path (Join-Path $root '..\outside.json') -RepositoryRoot $root } 'RECEIPT_PATH_INVALID' 'Repository escape was accepted.'
            Assert-Equal 'None' (Get-E2EReceiptState -PreparedPath $prepared -RecoveryPath $recovery) 'Empty state differs.'
            [System.IO.File]::WriteAllText($prepared, 'x')
            Assert-Equal 'Prepared' (Get-E2EReceiptState -PreparedPath $prepared -RecoveryPath $recovery) 'Prepared state differs.'
            [System.IO.File]::WriteAllText($recovery, 'x')
            Assert-Throws { Get-E2EReceiptState -PreparedPath $prepared -RecoveryPath $recovery } 'RECEIPT_STATE_INVALID' 'Dual receipt state was accepted.'
            New-Item -ItemType Junction -Path $link -Target $outside | Out-Null
            Assert-Throws { Assert-E2EPathSafe -Path (Join-Path $link 'receipt.json') -RepositoryRoot $root } 'RECEIPT_PATH_INVALID' 'Reparse-point receipt path was accepted.'
        }
        finally {
            if ([System.IO.Directory]::Exists($link)) {
                [System.IO.Directory]::Delete($link, $false)
            }
            if ([System.IO.Directory]::Exists($root)) { [System.IO.Directory]::Delete($root, $true) }
            $targetExistsAfterRootCleanup = [System.IO.File]::Exists($targetProbe)
            if ($targetExistsAfterRootCleanup) {
                $targetContentAfterRootCleanup = [System.IO.File]::ReadAllText($targetProbe, [System.Text.Encoding]::UTF8)
                $targetFingerprintAfterRootCleanup = (Get-FileHash -LiteralPath $targetProbe -Algorithm SHA256).Hash
            }
            if ([System.IO.Directory]::Exists($outside)) { [System.IO.Directory]::Delete($outside, $true) }
        }
        Assert-True $targetExistsAfterRootCleanup 'Deleting the fixture root deleted the junction target.'
        Assert-Equal $targetContent $targetContentAfterRootCleanup 'The junction target content changed.'
        Assert-Equal $targetFingerprint $targetFingerprintAfterRootCleanup 'The junction target fingerprint changed.'
        Assert-True (-not [System.IO.Directory]::Exists($root)) 'Containment fixture root remains.'
        Assert-True (-not [System.IO.Directory]::Exists($outside)) 'Containment fixture target remains.'
        Write-Output 'EVIDENCE reparse target unchanged=true fixture-residue=0'
    }

    Invoke-TestCase 'L2 Prepare verifies source before and after build' {
        $snapshots = [System.Collections.Generic.Queue[object]]::new()
        $snapshots.Enqueue([pscustomobject]$receipt)
        $snapshots.Enqueue([pscustomobject]$receipt)
        $calls = [System.Collections.Generic.List[string]]::new()
        $boundaries = @{
            GetSource = { $snapshots.Dequeue() }
            CreateRecovery = { param($value) $calls.Add('create') }
            BuildImages = { param($value) $calls.Add('build') }
            RenameRecoveryToPrepared = { $calls.Add('rename') }
            Cleanup = { param($value) $calls.Add('cleanup') }
        }
        Invoke-E2EPrepareLifecycle -Receipt $receipt -Boundaries $boundaries
        Assert-Equal @('create','build','rename') @($calls) 'Prepare transition order differs.'
        Assert-Equal 0 $snapshots.Count 'Prepare did not perform both source checks.'

        $changed = New-TestReceipt
        $changed['treeSha'] = 'd' * 40
        $changedSnapshots = [System.Collections.Generic.Queue[object]]::new()
        $changedSnapshots.Enqueue([pscustomobject]$receipt)
        $changedSnapshots.Enqueue([pscustomobject]$changed)
        $changedCalls = [System.Collections.Generic.List[string]]::new()
        $changedBoundaries = @{
            GetSource = { $changedSnapshots.Dequeue() }
            CreateRecovery = { $changedCalls.Add('create') }
            BuildImages = { $changedCalls.Add('build') }
            RenameRecoveryToPrepared = { $changedCalls.Add('rename') }
            Cleanup = { $changedCalls.Add('cleanup') }
        }
        Assert-Throws { Invoke-E2EPrepareLifecycle -Receipt $receipt -Boundaries $changedBoundaries } '^SOURCE_IDENTITY_INVALID$' 'Post-build source change was accepted.'
        Assert-Equal @('create','build','cleanup') @($changedCalls) 'Post-build mismatch cleanup order differs.'
    }

    Invoke-TestCase 'L2 Prepare preserves primary failure when cleanup fails' {
        $boundaries = @{
            GetSource = { [pscustomobject]$receipt }
            CreateRecovery = { }
            BuildImages = { throw 'PRIMARY_BUILD_FAILURE' }
            RenameRecoveryToPrepared = { }
            Cleanup = { throw 'CLEANUP_FAILURE' }
        }
        Assert-Throws { Invoke-E2EPrepareLifecycle -Receipt $receipt -Boundaries $boundaries } '^PRIMARY_BUILD_FAILURE$' 'Prepare primary failure was not preserved.'
    }

    Invoke-TestCase 'L2 Service and Run receipt transitions' {
        $serviceCalls = [System.Collections.Generic.List[string]]::new()
        $serviceBoundaries = @{
            ReadPrepared = { $receipt }
            RenamePreparedToRecovery = { $serviceCalls.Add('to-recovery') }
            AssertImages = { param($value) $serviceCalls.Add('images') }
            RunChild = { param($value) $serviceCalls.Add('child') }
            AssertContainers = { param($value) $serviceCalls.Add('containers') }
            CleanupResources = { $serviceCalls.Add('resources') }
            RenameRecoveryToPrepared = { $serviceCalls.Add('to-prepared') }
            Cleanup = { param($value) $serviceCalls.Add('cleanup') }
        }
        Invoke-E2EServiceLifecycle -Boundaries $serviceBoundaries
        Assert-Equal @('to-recovery','images','child','containers','resources','to-prepared') @($serviceCalls) 'Service transition order differs.'

        $runCalls = [System.Collections.Generic.List[string]]::new()
        $runBoundaries = @{
            ReadPrepared = { $receipt }
            RenamePreparedToRecovery = { $runCalls.Add('to-recovery') }
            AssertImages = { param($value) $runCalls.Add('images') }
            RunBrowser = { param($value) throw 'PRIMARY_BROWSER_FAILURE' }
            Cleanup = { param($value) $runCalls.Add('cleanup') }
        }
        Assert-Throws { Invoke-E2ERunLifecycle -Boundaries $runBoundaries } '^PRIMARY_BROWSER_FAILURE$' 'Run primary failure was not preserved.'
        Assert-True ($runCalls -contains 'cleanup') 'Run failure did not invoke cleanup.'

        $renameCleanupCalls = [System.Collections.Generic.List[string]]::new()
        $renameBoundaries = @{
            ReadPrepared = { $receipt }
            RenamePreparedToRecovery = { throw 'RECEIPT_TRANSITION_FAILED' }
            AssertImages = { }
            RunChild = { }
            AssertContainers = { }
            CleanupResources = { }
            RenameRecoveryToPrepared = { }
            Cleanup = { $renameCleanupCalls.Add('cleanup') }
        }
        Assert-Throws { Invoke-E2EServiceLifecycle -Boundaries $renameBoundaries } '^RECEIPT_TRANSITION_FAILED$' 'Receipt rename failure was ignored.'
        Assert-Equal 0 $renameCleanupCalls.Count 'Cleanup ran after a failed prepared-to-recovery rename.'

        $overlapBoundaries = @{
            ReadPrepared = { $receipt }
            RenamePreparedToRecovery = { }
            AssertImages = { }
            RunBrowser = { throw 'PRIMARY_BROWSER_FAILURE' }
            Cleanup = { throw 'RECEIPT_DELETE_FAILED' }
        }
        Assert-Throws { Invoke-E2ERunLifecycle -Boundaries $overlapBoundaries } '^PRIMARY_BROWSER_FAILURE$' 'Receipt deletion failure replaced the primary browser failure.'
    }

    Invoke-TestCase 'L2 exact cleanup rejects moved mismatched and in-use images' {
        $expected = [pscustomobject]@{ Id = 'sha256:' + ('d' * 64); Labels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend'; InUse = $false }
        Assert-True (Test-E2ECleanupTarget -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef' -ExpectedId $expected.Id -Document $expected -ExpectedLabels $expected.Labels) 'Valid cleanup target was rejected.'
        $moved = [pscustomobject]@{ Id = 'sha256:' + ('e' * 64); Labels = $expected.Labels; InUse = $false }
        Assert-Throws { Test-E2ECleanupTarget -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef' -ExpectedId $expected.Id -Document $moved -ExpectedLabels $expected.Labels } 'IMAGE_OWNERSHIP_INVALID' 'Moved tag was accepted.'
        $wrongLabels = [ordered]@{}
        foreach ($key in $expected.Labels.Keys) { $wrongLabels[$key] = $expected.Labels[$key] }
        $wrongLabels['com.finguardops.e2e.run-id'] = 'ffffffffffffffffffffffffffffffff'
        $mismatch = [pscustomobject]@{ Id = $expected.Id; Labels = $wrongLabels; InUse = $false }
        Assert-Throws { Test-E2ECleanupTarget -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef' -ExpectedId $expected.Id -Document $mismatch -ExpectedLabels $expected.Labels } 'IMAGE_OWNERSHIP_INVALID' 'Label mismatch was accepted.'
        $inUse = [pscustomobject]@{ Id = $expected.Id; Labels = $expected.Labels; InUse = $true }
        Assert-Throws { Test-E2ECleanupTarget -Reference 'finguardops-backend:e2e-bbbbbbbbbbbb-0123456789abcdef0123456789abcdef' -ExpectedId $expected.Id -Document $inUse -ExpectedLabels $expected.Labels } 'IMAGE_IN_USE' 'In-use image was accepted.'
    }

    Invoke-TestCase 'L2 receipt rename and delete failures are cleanup failures' {
        $cleanupCalls = [System.Collections.Generic.List[string]]::new()
        $cleanupBoundaries = @{
            ReadSingleReceipt = { [pscustomobject]@{ Receipt = $receipt; Path = 'e2e-image-cleanup-required.json' } }
            FullCleanup = {
                param($state)
                $cleanupCalls.Add('ownership')
                $cleanupCalls.Add('resources')
                $cleanupCalls.Add('images')
                throw 'RECEIPT_DELETE_FAILURE'
            }
        }
        Assert-Throws { Invoke-E2ECleanupLifecycle -Boundaries $cleanupBoundaries } '^RECEIPT_DELETE_FAILURE$' 'Receipt delete failure was ignored.'
        Assert-Equal @('ownership','resources','images') @($cleanupCalls) 'Cleanup order differs.'
    }

    Invoke-TestCase 'L3 process lock has exactly one winner' {
        Invoke-LockProcessTest
    }

    if ($script:Failures.Count -ne 0) {
        Write-Output ('formal failures: ' + $script:Failures.Count)
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'PowerShell contract tests passed'
}

Assert-Parsed $ModulePath
Assert-Parsed $PSCommandPath
$script:E2EModule = Import-Module $ModulePath -Force -PassThru

if ($Mode -eq 'Preflight') {
    Invoke-FixtureSelfTest
    Invoke-LockProcessTest -UseHarnessModule
    Write-Output 'harness preflight passed'
    exit 0
}

if ($Mode -eq 'MajorFixPreflight') {
    Invoke-MajorFixPreflight
    exit 0
}

if ($Mode -eq 'MajorFixFixture11') {
    Invoke-MajorFixFixture11
    exit 0
}

if ($Mode -eq 'MajorFixTargeted') {
    Invoke-MajorFixTargetedTests
    exit 0
}

if ($Mode -eq 'OwnerFixPreflight') {
    Invoke-OwnerFixPreflight
    exit 0
}

if ($Mode -eq 'OwnerFixTargeted') {
    Invoke-OwnerFixTargetedTests
    exit 0
}

if ($Mode -eq 'WaitBrowserPreflight') {
    Invoke-WaitBrowserPreflight
    exit 0
}

if ($Mode -eq 'WaitBrowserTargeted') {
    Invoke-WaitBrowserTargetedTests
    exit 0
}

if ($Mode -eq 'SessionStateTargeted') {
    Invoke-SessionStateTargetedTests
    exit 0
}

Invoke-FormalTests
