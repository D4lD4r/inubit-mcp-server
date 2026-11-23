#Requires -Version 5.1
<#
.SYNOPSIS
Run sheet for IMC-17 T026-T028 (spec 025, quickstart "Mandate B - recording session", option A of
IMC-21): records the INUBIT 7.4 cases with tools\record-fixtures.ps1 on the Windows machine.

.DESCRIPTION
Fill in the block "TO FILL IN" together with the owner of the machine, then run, in this order:

  powershell -ExecutionPolicy Bypass -File .apex\work\IMC-17\record-v74.ps1 -Phase Check
  powershell -ExecutionPolicy Bypass -File .apex\work\IMC-17\record-v74.ps1 -Phase Research
      -> send v74_exec_help.stdout and v74_exec_ps_help.stdout (already neutralized) to the
         execution agent; it adjusts the 7.4 case table if the commands differ (T027)
  powershell -ExecutionPolicy Bypass -File .apex\work\IMC-17\record-v74.ps1 -Phase Session
  powershell -ExecutionPolicy Bypass -File .apex\work\IMC-17\record-v74.ps1 -Phase Pack
      -> bring the ZIP back to the development machine

Run it from a copy of the repository (branch claude/025-imc-17-mandate-b-b4dc46); the recorder needs
tools\ and src\test\resources\fixtures\v7_4\cli next to it. All cases are read-only. The INUBIT
username and password are asked for at each run (the password only as a secure prompt, handed to
StartCLI through its standard input); they are never stored. Nothing is written on the INUBIT node.

Undo: the allowlists exist only in this PowerShell process. The recorder deletes its raw output
(a private inubit-record-<id> folder under %TEMP%); after an interrupted run delete a leftover
inubit-record-* folder there. "git checkout -- src/test/resources/fixtures/v7_4/cli" restores the
reconstructed cases. Delete the ZIP of -Phase Pack after handing it over.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Check', 'Research', 'Session', 'Pack')]
    [string] $Phase
)

$ErrorActionPreference = 'Stop'

# ===================================== TO FILL IN =====================================
# Fill in with the owner of the machine. No value here is committed or sent anywhere.

# Folder of the INUBIT 7.4 Workbench client (the folder that contains bin\startcli.bat).
$CliHome = '<path to the 7.4 Workbench client>'

# The JDK the 7.4 client runs with (the folder that contains bin\java.exe).
$JavaHome = '<path to its JDK>'

# StartCLI URL of the 7.4.0.90 node, e.g. https://<host>:<port>/ibis/servlet/IBISSoapServlet
$CliUrl = 'https://<host>:<port>/ibis/servlet/IBISSoapServlet'

# Neutral labels only (they end up in the file names of the run, not in the fixtures).
$Group = 'legacy74'
$Node = 'node1'

# Optional: a password-less trust store (.jks, .p12 or .pfx, no spaces in the path) if the node's
# certificate is not trusted by the JDK. Leave empty if not needed.
$TrustStore = ''

# The local neutralize map (customer value -> neutral value, one "<regex><TAB><replacement>" per
# line). It never leaves this machine and is never committed.
$NeutralizeMap = '<path to the neutralize map>'

# Set to $true only after the owner of the machine has confirmed that the INUBIT node behind
# $CliUrl is NOT classified production (spec FR-021).
$NodeIsNonProduction = $false
# ======================================================================================

$RepositoryRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$Recorder = Join-Path $RepositoryRoot 'tools\record-fixtures.ps1'
$FixtureFolder = Join-Path $RepositoryRoot 'src\test\resources\fixtures\v7_4\cli'

$ResearchCases = 'v74_help,v74_version,v74_exec_help,v74_exec_ps_help,v74_direct_health'
$SessionCases = 'v74_health_session,v74_ps_all,v74_ps_error,v74_ps_waiting,v74_ps_queued,' +
    'v74_ps_limit5,v74_login_failed,v74_failure'

function Fail([string] $Message) {
    Write-Host "STOP: $Message" -ForegroundColor Red
    exit 1
}

function Ok([string] $Message) {
    Write-Host "ok   $Message" -ForegroundColor Green
}

function Assert-Filled {
    foreach ($pair in @(
            @('CliHome', $CliHome), @('JavaHome', $JavaHome), @('CliUrl', $CliUrl),
            @('NeutralizeMap', $NeutralizeMap), @('Group', $Group), @('Node', $Node))) {
        if ([string]::IsNullOrWhiteSpace($pair[1]) -or $pair[1].Contains('<')) {
            Fail "fill in `$$($pair[0]) in the block TO FILL IN of $PSCommandPath"
        }
    }
    if (-not $NodeIsNonProduction) {
        Fail 'set $NodeIsNonProduction = $true only after the machine owner confirmed the node is not production'
    }
}

