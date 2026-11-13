$ErrorActionPreference='Stop'
. "$PSScriptRoot\scripts\DatabaseTools.ps1"
$taskPublicWebhook='https://hardening-surname-entity.ngrok-free.dev'
$taskKeyFile=Join-Path $PSScriptRoot '.local/sepay-webhook-api-key.txt'
if(-not(Test-Path -LiteralPath $taskKeyFile)){throw 'Chạy 02-Backend.ps1 để chuẩn bị API Key trước.'}
$taskKey=[IO.File]::ReadAllText($taskKeyFile)
$taskBefore=Invoke-TtmContainerSql -Database ttm_cantin -Sql 'SELECT (SELECT count(*) FROM cards),(SELECT count(*) FROM bank_events),(SELECT count(*) FROM topups),(SELECT count(*) FROM wallet_operations);'
function Invoke-WebhookProof([string]$Path,[bool]$Authenticated,[string]$Method='POST') {
    $headers=@{'ngrok-skip-browser-warning'='1'}
    if($Authenticated){$headers['Authorization']='Apikey '+$taskKey}
    $arguments=@{Uri=($taskPublicWebhook+$Path);Method=$Method;Headers=$headers;UseBasicParsing=$true;TimeoutSec=15;MaximumRedirection=0}
    if($Method -eq 'POST'){$arguments['ContentType']='application/json';$arguments['Body']='{"id":0}'}
    try {
        $response=Invoke-WebRequest @arguments
        return @{status=[int]$response.StatusCode;body=$response.Content}
    } catch {
        if($_.Exception.Response){return @{status=[int]$_.Exception.Response.StatusCode;body=''}}
        throw 'Không kết nối được URL ngrok. Kiểm tra container và kết nối Internet.'
    }
}
$taskUnauthorized=Invoke-WebhookProof -Path '/webhook' -Authenticated $false
if($taskUnauthorized.status -ne 401){throw 'Public webhook chưa trả đúng 401 khi thiếu API Key.'}
$taskAccepted=Invoke-WebhookProof -Path '/webhook' -Authenticated $true
if($taskAccepted.status -ne 200 -or -not($taskAccepted.body | ConvertFrom-Json).success){throw 'Probe có API Key chưa nhận được success=true từ backend.'}
$taskClosed=Invoke-WebhookProof -Path '/api/health' -Authenticated $false -Method 'GET'
if($taskClosed.status -ne 404){throw 'Đường API ứng dụng chưa được đóng trên tunnel.'}
$taskAfter=Invoke-TtmContainerSql -Database ttm_cantin -Sql 'SELECT (SELECT count(*) FROM cards),(SELECT count(*) FROM bank_events),(SELECT count(*) FROM topups),(SELECT count(*) FROM wallet_operations);'
if($taskBefore -cne $taskAfter){throw 'Dữ liệu ứng dụng thay đổi trong lúc probe. Kiểm tra giao dịch đồng thời; không coi probe này là bằng chứng không tác động.'}
$taskEvidence=[ordered]@{
    verifiedAt=[DateTime]::UtcNow.ToString('o')
    webhook=($taskPublicWebhook+'/webhook')
    unauthorizedStatus=$taskUnauthorized.status
    authenticatedProbeStatus=$taskAccepted.status
    acknowledgement=@{success=$true}
    applicationApiStatus=$taskClosed.status
    applicationRowCountsUnchanged=$true
    probeId=0
    realBankTransferVerified=$false
}
$taskEvidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot '.local/verification-sepay-public.json') -Encoding utf8
$taskEvidence | ConvertTo-Json -Depth 4
