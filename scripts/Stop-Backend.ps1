[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar = Join-Path $projectRoot 'backend\target\ttm-backend-1.0-SNAPSHOT.jar'
$java = 'C:\Program Files\Java\jdk-25\bin\java.exe'
$pidFile = Join-Path $projectRoot '.local\backend.pid'

try {
    $jarPattern = '(?:^|\s)-jar\s+(?:"{0}"|{0})(?=\s|$)' -f [regex]::Escape($jar)
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
        Where-Object { $_.ExecutablePath -eq $java -and $_.CommandLine -match $jarPattern })

    if ($candidates.Count -eq 0) {
        $recordedPid = 0
        if ((Test-Path -LiteralPath $pidFile -PathType Leaf) -and
            [int]::TryParse([IO.File]::ReadAllText($pidFile).Trim(), [ref]$recordedPid) -and
            (Get-Process -Id $recordedPid -ErrorAction SilentlyContinue)) {
            throw 'Không xác minh được tiến trình trong file PID. Không dừng tiến trình này.'
        }
        Write-Host 'Không tìm thấy backend của project đang chạy.'
        return
    }
    if ($candidates.Count -ne 1) {
        throw 'Tìm thấy nhiều tiến trình backend cùng JAR. Hãy kiểm tra trước khi dừng.'
    }

    $owner = $candidates[0]
    $process = Get-Process -Id $owner.ProcessId -ErrorAction Stop
    try {
        [void]$process.Handle
        if (-not $owner.CreationDate -or
            [Math]::Abs(($process.StartTime.ToUniversalTime() - $owner.CreationDate.ToUniversalTime()).TotalMilliseconds) -gt 1) {
            throw 'Tiến trình đã thay đổi. Hãy chạy lại lệnh.'
        }
        if ($PSCmdlet.ShouldProcess("backend PID $($process.Id)", 'Dừng backend của project')) {
            Stop-Process -InputObject $process -ErrorAction Stop
            if (-not $process.WaitForExit(15000)) { throw 'Backend chưa dừng sau 15 giây.' }
            Write-Host 'Đã đóng backend của project.'
        }
    } finally {
        $process.Dispose()
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
