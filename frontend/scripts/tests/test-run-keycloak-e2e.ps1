[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'MajorFixPreflight', 'MajorFixFixture11', 'MajorFixTargeted', 'OwnerFixPreflight', 'OwnerFixTargeted', 'WaitBrowserPreflight', 'WaitBrowserTargeted', 'SessionStateTargeted', 'D209Preflight', 'D209A', 'D209B', 'D225Service', 'D248Targeted', 'CleanupBrowserTargeted', 'D273Targeted', 'D281Targeted', 'Formal')]
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
        [scriptblock]$FinalAudit,
        [scriptblock]$DeleteFile
    )

    if ($null -eq $ResourceCleanup) {
        $ResourceCleanup = { param($receipt) $Markers.Add('resource') }.GetNewClosure()
    }
    if ($null -eq $ImageCleanup) {
        $ImageCleanup = { param($receipt) $Markers.Add('image') }.GetNewClosure()
    }
    if ($null -eq $FinalAudit) {
        $FinalAudit = { param($receipt) $Markers.Add('audit') }.GetNewClosure()
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
            FinalAudit = $FinalAudit
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
                Assert-Equal @('enter','resource','release','dispose') @($markers) 'Top-level cleanup order differs.'
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
                Assert-Equal @('resource','image','audit','receipt') @($markers) "$stateName cleanup order differs."
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
            Assert-Equal @('resource','image','audit','receipt') @($markers) 'Successful actual cleanup order differs.'
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
            Assert-Equal @('resource') @($markers) 'Actual cleanup failure order differs.'
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
            Assert-Equal @('resource','image','audit','receipt') @($markers) 'Receipt deletion failure order differs.'
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
            RemoveProjectResources = { $markers.Add('compose') }.GetNewClosure()
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
        RemoveProjectResources = $compose
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

function Invoke-D209Preflight {
    Assert-Parsed $ModulePath
    Assert-Parsed $PSCommandPath
    $docker = Get-Command docker -ErrorAction Stop
    Assert-True ($null -ne $docker) 'Docker executable lookup failed.'
    Assert-True (-not [System.IO.File]::Exists((Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ModulePath))) 'infra/keycloak/.local/state/e2e-image-cleanup-required.json'))) 'Repository recovery receipt exists.'
    Write-Output 'D209 harness preflight passed; no Docker command invoked'
}

function New-D225ServiceDockerFake {
    param([Parameter(Mandatory = $true)][string]$Root)

    [System.IO.Directory]::CreateDirectory($Root) | Out-Null
    $shim = Join-Path $Root 'docker.cmd'
    $source = Join-Path $Root 'docker-shim.ps1'
    [System.IO.File]::WriteAllText($shim, "@echo off`r`nset `"FINGUARDOPS_D225S_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n", [System.Text.Encoding]::ASCII)
    $fakeSource = @'
$DockerArgs = $env:FINGUARDOPS_D225S_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D225S_ROOT
$events = Join-Path $root 'events.txt'
$line = $DockerArgs -join ' '
[System.IO.File]::AppendAllText($events, $line + "`n")
if ($DockerArgs[0] -eq 'image' -and $DockerArgs[1] -eq 'inspect') {
    $reference = $DockerArgs[-1]
    $identifier = $null
    if ($reference -cmatch '^finguardops-backend:e2e-') { $identifier = 'sha256:' + ('b' * 64) }
    elseif ($reference -cmatch '^finguardops-ai-service:e2e-') { $identifier = 'sha256:' + ('c' * 64) }
    elseif ($reference -cmatch '^finguardops-playwright-e2e:e2e-') { $identifier = 'sha256:' + ('d' * 64) }
    if ($null -eq $identifier -or $env:FINGUARDOPS_D225S_MISSING -eq '1') {
        [Console]::Error.WriteLine('Error: No such image')
        exit 1
    }
    Write-Output (@{ Id = $identifier; Config = @{ Labels = @{} } } | ConvertTo-Json -Depth 5 -Compress)
    exit 0
}
exit 81
'@
    [System.IO.File]::WriteAllText($source, ($fakeSource -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Assert-Parsed $source
    return $shim
}

function New-D225ImageRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Reference,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)]$Labels,
        [Parameter(Mandatory = $true)][string]$Role
    )

    return [pscustomobject]@{ Reference = $Reference; Id = $Id; Labels = $Labels; Role = $Role; InUse = $false }
}

function Copy-D225Labels {
    param([Parameter(Mandatory = $true)]$Labels)

    $copy = [ordered]@{}
    foreach ($key in $Labels.Keys) { $copy[$key] = $Labels[$key] }
    return $copy
}

function Invoke-D225ServiceTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-TestCase 'D225 Service image record preflight precedes child and every mutation' {
        $fixture = New-OwnerFixFixture
        $dockerRoot = Join-Path $fixture.Root 'fake-docker'
        $oldPath = $env:PATH
        $oldRoot = $env:FINGUARDOPS_D225S_ROOT
        $oldMissing = $env:FINGUARDOPS_D225S_MISSING
        try {
            $shim = New-D225ServiceDockerFake -Root $dockerRoot
            $events = Join-Path $dockerRoot 'events.txt'
            $env:FINGUARDOPS_D225S_ROOT = $dockerRoot
            $env:PATH = $dockerRoot + [System.IO.Path]::PathSeparator + $oldPath
            Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'Service preflight Docker fake sentinel was not selected.'

            $receipt = New-TestReceipt
            $refs = Get-E2EImageSet -Receipt $receipt
            $backendLabels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend'
            $aiLabels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'ai-service'
            $browserLabels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'browser'
            $backendId = 'sha256:' + ('b' * 64)
            $aiId = 'sha256:' + ('c' * 64)
            $browserId = 'sha256:' + ('d' * 64)

            # The authoritative identity the production validator resolves for
            # itself, asserted here so the expectations below are known to be
            # the daemon's answer rather than a fixture's opinion.
            $authoritative = & $script:E2EModule { param($value) Get-E2EAuthoritativeImageIdentity -Receipt $value } $receipt
            Assert-Equal $refs.Backend $authoritative.Backend.Reference 'Authoritative backend reference differs.'
            Assert-Equal $backendId $authoritative.Backend.Id 'Authoritative backend image ID differs.'
            Assert-Equal $aiId $authoritative.AiService.Id 'Authoritative ai-service image ID differs.'
            Assert-Equal $browserId $authoritative.Browser.Id 'Authoritative browser image ID differs.'
            Assert-Equal 'browser' $authoritative.Browser.Role 'Authoritative browser role differs.'

            $valid = [ordered]@{
                Backend = New-D225ImageRecord -Reference $refs.Backend -Id $backendId -Labels $backendLabels -Role 'backend'
                AiService = New-D225ImageRecord -Reference $refs.AiService -Id $aiId -Labels $aiLabels -Role 'ai-service'
                Browser = New-D225ImageRecord -Reference $refs.Browser -Id $browserId -Labels $browserLabels -Role 'browser'
            }
            $missingKey = [ordered]@{ Backend = $valid.Backend; AiService = $valid.AiService }
            $unknownKey = [ordered]@{ Backend = $valid.Backend; AiService = $valid.AiService; Other = $valid.Browser }
            $reordered = [ordered]@{ AiService = $valid.AiService; Backend = $valid.Backend; Browser = $valid.Browser }
            $missingProperty = [ordered]@{
                Backend = [pscustomobject]@{ Reference = $refs.Backend; Id = $backendId; Labels = $backendLabels; Role = 'backend' }
                AiService = $valid.AiService
                Browser = $valid.Browser
            }
            $malformedProperty = [ordered]@{
                Backend = New-D225ImageRecord -Reference $refs.Backend -Id 'invalid' -Labels $backendLabels -Role 'backend'
                AiService = $valid.AiService
                Browser = $valid.Browser
            }
            $wrongReference = [ordered]@{
                Backend = New-D225ImageRecord -Reference 'finguardops-backend:local' -Id $backendId -Labels $backendLabels -Role 'backend'
                AiService = $valid.AiService
                Browser = $valid.Browser
            }
            # Well formed, and not the identifier the daemon reports: the exact
            # case a `sha256:` format check accepts.
            $wrongIdentifier = [ordered]@{
                Backend = New-D225ImageRecord -Reference $refs.Backend -Id ('sha256:' + ('9' * 64)) -Labels $backendLabels -Role 'backend'
                AiService = $valid.AiService
                Browser = $valid.Browser
            }
            $wrongLabelValues = Copy-D225Labels -Labels $aiLabels
            $wrongLabelValues['com.finguardops.e2e.run-id'] = ('f' * 32)
            $wrongLabel = [ordered]@{
                Backend = $valid.Backend
                AiService = New-D225ImageRecord -Reference $refs.AiService -Id $aiId -Labels $wrongLabelValues -Role 'ai-service'
                Browser = $valid.Browser
            }
            $wrongRole = [ordered]@{
                Backend = $valid.Backend
                AiService = $valid.AiService
                Browser = New-D225ImageRecord -Reference $refs.Browser -Id $browserId -Labels $browserLabels -Role 'backend'
            }

            $cases = @(
                [pscustomobject]@{ Name = 'clean'; Emit = { $valid }.GetNewClosure(); Good = $true },
                [pscustomobject]@{ Name = 'leading-string'; Emit = { 'NOISE'; $valid }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'trailing-string'; Emit = { $valid; 'NOISE' }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'array-output'; Emit = { Write-Output -NoEnumerate @('NOISE') }; Good = $false },
                [pscustomobject]@{ Name = 'two-records'; Emit = { $valid; $valid }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'wrong-type'; Emit = { 'NOISE' }; Good = $false },
                [pscustomobject]@{ Name = 'unknown-key'; Emit = { $unknownKey }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'missing-key'; Emit = { $missingKey }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'reordered-key'; Emit = { $reordered }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'missing-property'; Emit = { $missingProperty }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'malformed-property'; Emit = { $malformedProperty }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'wrong-reference'; Emit = { $wrongReference }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'non-authoritative-id'; Emit = { $wrongIdentifier }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'wrong-ownership-label'; Emit = { $wrongLabel }.GetNewClosure(); Good = $false },
                [pscustomobject]@{ Name = 'wrong-role'; Emit = { $wrongRole }.GetNewClosure(); Good = $false }
            )

            $observed = [System.Collections.Generic.List[string]]::new()
            $cleanupPath = $fixture.Recovery
            $cleanupRoot = $fixture.Root
            $leaves = @{
                ResourceCleanup = { param($value) $observed.Add('resource-cleanup') }.GetNewClosure()
                ImageCleanup = { param($value) $observed.Add('image-cleanup') }.GetNewClosure()
                FinalAudit = { param($value) $observed.Add('audit') }.GetNewClosure()
                DeleteFile = {
                    param([string]$path)
                    $observed.Add('receipt-delete')
                    [System.IO.File]::Delete($path)
                }.GetNewClosure()
            }
            $cleanup = {
                param($value)
                $observed.Add('cleanup')
                Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $cleanupPath -RepositoryRootPath $cleanupRoot `
                    -LeafBoundaries $leaves -RequireLeafBoundaries
            }.GetNewClosure()

            foreach ($case in $cases) {
                foreach ($path in @($fixture.Prepared, $fixture.Recovery)) {
                    if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
                }
                New-E2EReceiptFile -Path $fixture.Prepared -Receipt $receipt -RepositoryRoot $fixture.Root
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                $observed.Clear()
                $boundaries = @{
                    ReadPrepared = { return $receipt }.GetNewClosure()
                    RenamePreparedToRecovery = {
                        Move-E2EReceiptFile -Source $fixture.Prepared -Destination $fixture.Recovery -RepositoryRoot $fixture.Root
                    }.GetNewClosure()
                    AssertImages = $case.Emit
                    AssertBrowserRuntime = { param($value) $observed.Add('browser-runtime') }.GetNewClosure()
                    RunChild = { param($value) $observed.Add('child') }.GetNewClosure()
                    AssertContainers = { param($value) $observed.Add('containers') }.GetNewClosure()
                    CleanupResources = { $observed.Add('resources') }.GetNewClosure()
                    RenameRecoveryToPrepared = {
                        Move-E2EReceiptFile -Source $fixture.Recovery -Destination $fixture.Prepared -RepositoryRoot $fixture.Root
                    }.GetNewClosure()
                    Cleanup = $cleanup
                }
                $failure = Get-CapturedException { Invoke-E2EServiceLifecycle -Boundaries $boundaries }
                $commands = @([System.IO.File]::ReadAllLines($events))
                if ($case.Good) {
                    $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                    Assert-True ($null -eq $failure) "Clean Service record failed: $detail"
                    Assert-Equal @('browser-runtime', 'child', 'containers', 'resources') @($observed) 'Clean Service order differs.'
                    Assert-True ([System.IO.File]::Exists($fixture.Prepared)) 'Clean Service did not restore the prepared receipt.'
                }
                else {
                    Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                    Assert-Equal 'IMAGE_RECORD_INVALID' $failure.Message "$($case.Name) returned the wrong fixed error."
                    Assert-Equal @() @($observed) "$($case.Name) ran a browser runtime check, a child, a container check or a cleanup."
                    Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) did not preserve the recovery receipt."
                    Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                }
                Assert-True (@($commands | Where-Object {
                    $_ -cmatch '^(stop|start|rm|create|run|kill|restart|pause|unpause) ' -or
                    $_ -cmatch '^(network|volume|image|container|system|builder) (rm|prune|remove|create)' -or
                    $_ -cmatch ' down( |$)' -or $_ -cmatch '--force|--volumes|--remove-orphans|prune'
                }).Count -eq 0) ("$($case.Name) issued a mutation-capable Docker command: " + ($commands -join ';'))
                Assert-True (@($commands | Where-Object { $_ -cnotmatch '^image inspect ' }).Count -eq 0) `
                    ("$($case.Name) issued a Docker command other than a read-only image inspect: " + ($commands -join ';'))
            }

            # A prepared image the daemon cannot answer for is a preflight
            # failure too, and must not reach cleanup either.
            foreach ($path in @($fixture.Prepared, $fixture.Recovery)) {
                if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
            }
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt $receipt -RepositoryRoot $fixture.Root
            $observed.Clear()
            $env:FINGUARDOPS_D225S_MISSING = '1'
            $boundaries = @{
                ReadPrepared = { return $receipt }.GetNewClosure()
                RenamePreparedToRecovery = {
                    Move-E2EReceiptFile -Source $fixture.Prepared -Destination $fixture.Recovery -RepositoryRoot $fixture.Root
                }.GetNewClosure()
                AssertImages = { $valid }.GetNewClosure()
                AssertBrowserRuntime = { param($value) $observed.Add('browser-runtime') }.GetNewClosure()
                RunChild = { param($value) $observed.Add('child') }.GetNewClosure()
                AssertContainers = { param($value) $observed.Add('containers') }.GetNewClosure()
                CleanupResources = { $observed.Add('resources') }.GetNewClosure()
                RenameRecoveryToPrepared = {}
                Cleanup = $cleanup
            }
            $failure = Get-CapturedException { Invoke-E2EServiceLifecycle -Boundaries $boundaries }
            $env:FINGUARDOPS_D225S_MISSING = $null
            Assert-True ($null -ne $failure) 'A missing prepared image was accepted.'
            Assert-Equal 'IMAGE_RECORD_INVALID' $failure.Message 'A missing prepared image returned the wrong fixed error.'
            Assert-Equal @() @($observed) 'A missing prepared image ran a browser runtime check, a child or a cleanup.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'A missing prepared image removed the receipt.'

            # Only after the child does a primary failure reach the approved
            # cleanup arbitration, and the primary identity survives it.
            foreach ($path in @($fixture.Prepared, $fixture.Recovery)) {
                if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
            }
            New-E2EReceiptFile -Path $fixture.Prepared -Receipt $receipt -RepositoryRoot $fixture.Root
            $observed.Clear()
            $primary = [System.InvalidOperationException]::new('SERVICE_CHILD_PRIMARY')
            $secondaryLeaves = @{
                ResourceCleanup = { param($value) $observed.Add('resource-cleanup'); throw 'NeverReflect C:\sensitive\resource credential' }.GetNewClosure()
                ImageCleanup = { param($value) $observed.Add('image-cleanup') }.GetNewClosure()
                FinalAudit = { param($value) $observed.Add('audit') }.GetNewClosure()
                DeleteFile = { param([string]$path) $observed.Add('receipt-delete'); [System.IO.File]::Delete($path) }.GetNewClosure()
            }
            $boundaries = @{
                ReadPrepared = { return $receipt }.GetNewClosure()
                RenamePreparedToRecovery = {
                    Move-E2EReceiptFile -Source $fixture.Prepared -Destination $fixture.Recovery -RepositoryRoot $fixture.Root
                }.GetNewClosure()
                AssertImages = { $valid }.GetNewClosure()
                AssertBrowserRuntime = { param($value) $observed.Add('browser-runtime') }.GetNewClosure()
                RunChild = { param($value) $observed.Add('child'); throw $primary }.GetNewClosure()
                AssertContainers = { param($value) $observed.Add('containers') }.GetNewClosure()
                CleanupResources = { $observed.Add('resources') }.GetNewClosure()
                RenameRecoveryToPrepared = {}
                Cleanup = {
                    param($value)
                    $observed.Add('cleanup')
                    Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $cleanupPath -RepositoryRootPath $cleanupRoot `
                        -LeafBoundaries $secondaryLeaves -RequireLeafBoundaries
                }.GetNewClosure()
            }
            $failure = Get-CapturedException { Invoke-E2EServiceLifecycle -Boundaries $boundaries }
            Assert-True ([object]::ReferenceEquals($primary, $failure)) 'Service child primary identity was replaced.'
            Assert-Equal @('browser-runtime', 'child', 'cleanup', 'resource-cleanup') @($observed) 'Service child failure cleanup order differs.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'Resource cleanup failure removed the receipt.'
            Assert-NoRawCleanupDetail $failure 'Service child failure reflected a cleanup detail.'
        }
        finally {
            $env:PATH = $oldPath
            $env:FINGUARDOPS_D225S_ROOT = $oldRoot
            $env:FINGUARDOPS_D225S_MISSING = $oldMissing
            Remove-OwnerFixFixtureRoot $fixture.Root
        }
    }
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D225 Service targeted passed'
}

function Invoke-D209ATests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-TestCase 'D209 A production image records consume browser native stdout' {
        $receipt = New-TestReceipt
        $result = & $script:E2EModule {
            param($activeReceipt)
            function script:Get-PlaywrightVersion { return '1.62.1' }
            function script:Assert-BrowserImage { return ('sha256:' + ('3' * 64)) }
            function script:Get-LocalImageDocument {
                param([string]$Reference)
                $role = if ($Reference -match 'backend:') { 'backend' } elseif ($Reference -match 'ai-service:') { 'ai-service' } else { 'browser' }
                $id = switch ($role) { 'backend' { '1' * 64 } 'ai-service' { '2' * 64 } default { '3' * 64 } }
                return [pscustomobject]@{ Id = ('sha256:' + $id); Config = [pscustomobject]@{ Labels = [pscustomobject](Get-E2EOwnershipLabels -Receipt $activeReceipt -Role $role) } }
            }
            function script:Invoke-ApprovedContainer {
                param([string]$ImageId, $Plan, [string]$Operation)
                return 'BROWSER_RUNTIME_VERIFIED'
            }
            $values = @(Assert-E2EOwnedImages -Receipt $activeReceipt)
            $script:D209Mismatch = ''
            $script:D209Discovery = ''
            function script:Invoke-NativeStdout {
                param([scriptblock]$Command)
                $script:D209Service = [string](Get-Variable -Scope 1 -Name service -ValueOnly)
                $script:D209Discovery = $Command.ToString()
                $global:LASTEXITCODE = 0
                $full = 'f' * 64
                # The daemon's answer, in each of the shapes a container
                # discovery can honestly or dishonestly come back in.
                switch ($script:D209Mismatch) {
                    'short' { return $full.Substring(0, 12) }
                    'prefix' { return $full.Substring(0, 63) }
                    'upper' { return $full.ToUpperInvariant() }
                    'duplicate' { return ($full + "`n" + $full) }
                    'ambiguous' { return ($full + "`n" + ('9' * 64)) }
                    default { return $full }
                }
            }
            function script:Get-ContainerDocument {
                param([string]$ContainerId)
                $refs = Get-E2EImageSet -Receipt $activeReceipt
                $role = if ($script:D209Service -eq 'backend') { 'backend' } else { 'ai-service' }
                $reference = if ($role -eq 'backend') { $refs.Backend } else { $refs.AiService }
                $id = if ($role -eq 'backend') { 'sha256:' + ('1' * 64) } else { 'sha256:' + ('2' * 64) }
                if ($script:D209Mismatch -eq 'reference') { $reference = 'wrong:reference' }
                if ($script:D209Mismatch -eq 'id') { $id = 'sha256:' + ('9' * 64) }
                $documentId = if ($script:D209Mismatch -eq 'document-id') { '9' * 64 } else { $ContainerId }
                return [pscustomobject]@{ Id=$documentId; Config=[pscustomobject]@{ Image=$reference }; Image=$id }
            }
            Assert-E2EContainerImages -Receipt $activeReceipt -Project 'fixture-project'
            $script:D209Mismatch = 'reference'
            $referenceFailure = try { Assert-E2EContainerImages -Receipt $activeReceipt -Project 'fixture-project'; '' } catch { $_.Exception.Message }
            $script:D209Mismatch = 'id'
            $idFailure = try { Assert-E2EContainerImages -Receipt $activeReceipt -Project 'fixture-project'; '' } catch { $_.Exception.Message }
            $identifierFailures = [ordered]@{}
            foreach ($shape in @('short', 'prefix', 'upper', 'duplicate', 'ambiguous', 'document-id')) {
                $script:D209Mismatch = $shape
                $identifierFailures[$shape] = try { Assert-E2EContainerImages -Receipt $activeReceipt -Project 'fixture-project'; '' } catch { $_.Exception.Message }
            }
            $script:D209Mismatch = ''
            $discovery = $script:D209Discovery
            $script:D209Receipt = $activeReceipt
            function script:Get-E2EPreparedReceipt { return $script:D209Receipt }
            function script:Assert-E2ESourceMatchesReceipt {}
            function script:Assert-SafeCertificate { return [System.IO.MemoryStream]::new() }
            function script:Assert-CertificateKeyPair { param($BrowserImageId) if ($BrowserImageId -cne ('sha256:' + ('3' * 64))) { throw 'WRONG_BROWSER_ID' } }
            Invoke-E2EValidateMode | Out-Null
            $script:D209Records = $values[0]
            function script:Assert-E2EOwnedImages { return 'BROWSER_RUNTIME_VERIFIED'; return $script:D209Records }
            $containerPollution = try { Assert-E2EContainerImages -Receipt $activeReceipt -Project 'fixture-project'; '' } catch { $_.Exception.Message }
            $validatePollution = try { Invoke-E2EValidateMode | Out-Null; '' } catch { $_.Exception.Message }
            return [pscustomobject]@{ Values=$values; ReferenceFailure=$referenceFailure; IdFailure=$idFailure; ContainerPollution=$containerPollution; ValidatePollution=$validatePollution; IdentifierFailures=$identifierFailures; Discovery=$discovery }
        } $receipt
        Assert-Equal 1 $result.Values.Count 'Image record return cardinality differs.'
        $records = $result.Values[0]
        Assert-True ($records -is [System.Collections.Specialized.OrderedDictionary]) 'Image record return type differs.'
        Assert-Equal @('Backend','AiService','Browser') @($records.Keys) 'Image record keys differ.'
        Assert-Equal 'backend' $records.Backend.Role 'Backend record access failed.'
        Assert-Equal 'ai-service' $records.AiService.Role 'AiService record access failed.'
        Assert-Equal 'browser' $records.Browser.Role 'Browser record access failed.'
        foreach ($key in @('Backend','AiService','Browser')) {
            $record = $records[$key]
            Assert-True ($record -is [pscustomobject]) "$key record type differs."
            Assert-Equal @('Reference','Id','Labels','Role','InUse') @($record.PSObject.Properties.Name) "$key record properties differ."
            Assert-True ($record.Reference -is [string] -and $record.Id -match '^sha256:[0-9a-f]{64}$' -and
                $record.Labels -is [System.Collections.IDictionary] -and $record.InUse -is [bool]) "$key record property type differs."
        }
        Assert-True (@($result.Values | Where-Object { $_ -is [string] }).Count -eq 0) 'Native stdout contaminated image records.'
        Assert-Equal 'CONTAINER_OWNERSHIP_INVALID' $result.ReferenceFailure '.Config.Image mismatch was accepted.'
        Assert-Equal 'CONTAINER_OWNERSHIP_INVALID' $result.IdFailure '.Image mismatch was accepted.'
        Assert-True ($result.Discovery -cmatch '--no-trunc') 'Container discovery does not ask for full identifiers.'
        foreach ($shape in @('short', 'prefix', 'upper', 'duplicate', 'ambiguous', 'document-id')) {
            Assert-Equal 'CONTAINER_OWNERSHIP_INVALID' $result.IdentifierFailures[$shape] "An abbreviated or ambiguous identifier was accepted: $shape"
        }
        Assert-Equal 'IMAGE_RECORD_INVALID' $result.ContainerPollution 'Container consumer accepted record contamination.'
        Assert-Equal 'IMAGE_RECORD_INVALID' $result.ValidatePollution 'Validate consumer accepted record contamination.'
        $records.Backend.Id = 'invalid-id'
        $badRecord = Get-CapturedException { & $script:E2EModule { param($value,$activeReceipt) Assert-E2EImageRecordSet -Values @($value) -Receipt $activeReceipt } $records $receipt }
        Assert-Equal 'IMAGE_RECORD_INVALID' $badRecord.Message 'Malformed image ID was accepted.'
    }
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D209 A targeted passed'
}


