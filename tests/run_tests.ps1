#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Runs integration tests for the BlockchainIST client.
    Automatically starts the sequencer and node, then shuts them down after tests.
.PARAMETER d
    Enable debug mode: passes -Ddebug=true to the sequencer and node.
.PARAMETER NoSequencer
    Skip sequencer startup and run tests with only the node.
#>
param(
    [switch]$d,
    [switch]$NoSequencer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

################################################
### CONFIGURATION (mirrors defaults in root pom.xml)
$SEQUENCER_HOST = "localhost"
$SEQUENCER_PORT = 3001
$NODE_HOST      = "localhost"
$NODE_PORT      = 2001
$NODE_ORG       = "OrgCoin"
$CLIENT_MODULE  = "client"
### PATHS
$TESTS_DIR        = $PSScriptRoot
$MVN_ROOT_DIR     = Split-Path $TESTS_DIR -Parent
$MVN_ROOT_POM     = Join-Path $MVN_ROOT_DIR "pom.xml"
$INPUTS           = Join-Path $TESTS_DIR "inputs"
$EXPECTED_OUTPUTS = Join-Path $TESTS_DIR "outputs"
$TEST_OUTPUTS     = Join-Path $TESTS_DIR "test-outputs"
$MVN_EXE          = (Get-Command mvn -ErrorAction Stop).Source
$debugArgs        = if ($d) { "-Ddebug=true" } else { "" }
################################################
### UI helpers
################################################

$UI_WIDTH = 60

function Write-Banner {
    $title  = "  BlockchainIST  -  Integration Tests  "
    $border = "=" * $UI_WIDTH
    Write-Host ""
    Write-Host $border                    -ForegroundColor DarkCyan
    Write-Host ((" " * [Math]::Max(0, ($UI_WIDTH - $title.Length) / 2)) + $title) `
                                          -ForegroundColor Cyan
    Write-Host $border                    -ForegroundColor DarkCyan
    Write-Host ""
}

function Write-Section([string]$text) {
    Write-Host ""
    Write-Host ("  $text") -ForegroundColor DarkCyan
    Write-Host ("  " + ("-" * ($UI_WIDTH - 2))) -ForegroundColor DarkGray
}

# Prints a single-line spinner while waiting for a TCP port.
# Returns $true if port opened, $false if timed out.
function Wait-ForPortSpinner([string]$svcName, [string]$hostName, [int]$port, [int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $start    = Get-Date
    Write-Host "  Waiting for $svcName ($($hostName):$port)..." -NoNewline -ForegroundColor DarkGray

    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = [System.Net.Sockets.TcpClient]::new()
            $tcp.Connect($hostName, $port)
            $tcp.Close()
            $elapsed = [Math]::Round(((Get-Date) - $start).TotalSeconds, 1)
            Write-Host " ready (${elapsed}s)" -ForegroundColor Green
            return $true
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    Write-Host " TIMEOUT" -ForegroundColor Red
    return $false
}

# Wait for a service to open its port; dump logs and throw on timeout.
function Assert-ServiceStarted([string]$name, [string]$hostName, [int]$port, [hashtable]$info) {
    if (-not (Wait-ForPortSpinner $name $hostName $port 60)) {
        Write-Host ""
        Write-Host "  !! ${name} log: $($info.LogFile)" -ForegroundColor Yellow
        foreach ($f in @($info.LogFile, $info.DbgFile)) {
            if (Test-Path $f) {
                Get-Content $f | Select-Object -Last 20 |
                    ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
            }
        }
        throw "$name did not start within 60 s"
    }
}

# Kill a process and its entire child tree
function Stop-ProcessTree([System.Diagnostics.Process]$proc) {
    if ($proc -and -not $proc.HasExited) {
        taskkill /F /T /PID $proc.Id 2>$null | Out-Null
    }
}

# Start a Maven module in the background; returns a hashtable with {Process, LogFile, DbgFile}
function Start-MvnBackground([string]$module, [string]$extraArgs) {
    $logFile = Join-Path $TEST_OUTPUTS "${module}.log"
    $dbgFile = Join-Path $TEST_OUTPUTS "${module}.dbg"
    $modulePom = Join-Path (Join-Path $MVN_ROOT_DIR $module) "pom.xml"

    # Build the argument list as an array to avoid cmd.exe quoting issues with spaces in paths
    [string[]]$argList = if (Test-Path $modulePom) {
        @("-f", $modulePom, "exec:java")
    } else {
        @("-f", $MVN_ROOT_POM, "-pl", $module, "exec:java")
    }
    if ($extraArgs.Trim()) {
        $argList += ($extraArgs.Trim() -split '\s+')
    }

    $proc = Start-Process `
        -FilePath               $MVN_EXE `
        -ArgumentList           $argList `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError  $dbgFile `
        -NoNewWindow `
        -PassThru

    return @{ Process = $proc; LogFile = $logFile; DbgFile = $dbgFile }
}

# Run the Maven client, feeding $inputFile to stdin.
# stdout is written to $outputFile (used for diffing).
# stderr is written to a separate .err file so it never pollutes the diff.
# Returns the process exit code.
function Invoke-Client([string]$inputFile, [string]$outputFile, [string]$errFile) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $MVN_EXE
    $psi.Arguments              = "--quiet -f `"$MVN_ROOT_POM`" -pl $CLIENT_MODULE exec:java"
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true

    $proc = [System.Diagnostics.Process]::Start($psi)

    $stdinContent = [System.IO.File]::ReadAllText($inputFile)
    $proc.StandardInput.Write($stdinContent)
    $proc.StandardInput.Close()

    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()

    [System.IO.File]::WriteAllText($outputFile, $stdoutTask.Result)
    if ($stderrTask.Result.Trim()) {
        [System.IO.File]::WriteAllText($errFile, $stderrTask.Result)
    }
    return $proc.ExitCode
}

# Print a side-by-side diff for lines that differ
function Show-Diff([string]$gotFile, [string]$expFile) {
    $got    = Get-Content $gotFile
    $exp    = Get-Content $expFile
    $maxLen = [Math]::Max($got.Count, $exp.Count)
    $col    = 32   # width of each column

    $header  = "  {0,-5}  {1,-$col}  {2}" -f "line", "got", "expected"
    $divider = "  " + ("-" * ($UI_WIDTH - 2))

    $anyDiff = $false
    for ($l = 0; $l -lt $maxLen; $l++) {
        $a = if ($l -lt $got.Count) { $got[$l] } else { '<missing>' }
        $b = if ($l -lt $exp.Count) { $exp[$l] } else { '<missing>' }
        if ($a -ne $b) {
            if (-not $anyDiff) {
                Write-Host $divider -ForegroundColor DarkGray
                Write-Host $header  -ForegroundColor DarkGray
                Write-Host $divider -ForegroundColor DarkGray
                $anyDiff = $true
            }
            Write-Host ("  {0,-5}  " -f ($l + 1))          -NoNewline -ForegroundColor DarkGray
            Write-Host ("{0,-$col}" -f ($a -replace "`t"," ")) -NoNewline -ForegroundColor Red
            Write-Host "  =>  " -NoNewline                  -ForegroundColor DarkGray
            Write-Host ($b -replace "`t"," ")               -ForegroundColor Green
        }
    }
    if ($anyDiff) { Write-Host $divider -ForegroundColor DarkGray }
}

