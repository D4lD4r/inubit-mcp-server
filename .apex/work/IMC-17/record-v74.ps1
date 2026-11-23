#Requires -Version 5.1
<#
.SYNOPSIS
Run sheet for IMC-17 T026-T028 (spec 025, quickstart "Mandate B - recording session"; IMC-21
option A, IMC-24): records the INUBIT 7.4 cases with tools\record-fixtures.ps1 on the Windows
machine, for evaluation only.

.DESCRIPTION
IMC-24 (owner, 2026-11-23): the results never enter a repository and are handed over raw (no
customer neutralization). They are written to $ResultFolder outside the kit, packed by -Phase Pack,
evaluated by the execution agent outside the repository and deleted after the evaluation.

Fill in the block "TO FILL IN" together with the owner of the machine, then run, in this order
(ANLEITUNG.txt of the recording kit walks through every step):

  powershell -ExecutionPolicy Bypass -File record-v74.ps1 -Phase Check
  powershell -ExecutionPolicy Bypass -File record-v74.ps1 -Phase Research
      -> send v74_version.stdout, v74_exec_help.stdout and v74_exec_ps_help.stdout to the owner /
         execution agent; it adjusts the 7.4 case table if the commands differ (T027)
  powershell -ExecutionPolicy Bypass -File record-v74.ps1 -Phase Session
  powershell -ExecutionPolicy Bypass -File record-v74.ps1 -Phase Pack
      -> hand the ZIP over to the owner

All cases are read-only. The INUBIT password is asked for at each case as a secure prompt and
handed to StartCLI through its standard input; it is never stored. Built into the recorder and kept:
the node's host, the login user, the Windows account names and local paths are replaced by
placeholders, and a run stops if the password is in an output. No other value is changed (the
neutralize map of this kit replaces nothing). Nothing is written on the INUBIT node.

Undo: the allowlists exist only in this PowerShell process. The recorder deletes its private raw
folder (inubit-record-<id> under $WorkRoot); after an interrupted run delete a leftover
inubit-record-* folder there. When done delete $WorkRoot, $ResultFolder, the ZIP of -Phase Pack
and the kit folder.
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

# Labels for the recorder's allowlist; leave them as they are.
$Group = 'legacy74'
$Node = 'node1'

# Optional: a password-less trust store (.jks, .p12 or .pfx, no spaces in the path) if the node's
# certificate is not trusted by the JDK. Leave empty if not needed.
$TrustStore = ''

# Optional: the INUBIT username for the StartCLI login (read access is enough). If empty, the
# recorder asks for it at every case. The password is always asked for, once per case, as a
# secure prompt; it is never stored.
$Username = ''

# Working folder for the recorder's private raw output, and the folder for the results (ASCII
# paths without spaces or special characters; created if missing). Leave them as they are.
$WorkRoot = 'C:\inubit-record'
$ResultFolder = 'C:\imc17-results'

# Set to $true only after the owner of the machine has confirmed that the INUBIT node behind
# $CliUrl is NOT classified production (spec FR-021).
$NodeIsNonProduction = $false
# ======================================================================================