# --- D248 browser runtime boundary, browser cleanup and dead code -----------
#
# The fake below is a Docker daemon and nothing else: it answers `ps`,
# `container inspect`, `stop` and `rm` for one container whose whole state it
# keeps in files, and it records every argument vector it was given. Every
# decision about whether that container may be stopped or removed is left to
# the production module.
function New-D248BrowserDockerFake {
    param([Parameter(Mandatory = $true)][string]$Root)

    [System.IO.Directory]::CreateDirectory($Root) | Out-Null
    $shim = Join-Path $Root 'docker.cmd'
    $source = Join-Path $Root 'docker-shim.ps1'
    [System.IO.File]::WriteAllText($shim, "@echo off`r`nset `"FINGUARDOPS_D248_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n", [System.Text.Encoding]::ASCII)
    $fakeSource = @'
$DockerArgs = $env:FINGUARDOPS_D248_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D248_ROOT
$events = Join-Path $root 'events.txt'
$state = Join-Path $root 'container-state.txt'
$line = $DockerArgs -join ' '
[System.IO.File]::AppendAllText($events, $line + "`n")
$id = 'b' * 64
$imageId = 'sha256:' + ('d' * 64)
$reportedId = if ($env:FINGUARDOPS_D248_SWAP_ID -eq '1') { 'c' * 64 } else { $id }
$reportedImage = if ($env:FINGUARDOPS_D248_SWAP_IMAGE -eq '1') { 'sha256:' + ('9' * 64) } else { $imageId }
$present = [System.IO.File]::Exists($state)
$status = if ($present) { [System.IO.File]::ReadAllText($state) } else { '' }

if ($DockerArgs[0] -eq 'ps') {
    $wanted = $null
    foreach ($token in $DockerArgs) {
        if ($token -cmatch '^id=([0-9a-f]{64})$') { $wanted = $Matches[1] }
    }
    if ($null -eq $wanted) { exit 81 }
    if ($present -and $wanted -ceq $id) { Write-Output $id }
    exit 0
}
if ($DockerArgs[0] -eq 'container' -and $DockerArgs[1] -eq 'inspect') {
    $target = $DockerArgs[-1]
    if (-not $present -or $target -cne $id) { exit 1 }
    $mounts = @([ordered]@{ Type = 'bind'; Source = 'C:\fixture\scripts'; Destination = '/finguardops/scripts' })
    if ($env:FINGUARDOPS_D248_VOLUME_MOUNT -eq '1') {
        $mounts += , ([ordered]@{ Type = 'volume'; Name = 'unexpected-volume'; Destination = '/data' })
    }
    $document = [ordered]@{
        Id = $reportedId
        Image = $reportedImage
        Mounts = $mounts
        State = [ordered]@{ Status = $status; Running = ($status -ceq 'running') }
    }
    Write-Output ($document | ConvertTo-Json -Depth 8 -Compress)
    exit 0
}
if ($DockerArgs[0] -eq 'stop') {
    $target = $DockerArgs[1]
    if (-not $present -or $target -cne $id) { exit 81 }
    if ($env:FINGUARDOPS_D248_FAIL -eq 'stop') { exit 17 }
    [System.IO.File]::WriteAllText($state, 'exited')
    Write-Output $target
    exit 0
}
if ($DockerArgs[0] -eq 'rm' -and $DockerArgs[1] -cnotmatch '^-') {
    $target = $DockerArgs[1]
    if (-not $present -or $target -cne $id) { exit 81 }
    if ($env:FINGUARDOPS_D248_FAIL -eq 'rm') { exit 17 }
    [System.IO.File]::Delete($state)
    Write-Output $target
    exit 0
}
exit 81
'@
    [System.IO.File]::WriteAllText($source, ($fakeSource -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Assert-Parsed $source
    return $shim
}

function Get-D248DockerCommands([string]$Path) {
    if (-not [System.IO.File]::Exists($Path)) { return @() }
    return @([System.IO.File]::ReadAllLines($Path) | Where-Object { $_ })
}

function Assert-D248NoForcedRemoval($Commands, [string]$Message) {
    Assert-True (@($Commands | Where-Object {
        $_ -cmatch '(^|\s)--force(\s|$)' -or $_ -cmatch '(^|\s)--volumes(\s|$)' -or
        ($_ -cmatch '^(stop|rm|network rm|volume rm|image rm) ' -and $_ -cmatch '(^|\s)-f(\s|$)')
    }).Count -eq 0) ($Message + ' commands=' + ($Commands -join ';'))
}

function Invoke-D248ServiceBoundaryTests {
    Invoke-TestCase 'D248 Service browser runtime runs only on a validated record' {
        $fixture = New-OwnerFixFixture
        $dockerRoot = Join-Path $fixture.Root 'fake-docker'
        $oldPath = $env:PATH
        $oldRoot = $env:FINGUARDOPS_D225S_ROOT
        try {
            $shim = New-D225ServiceDockerFake -Root $dockerRoot
            $events = Join-Path $dockerRoot 'events.txt'
            $env:FINGUARDOPS_D225S_ROOT = $dockerRoot
            $env:PATH = $dockerRoot + [System.IO.Path]::PathSeparator + $oldPath
            Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D248 Service Docker fake sentinel was not selected.'

            $receipt = New-TestReceipt
            $refs = Get-E2EImageSet -Receipt $receipt
            $valid = [ordered]@{
                Backend = New-D225ImageRecord -Reference $refs.Backend -Id ('sha256:' + ('b' * 64)) -Labels (Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend') -Role 'backend'
                AiService = New-D225ImageRecord -Reference $refs.AiService -Id ('sha256:' + ('c' * 64)) -Labels (Get-E2EOwnershipLabels -Receipt $receipt -Role 'ai-service') -Role 'ai-service'
                Browser = New-D225ImageRecord -Reference $refs.Browser -Id ('sha256:' + ('d' * 64)) -Labels (Get-E2EOwnershipLabels -Receipt $receipt -Role 'browser') -Role 'browser'
            }
            $wrongIdentifier = [ordered]@{
                Backend = New-D225ImageRecord -Reference $refs.Backend -Id ('sha256:' + ('9' * 64)) -Labels (Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend') -Role 'backend'
                AiService = $valid.AiService
                Browser = $valid.Browser
            }

            $observed = [System.Collections.Generic.List[string]]::new()
            $cleanupPath = $fixture.Recovery
            $cleanupRoot = $fixture.Root
            $leaves = @{
                ResourceCleanup = { param($value) $observed.Add('resource-cleanup') }.GetNewClosure()
                ImageCleanup = { param($value) $observed.Add('image-cleanup') }.GetNewClosure()
                FinalAudit = { param($value) $observed.Add('audit') }.GetNewClosure()
                DeleteFile = {
                    param([string]$path)
                    $observed.Add('receipt-delete')
                    [System.IO.File]::Delete($path)
                }.GetNewClosure()
            }
            $cleanup = {
                param($value)
                $observed.Add('cleanup')
                Invoke-E2EFullCleanup -Receipt $value -ReceiptPath $cleanupPath -RepositoryRootPath $cleanupRoot `
                    -LeafBoundaries $leaves -RequireLeafBoundaries
            }.GetNewClosure()
            # The production browser runtime preflight itself, not a stand-in
            # for it. The fake daemon knows nothing about the pinned Playwright
            # base image, so this boundary fails - which is the point: what is
            # being measured is what it did before it failed.
            $browserRuntime = & $script:E2EModule {
                return { param($value) Assert-E2EPreparedBrowserRuntime -Receipt $value }
            }
            $baseInspect = 'image inspect --format "{{json .}}" ' + (& $script:E2EModule { return $BrowserBaseImage })

            $cases = @(
                [pscustomobject]@{ Name = 'non-authoritative-id'; Emit = { $wrongIdentifier }.GetNewClosure(); Valid = $false },
                [pscustomobject]@{ Name = 'trailing-string'; Emit = { $valid; 'NOISE' }.GetNewClosure(); Valid = $false },
                [pscustomobject]@{ Name = 'wrong-type'; Emit = { 'NOISE' }; Valid = $false },
                [pscustomobject]@{ Name = 'validated-record'; Emit = { $valid }.GetNewClosure(); Valid = $true }
            )
            foreach ($case in $cases) {
                foreach ($path in @($fixture.Prepared, $fixture.Recovery)) {
                    if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) }
                }
                New-E2EReceiptFile -Path $fixture.Prepared -Receipt $receipt -RepositoryRoot $fixture.Root
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                $observed.Clear()
                $boundaries = @{
                    ReadPrepared = { return $receipt }.GetNewClosure()
                    RenamePreparedToRecovery = {
                        Move-E2EReceiptFile -Source $fixture.Prepared -Destination $fixture.Recovery -RepositoryRoot $fixture.Root
                    }.GetNewClosure()
                    AssertImages = $case.Emit
                    AssertBrowserRuntime = $browserRuntime
                    RunChild = { param($value) $observed.Add('child') }.GetNewClosure()
                    AssertContainers = { param($value) $observed.Add('containers') }.GetNewClosure()
                    CleanupResources = { $observed.Add('resources') }.GetNewClosure()
                    RenameRecoveryToPrepared = {
                        Move-E2EReceiptFile -Source $fixture.Recovery -Destination $fixture.Prepared -RepositoryRoot $fixture.Root
                    }.GetNewClosure()
                    Cleanup = $cleanup
                }
                $failure = Get-CapturedException { Invoke-E2EServiceLifecycle -Boundaries $boundaries }
                $commands = Get-D248DockerCommands $events
                $runtimeCalls = @($commands | Where-Object { $_ -ceq $baseInspect })

                Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                # Nothing after the preflight ran, whichever preflight refused.
                Assert-Equal @() @($observed) "$($case.Name) ran a child, a container check or a cleanup."
                Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) did not preserve the recovery receipt."
                Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                Assert-True (@($commands | Where-Object {
                    $_ -cmatch '^(stop|start|rm|create|run|kill|restart|pause|unpause) ' -or
                    $_ -cmatch '^(network|volume|image|container|system|builder) (rm|prune|remove|create)' -or
                    $_ -cmatch ' down( |$)'
                }).Count -eq 0) ("$($case.Name) reached a mutation-capable Docker command: " + ($commands -join ';'))
                Assert-D248NoForcedRemoval $commands "$($case.Name) used a forced removal."

                if ($case.Valid) {
                    # The record was accepted, so the browser runtime preflight
                    # ran - exactly once, and only after the three reads the
                    # authoritative validator makes.
                    Assert-Equal 1 $runtimeCalls.Count 'A validated record did not reach the browser runtime preflight exactly once.'
                    $index = [array]::IndexOf(@($commands), $baseInspect)
                    Assert-Equal 3 $index 'The browser runtime preflight did not run after the authoritative validator.'
                    Assert-True (@($commands[0..2] | Where-Object { $_ -cnotmatch '^image inspect --format "\{\{json \.\}\}" finguardops-' }).Count -eq 0) `
                        ('The authoritative validator asked something other than a prepared image read: ' + ($commands -join ';'))
                }
                else {
                    Assert-Equal 'IMAGE_RECORD_INVALID' $failure.Message "$($case.Name) returned the wrong fixed error."
                    Assert-Equal 0 $runtimeCalls.Count "$($case.Name) reached the browser runtime preflight."
                }
            }
        }
        finally {
            $env:PATH = $oldPath
            $env:FINGUARDOPS_D225S_ROOT = $oldRoot
            Remove-OwnerFixFixtureRoot $fixture.Root
        }
    }
}

function Invoke-D248BrowserCleanupTests {
    Invoke-TestCase 'D248 Run browser cleanup stops and removes one exact container' {
        $fixture = New-OwnerFixFixture
        $dockerRoot = Join-Path $fixture.Root 'fake-docker'
        $state = Join-Path $dockerRoot 'container-state.txt'
        $events = Join-Path $dockerRoot 'events.txt'
        $id = 'b' * 64
        $imageId = 'sha256:' + ('d' * 64)
        $names = @('FINGUARDOPS_D248_ROOT', 'FINGUARDOPS_D248_FAIL', 'FINGUARDOPS_D248_SWAP_ID',
            'FINGUARDOPS_D248_SWAP_IMAGE', 'FINGUARDOPS_D248_VOLUME_MOUNT')
        $previous = @{}
        foreach ($name in $names) { $previous[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process') }
        $oldPath = $env:PATH
        try {
            $shim = New-D248BrowserDockerFake -Root $dockerRoot
            foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
            $env:FINGUARDOPS_D248_ROOT = $dockerRoot
            $env:PATH = $dockerRoot + [System.IO.Path]::PathSeparator + $oldPath
            Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D248 browser Docker fake sentinel was not selected.'

            $presence = 'ps -a --no-trunc --filter id=' + $id + ' --format {{.ID}}'
            $inspect = 'container inspect --format "{{json .}}" ' + $id
            $remove = & $script:E2EModule {
                return { param($container, $image) Remove-OwnedContainer $container $image }
            }

            $cases = @(
                [pscustomobject]@{
                    Name = 'running-container'
                    Start = 'running'
                    Env = @{}
                    Succeeds = $true
                    Expected = @($presence, $inspect, ('stop ' + $id), $inspect, ('rm ' + $id), $presence)
                    Gone = $true
                },
                [pscustomobject]@{
                    Name = 'already-stopped-container'
                    Start = 'exited'
                    Env = @{}
                    Succeeds = $true
                    Expected = @($presence, $inspect, ('rm ' + $id), $presence)
                    Gone = $true
                },
                [pscustomobject]@{
                    Name = 'already-absent-container'
                    Start = ''
                    Env = @{}
                    Succeeds = $true
                    Expected = @($presence)
                    Gone = $true
                },
                [pscustomobject]@{
                    Name = 'unexpected-volume-mount'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D248_VOLUME_MOUNT = '1' }
                    Succeeds = $false
                    Expected = @($presence, $inspect)
                    Gone = $false
                },
                [pscustomobject]@{
                    Name = 'replaced-container-identifier'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D248_SWAP_ID = '1' }
                    Succeeds = $false
                    Expected = @($presence, $inspect)
                    Gone = $false
                },
                [pscustomobject]@{
                    Name = 'replaced-container-image'
                    Start = 'running'
                    Env = @{ FINGUARDOPS_D248_SWAP_IMAGE = '1' }
                    Succeeds = $false
                    Expected = @($presence, $inspect)
                    Gone = $false
                },
                [pscustomobject]@{
                    Name = 'stop-failure'
                    Start = 'running'
                    Env = @{ FINGUARDOPS_D248_FAIL = 'stop' }
                    Succeeds = $false
                    Expected = @($presence, $inspect, ('stop ' + $id))
                    Gone = $false
                },
                [pscustomobject]@{
                    Name = 'remove-failure'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D248_FAIL = 'rm' }
                    Succeeds = $false
                    Expected = @($presence, $inspect, ('rm ' + $id))
                    Gone = $false
                }
            )

            foreach ($case in $cases) {
                foreach ($name in $names) {
                    if ($name -cne 'FINGUARDOPS_D248_ROOT') { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
                }
                foreach ($name in $case.Env.Keys) { [System.Environment]::SetEnvironmentVariable($name, $case.Env[$name], 'Process') }
                if ([System.IO.File]::Exists($state)) { [System.IO.File]::Delete($state) }
                if ($case.Start -ne '') { [System.IO.File]::WriteAllText($state, $case.Start, [System.Text.Encoding]::ASCII) }
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)

                $failure = Get-CapturedException { & $remove $id $imageId }
                $commands = Get-D248DockerCommands $events
                if ($case.Succeeds) {
                    $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                    Assert-True ($null -eq $failure) "$($case.Name) failed: $detail"
                }
                else {
                    Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                    Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                }
                Assert-Equal @($case.Expected) @($commands) "$($case.Name) argument vectors differ."
                Assert-D248NoForcedRemoval $commands "$($case.Name) used a forced removal."
                Assert-True (@($commands | Where-Object { $_ -cmatch '^(stop|rm) ' -and $_ -cnotmatch ('^(stop|rm) ' + $id + '$') }).Count -eq 0) `
                    ("$($case.Name) named something other than the exact full identifier: " + ($commands -join ';'))
                Assert-Equal $case.Gone (-not [System.IO.File]::Exists($state)) "$($case.Name) container residue differs."
            }

            # A browser cleanup failure that coincides with a primary failure
            # leaves the primary exception object exactly as it was.
            [System.IO.File]::WriteAllText($state, 'exited', [System.Text.Encoding]::ASCII)
            $env:FINGUARDOPS_D248_VOLUME_MOUNT = '1'
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $primary = [System.InvalidOperationException]::new('RUN_PRIMARY_FAILURE')
            $order = [System.Collections.Generic.List[string]]::new()
            $boundaries = @{
                RestoreOutputEnvironment = { $order.Add('output-env') }.GetNewClosure()
                RemoveBrowser = { $order.Add('browser'); & $remove $id $imageId }.GetNewClosure()
                RemoveProjectResources = { $order.Add('resources') }.GetNewClosure()
                ReleaseRunMutex = { $order.Add('mutex') }.GetNewClosure()
            }
            $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $primary -Boundaries $boundaries }
            Assert-True ([object]::ReferenceEquals($primary, $failure)) 'A browser cleanup failure replaced the primary exception object.'
            Assert-Equal @('output-env', 'browser', 'resources', 'mutex') @($order) 'Run cleanup boundary order differs.'
            Assert-NoRawCleanupDetail $failure 'The overlapping failure reflected a cleanup detail.'
            Assert-D248NoForcedRemoval (Get-D248DockerCommands $events) 'The overlapping failure used a forced removal.'

            # And a browser cleanup failure on its own is the fixed cleanup code.
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $order.Clear()
            $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $null -Boundaries $boundaries }
            Assert-True ($null -ne $failure) 'A browser cleanup failure alone was ignored.'
            Assert-Equal 'BROWSER_CONTAINER_CLEANUP_FAILED' $failure.Message 'The browser cleanup fixed error changed.'
            Assert-True ([System.IO.File]::Exists($state)) 'A refused browser cleanup removed the container anyway.'
        }
        finally {
            $env:PATH = $oldPath
            foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
            Remove-OwnerFixFixtureRoot $fixture.Root
        }
    }
}

function Invoke-D248SourceAuditTests {
    Invoke-TestCase 'D248 production module holds no forced or name based removal' {
        $tokens = $null
        $errors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile($ModulePath, [ref]$tokens, [ref]$errors)
        Assert-Equal 0 $errors.Count 'The production module does not parse.'

        # No literal ever carries a forcing flag, wherever an argument vector
        # is assembled - inline, in an array, or through a splat.
        $literals = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true))
        foreach ($literal in $literals) {
            Assert-True ($literal.Value -cnotin @('--force', '--volumes', '--remove-orphans')) `
                ('The production module builds a forcing Docker argument: ' + $literal.Value)
        }

        $commands = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
        $dockerCommands = @($commands | Where-Object { $_.CommandElements[0].Extent.Text -ceq 'docker' })
        Assert-True ($dockerCommands.Count -gt 0) 'No Docker command was found in the production module.'
        $removalSeen = 0
        foreach ($command in $dockerCommands) {
            $elements = @($command.CommandElements)
            $parameters = @($elements | Where-Object { $_ -is [System.Management.Automation.Language.CommandParameterAst] })
            foreach ($parameter in $parameters) {
                Assert-True ($parameter.ParameterName -cnotin @('force', 'volumes', 'remove-orphans')) `
                    ('A Docker command is given a forcing parameter: ' + $command.Extent.Text)
            }
            $words = @($elements | Select-Object -Skip 1 | ForEach-Object { $_.Extent.Text })
            $verb = @($words | Where-Object { $_ -cin @('rm', 'prune') })
            if ($verb.Count -eq 0) { continue }
            $removalSeen++
            foreach ($parameter in $parameters) {
                Assert-True ($parameter.ParameterName -cne 'f') `
                    ('A Docker removal is forced with -f: ' + $command.Extent.Text)
            }
            Assert-True ($words -cnotcontains 'prune') ('A Docker removal prunes: ' + $command.Extent.Text)
            # Everything a removal names is a value this run pinned, never a
            # literal name written into the source.
            $index = [array]::IndexOf($words, 'rm')
            foreach ($operand in @($words | Select-Object -Skip ($index + 1))) {
                if ($operand -cmatch '^(2>\$null|\|)$') { continue }
                Assert-True ($operand -cmatch '^\$') `
                    ('A Docker removal names a literal rather than a verified identifier: ' + $command.Extent.Text)
            }
        }
        Assert-True ($removalSeen -ge 3) 'The production removal commands were not found.'

        # The dead by-name browser removal is gone, definition and callers.
        $moduleText = [System.IO.File]::ReadAllText($ModulePath)
        Assert-Equal 0 ([regex]::Matches($moduleText, 'Remove-BrowserContainer')).Count `
            'The dead by-name browser container removal is still present.'
        $functions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true))
        Assert-True (@($functions | Where-Object { $_.Name -ceq 'Remove-BrowserContainer' }).Count -eq 0) `
            'The dead by-name browser container removal is still defined.'
        Assert-True (@($functions | Where-Object { $_.Name -ceq 'Assert-E2EPreparedBrowserRuntime' }).Count -eq 1) `
            'The separated browser runtime preflight is not defined exactly once.'
        Assert-True (@($functions | Where-Object { $_.Name -ceq 'Get-E2EVolumeIdentity' }).Count -eq 1) `
            'The volume identity reader is not defined exactly once.'

        # The record reader itself starts no container.
        $reader = @($functions | Where-Object { $_.Name -ceq 'Assert-E2EOwnedImages' })
        Assert-Equal 1 $reader.Count 'The image record reader is not defined exactly once.'
        $readerText = $reader[0].Extent.Text
        foreach ($mutation in @('Assert-BrowserRuntime', 'Invoke-ApprovedContainer', 'New-CreatedContainer', 'Remove-OwnedContainer')) {
            Assert-True ($readerText -cnotmatch [regex]::Escape($mutation)) `
                ('The read-only image record reader can still reach ' + $mutation + '.')
        }
    }
}

function Invoke-D248TargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-D248ServiceBoundaryTests
    Invoke-D248BrowserCleanupTests
    Invoke-D248SourceAuditTests
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D248 targeted passed'
}

function Invoke-D209BTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-TestCase 'D242 production Cleanup removes only exact verified resources' {
        $fixture = New-OwnerFixFixture
        $dockerRoot = Join-Path $fixture.Root 'fake-docker'
        [System.IO.Directory]::CreateDirectory($dockerRoot) | Out-Null
        $shim = Join-Path $dockerRoot 'docker.cmd'
        $source = Join-Path $dockerRoot 'docker-shim.ps1'
        $state = Join-Path $dockerRoot 'container-state.txt'
        $backendState = Join-Path $dockerRoot 'backend-state.txt'
        $networkState = Join-Path $dockerRoot 'network-state.txt'
        $namedState = Join-Path $dockerRoot 'named-state.txt'
        $anonymousState = Join-Path $dockerRoot 'anonymous-state.txt'
        $stoppedFlag = Join-Path $dockerRoot 'stopped.flag'
        $removedNetworks = Join-Path $dockerRoot 'networks-removed.txt'
        $namedInspectCount = Join-Path $dockerRoot 'named-inspect-count.txt'
        $events = Join-Path $dockerRoot 'events.txt'
        $id = 'f' * 64
        $backendId = '7' * 64
        $project = 'finguardops-kc241-e2e-0123456789ab'
        $sharedServices = @('external-risk-mock', 'keycloak', 'keycloak-bootstrap', 'keycloak-verify')
        $oldPath = $env:PATH
        $scenarioNames = @('FINGUARDOPS_D209_FAIL', 'FINGUARDOPS_D209_SERVICE', 'FINGUARDOPS_D209_PROJECT',
            'FINGUARDOPS_D209_UNRELATED_NETWORK', 'FINGUARDOPS_D209_UNRELATED_VOLUME',
            'FINGUARDOPS_D225_CONFIG_IMAGE', 'FINGUARDOPS_D225_IMAGE_ID', 'FINGUARDOPS_D225_DUPLICATE',
            'FINGUARDOPS_D225_MOUNT', 'FINGUARDOPS_D225_NUMBER', 'FINGUARDOPS_D225_ONEOFF',
            'FINGUARDOPS_D225_CONFIG_FILES', 'FINGUARDOPS_D225_WORKDIR', 'FINGUARDOPS_D225_NETWORK',
            'FINGUARDOPS_D225_OWNER_LABEL', 'FINGUARDOPS_D225_NAMESPACE', 'FINGUARDOPS_D242_NAME',
            'FINGUARDOPS_D242_FOREIGN', 'FINGUARDOPS_D242_FOREIGN_AFTER', 'FINGUARDOPS_D242_REPLACE_AFTER',
            'FINGUARDOPS_D242_NET_ATTACH_AFTER', 'FINGUARDOPS_D242_NET_REPLACE_AFTER',
            'FINGUARDOPS_D242_VOLUME_SWAP', 'FINGUARDOPS_D242_VOL_USER_AFTER',
            'FINGUARDOPS_D242_IMAGES_GONE', 'FINGUARDOPS_D248_DRIFT_AT',
            'FINGUARDOPS_D248_DRIFT_FIELD', 'FINGUARDOPS_D248_USER_AT', 'FINGUARDOPS_D273_BIND')
        $environmentNames = @('FINGUARDOPS_D209_ROOT', 'FINGUARDOPS_D209_REPOSITORY_ROOT') + $scenarioNames
        $previousEnvironment = @{}
        foreach ($name in $environmentNames) { $previousEnvironment[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process') }
        try {
            $receiptForDocker = New-TestReceipt
            $previousOwner = & $script:E2EModule { param($value) Set-E2EOwnerEnvironment -Receipt $value } $receiptForDocker
            try {
                $contractJson = & $script:E2EModule {
                    param($activeProject)
                    $arguments = Get-E2EComposeBaseArguments -Project $activeProject
                    Invoke-E2EInLocation -Path $RepositoryRoot -Body {
                        $value = Invoke-NativeStdout { & docker @arguments config --format json }
                        if ($LASTEXITCODE -ne 0) { throw 'COMPOSE_CONFIG_FIXTURE_FAILED' }
                        return $value
                    }
                } $project
                [System.IO.File]::WriteAllText((Join-Path $dockerRoot 'contract.json'), ($contractJson -join "`n"), [System.Text.UTF8Encoding]::new($false))
            }
            finally { & $script:E2EModule { param($value) Restore-E2EOwnerEnvironment -Previous $value } $previousOwner }
            [System.IO.File]::WriteAllText($shim, "@echo off`r`nset `"FINGUARDOPS_D209_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n", [System.Text.Encoding]::ASCII)
            $fakeSource = @'
$DockerArgs = $env:FINGUARDOPS_D209_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D209_ROOT
$fail = $env:FINGUARDOPS_D209_FAIL
$state = Join-Path $root 'container-state.txt'
$backendState = Join-Path $root 'backend-state.txt'
$networkState = Join-Path $root 'network-state.txt'
$namedState = Join-Path $root 'named-state.txt'
$anonymousState = Join-Path $root 'anonymous-state.txt'
$stoppedFlag = Join-Path $root 'stopped.flag'
$removedNetworksPath = Join-Path $root 'networks-removed.txt'
$namedInspectCount = Join-Path $root 'named-inspect-count.txt'
$events = Join-Path $root 'events.txt'
$line = $DockerArgs -join ' '
[System.IO.File]::AppendAllText($events, $line + "`n")
$id = 'f' * 64
$backendId = '7' * 64
$replacedId = '5' * 64
$foreignId = '9' * 64
$anonymous = 'a' * 64
$project = 'finguardops-kc241-e2e-0123456789ab'
$contract = Get-Content (Join-Path $root 'contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$activeService = if ($env:FINGUARDOPS_D209_SERVICE) { $env:FINGUARDOPS_D209_SERVICE } else { 'postgresql' }
$sharedServices = @('external-risk-mock', 'keycloak', 'keycloak-bootstrap', 'keycloak-verify')
$networkIds = @{ application = ('e' * 64); observability = ('d' * 64); 'prometheus-ui' = ('c' * 64); 'grafana-ui' = ('6' * 64) }
$replacementNetworkId = '4' * 64
$activeNetworks = if ($activeService -in $sharedServices -or $activeService -eq 'backend') {
    @('application', 'observability', 'prometheus-ui')
} elseif ($null -ne $contract.services.PSObject.Properties[$activeService]) {
    @($contract.services.PSObject.Properties[$activeService].Value.networks.PSObject.Properties.Name)
} else { @('application') }
$activeNetworks = [string[]]@($activeNetworks)
$primaryNetwork = if ($activeNetworks.Count -ne 0) { $activeNetworks[0] } else { 'application' }
$declaredVolume = @($contract.services.PSObject.Properties[$activeService].Value.volumes | Where-Object { $_.type -eq 'volume' })
$volumeRole = if ($declaredVolume.Count -ne 0) { $declaredVolume[0].source } else { 'keycloak-data' }
$namedVolume = $project + '_' + $volumeRole

$containersGone = -not ([System.IO.File]::Exists($state) -or [System.IO.File]::Exists($backendState))
$removedNetworks = if ([System.IO.File]::Exists($removedNetworksPath)) {
    @([System.IO.File]::ReadAllLines($removedNetworksPath) | Where-Object { $_ })
} else { @() }
$liveNetworks = if ([System.IO.File]::Exists($networkState)) {
    @($activeNetworks | Where-Object { $removedNetworks -cnotcontains $_ })
} else { @() }

$primaryId = if ($env:FINGUARDOPS_D242_REPLACE_AFTER -eq '1' -and [System.IO.File]::Exists($stoppedFlag)) { $replacedId } else { $id }
$present = [System.Collections.Generic.List[object]]::new()
if ([System.IO.File]::Exists($state)) { $present.Add(@{ Id = $primaryId; Service = $activeService }) }
if ([System.IO.File]::Exists($backendState)) { $present.Add(@{ Id = $backendId; Service = 'backend' }) }
if ($env:FINGUARDOPS_D225_DUPLICATE -eq '1' -and [System.IO.File]::Exists($state)) {
    $present.Add(@{ Id = $replacedId; Service = $activeService })
}
if ($env:FINGUARDOPS_D242_FOREIGN -eq '1' -or
    ($env:FINGUARDOPS_D242_FOREIGN_AFTER -eq '1' -and [System.IO.File]::Exists($stoppedFlag))) {
    $present.Add(@{ Id = $foreignId; Service = 'unknown-sidecar' })
}
$namedInspectSoFar = 0
if ([System.IO.File]::Exists($namedInspectCount)) {
    $namedInspectSoFar = [int][System.IO.File]::ReadAllText($namedInspectCount)
}
$volumeUserPresent = $env:FINGUARDOPS_D209_UNRELATED_VOLUME -eq '1' -or
    ($env:FINGUARDOPS_D242_VOL_USER_AFTER -eq '1' -and $containersGone -and $liveNetworks.Count -eq 0) -or
    ($env:FINGUARDOPS_D248_USER_AT -and $namedInspectSoFar -ge [int]$env:FINGUARDOPS_D248_USER_AT)

$inspectable = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $present) { $inspectable.Add($entry) }
if ($env:FINGUARDOPS_D242_REPLACE_AFTER -eq '1' -and [System.IO.File]::Exists($stoppedFlag) -and
    [System.IO.File]::Exists($state)) {
    $inspectable.Add(@{ Id = $id; Service = $activeService })
}

function Get-FakeNetworkId {
    param([string]$Name)
    if ($Name -ceq $primaryNetwork -and $env:FINGUARDOPS_D242_NET_REPLACE_AFTER -eq '1' -and $containersGone) {
        return $replacementNetworkId
    }
    return $networkIds[$Name]
}

function New-FakeContainerDocument {
    param([string]$Target, [string]$Service)

    $repoRoot = $env:FINGUARDOPS_D209_REPOSITORY_ROOT
    $configFiles = @((Join-Path $repoRoot 'infra/compose.yml'), (Join-Path $repoRoot 'infra/compose.keycloak-local-e2e.yml')) -join ','
    # Compose records the directory of the first `-f` file, which is the
    # repository's `infra` directory and not the repository root.
    $workingDir = Join-Path $repoRoot 'infra'
    $expectedReference = $contract.services.PSObject.Properties[$Service].Value.image
    $isPrimary = $Service -ceq $activeService -and $Target -cne $backendId
    $reference = if ($isPrimary -and $env:FINGUARDOPS_D225_CONFIG_IMAGE) { $env:FINGUARDOPS_D225_CONFIG_IMAGE } else { $expectedReference }
    $imageId = if ($isPrimary -and $env:FINGUARDOPS_D225_IMAGE_ID) { $env:FINGUARDOPS_D225_IMAGE_ID }
        elseif ($Service -in @('ai-service', 'external-risk-mock', 'alertmanager-webhook')) { 'sha256:' + ('c' * 64) }
        elseif ($Service -eq 'backend') { 'sha256:' + ('b' * 64) }
        elseif ($expectedReference -match '@sha256:([0-9a-f]{64})$') { 'sha256:' + $Matches[1] }
        else { 'sha256:' + ('a' * 64) }
    $owner = if ($isPrimary -and $env:FINGUARDOPS_D209_PROJECT) { $env:FINGUARDOPS_D209_PROJECT } else { $project }
    $labels = @{
        'com.docker.compose.project' = $owner
        'com.docker.compose.service' = $Service
        'com.docker.compose.container-number' = $(if ($isPrimary -and $env:FINGUARDOPS_D225_NUMBER) { $env:FINGUARDOPS_D225_NUMBER } else { '1' })
        'com.docker.compose.oneoff' = $(if ($isPrimary -and $env:FINGUARDOPS_D225_ONEOFF) { $env:FINGUARDOPS_D225_ONEOFF } else { 'False' })
        'com.docker.compose.project.config_files' = $(if ($isPrimary -and $env:FINGUARDOPS_D225_CONFIG_FILES) { $env:FINGUARDOPS_D225_CONFIG_FILES } else { $configFiles })
        'com.docker.compose.project.working_dir' = $(if ($isPrimary -and $env:FINGUARDOPS_D225_WORKDIR) { $env:FINGUARDOPS_D225_WORKDIR } else { $workingDir })
    }
    if ($isPrimary -and $env:FINGUARDOPS_D225_WORKDIR -ceq '@@absent@@') {
        $labels.Remove('com.docker.compose.project.working_dir')
    }
    if ($Service -in @('ai-service', 'backend', 'external-risk-mock', 'alertmanager-webhook')) {
        $role = if ($Service -eq 'backend') { 'backend' } else { 'ai-service' }
        $labels['org.opencontainers.image.revision'] = $env:FINGUARDOPS_E2E_REVISION
        $labels['com.finguardops.e2e.source-tree'] = $env:FINGUARDOPS_E2E_SOURCE_TREE
        $labels['com.finguardops.e2e.run-id'] = $(if ($isPrimary -and $env:FINGUARDOPS_D225_OWNER_LABEL) { 'wrong' } else { $env:FINGUARDOPS_E2E_RUN_ID })
        $labels['com.finguardops.e2e.repository-id'] = $env:FINGUARDOPS_E2E_REPOSITORY_ID
        $labels['com.finguardops.e2e.image-role'] = $role
    }
    $mounts = @()
    if ($Service -eq 'postgresql' -and [System.IO.File]::Exists($anonymousState)) {
        $mounts += , @{ Type = 'volume'; Name = $anonymous; Destination = $(if ($env:FINGUARDOPS_D225_MOUNT -eq 'wrong') { '/wrong' } else { '/var/lib/postgresql/data' }) }
    }
    if ($Service -ne 'postgresql') {
        foreach ($volume in @($contract.services.PSObject.Properties[$Service].Value.volumes)) {
            if ($volume.type -eq 'volume') { $mounts += , @{ Type = 'volume'; Name = ($project + '_' + $volume.source); Destination = $volume.target } }
            elseif ($volume.type -eq 'bind') {
                # Docker Desktop runs the daemon in a Linux VM, so a Windows
                # host bind source comes back as the VM's view of it unless a
                # case asks for the Windows spelling instead.
                $spelling = $env:FINGUARDOPS_D273_BIND
                $source = $volume.source
                if ($spelling -cne 'windows') {
                    $drive = $source.Substring(0, 1).ToLowerInvariant()
                    if ($isPrimary -and $spelling -ceq 'other-drive') { $drive = 'd' }
                    $source = '/run/desktop/mnt/host/' + $drive + '/' + ($source.Substring(3) -replace '\\', '/')
                    if ($isPrimary -and $spelling -ceq 'unknown-prefix') { $source = $source -replace '^/run/desktop', '' }
                }
                $writable = $isPrimary -and $spelling -ceq 'writable'
                $mounts += , @{ Type = 'bind'; Source = $source; Destination = $volume.target
                    Mode = $(if ($writable) { 'rw' } else { 'ro' }); RW = $writable; Propagation = 'rprivate' }
            }
        }
        foreach ($secret in @($contract.services.PSObject.Properties[$Service].Value.secrets)) {
            if ($null -eq $secret) { continue }
            $mounts += , @{ Type = 'bind'; Source = 'fixture-secret'; Destination = ('/run/secrets/' + $secret.target) }
        }
    }
    $serviceNetworks = [string[]]@(if ($Service -eq 'backend' -and $activeService -in $sharedServices) {
        @('application', 'observability', 'prometheus-ui')
    } else { $activeNetworks })
    $attachments = @{}
    foreach ($name in $serviceNetworks) { $attachments[$project + '_' + $name] = @{ NetworkID = (Get-FakeNetworkId $name) } }
    $networkMode = $project + '_' + $serviceNetworks[0]
    if ($isPrimary -and $env:FINGUARDOPS_D225_NETWORK -eq 'wrong') {
        $networkMode = $project + '_wrong'
        $attachments = @{ ($project + '_wrong') = @{ NetworkID = $networkIds['application'] } }
    }
    if ($Service -in $sharedServices) {
        $networkMode = 'container:' + $(if ($env:FINGUARDOPS_D225_NAMESPACE -eq 'wrong') { '9' * 64 } else { $backendId })
        $attachments = @{}
    }
    $status = if ($Target -ceq $backendId) { [System.IO.File]::ReadAllText($backendState) } else { [System.IO.File]::ReadAllText($state) }
    $observedName = if ($isPrimary -and $env:FINGUARDOPS_D242_NAME -eq 'wrong') { '/' + $project + '-unexpected-1' } else { '/' + $project + '-' + $Service + '-1' }
    return [ordered]@{
        Id = $Target
        Name = $observedName
        Config = @{ Image = $reference; Labels = $labels }
        HostConfig = @{ NetworkMode = $networkMode }
        State = @{ Status = $status; Running = ($status -eq 'running') }
        Image = $imageId
        Mounts = $mounts
        NetworkSettings = @{ Networks = $attachments }
    }
}

if ($DockerArgs[0] -eq 'compose' -and $line -match ' config --format json$') {
    Write-Output (Get-Content (Join-Path $root 'contract.json') -Raw -Encoding UTF8); exit 0
}
if ($DockerArgs[0] -eq 'image' -and $DockerArgs[1] -eq 'inspect') {
    $reference = $DockerArgs[-1]
    if ($env:FINGUARDOPS_D242_IMAGES_GONE -eq '1' -and $reference -cmatch ':e2e-') { exit 1 }
    $known = @($contract.services.PSObject.Properties | Where-Object { $_.Value.image -ceq $reference })
    if ($known.Count -eq 0) { exit 1 }
    if ($reference -match '@sha256:([0-9a-f]{64})$') { $imageId = 'sha256:' + $Matches[1]; $labels = @{} }
    elseif ($reference -match 'backend:') {
        $imageId = 'sha256:' + ('b' * 64)
        $labels = @{
            'org.opencontainers.image.revision' = $env:FINGUARDOPS_E2E_REVISION
            'com.finguardops.e2e.source-tree' = $env:FINGUARDOPS_E2E_SOURCE_TREE
            'com.finguardops.e2e.run-id' = $env:FINGUARDOPS_E2E_RUN_ID
            'com.finguardops.e2e.repository-id' = $env:FINGUARDOPS_E2E_REPOSITORY_ID
            'com.finguardops.e2e.image-role' = 'backend'
        }
    }
    else {
        $imageId = 'sha256:' + ('c' * 64)
        $labels = @{
            'org.opencontainers.image.revision' = $env:FINGUARDOPS_E2E_REVISION
            'com.finguardops.e2e.source-tree' = $env:FINGUARDOPS_E2E_SOURCE_TREE
            'com.finguardops.e2e.run-id' = $env:FINGUARDOPS_E2E_RUN_ID
            'com.finguardops.e2e.repository-id' = $env:FINGUARDOPS_E2E_REPOSITORY_ID
            'com.finguardops.e2e.image-role' = 'ai-service'
        }
    }
    Write-Output (@{ Id = $imageId; Config = @{ Labels = $labels } } | ConvertTo-Json -Depth 5 -Compress); exit 0
}
if ($DockerArgs[0] -eq 'ps') {
    if ($line -match 'name=') {
        $wanted = @()
        foreach ($token in $DockerArgs) {
            if ($token -cmatch '^name=\^?/(.+)\$$') { $wanted += $Matches[1] }
        }
        foreach ($entry in $present) {
            if ($wanted -ccontains ($project + '-' + $entry.Service + '-1')) { Write-Output $entry.Id }
        }
        exit 0
    }
    if ($line -match 'label=com\.docker\.compose\.project=([^\s]+)') {
        if ($Matches[1] -ceq $project) { foreach ($entry in $present) { Write-Output $entry.Id } }
        exit 0
    }
    foreach ($entry in $present) { Write-Output $entry.Id }
    if ($volumeUserPresent) { Write-Output $foreignId }
    exit 0
}
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'ls') {
    if ($line -match 'name=') {
        $wanted = @()
        foreach ($token in $DockerArgs) {
            if ($token -cmatch '^name=\^?(.+)\$$') { $wanted += $Matches[1] }
        }
        foreach ($name in $liveNetworks) {
            if ($wanted -ccontains ($project + '_' + $name)) { Write-Output (Get-FakeNetworkId $name) }
        }
        exit 0
    }
    if ($line -match 'label=com\.docker\.compose\.project=([^\s]+)') {
        if ($Matches[1] -ceq $project) { foreach ($name in $liveNetworks) { Write-Output (Get-FakeNetworkId $name) } }
        exit 0
    }
    exit 0
}
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'inspect') {
    $target = $DockerArgs[-1]
    $name = $null
    foreach ($candidate in $liveNetworks) { if ((Get-FakeNetworkId $candidate) -ceq $target) { $name = $candidate; break } }
    if ($null -eq $name) { exit 1 }
    $attached = @{}
    foreach ($entry in $present) {
        if ($entry.Service -in $sharedServices) { continue }
        if ($entry.Service -ceq 'unknown-sidecar') { continue }
        $attached[$entry.Id] = @{}
    }
    if ($env:FINGUARDOPS_D209_UNRELATED_NETWORK -eq '1') { $attached[$foreignId] = @{} }
    if ($env:FINGUARDOPS_D242_NET_ATTACH_AFTER -eq '1' -and $containersGone) { $attached[$id] = @{} }
    $document = [ordered]@{
        Id = $target
        Name = $project + '_' + $name
        Labels = @{ 'com.docker.compose.project' = $project; 'com.docker.compose.network' = $name }
        Containers = $attached
    }
    Write-Output ($document | ConvertTo-Json -Depth 8 -Compress); exit 0
}
if ($DockerArgs[0] -eq 'volume' -and $DockerArgs[1] -eq 'ls') {
    if ($line -match 'label=com\.docker\.compose\.project=([^\s]+)') {
        if ($Matches[1] -ceq $project -and [System.IO.File]::Exists($namedState)) { Write-Output $namedVolume }
        exit 0
    }
    if ($line -match 'name=\^?([^$]+)\$') {
        $wanted = $Matches[1]
        if ($wanted -ceq $namedVolume -and [System.IO.File]::Exists($namedState)) { Write-Output $namedVolume }
        if ($wanted -ceq $anonymous -and [System.IO.File]::Exists($anonymousState)) { Write-Output $anonymous }
        exit 0
    }
    exit 0
}
if ($DockerArgs[0] -eq 'volume' -and $DockerArgs[1] -eq 'inspect') {
    $name = $DockerArgs[-1]
    # Every field Docker's Volume API reports for a volume, so the production
    # identity check has the same material a real daemon would give it. The
    # drift below is the daemon answering differently on a later call for the
    # same name, which is exactly the shape the check exists for.
    $drift = ''
    if ($name -ceq $namedVolume) {
        $count = 0
        if ([System.IO.File]::Exists($namedInspectCount)) {
            $count = [int][System.IO.File]::ReadAllText($namedInspectCount)
        }
        $count = $count + 1
        [System.IO.File]::WriteAllText($namedInspectCount, [string]$count)
        if ($env:FINGUARDOPS_D248_DRIFT_AT -and $count -ge [int]$env:FINGUARDOPS_D248_DRIFT_AT) {
            $drift = [string]$env:FINGUARDOPS_D248_DRIFT_FIELD
        }
    }
    if ($name -ceq $namedVolume -and [System.IO.File]::Exists($namedState)) {
        $labels = @{ 'com.docker.compose.project' = $project; 'com.docker.compose.volume' = $volumeRole }
        if ($drift -ceq 'labels') { $labels['com.finguardops.drift'] = 'added' }
        $options = @{ type = 'none' }
        if ($drift -ceq 'options') { $options = @{ type = 'tmpfs' } }
        $reported = if ($env:FINGUARDOPS_D242_VOLUME_SWAP -eq '1' -and $containersGone) { $project + '_grafana-data' } else { $name }
        $created = if ($drift -ceq 'createdat') { '2026-02-02T02:02:02Z' } else { '2026-01-01T01:01:01Z' }
        $driver = if ($drift -ceq 'driver') { 'other-driver' } else { 'local' }
        $scope = if ($drift -ceq 'scope') { 'global' } else { 'local' }
        $mountpoint = if ($drift -ceq 'mountpoint') { '/var/lib/docker/volumes/other/_data' } else { '/var/lib/docker/volumes/' + $name + '/_data' }
    }
    elseif ($name -ceq $anonymous -and [System.IO.File]::Exists($anonymousState)) {
        $labels = @{ 'com.docker.volume.anonymous' = '' }
        $options = @{}
        $reported = $name
        $created = '2026-01-01T01:01:01Z'
        $driver = 'local'
        $scope = 'local'
        $mountpoint = '/var/lib/docker/volumes/' + $name + '/_data'
    }
    else { exit 1 }
    Write-Output (@{ Name = $reported; CreatedAt = $created; Driver = $driver; Scope = $scope;
        Mountpoint = $mountpoint; Labels = $labels; Options = $options } | ConvertTo-Json -Depth 5 -Compress)
    exit 0
}
if ($DockerArgs[0] -eq 'container' -and $DockerArgs[1] -eq 'inspect') {
    $target = $DockerArgs[-1]
    if ($volumeUserPresent -and $target -ceq $foreignId) {
        $mounts = @([ordered]@{ Type = 'volume'; Name = $namedVolume })
        Write-Output (@{ Id = $foreignId; Name = '/foreign'; Mounts = $mounts; Config = @{ Labels = @{} } } | ConvertTo-Json -Depth 5 -Compress); exit 0
    }
    $entry = @($inspectable | Where-Object { $_.Id -ceq $target })
    if ($entry.Count -ne 1) { exit 1 }
    Write-Output ((New-FakeContainerDocument -Target $target -Service $entry[0].Service) | ConvertTo-Json -Depth 8 -Compress); exit 0
}
if ($DockerArgs[0] -eq 'stop' -or ($DockerArgs[0] -eq 'rm' -and $DockerArgs[1] -cnotmatch '^-')) {
    $target = $DockerArgs[1]
    $path = if ($target -ceq $id) { $state } elseif ($target -ceq $backendId) { $backendState } else { $null }
    if ($null -eq $path -or -not [System.IO.File]::Exists($path)) { exit 81 }
    if ($fail -eq $DockerArgs[0]) { exit 17 }
    if ($DockerArgs[0] -eq 'stop') {
        [System.IO.File]::WriteAllText($path, 'exited')
        if ($target -ceq $id) { [System.IO.File]::WriteAllText($stoppedFlag, '1') }
    }
    else { [System.IO.File]::Delete($path) }
    Write-Output $target; exit 0
}
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'rm') {
    $target = $DockerArgs[2]
    $name = $null
    foreach ($candidate in $liveNetworks) { if ((Get-FakeNetworkId $candidate) -ceq $target) { $name = $candidate; break } }
    if ($null -eq $name) { exit 81 }
    if ($fail -eq 'network') { exit 17 }
    [System.IO.File]::AppendAllText($removedNetworksPath, $name + "`n")
    if (@($liveNetworks | Where-Object { $_ -cne $name }).Count -eq 0) { [System.IO.File]::Delete($networkState) }
    Write-Output $target; exit 0
}
if ($DockerArgs[0] -eq 'volume' -and $DockerArgs[1] -eq 'rm') {
    $name = $DockerArgs[2]
    $path = if ($name -ceq $namedVolume) { $namedState } elseif ($name -ceq $anonymous) { $anonymousState } else { $null }
    if ($null -eq $path -or -not [System.IO.File]::Exists($path)) { exit 81 }
    if ($fail -eq 'volume') { exit 17 }
    [System.IO.File]::Delete($path); Write-Output $name; exit 0
}
exit 81

'@
            [System.IO.File]::WriteAllText($source, ($fakeSource -replace "(?<!`r)`n", "`r`n"), [System.Text.UTF8Encoding]::new($false))
            Assert-Parsed $source
            $env:FINGUARDOPS_D209_ROOT = $dockerRoot
            $env:FINGUARDOPS_D209_REPOSITORY_ROOT = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ModulePath))
            $env:PATH = $dockerRoot + [System.IO.Path]::PathSeparator + $oldPath
            Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'Docker fake sentinel was not selected.'

            # A `.cmd` shim loses a bare `^` from an argument before the fake
            # can see it, so the anchoring is asserted against what production
            # actually builds; the fake below still proves that the name part
            # of every query is an exact contract name.
            $filterSample = & $script:E2EModule { Get-E2EExactNameFilters -Names @('alpha', 'beta') -Prefix '/' }
            Assert-Equal @('--filter', 'name=^/alpha$', '--filter', 'name=^/beta$') @($filterSample) 'Container name filters are not anchored at both ends.'
            $networkSample = & $script:E2EModule { Get-E2EExactNameFilters -Names @('alpha') -Prefix '' }
            Assert-Equal @('--filter', 'name=^alpha$') @($networkSample) 'Network name filters are not anchored at both ends.'
            $contractNames = @()
            foreach ($service in @('postgresql', 'ai-service', 'external-risk-mock', 'backend', 'prometheus',
                'grafana', 'alertmanager', 'alertmanager-webhook', 'keycloak', 'keycloak-bootstrap', 'keycloak-verify')) {
                $contractNames += ($project + '-' + $service + '-1')
            }
            foreach ($network in @('application', 'observability', 'prometheus-ui', 'grafana-ui')) { $contractNames += ($project + '_' + $network) }
            foreach ($volume in @('keycloak-data', 'prometheus-data', 'alertmanager-data', 'grafana-data')) { $contractNames += ($project + '_' + $volume) }
            $contractNames += ('a' * 64)
            $contractNames += 'finguardops-keycloak-browser-e2e-chromium'

            $markers = [System.Collections.Generic.List[string]]::new()
            $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
            $context.LeafBoundaries.ResourceCleanup = & $script:E2EModule {
                param($eventSink)
                $script:D209ResourceMarkers = $eventSink
                return { param($activeReceipt) $script:D209ResourceMarkers.Add('resource'); Invoke-E2EProjectCleanup -Project (Get-E2EServiceProjectName -Receipt $activeReceipt) -Receipt $activeReceipt }
            } $markers

            $allStates = @($state, $backendState, $networkState, $namedState, $anonymousState)
            $flags = @($stoppedFlag, $removedNetworks, $namedInspectCount)

            # How many times a clean cleanup asks the daemon about the named
            # volume. The last of those calls is the identity re-read that
            # happens immediately before the removal, so the drift cases below
            # switch the daemon's answer on exactly that call: everything the
            # inventory pinned was pinned from an unchanged volume, and the
            # only thing that differs is what the daemon says at the moment of
            # the removal. The number is measured rather than assumed.
            $namedVolumeName = $project + '_keycloak-data'
            foreach ($path in ($allStates + $flags)) { if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) } }
            foreach ($path in @($state, $networkState, $namedState, $anonymousState)) { [System.IO.File]::WriteAllText($path, 'running', [System.Text.Encoding]::ASCII) }
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            foreach ($name in $scenarioNames) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
            $env:FINGUARDOPS_D209_SERVICE = ''
            $env:FINGUARDOPS_D209_FAIL = ''
            $markers.Clear()
            $baselineFailure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext $context }
            $baselineDetail = if ($null -ne $baselineFailure) { $baselineFailure.Message } else { '' }
            Assert-True ($null -eq $baselineFailure) "The volume identity baseline cleanup failed: $baselineDetail"
            $baselineCommands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
            $namedInspects = @($baselineCommands | Where-Object { $_ -ceq ('volume inspect --format "{{json .}}" ' + $namedVolumeName) })
            Assert-True ($namedInspects.Count -ge 2) 'The named volume was not re-inspected before its removal.'
            $finalInspect = $namedInspects.Count
            $baselineVolumeRemovals = @($baselineCommands | Where-Object { $_ -ceq ('volume rm ' + $namedVolumeName) })
            Assert-Equal 1 $baselineVolumeRemovals.Count 'The baseline cleanup did not remove the named volume exactly once.'
            $volumeDriftMutations = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64)), ('network rm ' + ('e' * 64)))
            $cases = @(
                # The identity of an untouched volume is stable across every
                # re-read a clean cleanup makes, so this case also proves the
                # new comparison does not refuse a volume that did not change.
                [pscustomobject]@{ Name = 'expected-all-present'; Service = ''; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'unique-image-service-present'; Service = 'ai-service'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'backend-present'; Service = 'backend'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'keycloak-present'; Service = 'keycloak'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'bootstrap-present'; Service = 'keycloak-bootstrap'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'verify-present'; Service = 'keycloak-verify'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'external-risk-present'; Service = 'external-risk-mock'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'prometheus-present'; Service = 'prometheus'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'grafana-present'; Service = 'grafana'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'alertmanager-present'; Service = 'alertmanager'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'webhook-present'; Service = 'alertmanager-webhook'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                # "Partly absent" has to stay a state Compose could actually
                # leave behind: a postgresql container always carries its data
                # volume, and a container always sits on its network, so the
                # two recoverable shapes are a named volume whose container
                # never mounted it, and resources outliving their container.
                [pscustomobject]@{ Name = 'expected-partly-absent-volume'; Service = ''; Fail = ''; Env = @{}; Absent = @('named-state.txt'); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'expected-partly-absent-container'; Service = ''; Fail = ''; Env = @{}; Absent = @('container-state.txt', 'anonymous-state.txt'); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'expected-all-absent'; Service = ''; Fail = ''; Env = @{}; Absent = @('container-state.txt', 'network-state.txt', 'named-state.txt', 'anonymous-state.txt'); Success = $true; Mutating = $false },
                [pscustomobject]@{ Name = 'allowed-service-wrong-reference'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_CONFIG_IMAGE = 'wrong:reference' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'allowed-service-wrong-image-id'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_IMAGE_ID = ('sha256:' + ('9' * 64)) }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unique-service-wrong-reference'; Service = 'ai-service'; Fail = ''; Env = @{ FINGUARDOPS_D225_CONFIG_IMAGE = 'wrong:reference' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unique-service-wrong-image-id'; Service = 'ai-service'; Fail = ''; Env = @{ FINGUARDOPS_D225_IMAGE_ID = ('sha256:' + ('9' * 64)) }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unique-service-ownership-label-mismatch'; Service = 'ai-service'; Fail = ''; Env = @{ FINGUARDOPS_D225_OWNER_LABEL = '1' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'container-number-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_NUMBER = '2' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'oneoff-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_ONEOFF = 'True' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'config-files-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_CONFIG_FILES = 'wrong.yml' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'working-directory-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_WORKDIR = 'C:\wrong' }; Absent = @(); Success = $false; Mutating = $false },
                # The repository root is where the Compose command runs, not
                # the directory Compose records as the project's own.
                [pscustomobject]@{ Name = 'working-directory-repository-root'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_WORKDIR = $env:FINGUARDOPS_D209_REPOSITORY_ROOT }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'working-directory-sibling'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_WORKDIR = (Join-Path $env:FINGUARDOPS_D209_REPOSITORY_ROOT 'infrastructure') }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'working-directory-suffix-only'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_WORKDIR = 'C:\elsewhere\infra' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'working-directory-absent'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_WORKDIR = '@@absent@@' }; Absent = @(); Success = $false; Mutating = $false },
                # The bind source, in every spelling the daemon can report it
                # in. The Windows spelling and the Docker Desktop spelling name
                # one path; the other three name something else.
                [pscustomobject]@{ Name = 'bind-source-windows-spelling'; Service = 'keycloak-bootstrap'; Fail = ''; Env = @{ FINGUARDOPS_D273_BIND = 'windows' }; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'bind-source-other-drive'; Service = 'keycloak-bootstrap'; Fail = ''; Env = @{ FINGUARDOPS_D273_BIND = 'other-drive' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'bind-source-unknown-prefix'; Service = 'keycloak-bootstrap'; Fail = ''; Env = @{ FINGUARDOPS_D273_BIND = 'unknown-prefix' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'bind-source-writable'; Service = 'keycloak-bootstrap'; Fail = ''; Env = @{ FINGUARDOPS_D273_BIND = 'writable' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'bind-source-prometheus-desktop'; Service = 'prometheus'; Fail = ''; Env = @{}; Absent = @(); Success = $true; Mutating = $true },
                [pscustomobject]@{ Name = 'container-name-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_NAME = 'wrong' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'network-namespace-mismatch'; Service = 'keycloak'; Fail = ''; Env = @{ FINGUARDOPS_D225_NAMESPACE = 'wrong' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'network-attachment-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_NETWORK = 'wrong' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'mount-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_MOUNT = 'wrong' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'duplicate-ambiguous-container'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D225_DUPLICATE = '1' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unexpected-project-labeled-container'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_FOREIGN = '1' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'project-label-mismatch'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D209_PROJECT = 'unrelated' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unrelated-network-attachment'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D209_UNRELATED_NETWORK = '1' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'unrelated-volume-user'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D209_UNRELATED_VOLUME = '1' }; Absent = @(); Success = $false; Mutating = $false },
                [pscustomobject]@{ Name = 'foreign-container-after-preflight'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_FOREIGN_AFTER = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @('stop ' + ('f' * 64)) },
                [pscustomobject]@{ Name = 'exact-name-full-id-replaced'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_REPLACE_AFTER = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @('stop ' + ('f' * 64)) },
                [pscustomobject]@{ Name = 'container-stop-failure'; Service = ''; Fail = 'stop'; Env = @{}; Absent = @(); Success = $false; Mutating = $true; Expected = @('stop ' + ('f' * 64)) },
                [pscustomobject]@{ Name = 'container-remove-failure'; Service = ''; Fail = 'rm'; Env = @{}; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64))) },
                [pscustomobject]@{ Name = 'network-attachment-after-container-removal'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_NET_ATTACH_AFTER = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64))) },
                [pscustomobject]@{ Name = 'network-id-replaced'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_NET_REPLACE_AFTER = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64))) },
                [pscustomobject]@{ Name = 'network-remove-failure'; Service = ''; Fail = 'network'; Env = @{}; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64)), ('network rm ' + ('e' * 64))) },
                [pscustomobject]@{ Name = 'volume-user-after-network-removal'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_VOL_USER_AFTER = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64)), ('network rm ' + ('e' * 64))) },
                [pscustomobject]@{ Name = 'volume-identity-replaced'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D242_VOLUME_SWAP = '1' }; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64))) },
                # Each of these leaves the volume in place right up to the
                # removal and then changes exactly one field of its identity on
                # the re-read the removal depends on. None of them may reach a
                # `volume rm`.
                [pscustomobject]@{ Name = 'volume-created-at-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'createdat' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-driver-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'driver' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-scope-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'scope' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-mountpoint-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'mountpoint' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-labels-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'labels' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-options-changed'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_DRIFT_AT = [string]$finalInspect; FINGUARDOPS_D248_DRIFT_FIELD = 'options' }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                # A container that attaches to the volume between the identity
                # re-read and the removal.
                [pscustomobject]@{ Name = 'volume-connection-added-before-removal'; Service = ''; Fail = ''; Env = @{ FINGUARDOPS_D248_USER_AT = [string]$finalInspect }; Absent = @(); Success = $false; Mutating = $true; Expected = $volumeDriftMutations },
                [pscustomobject]@{ Name = 'volume-remove-failure'; Service = ''; Fail = 'volume'; Env = @{}; Absent = @(); Success = $false; Mutating = $true; Expected = @(('stop ' + ('f' * 64)), ('rm ' + ('f' * 64)), ('network rm ' + ('e' * 64)), ('volume rm ' + $project + '_keycloak-data')) }
            )

            foreach ($case in $cases) {
                if ($env:FINGUARDOPS_D242_CASE_FILTER -and $case.Name -cne $env:FINGUARDOPS_D242_CASE_FILTER) { continue }
                foreach ($path in ($allStates + $flags)) { if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) } }
                $service = if ($case.Service) { $case.Service } else { 'postgresql' }
                # Only postgresql mounts the anonymous volume, so it is the
                # only service whose cleanup is expected to reach one.
                $live = @($state, $networkState, $namedState)
                if ($service -ceq 'postgresql') { $live += $anonymousState }
                if ($service -in $sharedServices) { $live += $backendState }
                $live = @($live | Where-Object { $case.Absent -cnotcontains [System.IO.Path]::GetFileName($_) })
                if ($case.Absent -ccontains 'container-state.txt') { $live = @($live | Where-Object { $_ -cne $backendState }) }
                foreach ($path in $live) { [System.IO.File]::WriteAllText($path, 'running', [System.Text.Encoding]::ASCII) }
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
                foreach ($name in $scenarioNames) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
                $env:FINGUARDOPS_D209_SERVICE = $case.Service
                $env:FINGUARDOPS_D209_FAIL = $case.Fail
                foreach ($name in $case.Env.Keys) { [System.Environment]::SetEnvironmentVariable($name, $case.Env[$name], 'Process') }
                $markers.Clear()
                $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext $context }
                $commands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
                $mutations = @($commands | Where-Object { $_ -cmatch '^(stop|rm|network rm|volume rm) ' })

                Assert-True (@($commands | Where-Object { $_ -cmatch '(^|\s)down(\s|$)' }).Count -eq 0) "$($case.Name) issued a Compose down."
                foreach ($command in $commands) {
                    foreach ($token in ($command -split ' ')) {
                        if ($token -cnotmatch '^name=') { continue }
                        $value = $token -creplace '^name=\^?/?', '' -creplace '\$$', ''
                        Assert-True ($contractNames -ccontains $value) "$($case.Name) queried a name that is not an exact contract name: $token"
                    }
                }
                Assert-True (@($commands | Where-Object {
                    $_ -cmatch '--remove-orphans|--force|--volumes|(^|\s)prune(\s|$)' -or
                    ($_ -cmatch '^(stop|rm|network rm|volume rm|image rm) ' -and $_ -cmatch '(^|\s)-f(\s|$)')
                }).Count -eq 0) "$($case.Name) used a forced or project-wide removal."
                foreach ($command in $mutations) {
                    Assert-True ($command -cmatch ('^stop (' + $id + '|' + $backendId + ')$') -or
                        $command -cmatch ('^rm (' + $id + '|' + $backendId + ')$') -or
                        $command -cmatch '^network rm [0-9a-f]{64}$' -or
                        $command -cmatch ('^volume rm (' + [regex]::Escape($project) + '_[a-z-]+|[0-9a-f]{64})$')) `
                        "$($case.Name) removed something by an identifier this run never approved: $command"
                }
                if ($null -ne $case.PSObject.Properties['Expected']) {
                    Assert-Equal @($case.Expected) @($mutations) "$($case.Name) mutation sequence differs."
                }
                if (-not $case.Mutating) {
                    Assert-Equal @() @($mutations) "$($case.Name) mutated before the ownership guard."
                }

                if ($case.Success) {
                    $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                    Assert-True ($null -eq $failure) ("$($case.Name) failed: $detail commands=" + ($commands -join ';'))
                    Assert-Equal @('resource', 'image', 'audit', 'receipt') @($markers) "$($case.Name) cleanup order differs."
                    Assert-True (-not [System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) left the receipt."
                    foreach ($path in $live) { Assert-True (-not [System.IO.File]::Exists($path)) "$($case.Name) left an owned resource: $path" }
                }
                else {
                    Assert-True ($null -ne $failure) "$($case.Name) did not fail."
                    Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message "$($case.Name) returned the wrong fixed error."
                    Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) removed the receipt."
                    Assert-Equal @('resource') @($markers) "$($case.Name) ran image cleanup, the final audit or the receipt delete."
                    Assert-NoRawCleanupDetail $failure "$($case.Name) reflected a raw Docker detail."
                    [System.IO.File]::Delete($fixture.Recovery)
                }
            }

            foreach ($name in $scenarioNames) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }

            # Image cleanup, the final residue audit and the receipt delete are
            # separate gates: each one that fails must stop everything after it
            # and must leave the receipt behind.
            $lateCases = @(
                [pscustomobject]@{ Name = 'image-cleanup-failure'; Failing = 'ImageCleanup'; Code = 'IMAGE_CLEANUP_FAILED'; Markers = @('resource', 'image') },
                [pscustomobject]@{ Name = 'final-residue-audit-failure'; Failing = 'FinalAudit'; Code = 'CLEANUP_RESIDUE_DETECTED'; Markers = @('resource', 'image', 'audit') },
                [pscustomobject]@{ Name = 'receipt-delete-failure'; Failing = 'DeleteFile'; Code = 'RECEIPT_DELETE_FAILED'; Markers = @('resource', 'image', 'audit', 'receipt') }
            )
            foreach ($case in $lateCases) {
                foreach ($path in ($allStates + $flags)) { if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) } }
                foreach ($path in @($state, $networkState, $namedState, $anonymousState)) { [System.IO.File]::WriteAllText($path, 'running', [System.Text.Encoding]::ASCII) }
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
                $markers.Clear()
                $lateContext = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
                $lateContext.LeafBoundaries.ResourceCleanup = $context.LeafBoundaries.ResourceCleanup
                $failingLeaf = $case.Failing
                $lateMarkers = $markers
                $lateContext.LeafBoundaries[$failingLeaf] = {
                    param($value)
                    $lateMarkers.Add($(if ($failingLeaf -eq 'ImageCleanup') { 'image' } elseif ($failingLeaf -eq 'FinalAudit') { 'audit' } else { 'receipt' }))
                    throw 'NeverReflect C:\sensitive\late credential'
                }.GetNewClosure()
                $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext $lateContext }
                Assert-True ($null -ne $failure) "$($case.Name) did not fail."
                Assert-Equal $case.Code $failure.Message "$($case.Name) returned the wrong fixed error."
                Assert-Equal @($case.Markers) @($markers) "$($case.Name) step order differs."
                Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) removed the receipt."
                Assert-NoRawCleanupDetail $failure "$($case.Name) reflected a raw cleanup detail."
                [System.IO.File]::Delete($fixture.Recovery)
            }

            # The production residue audit itself, rather than a stand-in for
            # it: a clean world passes, and anything still owned is refused.
            foreach ($path in ($allStates + $flags)) { if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) } }
            foreach ($name in $scenarioNames) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $env:FINGUARDOPS_D242_IMAGES_GONE = '1'
            $auditReceipt = New-TestReceipt
            $auditPrevious = & $script:E2EModule { param($value) Set-E2EOwnerEnvironment -Receipt $value } $auditReceipt
            try {
                $failure = Get-CapturedException { & $script:E2EModule { param($value) Invoke-E2EResidueAudit -Receipt $value } $auditReceipt }
                $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                Assert-True ($null -eq $failure) "A clean residue audit failed: $detail"
                $commands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
                Assert-True (@($commands | Where-Object { $_ -cmatch '^(stop|rm|network rm|volume rm|image rm) ' }).Count -eq 0) 'The residue audit mutated something.'

                [System.IO.File]::WriteAllText($state, 'running', [System.Text.Encoding]::ASCII)
                $failure = Get-CapturedException { & $script:E2EModule { param($value) Invoke-E2EResidueAudit -Receipt $value } $auditReceipt }
                Assert-True ($null -ne $failure) 'A leftover owned container passed the residue audit.'
                Assert-Equal 'CLEANUP_RESIDUE_DETECTED' $failure.Message 'A leftover owned container returned the wrong fixed error.'
                [System.IO.File]::Delete($state)

                $env:FINGUARDOPS_D242_IMAGES_GONE = $null
                $failure = Get-CapturedException { & $script:E2EModule { param($value) Invoke-E2EResidueAudit -Receipt $value } $auditReceipt }
                Assert-True ($null -ne $failure) 'A leftover owned image passed the residue audit.'
                Assert-Equal 'CLEANUP_RESIDUE_DETECTED' $failure.Message 'A leftover owned image returned the wrong fixed error.'
            }
            finally {
                & $script:E2EModule { param($value) Restore-E2EOwnerEnvironment -Previous $value } $auditPrevious
                $env:FINGUARDOPS_D242_IMAGES_GONE = $null
            }

            # A primary failure keeps its own exception object even when the
            # cleanup that follows it fails too, and the receipt stays.
            foreach ($path in ($allStates + $flags)) { if ([System.IO.File]::Exists($path)) { [System.IO.File]::Delete($path) } }
            foreach ($path in @($state, $networkState, $namedState, $anonymousState)) { [System.IO.File]::WriteAllText($path, 'running', [System.Text.Encoding]::ASCII) }
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            New-E2EReceiptFile -Path $fixture.Recovery -Receipt (New-TestReceipt) -RepositoryRoot $fixture.Root
            $env:FINGUARDOPS_D209_FAIL = 'stop'
            $markers.Clear()
            $primary = [System.InvalidOperationException]::new('RUN_PRIMARY')
            $lifecycle = @{
                ReadPrepared = { return New-TestReceipt }
                RenamePreparedToRecovery = {}
                AssertImages = { param($value) throw $primary }.GetNewClosure()
                RunBrowser = {}
                Cleanup = { param($value) Invoke-E2ECleanupMode -CleanupContext $context }.GetNewClosure()
            }
            $failure = Get-CapturedException { Invoke-E2ERunLifecycle -Boundaries $lifecycle }
            $env:FINGUARDOPS_D209_FAIL = $null
            Assert-True ([object]::ReferenceEquals($primary, $failure)) 'A cleanup failure replaced the primary exception object.'
            Assert-True ([System.IO.File]::Exists($fixture.Recovery)) 'A primary plus cleanup failure removed the receipt.'
            Assert-Equal @('resource') @($markers) 'A failed resource cleanup still ran a later step.'
            $commands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
            Assert-True (@($commands | Where-Object { $_ -cmatch '(^|\s)down(\s|$)' }).Count -eq 0) 'The primary plus cleanup case issued a Compose down.'
        }
        finally {
            $env:PATH = $oldPath
            foreach ($name in $environmentNames) { [System.Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
            Remove-OwnerFixFixtureRoot $fixture.Root
        }
    }
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D242 exact-resource Cleanup targeted passed'
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

    Invoke-TestCase 'Targeted 02 Run primary plus project resource cleanup failure' {
        $primary = [System.InvalidOperationException]::new('PRIMARY_BROWSER_FAILURE')
        Invoke-RunCleanupContractCase -FailActions @('Compose') -Primary $primary -ExpectedCleanupCode 'RESOURCE_CLEANUP_FAILED'
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
        Invoke-RunCleanupContractCase -FailActions @('Compose', 'Output') -Primary $null -ExpectedCleanupCode 'RESOURCE_CLEANUP_FAILED'
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
            Assert-Equal @('resource','image','audit','receipt') @($calls) 'Cleanup mode did not dispatch through the production lifecycle.'
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
            Assert-Equal @('resource','image','audit','receipt') @($calls) 'Prepared receipt cleanup order differs.'
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
            Assert-Equal @('resource','image','audit','receipt') @($calls) 'Recovery receipt cleanup order differs.'
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
    if (-not $lock.WaitOne(0)) { $lock.Dispose(); throw 'E2E_LOCK_BUSY' }
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
    'Assert-E2EPreparedBrowserRuntime',
    'Invoke-E2EServiceChild',
    'Assert-E2EContainerImages',
    'Invoke-E2EProjectCleanup',
    'Invoke-E2EBrowserRunCore',
    'Assert-SafeCertificate',
    'Assert-CertificateKeyPair',
    'Remove-OwnedContainer',
    'Get-OwnedContainerPresence',
    'Assert-OwnedContainerRemovable',
    'Assert-E2EOwnedBrowserContainer',
    'Assert-E2ENoOwnedBrowserResidue',
    'Get-E2EBrowserOwnershipContract',
    'Get-BrowserServerExpectation',
    'Get-BrowserServerExpectedBinds',
    'Get-BrowserServerApprovedBinds',
    'Get-E2EVolumeIdentity'
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
            function script:Get-LocalImageDocument {
                param([string]$Reference)
                $identifier = if ($Reference -cmatch '^finguardops-backend:') { 'sha256:' + ('b' * 64) }
                    elseif ($Reference -cmatch '^finguardops-ai-service:') { 'sha256:' + ('a' * 64) }
                    else { 'sha256:' + ('d' * 64) }
                return [pscustomobject]@{ Id = $identifier; Config = [pscustomobject]@{ Labels = [pscustomobject]@{} } }
            }
            function script:Assert-E2EOwnedImages {
                param($Receipt)
                $script:SessionStateEvents.Add(('assert-images:' + $script:SessionStateMode))
                $refs = Get-E2EImageSet -Receipt $Receipt
                return [ordered]@{
                    Backend = [pscustomobject]@{ Reference=$refs.Backend; Id=('sha256:' + ('b' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $Receipt -Role 'backend'); Role='backend'; InUse=$false }
                    AiService = [pscustomobject]@{ Reference=$refs.AiService; Id=('sha256:' + ('a' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $Receipt -Role 'ai-service'); Role='ai-service'; InUse=$false }
                    Browser = [pscustomobject]@{ Reference=$refs.Browser; Id=('sha256:' + ('d' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $Receipt -Role 'browser'); Role='browser'; InUse=$false }
                }
            }
            function script:Assert-E2EPreparedBrowserRuntime {
                param($Receipt)
                $script:SessionStateEvents.Add(('browser-runtime:' + $script:SessionStateMode))
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
            param([string]$ContainerId, [string]$ImageId)
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
            'assert-images:Service','browser-runtime:Service','service-child','service-containers','service-project-cleanup',
            'receipt-move:Service','owner-restore:Service',
            'receipt-read','source:Run','owner-set:Run','receipt-move:Run','assert-images:Run',
            'run-browser','full-cleanup:Run','owner-restore:Run',
            'receipt-read','source:Validate','owner-set:Validate','assert-images:Validate',
            'browser-runtime:Validate','validate-certificate','validate-key-pair','owner-restore:Validate'
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

# --- D253 Cleanup mode browser ownership boundary ---------------------------
#
# The fake below is a Docker daemon and nothing else. It answers `ps`,
# `image inspect`, `container inspect`, `stop` and `rm` about one container
# whose whole state it keeps in files, it serves whatever inspect document the
# case under test wrote for it, and it records every argument vector it was
# given. It judges nothing: every decision about whether that container may be
# stopped or removed is left to the production module, so a case that is
# refused here is refused by the production ownership validator and by nothing
# in this harness.
function New-D253BrowserDockerFake {
    param([Parameter(Mandatory = $true)][string]$Root)

    [System.IO.Directory]::CreateDirectory($Root) | Out-Null
    $shim = Join-Path $Root 'docker.cmd'
    $source = Join-Path $Root 'docker-shim.ps1'
    [System.IO.File]::WriteAllText($shim, "@echo off`r`nset `"FINGUARDOPS_D253_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n", [System.Text.Encoding]::ASCII)
    $fakeSource = @'
