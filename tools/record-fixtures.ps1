#Requires -Version 5.1
<#
.SYNOPSIS
Records the read-only StartCLI cases on Windows from a NON-PRODUCTION node (feature 013, FR-014,
research R-13).

.DESCRIPTION
Starts the client's bin\startcli.bat the way the server does (contracts/windows-invocation.md):
through cmd.exe of the system directory with "/d /v:off /s /c", every StartCLI argument in double
quotes, the server's environment allowlist only, the password only through the child's standard
input (never -p, never an argument, never a variable). The script refuses the characters the
server refuses.

Cases (all read-only; -List prints them):
  win_version    startcli.bat -v (no server, no credentials)
  win_help       startcli.bat -h (no server, no credentials)
  win_modules    module export of -Owner into a C:/ path (credentials; non-ASCII module names)
  win_failure    uptime against https://127.0.0.1:9/... (no server, a dummy password)
  win_argv       the argument list the client's Java main receives for the test values of the
                 invocation contract, through a recording java.cmd in a wrapper JAVA_HOME
                 (no server, no credentials)
  win_backslash  module export with a C:\... path inside the quoted --execCommand value; lists
                 where StartCLI wrote the ZIP (credentials)

INUBIT 7.4 cases (feature 025, spec FR-016; written to src\test\resources\fixtures\v7_4\cli under
the fixture names of spec FR-014, the research cases under their own name):
  v74_help            -h of the 7.4 client (no server, no credentials) -> help
  v74_version         -v of the 7.4 client (no server, no credentials)
  v74_exec_help       script mode, the 7.4 interactive commands (credentials)
  v74_exec_ps_help    script mode, the 7.4 ps options (credentials)
  v74_direct_health   the direct health options in one run (credentials)
  v74_health_session  one session with the health commands of the 6.1 session (credentials)
                      -> health_session_ok
  v74_ps_all, v74_ps_error, v74_ps_waiting, v74_ps_queued, v74_ps_limit5
                      one session with one ps listing each (credentials) -> ps_session_all,
                      ps_session_error, ps_session_waiting, ps_session_queued, ps_session_limit5
  v74_login_failed    uptime as -FailedLoginUser (default mcp-record-no-such-user, a user that
                      does not exist) with the dummy password of win_failure -> login_failed
  v74_failure         uptime against https://127.0.0.1:9/... (no server, dummy password)
                      -> unreachable
The arguments, session commands and end-of-session command of every case live only in the case
table $Cases (spec FR-018): adjust them there after v74_exec_help and v74_exec_ps_help.

Each case first checks that -Group is listed in RECORD_ALLOWED_GROUPS (space-separated); a case
that contacts the server of -CliUrl (the export, exec, direct and session cases and
v74_login_failed) then checks that its host is listed in RECORD_ALLOWED_HOSTS (space-separated
NON-PRODUCTION host names), so that no other server is contacted. StartCLI runs in -CliHome, like
under the server. -Case takes one or more names, also comma-separated in one argument.

A case of kind session (feature 025, spec FR-017) runs one interactive StartCLI session against
-CliUrl (host allowlist as above, credentials): startcli.bat -u <user> <url>, standard input = the
password line, the case's commands from the table and its end-of-session command, one per line.
A case of kind exec or direct runs startcli.bat -u <user> <its arguments> <url> with the password
line on standard input.

TLS: the win_* cases pass -TrustStore and -DisableHostNameVerification as client options. The 7.4
client has neither option (spec FR-017): a v74_* case gets -TrustStore (password-less; .jks, .p12
or .pfx, no whitespace in the path) as -Djavax.net.ssl.trustStore and -trustStoreType in
JAVA_TOOL_OPTIONS, and -DisableHostNameVerification with a v74_* case stops the run before any
launch.

Raw output only lives in a private directory under -WorkRoot (inheritance removed, full control
for the current user and SYSTEM) that is removed at the end. There the script replaces the paths
of the run (work directory, client, Java home, trust store, user profile, also in 8.3 form), the
host of -CliUrl, the Windows account names and the INUBIT username with placeholders, runs
tools\neutralize.py with the local map (-NeutralizeMap, else INUBIT_MCP_NEUTRALIZE_MAP, else
.neutralize-map in the repository), checks that neither the password nor the host is in an output,
and only then copies <case>.stdout, <case>.stderr, <case>.exit (and <case>.entries with the ZIP
entry names, <case>.result) into -OutputDirectory. Export ZIPs are never copied; one that StartCLI
writes outside the work directory (win_backslash) is listed and deleted. Afterwards run
NoCustomerIdentifiersTest.

win_argv runs a small Java program in source-file mode and needs a JDK (bin\javac.exe): -JavaHome
if it is one, else -DumpJavaHome. It only works with a startcli.bat that runs java from PATH: a
client whose startcli.bat starts %JAVA_HOME%\bin\java.exe, or that has a java_home_path_setter.cmd
setting JAVA_HOME next to its directory (the Virtimo clients 8.1 and 9.0), is not recorded and
the case writes the reason instead.

