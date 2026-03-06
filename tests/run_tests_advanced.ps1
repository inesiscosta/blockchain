#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Runs integration tests for the BlockchainIST client.
    Supports both simple sequential tests and concurrent multi-node scenarios.
#>
param(
    [switch]$d,
    [switch]$NoSequencer,
    [switch]$NoSimple,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

################################################
### CONFIGURATION
################################################
$SEQUENCER_HOST = "localhost"
$SEQUENCER_PORT = 3001

# MULTIPLE NODES
$NODES = @(
    @{ Host = "localhost"; Port = 2001; Org = "Org1" },
    @{ Host = "localhost"; Port = 2002; Org = "Org2" },
    @{ Host = "localhost"; Port = 2003; Org = "Org3" }
)

$CLIENT_MODULE  = "client"

### PATHS
$TESTS_DIR          = $PSScriptRoot
$MVN_ROOT_DIR       = Split-Path $TESTS_DIR -Parent
$MVN_ROOT_POM       = Join-Path $MVN_ROOT_DIR "pom.xml"

# Folders for simple tests
$INPUTS             = Join-Path $TESTS_DIR "inputs"
$EXPECTED_OUTPUTS   = Join-Path $TESTS_DIR "outputs"

# Folders for concurrent scenario tests
$SCENARIOS_INPUTS   = Join-Path $TESTS_DIR "scenarios-inputs"
$SCENARIOS_EXPECTED = Join-Path $TESTS_DIR "scenarios-outputs"

$TEST_OUTPUTS       = Join-Path $TESTS_DIR "test-outputs"
$RUN_SUFFIX         = (Get-Date -Format "yyyyMMdd-HHmmss-fff")
$MVN_EXE            = (Get-Command mvn -ErrorAction Stop).Source
$debugArgs          = if ($d) { "-Ddebug=true" } else { "" }

################################################
### UI helpers
################################################
$UI_WIDTH = 80

function Write-Banner {
    $title  = "  BlockchainIST  -  Integration & Scenario Tests  "
    $border = "=" * $UI_WIDTH
    Write-Host "`n$border" -ForegroundColor DarkCyan
    Write-Host ((" " * [Math]::Max(0, ($UI_WIDTH - $title.Length) / 2)) + $title) -ForegroundColor Cyan
    Write-Host "$border`n" -ForegroundColor DarkCyan
}

function Write-Section([string]$text) {
    Write-Host "`n  $text" -ForegroundColor DarkCyan
    Write-Host ("  " + ("-" * ($UI_WIDTH - 2))) -ForegroundColor DarkGray
}

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

function Assert-ServiceStarted([string]$name, [string]$hostName, [int]$port, [hashtable]$info) {
    if (-not (Wait-ForPortSpinner $name $hostName $port 60)) {
        Write-Host "`n  !! ${name} log: $($info.LogFile)" -ForegroundColor Yellow
        throw "$name did not start within 60 s"
    }
}

function Stop-ProcessTree([System.Diagnostics.Process]$proc) {
    if ($proc -and -not $proc.HasExited) {
        taskkill /F /T /PID $proc.Id 2>$null | Out-Null
    }
}

function Start-MvnBackground([string]$module, [string]$execArgs, [string]$extraArgs) {
    $cleanModule = $module -replace '[^a-zA-Z0-9]', '-'
    # Use unique filenames per run to avoid collisions with stale locked files.
    $logFile = Join-Path $TEST_OUTPUTS "${cleanModule}-${RUN_SUFFIX}.log"
    $dbgFile = Join-Path $TEST_OUTPUTS "${cleanModule}-${RUN_SUFFIX}.dbg"
    
    $argList = @("--quiet", "-f", $MVN_ROOT_POM, "-pl", ($module -split '-')[0], "exec:java")
    if ($execArgs) { $argList += "-Dexec.args=`"$execArgs`"" }
    if ($extraArgs.Trim()) { $argList += ($extraArgs.Trim() -split '\s+') }

    $proc = Start-Process -FilePath $MVN_EXE -ArgumentList $argList -RedirectStandardOutput $logFile -RedirectStandardError $dbgFile -NoNewWindow -PassThru
    return @{ Process = $proc; LogFile = $logFile; DbgFile = $dbgFile }
}

function Start-ClientAsync([string]$inputFile, [string]$clientExecArgs, [string]$extraArgs) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName               = $MVN_EXE
    $allArgs = "--quiet -f `"$MVN_ROOT_POM`" -pl $CLIENT_MODULE exec:java -Dexec.args=`"$clientExecArgs`""
    if ($extraArgs.Trim()) { $allArgs += " $($extraArgs.Trim())" }
    $psi.Arguments              = $allArgs
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

    return @{ Process = $proc; StdoutTask = $stdoutTask; StderrTask = $stderrTask }
}

function Stop-StaleInfrastructureProcesses {
    try {
        $projectPom = [Regex]::Escape($MVN_ROOT_POM)
        $stale = @(Get-CimInstance Win32_Process | Where-Object {
            ($_.CommandLine) -and
            ($_.CommandLine -match $projectPom) -and
            ($_.CommandLine -match 'exec:java') -and
            ($_.ProcessId -ne $PID) -and
            ($_.Name -match '^(java|mvn|cmd)(\\.exe|\\.cmd)?$')
        })

        foreach ($proc in $stale) {
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        }

        return $stale.Count
    } catch {
        return 0
    }
}

function Initialize-TestOutputs {
    if (-not (Test-Path $TEST_OUTPUTS)) {
        New-Item -ItemType Directory -Path $TEST_OUTPUTS | Out-Null
        return
    }

    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            Remove-Item -Recurse -Force $TEST_OUTPUTS -ErrorAction Stop
            break
        } catch {
            if ($attempt -eq 1) {
                $killed = Stop-StaleInfrastructureProcesses
                if ($killed -gt 0) {
                    Write-Host "  Clean-up: stopped $killed stale process(es) from previous run." -ForegroundColor Yellow
                }
            }

            if ($attempt -eq $maxAttempts) {
                # Locked files from a previous run should not prevent a new run.
                Write-Host "  Clean-up: some files are locked; continuing without full cleanup." -ForegroundColor Yellow
                break
            }

            Start-Sleep -Milliseconds 600
        }
    }

    if (-not (Test-Path $TEST_OUTPUTS)) {
        New-Item -ItemType Directory -Path $TEST_OUTPUTS | Out-Null
    }
}

