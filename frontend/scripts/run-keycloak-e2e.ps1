[CmdletBinding()]
param(
    [ValidateSet('Prepare', 'Service', 'Run', 'Validate', 'Cleanup')]
    [string]$Mode = 'Run'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$modulePath = Join-Path $PSScriptRoot 'keycloak-e2e-lib.psm1'
Import-Module $modulePath -Force
Invoke-KeycloakE2E -Mode $Mode