.EXAMPLE
$env:RECORD_ALLOWED_GROUPS = 'dev'; $env:RECORD_ALLOWED_HOSTS = 'dev-node1.example.test'
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group dev -Node node1 `
  -CliHome C:\inubit\client -JavaHome C:\java\jdk-17 -Case win_version,win_help,win_argv

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group dev -Node node1 `
  -CliHome C:\inubit\client -JavaHome C:\java\jdk-17 -TrustStore C:\inubit\truststore.jks `
  -CliUrl https://dev-node1.example.test:8443/ibis/servlet/IBISSoapServlet -Owner acme_dev `
  -WorkRoot C:\inubit-record -Case win_modules,win_backslash

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group dev -Node node1 `
  -CliHome C:\inubit74\client -JavaHome C:\inubit74\jdk -TrustStore C:\inubit74\truststore.jks `
  -CliUrl https://dev-node1.example.test:8443/ibis/servlet/IBISSoapServlet `
  -Case v74_health_session,v74_ps_all,v74_login_failed,v74_failure
#>
[CmdletBinding()]
param(
    [string] $Group,
    [string] $Node,
    [string[]] $Case,
    [string] $CliHome,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $CliUrl,
    [string] $TrustStore,
    [switch] $DisableHostNameVerification,
    [string] $Owner,
    [string] $DumpJavaHome,
    [string] $Username,
    [string] $FailedLoginUser = 'mcp-record-no-such-user',
    [string] $OutputDirectory,
    [string] $WorkRoot = $env:TEMP,
    [string] $NeutralizeMap,
    [int] $TimeoutSeconds = 120,
    [switch] $List
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = Split-Path -Parent $PSScriptRoot

# The case catalogue (feature 025, research D-12): the only place for the StartCLI arguments and
# session commands of a case. Kind = how the case runs; Arguments = the case's own arguments
# (<owner> and <file> are filled in by the export kinds); Commands = the session commands;
# Credentials = the case asks for the username and password; Contacts = the case contacts the
# server of -CliUrl (its host is checked against RECORD_ALLOWED_HOSTS before the case runs);
# Folder (repository-relative, unless -OutputDirectory) and FixtureName = where
# and under which name the case's files are written.
$Cases = @(
    [pscustomobject]@{
        Name        = 'win_version'
        Description = 'startcli.bat -v (no server, no credentials)'
        Kind        = 'local'
        Arguments   = @('-v')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_version'
    }
    [pscustomobject]@{
        Name        = 'win_help'
        Description = 'startcli.bat -h (no server, no credentials)'
        Kind        = 'local'
        Arguments   = @('-h')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_help'
    }
    [pscustomobject]@{
        Name        = 'win_modules'
        Description = 'module export of -Owner into a C:/ path (credentials, -CliUrl, -Owner)'
        Kind        = 'export'
        Arguments   = @('--execCommand', "export --exportModule '' --exportModuleGroup '' --exportModuleUser '<owner>' --exportFile <file>")
        Commands    = @()
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_modules'
    }
    [pscustomobject]@{
        Name        = 'win_failure'
        Description = 'uptime against https://127.0.0.1:9 (no server, dummy password)'
        Kind        = 'failure'
        Arguments   = @('--execCommand', 'uptime')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_failure'
    }
    [pscustomobject]@{
        Name        = 'win_argv'
        Description = 'argument list the Java main receives for the contract test values'
        Kind        = 'argv'
        Arguments   = @('--execCommand', 'uptime')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_argv'
    }
    [pscustomobject]@{
        Name        = 'win_backslash'
        Description = 'module export with a C:\ path in the command (credentials, -CliUrl, -Owner)'
        Kind        = 'export-backslash'
        Arguments   = @('--execCommand', "export --exportModule '' --exportModuleGroup '' --exportModuleUser '<owner>' --exportFile <file>")
        Commands    = @()
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\windows'
        FixtureName = 'win_backslash'
    }
    [pscustomobject]@{
        Name        = 'v74_help'
        Description = '-h of the 7.4 client (no server, no credentials)'
        Kind        = 'local'
        Arguments   = @('-h')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'help'
    }
    [pscustomobject]@{
        Name        = 'v74_version'
        Description = '-v of the 7.4 client (no server, no credentials)'
        Kind        = 'local'
        Arguments   = @('-v')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'v74_version'
    }
    [pscustomobject]@{
        Name        = 'v74_exec_help'
        Description = 'script mode help: the 7.4 interactive commands (credentials, -CliUrl)'
        Kind        = 'exec'
        Arguments   = @('--execCommand', 'help')
        Commands    = @()
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'v74_exec_help'
    }
    [pscustomobject]@{
        Name        = 'v74_exec_ps_help'
        Description = 'script mode ps -h: the 7.4 ps options (credentials, -CliUrl)'
        Kind        = 'exec'
        Arguments   = @('--execCommand', 'ps -h')
        Commands    = @()
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'v74_exec_ps_help'
    }
    [pscustomobject]@{
        Name        = 'v74_direct_health'
        Description = 'the direct health options in one run (credentials, -CliUrl)'
        Kind        = 'direct'
        Arguments   = @('-c', '-sv', '--pctmem', '--maintenanceState', '-ne', '-nx', '-nw', '-nq')
        Commands    = @()
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'v74_direct_health'
    }
    [pscustomobject]@{
        Name        = 'v74_health_session'
        Description = 'one session with the health commands of the 6.1 session (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('uname', 'uptime', 'maintenance', 'free -m', 'ps -c -e', 'ps -c -w', 'ps -c -q', 'ps -c -a', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'health_session_ok'
    }
    [pscustomobject]@{
        Name        = 'v74_ps_all'
        Description = 'one session listing all processes (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('ps -a -l 10000', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'ps_session_all'
    }
    [pscustomobject]@{
        Name        = 'v74_ps_error'
        Description = 'one session listing the failed processes (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('ps -e -l 10000', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'ps_session_error'
    }
    [pscustomobject]@{
        Name        = 'v74_ps_waiting'
        Description = 'one session listing the waiting processes (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('ps -w -l 10000', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'ps_session_waiting'
    }
    [pscustomobject]@{
        Name        = 'v74_ps_queued'
        Description = 'one session listing the queued processes (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('ps -q -l 10000', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'ps_session_queued'
    }
    [pscustomobject]@{
        Name        = 'v74_ps_limit5'
        Description = 'one session listing at most five processes (credentials, -CliUrl)'
        Kind        = 'session'
        Arguments   = @()
        Commands    = @('ps -a -l 5', 'exit')
        Credentials = $true
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'ps_session_limit5'
    }
    [pscustomobject]@{
        Name        = 'v74_login_failed'
        Description = 'uptime as -FailedLoginUser with a dummy password (-CliUrl)'
        Kind        = 'failure'
        Arguments   = @('--execCommand', 'uptime')
        Commands    = @()
        Credentials = $false
        Contacts    = $true
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'login_failed'
    }
    [pscustomobject]@{
        Name        = 'v74_failure'
        Description = 'uptime against https://127.0.0.1:9 (no server, dummy password)'
        Kind        = 'failure'
        Arguments   = @('--execCommand', 'uptime')
        Commands    = @()
        Credentials = $false
        Contacts    = $false
        Folder      = 'src\test\resources\fixtures\v7_4\cli'
        FixtureName = 'unreachable'
    }
)

# Same as StartCliEnvironment.WINDOWS_VARIABLES and WINDOWS_JAVA_TOOL_OPTIONS (research R-6).
$AllowedVariables = @('SystemRoot', 'windir', 'SystemDrive', 'PATHEXT', 'TEMP', 'TMP', 'USERPROFILE', 'USERNAME', 'USERDOMAIN', 'APPDATA', 'LOCALAPPDATA', 'ProgramData', 'OS', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS')
$JavaToolOptions = '-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djline.terminal=jline.UnsupportedTerminal'

# The server's limits (WindowsCommandLine): 8191 minus a margin of 2048, and the path rule.
$MaxLineLength = 6143
$PathRule = '^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,256}$'
$FailureUrl = 'https://127.0.0.1:9/ibis/servlet/IBISSoapServlet'
$ArgvTestValues = @('plain', 'with space', "''", "a'b", '(x)', 'a;b', 'a,b', 'a=b', 'C:\dir\',
    'C:/dir/x', '*', 'a?b')

$Latin1 = [Text.Encoding]::GetEncoding(28591)
$Utf8 = New-Object Text.UTF8Encoding($false)

function Stop-Recording {
    param([string] $Message)
    throw "record-fixtures: $Message"
}

function Assert-AllowedGroup {
    $allowed = @()
    if ($env:RECORD_ALLOWED_GROUPS) {
        $allowed = @($env:RECORD_ALLOWED_GROUPS -split '\s+' | Where-Object { $_ })
    }
    if ($allowed.Count -eq 0) {
        Stop-Recording 'RECORD_ALLOWED_GROUPS is empty: list the NON-PRODUCTION groups that may be recorded'
    }
    if ($allowed -cnotcontains $Group) {
        Stop-Recording "group '$Group' is not listed in RECORD_ALLOWED_GROUPS"
    }
}

function Assert-AllowedHost {
    $allowed = @()
    if ($env:RECORD_ALLOWED_HOSTS) {
        $allowed = @($env:RECORD_ALLOWED_HOSTS -split '\s+' | Where-Object { $_ })
    }
    $hostName = ([Uri] $CliUrl).Host
    if ($allowed -notcontains $hostName) {
        Stop-Recording "the host of -CliUrl is not listed in RECORD_ALLOWED_HOSTS (NON-PRODUCTION hosts only)"
    }
}

# The characters the server refuses (WindowsArguments): "%!^&|<>, control characters, non-ASCII.
function Assert-Passable {
    param([string] $What, [string] $Value, [string] $Extra = '')
    foreach ($c in $Value.ToCharArray()) {
        if ([char]::IsControl($c) -or [int]$c -ge 0x7f -or '"%!^&|<>'.IndexOf($c) -ge 0 -or
            ($Extra -and $Extra.IndexOf($c) -ge 0)) {
            Stop-Recording "$What contains a character the server refuses on Windows (U+$('{0:X4}' -f [int]$c))"
        }
    }
}

# Like WindowsArguments.quote: in double quotes, trailing backslashes doubled.
function ConvertTo-Quoted {
    param([string] $Value)
    $trailing = $Value.Length - $Value.TrimEnd('\').Length
    return '"' + $Value + ('\' * $trailing) + '"'
}

function ConvertTo-CommandPath {
    param([string] $Path)
    $slashed = $Path.Replace('\', '/')
    if ($slashed -notmatch $PathRule) {
        Stop-Recording "'$slashed' does not fit the server's path rule; use e.g. -WorkRoot C:\inubit-record"
    }
    return "'" + $slashed + "'"
}

function New-PrivateDirectory {
    param([string] $Root)
    Assert-Passable 'WorkRoot' $Root ';'
    $path = Join-Path $Root ('inubit-record-' + [Guid]::NewGuid().ToString('N').Substring(0, 12))
    New-Item -ItemType Directory -Path $path | Out-Null
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $icacls = Join-Path ([Environment]::SystemDirectory) 'icacls.exe'
    & $icacls $path /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Stop-Recording "icacls could not make the work directory private (exit $LASTEXITCODE)"
    }
    return $path
}

# Starts bin\startcli.bat like the server and returns the exit code; stdout and stderr go to
# <OutputPrefix>.stdout and .stderr byte for byte. ToolOptions are appended to JAVA_TOOL_OPTIONS.
function Invoke-StartCli {
    param([string[]] $Arguments, [byte[]] $Stdin, [string] $OutputPrefix,
        [string] $WorkingDirectory, [string] $EffectiveJavaHome = $JavaHome,
        [string] $ToolOptions = '')
    $systemDirectory = [Environment]::SystemDirectory
    $interpreter = Join-Path $systemDirectory 'cmd.exe'
    $batch = Join-Path $CliHome 'bin\startcli.bat'
    $inner = (@($batch) + $Arguments | ForEach-Object { ConvertTo-Quoted $_ }) -join ' '
    if ($inner.Length + 2 -gt $MaxLineLength) {
        Stop-Recording "the command line is longer than $MaxLineLength characters"
    }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $interpreter
    $info.Arguments = '/d /v:off /s /c ' + '"' + $inner + '"'
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $parent = @{}
    foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
        $parent[[string]$entry.Key] = [string]$entry.Value
    }
    $info.EnvironmentVariables.Clear()
    foreach ($name in $AllowedVariables) {
        if ($parent.ContainsKey($name)) {
            $info.EnvironmentVariables[$name] = $parent[$name]
        }
    }
    $info.EnvironmentVariables['ComSpec'] = $interpreter
    $info.EnvironmentVariables['PATH'] = (Join-Path $EffectiveJavaHome 'bin') + ';' + $systemDirectory
    $info.EnvironmentVariables['NoDefaultCurrentDirectoryInExePath'] = '1'
    $info.EnvironmentVariables['JAVA_HOME'] = $EffectiveJavaHome
    $info.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = $JavaToolOptions + $ToolOptions

    $stdout = [IO.File]::Create($OutputPrefix + '.stdout')
    $stderr = [IO.File]::Create($OutputPrefix + '.stderr')
    try {
        $process = [Diagnostics.Process]::Start($info)
        $copyOut = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $copyErr = $process.StandardError.BaseStream.CopyToAsync($stderr)
        try {
            if ($Stdin) {
                $process.StandardInput.BaseStream.Write($Stdin, 0, $Stdin.Length)
                $process.StandardInput.BaseStream.Flush()
            }
        } catch [IO.IOException] {
            # the client ended without reading its input
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $taskkill = Join-Path $systemDirectory 'taskkill.exe'
            & $taskkill /T /F /PID $process.Id | Out-Null
            $process.WaitForExit()
            Write-Warning "timeout after $TimeoutSeconds s: process tree ended"
        }
        $process.WaitForExit()
        $copyOut.Wait()
        $copyErr.Wait()
        return $process.ExitCode
    } finally {
        $stdout.Dispose()
        $stderr.Dispose()
    }
}

function Read-Username {
    $name = $Username
    if (-not $name) {
        $name = Read-Host 'INUBIT username (non-production node)'
    }
    Assert-Passable 'the username' $name
    return $name
}

# The password as UTF-8 bytes plus a line feed, the way the server writes it to the client.
function Read-PasswordBytes {
    $secure = Read-Host -AsSecureString 'INUBIT password (read by StartCLI from its standard input)'
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return , $Utf8.GetBytes([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) + "`n")
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

function Get-TlsArguments {
    $tls = @()
    if ($TrustStore) {
        Assert-Passable 'TrustStore' $TrustStore
        $tls += @('--trustStoreFilePath', $TrustStore)
    }
    if ($DisableHostNameVerification) {
        $tls += '--disableHostNameVerification'
    }
    return , $tls
}

# The trust store of a 7.4 case (spec FR-017): the 7.4 client has no trust-store option, so
# -TrustStore goes to its Java runtime settings like CliRunner.trustStoreProperties does, with a
# leading space. JAVA_TOOL_OPTIONS is split at whitespace without quoting.
function Get-TrustStoreToolOptions {
    if (-not $TrustStore) { return '' }
    Assert-Passable 'TrustStore' $TrustStore
    if ($TrustStore -match '\s') {
        Stop-Recording 'a -TrustStore path with whitespace cannot be passed in JAVA_TOOL_OPTIONS'
    }
    $type = switch ([IO.Path]::GetExtension($TrustStore).ToLowerInvariant()) {
        '.jks' { 'JKS' }
        '.p12' { 'PKCS12' }
        '.pfx' { 'PKCS12' }
        default { Stop-Recording '-TrustStore needs the extension .jks, .p12 or .pfx (its type)' }
    }
    return " -Djavax.net.ssl.trustStore=$TrustStore -Djavax.net.ssl.trustStoreType=$type"
}

# A case that contacts the server of -CliUrl (spec FR-017): only a host of RECORD_ALLOWED_HOSTS.
function Assert-CliUrl {
    if (-not $CliUrl) { Stop-Recording "$script:CaseName needs -CliUrl" }
    Assert-Passable 'CliUrl' $CliUrl
    Assert-AllowedHost
}

function Assert-ExportInputs {
    if ($Owner -notmatch '^[A-Za-z0-9_.-]{1,200}$') {
        Stop-Recording "$script:CaseName needs -Owner matching ^[A-Za-z0-9_.-]{1,200}$"
    }
}

# The arguments of an export case with -Owner for <owner> and the export file for <file>.
function Get-ExportArguments {
    param([string[]] $Arguments, [string] $ExportFile)
    return , @($Arguments | ForEach-Object { $_.Replace('<owner>', $Owner).Replace('<file>', $ExportFile) })
}

function Write-ExitCode {
    param([string] $Prefix, [int] $Code)
    [IO.File]::WriteAllText($Prefix + '.exit', "$Code`n")
}

# One interactive StartCLI session (spec FR-017, research D-12): one startcli.bat process whose
# standard input is the password line, then the case's commands with its end-of-session command
# last, one per line, as the server's sessions do (CliRunner.runSession).
function Invoke-StartCliSession {
    param([object] $Entry, [string] $UserName, [byte[]] $PasswordLine, [string] $OutputPrefix)
    foreach ($command in @($Entry.Commands)) {
        if ($command -match '[\x00-\x1f\x7f]') {
            Stop-Recording "a session command of $($Entry.Name) contains a line feed or control character"
        }
    }
    $commands = $Utf8.GetBytes((@($Entry.Commands) | ForEach-Object { $_ + "`n" }) -join '')
    $stdin = New-Object byte[] ($PasswordLine.Length + $commands.Length)
    [Array]::Copy($PasswordLine, $stdin, $PasswordLine.Length)
    [Array]::Copy($commands, 0, $stdin, $PasswordLine.Length, $commands.Length)
    try {
        return Invoke-StartCli -Arguments (@('-u', $UserName) + $Entry.Arguments + @($CliUrl)) -Stdin $stdin `
            -OutputPrefix $OutputPrefix -WorkingDirectory $CliHome -ToolOptions $TrustStoreOptions
    } finally {
        [Array]::Clear($stdin, 0, $stdin.Length)
    }
}

function Write-ZipEntries {
    param([string] $Zip, [string] $Target)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
    [IO.File]::WriteAllText($Target, (($names -join "`n") + "`n"), $Utf8)
}

function Invoke-CaseLocal {
    param([object] $Entry)
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $code = Invoke-StartCli -Arguments @($Entry.Arguments) -Stdin $null -OutputPrefix $prefix `
        -WorkingDirectory $CliHome
    Write-ExitCode $prefix $code
}

function Invoke-CaseFailure {
    param([object] $Entry)
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $user = 'recorder'
    $url = $FailureUrl
    if ($Entry.Contacts) {
        Assert-Passable 'FailedLoginUser' $FailedLoginUser
        $user = $FailedLoginUser
        $url = $CliUrl
        $script:SecretChecks += , @{ Prefix = $prefix; Username = $user }
    }
    $tls = @()
    $options = $TrustStoreOptions
    if ($Entry.Name -notlike 'v74_*') {
        $tls = Get-TlsArguments
        $options = ''
    }
    $dummy = $Utf8.GetBytes("not-a-password`n")
    $code = Invoke-StartCli -Arguments (@('-u', $user) + $tls + $Entry.Arguments +
        @($url)) -Stdin $dummy -OutputPrefix $prefix `
        -WorkingDirectory $CliHome -ToolOptions $options
    Write-ExitCode $prefix $code
}

function Invoke-CaseExport {
    param([object] $Entry, [switch] $Backslash)
    Assert-ExportInputs
    $name = Read-Username
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $exportDirectory = Join-Path $script:PrivateDirectory ($script:CaseName + '-export')
    New-Item -ItemType Directory -Path $exportDirectory | Out-Null
    $exportFile = Join-Path $exportDirectory 'export.zip'
    $commandPath = ConvertTo-CommandPath $exportFile
    if ($Backslash) {
        $commandPath = $commandPath.Replace('/', '\')
    }
    $started = Get-Date
    $passwordBytes = Read-PasswordBytes
    try {
        $code = Invoke-StartCli -Arguments (@('-u', $name) + (Get-TlsArguments) +
            (Get-ExportArguments $Entry.Arguments $commandPath) + @($CliUrl)) `
            -Stdin $passwordBytes -OutputPrefix $prefix -WorkingDirectory $CliHome
        Write-ExitCode $prefix $code
        $script:SecretChecks += , @{ Prefix = $prefix; Username = $name }
        Assert-NoSecret $prefix $passwordBytes
    } finally {
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    }
    # A mangled C:\ path may land in the working directory (cli.home) or at a drive root.
    $places = @($script:PrivateDirectory, $CliHome)
    $roots = @([IO.Path]::GetPathRoot($exportFile), [IO.Path]::GetPathRoot($CliHome)) |
        Select-Object -Unique
    $written = @(Get-ChildItem -LiteralPath $places -Recurse -File -Filter *.zip `
            -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $started })
    $written += @(Get-ChildItem -LiteralPath $roots -File -Filter *.zip `
            -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $started })
    $outside = @($written | Where-Object {
            -not $_.FullName.StartsWith($script:PrivateDirectory + '\',
                [StringComparison]::OrdinalIgnoreCase) })
    if ($Backslash) {
        $lines = @("command path: $commandPath", "expected file exists: $(Test-Path -LiteralPath $exportFile)")
        $lines += @($written | ForEach-Object { "written: $($_.FullName)" })
        if ($written.Count -eq 0) {
            $lines += 'written: none found in the work directory, cli.home or the drive roots'
        }
        [IO.File]::WriteAllText($prefix + '.result', (($lines -join "`n") + "`n"), $Utf8)
    } elseif (Test-Path -LiteralPath $exportFile) {
        Write-ZipEntries $exportFile ($prefix + '.entries')
    } else {
        Write-Warning "$($script:CaseName): StartCLI wrote no export file"
    }
    foreach ($file in $outside) {
        Remove-Item -LiteralPath $file.FullName -Force
        Write-Host "deleted the export StartCLI wrote outside the work directory: $($file.Name)"
    }
}

function Invoke-CaseSession {
    param([object] $Entry)
    $name = Read-Username
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $passwordBytes = Read-PasswordBytes
    try {
        $code = Invoke-StartCliSession -Entry $Entry -UserName $name -PasswordLine $passwordBytes `
            -OutputPrefix $prefix
        Write-ExitCode $prefix $code
        $script:SecretChecks += , @{ Prefix = $prefix; Username = $name }
        Assert-NoSecret $prefix $passwordBytes
    } finally {
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    }
}

# A 7.4 script-mode (exec) or direct-option (direct) case: startcli.bat -u <user> <the case's
# arguments> <url>, the password line on standard input (spec FR-016, FR-017).
function Invoke-CaseExec {
    param([object] $Entry)
    $name = Read-Username
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $passwordBytes = Read-PasswordBytes
    try {
        $code = Invoke-StartCli -Arguments (@('-u', $name) + $Entry.Arguments + @($CliUrl)) -Stdin $passwordBytes `
            -OutputPrefix $prefix -WorkingDirectory $CliHome -ToolOptions $TrustStoreOptions
        Write-ExitCode $prefix $code
        $script:SecretChecks += , @{ Prefix = $prefix; Username = $name }
        Assert-NoSecret $prefix $passwordBytes
    } finally {
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
    }
}

$ArgvDumpSource = @'
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArgvDump {
    public static void main(String[] args) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            StringBuilder line = new StringBuilder("[" + (i - 1) + "] ");
            args[i].codePoints().forEach(c -> line.append(c < 0x20 || c > 0x7e
                ? String.format("\\u%04x", c) : String.valueOf((char) c)));
            lines.add(line.toString());
        }
        Files.write(Paths.get(args[0]), lines, StandardCharsets.UTF_8);
    }
}
'@

# Why the wrapper runtime of win_argv cannot take the place of the client's java, or $null: the
# Virtimo clients call <cli.home>\..\java_home_path_setter.cmd first, which sets JAVA_HOME itself,
# and start "%JAVA_HOME%\bin\java.exe" by its full name (013 T031).
function Get-ArgvObstacle {
    $setter = Join-Path (Split-Path -Parent $CliHome) 'java_home_path_setter.cmd'
    if ((Test-Path -LiteralPath $setter) -and
        ([IO.File]::ReadAllText($setter) -match '(?im)^\s*set\s+"?JAVA_HOME=')) {
        return 'java_home_path_setter.cmd next to the client directory sets JAVA_HOME itself'
    }
    $batch = Join-Path $CliHome 'bin\startcli.bat'
    if ([IO.File]::ReadAllText($batch) -match '(?i)%JAVA_HOME%\\bin\\java\.exe') {
        return 'bin\startcli.bat starts %JAVA_HOME%\bin\java.exe, not java from PATH'
    }
    return $null
}

function Invoke-CaseArgv {
    param([object] $Entry)
    $prefix = Join-Path $script:RawDirectory $Entry.FixtureName
    $obstacle = Get-ArgvObstacle
    if ($obstacle) {
        Write-Warning "win_argv not recorded: $obstacle"
        [IO.File]::WriteAllText($prefix + '.stdout', "not recorded: $obstacle`n", $Utf8)
        [IO.File]::WriteAllText($prefix + '.stderr', '', $Utf8)
        [IO.File]::WriteAllText($prefix + '.exit', "skipped`n")
        return
    }
    $wrapperHome = Join-Path $script:PrivateDirectory 'argv-java'
    $wrapperBin = Join-Path $wrapperHome 'bin'
    New-Item -ItemType Directory -Path $wrapperBin | Out-Null
    $source = Join-Path $script:PrivateDirectory 'ArgvDump.java'
    [IO.File]::WriteAllText($source, $ArgvDumpSource, $Utf8)
    $jdk = $DumpJavaHome
    if (-not $jdk) { $jdk = $JavaHome }
    if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\javac.exe'))) {
        Stop-Recording "win_argv needs a JDK (bin\javac.exe) for its dump program: -JavaHome has none, pass -DumpJavaHome <JDK>"
    }
    Assert-Passable 'DumpJavaHome' $jdk ';'
    $realJava = Join-Path $jdk 'bin\java.exe'
    $report = New-Object Collections.Generic.List[string]
    $exitCodes = New-Object Collections.Generic.List[string]
    $index = 0
    foreach ($value in $ArgvTestValues) {
        $index++
        $dump = Join-Path $script:PrivateDirectory "argv-$index.txt"
        $wrapper = '@"' + $realJava + '" "' + $source + '" "' + $dump + '" %*' + "`r`n"
        [IO.File]::WriteAllText((Join-Path $wrapperBin 'java.cmd'), "@echo off`r`n" + $wrapper,
            [Text.Encoding]::ASCII)
        $tls = @('--trustStoreFilePath', $value)
        $code = Invoke-StartCli -Arguments (@('-u', $value) + $tls + $Entry.Arguments +
            @($FailureUrl)) -Stdin $null `
            -OutputPrefix (Join-Path $script:PrivateDirectory "argv-$index") `
            -WorkingDirectory $CliHome -EffectiveJavaHome $wrapperHome
        $exitCodes.Add("$index $code")
        $report.Add("value ${index}: $value")
        if (Test-Path -LiteralPath $dump) {
            foreach ($line in [IO.File]::ReadAllLines($dump)) { $report.Add("  $line") }
        } else {
            $report.Add('  (no dump: java from JAVA_HOME\bin did not run the dump program; its stderr:)')
            $stderr = Join-Path $script:PrivateDirectory "argv-$index.stderr"
            foreach ($line in [IO.File]::ReadAllLines($stderr)) { $report.Add("  | $line") }
        }
    }
    [IO.File]::WriteAllText($prefix + '.stdout', (($report -join "`n") + "`n"), $Utf8)
    [IO.File]::WriteAllText($prefix + '.stderr', '', $Utf8)
    [IO.File]::WriteAllText($prefix + '.exit', (($exitCodes -join "`n") + "`n"))
}

