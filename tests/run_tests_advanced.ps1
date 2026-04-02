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
    [switch]$SkipBuild,
    [switch]$C2Only
)

if ($C2Only) {
    $NoSimple = $true
}

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
    function Invoke-ClientSync([string]$inputContent, [string]$nodes, [string]$label = "b2") {
        $outFile = Join-Path $TEST_OUTPUTS "${label}-client-out-${RUN_SUFFIX}-$PID-$(Get-Random).txt"
        $errFile = Join-Path $TEST_OUTPUTS "${label}-client-err-${RUN_SUFFIX}-$PID-$(Get-Random).txt"
        
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $MVN_EXE
        $allArgs = "--quiet -f `"$MVN_ROOT_POM`" -pl client exec:java -Dexec.args=`"$nodes`""
        if ($debugArgs) { $allArgs += " $debugArgs" }
        $psi.Arguments = $allArgs
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $proc = [System.Diagnostics.Process]::Start($psi)
        $proc.StandardInput.Write($inputContent)
        $proc.StandardInput.Close()
        
        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        $proc.WaitForExit()

        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result

        [System.IO.File]::WriteAllText($outFile, $stdout)
        [System.IO.File]::WriteAllText($errFile, $stderr)
        return $outFile
    }

    if (-not $C2Only) {

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

    # ==========================================
    # 3. RUN B2 INFRASTRUCTURE TESTS
    # ==========================================
    Write-Host "`n  Stopping shared infrastructure for B2 tests..." -ForegroundColor DarkGray
    foreach ($p in $activeProcs) { Stop-ProcessTree $p }
    $activeProcs = @()
    Start-Sleep -Seconds 1

    $B2_SEQUENCER_PORT = 3001
    $B2_NODE_PORT_1 = 2011
    $B2_NODE_PORT_2 = 2012
    $B2_NODE_PORT_3 = 2013
    $B2_FO_NODE_PORT_1 = 2021
    $B2_FO_NODE_PORT_2 = 2022

    function Extract-B2Balances([string]$outFile) {
        $content = Get-Content $outFile -ErrorAction SilentlyContinue
        if ($content) {
            $balances = @($content | Where-Object { $_ -match "^balance:" })
            return ($balances -join "`n").Trim()
        }
        return ""
    }

    Write-Section "B2 Infrastructure Tests"

    #-----------------------------------------
    # B2-1: Late-join node sync
    #-----------------------------------------
    $t0_b2 = Get-Date

    $b2SeqInfo = Start-MvnBackground "sequencer-b2" "$B2_SEQUENCER_PORT" $debugArgs
    $b2_seq_pid = $b2SeqInfo.Process
    $activeProcs += $b2_seq_pid
    Assert-ServiceStarted "sequencer-b2" "localhost" $B2_SEQUENCER_PORT $b2SeqInfo

    $b2Node1Info = Start-MvnBackground "node-b2-$B2_NODE_PORT_1" "$B2_NODE_PORT_1 Org1 localhost:$B2_SEQUENCER_PORT" $debugArgs
    $b2_node1_pid = $b2Node1Info.Process
    $activeProcs += $b2_node1_pid
    Assert-ServiceStarted "node-b2-$B2_NODE_PORT_1" "localhost" $B2_NODE_PORT_1 $b2Node1Info

    $b2Node2Info = Start-MvnBackground "node-b2-$B2_NODE_PORT_2" "$B2_NODE_PORT_2 Org2 localhost:$B2_SEQUENCER_PORT" $debugArgs
    $b2_node2_pid = $b2Node2Info.Process
    $activeProcs += $b2_node2_pid
    Assert-ServiceStarted "node-b2-$B2_NODE_PORT_2" "localhost" $B2_NODE_PORT_2 $b2Node2Info

    $b2SeedInput = @"
C Alice cA 0 0
C Bob cB 0 0
T BC bc cA 200 0 0
T BC bc cB 150 1 0
T Alice cA cB 50 0 0
X
"@
    Invoke-ClientSync $b2SeedInput "localhost:${B2_NODE_PORT_1}:Org1 localhost:${B2_NODE_PORT_2}:Org2" | Out-Null

    Write-Host "    Starting late-join node on port $B2_NODE_PORT_3..." -ForegroundColor DarkGray
    $b2Node3Info = Start-MvnBackground "node-b2-$B2_NODE_PORT_3" "$B2_NODE_PORT_3 Org3 localhost:$B2_SEQUENCER_PORT" $debugArgs
    $b2_node3_pid = $b2Node3Info.Process
    $activeProcs += $b2_node3_pid
    Assert-ServiceStarted "node-b2-$B2_NODE_PORT_3" "localhost" $B2_NODE_PORT_3 $b2Node3Info

    $b2CheckInput = @"
S cA 0 0
S cB 0 0
S bc 0 0
X
"@
    $b2OutN1 = Invoke-ClientSync $b2CheckInput "localhost:${B2_NODE_PORT_1}:Org1"
    $b2OutN3 = Invoke-ClientSync $b2CheckInput "localhost:${B2_NODE_PORT_3}:Org3"

    $b2BalN1 = Extract-B2Balances $b2OutN1
    $b2BalN3 = Extract-B2Balances $b2OutN3
    $elapsedB21 = "$([Math]::Round(((Get-Date) - $t0_b2).TotalSeconds, 2))s"

    $b2BalN1Norm = ($b2BalN1 -replace "`r", "").Trim()
    $b2BalN3Norm = ($b2BalN3 -replace "`r", "").Trim()

    if ($b2BalN1Norm -eq $b2BalN3Norm -and -not [string]::IsNullOrEmpty($b2BalN1Norm)) {
        $results += [PSCustomObject]@{ Label="B2/late-join-sync"; Status="PASSED"; Time=$elapsedB21; Reason="balances match node 1" }
    } else {
        $results += [PSCustomObject]@{ Label="B2/late-join-sync"; Status="FAILED"; Time=$elapsedB21; Reason="node1=[$($b2BalN1Norm -replace "`n", "|")] node3=[$($b2BalN3Norm -replace "`n", "|")]" }
    }

    $b2ExpectedSync = "balance: 150`nbalance: 200`nbalance: 650"
    if ($b2BalN3Norm -eq $b2ExpectedSync) {
        $results += [PSCustomObject]@{ Label="B2/late-join-balances"; Status="PASSED"; Time=$elapsedB21; Reason="cA=150 cB=200 bc=650" }
    } else {
        $results += [PSCustomObject]@{ Label="B2/late-join-balances"; Status="FAILED"; Time=$elapsedB21; Reason="expected [$($b2ExpectedSync -replace "`n", "|")] got [$($b2BalN3Norm -replace "`n", "|")]" }
    }

    #-----------------------------------------
    # B2-2: Client failover (dead node → retry)
    #-----------------------------------------
    foreach ($p in $activeProcs) { Stop-ProcessTree $p }
    $activeProcs = @()
    Start-Sleep -Seconds 1

    $b2SeqInfo = Start-MvnBackground "sequencer-b2" "$B2_SEQUENCER_PORT" $debugArgs
    $b2_seq_pid = $b2SeqInfo.Process
    $activeProcs += $b2_seq_pid
    Assert-ServiceStarted "sequencer-b2" "localhost" $B2_SEQUENCER_PORT $b2SeqInfo

    $b2Fo1Info = Start-MvnBackground "node-b2-$B2_FO_NODE_PORT_1" "$B2_FO_NODE_PORT_1 Org1 localhost:$B2_SEQUENCER_PORT" $debugArgs
    $b2_fo1_pid = $b2Fo1Info.Process
    $activeProcs += $b2_fo1_pid
    Assert-ServiceStarted "node-b2-$B2_FO_NODE_PORT_1" "localhost" $B2_FO_NODE_PORT_1 $b2Fo1Info

    $b2Fo2Info = Start-MvnBackground "node-b2-$B2_FO_NODE_PORT_2" "$B2_FO_NODE_PORT_2 Org2 localhost:$B2_SEQUENCER_PORT" $debugArgs
    $b2_fo2_pid = $b2Fo2Info.Process
    $activeProcs += $b2_fo2_pid
    Assert-ServiceStarted "node-b2-$B2_FO_NODE_PORT_2" "localhost" $B2_FO_NODE_PORT_2 $b2Fo2Info

    $b2FoNodes = "localhost:${B2_FO_NODE_PORT_1}:Org1 localhost:${B2_FO_NODE_PORT_2}:Org2"

    $b2InitInput = @"
C Alice cA 0 0
T BC bc cA 500 0 0
X
"@
    Invoke-ClientSync $b2InitInput $b2FoNodes | Out-Null

    Write-Host "    Killing fo-node1 (port $B2_FO_NODE_PORT_1)..." -ForegroundColor DarkGray
    Stop-ProcessTree $b2_fo1_pid
    $activeProcs = @($b2_seq_pid, $b2_fo2_pid)
    Start-Sleep -Seconds 1

    $t0_fo = Get-Date
    $b2FailoverInput = @"
T Alice cA bc 100 0 0
S cA 0 0
S bc 0 0
X
"@
    $b2FoOut = Invoke-ClientSync $b2FailoverInput $b2FoNodes
    $elapsedFo = "$([Math]::Round(((Get-Date) - $t0_fo).TotalSeconds, 2))s"

    $b2FoBalances = Extract-B2Balances $b2FoOut
    $b2FoBalancesNorm = ($b2FoBalances -replace "`r", "").Trim()
    $b2ExpectedFo = "balance: 400`nbalance: 600"

    if ($b2FoBalancesNorm -match "balance: 400") {
        $results += [PSCustomObject]@{ Label="B2/failover-transfer"; Status="PASSED"; Time=$elapsedFo; Reason="transfer succeeded despite dead node" }
    } else {
        $outContent = Get-Content $b2FoOut -Raw -ErrorAction SilentlyContinue | Out-String
        $results += [PSCustomObject]@{ Label="B2/failover-transfer"; Status="FAILED"; Time=$elapsedFo; Reason="output: $($outContent -replace "`n", "|")" }
    }

    if ($b2FoBalancesNorm -eq $b2ExpectedFo) {
        $results += [PSCustomObject]@{ Label="B2/failover-balances"; Status="PASSED"; Time=$elapsedFo; Reason="cA=400 bc=600" }
    } else {
        $results += [PSCustomObject]@{ Label="B2/failover-balances"; Status="FAILED"; Time=$elapsedFo; Reason="expected [$($b2ExpectedFo -replace "`n", "|")] got [$($b2FoBalancesNorm -replace "`n", "|")]" }
    }


    #-----------------------------------------
    # B2-3: Deduplication / cross-node consistency
    #-----------------------------------------
    $t0_dedup = Get-Date
    $b2DedupInput = @"
C Carol cC 1 0
T BC bc cC 300 0 0
T Carol cC bc 300 1 0
E Carol cC 1 0
S bc 0 0
S bc 1 0
X
"@
    $b2DedupOut = Invoke-ClientSync $b2DedupInput $b2FoNodes
    $elapsedDedup = "$([Math]::Round(((Get-Date) - $t0_dedup).TotalSeconds, 2))s"

    $b2DedupBal = Extract-B2Balances $b2DedupOut
    $b2DedupBalNorm = ($b2DedupBal -replace "`r", "").Trim()
    $b2ExpectedDedup = "balance: 600`nbalance: 600"

    if ($b2DedupBalNorm -eq $b2ExpectedDedup) {
        $results += [PSCustomObject]@{ Label="B2/dedup-consistency"; Status="PASSED"; Time=$elapsedDedup; Reason="state consistent after create/transfer/delete cycle" }
    } else {
        $results += [PSCustomObject]@{ Label="B2/dedup-consistency"; Status="FAILED"; Time=$elapsedDedup; Reason="expected [$($b2ExpectedDedup -replace "`n", "|")] got [$($b2DedupBalNorm -replace "`n", "|")]" }
    }

    }

    # ==========================================
    # C2: Digital Signature Validation
    # ==========================================
    Write-Section "C2 Infrastructure Tests"

    $t0_c2 = Get-Date

    foreach ($p in $activeProcs) { Stop-ProcessTree $p }
    $activeProcs = @()
    Start-Sleep -Seconds 1

    # Temporarily overwrite Alice's public key with Bob's to simulate a signature mismatch
    $c2AlicePubSrc = Join-Path $MVN_ROOT_DIR "node/src/main/resources/Alice.pub"
    $c2BobPubSrc = Join-Path $MVN_ROOT_DIR "node/src/main/resources/Bob.pub"
    Copy-Item $c2AlicePubSrc "$c2AlicePubSrc.bak" -Force
    Copy-Item $c2BobPubSrc $c2AlicePubSrc -Force

    $c2AlicePubTarget = Join-Path $MVN_ROOT_DIR "node/target/classes/Alice.pub"
    $c2BobPubTarget = Join-Path $MVN_ROOT_DIR "node/target/classes/Bob.pub"
    if (Test-Path $c2AlicePubTarget) {
        Copy-Item $c2AlicePubTarget "$c2AlicePubTarget.bak" -Force
        Copy-Item $c2BobPubTarget $c2AlicePubTarget -Force
    }

    $c2SeqInfo = Start-MvnBackground "sequencer-c2" "3001" $debugArgs
    $c2_seq_pid = $c2SeqInfo.Process
    $activeProcs += $c2_seq_pid
    Assert-ServiceStarted "sequencer-c2" "localhost" 3001 $c2SeqInfo

    $c2Node1Info = Start-MvnBackground "node-c2-2011" "2011 Org1 localhost:3001" $debugArgs
    $c2_node1_pid = $c2Node1Info.Process
    $c2_node1_dbg_file = $c2Node1Info.DbgFile
    $activeProcs += $c2_node1_pid
    Assert-ServiceStarted "node-c2-2011" "localhost" 2011 $c2Node1Info

    $c2InitInput = @"
C Alice cA 0 0
X
"@
    $c2Out = Invoke-ClientSync $c2InitInput "localhost:2011:Org1" "c2"
    $elapsedC2 = "$([Math]::Round(((Get-Date) - $t0_c2).TotalSeconds, 2))s"

    $c2Node1DbgContent = Get-Content $c2_node1_dbg_file -Raw -ErrorAction SilentlyContinue
    if ($c2Node1DbgContent -match "(?i)\[Security\] Invalid signature") {
        $results += [PSCustomObject]@{ Label="C2/client-signature-validation"; Status="PASSED"; Time=$elapsedC2; Reason="invalid client signature correctly rejected" }
    } else {
        $results += [PSCustomObject]@{ Label="C2/client-signature-validation"; Status="FAILED"; Time=$elapsedC2; Reason="invalid client signature was not rejected" }
    }

    # Restore public key
    Move-Item "$c2AlicePubSrc.bak" $c2AlicePubSrc -Force
    if (Test-Path "$c2AlicePubTarget.bak") {
        Move-Item "$c2AlicePubTarget.bak" $c2AlicePubTarget -Force
    }

    #-----------------------------------------
    # C2: Sequencer-to-Node Signature Validation
    #-----------------------------------------
    $t0_c2_seq = Get-Date

    foreach ($p in $activeProcs) { Stop-ProcessTree $p }
    $activeProcs = @()
    Start-Sleep -Seconds 1

    # Temporarily overwrite Sequencer's public key with Alice's to simulate a signature mismatch
    $c2SeqPubSrc = Join-Path $MVN_ROOT_DIR "node/src/main/resources/seq/Seq.pub"
    $c2AlicePubSrc = Join-Path $MVN_ROOT_DIR "node/src/main/resources/Alice.pub"
    Copy-Item $c2SeqPubSrc "$c2SeqPubSrc.bak" -Force
    Copy-Item $c2AlicePubSrc $c2SeqPubSrc -Force

    $c2SeqPubTarget = Join-Path $MVN_ROOT_DIR "node/target/classes/seq/Seq.pub"
    $c2AlicePubTarget = Join-Path $MVN_ROOT_DIR "node/target/classes/Alice.pub"
    if (Test-Path $c2SeqPubTarget) {
        Copy-Item $c2SeqPubTarget "$c2SeqPubTarget.bak" -Force
        Copy-Item $c2AlicePubTarget $c2SeqPubTarget -Force
    }

    $c2SeqPort = 3002
    $c2SeqNodePort = 2014

    $c2SeqSeqInfo = Start-MvnBackground "sequencer-c2-seq" "$c2SeqPort" $debugArgs
    $c2_seq_pid_seq = $c2SeqSeqInfo.Process
    $activeProcs += $c2_seq_pid_seq
    Assert-ServiceStarted "sequencer-c2-seq" "localhost" $c2SeqPort $c2SeqSeqInfo

    $c2Node1SeqInfo = Start-MvnBackground "node-c2-seq-$c2SeqNodePort" "$c2SeqNodePort Org1 localhost:$c2SeqPort" $debugArgs
    $c2_node1_pid_seq = $c2Node1SeqInfo.Process
    $c2_node1_dbg_seq = $c2Node1SeqInfo.DbgFile
    $activeProcs += $c2_node1_pid_seq
    Assert-ServiceStarted "node-c2-seq-$c2SeqNodePort" "localhost" $c2SeqNodePort $c2Node1SeqInfo

    $c2SeqInputFile = Join-Path $TEST_OUTPUTS "c2-seq-input-${RUN_SUFFIX}.txt"
    @"
c Alice cA 0 0
c Alice cB 0 0
c Alice cc 0 0
c Alice cD 0 0
X
"@ | Set-Content $c2SeqInputFile

    $c2SeqInputContent = Get-Content $c2SeqInputFile -Raw
    Invoke-ClientSync $c2SeqInputContent "localhost:${c2SeqNodePort}:Org1" "c2" | Out-Null
    $elapsedC2Seq = "$([Math]::Round(((Get-Date) - $t0_c2_seq).TotalSeconds, 2))s"

    $c2NodeErrContent = Get-Content $c2_node1_dbg_seq -Raw -ErrorAction SilentlyContinue
    if ($c2NodeErrContent -match "(?i)\[Security\] Invalid signature") {
        $results += [PSCustomObject]@{ Label="C2/seq-signature-validation"; Status="PASSED"; Time=$elapsedC2Seq; Reason="invalid sequencer signature correctly rejected" }
    } else {
        $results += [PSCustomObject]@{ Label="C2/seq-signature-validation"; Status="FAILED"; Time=$elapsedC2Seq; Reason="invalid sequencer signature was not rejected" }
    }

    # Restore Sequencer public key
    Move-Item "$c2SeqPubSrc.bak" $c2SeqPubSrc -Force
    if (Test-Path "$c2SeqPubTarget.bak") {
        Move-Item "$c2SeqPubTarget.bak" $c2SeqPubTarget -Force
    }
} # end if (-not $C2Only)

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