$DockerArgs = $env:FINGUARDOPS_D253_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D253_ROOT
$events = Join-Path $root 'events.txt'
$state = Join-Path $root 'container-state.txt'
$stoppedFlag = Join-Path $root 'stopped.flag'
[System.IO.File]::AppendAllText($events, ($DockerArgs -join ' ') + "`n")
$id = [System.IO.File]::ReadAllText((Join-Path $root 'container-id.txt')).Trim()
$reference = [System.IO.File]::ReadAllText((Join-Path $root 'image-reference.txt')).Trim()
$name = [System.IO.File]::ReadAllText((Join-Path $root 'container-name.txt')).Trim()
$present = [System.IO.File]::Exists($state)

if ($DockerArgs[0] -eq 'image' -and $DockerArgs[1] -eq 'inspect') {
    if ($DockerArgs[-1] -cne $reference) { exit 1 }
    $imagePath = Join-Path $root 'image.json'
    if (-not [System.IO.File]::Exists($imagePath)) { exit 1 }
    Write-Output ([System.IO.File]::ReadAllText($imagePath))
    exit 0
}
if ($DockerArgs[0] -eq 'container' -and $DockerArgs[1] -eq 'inspect') {
    if (-not $present -or $DockerArgs[-1] -cne $id) { exit 1 }
    $after = Join-Path $root 'document-after.json'
    $documentPath = if ([System.IO.File]::Exists($stoppedFlag) -and [System.IO.File]::Exists($after)) {
        $after
    }
    else {
        Join-Path $root 'document.json'
    }
    $status = [System.IO.File]::ReadAllText($state).Trim()
    $running = if ($status -ceq 'running') { 'true' } else { 'false' }
    $document = [System.IO.File]::ReadAllText($documentPath)
    $document = $document.Replace('@@STATUS@@', $status).Replace('"@@RUNNING@@"', $running)
    if ($env:FINGUARDOPS_D253_INSPECT -eq 'array') { $document = '[' + $document + ',' + $document + ']' }
    Write-Output $document
    exit 0
}
if ($DockerArgs[0] -eq 'ps') {
    $byId = $null
    $byName = $false
    foreach ($token in $DockerArgs) {
        if ($token -cmatch '^id=([0-9a-f]{64})$') { $byId = $Matches[1] }
        if ($token -cmatch ([regex]::Escape('name=/' + $name + '$'))) { $byName = $true }
    }
    if ($null -ne $byId) {
        if ($present -and $byId -ceq $id) { Write-Output $id }
        exit 0
    }
    if (-not $byName) { exit 81 }
    if ($env:FINGUARDOPS_D253_DISCOVER -eq 'fail') { exit 29 }
    if ($present) {
        if ($env:FINGUARDOPS_D253_DISCOVER -eq 'duplicate') {
            Write-Output $id
            Write-Output ('9' * 64)
        }
        elseif ($env:FINGUARDOPS_D253_DISCOVER -eq 'partial') {
            Write-Output $id.Substring(0, 12)
        }
        else {
            Write-Output $id
        }
        exit 0
    }
    if ($env:FINGUARDOPS_D253_RESIDUE -eq '1') { Write-Output $id }
    exit 0
}
if ($DockerArgs[0] -eq 'stop') {
    if (-not $present -or $DockerArgs[1] -cne $id) { exit 81 }
    if ($env:FINGUARDOPS_D253_FAIL -eq 'stop') { exit 17 }
    [System.IO.File]::WriteAllText($state, 'exited')
    [System.IO.File]::WriteAllText($stoppedFlag, '1')
    Write-Output $DockerArgs[1]
    exit 0
}
if ($DockerArgs[0] -eq 'rm' -and $DockerArgs[1] -cnotmatch '^-') {
    if (-not $present -or $DockerArgs[1] -cne $id) { exit 81 }
    if ($env:FINGUARDOPS_D253_FAIL -eq 'rm') { exit 17 }
    [System.IO.File]::Delete($state)
    Write-Output $DockerArgs[1]
    exit 0
}
exit 81
'@
    [System.IO.File]::WriteAllText($source, ($fakeSource -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Assert-Parsed $source
    return $shim
}

# One `docker container inspect` document for a container that satisfies the
# production browser ownership contract completely. Every case below starts
# from this and changes exactly one thing, so what a rejection is about is the
# one field that differs.
function New-D253ContainerDocument {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)]$Labels,
        [Parameter(Mandatory = $true)]$Binds
    )

    $bindArguments = [System.Collections.Generic.List[string]]::new()
    $mounts = [System.Collections.Generic.List[object]]::new()
    foreach ($bind in $Binds) {
        $bindArguments.Add($bind.Source + ':' + $bind.Destination + ':ro')
        $mounts.Add([ordered]@{
            Type        = 'bind'
            Source      = $bind.Source
            Destination = $bind.Destination
            Mode        = 'ro'
            RW          = $false
            Propagation = 'rprivate'
        })
    }
    $labelMap = [ordered]@{}
    foreach ($key in $Labels.Keys) { $labelMap[$key] = [string]$Labels[$key] }
    return [ordered]@{
        Id     = $Id
        Name   = '/' + $Name
        Image  = $ImageId
        Config = [ordered]@{ Image = $ImageId; Labels = $labelMap }
        State  = [ordered]@{ Status = '@@STATUS@@'; Running = '@@RUNNING@@' }
        HostConfig = [ordered]@{
            NetworkMode       = 'bridge'
            ReadonlyRootfs    = $false
            Init              = $true
            Privileged        = $false
            PublishAllPorts   = $false
            CapAdd            = @()
            CapDrop           = @()
            SecurityOpt       = @()
            ExtraHosts        = @('host.docker.internal:host-gateway')
            Devices           = @()
            DeviceRequests    = @()
            DeviceCgroupRules = @()
            VolumesFrom       = @()
            Mounts            = @()
            Binds             = $bindArguments.ToArray()
            Tmpfs             = [ordered]@{}
            PortBindings      = [ordered]@{ '3500/tcp' = @([ordered]@{ HostIp = '127.0.0.1'; HostPort = '14250' }) }
        }
        NetworkSettings = [ordered]@{ Networks = [ordered]@{ bridge = [ordered]@{} } }
        Mounts          = $mounts.ToArray()
    }
}