# Fails if the password (without its line feed) is in an output of the case, in UTF-8 (as the
# client reads it) or in UTF-16 LE (the wide-character output of Java and Windows tools).
function Assert-NoSecret {
    param([string] $Prefix, [byte[]] $Secret)
    $length = $Secret.Length - 1
    if ($length -le 0) { return }
    $narrow = New-Object byte[] $length
    [Array]::Copy($Secret, $narrow, $length)
    $chars = $Utf8.GetChars($narrow)
    $wide = [Text.Encoding]::Unicode.GetBytes($chars)
    try {
        foreach ($file in Get-ChildItem -LiteralPath (Split-Path $Prefix) -File) {
            $bytes = [IO.File]::ReadAllBytes($file.FullName)
            foreach ($needle in @($narrow, $wide)) {
                for ($i = 0; $i -le $bytes.Length - $needle.Length; $i++) {
                    $match = $true
                    for ($j = 0; $j -lt $needle.Length; $j++) {
                        if ($bytes[$i + $j] -ne $needle[$j]) { $match = $false; break }
                    }
                    if ($match) {
                        Remove-Item -LiteralPath (Split-Path $Prefix) -Recurse -Force
                        Stop-Recording "the password appeared in $($file.Name); outputs removed"
                    }
                }
            }
        }
    } finally {
        foreach ($buffer in @($narrow, $chars, $wide)) { [Array]::Clear($buffer, 0, $buffer.Length) }
    }
}

