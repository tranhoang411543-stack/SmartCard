$ErrorActionPreference='Stop'
& "$PSScriptRoot\scripts\Prepare.ps1"
& "$PSScriptRoot\scripts\WebhookTunnel.ps1" -Action Configure
$jar=Join-Path $PSScriptRoot 'backend\target\ttm-backend-1.0-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $jar)){throw 'Chưa có bản build. Chạy 00-Build.cmd trước.'}
$privateRoot=Join-Path $PSScriptRoot '.local'
$health=$null
try{$health=Invoke-RestMethod 'http://127.0.0.1:18080/api/health' -TimeoutSec 2}catch{}
if($health -and $health.status -eq 'UP'){
    if($health.application -ne 'ttm-cantin'){throw 'Cổng 18080 đang thuộc ứng dụng khác.'}
    $pidFile=Join-Path $privateRoot 'backend.pid'
    if(-not(Test-Path -LiteralPath $pidFile)){throw 'Đóng backend cũ trước khi chuyển sang bản hiện hành; chưa xác định được tiến trình thuộc project.'}
    $taskBackendPid=[int]([IO.File]::ReadAllText($pidFile).Trim())
    $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskBackendPid"
    if(-not $taskProcess -or -not $taskProcess.ExecutablePath -or $taskProcess.ExecutablePath -ne 'C:\Program Files\Java\jdk-25\bin\java.exe' -or -not $taskProcess.CommandLine -or $taskProcess.CommandLine.IndexOf($jar,[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'PID backend không khớp jar thuộc project. Đóng backend cũ bằng cửa sổ đang chạy.'}
    $serverFile=Join-Path $privateRoot 'server.properties'
    $unchanged=(Get-Item -LiteralPath $jar).LastWriteTimeUtc -le $taskProcess.CreationDate.ToUniversalTime() -and (Get-Item -LiteralPath $serverFile).LastWriteTimeUtc -le $taskProcess.CreationDate.ToUniversalTime()
    if($health.protocol -eq '4' -and $health.bankWebhook -eq 'sepay-v1' -and $health.bankProvider -eq 'sepay' -and $unchanged){
        Write-Output 'Backend SePay đã chạy tại http://127.0.0.1:18080'
        & "$PSScriptRoot\scripts\WebhookTunnel.ps1" -Action Start
        exit 0
    }
    Stop-Process -Id $taskBackendPid
    Wait-Process -Id $taskBackendPid -Timeout 10 -ErrorAction SilentlyContinue
}
$jvmArgs=@('--add-opens=java.base/java.lang=ALL-UNNAMED','--add-opens=java.base/java.math=ALL-UNNAMED','--add-opens=java.base/java.util=ALL-UNNAMED',"-Dttm.config=$privateRoot/server.properties",'-jar',"`"$jar`"")
$process=Start-Process -FilePath 'C:\Program Files\Java\jdk-25\bin\java.exe' -ArgumentList $jvmArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $privateRoot 'backend.log') -RedirectStandardError (Join-Path $privateRoot 'backend-error.log') -PassThru
[IO.File]::WriteAllText((Join-Path $privateRoot 'backend.pid'),[string]$process.Id)
for($i=0;$i -lt 60;$i++){
    Start-Sleep -Milliseconds 250
    try{$health=Invoke-RestMethod 'http://127.0.0.1:18080/api/health' -TimeoutSec 1}catch{$health=$null}
    if($health -and $health.status -eq 'UP' -and $health.application -eq 'ttm-cantin' -and $health.protocol -eq '4' -and $health.bankWebhook -eq 'sepay-v1'){
        Write-Output 'Backend sẵn sàng tại http://127.0.0.1:18080'
        & "$PSScriptRoot\scripts\WebhookTunnel.ps1" -Action Start
        exit 0
    }
    if($process.HasExited){break}
}
throw 'Backend chưa sẵn sàng. Xem .local/backend.log và .local/backend-error.log.'
