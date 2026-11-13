[CmdletBinding()]
param([ValidateSet('Configure','Start','Stop','Status')][string]$Action='Start')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\DatabaseTools.ps1"
$taskWebhookRoot=$ttmDatabaseRoot
$taskWebhookUrl='https://hardening-surname-entity.ngrok-free.dev'
$taskWebhookContainer='ttm-cuoiky-ngrok'
$taskWebhookPrivate=Join-Path $taskWebhookRoot '.local'
$taskWebhookConfig=Join-Path $taskWebhookPrivate 'server.properties'
$taskWebhookAgent=Join-Path $taskWebhookPrivate 'docker/ngrok.yml'

function Read-WebhookProperties {
    if(-not(Test-Path -LiteralPath $taskWebhookConfig)){throw 'Chạy 01-Chuan-bi.ps1 trước để tạo cấu hình backend.'}
    $result=@{}
    foreach($line in [IO.File]::ReadAllLines($taskWebhookConfig)){
        if($line -match '^([^#=]+)=(.*)$'){$result[$matches[1]]=$matches[2]}
    }
    return $result
}

function Set-WebhookProperty([string]$Key,[string]$Value) {
    $lines=@([IO.File]::ReadAllLines($taskWebhookConfig))
    $pattern='^'+[regex]::Escape($Key)+'='
    $found=$false;$changed=$false
    $updated=@(foreach($line in $lines){
        if($line -match $pattern){$found=$true;if($line -cne ($Key+'='+$Value)){$changed=$true};$Key+'='+$Value}else{$line}
    })
    if(-not $found){$updated+=($Key+'='+$Value);$changed=$true}
    if($changed){[IO.File]::WriteAllLines($taskWebhookConfig,$updated,[Text.UTF8Encoding]::new($false))}
}

function Initialize-WebhookConfig {
    $null=Read-WebhookProperties
    foreach($pair in @(@('bank.provider','sepay'),@('bank.code','BIDV'),@('bank.account','96247Y6CZZ'),@('bank.name','TRAN HOANG THU'))){
        $environmentName='TTM_'+$pair[0].ToUpperInvariant().Replace('.','_')
        $environmentValue=[Environment]::GetEnvironmentVariable($environmentName)
        if($environmentValue -and $environmentValue -cne $pair[1]){throw "Biến $environmentName đang ghi đè cấu hình nhận tiền. Kiểm tra trước khi chạy."}
        Set-WebhookProperty -Key $pair[0] -Value $pair[1]
    }
    $properties=Read-WebhookProperties
    $secret=[Environment]::GetEnvironmentVariable('TTM_SEPAY_WEBHOOK_API_KEY')
    if(-not $secret){$secret=$properties['sepay.webhook.api.key']}
    if(-not $secret){
        $bytes=New-Object byte[] 32
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
        $secret=[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    }
    if($secret.Length -lt 32 -or $secret -match '[\r\n]'){throw 'API Key SePay phải có ít nhất 32 ký tự và không chứa xuống dòng.'}
    Set-WebhookProperty -Key 'sepay.webhook.api.key' -Value $secret
    $keyFile=Join-Path $taskWebhookPrivate 'sepay-webhook-api-key.txt'
    if(-not(Test-Path -LiteralPath $keyFile) -or [IO.File]::ReadAllText($keyFile) -cne $secret){
        [IO.File]::WriteAllText($keyFile,$secret,[Text.UTF8Encoding]::new($false))
    }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $taskWebhookAgent)) | Out-Null
    if(-not(Test-Path -LiteralPath $taskWebhookAgent)){
        # Microsoft Store virtualizes AppData. Search only ngrok's own known config locations.
        $candidates=@((Join-Path $env:LOCALAPPDATA 'ngrok/ngrok.yml'),(Join-Path $env:USERPROFILE '.ngrok2/ngrok.yml'))
        if(Get-Command Get-AppxPackage -ErrorAction SilentlyContinue){
            foreach($package in @(Get-AppxPackage '*ngrok*')){
                $candidates+=Join-Path $env:LOCALAPPDATA ('Packages/'+$package.PackageFamilyName+'/LocalCache/Local/ngrok/ngrok.yml')
            }
        }
        $source=@($candidates | Where-Object {Test-Path -LiteralPath $_} | Select-Object -Unique)
        $token=$null
        foreach($candidate in $source){
            $match=[regex]::Match([IO.File]::ReadAllText($candidate),'(?m)^\s*authtoken:\s*["'']?([A-Za-z0-9_-]+)["'']?\s*$')
            if($match.Success){$token=$match.Groups[1].Value;break}
        }
        if(-not $token){throw 'Chưa tìm thấy cấu hình ngrok có authtoken. Cấu hình ngrok riêng trên máy, không dán token vào chat.'}
        # The token stays in a private Docker secret, never environment/arguments/log output.
        [IO.File]::WriteAllLines($taskWebhookAgent,@('version: "3"','agent:',('  authtoken: '+$token),'  web_addr: 0.0.0.0:4040'),[Text.UTF8Encoding]::new($false))
    }
    Write-Output 'SePay dùng mã TT/NT + MSSV đầy đủ + 8 chữ số của yêu cầu (18 ký tự).'
    Write-Output ('Webhook: '+$taskWebhookUrl+'/webhook')
    Write-Output 'API Key được lưu riêng trong .local/sepay-webhook-api-key.txt; không in khóa ra terminal.'
}