function Add-D253Mount($Document, $Entry) {
    $mounts = [System.Collections.Generic.List[object]]::new()
    foreach ($mount in @($Document['Mounts'])) { $mounts.Add($mount) }
    $mounts.Add($Entry)
    $Document['Mounts'] = $mounts.ToArray()
}

function Write-D253Document([string]$Path, $Document) {
    [System.IO.File]::WriteAllText($Path, ($Document | ConvertTo-Json -Depth 10 -Compress), [System.Text.UTF8Encoding]::new($false))
}

function Get-D253DockerCommands([string]$Path) {
    if (-not [System.IO.File]::Exists($Path)) { return @() }
    return @([System.IO.File]::ReadAllLines($Path) | Where-Object { $_ })
}

function Assert-D253SafeRemoval($Commands, [string]$Message) {
    Assert-True (@($Commands | Where-Object {
        $_ -cmatch '(^|\s)--force(\s|$)' -or $_ -cmatch '(^|\s)--volumes(\s|$)' -or
        $_ -cmatch '(^|\s)--remove-orphans(\s|$)' -or $_ -cmatch '(^|\s)prune(\s|$)' -or
        ($_ -cmatch '^(stop|rm) ' -and $_ -cmatch '(^|\s)-f(\s|$)')
    }).Count -eq 0) ($Message + ' commands=' + ($Commands -join ';'))
}

# Every stop and every removal names the one full 64-hex identifier discovery
# produced, and nothing is ever stopped or removed by name, prefix or label.
function Assert-D253ExactIdentifierOnly($Commands, [string]$Id, [string]$Message) {
    Assert-True (@($Commands | Where-Object {
        $_ -cmatch '^(stop|rm) ' -and $_ -cnotmatch ('^(stop|rm) ' + $Id + '$')
    }).Count -eq 0) ($Message + ' commands=' + ($Commands -join ';'))
}

function Get-D253Cases {
    param([string]$Id, [string]$Name, [string]$ImageId)

    $foreignImage = 'sha256:' + ('9' * 64)
    return @(
        # The three shapes the world can honestly be in.
        [pscustomobject]@{ Name = 'running-owned-browser'; Start = 'running'; Succeeds = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'stopped-owned-browser'; Start = 'exited'; Succeeds = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'absent-owned-browser'; Start = ''; Succeeds = $true; Mutate = $null },
        # The fixed name and this run's image, with something nobody approved.
        [pscustomobject]@{ Name = 'unexpected-named-volume'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            Add-D253Mount $document ([ordered]@{ Type = 'volume'; Name = 'unexpected-volume'; Source = ''
                Destination = '/data'; Mode = 'z'; RW = $true; Propagation = '' })
        } },
        [pscustomobject]@{ Name = 'unexpected-anonymous-volume'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            Add-D253Mount $document ([ordered]@{ Type = 'volume'; Name = ('a' * 64); Source = ''
                Destination = '/cache'; Mode = 'z'; RW = $true; Propagation = '' })
        } },
        [pscustomobject]@{ Name = 'unexpected-bind'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $binds = @(@($document['HostConfig']['Binds']) + @('C:\outside\secrets:/finguardops/extra:ro'))
            $document['HostConfig']['Binds'] = $binds
            Add-D253Mount $document ([ordered]@{ Type = 'bind'; Source = 'C:\outside\secrets'
                Destination = '/finguardops/extra'; Mode = 'ro'; RW = $false; Propagation = 'rprivate' })
        } },
        [pscustomobject]@{ Name = 'wrong-config-image'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Config']['Image'] = 'sha256:' + ('9' * 64)
        } },
        [pscustomobject]@{ Name = 'wrong-authoritative-image-id'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Image'] = 'sha256:' + ('9' * 64)
        } },
        [pscustomobject]@{ Name = 'ownership-label-mismatch'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Config']['Labels']['com.finguardops.e2e.run-id'] = 'f' * 32
        } },
        [pscustomobject]@{ Name = 'ownership-label-missing'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Config']['Labels'].Remove('com.finguardops.e2e.source-tree')
        } },
        [pscustomobject]@{ Name = 'wrong-image-role'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Config']['Labels']['com.finguardops.e2e.image-role'] = 'backend'
        } },
        [pscustomobject]@{ Name = 'wrong-container-name'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Name'] = '/finguardops-keycloak-browser-e2e-other'
        } },
        [pscustomobject]@{ Name = 'wrong-network-mode'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['NetworkMode'] = 'host'
        } },
        [pscustomobject]@{ Name = 'unapproved-attached-network'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['NetworkSettings']['Networks'] = [ordered]@{ bridge = [ordered]@{}; other = [ordered]@{} }
        } },
        [pscustomobject]@{ Name = 'wrong-port-binding'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $document['HostConfig']['PortBindings'] = [ordered]@{ '3500/tcp' = @([ordered]@{ HostIp = '0.0.0.0'; HostPort = '14250' }) }
        } },
        [pscustomobject]@{ Name = 'extra-port-binding'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $document['HostConfig']['PortBindings'] = [ordered]@{
                '3500/tcp' = @([ordered]@{ HostIp = '127.0.0.1'; HostPort = '14250' })
                '9229/tcp' = @([ordered]@{ HostIp = '127.0.0.1'; HostPort = '9229' })
            }
        } },
        [pscustomobject]@{ Name = 'publish-all-ports'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['PublishAllPorts'] = $true
        } },
        [pscustomobject]@{ Name = 'privileged-container'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['Privileged'] = $true
        } },
        [pscustomobject]@{ Name = 'unexpected-device'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $document['HostConfig']['Devices'] = @([ordered]@{ PathOnHost = '\\.\pipe\docker_engine'
                PathInContainer = '/dev/engine'; CgroupPermissions = 'rwm' })
        } },
        [pscustomobject]@{ Name = 'unexpected-device-request'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $document['HostConfig']['DeviceRequests'] = @([ordered]@{ Driver = 'nvidia'; Count = 1 })
        } },
        [pscustomobject]@{ Name = 'unexpected-added-capability'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['CapAdd'] = @('CAP_SYS_ADMIN')
        } },
        [pscustomobject]@{ Name = 'unexpected-security-option'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['SecurityOpt'] = @('seccomp=unconfined')
        } },
        [pscustomobject]@{ Name = 'unexpected-extra-host'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['ExtraHosts'] = @('host.docker.internal:host-gateway', 'registry:10.0.0.1')
        } },
        [pscustomobject]@{ Name = 'unexpected-tmpfs'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['Tmpfs'] = [ordered]@{ '/scratch' = 'rw' }
        } },
        [pscustomobject]@{ Name = 'unexpected-structured-mount'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document)
            $document['HostConfig']['Mounts'] = @([ordered]@{ Type = 'volume'; Source = 'other'; Target = '/other' })
        } },
        [pscustomobject]@{ Name = 'inherited-volumes-from'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['VolumesFrom'] = @('finguardops-other')
        } },
        [pscustomobject]@{ Name = 'writable-root-filesystem'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['ReadonlyRootfs'] = $true
        } },
        [pscustomobject]@{ Name = 'init-mismatch'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['HostConfig']['Init'] = $false
        } },
        # The identifier discovery pinned is not the identifier the daemon
        # answered about.
        [pscustomobject]@{ Name = 'replaced-container-identifier'; Start = 'exited'; Succeeds = $false; Mutate = {
            param($document) $document['Id'] = 'c' * 64
        } }
    )
}