function Show-Diff([string]$gotFile, [string]$expFile) {
    $got = Get-Content $gotFile; $exp = Get-Content $expFile
    $maxLen = [Math]::Max($got.Count, $exp.Count)
    $col = 32
    $header  = "  {0,-5}  {1,-$col}  {2}" -f "line", "got", "expected"
    $divider = "  " + ("-" * ($UI_WIDTH - 2))
    $anyDiff = $false
    for ($l = 0; $l -lt $maxLen; $l++) {
        $a = if ($l -lt $got.Count) { $got[$l] } else { '<missing>' }
        $b = if ($l -lt $exp.Count) { $exp[$l] } else { '<missing>' }
        if ($a -ne $b) {
            if (-not $anyDiff) { Write-Host $divider -ForegroundColor DarkGray; Write-Host $header -ForegroundColor DarkGray; Write-Host $divider -ForegroundColor DarkGray; $anyDiff = $true }
            Write-Host ("  {0,-5}  " -f ($l + 1)) -NoNewline -ForegroundColor DarkGray
            Write-Host ("{0,-$col}" -f ($a -replace "`t"," ")) -NoNewline -ForegroundColor Red
            Write-Host "  =>  " -NoNewline -ForegroundColor DarkGray
            Write-Host ($b -replace "`t"," ") -ForegroundColor Green
        }
    }
    if ($anyDiff) { Write-Host $divider -ForegroundColor DarkGray }
}

function Normalize-Output([string]$text) {
    return (($text -replace '\r\n|\r', "`n").Trim())
}

function Get-ScenarioAlternatives([string]$scenarioExpectedDir) {
    $manifestCandidates = @(
        (Join-Path $scenarioExpectedDir "alternatives.txt"),
        (Join-Path $scenarioExpectedDir "expected-alternatives.txt")
    )

    $manifestPath = $null
    foreach ($candidate in $manifestCandidates) {
        if (Test-Path $candidate) {
            $manifestPath = $candidate
            break
        }
    }

    if (-not $manifestPath) { return @() }

    $content = Normalize-Output (Get-Content $manifestPath -Raw)
    if (-not $content) { return @() }

    $alternatives = @()
    $currentAlt = @{}
    $currentClient = $null
    $buffer = [System.Collections.Generic.List[string]]::new()

    foreach ($line in ($content -split "`n")) {
        if ($line -match '^\s*#') { continue }

        if ($line -match '^\s*or\s*$') {
            if ($null -ne $currentClient) {
                $currentAlt[$currentClient] = Normalize-Output ($buffer -join "`n")
                $currentClient = $null
                $buffer.Clear()
            }
            if ($currentAlt.Count -gt 0) {
                $alternatives += ,$currentAlt
                $currentAlt = @{}
            }
            continue
        }

        if ($line -match '^\s*---\s*CLIENT:\s*(.+?)\s*---\s*$') {
            if ($null -ne $currentClient) {
                $currentAlt[$currentClient] = Normalize-Output ($buffer -join "`n")
                $currentClient = $null
                $buffer.Clear()
            }

            $currentClient = $Matches[1].Trim()
            if ($currentAlt.ContainsKey($currentClient)) {
                throw "Duplicate client '$currentClient' found in scenario alternatives file: $manifestPath"
            }
            continue
        }

        if ($null -ne $currentClient) {
            $buffer.Add($line)
        } elseif (-not [string]::IsNullOrWhiteSpace($line)) {
            throw "Invalid content outside client block in scenario alternatives file: $manifestPath"
        }
    }

    if ($null -ne $currentClient) {
        $currentAlt[$currentClient] = Normalize-Output ($buffer -join "`n")
    }

    if ($currentAlt.Count -gt 0) {
        $alternatives += ,$currentAlt
    }

    return $alternatives
}

