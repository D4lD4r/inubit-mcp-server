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

Each case first checks that -Group is listed in RECORD_ALLOWED_GROUPS (space-separated); the
export cases also check that the host of -CliUrl is listed in RECORD_ALLOWED_HOSTS (space-separated
NON-PRODUCTION host names), so that no other server is contacted. StartCLI runs in -CliHome, like
under the server. -Case takes one or more names, also comma-separated in one argument.

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
if it is one, else -DumpJavaHome.

.EXAMPLE
$env:RECORD_ALLOWED_GROUPS = 'dev'; $env:RECORD_ALLOWED_HOSTS = 'dev-node1.example.test'
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group dev -Node node1 `
  -CliHome C:\inubit\client -JavaHome C:\java\jdk-17 -Case win_version,win_help,win_argv

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group dev -Node node1 `
  -CliHome C:\inubit\client -JavaHome C:\java\jdk-17 -TrustStore C:\inubit\truststore.jks `
  -CliUrl https://dev-node1.example.test:8443/ibis/servlet/IBISSoapServlet -Owner acme_dev `
  -WorkRoot C:\inubit-record -Case win_modules,win_backslash
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
    [string] $OutputDirectory,
    [string] $WorkRoot = $env:TEMP,
    [string] $NeutralizeMap,
    [int] $TimeoutSeconds = 120,
    [switch] $List
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $RepositoryRoot 'src\test\resources\fixtures\windows'
}

# The case catalogue; credentials = the case asks for the username and password.
$Cases = [ordered]@{
    win_version   = 'startcli.bat -v (no server, no credentials)'
    win_help      = 'startcli.bat -h (no server, no credentials)'
    win_modules   = 'module export of -Owner into a C:/ path (credentials, -CliUrl, -Owner)'
    win_failure   = 'uptime against https://127.0.0.1:9 (no server, dummy password)'
    win_argv      = 'argument list the Java main receives for the contract test values'
    win_backslash = 'module export with a C:\ path in the command (credentials, -CliUrl, -Owner)'
}