function Invoke-D253CleanupBoundaryTests {
    Invoke-TestCase 'D253 production Cleanup mode browser removal is one owned exact container' {
        $fixture = New-OwnerFixFixture
        $dockerRoot = Join-Path $fixture.Root 'fake-docker'
        $state = Join-Path $dockerRoot 'container-state.txt'
        $stoppedFlag = Join-Path $dockerRoot 'stopped.flag'
        $events = Join-Path $dockerRoot 'events.txt'
        $documentPath = Join-Path $dockerRoot 'document.json'
        $afterPath = Join-Path $dockerRoot 'document-after.json'
        $id = 'b' * 64
        $imageId = 'sha256:' + ('d' * 64)
        $name = 'finguardops-keycloak-browser-e2e-chromium'
        $names = @('FINGUARDOPS_D253_ROOT', 'FINGUARDOPS_D253_FAIL', 'FINGUARDOPS_D253_DISCOVER',
            'FINGUARDOPS_D253_RESIDUE', 'FINGUARDOPS_D253_INSPECT')
        $previous = @{}
        foreach ($entry in $names) { $previous[$entry] = [System.Environment]::GetEnvironmentVariable($entry, 'Process') }
        $oldPath = $env:PATH
        try {
            $shim = New-D253BrowserDockerFake -Root $dockerRoot
            foreach ($entry in $names) { [System.Environment]::SetEnvironmentVariable($entry, $null, 'Process') }
            $env:FINGUARDOPS_D253_ROOT = $dockerRoot
            $env:PATH = $dockerRoot + [System.IO.Path]::PathSeparator + $oldPath
            Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D253 Docker fake sentinel was not selected.'

            $receipt = New-TestReceipt
            $reference = (Get-E2EImageSet -Receipt $receipt).Browser
            $labels = Get-E2EOwnershipLabels -Receipt $receipt -Role 'browser'
            Assert-Equal 5 @($labels.Keys).Count 'The browser ownership label set is not five labels.'
            Assert-Equal $name (& $script:E2EModule { $BrowserContainerName }) 'The fixed browser container name changed.'
            # The mount set the production creation contract approves. The
            # fixture describes a compliant container with it; it never decides
            # whether a container is compliant.
            $contractBinds = @(& $script:E2EModule { Get-BrowserServerExpectedBinds })
            Assert-Equal 3 $contractBinds.Count 'The production browser bind contract is not three binds.'

            [System.IO.File]::WriteAllText((Join-Path $dockerRoot 'container-id.txt'), $id, [System.Text.Encoding]::ASCII)
            [System.IO.File]::WriteAllText((Join-Path $dockerRoot 'container-name.txt'), $name, [System.Text.Encoding]::ASCII)
            [System.IO.File]::WriteAllText((Join-Path $dockerRoot 'image-reference.txt'), $reference, [System.Text.Encoding]::ASCII)
            Write-D253Document (Join-Path $dockerRoot 'image.json') ([ordered]@{
                Id = $imageId
                Config = [ordered]@{ Labels = $labels }
            })

            $discover = 'ps -aq --no-trunc --filter name=/' + $name + '$'
            $imageInspect = 'image inspect --format "{{json .}}" ' + $reference
            $presence = 'ps -a --no-trunc --filter id=' + $id + ' --format {{.ID}}'
            $inspect = 'container inspect --format "{{json .}}" ' + $id
            $runningSequence = @($discover, $imageInspect, $presence, $inspect, ('stop ' + $id), $inspect, ('rm ' + $id), $presence, $discover)
            $stoppedSequence = @($discover, $imageInspect, $presence, $inspect, ('rm ' + $id), $presence, $discover)
            $refusedSequence = @($discover, $imageInspect, $presence, $inspect)

            $markers = [System.Collections.Generic.List[string]]::new()
            $context = New-OwnerFixCleanupContext -Fixture $fixture -Markers $markers
            $context.LeafBoundaries.ResourceCleanup = & $script:E2EModule {
                param($eventSink)
                $script:D253ResourceMarkers = $eventSink
                return {
                    param($activeReceipt)
                    $script:D253ResourceMarkers.Add('resource')
                    Remove-E2EOwnedBrowserContainer -Receipt $activeReceipt
                }
            } $markers

            $cases = @(Get-D253Cases -Id $id -Name $name -ImageId $imageId)
            foreach ($case in $cases) {
                $expected = if ($case.Succeeds) {
                    if ($case.Start -eq 'running') { $runningSequence }
                    elseif ($case.Start -eq '') { @($discover) }
                    else { $stoppedSequence }
                }
                else { $refusedSequence }
                $document = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
                if ($null -ne $case.Mutate) { & $case.Mutate $document }
                Write-D253Document $documentPath $document
                if ([System.IO.File]::Exists($afterPath)) { [System.IO.File]::Delete($afterPath) }
                if ([System.IO.File]::Exists($stoppedFlag)) { [System.IO.File]::Delete($stoppedFlag) }
                if ([System.IO.File]::Exists($state)) { [System.IO.File]::Delete($state) }
                if ($case.Start -ne '') { [System.IO.File]::WriteAllText($state, $case.Start, [System.Text.Encoding]::ASCII) }
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                New-E2EReceiptFile -Path $fixture.Recovery -Receipt $receipt -RepositoryRoot $fixture.Root
                $markers.Clear()

                $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext $context }
                $commands = Get-D253DockerCommands $events
                $stops = @($commands | Where-Object { $_ -cmatch '^stop ' }).Count
                $removals = @($commands | Where-Object { $_ -cmatch '^rm ' }).Count
                Assert-Equal @($expected) @($commands) "$($case.Name) argument vectors differ."
                Assert-D253SafeRemoval $commands "$($case.Name) used a forced or wholesale removal."
                Assert-D253ExactIdentifierOnly $commands $id "$($case.Name) named something other than the exact full identifier."
                if ($case.Succeeds) {
                    $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                    Assert-True ($null -eq $failure) "$($case.Name) failed: $detail commands=$($commands -join ';')"
                    Assert-Equal @('resource', 'image', 'audit', 'receipt') @($markers) "$($case.Name) cleanup step order differs."
                    Assert-True (-not [System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) left the receipt behind."
                    Assert-True (-not [System.IO.File]::Exists($state)) "$($case.Name) left the container behind."
                }
                else {
                    Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                    Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message "$($case.Name) returned the wrong fixed error."
                    Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                    Assert-Equal 0 $stops "$($case.Name) stopped a container it does not own."
                    Assert-Equal 0 $removals "$($case.Name) removed a container it does not own."
                    Assert-Equal @('resource') @($markers) "$($case.Name) ran image cleanup, the final audit or the receipt delete."
                    Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) removed the receipt."
                    Assert-True ([System.IO.File]::Exists($state)) "$($case.Name) removed the container anyway."
                    [System.IO.File]::Delete($fixture.Recovery)
                }
            }

            # Ownership that changes while the container is being stopped is
            # caught by the re-check the common remover makes before it removes
            # anything, so the container survives a stop it already performed.
            $lateCases = @(
                [pscustomobject]@{
                    Name = 'ownership-replaced-after-stop'
                    Start = 'running'
                    Env = @{}
                    After = {
                        param($document)
                        Add-D253Mount $document ([ordered]@{ Type = 'volume'; Name = 'appeared-volume'; Source = ''
                            Destination = '/late'; Mode = 'z'; RW = $true; Propagation = '' })
                    }
                    Expected = @($discover, $imageInspect, $presence, $inspect, ('stop ' + $id), $inspect)
                    Stops = 1
                    Removals = 0
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'identifier-replaced-after-stop'
                    Start = 'running'
                    Env = @{}
                    After = { param($document) $document['Id'] = 'c' * 64 }
                    Expected = @($discover, $imageInspect, $presence, $inspect, ('stop ' + $id), $inspect)
                    Stops = 1
                    Removals = 0
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'stop-failure'
                    Start = 'running'
                    Env = @{ FINGUARDOPS_D253_FAIL = 'stop' }
                    After = $null
                    Expected = @($discover, $imageInspect, $presence, $inspect, ('stop ' + $id))
                    Stops = 1
                    Removals = 0
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'remove-failure'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_FAIL = 'rm' }
                    After = $null
                    Expected = @($discover, $imageInspect, $presence, $inspect, ('rm ' + $id))
                    Stops = 0
                    Removals = 1
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'name-residue-after-removal'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_RESIDUE = '1' }
                    After = $null
                    Expected = $stoppedSequence
                    Stops = 0
                    Removals = 1
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'multiple-inspect-documents'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_INSPECT = 'array' }
                    After = $null
                    Expected = @($discover, $imageInspect, $presence, $inspect)
                    Stops = 0
                    Removals = 0
                    Code = 'RESOURCE_CLEANUP_FAILED'
                },
                [pscustomobject]@{
                    Name = 'duplicate-name-candidates'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_DISCOVER = 'duplicate' }
                    After = $null
                    Expected = @($discover)
                    Stops = 0
                    Removals = 0
                    Code = 'RESOURCE_OWNERSHIP_INVALID'
                },
                [pscustomobject]@{
                    Name = 'partial-candidate-identifier'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_DISCOVER = 'partial' }
                    After = $null
                    Expected = @($discover)
                    Stops = 0
                    Removals = 0
                    Code = 'RESOURCE_OWNERSHIP_INVALID'
                },
                [pscustomobject]@{
                    Name = 'discovery-failure'
                    Start = 'exited'
                    Env = @{ FINGUARDOPS_D253_DISCOVER = 'fail' }
                    After = $null
                    Expected = @($discover)
                    Stops = 0
                    Removals = 0
                    Code = 'RESOURCE_CLEANUP_FAILED'
                }
            )
            foreach ($case in $lateCases) {
                foreach ($entry in $names) {
                    if ($entry -cne 'FINGUARDOPS_D253_ROOT') { [System.Environment]::SetEnvironmentVariable($entry, $null, 'Process') }
                }
                foreach ($entry in $case.Env.Keys) { [System.Environment]::SetEnvironmentVariable($entry, $case.Env[$entry], 'Process') }
                $document = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
                Write-D253Document $documentPath $document
                if ([System.IO.File]::Exists($afterPath)) { [System.IO.File]::Delete($afterPath) }
                if ($null -ne $case.After) {
                    $afterDocument = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
                    & $case.After $afterDocument
                    Write-D253Document $afterPath $afterDocument
                }
                if ([System.IO.File]::Exists($stoppedFlag)) { [System.IO.File]::Delete($stoppedFlag) }
                if ([System.IO.File]::Exists($state)) { [System.IO.File]::Delete($state) }
                [System.IO.File]::WriteAllText($state, $case.Start, [System.Text.Encoding]::ASCII)
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                New-E2EReceiptFile -Path $fixture.Recovery -Receipt $receipt -RepositoryRoot $fixture.Root
                $markers.Clear()

                $failure = Get-CapturedException { Invoke-E2ECleanupMode -CleanupContext $context }
                $commands = Get-D253DockerCommands $events
                Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                Assert-Equal $case.Code $failure.Message "$($case.Name) returned the wrong fixed error."
                Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                Assert-Equal @($case.Expected) @($commands) "$($case.Name) argument vectors differ."
                Assert-Equal $case.Stops @($commands | Where-Object { $_ -cmatch '^stop ' }).Count "$($case.Name) stop count differs."
                Assert-Equal $case.Removals @($commands | Where-Object { $_ -cmatch '^rm ' }).Count "$($case.Name) removal count differs."
                Assert-D253SafeRemoval $commands "$($case.Name) used a forced or wholesale removal."
                Assert-D253ExactIdentifierOnly $commands $id "$($case.Name) named something other than the exact full identifier."
                Assert-Equal @('resource') @($markers) "$($case.Name) ran image cleanup, the final audit or the receipt delete."
                Assert-True ([System.IO.File]::Exists($fixture.Recovery)) "$($case.Name) removed the receipt."
                [System.IO.File]::Delete($fixture.Recovery)
            }
            foreach ($entry in $names) {
                if ($entry -cne 'FINGUARDOPS_D253_ROOT') { [System.Environment]::SetEnvironmentVariable($entry, $null, 'Process') }
            }

            # The Run-end cleanup, given the same receipt-derived contract, asks
            # the daemon for exactly what the Cleanup mode asks it for once the
            # candidate identifier is known - the same presence check, the same
            # inspects, the same stop, the same removal and the same two-part
            # residue audit.
            $runContract = & $script:E2EModule { param($value) Get-E2EBrowserOwnershipContract -Receipt $value } $receipt
            Assert-Equal $imageId $runContract.ImageId 'The receipt-derived browser image identifier differs.'
            Assert-Equal $reference $runContract.Reference 'The receipt-derived browser image reference differs.'
            Assert-Equal $name $runContract.Name 'The receipt-derived browser container name differs.'
            Assert-Equal 'browser' $runContract.Role 'The receipt-derived browser role differs.'
            $remove = & $script:E2EModule {
                return { param($container, $image, $contract) Remove-OwnedContainer $container $image $contract }
            }
            $runCases = @(
                [pscustomobject]@{
                    Name = 'run-running-owned-browser'
                    Start = 'running'
                    Succeeds = $true
                    Mutate = $null
                    Expected = @($presence, $inspect, ('stop ' + $id), $inspect, ('rm ' + $id), $presence, $discover)
                },
                [pscustomobject]@{
                    Name = 'run-stopped-owned-browser'
                    Start = 'exited'
                    Succeeds = $true
                    Mutate = $null
                    Expected = @($presence, $inspect, ('rm ' + $id), $presence, $discover)
                },
                [pscustomobject]@{
                    Name = 'run-absent-owned-browser'
                    Start = ''
                    Succeeds = $true
                    Mutate = $null
                    Expected = @($presence, $discover)
                },
                [pscustomobject]@{
                    Name = 'run-unexpected-volume'
                    Start = 'exited'
                    Succeeds = $false
                    Mutate = {
                        param($document)
                        Add-D253Mount $document ([ordered]@{ Type = 'volume'; Name = 'unexpected-volume'; Source = ''
                            Destination = '/data'; Mode = 'z'; RW = $true; Propagation = '' })
                    }
                    Expected = @($presence, $inspect)
                },
                [pscustomobject]@{
                    Name = 'run-wrong-port-binding'
                    Start = 'exited'
                    Succeeds = $false
                    Mutate = {
                        param($document)
                        $document['HostConfig']['PortBindings'] = [ordered]@{ '3500/tcp' = @([ordered]@{ HostIp = '0.0.0.0'; HostPort = '14250' }) }
                    }
                    Expected = @($presence, $inspect)
                },
                [pscustomobject]@{
                    Name = 'run-ownership-label-mismatch'
                    Start = 'exited'
                    Succeeds = $false
                    Mutate = { param($document) $document['Config']['Labels']['com.finguardops.e2e.repository-id'] = 'e' * 64 }
                    Expected = @($presence, $inspect)
                }
            )
            foreach ($case in $runCases) {
                $document = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
                if ($null -ne $case.Mutate) { & $case.Mutate $document }
                Write-D253Document $documentPath $document
                if ([System.IO.File]::Exists($stoppedFlag)) { [System.IO.File]::Delete($stoppedFlag) }
                if ([System.IO.File]::Exists($state)) { [System.IO.File]::Delete($state) }
                if ($case.Start -ne '') { [System.IO.File]::WriteAllText($state, $case.Start, [System.Text.Encoding]::ASCII) }
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)

                $failure = Get-CapturedException { & $remove $id $imageId $runContract }
                $commands = Get-D253DockerCommands $events
                if ($case.Succeeds) {
                    $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                    Assert-True ($null -eq $failure) "$($case.Name) failed: $detail commands=$($commands -join ';')"
                }
                else {
                    Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                    Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
                    Assert-Equal 0 @($commands | Where-Object { $_ -cmatch '^stop ' }).Count "$($case.Name) stopped a container it does not own."
                    Assert-Equal 0 @($commands | Where-Object { $_ -cmatch '^rm ' }).Count "$($case.Name) removed a container it does not own."
                    Assert-True ([System.IO.File]::Exists($state)) "$($case.Name) removed the container anyway."
                }
                Assert-Equal @($case.Expected) @($commands) "$($case.Name) argument vectors differ."
                Assert-D253SafeRemoval $commands "$($case.Name) used a forced or wholesale removal."
                Assert-D253ExactIdentifierOnly $commands $id "$($case.Name) named something other than the exact full identifier."
            }
            # The image the caller pinned has to be the image the receipt
            # resolves to. The refusal comes out of the shared validator, so it
            # costs the two read-only queries every other refusal costs and it
            # stops before anything is stopped or removed.
            $document = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
            Write-D253Document $documentPath $document
            if ([System.IO.File]::Exists($stoppedFlag)) { [System.IO.File]::Delete($stoppedFlag) }
            [System.IO.File]::WriteAllText($state, 'exited', [System.Text.Encoding]::ASCII)
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $failure = Get-CapturedException { & $remove $id ('sha256:' + ('9' * 64)) $runContract }
            Assert-True ($null -ne $failure) 'A browser removal on an image the receipt does not name was accepted.'
            Assert-NoRawCleanupDetail $failure 'A refused browser removal reflected an internal detail.'
            $commands = Get-D253DockerCommands $events
            Assert-Equal @($presence, $inspect) @($commands) 'A refused browser removal asked the daemon something else.'
            Assert-Equal 0 @($commands | Where-Object { $_ -cmatch '^(stop|rm) ' }).Count 'A refused browser removal mutated a container.'
            Assert-True ([System.IO.File]::Exists($state)) 'A refused browser removal removed the container anyway.'

            # A browser cleanup failure that coincides with a primary failure
            # leaves the primary exception object exactly as it was, and the
            # refused container is still there.
            $document = New-D253ContainerDocument -Id $id -Name $name -ImageId $imageId -Labels $labels -Binds $contractBinds
            $document['HostConfig']['Privileged'] = $true
            Write-D253Document $documentPath $document
            [System.IO.File]::WriteAllText($state, 'exited', [System.Text.Encoding]::ASCII)
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $primary = [System.InvalidOperationException]::new('RUN_PRIMARY_FAILURE')
            $order = [System.Collections.Generic.List[string]]::new()
            $boundaries = @{
                RestoreOutputEnvironment = { $order.Add('output-env') }.GetNewClosure()
                RemoveBrowser = { $order.Add('browser'); & $remove $id $imageId $runContract }.GetNewClosure()
                RemoveProjectResources = { $order.Add('resources') }.GetNewClosure()
                ReleaseRunMutex = { $order.Add('mutex') }.GetNewClosure()
            }
            $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $primary -Boundaries $boundaries }
            Assert-True ([object]::ReferenceEquals($primary, $failure)) 'A browser cleanup failure replaced the primary exception object.'
            Assert-Equal @('output-env', 'browser', 'resources', 'mutex') @($order) 'Run cleanup boundary order differs.'
            Assert-NoRawCleanupDetail $failure 'The overlapping failure reflected a cleanup detail.'
            $commands = Get-D253DockerCommands $events
            Assert-Equal 0 @($commands | Where-Object { $_ -cmatch '^(stop|rm) ' }).Count 'The overlapping failure mutated a container.'
            Assert-True ([System.IO.File]::Exists($state)) 'The overlapping failure removed the container anyway.'

            # And a browser cleanup failure on its own is the Run boundary's
            # fixed cleanup code.
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $order.Clear()
            $failure = Get-CapturedException { Invoke-E2ERunCoreCleanup -Primary $null -Boundaries $boundaries }
            Assert-True ($null -ne $failure) 'A browser cleanup failure alone was ignored.'
            Assert-Equal 'BROWSER_CONTAINER_CLEANUP_FAILED' $failure.Message 'The browser cleanup fixed error changed.'
            Assert-True ([System.IO.File]::Exists($state)) 'A refused browser cleanup removed the container anyway.'
        }
        finally {
            $env:PATH = $oldPath
            foreach ($entry in $names) { [System.Environment]::SetEnvironmentVariable($entry, $previous[$entry], 'Process') }
            Remove-OwnerFixFixtureRoot $fixture.Root
        }
    }
}

function Get-D253Functions {
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($ModulePath, [ref]$tokens, [ref]$errors)
    Assert-Equal 0 $errors.Count 'The production module does not parse.'
    return [pscustomobject]@{
        Ast = $ast
        Functions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true))
    }
}

function Get-D253Function($Functions, [string]$Name) {
    $found = @($Functions | Where-Object { $_.Name -ceq $Name })
    Assert-Equal 1 $found.Count ("The production module does not define {0} exactly once." -f $Name)
    return $found[0]
}

function Get-D253Commands($Node) {
    return @($Node.FindAll({ param($inner) $inner -is [System.Management.Automation.Language.CommandAst] }, $true))
}

function Get-D253NamedCalls($Node, [string]$Name) {
    return @(Get-D253Commands $Node | Where-Object { $_.CommandElements[0].Extent.Text -ceq $Name })
}

function Invoke-D253CallGraphTests {
    Invoke-TestCase 'D253 Run and Cleanup mode share one browser validator and one remover' {
        $parsed = Get-D253Functions
        $functions = $parsed.Functions

        # Each of them exists exactly once, so "the same function" is a fact
        # about the module rather than about a name.
        foreach ($name in @('Remove-OwnedContainer', 'Assert-OwnedContainerRemovable',
            'Assert-E2EOwnedBrowserContainer', 'Assert-E2ENoOwnedBrowserResidue',
            'Get-E2EBrowserOwnershipContract', 'Get-BrowserServerExpectation',
            'Get-BrowserServerExpectedBinds', 'Get-BrowserServerApprovedBinds',
            'Remove-E2EOwnedBrowserContainer', 'Invoke-E2EBrowserRunCore')) {
            Get-D253Function $functions $name | Out-Null
        }

        # The browser ownership validator has exactly one caller in the whole
        # module, and it is the common removable check both browser callers go
        # through.
        $validatorCalls = @(Get-D253NamedCalls $parsed.Ast 'Assert-E2EOwnedBrowserContainer')
        Assert-Equal 1 $validatorCalls.Count 'The browser ownership validator is not called exactly once.'
        $removable = Get-D253Function $functions 'Assert-OwnedContainerRemovable'
        Assert-True ($validatorCalls[0].Extent.StartOffset -ge $removable.Extent.StartOffset -and
            $validatorCalls[0].Extent.EndOffset -le $removable.Extent.EndOffset) `
            'The browser ownership validator is called from outside the common removable check.'

        # Exactly two boundaries in the module stop or remove a container: the
        # common exact-identifier remover, and the Compose project's own exact
        # resource cleanup. Neither browser caller is one of them, so neither
        # can be holding a browser removal of its own.
        $remover = Get-D253Function $functions 'Remove-OwnedContainer'
        $projectCleanup = Get-D253Function $functions 'Invoke-E2EExactResourceCleanup'
        $dockerCommands = @(Get-D253Commands $parsed.Ast | Where-Object { $_.CommandElements[0].Extent.Text -ceq 'docker' })
        Assert-True ($dockerCommands.Count -gt 0) 'No Docker command was found in the production module.'
        $mutationOwners = [System.Collections.Generic.List[string]]::new()
        foreach ($command in $dockerCommands) {
            $words = @(@($command.CommandElements) | Select-Object -Skip 1 | ForEach-Object { $_.Extent.Text })
            if ($words.Count -eq 0) { continue }
            if ($words[0] -cnotin @('stop', 'rm')) { continue }
            $owner = $null
            foreach ($candidate in @($remover, $projectCleanup)) {
                if ($command.Extent.StartOffset -ge $candidate.Extent.StartOffset -and
                    $command.Extent.EndOffset -le $candidate.Extent.EndOffset) {
                    $owner = $candidate.Name
                }
            }
            Assert-True ($null -ne $owner) `
                ('A container stop or removal lives outside the two approved boundaries: ' + $command.Extent.Text)
            $mutationOwners.Add($owner + ':' + $words[0])
        }
        Assert-Equal @('Remove-OwnedContainer:stop', 'Remove-OwnedContainer:rm',
            'Invoke-E2EExactResourceCleanup:stop', 'Invoke-E2EExactResourceCleanup:rm') `
            @($mutationOwners) 'The set of container mutation boundaries changed.'

        # Both browser callers reach the common remover, and both of them hand
        # it the receipt-derived ownership contract.
        $cleanup = Get-D253Function $functions 'Remove-E2EOwnedBrowserContainer'
        $runCore = Get-D253Function $functions 'Invoke-E2EBrowserRunCore'
        $pairs = @()
        foreach ($table in @($runCore.FindAll({ param($inner) $inner -is [System.Management.Automation.Language.HashtableAst] }, $true))) {
            foreach ($pair in $table.KeyValuePairs) {
                if ($pair.Item1.Extent.Text -cmatch '^''?RemoveBrowser''?$') { $pairs += $pair.Item2 }
            }
        }
        Assert-Equal 1 @($pairs).Count 'The Run cleanup does not declare exactly one browser removal boundary.'
        $callers = @(
            [pscustomobject]@{ Name = 'Cleanup mode'; Node = $cleanup },
            [pscustomobject]@{ Name = 'Run cleanup boundary'; Node = @($pairs)[0] }
        )
        foreach ($caller in $callers) {
            $calls = @(Get-D253NamedCalls $caller.Node 'Remove-OwnedContainer')
            Assert-Equal 1 $calls.Count ("{0} does not call the common remover exactly once." -f $caller.Name)
            Assert-Equal 4 @($calls[0].CommandElements).Count ("{0} does not hand the common remover three arguments." -f $caller.Name)
            $contractText = @($calls[0].CommandElements)[3].Extent.Text
            $accepted = $contractText -cmatch 'Get-E2EBrowserOwnershipContract'
            foreach ($assignment in @($caller.Node.FindAll({ param($inner) $inner -is [System.Management.Automation.Language.AssignmentStatementAst] }, $true))) {
                if ($assignment.Right.Extent.Text -cmatch 'Get-E2EBrowserOwnershipContract' -and
                    $contractText -ceq $assignment.Left.Extent.Text) {
                    $accepted = $true
                }
            }
            Assert-True $accepted ("{0} does not hand the common remover the receipt-derived ownership contract." -f $caller.Name)
            Assert-Equal 1 @(Get-D253NamedCalls $caller.Node 'Get-E2EBrowserOwnershipContract').Count `
                ("{0} does not build the ownership contract exactly once." -f $caller.Name)
            Assert-Equal 0 @(Get-D253NamedCalls $caller.Node 'Get-ContainerDocument').Count `
                ("{0} inspects a container outside the common validator." -f $caller.Name)
            Assert-Equal 0 @(Get-D253NamedCalls $caller.Node 'Invoke-E2EExactResourceCleanup').Count `
                ("{0} reaches the Compose resource cleanup." -f $caller.Name)
            foreach ($inner in @(Get-D253Commands $caller.Node)) {
                $words = @(@($inner.CommandElements) | Select-Object -Skip 1 | ForEach-Object { $_.Extent.Text })
                if ($inner.CommandElements[0].Extent.Text -cne 'docker' -or $words.Count -eq 0) { continue }
                Assert-True ($words[0] -cnotin @('stop', 'rm')) `
                    ("{0} stops or removes a container itself." -f $caller.Name)
            }
        }

        # The Cleanup mode holds no ownership decision of its own: one
        # discovery query, and nothing else that reaches Docker.
        $cleanupDocker = @(Get-D253Commands $cleanup | Where-Object { $_.CommandElements[0].Extent.Text -ceq 'docker' })
        Assert-Equal 1 $cleanupDocker.Count 'The Cleanup mode browser removal asks Docker more than the one discovery query.'
        Assert-True ($cleanupDocker[0].Extent.Text -cmatch '^& docker ps ') 'The Cleanup mode discovery query is not a container listing.'

        # And the confinement both boundaries compare against is built once.
        $expectationCalls = @(Get-D253NamedCalls $parsed.Ast 'Get-BrowserServerExpectation')
        Assert-Equal 2 $expectationCalls.Count 'The browser confinement contract is not shared by exactly two boundaries.'
        $plan = Get-D253Function $functions 'Get-BrowserServerPlan'
        $contract = Get-D253Function $functions 'Get-E2EBrowserOwnershipContract'
        foreach ($owner in @($plan, $contract)) {
            Assert-Equal 1 @(Get-D253NamedCalls $owner 'Get-BrowserServerExpectation').Count `
                ("{0} does not build the shared browser confinement exactly once." -f $owner.Name)
        }
        Assert-Equal 1 @(Get-D253NamedCalls $plan 'Get-BrowserServerApprovedBinds').Count `
            'The browser creation boundary does not resolve the shared bind contract.'
        Assert-Equal 1 @(Get-D253NamedCalls $contract 'Get-BrowserServerExpectedBinds').Count `
            'The browser ownership contract does not use the shared bind contract.'
        Assert-Equal 0 @(Get-D253NamedCalls $contract 'Get-ContainerDocument').Count `
            'The browser ownership contract reads a candidate container.'
        Assert-True ($contract.Extent.Text -cnotmatch 'container inspect') `
            'The browser ownership contract derives an expected value from a container.'
    }
}

function Invoke-CleanupBrowserTargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    Invoke-D253CleanupBoundaryTests
    Invoke-D253CallGraphTests
    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'Cleanup browser targeted passed'
}

# --- D273 Compose working directory, Docker Desktop bind sources, full IDs ---
#
# Three production contracts the clean-commit runtime gate found unproven, each
# asked of the production validator itself. Every fixture below only describes
# a world - a Compose configuration the real `docker compose config` produced,
# a container document, a daemon that answers `ps`, `inspect`, `stop` and `rm`
# - and no fixture decides whether that world is acceptable. That decision is
# the production module's, every time.

function Get-D273RepositoryRoot {
    return [System.IO.Path]::GetFullPath((& $script:E2EModule { $RepositoryRoot }))
}

# The real Compose configuration, produced by the production argument vector.
# `docker compose config` reads files and resolves variables; it creates,
# starts and removes nothing.
function Get-D273ComposeConfiguration {
    param([Parameter(Mandatory = $true)][string]$Project, [Parameter(Mandatory = $true)]$Receipt)

    $previous = & $script:E2EModule { param($value) Set-E2EOwnerEnvironment -Receipt $value } $Receipt
    try {
        $encoded = & $script:E2EModule {
            param($activeProject)
            $arguments = Get-E2EComposeBaseArguments -Project $activeProject
            Invoke-E2EInLocation -Path $RepositoryRoot -Body {
                $value = Invoke-NativeStdout { & docker @arguments config --format json }
                if ($LASTEXITCODE -ne 0) { throw 'COMPOSE_CONFIG_FIXTURE_FAILED' }
                return $value
            }
        } $Project
    }
    finally { & $script:E2EModule { param($value) Restore-E2EOwnerEnvironment -Previous $value } $previous }
    return (($encoded -join "`n") | ConvertFrom-Json)
}

# The Docker Desktop Linux VM's spelling of a Windows host path, written here
# so a counterexample can differ from it by exactly one thing.
function ConvertTo-D273DesktopPath([string]$Path, [string]$Drive) {
    if ($Path -cnotmatch '^(?<drive>[A-Za-z]):[\\/](?<rest>.*)$') { throw ('D273_PATH_INVALID ' + $Path) }
    $letter = if ([string]::IsNullOrEmpty($Drive)) { $Matches['drive'].ToLowerInvariant() } else { $Drive }
    return '/run/desktop/mnt/host/' + $letter + '/' + ($Matches['rest'] -replace '\\', '/')
}

# A `prometheus` container as the daemon records one: two read-only binds, one
# named volume, two project networks, no ownership labels and no secrets.
function New-D273PrometheusDocument {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Project,
        [Parameter(Mandatory = $true)]$Definition,
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string[]]$ConfigFiles,
        [string]$BindSpelling = 'windows'
    )

    $mounts = [System.Collections.Generic.List[object]]::new()
    foreach ($volume in @($Definition.volumes)) {
        if ($volume.type -ceq 'volume') {
            $mounts.Add([ordered]@{ Type = 'volume'; Name = ($Project + '_' + $volume.source); Source = ''
                Destination = $volume.target; Mode = 'z'; RW = $true; Propagation = '' })
            continue
        }
        $source = if ($BindSpelling -ceq 'desktop') { ConvertTo-D273DesktopPath $volume.source '' } else { $volume.source }
        $mounts.Add([ordered]@{ Type = 'bind'; Name = ''; Source = $source
            Destination = $volume.target; Mode = 'ro'; RW = $false; Propagation = 'rprivate' })
    }
    $networkNames = @($Definition.networks.PSObject.Properties.Name)
    $attachments = [ordered]@{}
    foreach ($name in $networkNames) { $attachments[($Project + '_' + $name)] = [ordered]@{ NetworkID = ('e' * 64) } }
    return [ordered]@{
        Id     = $Id
        Name   = '/' + $Project + '-prometheus-1'
        Image  = $ImageId
        Config = [ordered]@{
            Image  = $Definition.image
            Labels = [ordered]@{
                'com.docker.compose.project' = $Project
                'com.docker.compose.service' = 'prometheus'
                'com.docker.compose.container-number' = '1'
                'com.docker.compose.oneoff' = 'False'
                'com.docker.compose.project.config_files' = ($ConfigFiles -join ',')
                'com.docker.compose.project.working_dir' = $WorkingDirectory
            }
        }
        State           = [ordered]@{ Status = 'exited'; Running = $false }
        HostConfig      = [ordered]@{ NetworkMode = ($Project + '_' + $networkNames[0]) }
        NetworkSettings = [ordered]@{ Networks = $attachments }
        Mounts          = $mounts.ToArray()
    }
}