$KitRoot = $PSScriptRoot
$Recorder = Join-Path $KitRoot 'tools\record-fixtures.ps1'
# The recorder always runs tools\neutralize.py with a map; this one never matches (IMC-24: no
# customer neutralization). "(?!)" is an empty negative lookahead: it matches nowhere.
$NoOpMap = [IO.Path]::Combine($WorkRoot, 'no-op-map.txt')

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
            @('Group', $Group), @('Node', $Node), @('WorkRoot', $WorkRoot),
            @('ResultFolder', $ResultFolder))) {
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

function Initialize-Folders {
    foreach ($folder in @($WorkRoot, $ResultFolder)) {
        if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
    }
    [IO.File]::WriteAllText($NoOpMap, "# replaces nothing (IMC-24)`n(?!)`tunused`n", (New-Object Text.UTF8Encoding($false)))
}

function Invoke-Check {
    Assert-Filled
    if (-not (Test-Path -LiteralPath $Recorder)) { Fail "recorder not found: $Recorder (unpack the whole kit)" }
    Ok 'kit complete'
    if (-not (Test-Path -LiteralPath (Join-Path $CliHome 'bin\startcli.bat'))) { Fail "no bin\startcli.bat in $CliHome" }
    Ok '7.4 Workbench client found'
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { Fail "no bin\java.exe in $JavaHome" }
    $javaVersion = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Select-Object -First 1
    Ok "JDK found: $javaVersion"
    $python = Get-Python
    if (-not $python) {
        Fail 'Python 3.7 or newer is needed (the recorder always runs tools\neutralize.py; standard library only, no packages). Install it, then open a new PowerShell.'
    }
    Ok "Python found: $($python.Version)"
    & $python.Path (Join-Path $KitRoot 'tools\neutralize.py') --self-test | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'neutralize.py --self-test failed' }
    Ok 'neutralize.py runs'
    if ($TrustStore -and -not (Test-Path -LiteralPath $TrustStore)) { Fail "trust store not found: $TrustStore" }
    foreach ($pair in @(@('CliHome', $CliHome), @('JavaHome', $JavaHome), @('WorkRoot', $WorkRoot),
            @('ResultFolder', $ResultFolder))) {
        if ($pair[1] -match '[^\x20-\x7e]|["%!^&|<>;]') {
            Fail "`$$($pair[0]) contains a character the recorder refuses (umlaut, ; or one of `"%!^&|<>): use another folder"
        }
    }
    Ok 'paths contain no refused characters'
    $nodeHost = Get-NodeHost
    $port = ([Uri] $CliUrl).Port
    $tcp = Test-NetConnection -ComputerName $nodeHost -Port $port -WarningAction SilentlyContinue
    if (-not $tcp.TcpTestSucceeded) { Fail "node not reachable on port $port (network, VPN or firewall)" }
    Ok "node reachable on port $port"
    Write-Host 'Check passed. Next: -Phase Research.'
}

function Invoke-Recorder([string] $Cases) {
    Assert-Filled
    Initialize-Folders
    $env:RECORD_ALLOWED_GROUPS = $Group
    $env:RECORD_ALLOWED_HOSTS = Get-NodeHost
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Recorder,
        '-Group', $Group, '-Node', $Node, '-CliHome', $CliHome, '-JavaHome', $JavaHome,
        '-CliUrl', $CliUrl, '-NeutralizeMap', $NoOpMap, '-WorkRoot', $WorkRoot,
        '-OutputDirectory', $ResultFolder, '-Case', $Cases)
    if ($TrustStore) { $arguments += @('-TrustStore', $TrustStore) }
    if ($Username) { $arguments += @('-Username', $Username) }
    $powershell = (Get-Process -Id $PID).Path   # the same PowerShell that runs this sheet
    & $powershell @arguments
    $code = $LASTEXITCODE
    Remove-Item Env:RECORD_ALLOWED_GROUPS, Env:RECORD_ALLOWED_HOSTS -ErrorAction SilentlyContinue
    if ($code -ne 0) { Fail "recorder exit $code (nothing is kept for a failed case; read its message above)" }
    Ok "recorded into ${ResultFolder}: $Cases"
}

function Invoke-Pack {
    if (-not (Test-Path -LiteralPath (Join-Path $ResultFolder 'v74_version.stdout'))) {
        Fail "no results in $ResultFolder - run -Phase Research and -Phase Session first"
    }
    $zip = Join-Path ([Environment]::GetFolderPath('Desktop')) 'imc17-v74-results-RAW.zip'
    $files = Get-ChildItem -LiteralPath $ResultFolder -File
    Compress-Archive -LiteralPath $files.FullName -DestinationPath $zip -Force
    Ok "$($files.Count) files packed into $zip"
    Write-Host 'The ZIP holds raw customer output: hand it over directly (no public share, no repository), then delete it here.'
}

switch ($Phase) {
    'Check' { Invoke-Check }
    'Research' {
        Invoke-Recorder $ResearchCases
        $versionFile = Join-Path $ResultFolder 'v74_version.stdout'
        Write-Host '--- client version (v74_version.stdout) ---'
        if (Test-Path -LiteralPath $versionFile) { Get-Content -LiteralPath $versionFile | Select-Object -First 5 }
        Write-Host '---'
        Write-Host 'The client version above must be 7.4 (7.4.0.90). If not: stop here and tell Daniel.'
        Write-Host "Send v74_version.stdout, v74_exec_help.stdout and v74_exec_ps_help.stdout from $ResultFolder to Daniel and wait for the OK before -Phase Session."
    }
    'Session' { Invoke-Recorder $SessionCases; Write-Host 'Next: -Phase Pack.' }
    'Pack' { Invoke-Pack }
}