function Get-NodeHost {
    try { return ([Uri] $CliUrl).Host } catch { Fail "`$CliUrl is not a URL: $CliUrl" }
}

function Get-Python {
    foreach ($name in @('python', 'py')) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $command) { continue }
        # The Microsoft Store placeholder python.exe exists but does not run Python.
        $version = & $command.Source --version 2>&1
        if ($LASTEXITCODE -eq 0 -and "$version" -match 'Python 3\.(\d+)') {
            if ([int] $Matches[1] -ge 7) { return @{ Path = $command.Source; Version = "$version" } }
        }
    }
    return $null
}

function Invoke-Check {
    Assert-Filled
    if (-not (Test-Path -LiteralPath $Recorder)) { Fail "recorder not found: $Recorder (run from a repository copy)" }
    if (-not (Test-Path -LiteralPath $FixtureFolder)) { Fail "fixture folder not found: $FixtureFolder" }
    Ok 'repository copy with tools\ and the 7.4 fixture folder'
    if (-not (Test-Path -LiteralPath (Join-Path $CliHome 'bin\startcli.bat'))) { Fail "no bin\startcli.bat in $CliHome" }
    Ok '7.4 Workbench client found'
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { Fail "no bin\java.exe in $JavaHome" }
    $javaVersion = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1
    Ok "JDK found: $javaVersion"
    $python = Get-Python
    if (-not $python) {
        Fail 'Python 3.7 or newer is needed for tools\neutralize.py (standard library only, no packages). Install it, e.g. the python.org installer for the current user, then open a new PowerShell.'
    }
    Ok "Python found: $($python.Version)"
    & $python.Path (Join-Path $RepositoryRoot 'tools\neutralize.py') --self-test | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'neutralize.py --self-test failed' }
    Ok 'neutralize.py self-test passed'
    if (-not (Test-Path -LiteralPath $NeutralizeMap)) { Fail "neutralize map not found: $NeutralizeMap" }
    Ok 'neutralize map found'
    if ($TrustStore -and -not (Test-Path -LiteralPath $TrustStore)) { Fail "trust store not found: $TrustStore" }
    $nodeHost = Get-NodeHost
    $port = ([Uri] $CliUrl).Port
    $tcp = Test-NetConnection -ComputerName $nodeHost -Port $port -WarningAction SilentlyContinue
    if (-not $tcp.TcpTestSucceeded) { Fail "node not reachable on port $port (network, VPN or firewall)" }
    Ok "node reachable on port $port"
    Write-Host 'Check passed. Next: -Phase Research.'
}

function Invoke-Recorder([string] $Cases) {
    Assert-Filled
    $env:RECORD_ALLOWED_GROUPS = $Group
    $env:RECORD_ALLOWED_HOSTS = Get-NodeHost
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Recorder,
        '-Group', $Group, '-Node', $Node, '-CliHome', $CliHome, '-JavaHome', $JavaHome,
        '-CliUrl', $CliUrl, '-NeutralizeMap', $NeutralizeMap, '-Case', $Cases)
    if ($TrustStore) { $arguments += @('-TrustStore', $TrustStore) }
    $powershell = Join-Path $PSHOME 'powershell.exe'
    & $powershell @arguments
    $code = $LASTEXITCODE
    Remove-Item Env:RECORD_ALLOWED_GROUPS, Env:RECORD_ALLOWED_HOSTS -ErrorAction SilentlyContinue
    if ($code -ne 0) { Fail "recorder exit $code (nothing is kept for a failed case; read its message above)" }
    Ok "recorded: $Cases"
}

function Invoke-Pack {
    $zip = Join-Path ([Environment]::GetFolderPath('Desktop')) 'imc17-v74-recordings.zip'
    # The whole 7.4 fixture folder (recorded and still reconstructed cases); the execution agent
    # diffs it against the branch.
    $files = Get-ChildItem -LiteralPath $FixtureFolder -File
    if (-not (Get-ChildItem -LiteralPath $FixtureFolder -Filter 'v74_*' -File)) {
        Fail "no v74_* recordings in $FixtureFolder - run -Phase Research and -Phase Session first"
    }
    Compress-Archive -LiteralPath $files.FullName -DestinationPath $zip -Force
    Ok "$($files.Count) files packed into $zip - hand it over, then delete it here"
}

switch ($Phase) {
    'Check' { Invoke-Check }
    'Research' { Invoke-Recorder $ResearchCases; Write-Host "Send v74_exec_help.stdout and v74_exec_ps_help.stdout from $FixtureFolder to the execution agent before -Phase Session." }
    'Session' { Invoke-Recorder $SessionCases; Write-Host 'Next: -Phase Pack.' }
    'Pack' { Invoke-Pack }
}