# The document as the daemon hands it over, parsed the way production parses
# `docker container inspect` output.
function ConvertTo-D273Document($Value) {
    return (ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 12 -Compress))
}

function Get-D273IdentityFailure($Document, [string]$Id, [string]$Project, $Contract, $Receipt) {
    return Get-CapturedException {
        & $script:E2EModule {
            param($document, $id, $project, $contract, $receipt, $ids)
            Assert-E2EComposeContainerIdentity -Document $document -Id $id -Project $project `
                -Service 'prometheus' -Contract $contract -Receipt $receipt -AllIds $ids -PriorBackendId ''
        } $Document $Id $Project $Contract $Receipt @($Id)
    }
}

function Get-D273ComposeIdentityCases {
    param([string]$Root, [string]$Infra)

    $desktopInfra = ConvertTo-D273DesktopPath $Infra ''
    return @(
        # Approved: the canonical directory the production Compose invocation
        # actually works in, and the spellings Windows treats as the same one.
        [pscustomobject]@{ Name = 'working-dir-canonical-infra'; WorkingDirectory = $Infra; Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-forward-separators'; WorkingDirectory = $Infra.Replace('\', '/'); Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-lower-case'; WorkingDirectory = $Infra.ToLowerInvariant(); Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-upper-drive'; WorkingDirectory = $Infra.Substring(0, 1).ToUpperInvariant() + $Infra.Substring(1); Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-trailing-separator'; WorkingDirectory = $Infra + '\'; Accept = $true; Mutate = $null },
        # Refused: the repository root is the process working directory, not the
        # Compose project working directory.
        [pscustomobject]@{ Name = 'working-dir-repository-root'; WorkingDirectory = $Root; Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-sibling'; WorkingDirectory = (Join-Path $Root 'infra-sibling'); Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-prefix-sibling'; WorkingDirectory = (Join-Path $Root 'infrastructure'); Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-child'; WorkingDirectory = (Join-Path $Infra 'keycloak'); Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-suffix-only'; WorkingDirectory = 'C:\elsewhere\infra'; Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-other-drive'; WorkingDirectory = 'D:' + $Infra.Substring(2); Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-traversal'; WorkingDirectory = $Root + '\infra\..\infra'; Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-desktop-spelling'; WorkingDirectory = $desktopInfra; Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-polluted'; WorkingDirectory = $Infra + ';' + $Root; Accept = $false; Mutate = $null },
        [pscustomobject]@{ Name = 'working-dir-empty'; WorkingDirectory = ''; Accept = $false; Mutate = $null },
        [pscustomobject]@{
            Name = 'working-dir-missing'; WorkingDirectory = $Infra; Accept = $false
            Mutate = { param($document) $document['Config']['Labels'].Remove('com.docker.compose.project.working_dir') }
        },
        [pscustomobject]@{
            Name = 'working-dir-multiple'; WorkingDirectory = $Infra; Accept = $false
            Mutate = { param($document, $root, $infra) $document['Config']['Labels']['com.docker.compose.project.working_dir'] = @($infra, $root) }
        },
        # The Compose file list stays exactly what it was.
        [pscustomobject]@{
            Name = 'config-files-mismatch'; WorkingDirectory = $Infra; Accept = $false
            Mutate = { param($document) $document['Config']['Labels']['com.docker.compose.project.config_files'] = 'wrong.yml' }
        },
        [pscustomobject]@{
            Name = 'config-files-partial'; WorkingDirectory = $Infra; Accept = $false
            Mutate = { param($document, $root)
                $document['Config']['Labels']['com.docker.compose.project.config_files'] =
                    [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.yml')) }
        },
        # The bind source, in every spelling the daemon can report it in.
        [pscustomobject]@{ Name = 'bind-windows-source'; WorkingDirectory = $Infra; Accept = $true; Spelling = 'windows'; Mutate = $null },
        [pscustomobject]@{ Name = 'bind-desktop-source'; WorkingDirectory = $Infra; Accept = $true; Spelling = 'desktop'; Mutate = $null },
        [pscustomobject]@{
            Name = 'bind-desktop-other-drive'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                foreach ($mount in @($document['Mounts'])) {
                    if ($mount['Type'] -ceq 'bind') { $mount['Source'] = $mount['Source'].Replace('/mnt/host/c/', '/mnt/host/d/') }
                } }
        },
        [pscustomobject]@{
            Name = 'bind-desktop-suffix-only'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                foreach ($mount in @($document['Mounts'])) {
                    if ($mount['Type'] -ceq 'bind') { $mount['Source'] = '/run/desktop/mnt/host/c/elsewhere/infra/prometheus/prometheus.yml' }
                } }
        },
        [pscustomobject]@{
            Name = 'bind-desktop-unknown-prefix'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                foreach ($mount in @($document['Mounts'])) {
                    if ($mount['Type'] -ceq 'bind') { $mount['Source'] = $mount['Source'].Replace('/run/desktop/mnt/host/', '/mnt/host/') }
                } }
        },
        [pscustomobject]@{
            Name = 'bind-writable'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                foreach ($mount in @($document['Mounts'])) {
                    if ($mount['Type'] -ceq 'bind') { $mount['Mode'] = 'rw'; $mount['RW'] = $true }
                } }
        },
        [pscustomobject]@{
            Name = 'bind-wrong-destination'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                foreach ($mount in @($document['Mounts'])) {
                    if ($mount['Type'] -ceq 'bind') { $mount['Destination'] = '/etc/prometheus/elsewhere.yml'; break }
                } }
        },
        [pscustomobject]@{
            Name = 'bind-added'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                $extra = [ordered]@{ Type = 'bind'; Name = ''; Source = '/run/desktop/mnt/host/c/windows'
                    Destination = '/etc/prometheus/extra'; Mode = 'ro'; RW = $false; Propagation = 'rprivate' }
                $document['Mounts'] = @(@($document['Mounts']) + @($extra)) }
        },
        [pscustomobject]@{
            Name = 'bind-missing'; WorkingDirectory = $Infra; Accept = $false; Spelling = 'desktop'
            Mutate = { param($document)
                $document['Mounts'] = @(@($document['Mounts']) | Where-Object { $_['Type'] -cne 'bind' }) }
        }
    )
}

function Invoke-D273ComposeIdentityTests {
    param([string]$Project, $Receipt, $Configuration)

    $root = Get-D273RepositoryRoot
    $infra = [System.IO.Path]::GetFullPath((Join-Path $root 'infra'))
    $configFiles = @(
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.yml')),
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.keycloak-local-e2e.yml'))
    )
    $definition = $Configuration.services.PSObject.Properties['prometheus'].Value
    $id = '1' * 64
    $imageId = 'sha256:' + ('2' * 64)
    $contract = [pscustomobject]@{ Reference = $definition.image; Id = $imageId; Definition = $definition }

    foreach ($case in @(Get-D273ComposeIdentityCases -Root $root -Infra $infra)) {
        $spelling = if ($case.PSObject.Properties['Spelling']) { $case.Spelling } else { 'windows' }
        $document = New-D273PrometheusDocument -Id $id -Project $Project -Definition $definition `
            -ImageId $imageId -WorkingDirectory $case.WorkingDirectory -ConfigFiles $configFiles -BindSpelling $spelling
        if ($null -ne $case.Mutate) { & $case.Mutate $document $root $infra }
        $failure = Get-D273IdentityFailure (ConvertTo-D273Document $document) $id $Project $contract $Receipt
        if ($case.Accept) {
            $detail = if ($null -ne $failure) { $failure.Message } else { '' }
            Assert-True ($null -eq $failure) "$($case.Name) was refused: $detail"
        }
        else {
            Assert-True ($null -ne $failure) "$($case.Name) was accepted."
            Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message "$($case.Name) returned the wrong fixed error."
            Assert-NoRawCleanupDetail $failure "$($case.Name) reflected an internal detail."
        }
    }

    # The expected working directory is the repository's `infra` directory and
    # not the repository root, and it is computed from the production Compose
    # declaration rather than read back off the container being judged.
    $computed = & $script:E2EModule { Get-E2EComposeWorkingDirectory }
    Assert-Equal $infra $computed 'The production Compose working directory is not the repository infra directory.'
    Assert-True ($computed -cne $root) 'The production Compose working directory is the repository root.'
}

function Invoke-D273BrowserBindTests {
    $expected = @(& $script:E2EModule { Get-BrowserServerExpectedBinds })
    Assert-Equal 3 $expected.Count 'The production browser bind contract is not three binds.'

    $windows = @($expected | ForEach-Object { $_.Source + ':' + $_.Destination + ':ro' })
    $desktop = @($expected | ForEach-Object { (ConvertTo-D273DesktopPath $_.Source '') + ':' + $_.Destination + ':ro' })
    $upperDrive = @($expected | ForEach-Object { (ConvertTo-D273DesktopPath $_.Source $_.Source.Substring(0, 1).ToUpperInvariant()) + ':' + $_.Destination + ':ro' })
    $otherDrive = @($desktop | ForEach-Object { $_.Replace('/mnt/host/c/', '/mnt/host/d/') })
    $unknownPrefix = @($desktop | ForEach-Object { $_.Replace('/run/desktop/mnt/host/', '/mnt/host/') })
    $suffixOnly = @($expected | ForEach-Object { '/run/desktop/mnt/host/c/elsewhere' + (ConvertTo-D273DesktopPath $_.Source '').Substring('/run/desktop/mnt/host/c'.Length) + ':' + $_.Destination + ':ro' })

    $bindCases = @(
        [pscustomobject]@{ Name = 'binds-windows'; Value = $windows; Accept = $true },
        [pscustomobject]@{ Name = 'binds-desktop'; Value = $desktop; Accept = $true },
        [pscustomobject]@{ Name = 'binds-desktop-upper-drive'; Value = $upperDrive; Accept = $true },
        [pscustomobject]@{ Name = 'binds-desktop-other-drive'; Value = $otherDrive; Accept = $false },
        [pscustomobject]@{ Name = 'binds-desktop-unknown-prefix'; Value = $unknownPrefix; Accept = $false },
        [pscustomobject]@{ Name = 'binds-desktop-suffix-only'; Value = $suffixOnly; Accept = $false },
        [pscustomobject]@{ Name = 'binds-writable'; Value = @($desktop | ForEach-Object { $_.Substring(0, $_.Length - 2) + 'rw' }); Accept = $false },
        [pscustomobject]@{ Name = 'binds-wrong-destination'; Value = @($desktop[0].Replace($expected[0].Destination, '/finguardops/elsewhere')) + @($desktop[1], $desktop[2]); Accept = $false },
        [pscustomobject]@{ Name = 'binds-missing'; Value = @($desktop[0], $desktop[1]); Accept = $false },
        [pscustomobject]@{ Name = 'binds-added'; Value = @($desktop) + @('/run/desktop/mnt/host/c/windows:/finguardops/extra:ro'); Accept = $false }
    )
    foreach ($case in $bindCases) {
        $failure = Get-CapturedException {
            & $script:E2EModule { param($value, $approved, $message) Assert-ExactBinds $value $approved $message } `
                $case.Value $expected 'D273_BIND_REFUSED'
        }
        if ($case.Accept) {
            $detail = if ($null -ne $failure) { $failure.Message } else { '' }
            Assert-True ($null -eq $failure) "$($case.Name) was refused: $detail"
        }
        else {
            Assert-True ($null -ne $failure) "$($case.Name) was accepted."
            Assert-Equal 'D273_BIND_REFUSED' $failure.Message "$($case.Name) returned the wrong message."
        }
    }

    $mountCases = @(
        [pscustomobject]@{ Name = 'mounts-windows'; Spelling = 'windows'; Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'mounts-desktop'; Spelling = 'desktop'; Accept = $true; Mutate = $null },
        [pscustomobject]@{ Name = 'mounts-desktop-other-drive'; Spelling = 'desktop'; Accept = $false
            Mutate = { param($mounts) foreach ($mount in $mounts) { $mount['Source'] = $mount['Source'].Replace('/mnt/host/c/', '/mnt/host/d/') } } },
        [pscustomobject]@{ Name = 'mounts-writable'; Spelling = 'desktop'; Accept = $false
            Mutate = { param($mounts) foreach ($mount in $mounts) { $mount['RW'] = $true; $mount['Mode'] = 'rw' } } },
        [pscustomobject]@{ Name = 'mounts-wrong-propagation'; Spelling = 'desktop'; Accept = $false
            Mutate = { param($mounts) foreach ($mount in $mounts) { $mount['Propagation'] = 'rshared' } } },
        [pscustomobject]@{ Name = 'mounts-wrong-destination'; Spelling = 'desktop'; Accept = $false
            Mutate = { param($mounts) $mounts[0]['Destination'] = '/finguardops/elsewhere' } },
        [pscustomobject]@{ Name = 'mounts-volume-type'; Spelling = 'desktop'; Accept = $false
            Mutate = { param($mounts) $mounts[0]['Type'] = 'volume' } }
    )
    foreach ($case in $mountCases) {
        $mounts = [System.Collections.Generic.List[object]]::new()
        foreach ($bind in $expected) {
            $source = if ($case.Spelling -ceq 'desktop') { ConvertTo-D273DesktopPath $bind.Source '' } else { $bind.Source }
            $mounts.Add([ordered]@{ Type = 'bind'; Source = $source; Destination = $bind.Destination
                Mode = 'ro'; RW = $false; Propagation = 'rprivate' })
        }
        $list = @($mounts.ToArray())
        if ($null -ne $case.Mutate) { & $case.Mutate $list }
        $encoded = ConvertTo-Json -InputObject $list -Depth 8 -Compress
        $parsed = ConvertFrom-Json -InputObject $encoded
        $documents = @($parsed)
        $failure = Get-CapturedException {
            & $script:E2EModule { param($value, $approved, $message) Assert-ExactMounts $value $approved $message } `
                $documents $expected 'D273_MOUNT_REFUSED'
        }
        if ($case.Accept) {
            $detail = if ($null -ne $failure) { $failure.Message } else { '' }
            Assert-True ($null -eq $failure) "$($case.Name) was refused: $detail"
        }
        else {
            Assert-True ($null -ne $failure) "$($case.Name) was accepted."
            Assert-Equal 'D273_MOUNT_REFUSED' $failure.Message "$($case.Name) returned the wrong message."
        }
    }
}

# A Docker daemon and nothing else. It answers `ps`, `container inspect` and
# `image inspect`, truncates an identifier exactly as the real client does when
# `--no-trunc` was not asked for, accepts a prefix on `inspect` exactly as the
# real daemon does, and records every argument vector it was given.
function New-D273IdentityDockerFake {
    param([Parameter(Mandatory = $true)][string]$Root)

    [System.IO.Directory]::CreateDirectory($Root) | Out-Null
    $shim = Join-Path $Root 'docker.cmd'
    $source = Join-Path $Root 'docker-shim.ps1'
    [System.IO.File]::WriteAllText($shim, "@echo off`r`nset `"FINGUARDOPS_D273_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n", [System.Text.Encoding]::ASCII)
    $fakeSource = @'
$DockerArgs = $env:FINGUARDOPS_D273_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D273_ROOT
$events = Join-Path $root 'events.txt'
[System.IO.File]::AppendAllText($events, ($DockerArgs -join ' ') + "`n")
$full = [System.IO.File]::ReadAllText((Join-Path $root 'container-id.txt')).Trim()
$mode = $env:FINGUARDOPS_D273_MODE

if ($DockerArgs[0] -eq 'image' -and $DockerArgs[1] -eq 'inspect') {
    $map = Get-Content (Join-Path $root 'image.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $entry = $map.PSObject.Properties[$DockerArgs[-1]]
    if ($null -eq $entry) { exit 1 }
    Write-Output ($entry.Value | ConvertTo-Json -Depth 10 -Compress)
    exit 0
}
if ($DockerArgs[0] -eq 'ps') {
    if ($mode -eq 'fail') { exit 29 }
    $truncated = $full.Substring(0, 12)
    $answer = if ($DockerArgs -ccontains '--no-trunc') { $full } else { $truncated }
    if ($mode -eq 'short') { $answer = $truncated }
    if ($mode -eq 'prefix') { $answer = $full.Substring(0, 63) }
    if ($mode -eq 'uppercase') { $answer = $full.ToUpperInvariant() }
    Write-Output $answer
    if ($mode -eq 'duplicate') { Write-Output $answer }
    if ($mode -eq 'ambiguous') { Write-Output ('9' * 64) }
    exit 0
}
if ($DockerArgs[0] -eq 'container' -and $DockerArgs[1] -eq 'inspect') {
    $operand = $DockerArgs[-1]
    if ($operand.Length -lt 12 -or -not $full.StartsWith($operand, [System.StringComparison]::Ordinal)) { exit 1 }
    $document = Get-Content (Join-Path $root 'document.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($mode -eq 'document-id') { $document.Id = '9' * 64 }
    Write-Output ($document | ConvertTo-Json -Depth 12 -Compress)
    exit 0
}
exit 81
'@
    [System.IO.File]::WriteAllText($source, ($fakeSource -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Assert-Parsed $source
    return $shim
}

function Invoke-D273FullIdentityTests {
    param([string]$Project, $Receipt)

    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-d273-' + [guid]::NewGuid().ToString('N'))
    $events = Join-Path $root 'events.txt'
    $full = 'a1b2c3d4e5f6' + ('0' * 52)
    $imageId = 'sha256:' + ('b' * 64)
    $names = @('FINGUARDOPS_D273_ROOT', 'FINGUARDOPS_D273_MODE', 'FINGUARDOPS_D273_ARGS')
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process') }
    $oldPath = $env:PATH
    try {
        $shim = New-D273IdentityDockerFake -Root $root
        foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
        $env:FINGUARDOPS_D273_ROOT = $root
        $env:PATH = $root + [System.IO.Path]::PathSeparator + $oldPath
        Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D273 Docker fake sentinel was not selected.'

        $images = & $script:E2EModule { param($value) Get-E2EImageSet -Receipt $value } $Receipt
        $labels = & $script:E2EModule { param($value) Get-E2EOwnershipLabels -Receipt $value -Role 'backend' } $Receipt
        [System.IO.File]::WriteAllText((Join-Path $root 'container-id.txt'), $full, [System.Text.Encoding]::ASCII)
        $imageMap = [ordered]@{}
        $imageMap[$images.Backend] = [ordered]@{ Id = $imageId; Config = [ordered]@{ Labels = $labels } }
        [System.IO.File]::WriteAllText((Join-Path $root 'image.json'),
            ($imageMap | ConvertTo-Json -Depth 8 -Compress), [System.Text.UTF8Encoding]::new($false))
        $document = [ordered]@{
            Id     = $full
            Name   = '/' + $Project + '-backend-1'
            Image  = $imageId
            Config = [ordered]@{
                Image  = $images.Backend
                Labels = [ordered]@{
                    'com.docker.compose.project' = $Project
                    'com.docker.compose.service' = 'backend'
                }
            }
        }
        [System.IO.File]::WriteAllText((Join-Path $root 'document.json'),
            ($document | ConvertTo-Json -Depth 8 -Compress), [System.Text.UTF8Encoding]::new($false))

        $discovery = 'ps -aq --no-trunc --filter label=com.docker.compose.project=' + $Project
        $refused = 'RESOURCE_OWNERSHIP_INVALID'
        $uninspectable = 'A container this run created could not be inspected.'
        $cases = @(
            [pscustomobject]@{ Name = 'full-identifier'; Mode = ''; Accept = $true; Inspects = 1; Error = '' },
            [pscustomobject]@{ Name = 'discovery-failure'; Mode = 'fail'; Accept = $false; Inspects = 0; Error = $refused },
            [pscustomobject]@{ Name = 'short-identifier'; Mode = 'short'; Accept = $false; Inspects = 0; Error = $refused },
            [pscustomobject]@{ Name = 'prefix-identifier'; Mode = 'prefix'; Accept = $false; Inspects = 0; Error = $refused },
            [pscustomobject]@{ Name = 'upper-case-identifier'; Mode = 'uppercase'; Accept = $false; Inspects = 0; Error = $refused },
            [pscustomobject]@{ Name = 'duplicate-identifier'; Mode = 'duplicate'; Accept = $false; Inspects = 0; Error = $refused },
            # Two different full identifiers under one project name: the second
            # is not a container this run owns, and the inspect boundary refuses
            # it rather than counting it.
            [pscustomobject]@{ Name = 'ambiguous-identifier'; Mode = 'ambiguous'; Accept = $false; Inspects = -1; Error = $uninspectable },
            [pscustomobject]@{ Name = 'inspect-answers-another-container'; Mode = 'document-id'; Accept = $false; Inspects = -1; Error = $uninspectable }
        )
        foreach ($case in $cases) {
            $env:FINGUARDOPS_D273_MODE = $case.Mode
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $failure = Get-CapturedException {
                & $script:E2EModule { param($value, $project) Assert-E2EExistingProjectOwnership -Receipt $value -Project $project } `
                    $Receipt $Project
            }
            $commands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
            Assert-True ($commands.Count -ge 1) "$($case.Name) asked the daemon nothing."
            Assert-Equal $discovery $commands[0] "$($case.Name) discovery vector differs."
            $inspects = @($commands | Where-Object { $_ -cmatch '^container inspect ' })
            foreach ($inspect in $inspects) {
                Assert-True ($inspect -cmatch ' (?<id>[0-9a-f]{64})$') "$($case.Name) inspected an abbreviated identifier: $inspect"
            }
            Assert-Equal 0 @($commands | Where-Object { $_ -cmatch '^(stop|rm|prune) ' -or $_ -cmatch '^(container|image|network|volume|system) (rm|prune) ' -or $_ -cmatch '^compose .* down' }).Count `
                "$($case.Name) mutated something."
            if ($case.Inspects -ge 0) {
                Assert-Equal $case.Inspects $inspects.Count "$($case.Name) inspect count differs."
            }
            if ($case.Accept) {
                $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                Assert-True ($null -eq $failure) "$($case.Name) was refused: $detail"
            }
            else {
                Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                Assert-Equal $case.Error $failure.Message "$($case.Name) returned the wrong fixed error."
            }
        }
    }
    finally {
        $env:PATH = $oldPath
        foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        if ([System.IO.Directory]::Exists($root)) { [System.IO.Directory]::Delete($root, $true) }
    }
    if ([System.IO.Directory]::Exists($root)) { throw 'D273_TEMP_CLEANUP_FAILED' }
}

# The characters this run refuses to decide a security question on.
#
# Each case turns an otherwise approved value into the same value carrying one
# smuggled character, so what the boundary is being asked differs from the
# approved question by that character and by nothing else.
function Get-D277ContaminationCases {
    return @(
        [pscustomobject]@{ Name = 'trailing-lf'; Apply = { param($value) $value + "`n" } },
        [pscustomobject]@{ Name = 'trailing-cr'; Apply = { param($value) $value + "`r" } },
        [pscustomobject]@{ Name = 'trailing-crlf'; Apply = { param($value) $value + "`r`n" } },
        [pscustomobject]@{ Name = 'leading-lf'; Apply = { param($value) "`n" + $value } },
        [pscustomobject]@{ Name = 'leading-cr'; Apply = { param($value) "`r" + $value } },
        [pscustomobject]@{ Name = 'embedded-lf'; Apply = { param($value) $value.Insert($value.Length - 1, "`n") } },
        [pscustomobject]@{ Name = 'embedded-cr'; Apply = { param($value) $value.Insert($value.Length - 1, "`r") } },
        [pscustomobject]@{ Name = 'trailing-nul'; Apply = { param($value) $value + [string][char]0 } },
        [pscustomobject]@{ Name = 'embedded-nul'; Apply = { param($value) $value.Insert($value.Length - 1, [string][char]0) } },
        [pscustomobject]@{ Name = 'trailing-line-separator'; Apply = { param($value) $value + [string][char]0x2028 } },
        [pscustomobject]@{ Name = 'trailing-paragraph-separator'; Apply = { param($value) $value + [string][char]0x2029 } },
        [pscustomobject]@{ Name = 'embedded-line-separator'; Apply = { param($value) $value.Insert($value.Length - 1, [string][char]0x2028) } },
        [pscustomobject]@{ Name = 'trailing-vertical-tab'; Apply = { param($value) $value + [string][char]0x0B } },
        [pscustomobject]@{ Name = 'trailing-delete'; Apply = { param($value) $value + [string][char]0x7F } }
    )
}

# No fixed error a contaminated value reaches may carry that value back out. A
# line break in a run log is what splits one record into two, so the check is
# for the characters themselves rather than for a substring of the input.
function Assert-D277NoContaminationEcho($Failure, [string]$Message) {
    if ($null -eq $Failure) { return }
    foreach ($character in $Failure.Message.ToCharArray()) {
        $category = [System.Globalization.CharUnicodeInfo]::GetUnicodeCategory($character)
        if ($category -eq [System.Globalization.UnicodeCategory]::Control -or
            $category -eq [System.Globalization.UnicodeCategory]::LineSeparator -or
            $category -eq [System.Globalization.UnicodeCategory]::ParagraphSeparator) {
            throw $Message
        }
    }
}

# The two path comparisons every mount and every Compose path label is decided
# by, asked directly.
function Invoke-D277PathScalarTests {
    $root = Get-D273RepositoryRoot
    $infra = [System.IO.Path]::GetFullPath((Join-Path $root 'infra'))
    $desktop = ConvertTo-D273DesktopPath $infra ''

    $same = { param($observed, $expected) Test-SamePhysicalPath $observed $expected }
    $bind = { param($observed, $expected) Test-SameBindSourcePath $observed $expected }
    $convert = { param($observed) ConvertFrom-E2EDockerDesktopHostPath $observed }

    # Every clean spelling this boundary has always approved stays approved.
    foreach ($clean in @($infra, $infra.Replace('\', '/'), ($infra + '\'), $infra.ToLowerInvariant())) {
        Assert-True (& $script:E2EModule $same $clean $infra) 'A clean canonical Windows path was refused.'
        Assert-True (& $script:E2EModule $bind $clean $infra) 'A clean canonical Windows bind source was refused.'
    }
    Assert-True (& $script:E2EModule $bind $desktop $infra) 'A clean Docker Desktop bind source was refused.'
    # The drive letter comes back as the Docker Desktop spelling carried it, and
    # letter case is the one difference Windows does not treat as a difference.
    Assert-True ([string]::Equals($infra, (& $script:E2EModule $convert $desktop), [System.StringComparison]::OrdinalIgnoreCase)) `
        'A clean Docker Desktop path did not convert to the Windows path it denotes.'

    foreach ($case in Get-D277ContaminationCases) {
        $windows = & $case.Apply $infra
        $polluted = & $case.Apply $desktop
        Assert-True (-not (& $script:E2EModule $same $windows $infra)) `
            "Test-SamePhysicalPath accepted a contaminated observed path: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $same $infra $windows)) `
            "Test-SamePhysicalPath accepted a contaminated expected path: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $bind $windows $infra)) `
            "Test-SameBindSourcePath accepted a contaminated Windows source: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $bind $polluted $infra)) `
            "Test-SameBindSourcePath accepted a contaminated Docker Desktop source: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $bind $desktop $windows)) `
            "Test-SameBindSourcePath accepted a contaminated expected source: $($case.Name)"
        Assert-True ($null -eq (& $script:E2EModule $convert $polluted)) `
            "The Docker Desktop conversion accepted a contaminated path: $($case.Name)"
    }
}

# The two Compose path labels, as the daemon hands them over: the raw
# `config_files` label before it is split, each path the split produced, and
# the raw `working_dir` label.
function Invoke-D277ComposeLabelTests {
    param([string]$Project, $Receipt, $Configuration)

    $root = Get-D273RepositoryRoot
    $infra = [System.IO.Path]::GetFullPath((Join-Path $root 'infra'))
    $configFiles = @(
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.yml')),
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.keycloak-local-e2e.yml'))
    )
    $definition = $Configuration.services.PSObject.Properties['prometheus'].Value
    $id = '1' * 64
    $imageId = 'sha256:' + ('2' * 64)
    $contract = [pscustomobject]@{ Reference = $definition.image; Id = $imageId; Definition = $definition }

    # The clean document this whole family differs from by one character.
    $baseline = New-D273PrometheusDocument -Id $id -Project $Project -Definition $definition `
        -ImageId $imageId -WorkingDirectory $infra -ConfigFiles $configFiles
    $baselineFailure = Get-D273IdentityFailure (ConvertTo-D273Document $baseline) $id $Project $contract $Receipt
    Assert-True ($null -eq $baselineFailure) 'The clean Compose identity document was refused.'

    foreach ($case in Get-D277ContaminationCases) {
        $variants = @(
            [pscustomobject]@{ Name = 'working-dir'; Directory = (& $case.Apply $infra); Files = $configFiles; Raw = ''; Spelling = 'windows'; Mutate = $null },
            [pscustomobject]@{ Name = 'config-file-first'; Directory = $infra; Files = @((& $case.Apply $configFiles[0]), $configFiles[1]); Raw = ''; Spelling = 'windows'; Mutate = $null },
            [pscustomobject]@{ Name = 'config-file-last'; Directory = $infra; Files = @($configFiles[0], (& $case.Apply $configFiles[1])); Raw = ''; Spelling = 'windows'; Mutate = $null },
            [pscustomobject]@{ Name = 'config-files-raw'; Directory = $infra; Files = $configFiles; Raw = (& $case.Apply ($configFiles -join ',')); Spelling = 'windows'; Mutate = $null },
            [pscustomobject]@{
                Name = 'mount-source-windows'; Directory = $infra; Files = $configFiles; Raw = ''; Spelling = 'windows'
                Mutate = { param($document, $apply)
                    foreach ($mount in @($document['Mounts'])) {
                        if ($mount['Type'] -ceq 'bind') { $mount['Source'] = & $apply $mount['Source'] }
                    } }
            },
            [pscustomobject]@{
                Name = 'mount-source-desktop'; Directory = $infra; Files = $configFiles; Raw = ''; Spelling = 'desktop'
                Mutate = { param($document, $apply)
                    foreach ($mount in @($document['Mounts'])) {
                        if ($mount['Type'] -ceq 'bind') { $mount['Source'] = & $apply $mount['Source'] }
                    } }
            },
            [pscustomobject]@{
                Name = 'mount-destination'; Directory = $infra; Files = $configFiles; Raw = ''; Spelling = 'windows'
                Mutate = { param($document, $apply)
                    foreach ($mount in @($document['Mounts'])) {
                        if ($mount['Type'] -ceq 'bind') { $mount['Destination'] = & $apply $mount['Destination'] }
                    } }
            }
        )
        foreach ($variant in $variants) {
            $document = New-D273PrometheusDocument -Id $id -Project $Project -Definition $definition `
                -ImageId $imageId -WorkingDirectory $variant.Directory -ConfigFiles $variant.Files -BindSpelling $variant.Spelling
            if ($variant.Raw.Length -ne 0) {
                $document['Config']['Labels']['com.docker.compose.project.config_files'] = $variant.Raw
            }
            if ($null -ne $variant.Mutate) { & $variant.Mutate $document $case.Apply }
            $failure = Get-D273IdentityFailure (ConvertTo-D273Document $document) $id $Project $contract $Receipt
            Assert-True ($null -ne $failure) "Compose $($variant.Name) accepted contamination: $($case.Name)"
            Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message "Compose $($variant.Name) returned the wrong fixed error: $($case.Name)"
            Assert-NoRawCleanupDetail $failure "Compose $($variant.Name) reflected an internal detail: $($case.Name)"
            Assert-D277NoContaminationEcho $failure "Compose $($variant.Name) reflected the contaminated value: $($case.Name)"
        }
    }
}

# The browser container's own bind and mount boundaries, contaminated in the
# host path, in the container path, in the whole `Binds` entry, and in `Binds`
# and `Mounts` at the same time.
function Invoke-D277BrowserScalarTests {
    $expected = @(& $script:E2EModule { Get-BrowserServerExpectedBinds })
    Assert-Equal 3 $expected.Count 'The production browser bind contract is not three binds.'

    $bindBoundary = { param($value, $approved, $message) Assert-ExactBinds $value $approved $message }
    $mountBoundary = { param($value, $approved, $message) Assert-ExactMounts $value $approved $message }

    $cleanBinds = @($expected | ForEach-Object { $_.Source + ':' + $_.Destination + ':ro' })
    $cleanMounts = @($expected | ForEach-Object {
        [ordered]@{ Type = 'bind'; Source = $_.Source; Destination = $_.Destination
            Mode = 'ro'; RW = $false; Propagation = 'rprivate' } })
    Assert-True ($null -eq (Get-CapturedException { & $script:E2EModule $bindBoundary $cleanBinds $expected 'D277_BIND_REFUSED' })) `
        'The clean browser bind list was refused.'
    $cleanDocuments = ConvertFrom-Json (ConvertTo-Json -InputObject @($cleanMounts) -Depth 8 -Compress)
    Assert-True ($null -eq (Get-CapturedException {
        & $script:E2EModule $mountBoundary @($cleanDocuments) $expected 'D277_MOUNT_REFUSED' })) `
        'The clean browser mount list was refused.'

    foreach ($case in Get-D277ContaminationCases) {
        $bindCases = @(
            [pscustomobject]@{ Name = 'binds-windows-source'
                Value = @($expected | ForEach-Object { (& $case.Apply $_.Source) + ':' + $_.Destination + ':ro' }) },
            [pscustomobject]@{ Name = 'binds-desktop-source'
                Value = @($expected | ForEach-Object { (& $case.Apply (ConvertTo-D273DesktopPath $_.Source '')) + ':' + $_.Destination + ':ro' }) },
            [pscustomobject]@{ Name = 'binds-destination'
                Value = @($expected | ForEach-Object { $_.Source + ':' + (& $case.Apply $_.Destination) + ':ro' }) },
            [pscustomobject]@{ Name = 'binds-whole-entry'
                Value = @($expected | ForEach-Object { & $case.Apply ($_.Source + ':' + $_.Destination + ':ro') }) }
        )
        foreach ($entry in $bindCases) {
            $failure = Get-CapturedException { & $script:E2EModule $bindBoundary $entry.Value $expected 'D277_BIND_REFUSED' }
            Assert-True ($null -ne $failure) "$($entry.Name) accepted contamination: $($case.Name)"
            Assert-Equal 'D277_BIND_REFUSED' $failure.Message "$($entry.Name) returned the wrong fixed error: $($case.Name)"
            Assert-D277NoContaminationEcho $failure "$($entry.Name) reflected the contaminated value: $($case.Name)"
        }

        $mountCases = @(
            [pscustomobject]@{ Name = 'mounts-windows-source'; Field = 'Source'; Spelling = 'windows' },
            [pscustomobject]@{ Name = 'mounts-desktop-source'; Field = 'Source'; Spelling = 'desktop' },
            [pscustomobject]@{ Name = 'mounts-destination'; Field = 'Destination'; Spelling = 'windows' }
        )
        foreach ($entry in $mountCases) {
            $mounts = [System.Collections.Generic.List[object]]::new()
            foreach ($approved in $expected) {
                $source = if ($entry.Spelling -ceq 'desktop') { ConvertTo-D273DesktopPath $approved.Source '' } else { $approved.Source }
                $mount = [ordered]@{ Type = 'bind'; Source = $source; Destination = $approved.Destination
                    Mode = 'ro'; RW = $false; Propagation = 'rprivate' }
                $mount[$entry.Field] = & $case.Apply $mount[$entry.Field]
                $mounts.Add($mount)
            }
            $parsed = ConvertFrom-Json (ConvertTo-Json -InputObject @($mounts.ToArray()) -Depth 8 -Compress)
            $documents = @($parsed)
            $failure = Get-CapturedException { & $script:E2EModule $mountBoundary $documents $expected 'D277_MOUNT_REFUSED' }
            Assert-True ($null -ne $failure) "$($entry.Name) accepted contamination: $($case.Name)"
            Assert-Equal 'D277_MOUNT_REFUSED' $failure.Message "$($entry.Name) returned the wrong fixed error: $($case.Name)"
            Assert-D277NoContaminationEcho $failure "$($entry.Name) reflected the contaminated value: $($case.Name)"
        }

        # `Binds` and `Mounts` carrying the same smuggled character at once.
        $bothBinds = @($expected | ForEach-Object { (& $case.Apply $_.Source) + ':' + $_.Destination + ':ro' })
        $bothMounts = [System.Collections.Generic.List[object]]::new()
        foreach ($approved in $expected) {
            $bothMounts.Add([ordered]@{ Type = 'bind'; Source = (& $case.Apply $approved.Source)
                Destination = $approved.Destination; Mode = 'ro'; RW = $false; Propagation = 'rprivate' })
        }
        $bindFailure = Get-CapturedException { & $script:E2EModule $bindBoundary $bothBinds $expected 'D277_BIND_REFUSED' }
        $bothParsed = ConvertFrom-Json (ConvertTo-Json -InputObject @($bothMounts.ToArray()) -Depth 8 -Compress)
        $mountFailure = Get-CapturedException {
            & $script:E2EModule $mountBoundary @($bothParsed) $expected 'D277_MOUNT_REFUSED' }
        Assert-True ($null -ne $bindFailure) "binds-and-mounts accepted contaminated Binds: $($case.Name)"
        Assert-True ($null -ne $mountFailure) "binds-and-mounts accepted contaminated Mounts: $($case.Name)"
        Assert-Equal 'D277_BIND_REFUSED' $bindFailure.Message "binds-and-mounts returned the wrong bind error: $($case.Name)"
        Assert-Equal 'D277_MOUNT_REFUSED' $mountFailure.Message "binds-and-mounts returned the wrong mount error: $($case.Name)"
    }
}

# A container identifier is a full 64-character lowercase hexadecimal string
# and is that string absolutely: no line break may terminate it.
function Get-D277IdentifierCases([string]$Clean) {
    return @(
        [pscustomobject]@{ Name = 'clean-64-hex'; Value = $Clean; Accept = $true },
        [pscustomobject]@{ Name = 'trailing-lf'; Value = $Clean + "`n"; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-cr'; Value = $Clean + "`r"; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-crlf'; Value = $Clean + "`r`n"; Accept = $false },
        [pscustomobject]@{ Name = 'leading-lf'; Value = "`n" + $Clean; Accept = $false },
        [pscustomobject]@{ Name = 'leading-cr'; Value = "`r" + $Clean; Accept = $false },
        [pscustomobject]@{ Name = 'embedded-lf'; Value = $Clean.Insert(32, "`n"); Accept = $false },
        [pscustomobject]@{ Name = 'embedded-cr'; Value = $Clean.Insert(32, "`r"); Accept = $false },
        [pscustomobject]@{ Name = 'trailing-nul'; Value = $Clean + [string][char]0; Accept = $false },
        [pscustomobject]@{ Name = 'embedded-nul'; Value = $Clean.Insert(32, [string][char]0); Accept = $false },
        [pscustomobject]@{ Name = 'trailing-line-separator'; Value = $Clean + [string][char]0x2028; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-paragraph-separator'; Value = $Clean + [string][char]0x2029; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-vertical-tab'; Value = $Clean + [string][char]0x0B; Accept = $false },
        [pscustomobject]@{ Name = 'sixty-three-characters'; Value = $Clean.Substring(0, 63); Accept = $false },
        [pscustomobject]@{ Name = 'sixty-five-characters'; Value = $Clean + '0'; Accept = $false },
        [pscustomobject]@{ Name = 'upper-case'; Value = $Clean.ToUpperInvariant(); Accept = $false },
        [pscustomobject]@{ Name = 'non-hexadecimal'; Value = $Clean.Substring(0, 63) + 'g'; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-space'; Value = $Clean + ' '; Accept = $false },
        [pscustomobject]@{ Name = 'empty'; Value = ''; Accept = $false }
    )
}

function Invoke-D277IdentifierTerminationTests {
    param([string]$Project, $Receipt)

    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-d277-' + [guid]::NewGuid().ToString('N'))
    $events = Join-Path $root 'events.txt'
    $full = 'a1b2c3d4e5f6' + ('0' * 52)
    $imageId = 'sha256:' + ('b' * 64)
    $names = @('FINGUARDOPS_D273_ROOT', 'FINGUARDOPS_D273_MODE', 'FINGUARDOPS_D273_ARGS')
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process') }
    $oldPath = $env:PATH
    try {
        $shim = New-D273IdentityDockerFake -Root $root
        foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $null, 'Process') }
        $env:FINGUARDOPS_D273_ROOT = $root
        $env:FINGUARDOPS_D273_MODE = ''
        $env:PATH = $root + [System.IO.Path]::PathSeparator + $oldPath
        Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D277 Docker fake sentinel was not selected.'

        [System.IO.File]::WriteAllText((Join-Path $root 'container-id.txt'), $full, [System.Text.Encoding]::ASCII)
        [System.IO.File]::WriteAllText((Join-Path $root 'image.json'), '{}', [System.Text.UTF8Encoding]::new($false))
        $document = [ordered]@{
            Id = $full; Name = '/' + $Project + '-backend-1'; Image = $imageId
            Config = [ordered]@{ Image = 'reference'; Labels = [ordered]@{ 'com.docker.compose.project' = $Project } }
        }
        [System.IO.File]::WriteAllText((Join-Path $root 'document.json'),
            ($document | ConvertTo-Json -Depth 8 -Compress), [System.Text.UTF8Encoding]::new($false))

        foreach ($case in Get-D277IdentifierCases -Clean $full) {
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $failure = Get-CapturedException {
                & $script:E2EModule { param($value) Get-ContainerDocument $value } $case.Value
            }
            $commands = @([System.IO.File]::ReadAllLines($events) | Where-Object { $_ })
            $inspects = @($commands | Where-Object { $_ -cmatch '^container inspect ' })
            Assert-Equal 0 @($commands | Where-Object {
                $_ -cmatch '^(stop|rm|start|run|create|tag|build|pull|push|prune) ' -or
                $_ -cmatch '^(container|image|network|volume|system|builder) (rm|prune|create|stop|start|tag|build|pull) ' -or
                $_ -cmatch '^compose .* (down|up|rm|stop)' }).Count `
                "$($case.Name) mutated something."
            if ($case.Accept) {
                $detail = if ($null -ne $failure) { $failure.Message } else { '' }
                Assert-True ($null -eq $failure) "$($case.Name) was refused: $detail"
                Assert-Equal 1 $inspects.Count "$($case.Name) inspect count differs."
            }
            else {
                Assert-True ($null -ne $failure) "$($case.Name) was accepted."
                Assert-Equal 'A container this run created could not be inspected.' $failure.Message `
                    "$($case.Name) returned the wrong fixed error."
                Assert-D277NoContaminationEcho $failure "$($case.Name) reflected the malformed identifier."
                Assert-Equal 0 $commands.Count "$($case.Name) asked the daemon something."
                Assert-Equal 0 $inspects.Count "$($case.Name) inspected a malformed identifier."
            }
        }

        # Every boundary that decides a Docker resource identifier answers the
        # same way, so a line break terminates none of them.
        $terminates = & $script:E2EModule {
            param($value)
            return @(
                ($value -cmatch '\A[0-9a-f]{64}\z'),
                (('sha256:' + $value) -cmatch '\Asha256:[0-9a-f]{64}\z'),
                (('container:' + $value) -cmatch '\Acontainer:[0-9a-f]{64}\z')
            )
        } ($full + "`n")
        foreach ($answer in $terminates) {
            Assert-True (-not $answer) 'A Docker identifier boundary was terminated by a line break.'
        }
    }
    finally {
        $env:PATH = $oldPath
        foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        if ([System.IO.Directory]::Exists($root)) { [System.IO.Directory]::Delete($root, $true) }
    }
    if ([System.IO.Directory]::Exists($root)) { throw 'D277_TEMP_CLEANUP_FAILED' }
}

function Invoke-D273TargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    $project = 'finguardops-kc241-e2e-0123456789ab'
    $receipt = New-TestReceipt
    $configuration = Get-D273ComposeConfiguration -Project $project -Receipt $receipt

    Invoke-TestCase 'D273 Compose container identity is judged against the infra working directory' {
        Invoke-D273ComposeIdentityTests -Project $project -Receipt $receipt -Configuration $configuration
    }
    Invoke-TestCase 'D273 browser bind sources accept only the exact Docker Desktop representation' {
        Invoke-D273BrowserBindTests
    }
    Invoke-TestCase 'D273 container ownership uses full identifiers only' {
        Invoke-D273FullIdentityTests -Project $project -Receipt $receipt
    }
    Invoke-TestCase 'D277 path comparisons refuse a contaminated scalar before normalizing it' {
        Invoke-D277PathScalarTests
    }
    Invoke-TestCase 'D277 Compose path labels refuse a contaminated scalar' {
        Invoke-D277ComposeLabelTests -Project $project -Receipt $receipt -Configuration $configuration
    }
    Invoke-TestCase 'D277 browser binds and mounts refuse a contaminated scalar' {
        Invoke-D277BrowserScalarTests
    }
    Invoke-TestCase 'D277 a container identifier is terminated absolutely' {
        Invoke-D277IdentifierTerminationTests -Project $project -Receipt $receipt
    }

    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D273 targeted passed'
}

# The characters this run refuses to decide an ownership question on.
#
# Each of these is a Unicode Format character, which `Test-E2ECleanScalar` has
# never refused and never needed to: none of them is a control character, a line
# separator or a paragraph separator, and none of them splits a log record. What
# they do is something else entirely. PowerShell's string operators compare
# through a culture, and on this platform the invariant culture treats every one
# of them as no character at all, so `-ceq`, `-cne`, `-ccontains`, `-cnotcontains`
# and `-cnotin` each answer that an approved label and the same label carrying one
# of them are the same string.
#
# U+200B is included although the platform's collation does distinguish it: a
# counterexample family that only contains the characters that currently succeed
# would stop being a counterexample family the moment the collation changed.
function Get-D281FormatCases {
    return @(
        [pscustomobject]@{ Name = 'soft-hyphen'; Character = [string][char]0x00AD },
        [pscustomobject]@{ Name = 'zero-width-space'; Character = [string][char]0x200B },
        [pscustomobject]@{ Name = 'zero-width-non-joiner'; Character = [string][char]0x200C },
        [pscustomobject]@{ Name = 'zero-width-joiner'; Character = [string][char]0x200D },
        [pscustomobject]@{ Name = 'word-joiner'; Character = [string][char]0x2060 },
        [pscustomobject]@{ Name = 'zero-width-no-break-space'; Character = [string][char]0xFEFF }
    )
}

# The commands a fake Docker recorded that would have changed the world.
function Get-D281MutationEvents([string]$EventPath) {
    $commands = @([System.IO.File]::ReadAllLines($EventPath) | Where-Object { $_ })
    return @($commands | Where-Object {
        $_ -cmatch '^(stop|rm|start|run|create|tag|build|pull|push|prune) ' -or
        $_ -cmatch '^(container|image|network|volume|system|builder) (rm|prune|create|stop|start|tag|build|pull) ' -or
        $_ -cmatch '^compose .* (down|up|rm|stop)' })
}

# A native Docker leaf, and nothing above it. Every answer is read from a file
# this fixture wrote; no production validator is reimplemented here.
function New-D281DockerFake {
    param([Parameter(Mandatory = $true)][string]$Root)

    [System.IO.Directory]::CreateDirectory($Root) | Out-Null
    $shim = Join-Path $Root 'docker.cmd'
    [System.IO.File]::WriteAllText($shim,
        "@echo off`r`nset `"FINGUARDOPS_D281_ARGS=%*`"`r`npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"%~dp0docker-shim.ps1`"`r`n",
        [System.Text.Encoding]::ASCII)
    $source = @'
$DockerArgs = $env:FINGUARDOPS_D281_ARGS -split ' '
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$root = $env:FINGUARDOPS_D281_ROOT
$line = ($DockerArgs -join ' ')
[System.IO.File]::AppendAllText((Join-Path $root 'events.txt'), $line + "`n")

function Read-Json([string]$Name) {
    $path = Join-Path $root $Name
    if (-not [System.IO.File]::Exists($path)) { return $null }
    return (Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

if ($DockerArgs[0] -eq 'image' -and $DockerArgs[1] -eq 'inspect') {
    $map = Read-Json 'image.json'
    if ($null -eq $map) { exit 1 }
    $entry = $map.PSObject.Properties[$DockerArgs[-1]]
    if ($null -eq $entry) { exit 1 }
    Write-Output ($entry.Value | ConvertTo-Json -Depth 10 -Compress)
    exit 0
}
if ($DockerArgs[0] -eq 'container' -and $DockerArgs[1] -eq 'inspect') {
    $map = Read-Json 'documents.json'
    if ($null -eq $map) { exit 1 }
    $entry = $map.PSObject.Properties[$DockerArgs[-1]]
    if ($null -eq $entry) { exit 1 }
    Write-Output ($entry.Value | ConvertTo-Json -Depth 12 -Compress)
    exit 0
}
if ($DockerArgs[0] -eq 'ps') {
    $path = Join-Path $root 'ps.txt'
    if (-not [System.IO.File]::Exists($path)) { exit 0 }
    foreach ($value in @(Get-Content $path -Encoding UTF8 | Where-Object { $_ })) { Write-Output $value }
    exit 0
}
if ($DockerArgs[0] -eq 'volume' -and $DockerArgs[1] -eq 'ls') { exit 0 }
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'ls') {
    $state = Join-Path $root 'network-present.txt'
    if (-not [System.IO.File]::Exists($state)) { exit 0 }
    if ([System.IO.File]::ReadAllText($state).Trim() -ne '1') { exit 0 }
    $wanted = [System.IO.File]::ReadAllText((Join-Path $root 'network-filter.txt')).Trim()
    if ($line.Contains($wanted)) {
        Write-Output ([System.IO.File]::ReadAllText((Join-Path $root 'network-id.txt')).Trim())
    }
    exit 0
}
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'inspect') {
    Write-Output (Get-Content (Join-Path $root 'network.json') -Raw -Encoding UTF8)
    exit 0
}
if ($DockerArgs[0] -eq 'network' -and $DockerArgs[1] -eq 'rm') {
    [System.IO.File]::WriteAllText((Join-Path $root 'network-present.txt'), '0')
    exit 0
}
exit 81
'@
    [System.IO.File]::WriteAllText((Join-Path $Root 'docker-shim.ps1'),
        ($source -replace "(?<!`r)`n", "`r`n") + "`r`n", [System.Text.UTF8Encoding]::new($false))
    Assert-Parsed (Join-Path $Root 'docker-shim.ps1')
    return $shim
}

function Invoke-D281WithDockerFake([string]$Root, [scriptblock]$Body) {
    $names = @('FINGUARDOPS_D281_ROOT', 'FINGUARDOPS_D281_ARGS')
    $previous = @{}
    foreach ($name in $names) { $previous[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process') }
    $oldPath = $env:PATH
    try {
        $shim = New-D281DockerFake -Root $Root
        $env:FINGUARDOPS_D281_ROOT = $Root
        $env:PATH = $Root + [System.IO.Path]::PathSeparator + $oldPath
        Assert-Equal $shim (Get-Command docker -ErrorAction Stop).Source 'D281 Docker fake sentinel was not selected.'
        & $Body
    }
    finally {
        $env:PATH = $oldPath
        foreach ($name in $names) { [System.Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        if ([System.IO.Directory]::Exists($Root)) { [System.IO.Directory]::Delete($Root, $true) }
    }
    if ([System.IO.Directory]::Exists($Root)) { throw 'D281_TEMP_CLEANUP_FAILED' }
}

function New-D281TempRoot([string]$Tag) {
    return Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-d281-' + $Tag + '-' + [guid]::NewGuid().ToString('N'))
}

# The helpers themselves, against the operators they exist to replace.
function Invoke-D281OrdinalHelperTests {
    $approved = 'finguardops-kc241-e2e-0123456789ab'
    $equal = { param($left, $right) Test-E2EOrdinalEqual $left $right }
    $pathEqual = { param($left, $right) Test-E2EOrdinalPathEqual $left $right }
    $contains = { param($values, $candidate) Test-E2EOrdinalContains $values $candidate }
    $sequence = { param($expected, $actual) Test-E2EOrdinalSequenceEqual $expected $actual }
    $set = { param($expected, $actual) Test-E2EOrdinalSetEqual $expected $actual }

    Assert-True (& $script:E2EModule $equal $approved $approved) 'An identical scalar was refused.'
    Assert-True (& $script:E2EModule $pathEqual 'C:\Repo\Infra' 'c:\repo\infra') 'A Windows path differing only in case was refused.'
    Assert-True (& $script:E2EModule $contains @('alpha', 'beta') 'beta') 'An exact member was refused.'
    Assert-True (& $script:E2EModule $sequence @('alpha', 'beta') @('alpha', 'beta')) 'An identical sequence was refused.'
    Assert-True (& $script:E2EModule $set @('alpha', 'beta') @('beta', 'alpha')) 'An identical set was refused.'

    # Null, a non-string and a type difference are differences, not matches.
    Assert-True (-not (& $script:E2EModule $equal $null $approved)) 'A null candidate was accepted.'
    Assert-True (-not (& $script:E2EModule $equal $approved $null)) 'A null expectation was accepted.'
    Assert-True (-not (& $script:E2EModule $equal $null $null)) 'Two nulls were accepted as equal.'
    Assert-True (-not (& $script:E2EModule $equal 1 '1')) 'A non-string candidate was accepted.'
    Assert-True (-not (& $script:E2EModule $pathEqual $null 'C:\repo')) 'A null path candidate was accepted.'
    Assert-True (-not (& $script:E2EModule $contains @('alpha') $null)) 'A null member was accepted.'
    # Case is a difference everywhere except in a Windows path.
    Assert-True (-not (& $script:E2EModule $equal $approved $approved.ToUpperInvariant())) 'A case difference was accepted.'
    # A set is a set: a repeated value on either side is not one.
    Assert-True (-not (& $script:E2EModule $set @('alpha', 'alpha') @('alpha', 'beta'))) 'A repeated expectation was accepted as a set.'
    Assert-True (-not (& $script:E2EModule $set @('alpha', 'beta') @('alpha', 'alpha'))) 'A repeated candidate was accepted as a set.'
    Assert-True (-not (& $script:E2EModule $sequence @('alpha', 'beta') @('beta', 'alpha'))) 'A reordered sequence was accepted.'
    Assert-True (-not (& $script:E2EModule $sequence @('alpha') @('alpha', 'beta'))) 'A longer sequence was accepted.'

    foreach ($case in Get-D281FormatCases) {
        $polluted = $approved.Insert(11, $case.Character)
        Assert-True (-not (& $script:E2EModule $equal $approved $polluted)) `
            "Test-E2EOrdinalEqual accepted a contaminated candidate: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $equal $polluted $approved)) `
            "Test-E2EOrdinalEqual accepted a contaminated expectation: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $pathEqual 'C:\repo\infra' ('C:\repo\inf' + $case.Character + 'ra'))) `
            "Test-E2EOrdinalPathEqual accepted a contaminated path: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $contains @($approved) $polluted)) `
            "Test-E2EOrdinalContains accepted a contaminated member: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $sequence @($approved) @($polluted))) `
            "Test-E2EOrdinalSequenceEqual accepted a contaminated element: $($case.Name)"
        Assert-True (-not (& $script:E2EModule $set @($approved) @($polluted))) `
            "Test-E2EOrdinalSetEqual accepted a contaminated member: $($case.Name)"
    }
}

# A `prometheus` container as the daemon records one, with each of the six
# non-path identity scalars contaminated in turn. This is the validator that
# stands between Compose container discovery and `docker stop` / `docker rm`.
function Invoke-D281ComposeIdentityTests {
    param([string]$Project, $Receipt, $Configuration)

    $root = Get-D273RepositoryRoot
    $infra = [System.IO.Path]::GetFullPath((Join-Path $root 'infra'))
    $configFiles = @(
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.yml')),
        [System.IO.Path]::GetFullPath((Join-Path $root 'infra/compose.keycloak-local-e2e.yml'))
    )
    $definition = $Configuration.services.PSObject.Properties['prometheus'].Value
    $id = '1' * 64
    $imageId = 'sha256:' + ('2' * 64)
    $contract = [pscustomobject]@{ Reference = $definition.image; Id = $imageId; Definition = $definition }

    $baseline = New-D273PrometheusDocument -Id $id -Project $Project -Definition $definition `
        -ImageId $imageId -WorkingDirectory $infra -ConfigFiles $configFiles
    Assert-True ($null -eq (Get-D273IdentityFailure (ConvertTo-D273Document $baseline) $id $Project $contract $Receipt)) `
        'The clean Compose identity document was refused.'

    foreach ($case in Get-D281FormatCases) {
        $character = $case.Character
        $variants = @(
            [pscustomobject]@{ Name = 'document-id'; Apply = { param($d) $d['Id'] = $d['Id'].Insert(32, $character) } },
            [pscustomobject]@{ Name = 'project-label'; Apply = { param($d) $d['Config']['Labels']['com.docker.compose.project'] = $Project.Insert(11, $character) } },
            [pscustomobject]@{ Name = 'service-label'; Apply = { param($d) $d['Config']['Labels']['com.docker.compose.service'] = 'prom' + $character + 'etheus' } },
            [pscustomobject]@{ Name = 'container-number'; Apply = { param($d) $d['Config']['Labels']['com.docker.compose.container-number'] = '1' + $character } },
            [pscustomobject]@{ Name = 'oneoff-label'; Apply = { param($d) $d['Config']['Labels']['com.docker.compose.oneoff'] = 'Fal' + $character + 'se' } },
            [pscustomobject]@{ Name = 'config-image'; Apply = { param($d) $d['Config']['Image'] = ([string]$d['Config']['Image']).Insert(3, $character) } },
            [pscustomobject]@{ Name = 'document-image'; Apply = { param($d) $d['Image'] = ([string]$d['Image']).Insert(10, $character) } },
            [pscustomobject]@{ Name = 'network-mode'; Apply = { param($d) $d['HostConfig']['NetworkMode'] = ([string]$d['HostConfig']['NetworkMode']).Insert(2, $character) } },
            [pscustomobject]@{ Name = 'mount-type'; Apply = { param($d)
                foreach ($mount in @($d['Mounts'])) { $mount['Type'] = ([string]$mount['Type']).Insert(1, $character) } } },
            [pscustomobject]@{ Name = 'mount-volume-name'; Apply = { param($d)
                foreach ($mount in @($d['Mounts'])) {
                    if ($mount['Type'] -ceq 'volume') { $mount['Name'] = ([string]$mount['Name']).Insert(11, $character) }
                } } }
        )
        foreach ($variant in $variants) {
            $document = New-D273PrometheusDocument -Id $id -Project $Project -Definition $definition `
                -ImageId $imageId -WorkingDirectory $infra -ConfigFiles $configFiles
            & $variant.Apply $document
            $failure = Get-D273IdentityFailure (ConvertTo-D273Document $document) $id $Project $contract $Receipt
            Assert-True ($null -ne $failure) "Compose $($variant.Name) accepted contamination: $($case.Name)"
            Assert-Equal 'RESOURCE_CLEANUP_FAILED' $failure.Message `
                "Compose $($variant.Name) returned the wrong fixed error: $($case.Name)"
            Assert-NoRawCleanupDetail $failure "Compose $($variant.Name) reflected an internal detail: $($case.Name)"
            Assert-D281NoContaminationEcho $failure $character `
                "Compose $($variant.Name) reflected the contaminated value: $($case.Name)"
        }
    }
}

# No fixed error a contaminated value reaches may carry that character back out.
function Assert-D281NoContaminationEcho($Failure, [string]$Character, [string]$Message) {
    if ($null -eq $Failure) { return }
    if ($Failure.Message.Contains($Character)) { throw $Message }
    Assert-D277NoContaminationEcho $Failure $Message
}

# The one production statement that a candidate container is this run's own
# dedicated browser container, asked of a document that differs from an approved
# one by a single Format character.
function Invoke-D281BrowserOwnershipTests {
    param($Receipt)

    $imageId = 'sha256:' + ('7' * 64)
    $containerId = '3' * 64
    $labels = & $script:E2EModule { param($r) Get-E2EOwnershipLabels -Receipt $r -Role 'browser' } $Receipt
    $expectation = & $script:E2EModule { Get-BrowserServerExpectation (Get-BrowserServerExpectedBinds) }
    $name = & $script:E2EModule { $BrowserContainerName }
    $images = & $script:E2EModule { param($r) Get-E2EImageSet -Receipt $r } $Receipt
    $contract = [ordered]@{
        Name = $name; Reference = $images.Browser; ImageId = $imageId
        Labels = $labels; Role = 'browser'; Expectation = $expectation
    }

    $build = {
        param($ContainerName, $DocumentImage, $ConfigImage, $NetworkMode, $LabelOverrides)
        $binds = @($expectation.Binds | ForEach-Object { $_.Source + ':' + $_.Destination + ':ro' })
        $mounts = @($expectation.Binds | ForEach-Object {
            [ordered]@{ Type = 'bind'; Source = $_.Source; Destination = $_.Destination
                Mode = 'ro'; RW = $false; Propagation = 'rprivate' } })
        $ports = [ordered]@{}
        foreach ($port in $expectation.PortBindings.Keys) {
            $ports[$port] = @($expectation.PortBindings[$port] | ForEach-Object {
                [ordered]@{ HostIp = $_.HostIp; HostPort = $_.HostPort } })
        }
        $documentLabels = [ordered]@{}
        foreach ($key in $labels.Keys) { $documentLabels[$key] = $labels[$key] }
        if ($null -ne $LabelOverrides) {
            foreach ($key in $LabelOverrides.Keys) { $documentLabels[$key] = $LabelOverrides[$key] }
        }
        return [ordered]@{
            Id = $containerId; Name = $ContainerName; Image = $DocumentImage
            Config = [ordered]@{ Image = $ConfigImage; Labels = $documentLabels }
            HostConfig = [ordered]@{
                NetworkMode = $NetworkMode; ReadonlyRootfs = $expectation.ReadOnlyRootFilesystem
                Init = $expectation.Init; Privileged = $false; PublishAllPorts = $false
                CapAdd = @(); CapDrop = $expectation.CapabilityDrop
                SecurityOpt = $expectation.SecurityOptions; ExtraHosts = $expectation.ExtraHosts
                Devices = @(); DeviceRequests = @(); DeviceCgroupRules = @(); VolumesFrom = @(); Mounts = @()
                Binds = $binds; Tmpfs = $expectation.Tmpfs; PortBindings = $ports
            }
            NetworkSettings = [ordered]@{ Networks = [ordered]@{ "$($expectation.NetworkMode)" = [ordered]@{} } }
            Mounts = $mounts
        }
    }
    $judge = {
        param($Document)
        return Get-CapturedException {
            & $script:E2EModule {
                param($document, $id, $image, $contract)
                Assert-E2EOwnedBrowserContainer -Document $document -ContainerId $id -ImageId $image -Contract $contract
            } $Document $containerId $imageId $contract
        }
    }
    $message = 'A browser container removal was asked for a container this run does not own.'
    $clean = ConvertTo-D273Document (& $build ('/' + $name) $imageId $imageId $expectation.NetworkMode $null)
    Assert-True ($null -eq (& $judge $clean)) 'The clean browser ownership document was refused.'

    foreach ($case in Get-D281FormatCases) {
        $character = $case.Character
        $variants = @(
            [pscustomobject]@{ Name = 'container-name'
                Document = (& $build ('/' + $name.Insert(6, $character)) $imageId $imageId $expectation.NetworkMode $null) },
            [pscustomobject]@{ Name = 'document-image'
                Document = (& $build ('/' + $name) $imageId.Insert(10, $character) $imageId $expectation.NetworkMode $null) },
            [pscustomobject]@{ Name = 'config-image'
                Document = (& $build ('/' + $name) $imageId $imageId.Insert(10, $character) $expectation.NetworkMode $null) },
            [pscustomobject]@{ Name = 'network-mode'
                Document = (& $build ('/' + $name) $imageId $imageId $expectation.NetworkMode.Insert(2, $character) $null) },
            [pscustomobject]@{ Name = 'image-role-label'
                Document = (& $build ('/' + $name) $imageId $imageId $expectation.NetworkMode `
                    ([ordered]@{ 'com.finguardops.e2e.image-role' = 'brow' + $character + 'ser' })) }
        )
        foreach ($variant in $variants) {
            $failure = & $judge (ConvertTo-D273Document $variant.Document)
            Assert-True ($null -ne $failure) "Browser $($variant.Name) accepted contamination: $($case.Name)"
            Assert-Equal $message $failure.Message "Browser $($variant.Name) returned the wrong fixed error: $($case.Name)"
            Assert-D281NoContaminationEcho $failure $character `
                "Browser $($variant.Name) reflected the contaminated value: $($case.Name)"
        }
    }
}

# The authoritative image record set, and the pre-mutation project ownership
# validator, each asked through the production Docker leaf.
function Invoke-D281ImageAndProjectOwnershipTests {
    param([string]$Project, $Receipt)

    $root = New-D281TempRoot 'ownership'
    Invoke-D281WithDockerFake $root {
        $events = Join-Path $root 'events.txt'
        $images = & $script:E2EModule { param($r) Get-E2EImageSet -Receipt $r } $Receipt
        $identifiers = [ordered]@{ Backend = 'sha256:' + ('4' * 64); AiService = 'sha256:' + ('5' * 64); Browser = 'sha256:' + ('6' * 64) }
        $roles = [ordered]@{ Backend = 'backend'; AiService = 'ai-service'; Browser = 'browser' }
        $imageMap = [ordered]@{}
        foreach ($key in $roles.Keys) {
            $imageMap[$images[$key]] = [ordered]@{
                Id = $identifiers[$key]
                Config = [ordered]@{ Labels = (& $script:E2EModule {
                    param($r, $role) Get-E2EOwnershipLabels -Receipt $r -Role $role } $Receipt $roles[$key]) }
            }
        }
        [System.IO.File]::WriteAllText((Join-Path $root 'image.json'),
            ($imageMap | ConvertTo-Json -Depth 8 -Compress), [System.Text.UTF8Encoding]::new($false))

        $buildRecords = {
            param([string]$Field, [string]$Character)
            $records = [ordered]@{}
            foreach ($key in $roles.Keys) {
                $reference = $images[$key]
                $identifier = $identifiers[$key]
                $role = $roles[$key]
                if ($Field -ceq 'Reference') { $reference = $reference.Insert(5, $Character) }
                if ($Field -ceq 'Id') { $identifier = $identifier.Insert(10, $Character) }
                if ($Field -ceq 'Role') { $role = $role.Insert(1, $Character) }
                $records[$key] = [pscustomobject]@{
                    Reference = $reference; Id = $identifier; Role = $role; InUse = $false
                    Labels = (& $script:E2EModule { param($r, $value) Get-E2EOwnershipLabels -Receipt $r -Role $value } $Receipt $roles[$key])
                }
            }
            return $records
        }
        $judgeRecords = {
            param($Records)
            return Get-CapturedException {
                & $script:E2EModule { param($value, $r) Assert-E2EImageRecordSet -Values @($value) -Receipt $r } $Records $Receipt
            }
        }
        [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
        Assert-True ($null -eq (& $judgeRecords (& $buildRecords '' ''))) 'The clean image record set was refused.'
        Assert-Equal 0 @(Get-D281MutationEvents $events).Count 'The clean image record set mutated something.'

        foreach ($case in Get-D281FormatCases) {
            foreach ($field in @('Reference', 'Id', 'Role')) {
                [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
                $failure = & $judgeRecords (& $buildRecords $field $case.Character)
                Assert-True ($null -ne $failure) "Image record $field accepted contamination: $($case.Name)"
                Assert-Equal 'IMAGE_RECORD_INVALID' $failure.Message "Image record $field returned the wrong fixed error: $($case.Name)"
                Assert-D281NoContaminationEcho $failure $case.Character "Image record $field reflected the contaminated value: $($case.Name)"
                Assert-Equal 0 @(Get-D281MutationEvents $events).Count "Image record $field mutated something: $($case.Name)"
            }
        }

        # The pre-mutation project ownership validator.
        $full = 'a1b2c3d4e5f6' + ('0' * 52)
        [System.IO.File]::WriteAllText((Join-Path $root 'ps.txt'), $full, [System.Text.Encoding]::ASCII)
        $writeDocument = {
            param([string]$ProjectLabel, [string]$ServiceLabel, [string]$ConfigImage, [string]$DocumentImage)
            $document = [ordered]@{
                Id = $full; Name = '/' + $Project + '-backend-1'; Image = $DocumentImage
                Config = [ordered]@{
                    Image = $ConfigImage
                    Labels = [ordered]@{
                        'com.docker.compose.project' = $ProjectLabel
                        'com.docker.compose.service' = $ServiceLabel
                    }
                }
            }
            $map = [ordered]@{}
            $map[$full] = $document
            [System.IO.File]::WriteAllText((Join-Path $root 'documents.json'),
                ($map | ConvertTo-Json -Depth 12 -Compress), [System.Text.UTF8Encoding]::new($false))
        }
        $judgeProject = {
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            $failure = Get-CapturedException {
                & $script:E2EModule { param($r, $p) Assert-E2EExistingProjectOwnership -Receipt $r -Project $p } $Receipt $Project
            }
            return [pscustomobject]@{ Failure = $failure; Mutations = @(Get-D281MutationEvents $events).Count }
        }
        & $writeDocument $Project 'backend' $images.Backend $identifiers.Backend
        $clean = & $judgeProject
        Assert-True ($null -eq $clean.Failure) 'The clean project ownership document was refused.'
        Assert-Equal 0 $clean.Mutations 'The clean project ownership check mutated something.'

        foreach ($case in Get-D281FormatCases) {
            $character = $case.Character
            $variants = @(
                [pscustomobject]@{ Name = 'project-label'; Values = @($Project.Insert(11, $character), 'backend', $images.Backend, $identifiers.Backend) },
                [pscustomobject]@{ Name = 'service-label'; Values = @($Project, 'back' + $character + 'end', $images.Backend, $identifiers.Backend) },
                [pscustomobject]@{ Name = 'config-image'; Values = @($Project, 'backend', $images.Backend.Insert(5, $character), $identifiers.Backend) },
                [pscustomobject]@{ Name = 'document-image'; Values = @($Project, 'backend', $images.Backend, $identifiers.Backend.Insert(10, $character)) }
            )
            foreach ($variant in $variants) {
                & $writeDocument $variant.Values[0] $variant.Values[1] $variant.Values[2] $variant.Values[3]
                $result = & $judgeProject
                Assert-True ($null -ne $result.Failure) "Project $($variant.Name) accepted contamination: $($case.Name)"
                Assert-Equal 'RESOURCE_OWNERSHIP_INVALID' $result.Failure.Message `
                    "Project $($variant.Name) returned the wrong fixed error: $($case.Name)"
                Assert-D281NoContaminationEcho $result.Failure $character `
                    "Project $($variant.Name) reflected the contaminated value: $($case.Name)"
                Assert-Equal 0 $result.Mutations "Project $($variant.Name) mutated something: $($case.Name)"
            }
        }

        # Letter case is a difference here as well: `-eq` and `-ne` answered
        # that it was not, and this boundary decides which image a container
        # under this project is allowed to be running.
        foreach ($variant in @(
            [pscustomobject]@{ Name = 'service-label-case'; Values = @($Project, 'BACKEND', $images.Backend, $identifiers.Backend) },
            [pscustomobject]@{ Name = 'project-label-case'; Values = @($Project.ToUpperInvariant(), 'backend', $images.Backend, $identifiers.Backend) },
            [pscustomobject]@{ Name = 'config-image-case'; Values = @($Project, 'backend', $images.Backend.ToUpperInvariant(), $identifiers.Backend) },
            [pscustomobject]@{ Name = 'document-image-case'; Values = @($Project, 'backend', $images.Backend, $identifiers.Backend.ToUpperInvariant()) }
        )) {
            & $writeDocument $variant.Values[0] $variant.Values[1] $variant.Values[2] $variant.Values[3]
            $result = & $judgeProject
            Assert-True ($null -ne $result.Failure) "Project $($variant.Name) accepted a case difference."
            Assert-Equal 'RESOURCE_OWNERSHIP_INVALID' $result.Failure.Message "Project $($variant.Name) returned the wrong fixed error."
            Assert-Equal 0 $result.Mutations "Project $($variant.Name) mutated something."
        }
    }
}

# The whole cleanup, from the first read to the receipt deletion, against a world
# holding one network whose project label differs from this run's by a single
# Format character. Every leaf below is a native Docker command or a file.
function Invoke-D281FullCleanupReceiptTests {
    param($Receipt)

    $serviceProject = & $script:E2EModule { param($r) Get-E2EServiceProjectName -Receipt $r } $Receipt
    $networkId = '9' * 64
    $root = New-D281TempRoot 'cleanup'
    Invoke-D281WithDockerFake $root {
        $events = Join-Path $root 'events.txt'
        [System.IO.File]::WriteAllText((Join-Path $root 'network-id.txt'), $networkId, [System.Text.Encoding]::ASCII)
        [System.IO.File]::WriteAllText((Join-Path $root 'network-filter.txt'),
            ($serviceProject + '_application$'), [System.Text.Encoding]::ASCII)
        $repository = Join-Path $root 'repo'
        $state = Join-Path $repository 'infra\keycloak\.local\state'
        [System.IO.Directory]::CreateDirectory($state) | Out-Null
        $receiptPath = Join-Path $state 'e2e-image-cleanup-required.json'

        $run = {
            param([string]$ProjectLabel)
            $network = [ordered]@{
                Id = $networkId
                Name = $serviceProject + '_application'
                Labels = [ordered]@{
                    'com.docker.compose.network' = 'application'
                    'com.docker.compose.project' = $ProjectLabel
                }
                Containers = [ordered]@{}
            }
            [System.IO.File]::WriteAllText((Join-Path $root 'network.json'),
                ($network | ConvertTo-Json -Depth 8 -Compress), [System.Text.UTF8Encoding]::new($false))
            [System.IO.File]::WriteAllText((Join-Path $root 'network-present.txt'), '1', [System.Text.Encoding]::ASCII)
            [System.IO.File]::WriteAllText($events, '', [System.Text.Encoding]::ASCII)
            & $script:E2EModule { param($p, $r, $base) New-E2EReceiptFile -Path $p -Receipt $r -RepositoryRoot $base } `
                $receiptPath $Receipt $repository
            $failure = Get-CapturedException {
                & $script:E2EModule { param($r, $p, $base) Invoke-E2EFullCleanup -Receipt $r -ReceiptPath $p -RepositoryRootPath $base } `
                    $Receipt $receiptPath $repository
            }
            $mutations = @(Get-D281MutationEvents $events)
            $result = [pscustomobject]@{
                Failure = $failure
                Mutations = $mutations.Count
                NetworkRemovals = @($mutations | Where-Object { $_ -cmatch '^network rm ' }).Count
                ReceiptKept = [System.IO.File]::Exists($receiptPath)
            }
            if ($result.ReceiptKept) { [System.IO.File]::Delete($receiptPath) }
            return $result
        }

        # The clean world still finishes: the owned network is removed and the
        # receipt is deleted, exactly as before.
        $clean = & $run $serviceProject
        Assert-True ($null -eq $clean.Failure) 'A clean full cleanup failed.'
        Assert-Equal 1 $clean.NetworkRemovals 'A clean full cleanup did not remove the owned network.'
        Assert-True (-not $clean.ReceiptKept) 'A clean full cleanup did not delete the receipt.'

        foreach ($case in Get-D281FormatCases) {
            $result = & $run ($serviceProject + $case.Character)
            Assert-True ($null -ne $result.Failure) "Full cleanup accepted a contaminated network: $($case.Name)"
            Assert-Equal 'RESOURCE_CLEANUP_FAILED' $result.Failure.Message `
                "Full cleanup returned the wrong fixed error: $($case.Name)"
            Assert-D281NoContaminationEcho $result.Failure $case.Character `
                "Full cleanup reflected the contaminated value: $($case.Name)"
            Assert-Equal 0 $result.Mutations "Full cleanup mutated something: $($case.Name)"
            Assert-Equal 0 $result.NetworkRemovals "Full cleanup removed a foreign network: $($case.Name)"
            Assert-True $result.ReceiptKept "Full cleanup deleted the receipt after refusing: $($case.Name)"
        }
    }
}

# Every candidate the safe cleanup error code boundary can be handed.
function Get-D281ErrorCodeCases {
    $fixed = 'RESOURCE_CLEANUP_FAILED'
    return @(
        [pscustomobject]@{ Name = 'clean-fixed-code'; Value = $fixed; Accept = $true },
        [pscustomobject]@{ Name = 'longest-accepted-code'; Value = 'A' + ('B' * 63); Accept = $true },
        [pscustomobject]@{ Name = 'trailing-lf'; Value = $fixed + "`n"; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-cr'; Value = $fixed + "`r"; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-crlf'; Value = $fixed + "`r`n"; Accept = $false },
        [pscustomobject]@{ Name = 'leading-lf'; Value = "`n" + $fixed; Accept = $false },
        [pscustomobject]@{ Name = 'leading-cr'; Value = "`r" + $fixed; Accept = $false },
        [pscustomobject]@{ Name = 'embedded-lf'; Value = $fixed.Insert(8, "`n"); Accept = $false },
        [pscustomobject]@{ Name = 'embedded-cr'; Value = $fixed.Insert(8, "`r"); Accept = $false },
        [pscustomobject]@{ Name = 'line-feed-then-record'; Value = $fixed + "`nRESOURCE_CLEANUP_FAILED"; Accept = $false },
        [pscustomobject]@{ Name = 'trailing-nul'; Value = $fixed + [string][char]0; Accept = $false },
        [pscustomobject]@{ Name = 'embedded-nul'; Value = $fixed.Insert(8, [string][char]0); Accept = $false },
        [pscustomobject]@{ Name = 'soft-hyphen'; Value = $fixed.Insert(8, [string][char]0x00AD); Accept = $false },
        [pscustomobject]@{ Name = 'zero-width-space'; Value = $fixed.Insert(8, [string][char]0x200B); Accept = $false },
        [pscustomobject]@{ Name = 'word-joiner'; Value = $fixed.Insert(8, [string][char]0x2060); Accept = $false },
        [pscustomobject]@{ Name = 'zero-width-no-break-space'; Value = $fixed.Insert(8, [string][char]0xFEFF); Accept = $false },
        [pscustomobject]@{ Name = 'line-separator'; Value = $fixed + [string][char]0x2028; Accept = $false },
        [pscustomobject]@{ Name = 'lower-case'; Value = $fixed.ToLowerInvariant(); Accept = $false },
        [pscustomobject]@{ Name = 'trailing-space'; Value = $fixed + ' '; Accept = $false },
        [pscustomobject]@{ Name = 'embedded-space'; Value = $fixed.Insert(8, ' '); Accept = $false },
        [pscustomobject]@{ Name = 'leading-digit'; Value = '1' + $fixed; Accept = $false },
        [pscustomobject]@{ Name = 'sixty-five-characters'; Value = 'A' + ('B' * 64); Accept = $false },
        [pscustomobject]@{ Name = 'empty'; Value = ''; Accept = $false }
    )
}

function Invoke-D281SafeErrorCodeTests {
    $fallback = 'IMAGE_CLEANUP_FAILED'
    foreach ($case in Get-D281ErrorCodeCases) {
        $record = $null
        try { throw [System.InvalidOperationException]::new($case.Value) } catch { $record = $_ }
        $selected = & $script:E2EModule { param($r, $f) Get-E2ESafeCleanupFailure -ErrorRecord $r -FallbackCode $f } $record $fallback

        # The same candidate, through the one production caller that reaches
        # this boundary at all.
        $raised = Get-CapturedException {
            & $script:E2EModule {
                param($value, $code)
                $actions = @([pscustomobject]@{
                    Action = { throw [System.InvalidOperationException]::new($value) }.GetNewClosure()
                    ErrorCode = $code
                    SkipAfterCleanupFailure = $false
                })
                Invoke-E2ECleanupActions -Primary $null -Actions $actions
            } $case.Value $fallback
        }
        Assert-True ($null -ne $raised) "$($case.Name) raised nothing."
        if ($case.Accept) {
            Assert-Equal $case.Value $selected.Message "$($case.Name) was not returned as its own code."
            Assert-Equal $case.Value $raised.Message "$($case.Name) was not preserved through the cleanup actions."
        }
        else {
            Assert-Equal $fallback $selected.Message "$($case.Name) was not replaced by the fallback code."
            Assert-Equal $fallback $raised.Message "$($case.Name) was not replaced through the cleanup actions."
            if ($case.Value.Length -ne 0) {
                Assert-True (-not $selected.Message.Contains($case.Value)) "$($case.Name) reflected the raw candidate."
            }
            Assert-D277NoContaminationEcho $raised "$($case.Name) reflected a contaminated character."
        }
    }

    # A fallback code is itself bounded absolutely.
    Assert-Throws {
        & $script:E2EModule {
            $record = $null
            try { throw [System.InvalidOperationException]::new('X') } catch { $record = $_ }
            Get-E2ESafeCleanupFailure -ErrorRecord $record -FallbackCode "IMAGE_CLEANUP_FAILED`n"
        }
    } '.' 'A fallback code carrying a line break was accepted.'

    # Arbitration and primary identity are unchanged.
    $primary = [System.InvalidOperationException]::new('PRIMARY_CODE')
    $cleanup = [System.InvalidOperationException]::new('CLEANUP_CODE')
    Assert-True ([object]::ReferenceEquals($primary, (& $script:E2EModule {
        param($p, $c) Select-E2EFailure -Primary $p -Cleanup $c } $primary $cleanup))) `
        'Cleanup replaced the primary failure.'
    Assert-True ([object]::ReferenceEquals($cleanup, (& $script:E2EModule {
        param($p, $c) Select-E2EFailure -Primary $p -Cleanup $c } $null $cleanup))) `
        'The dedicated cleanup failure was not selected.'
    $preserved = Get-CapturedException {
        & $script:E2EModule {
            param($p)
            $actions = @([pscustomobject]@{
                Action = { throw [System.InvalidOperationException]::new("RESOURCE_CLEANUP_FAILED`n") }
                ErrorCode = 'IMAGE_CLEANUP_FAILED'
                SkipAfterCleanupFailure = $false
            })
            Invoke-E2ECleanupActions -Primary $p -Actions $actions -DiagnosticWriter { param($value) }
        } $primary
    }
    Assert-True ([object]::ReferenceEquals($primary, $preserved)) `
        'A malformed cleanup code displaced the primary exception object.'
}

function Invoke-D281TargetedTests {
    $script:Failures = [System.Collections.Generic.List[string]]::new()
    $project = 'finguardops-kc241-e2e-0123456789ab'
    $receipt = New-TestReceipt
    $configuration = Get-D273ComposeConfiguration -Project $project -Receipt $receipt

    Invoke-TestCase 'D281 the ordinal comparison helpers refuse a Unicode Format counterexample' {
        Invoke-D281OrdinalHelperTests
    }
    Invoke-TestCase 'D281 Compose container ownership refuses a Format-contaminated scalar' {
        Invoke-D281ComposeIdentityTests -Project $project -Receipt $receipt -Configuration $configuration
    }
    Invoke-TestCase 'D281 browser container ownership refuses a Format-contaminated scalar' {
        Invoke-D281BrowserOwnershipTests -Receipt $receipt
    }
    Invoke-TestCase 'D281 image and project ownership refuse a Format-contaminated scalar without mutating' {
        Invoke-D281ImageAndProjectOwnershipTests -Project $project -Receipt $receipt
    }
    Invoke-TestCase 'D281 full cleanup refuses a contaminated network and preserves the receipt' {
        Invoke-D281FullCleanupReceiptTests -Receipt $receipt
    }
    Invoke-TestCase 'D281 the safe cleanup error code is bounded absolutely' {
        Invoke-D281SafeErrorCodeTests
    }

    if ($script:Failures.Count -ne 0) {
        foreach ($failure in $script:Failures) { Write-Output $failure }
        exit 1
    }
    Write-Output 'D281 targeted passed'
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
        # The Service preflight now compares each record against the image ID
        # the daemon reports, so the transition order is asserted against a
        # native Docker fake rather than against invented identifiers.
        $l2DockerRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('finguardops-l2-docker-' + [guid]::NewGuid().ToString('N'))
        $l2OldPath = $env:PATH
        $l2OldRoot = $env:FINGUARDOPS_D225S_ROOT
        try {
        $l2Shim = New-D225ServiceDockerFake -Root $l2DockerRoot
        $env:FINGUARDOPS_D225S_ROOT = $l2DockerRoot
        $env:PATH = $l2DockerRoot + [System.IO.Path]::PathSeparator + $l2OldPath
        Assert-Equal $l2Shim (Get-Command docker -ErrorAction Stop).Source 'L2 Docker fake sentinel was not selected.'
        $serviceCalls = [System.Collections.Generic.List[string]]::new()
        $serviceRefs = Get-E2EImageSet -Receipt $receipt
        $serviceRecord = [ordered]@{
            Backend = [pscustomobject]@{ Reference=$serviceRefs.Backend; Id=('sha256:' + ('b' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $receipt -Role 'backend'); Role='backend'; InUse=$false }
            AiService = [pscustomobject]@{ Reference=$serviceRefs.AiService; Id=('sha256:' + ('c' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $receipt -Role 'ai-service'); Role='ai-service'; InUse=$false }
            Browser = [pscustomobject]@{ Reference=$serviceRefs.Browser; Id=('sha256:' + ('d' * 64)); Labels=(Get-E2EOwnershipLabels -Receipt $receipt -Role 'browser'); Role='browser'; InUse=$false }
        }
        $serviceBoundaries = @{
            ReadPrepared = { $receipt }
            RenamePreparedToRecovery = { $serviceCalls.Add('to-recovery') }
            AssertImages = { param($value) $serviceCalls.Add('images'); return $serviceRecord }
            AssertBrowserRuntime = { param($value) $serviceCalls.Add('browser-runtime') }
            RunChild = { param($value) $serviceCalls.Add('child') }
            AssertContainers = { param($value) $serviceCalls.Add('containers') }
            CleanupResources = { $serviceCalls.Add('resources') }
            RenameRecoveryToPrepared = { $serviceCalls.Add('to-prepared') }
            Cleanup = { param($value) $serviceCalls.Add('cleanup') }
        }
        Invoke-E2EServiceLifecycle -Boundaries $serviceBoundaries
        Assert-Equal @('to-recovery','images','browser-runtime','child','containers','resources','to-prepared') @($serviceCalls) 'Service transition order differs.'

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
            AssertBrowserRuntime = { }
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
        finally {
            $env:PATH = $l2OldPath
            $env:FINGUARDOPS_D225S_ROOT = $l2OldRoot
            if ([System.IO.Directory]::Exists($l2DockerRoot)) { [System.IO.Directory]::Delete($l2DockerRoot, $true) }
        }
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

if ($Mode -eq 'D209Preflight') {
    Invoke-D209Preflight
    exit 0
}

if ($Mode -eq 'D209A') {
    Invoke-D209ATests
    exit 0
}

if ($Mode -eq 'D225Service') {
    Invoke-D225ServiceTests
    exit 0
}

if ($Mode -eq 'D209B') {
    Invoke-D209BTests
    exit 0
}

if ($Mode -eq 'D248Targeted') {
    Invoke-D248TargetedTests
    exit 0
}

if ($Mode -eq 'CleanupBrowserTargeted') {
    Invoke-CleanupBrowserTargetedTests
    exit 0
}

if ($Mode -eq 'D273Targeted') {
    Invoke-D273TargetedTests
    exit 0
}

if ($Mode -eq 'D281Targeted') {
    Invoke-D281TargetedTests
    exit 0
}

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