################################################
### Main
################################################

$seqProc   = $null
$nodeProc  = $null
$startTime = Get-Date

Write-Banner

try {
    # ── Prepare output directory ───────────────────────────────────
    if (Test-Path $TEST_OUTPUTS) { Remove-Item -Recurse -Force $TEST_OUTPUTS }
    New-Item -ItemType Directory -Path $TEST_OUTPUTS | Out-Null

    # ── Build project ───────────────────────────────────────────────
    Write-Section "Building project"
    Write-Host "  Running mvn install..." -ForegroundColor DarkGray
    $buildStart = Get-Date
    & $MVN_EXE --quiet -f $MVN_ROOT_POM install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn install failed (exit $LASTEXITCODE)" }
    $buildElapsed = [Math]::Round(((Get-Date) - $buildStart).TotalSeconds, 1)
    Write-Host "  Build complete  (${buildElapsed}s)" -ForegroundColor Green

    Write-Section "Infrastructure"
    if (-not $NoSequencer) {
        # ── Start sequencer ────────────────────────────────────────
        Write-Host "  Starting sequencer  (${SEQUENCER_HOST}:${SEQUENCER_PORT})..." -ForegroundColor DarkGray
        $seqInfo = Start-MvnBackground "sequencer" $debugArgs
        $seqProc = $seqInfo.Process
        Assert-ServiceStarted "sequencer" $SEQUENCER_HOST $SEQUENCER_PORT $seqInfo
    } else {
        Write-Host "  Skipping sequencer startup (-NoSequencer)." -ForegroundColor Yellow
    }

    # ── Start node ─────────────────────────────────────────────────
    Write-Host "  Starting node       (${NODE_HOST}:${NODE_PORT}, org=${NODE_ORG})..." -ForegroundColor DarkGray
    $nodeInfo = Start-MvnBackground "node" $debugArgs
    $nodeProc = $nodeInfo.Process
    Assert-ServiceStarted "node" $NODE_HOST $NODE_PORT $nodeInfo

    # ── Collect test files ─────────────────────────────────────────
    $testFiles = @()
    $idx = 1
    while ($true) {
        $lbl = "{0:D2}" -f $idx
        $f   = Join-Path $INPUTS "input${lbl}.txt"
        if (-not (Test-Path $f)) { break }
        $testFiles += $lbl
        $idx++
    }
    $total = $testFiles.Count

    Write-Section "Running $total test(s)"

    $passed  = 0
    $failed  = 0
    $results = @()   # collect rows for final table

    foreach ($label in $testFiles) {
        $inputFile  = Join-Path $INPUTS           "input${label}.txt"
        $outputFile = Join-Path $TEST_OUTPUTS     "out${label}.txt"
        $expectFile = Join-Path $EXPECTED_OUTPUTS "out${label}.txt"

        $counter = "[$([int]$label)/$total]"
        $errFile = Join-Path $TEST_OUTPUTS "out${label}.err"

        # -- Run --
        $t0       = Get-Date
        $exitCode = Invoke-Client $inputFile $outputFile $errFile
        $elapsed  = [Math]::Round(((Get-Date) - $t0).TotalSeconds, 2)
        $timeStr  = "${elapsed}s"

        $prefix = "  $counter  Test $label"

        if ($exitCode -ne 0) {
            Write-Host ("{0,-35}  " -f $prefix) -NoNewline
            Write-Host "FAILED" -NoNewline -ForegroundColor Red
            Write-Host "  (maven exit $exitCode, $timeStr)" -ForegroundColor DarkGray
            Write-Host "      Output dump:" -ForegroundColor DarkGray
            Get-Content $outputFile | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
            if (Test-Path $errFile) {
                Write-Host "      Stderr dump:" -ForegroundColor DarkGray
                Get-Content $errFile | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
            }
            $failed++
            $results += [PSCustomObject]@{ Label=$label; Status="FAILED"; Time=$timeStr; Reason="maven exit $exitCode" }
            continue
        }

        # Normalise line endings for comparison
        $gotText = (Get-Content $outputFile -Raw) -replace '\r\n|\r', "`n"
        $expText = (Get-Content $expectFile -Raw) -replace '\r\n|\r', "`n"

        if ($gotText -eq $expText) {
            Write-Host ("{0,-35}  " -f $prefix) -NoNewline
            Write-Host "PASSED" -NoNewline -ForegroundColor Green
            Write-Host "  ($timeStr)" -ForegroundColor DarkGray
            $passed++
            $results += [PSCustomObject]@{ Label=$label; Status="PASSED"; Time=$timeStr; Reason="" }
        } else {
            Write-Host ("{0,-35}  " -f $prefix) -NoNewline
            Write-Host "FAILED" -NoNewline -ForegroundColor Red
            Write-Host "  (output mismatch, $timeStr)" -ForegroundColor DarkGray
            Show-Diff $outputFile $expectFile
            $failed++
            $results += [PSCustomObject]@{ Label=$label; Status="FAILED"; Time=$timeStr; Reason="output mismatch" }
        }
    }

    # ── Summary ────────────────────────────────────────────────────
    $totalElapsed = [Math]::Round(((Get-Date) - $startTime).TotalSeconds, 1)
    $resultColor  = if ($failed -eq 0) { 'Green' } else { 'Red' }

    Write-Host ""
    Write-Host ("  " + ("=" * ($UI_WIDTH - 2))) -ForegroundColor DarkCyan
    Write-Host "  SUMMARY" -ForegroundColor Cyan
    Write-Host ("  " + ("-" * ($UI_WIDTH - 2))) -ForegroundColor DarkGray

    foreach ($r in $results) {
        $statusColor = if ($r.Status -eq "PASSED") { 'Green' } else { 'Red' }
        $note = if ($r.Reason) { "  ($($r.Reason))" } else { "" }
        Write-Host ("    Test {0}   " -f $r.Label) -NoNewline -ForegroundColor White
        Write-Host ("{0,-6}" -f $r.Status) -NoNewline -ForegroundColor $statusColor
        Write-Host ("  {0,-8}{1}" -f $r.Time, $note) -ForegroundColor DarkGray
    }

    Write-Host ("  " + ("-" * ($UI_WIDTH - 2))) -ForegroundColor DarkGray
    Write-Host ("  Passed  : $passed / $total") -ForegroundColor $resultColor
    Write-Host ("  Failed  : $failed / $total") -ForegroundColor $(if ($failed -eq 0) { 'DarkGray' } else { 'Red' })
    Write-Host ("  Duration: ${totalElapsed}s total") -ForegroundColor DarkGray
    Write-Host ("  Outputs : $TEST_OUTPUTS") -ForegroundColor DarkGray
    Write-Host ("  " + ("=" * ($UI_WIDTH - 2))) -ForegroundColor DarkCyan
    Write-Host ""

} finally {
    # ── Shut down infrastructure ───────────────────────────────────
    if ($nodeProc -or $seqProc) {
        Write-Host "  Stopping infrastructure..." -NoNewline -ForegroundColor DarkGray
        if ($nodeProc) { Stop-ProcessTree $nodeProc }
        if ($seqProc)  { Stop-ProcessTree $seqProc  }
        Write-Host " done." -ForegroundColor DarkGray
        Write-Host ""
    }
}
