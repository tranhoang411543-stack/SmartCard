$ErrorActionPreference = 'Stop'

function Read-SetupSetting([string]$key, [string]$envName) {
    $value = [Environment]::GetEnvironmentVariable($envName)
    if ($null -eq $value) {
        $pattern = '^\s*' + [regex]::Escape($key) + '\s*=\s*(.*)$'
        foreach ($line in [IO.File]::ReadLines($serverFile)) {
            if ($line -cmatch $pattern) { $value = $Matches[1] }
        }
    }
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Thiếu cấu hình $key. Kiểm tra .local/server.properties."
    }
    return $value
}

function Invoke-SetupRequest([string]$method, [string]$path, [hashtable]$payload) {
    $request = @{
        Method = $method
        Uri = "$apiBase$path"
        TimeoutSec = 30
        MaximumRedirection = 0
        UseBasicParsing = $true
        ErrorAction = 'Stop'
    }
    if ($path -ne '/health') { $request.Headers = $headers }
    if ($null -ne $payload) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
    }

    try {
        Invoke-RestMethod @request
    } catch {
        $response = $_.Exception.Response
        $detail = $_.ErrorDetails.Message
        if (-not $detail -and $response -is [Net.HttpWebResponse]) {
            $stream = $response.GetResponseStream()
            if ($null -ne $stream) {
                $reader = [IO.StreamReader]::new($stream)
                try { $detail = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
        }
        $problem = if ($detail) { $detail | ConvertFrom-Json -ErrorAction SilentlyContinue }
        if ($problem.code -and $problem.message) {
            $failure = [InvalidOperationException]::new($problem.message)
            $failure.Data['SetupCode'] = $problem.code
            throw $failure
        }
        if ($response -and [int]$response.StatusCode -in 404, 405) {
            throw 'Backend chưa hỗ trợ luồng OTP. Hãy chạy 09-Cap-nhat-backend.cmd.'
        }
        throw 'Không nhận được phản hồi hợp lệ từ backend. Kiểm tra backend đã chạy và thử lại.'
    }
}

function Read-PasswordText([Security.SecureString]$secret) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$password = $null
try {
    $serverFile = Join-Path $PSScriptRoot '.local/server.properties'
    if (-not (Test-Path -LiteralPath $serverFile -PathType Leaf)) {
        throw 'Chưa có .local/server.properties. Hãy chạy 01-Chuan-bi.ps1.'
    }
    $port = 18080
    if ($env:TTM_PORT -and (-not [int]::TryParse($env:TTM_PORT, [ref]$port) -or
            $port -lt 1 -or $port -gt 65535)) {
        throw 'Cấu hình TTM_PORT không hợp lệ.'
    }
    $apiBase = "http://127.0.0.1:$port/api"
    $health = Invoke-SetupRequest Get '/health'
    if ($health.application -ne 'ttm-cantin' -or $health.status -ne 'UP') {
        throw 'Không tìm thấy backend TTM Căn tin tại cổng đã cấu hình.'
    }
    $headers = @{ 'X-Device-Key' = (Read-SetupSetting 'device.key' 'TTM_DEVICE_KEY') }
    $status = Invoke-SetupRequest Get '/setup/status'
    if ($status.needed -eq $false) {
        Write-Host 'Hệ thống đã có tài khoản admin. Bạn có thể đăng nhập ở tab Admin tại Quầy quản lý.'
        return
    }
    if ($status.needed -ne $true) { throw 'Backend chưa trả về trạng thái khởi tạo hợp lệ.' }
    $token = Read-SetupSetting 'setup.token' 'TTM_SETUP_TOKEN'

    while ($true) {
        $username = (Read-Host 'Nhập Username').Trim()
        if ($username -cmatch '^[A-Za-z0-9_.-]{3,60}$') { break }
        Write-Host 'Username cần 3 đến 60 ký tự, gồm chữ cái, số, dấu chấm, gạch dưới hoặc gạch ngang.'
    }
    while ($true) {
        $password = Read-Host 'Nhập Password' -AsSecureString
        $confirmation = Read-Host 'Xác nhận Password' -AsSecureString
        try {
            if ($password.Length -lt 12 -or $password.Length -gt 20) {
                Write-Host 'Mật khẩu mới phải chứa từ 12 đến 20 ký tự'
            } elseif ((Read-PasswordText $password) -cne (Read-PasswordText $confirmation)) {
                Write-Host 'Hai mật khẩu chưa khớp. Vui lòng nhập lại.'
            } else { break }
        } finally { $confirmation.Dispose() }
        $password.Dispose()
        $password = $null
    }
    while ($true) {
        $email = (Read-Host 'Nhập Email').Trim().ToLowerInvariant()
        if ($email.Length -le 180 -and $email -match '^[^\s@]+@[^\s@]+\.[^\s@]+$') { break }
        Write-Host 'Địa chỉ email không hợp lệ.'
    }

    $identity = @{ token = $token; username = $username; email = $email }
    $null = Invoke-SetupRequest Post '/setup/otp' $identity
    while ($true) {
        $code = (Read-Host "Nhập mã OTP vừa được gửi đến $email").Trim()
        if ($code -cnotmatch '^[0-9]{8}$') {
            Write-Host 'Mã OTP cần có 8 chữ số.'
            continue
        }
        $submission = $identity.Clone()
        try {
            $submission.code = $code
            $submission.password = Read-PasswordText $password
            $null = Invoke-SetupRequest Post '/setup' $submission
            Write-Host 'Khởi tạo admin thành công!'
            break
        } catch {
            if ($_.Exception.Data['SetupCode'] -ne 'SETUP_OTP_WRONG') { throw }
            Write-Host $_.Exception.Message
        } finally { $submission.Clear() }
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    if ($password) { $password.Dispose() }
}