function Find-MatchingAlternativeIndex([hashtable]$actualByClient, [object[]]$alternatives) {
    for ($i = 0; $i -lt $alternatives.Count; $i++) {
        $candidate = $alternatives[$i]
        if ($candidate.Count -ne $actualByClient.Count) { continue }

        $allMatch = $true
        foreach ($clientName in $actualByClient.Keys) {
            if (-not $candidate.ContainsKey($clientName)) {
                $allMatch = $false
                break
            }
            if ($candidate[$clientName] -ne $actualByClient[$clientName]) {
                $allMatch = $false
                break
            }
        }

        if ($allMatch) { return $i }
    }

    return -1
}

################################################
### Main
################################################
$activeProcs = @()
$startTime = Get-Date

Write-Banner

try {
    Initialize-TestOutputs

    if ($SkipBuild) {
        Write-Section "Building project"
        Write-Host "  Skipping build due to -SkipBuild flag." -ForegroundColor DarkGray
    } else {
        Write-Section "Building project"
        Write-Host "  Running mvn install..." -ForegroundColor DarkGray
        & $MVN_EXE --quiet -f $MVN_ROOT_POM install -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "mvn install failed (exit $LASTEXITCODE)" }
        Write-Host "  Build complete" -ForegroundColor Green
    }

    Write-Section "Infrastructure"
    if (-not $NoSequencer) {
        $seqArgs = "$SEQUENCER_PORT"
        $seqInfo = Start-MvnBackground "sequencer" $seqArgs $debugArgs
        $activeProcs += $seqInfo.Process
        Assert-ServiceStarted "sequencer" $SEQUENCER_HOST $SEQUENCER_PORT $seqInfo
    }

    # Start all nodes
    foreach ($node in $NODES) {
        $nodeArgs = "$($node.Port) $($node.Org) ${SEQUENCER_HOST}:${SEQUENCER_PORT}"
        $nodeInfo = Start-MvnBackground "node-$($node.Port)" $nodeArgs $debugArgs
        $activeProcs += $nodeInfo.Process
        Assert-ServiceStarted "node-$($node.Port)" $node.Host $node.Port $nodeInfo
    }

    $clientArgsStr = ($NODES | ForEach-Object { "$($_.Host):$($_.Port):$($_.Org)" }) -join " "
    $results = @()

    # ==========================================
    # 1. RUN SIMPLE TESTS
    # ==========================================
    if ($NoSimple) {
        Write-Section "Simple tests skipped"
        Write-Host "  Skipping simple tests due to -NoSimple flag." -ForegroundColor DarkGray
    } elseif (Test-Path $INPUTS) {
        $simpleFiles = @(Get-ChildItem -Path $INPUTS -Filter "*.txt")
        if ($simpleFiles.Count -gt 0) {
            Write-Section "Running $($simpleFiles.Count) Simple Test(s)"
            
            foreach ($sf in $simpleFiles) {
                $t0 = Get-Date
                $asyncClient = Start-ClientAsync $sf.FullName $clientArgsStr $debugArgs
                $asyncClient.Process.WaitForExit()
                
                $outFile = Join-Path $TEST_OUTPUTS "out-$($sf.Name)"
                $errFile = Join-Path $TEST_OUTPUTS "err-$($sf.Name)"

                # FIX: Change 'input01.txt' to 'out01.txt' to match your folder
                $expectedName = $sf.Name -replace 'input', 'out'
                $expFile = Join-Path $EXPECTED_OUTPUTS $expectedName

                $stdout = $asyncClient.StdoutTask.Result
                $stderr = $asyncClient.StderrTask.Result
                [System.IO.File]::WriteAllText($outFile, $stdout)
                if ($stderr.Trim()) { [System.IO.File]::WriteAllText($errFile, $stderr) }

                $exitCode = $asyncClient.Process.ExitCode
                $timeStr  = "$([Math]::Round(((Get-Date) - $t0).TotalSeconds, 2))s"
                $prefix   = "  [Simple] $($sf.BaseName)"

                if ($exitCode -ne 0) {
                    Write-Host ("{0,-35}  " -f $prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                    $results += [PSCustomObject]@{ Label="Simple/$($sf.BaseName)"; Status="FAILED"; Time=$timeStr; Reason="exit $exitCode" }
                    continue
                }

                if (Test-Path $expFile) {
                    $gotText = Normalize-Output (Get-Content $outFile -Raw)
                    $expText = Normalize-Output (Get-Content $expFile -Raw)

                    if ($gotText -eq $expText) {
                        Write-Host ("{0,-35}  " -f $prefix) -NoNewline; Write-Host "PASSED" -ForegroundColor Green
                        $results += [PSCustomObject]@{ Label="Simple/$($sf.BaseName)"; Status="PASSED"; Time=$timeStr; Reason="" }
                    } else {
                        Write-Host ("{0,-35}  " -f $prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                        Show-Diff $outFile $expFile
                        $results += [PSCustomObject]@{ Label="Simple/$($sf.BaseName)"; Status="FAILED"; Time=$timeStr; Reason="output mismatch" }
                    }
                } else {
                    Write-Host ("{0,-35}  " -f $prefix) -NoNewline; Write-Host "NO EXPECTED FILE" -ForegroundColor Yellow
                    Write-Host "      (Checked: $expFile)" -ForegroundColor DarkGray
                }
            }
        }
    }

    # ==========================================
    # 2. RUN SCENARIO TESTS
    # ==========================================
    if (Test-Path $SCENARIOS_INPUTS) {
        $scenarios = @(Get-ChildItem -Path $SCENARIOS_INPUTS -Directory)
        
        if ($scenarios.Count -gt 0) {
            Write-Section "Running $($scenarios.Count) Scenario(s)"

            foreach ($scenario in $scenarios) {
                $scenarioName = $scenario.Name
                Write-Host "  Scenario: $scenarioName" -ForegroundColor Cyan

                $scenarioExpectedDir = Join-Path $SCENARIOS_EXPECTED $scenarioName
                $scenarioAlternatives = Get-ScenarioAlternatives $scenarioExpectedDir
                if ($scenarioAlternatives.Count -gt 0) {
                    Write-Host "    -> Using $($scenarioAlternatives.Count) expected alternative(s)" -ForegroundColor DarkGray
                }
                
                # Force array here too just in case
                $clientFiles = @(Get-ChildItem -Path $scenario.FullName -Filter "*.txt")
                $runningClients = @()

                $t0 = Get-Date
                foreach ($cf in $clientFiles) {
                    Write-Host "    -> Starting client: $($cf.Name)" -ForegroundColor DarkGray
                    $asyncClient = Start-ClientAsync $cf.FullName $clientArgsStr $debugArgs
                    
                    $runningClients += @{
                        Name = $cf.Name
                        AsyncObj = $asyncClient
                        OutFile = Join-Path $TEST_OUTPUTS "${scenarioName}-$($cf.Name)"
                        ErrFile = Join-Path $TEST_OUTPUTS "${scenarioName}-$($cf.Name).err"
                        ExpFile = Join-Path $scenarioExpectedDir $cf.Name
                    }
                }

                $completedClients = @()

                foreach ($rc in $runningClients) {
                    $rc.AsyncObj.Process.WaitForExit()
                    
                    $stdout = $rc.AsyncObj.StdoutTask.Result
                    $stderr = $rc.AsyncObj.StderrTask.Result
                    [System.IO.File]::WriteAllText($rc.OutFile, $stdout)
                    if ($stderr.Trim()) { [System.IO.File]::WriteAllText($rc.ErrFile, $stderr) }

                    $exitCode = $rc.AsyncObj.Process.ExitCode
                    $timeStr  = "$([Math]::Round(((Get-Date) - $t0).TotalSeconds, 2))s"
                    $testLabel = "Scenario/${scenarioName}/$($rc.Name)"
                    $prefix   = "    [Client] $($rc.Name)"

                    if ($exitCode -ne 0) {
                        Write-Host ("{0,-35}  " -f $prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                        $results += [PSCustomObject]@{ Label=$testLabel; Status="FAILED"; Time=$timeStr; Reason="exit $exitCode" }
                        $completedClients += [PSCustomObject]@{
                            Name = $rc.Name
                            Prefix = $prefix
                            Label = $testLabel
                            Time = $timeStr
                            OutFile = $rc.OutFile
                            ExpFile = $rc.ExpFile
                            ExitCode = $exitCode
                            NormalizedStdout = ""
                        }
                        continue
                    }

                    $completedClients += [PSCustomObject]@{
                        Name = $rc.Name
                        Prefix = $prefix
                        Label = $testLabel
                        Time = $timeStr
                        OutFile = $rc.OutFile
                        ExpFile = $rc.ExpFile
                        ExitCode = $exitCode
                        NormalizedStdout = Normalize-Output $stdout
                    }
                }

                $successfulClients = @($completedClients | Where-Object ExitCode -eq 0)

                if ($scenarioAlternatives.Count -gt 0) {
                    if ($successfulClients.Count -ne $runningClients.Count) {
                        foreach ($sc in $successfulClients) {
                            Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                            $results += [PSCustomObject]@{ Label=$sc.Label; Status="FAILED"; Time=$sc.Time; Reason="scenario incomplete (client failed)" }
                        }
                        continue
                    }

                    $actualByClient = @{}
                    foreach ($sc in $successfulClients) {
                        $actualByClient[$sc.Name] = $sc.NormalizedStdout
                    }

                    $matchedIndex = Find-MatchingAlternativeIndex $actualByClient $scenarioAlternatives
                    if ($matchedIndex -ge 0) {
                        foreach ($sc in $successfulClients) {
                            Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "PASSED" -ForegroundColor Green
                            $results += [PSCustomObject]@{ Label=$sc.Label; Status="PASSED"; Time=$sc.Time; Reason="alternative #$($matchedIndex + 1)" }
                        }
                    } else {
                        foreach ($sc in $successfulClients) {
                            Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                            $results += [PSCustomObject]@{ Label=$sc.Label; Status="FAILED"; Time=$sc.Time; Reason="no alternative matched" }
                        }
                    }
                    continue
                }

                foreach ($sc in $successfulClients) {
                    if (Test-Path $sc.ExpFile) {
                        $expText = Normalize-Output (Get-Content $sc.ExpFile -Raw)

                        if ($sc.NormalizedStdout -eq $expText) {
                            Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "PASSED" -ForegroundColor Green
                            $results += [PSCustomObject]@{ Label=$sc.Label; Status="PASSED"; Time=$sc.Time; Reason="" }
                        } else {
                            Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "FAILED" -ForegroundColor Red
                            Show-Diff $sc.OutFile $sc.ExpFile
                            $results += [PSCustomObject]@{ Label=$sc.Label; Status="FAILED"; Time=$sc.Time; Reason="output mismatch" }
                        }
                    } else {
                        Write-Host ("{0,-35}  " -f $sc.Prefix) -NoNewline; Write-Host "NO EXPECTED FILE" -ForegroundColor Yellow
                    }
                }
            }
        }
    }

    # Summary Generation
    $totalElapsed = [Math]::Round(((Get-Date) - $startTime).TotalSeconds, 1)
    $passed = @($results | Where-Object Status -eq 'PASSED').Count
    $failed = @($results | Where-Object Status -eq 'FAILED').Count

    Write-Section "SUMMARY"
    foreach ($r in $results) {
        $color = if ($r.Status -eq "PASSED") { 'Green' } else { 'Red' }
        Write-Host ("    {0,-45} " -f $r.Label) -NoNewline; Write-Host ("{0,-6}" -f $r.Status) -NoNewline -ForegroundColor $color
        Write-Host ("  {0,-8} {1}" -f $r.Time, $r.Reason) -ForegroundColor DarkGray
    }
    Write-Host ("  " + ("-" * ($UI_WIDTH - 2))) -ForegroundColor DarkGray
    Write-Host "  Passed  : $passed" -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'White' })
    Write-Host "  Failed  : $failed" -ForegroundColor $(if ($failed -eq 0) { 'DarkGray' } else { 'Red' })
    Write-Host "  Duration: ${totalElapsed}s" -ForegroundColor DarkGray
    Write-Host "  Output dir: $TEST_OUTPUTS" -ForegroundColor DarkGray

} finally {
    Write-Host "`n  Stopping infrastructure..." -NoNewline -ForegroundColor DarkGray
    foreach ($p in $activeProcs) { Stop-ProcessTree $p }
    Write-Host " done.`n" -ForegroundColor DarkGray
}