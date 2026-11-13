$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$backendPidFile=Join-Path $PSScriptRoot '.local/backend.pid'
if(Test-Path -LiteralPath $backendPidFile){
    $taskBackendPid=0
    if([int]::TryParse([IO.File]::ReadAllText($backendPidFile).Trim(),[ref]$taskBackendPid)){
        if(Get-Process -Id $taskBackendPid -ErrorAction SilentlyContinue){throw 'Đóng backend trước khi tạo bộ thẻ demo để hai tiến trình không cùng ghi file thẻ.'}
    }
}
$jar=Join-Path $PSScriptRoot 'backend/target/ttm-backend-1.0-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $jar)){throw 'Chạy 00-Build.cmd trước.'}
& 'C:\Program Files\Java\jdk-25\bin\java.exe' '--add-opens=java.base/java.lang=ALL-UNNAMED' '--add-opens=java.base/java.math=ALL-UNNAMED' '--add-opens=java.base/java.util=ALL-UNNAMED' "-Dttm.config=$PSScriptRoot/.local/server.properties" -jar $jar --create-demo-cards
if($LASTEXITCODE -ne 0){throw 'Chưa tạo xong bộ thẻ demo V3. Xem thông báo phía trên.'}
