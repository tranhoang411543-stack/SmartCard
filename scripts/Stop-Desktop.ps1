[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param([switch]$SkipUpdateLockCheck)

$ErrorActionPreference = 'Stop'

function Get-DesktopRole {
    param(
        [string]$ProcessName,
        [string]$CommandLine,
        [string]$JarPath
    )

    if ($ProcessName -notin @('java.exe', 'javaw.exe') -or
        [string]::IsNullOrWhiteSpace($CommandLine) -or
        [string]::IsNullOrWhiteSpace($JarPath)) {
        return $null
    }
    $jarPattern = '(?:^|\s)-jar\s+(?:"{0}"|{0})(?=\s|$)' -f [regex]::Escape($JarPath)
    if ($CommandLine -notmatch $jarPattern) { return $null }
    if ($CommandLine -match '(?:^|\s)"?--quay=thanhtoan"?(?=\s|$)') {
        return 'quầy thanh toán'
    }
    # TTM_CuoiKy.main chọn quầy quản lý khi không có --quay=thanhtoan.
    return 'quầy quản lý'
}

function Test-FileAvailable {
    param([string]$Path)
    if (-not [IO.File]::Exists($Path)) { return $true }
    $handle = $null
    try {
        $handle = [IO.File]::Open($Path, [IO.FileMode]::Open,
            [IO.FileAccess]::Read, [IO.FileShare]::None)
        return $true
    } catch [IO.IOException] {
        $code = $_.Exception.GetBaseException().HResult -band 0xffff
        if ($code -in 32, 33) { return $false }
        throw
    } finally {
        if ($handle) { $handle.Dispose() }
    }
}

# Chỉ nạp hàm khi được dot-source để kiểm tra, không tự dừng tiến trình.
if ($MyInvocation.InvocationName -eq '.') { return }

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar = Join-Path $projectRoot 'target\TTM_CuoiKy-1.0-SNAPSHOT.jar'
$updateLock = Join-Path $projectRoot 'target\desktop-update.lock'
$failed = $false

try {
    $javaProcesses = @(& {
        # Truy vấn chỉ đọc; tránh thông báo WhatIf khi PowerShell nạp module CIM.
        $WhatIfPreference = $false
        Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop
    })
    $candidates = @(
        foreach ($item in $javaProcesses) {
            if (-not $item.ExecutablePath -or -not $item.CreationDate) { continue }
            $role = Get-DesktopRole -ProcessName $item.Name -CommandLine $item.CommandLine -JarPath $jar
            if ($role) { [pscustomobject]@{ Process = $item; Role = $role } }
        }
    )

    if ($candidates.Count -eq 0) {
        Write-Host 'Không tìm thấy tiến trình hai quầy của dự án đang chạy.'
    }

    foreach ($candidate in $candidates) {
        $item = $candidate.Process
        $process = $null
        try {
            $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
            if (-not $process) {
                Write-Host "Tiến trình PID $($item.ProcessId) đã tự dừng."
                continue
            }
            # Giữ handle và đối chiếu thời điểm tạo để tránh PID bị Windows cấp lại.
            [void]$process.Handle
            $difference = [Math]::Abs(($process.StartTime.ToUniversalTime() -
                $item.CreationDate.ToUniversalTime()).TotalMilliseconds)
            if ($difference -gt 1) {
                throw 'PID đã thay đổi từ lúc kiểm tra; hãy chạy lại lệnh.'
            }
            if (-not $PSCmdlet.ShouldProcess("$($candidate.Role), PID $($process.Id)", 'Đóng quầy')) {
                continue
            }

            $process.Refresh()
            if ($process.HasExited) {
                Write-Host "Tiến trình PID $($item.ProcessId) đã tự dừng."
                continue
            }
            if ($process.MainWindowHandle -ne [IntPtr]::Zero) {
                Write-Host "Đang yêu cầu đóng $($candidate.Role) (PID $($process.Id))..."
                [void]$process.CloseMainWindow()
                if ($process.WaitForExit(12000)) {
                    Write-Host "Đã đóng $($candidate.Role)."
                    continue
                }
                $process.Refresh()
                if (-not $process.HasExited -and $process.MainWindowHandle -ne [IntPtr]::Zero) {
                    throw 'Cửa sổ vẫn đang mở. Hãy xử lý hộp thoại hoặc lưu dữ liệu rồi chạy lại.'
                }
            }
            if (-not $process.HasExited) {
                Write-Host "Đang dừng tiến trình nền của $($candidate.Role) (PID $($process.Id))..."
                Stop-Process -InputObject $process -ErrorAction Stop
                if (-not $process.WaitForExit(5000)) {
                    throw 'Tiến trình chưa dừng. Hãy chạy lại lệnh.'
                }
            }
            Write-Host "Đã đóng $($candidate.Role)."
        } catch {
            $failed = $true
            [Console]::Error.WriteLine("Không đóng được $($candidate.Role) (PID $($item.ProcessId)): $($_.Exception.Message)")
        } finally {
            if ($process) { $process.Dispose() }
        }
    }

    if ($WhatIfPreference) {
        Write-Host 'Chế độ xem trước: chưa đóng tiến trình nào.'
        return
    }
    if (-not $SkipUpdateLockCheck -and -not (Test-FileAvailable $updateLock)) {
        $failed = $true
        [Console]::Error.WriteLine('Đang có lượt cập nhật desktop khác. Hãy đợi lượt đó kết thúc.')
    }
    if (-not (Test-FileAvailable $jar)) {
        $failed = $true
        [Console]::Error.WriteLine('Tệp JAR vẫn đang bị tiến trình khác giữ. Lệnh không tự đóng tiến trình chưa xác minh được.')
    }
    if ($failed) { exit 1 }
    if ($SkipUpdateLockCheck) {
        Write-Host 'Hai quầy đã được đóng; tệp desktop đã sẵn sàng để cập nhật.'
    } else {
        Write-Host 'Tệp desktop đã sẵn sàng. Bạn có thể chạy lại 08-Cap-nhat-desktop.cmd.'
    }
} catch {
    [Console]::Error.WriteLine("Không kiểm tra được tiến trình hoặc tệp desktop: $($_.Exception.Message)")
    exit 1
}
