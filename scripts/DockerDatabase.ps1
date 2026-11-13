param([ValidateSet('Start','Stop','Status')][string]$Action='Start')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\DatabaseTools.ps1"
if(-not(Test-Path -LiteralPath $ttmDatabaseRuntime)){throw 'Chưa có xác nhận chuyển CSDL. Chạy 06-Chuyen-CSDL-sang-Docker.ps1 trước; không tạo CSDL rỗng thay thế nguồn.'}
$runtime=Get-Content -LiteralPath $ttmDatabaseRuntime -Raw | ConvertFrom-Json
if($runtime.provider -ne 'docker' -or -not $runtime.verified){throw 'Trạng thái chuyển CSDL chưa hợp lệ.'}
if($Action -eq 'Start'){
    Start-TtmDockerDesktop;Assert-TtmDockerOwnership
    $knownVolumes=@(Invoke-TtmDocker -Arguments @('volume','ls','--format','{{.Name}}'))
    if($knownVolumes -notcontains $ttmDatabaseVolume){throw 'Không tìm thấy volume dữ liệu đã chuyển. Khôi phục từ backup trước; không tạo CSDL rỗng thay thế.'}
    Start-TtmDockerDatabase
    Write-Output 'CSDL Docker sẵn sàng: 127.0.0.1:5548 / ttm_cantin / ttm.'
}elseif($Action -eq 'Stop'){
    Start-TtmDockerDesktop;Assert-TtmDockerOwnership
    Invoke-TtmCompose -Arguments @('stop','postgres') | Out-Host
    Write-Output 'Đã dừng container. Dữ liệu vẫn được giữ trong volume.'
}else{
    Start-TtmDockerDesktop;Assert-TtmDockerOwnership
    Invoke-TtmCompose -Arguments @('ps') | Out-Host
}
