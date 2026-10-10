# Start/stop the local FastAPI/uvicorn dev server without blocking the caller.
# Usage:
#   .\backend\scripts\dev-server.ps1 start
#   .\backend\scripts\dev-server.ps1 stop
#   .\backend\scripts\dev-server.ps1 status
#
# Optional start flags:
#   -HostAddress 0.0.0.0   # LAN bind (default 127.0.0.1)
#   -Port 8000
#   -Reload                # uvicorn --reload (omit for a quieter, faster process)

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("start", "stop", "status")]
    [string]$Action,

    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8000,
    [switch]$Reload
)

$ErrorActionPreference = "Stop"
$BackendDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$PidFile = Join-Path $BackendDir ".dev-server.pid"
$OutLog = Join-Path $BackendDir ".dev-server.out.log"
$ErrLog = Join-Path $BackendDir ".dev-server.err.log"

function Resolve-Python {
    $candidates = @(
        (Join-Path $BackendDir ".venv\bin\python.exe"),
        (Join-Path $BackendDir ".venv\Scripts\python.exe"),
        (Join-Path $BackendDir ".venv-ss\Scripts\python.exe")
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) {
            return (Resolve-Path $path).Path
        }
    }
    throw "No backend Python found under backend/.venv or backend/.venv-ss. Create the venv first."
}

function Get-ListeningPids {
    param([int]$TargetPort)
    Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
}

function Get-TrackedPid {
    if (-not (Test-Path $PidFile)) { return $null }
    $raw = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return [int]$raw.Trim()
}

function Test-ProcessAlive {
    param([int]$ProcessId)
    if ($ProcessId -le 0) { return $false }
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Write-Status {
    $tracked = Get-TrackedPid
    $listeners = @(Get-ListeningPids -TargetPort $Port)
    if ($tracked) {
        $alive = Test-ProcessAlive -ProcessId $tracked
        Write-Output "pid_file=$PidFile"
        Write-Output "tracked_pid=$tracked"
        Write-Output "tracked_alive=$alive"
    } else {
        Write-Output "tracked_pid="
        Write-Output "tracked_alive=false"
    }
    Write-Output "port=$Port"
    Write-Output "listening_pids=$($listeners -join ',')"
    if ($listeners.Count -gt 0) {
        Write-Output "state=listening"
    } else {
        Write-Output "state=stopped"
    }
}

switch ($Action) {
    "status" {
        Write-Status
    }
    "stop" {
        $tracked = Get-TrackedPid
        if ($tracked -and (Test-ProcessAlive -ProcessId $tracked)) {
            Stop-Process -Id $tracked -Force -ErrorAction SilentlyContinue
            Write-Output "stopped_pid=$tracked"
        } elseif ($tracked) {
            Write-Output "stopped_pid=$tracked (already gone)"
        }

        # Clean up any leftover listener on the target port (e.g. a previous
        # server started by hand that never wrote the pid file).
        foreach ($listenerPid in (Get-ListeningPids -TargetPort $Port)) {
            if ($listenerPid -eq $tracked) { continue }
            Stop-Process -Id $listenerPid -Force -ErrorAction SilentlyContinue
            Write-Output "stopped_port_pid=$listenerPid"
        }

        if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
        Write-Status
    }
    "start" {
        $existing = @(Get-ListeningPids -TargetPort $Port)
        if ($existing.Count -gt 0) {
            Write-Output "error=port_in_use"
            Write-Output "port=$Port"
            Write-Output "listening_pids=$($existing -join ',')"
            Write-Output "hint=Run: .\backend\scripts\dev-server.ps1 stop"
            exit 1
        }

        $python = Resolve-Python
        $uvicornArgs = @(
            "-m", "uvicorn",
            "class_table_backend.api.app:create_app",
            "--factory",
            "--host", $HostAddress,
            "--port", "$Port"
        )
        if ($Reload) {
            $uvicornArgs += "--reload"
        }

        # Fresh logs so each start is easy to inspect.
        New-Item -ItemType File -Path $OutLog -Force | Out-Null
        New-Item -ItemType File -Path $ErrLog -Force | Out-Null

        $process = Start-Process `
            -FilePath $python `
            -ArgumentList $uvicornArgs `
            -WorkingDirectory $BackendDir `
            -RedirectStandardOutput $OutLog `
            -RedirectStandardError $ErrLog `
            -WindowStyle Hidden `
            -PassThru

        $process.Id | Set-Content -Path $PidFile -Encoding ascii

        # Brief readiness wait: uvicorn should bind quickly. Do not block forever.
        $deadline = (Get-Date).AddSeconds(8)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 250
            if (-not (Test-ProcessAlive -ProcessId $process.Id)) {
                Write-Output "error=process_exited"
                Write-Output "pid=$($process.Id)"
                Write-Output "log_err=$ErrLog"
                Write-Output "log_out=$OutLog"
                if (Test-Path $ErrLog) {
                    Get-Content $ErrLog | Select-Object -Last 30 | ForEach-Object { Write-Output "err> $_" }
                }
                exit 1
            }
            if (@(Get-ListeningPids -TargetPort $Port).Count -gt 0) {
                $ready = $true
                break
            }
        }

        Write-Output "pid=$($process.Id)"
        Write-Output "host=$HostAddress"
        Write-Output "port=$Port"
        Write-Output "ready=$ready"
        Write-Output "log_out=$OutLog"
        Write-Output "log_err=$ErrLog"
        Write-Output "health=http://${HostAddress}:$Port/health"
        Write-Output "hint=Stop with: .\backend\scripts\dev-server.ps1 stop"
        if (-not $ready) {
            exit 1
        }
    }
}