function Assert-WebhookOwnership {
    $known=@(Invoke-TtmDocker -Arguments @('ps','-a','--format','{{.Names}}'))
    if($known -contains $taskWebhookContainer){
        $labels=(Invoke-TtmDocker -Arguments @('inspect','--format','{{json .Config.Labels}}',$taskWebhookContainer)) | ConvertFrom-Json
        if($labels.'com.docker.compose.project' -ne 'ttm-cuoiky' -or $labels.'com.docker.compose.service' -ne 'ngrok'){
            throw 'Tên container ngrok thuộc tài nguyên khác; không thay đổi container đó.'
        }
    }
}

if($Action -eq 'Configure'){Initialize-WebhookConfig;return}
Start-TtmDockerDesktop
Assert-WebhookOwnership
if($Action -eq 'Stop'){
    Invoke-TtmCompose -Arguments @('--profile','webhook','stop','ngrok') | Out-Host
    Write-Output 'Đã dừng riêng tunnel SePay; backend và CSDL tiếp tục chạy.'
    return
}
if($Action -eq 'Status'){
    Invoke-TtmCompose -Arguments @('--profile','webhook','ps','ngrok') | Out-Host
    return
}
Initialize-WebhookConfig
$health=Invoke-RestMethod 'http://127.0.0.1:18080/api/health' -TimeoutSec 3
if($health.application -ne 'ttm-cantin' -or $health.bankWebhook -ne 'sepay-v1' -or $health.bankProvider -ne 'sepay'){
    throw 'Backend SePay chưa sẵn sàng. Build bản mới rồi chạy 02-Backend.ps1.'
}
Invoke-TtmCompose -Arguments @('--profile','webhook','up','-d','--no-deps','ngrok') | Out-Host
for($attempt=0;$attempt -lt 30;$attempt++){
    try{
        $agent=Invoke-RestMethod 'http://127.0.0.1:4041/api/tunnels' -TimeoutSec 1
        $matching=@($agent.tunnels | Where-Object {$_.public_url -eq $taskWebhookUrl -and $_.config.addr -eq 'http://host.docker.internal:18080'})
        if($matching.Count -eq 1){
            [IO.File]::WriteAllText((Join-Path $taskWebhookPrivate 'webhook-url.txt'),($taskWebhookUrl+'/webhook'),[Text.UTF8Encoding]::new($false))
            Write-Output ('Tunnel SePay sẵn sàng: '+$taskWebhookUrl+'/webhook -> backend:18080/webhook')
            return
        }
    }catch{}
    Start-Sleep -Milliseconds 500
}
throw 'Tunnel chưa sẵn sàng. Xem docker logs ttm-cuoiky-ngrok. Nếu domain đang dùng ở WebhookTest, dừng riêng tunnel cũ rồi chạy lại.'