# The 8.3 form of an existing path (e.g. C:\Users\JOHNDO~1), or $null.
function Get-ShortPath {
    param([string] $Path)
    try {
        $fso = New-Object -ComObject Scripting.FileSystemObject
        if (Test-Path -LiteralPath $Path -PathType Container) { return $fso.GetFolder($Path).ShortPath }
        if (Test-Path -LiteralPath $Path -PathType Leaf) { return $fso.GetFile($Path).ShortPath }
    } catch {
        return $null
    }
    return $null
}

# The UTF-8 bytes of $Text read as Latin-1, to match it inside a file read as Latin-1.
function ConvertTo-ByteText {
    param([string] $Text)
    return $Latin1.GetString($Utf8.GetBytes($Text))
}

# Replaces the local paths, the host and the account names in the raw text outputs (byte-wise, so
# the code page of the recording is kept). Paths: case-insensitive, with \ and /; names: whole
# words, case-sensitive.
function Protect-Outputs {
    $paths = [ordered]@{}
    $paths[$script:PrivateDirectory] = '<work>'
    $paths[$CliHome] = '<cli.home>'
    $paths[$JavaHome] = '<java.home>'
    if ($DumpJavaHome) { $paths[$DumpJavaHome] = '<jdk.home>' }
    if ($TrustStore) { $paths[$TrustStore] = '<trust.store>' }
    if ($env:USERPROFILE) { $paths[$env:USERPROFILE] = '<user.home>' }
    foreach ($key in @($paths.Keys)) {
        $short = Get-ShortPath $key
        if ($short -and $short -ne $key -and -not $paths.Contains($short)) { $paths[$short] = $paths[$key] }
    }
    $names = [ordered]@{}
    if ($CliUrl) { $names[([Uri] $CliUrl).Host] = 'node.example.test' }
    foreach ($check in $script:SecretChecks) { $names[$check.Username] = 'recorder' }
    if ($env:USERNAME) { $names[$env:USERNAME] = 'windows-user' }
    if ($env:USERDOMAIN) { $names[$env:USERDOMAIN] = 'windows-domain' }
    if ($env:COMPUTERNAME) { $names[$env:COMPUTERNAME] = 'windows-host' }
    $ignoreCase = [Text.RegularExpressions.RegexOptions]::IgnoreCase
    foreach ($file in Get-ChildItem -LiteralPath $script:RawDirectory -File) {
        $text = $Latin1.GetString([IO.File]::ReadAllBytes($file.FullName))
        foreach ($key in @($paths.Keys | Sort-Object { $_.Length } -Descending)) {
            foreach ($variant in @($key, $key.Replace('\', '/'))) {
                $text = [regex]::Replace($text, [regex]::Escape((ConvertTo-ByteText $variant)),
                    $paths[$key], $ignoreCase)
            }
        }
        foreach ($key in $names.Keys) {
            if (-not $key) { continue }
            $pattern = '(?<![A-Za-z0-9_])' + [regex]::Escape((ConvertTo-ByteText $key)) + '(?![A-Za-z0-9_])'
            $text = [regex]::Replace($text, $pattern, $names[$key])
        }
        [IO.File]::WriteAllBytes($file.FullName, $Latin1.GetBytes($text))
    }
}

# Fails if the host of -CliUrl is still in an output (after Protect-Outputs and neutralize.py).
function Assert-NoHost {
    if (-not $CliUrl) { return }
    $hostText = ConvertTo-ByteText ([Uri] $CliUrl).Host
    foreach ($file in Get-ChildItem -LiteralPath $script:RawDirectory -File) {
        $text = $Latin1.GetString([IO.File]::ReadAllBytes($file.FullName))
        if ($text.IndexOf($hostText, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Stop-Recording "the host of -CliUrl is still in $($file.Name); nothing written"
        }
    }
}

function Invoke-Neutralize {
    $map = $NeutralizeMap
    if (-not $map) { $map = $env:INUBIT_MCP_NEUTRALIZE_MAP }
    if (-not $map) { $map = Join-Path $RepositoryRoot '.neutralize-map' }
    if (-not (Test-Path -LiteralPath $map)) {
        Stop-Recording "no neutralize map: pass -NeutralizeMap or create $map"
    }
    $python = Get-Command python, py -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $python) { Stop-Recording 'python is needed for tools\neutralize.py' }
    & $python.Source (Join-Path $RepositoryRoot 'tools\neutralize.py') --map $map $script:RawDirectory |
        Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-Recording "neutralize.py failed (exit $LASTEXITCODE)" }
}

function Invoke-RecordCase {
    param([object] $Entry)
    Assert-AllowedGroup
    $script:CaseName = $Entry.Name
    if ($Entry.Contacts) { Assert-CliUrl }
    Write-Host "recording $($Entry.Name) for $Group/$Node"
    switch ($Entry.Kind) {
        'local'            { Invoke-CaseLocal $Entry }
        'export'           { Invoke-CaseExport $Entry }
        'failure'          { Invoke-CaseFailure $Entry }
        'argv'             { Invoke-CaseArgv $Entry }
        'export-backslash' { Invoke-CaseExport $Entry -Backslash }
        'session'          { Invoke-CaseSession $Entry }
        'exec'             { Invoke-CaseExec $Entry }
        'direct'           { Invoke-CaseExec $Entry }
    }
}

if ($List) {
    foreach ($entry in $Cases) {
        '{0,-18} {1}' -f $entry.Name, $entry.Description
    }
    return
}

if (-not $Group -or -not $Node -or -not $Case -or -not $CliHome -or -not $JavaHome) {
    Stop-Recording 'needed: -Group, -Node, -Case, -CliHome, -JavaHome (or JAVA_HOME); -List prints the cases'
}
# powershell -File passes "a,b" as one string
$Case = @($Case | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$Selected = @(foreach ($name in $Case) {
        $entry = $Cases | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if (-not $entry) { Stop-Recording "unknown case '$name' (-List prints them)" }
        $entry
    })
$CliHome = (Resolve-Path -LiteralPath $CliHome).ProviderPath
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).ProviderPath
$WorkRoot = (Resolve-Path -LiteralPath $WorkRoot).ProviderPath
if ($DumpJavaHome) { $DumpJavaHome = (Resolve-Path -LiteralPath $DumpJavaHome).ProviderPath }
if ($TrustStore) { $TrustStore = (Resolve-Path -LiteralPath $TrustStore).ProviderPath }
Assert-Passable 'CliHome' $CliHome
Assert-Passable 'JavaHome' $JavaHome ';'
if (-not (Test-Path -LiteralPath (Join-Path $CliHome 'bin\startcli.bat'))) {
    Stop-Recording "no bin\startcli.bat under -CliHome '$CliHome'"
}
# The 7.4 client has no TLS options (spec FR-017): checked for the whole run, before any case.
$TrustStoreOptions = ''
if (@($Selected | Where-Object { $_.Name -like 'v74_*' }).Count -gt 0) {
    if ($DisableHostNameVerification) {
        Stop-Recording '-DisableHostNameVerification stops a run with v74_* cases: the 7.4 client cannot skip host-name verification'
    }
    $TrustStoreOptions = Get-TrustStoreToolOptions
}

$script:SecretChecks = @()
$private = New-PrivateDirectory $WorkRoot
try {
    $script:PrivateDirectory = $private
    $script:RawDirectory = Join-Path $private 'raw'
    New-Item -ItemType Directory -Path $script:RawDirectory | Out-Null
    foreach ($entry in $Selected) {
        Invoke-RecordCase $entry
    }
    Protect-Outputs
    Invoke-Neutralize
    Assert-NoHost
    foreach ($file in Get-ChildItem -LiteralPath $script:RawDirectory -File) {
        $target = $OutputDirectory
        if (-not $target) {
            $fixture = [IO.Path]::GetFileNameWithoutExtension($file.Name)
            $target = Join-Path $RepositoryRoot ($Cases | Where-Object { $_.FixtureName -eq $fixture }).Folder
        }
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        Write-Host "written: $($file.Name)"
    }
    Write-Host 'next: review the files, then run NoCustomerIdentifiersTest'
} finally {
    if (Test-Path -LiteralPath $private) {
        Remove-Item -LiteralPath $private -Recurse -Force
    }
}
