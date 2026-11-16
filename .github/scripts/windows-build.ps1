# Runs the offline build on the Windows runner and watches the machine while it runs.
#
# Several Windows jobs ended with "The hosted runner lost communication with the server" and no
# log at all. This script leaves traces for that case: Maven's lines carry a time stamp, a status
# line (free memory, CPU load, Java processes) is printed every minute, and when the free memory
# falls below the limit the script prints the largest processes, ends the build and fails the
# step, so the log is kept instead of the runner disappearing.

#   ./.github/scripts/windows-build.ps1 mvn -B -ntp clean verify
# The arguments are taken as given ($args, no param block, so -B and the like stay Maven's);
# WATCH_INTERVAL_SECONDS and WATCH_MIN_FREE_MB change the defaults.

$ErrorActionPreference = 'Stop'
$Command = [string[]] $args
$IntervalSeconds = 60
if ($env:WATCH_INTERVAL_SECONDS) { $IntervalSeconds = [int] $env:WATCH_INTERVAL_SECONDS }
$MinimumFreeMemoryMb = 700
if ($env:WATCH_MIN_FREE_MB) { $MinimumFreeMemoryMb = [int] $env:WATCH_MIN_FREE_MB }

function Get-Status {
    $os = Get-CimInstance Win32_OperatingSystem
    $freeMb = [int]($os.FreePhysicalMemory / 1024)
    $totalMb = [int]($os.TotalVisibleMemorySize / 1024)
    $load = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    $javaMb = [int](($java | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB)
    [pscustomobject]@{ FreeMb = $freeMb; TotalMb = $totalMb; Load = $load; Java = $java.Count; JavaMb = $javaMb }
}

function Write-Largest {
    Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 12 |
        ForEach-Object {
            '  {0,-28} pid {1,6}  {2,6} MB  cpu {3,8:N0} s' -f $_.ProcessName, $_.Id,
                [int]($_.WorkingSet64 / 1MB), $_.CPU
        } | Write-Host
}

if ($Command.Length -lt 2 -or $Command[0] -ne 'mvn') {
    throw 'usage: windows-build.ps1 mvn <arguments>'
}
$arguments = @('-Dorg.slf4j.simpleLogger.showDateTime=true',
    '-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss') + $Command[1..($Command.Length - 1)]
$maven = Start-Process -FilePath 'mvn.cmd' -ArgumentList $arguments -NoNewWindow -PassThru
$handle = $maven.Handle # keeps the exit code readable after the process ended

while (-not $maven.WaitForExit($IntervalSeconds * 1000)) {
    $status = Get-Status
    Write-Host ('[watch] {0:HH:mm:ss} free {1}/{2} MB, cpu {3}%, java processes {4} ({5} MB)' -f
        (Get-Date), $status.FreeMb, $status.TotalMb, $status.Load, $status.Java, $status.JavaMb)
    if ($status.FreeMb -lt $MinimumFreeMemoryMb) {
        Write-Host "::error::Free memory below $MinimumFreeMemoryMb MB; the build is stopped so that the log is kept"
        Write-Host '[watch] largest processes:'
        Write-Largest
        & taskkill.exe /T /F /PID $maven.Id | Out-Null
        exit 1
    }
}

$maven.WaitForExit()
Write-Host ('[watch] {0:HH:mm:ss} Maven ended with exit code {1}' -f (Get-Date), $maven.ExitCode)
exit $maven.ExitCode