# Same as StartCliEnvironment.WINDOWS_VARIABLES and WINDOWS_JAVA_TOOL_OPTIONS (research R-6).
$AllowedVariables = @('SystemRoot', 'windir', 'SystemDrive', 'PATHEXT', 'TEMP', 'TMP', 'USERPROFILE', 'USERNAME', 'USERDOMAIN', 'APPDATA', 'LOCALAPPDATA', 'ProgramData', 'OS', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS')
$JavaToolOptions = '-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

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
# <OutputPrefix>.stdout and .stderr byte for byte.
function Invoke-StartCli {
    param([string[]] $Arguments, [byte[]] $Stdin, [string] $OutputPrefix,
        [string] $WorkingDirectory, [string] $EffectiveJavaHome = $JavaHome)
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
    $info.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = $JavaToolOptions

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

function Assert-ExportInputs {
    if (-not $CliUrl) { Stop-Recording "$script:CaseName needs -CliUrl" }
    Assert-Passable 'CliUrl' $CliUrl
    Assert-AllowedHost
    if ($Owner -notmatch '^[A-Za-z0-9_.-]{1,200}$') {
        Stop-Recording "$script:CaseName needs -Owner matching ^[A-Za-z0-9_.-]{1,200}$"
    }
}

function Get-ExportCommand {
    param([string] $ExportFile)
    return "export --exportModule '' --exportModuleGroup '' --exportModuleUser '$Owner' --exportFile $ExportFile"
}

function Write-ExitCode {
    param([string] $Prefix, [int] $Code)
    [IO.File]::WriteAllText($Prefix + '.exit', "$Code`n")
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

function Invoke-CaseVersion {
    param([string] $Flag)
    $prefix = Join-Path $script:RawDirectory $script:CaseName
    $code = Invoke-StartCli -Arguments @($Flag) -Stdin $null -OutputPrefix $prefix `
        -WorkingDirectory $CliHome
    Write-ExitCode $prefix $code
}

function Invoke-CaseFailure {
    $prefix = Join-Path $script:RawDirectory $script:CaseName
    $dummy = $Utf8.GetBytes("not-a-password`n")
    $code = Invoke-StartCli -Arguments (@('-u', 'recorder') + (Get-TlsArguments) +
        @('--execCommand', 'uptime', $FailureUrl)) -Stdin $dummy -OutputPrefix $prefix `
        -WorkingDirectory $CliHome
    Write-ExitCode $prefix $code
}

function Invoke-CaseExport {
    param([switch] $Backslash)
    Assert-ExportInputs
    $name = Read-Username
    $prefix = Join-Path $script:RawDirectory $script:CaseName
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
            @('--execCommand', (Get-ExportCommand $commandPath), $CliUrl)) -Stdin $passwordBytes `
            -OutputPrefix $prefix -WorkingDirectory $CliHome
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

function Invoke-CaseArgv {
    $prefix = Join-Path $script:RawDirectory $script:CaseName
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
        $code = Invoke-StartCli -Arguments (@('-u', $value) + $tls +
            @('--execCommand', 'uptime', $FailureUrl)) -Stdin $null `
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

# Fails if the password (without its line feed) is in an output of the case.
function Assert-NoSecret {
    param([string] $Prefix, [byte[]] $Secret)
    $length = $Secret.Length - 1
    if ($length -le 0) { return }
    foreach ($file in Get-ChildItem -LiteralPath (Split-Path $Prefix) -File) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        for ($i = 0; $i -le $bytes.Length - $length; $i++) {
            $match = $true
            for ($j = 0; $j -lt $length; $j++) {
                if ($bytes[$i + $j] -ne $Secret[$j]) { $match = $false; break }
            }
            if ($match) {
                Remove-Item -LiteralPath (Split-Path $Prefix) -Recurse -Force
                Stop-Recording "the password appeared in $($file.Name); outputs removed"
            }
        }
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
    param([string] $Name)
    Assert-AllowedGroup
    $script:CaseName = $Name
    Write-Host "recording $Name for $Group/$Node"
    switch ($Name) {
        'win_version'   { Invoke-CaseVersion '-v' }
        'win_help'      { Invoke-CaseVersion '-h' }
        'win_modules'   { Invoke-CaseExport }
        'win_failure'   { Invoke-CaseFailure }
        'win_argv'      { Invoke-CaseArgv }
        'win_backslash' { Invoke-CaseExport -Backslash }
    }
}

if ($List) {
    foreach ($entry in $Cases.GetEnumerator()) {
        '{0,-14} {1}' -f $entry.Key, $entry.Value
    }
    return
}

if (-not $Group -or -not $Node -or -not $Case -or -not $CliHome -or -not $JavaHome) {
    Stop-Recording 'needed: -Group, -Node, -Case, -CliHome, -JavaHome (or JAVA_HOME); -List prints the cases'
}
# powershell -File passes "a,b" as one string
$Case = @($Case | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($name in $Case) {
    if (-not $Cases.Contains($name)) { Stop-Recording "unknown case '$name' (-List prints them)" }
}
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

$script:SecretChecks = @()
$private = New-PrivateDirectory $WorkRoot
try {
    $script:PrivateDirectory = $private
    $script:RawDirectory = Join-Path $private 'raw'
    New-Item -ItemType Directory -Path $script:RawDirectory | Out-Null
    foreach ($name in $Case) {
        Invoke-RecordCase $name
    }
    Protect-Outputs
    Invoke-Neutralize
    Assert-NoHost
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $script:RawDirectory -File) {
        Copy-Item -LiteralPath $file.FullName -Destination $OutputDirectory -Force
        Write-Host "written: $($file.Name)"
    }
    Write-Host 'next: review the files, then run NoCustomerIdentifiersTest'
} finally {
    if (Test-Path -LiteralPath $private) {
        Remove-Item -LiteralPath $private -Recurse -Force
    }
}
