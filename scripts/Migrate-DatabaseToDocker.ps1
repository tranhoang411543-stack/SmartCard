$ErrorActionPreference='Stop'
. "$PSScriptRoot\DatabaseTools.ps1"
if(Test-Path -LiteralPath $ttmDatabaseRuntime){
    $runtime=Get-Content -LiteralPath $ttmDatabaseRuntime -Raw | ConvertFrom-Json
    if($runtime.provider -eq 'docker' -and $runtime.verified){Write-Output 'CSDL đã chuyển vào Docker. Dùng 06-CSDL-Docker.cmd để bật lại.';return}
    throw 'Có trạng thái CSDL chưa được xác minh. Kiểm tra trước khi chuyển lại.'
}
$config=Read-TtmDatabaseConfig
$nativeData=[IO.Path]::GetFullPath((Join-Path $ttmDatabaseRoot '.local/postgres'))
$backup=Join-Path $ttmDatabaseRoot ('.local/backups/docker-migration-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6))
$nativeStopped=$false
$completed=$false
$backendStopped=$false
$sourceManifests=[ordered]@{}

function Assert-TtmNativeInstance {
    $actual=Invoke-TtmNativeSql -Database postgres -Sql "SELECT current_setting('data_directory');"
    if(-not [string]::Equals([IO.Path]::GetFullPath($actual),$nativeData,[StringComparison]::OrdinalIgnoreCase)){throw 'Cụm PostgreSQL trên 5548 không phải .local/postgres của project.'}
    $version=[int](Invoke-TtmNativeSql -Database postgres -Sql "SELECT current_setting('server_version_num');")
    if($version -lt 180000 -or $version -ge 190000){throw 'Nguồn không phải PostgreSQL 18; dừng để không đổi major version ngoài yêu cầu.'}
}

function Stop-TtmMigrationBackend {
    $listeners=@(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue)
    if($listeners.Count -eq 0){return}
    $ids=@($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    if($ids.Count -ne 1){throw 'Không xác định được backend duy nhất trên cổng 18080.'}
    $jar=Join-Path $ttmDatabaseRoot 'backend/target/ttm-backend-1.0-SNAPSHOT.jar'
    $process=Get-CimInstance Win32_Process -Filter ("ProcessId="+$ids[0])
    if(-not $process -or -not $process.ExecutablePath -or $process.ExecutablePath -ne 'C:\Program Files\Java\jdk-25\bin\java.exe' -or -not $process.CommandLine -or $process.CommandLine.IndexOf($jar,[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'Cổng 18080 chưa được xác nhận là backend thuộc project. Đóng backend bằng cửa sổ của nó trước.'}
    Stop-Process -Id $process.ProcessId
    Wait-Process -Id $process.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
    $script:backendStopped=$true
}

function Write-TtmMigrationFile {
    param([string]$Path,$Value)
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 12),[Text.UTF8Encoding]::new($false))
}

try {
    Start-TtmDockerDesktop
    Assert-TtmDockerOwnership
    Assert-TtmNativeInstance
    Stop-TtmMigrationBackend
    foreach($database in @('ttm_cantin','ttm_test')){
        $exists=Invoke-TtmNativeSql -Database postgres -Sql ("SELECT count(*) FROM pg_database WHERE datname='"+$database+"';")
        if($exists -eq '0'){continue}
        $clients=Invoke-TtmNativeSql -Database $database -Sql "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();"
        if($clients -ne '0'){throw "CSDL $database còn phiên kết nối. Đóng quầy/backend và ngắt kết nối DBeaver trước khi chuyển."}
        $sourceManifests[$database]=Get-TtmDatabaseManifest -Database $database -Provider Native
    }
    if(-not $sourceManifests.Contains('ttm_cantin')){throw 'Không tìm thấy CSDL ứng dụng để chuyển.'}
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    foreach($name in @('server.properties','client.properties')){Copy-Item -LiteralPath (Join-Path $ttmDatabaseRoot ('.local/'+$name)) -Destination (Join-Path $backup $name) -ErrorAction SilentlyContinue}
    if(Test-Path -LiteralPath (Join-Path $ttmDatabaseRoot '.local/cards')){Copy-Item -LiteralPath (Join-Path $ttmDatabaseRoot '.local/cards') -Destination (Join-Path $backup 'cards') -Recurse}
    Write-TtmMigrationFile -Path (Join-Path $backup 'source-manifests.json') -Value $sourceManifests
    $dumpHashes=[ordered]@{}
    foreach($database in $sourceManifests.Keys){
        $file=Join-Path $backup ($database+'.dump')
        $previous=$env:PGPASSWORD;$previousTimeout=$env:PGCONNECT_TIMEOUT
        try{
            $env:PGPASSWORD=$config['db.password'];$env:PGCONNECT_TIMEOUT='5'
            & (Join-Path $ttmDatabasePgBin 'pg_dump.exe') -w -h 127.0.0.1 -p 5548 -U ttm -d $database --format=custom --no-owner --no-privileges --file=$file
            if($LASTEXITCODE -ne 0){throw "Sao lưu $database thất bại; chưa chuyển kết nối."}
        }finally{$env:PGPASSWORD=$previous;$env:PGCONNECT_TIMEOUT=$previousTimeout}
        if((Get-Item -LiteralPath $file).Length -lt 5){throw 'File dump không hợp lệ.'}
        $dumpHashes[$database]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
        Write-Output "Đã sao lưu $database."
    }
    Write-TtmMigrationFile -Path (Join-Path $backup 'dump-sha256.json') -Value $dumpHashes
    # Restore on a temporary local port. The old PostgreSQL remains authoritative.
    Start-TtmDockerDatabase -Port 5549
    $destinationManifests=[ordered]@{}
    foreach($database in $sourceManifests.Keys){
        $exists=Invoke-TtmContainerSql -Database postgres -Sql ("SELECT count(*) FROM pg_database WHERE datname='"+$database+"';")
        if($exists -eq '0'){$null=Invoke-TtmDocker -Arguments @('exec',$ttmDatabaseContainer,'createdb','-U','ttm','--template=template0','--encoding=UTF8','--locale=C',$database)}
        $tables=[long](Invoke-TtmContainerSql -Database $database -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT LIKE 'pg_%' AND table_schema<>'information_schema' AND table_type='BASE TABLE';")
        if($tables -eq 0){
            $containerFile='/tmp/'+$database+'.dump'
            $null=Invoke-TtmDocker -Arguments @('cp',(Join-Path $backup ($database+'.dump')),($ttmDatabaseContainer+':'+$containerFile))
            $restoreOutput=& docker exec $ttmDatabaseContainer pg_restore -U ttm --dbname=$database --no-owner --no-privileges --single-transaction --exit-on-error $containerFile 2>&1
            if($LASTEXITCODE -ne 0){throw "Khôi phục $database thất bại. Bản nguồn và dump được giữ nguyên; chưa chuyển cổng."}
        }
        $destinationManifests[$database]=Get-TtmDatabaseManifest -Database $database -Provider Docker
        Assert-TtmDatabaseManifest -Expected $sourceManifests[$database] -Actual $destinationManifests[$database] -Message "Dữ liệu đích $database không khớp nguồn. Không chuyển kết nối hoặc ghi đè dữ liệu đích."
        Write-Output "Đối chiếu ${database}: bảng/dữ liệu/sequence/large-object khớp nguồn."
    }
    Write-TtmMigrationFile -Path (Join-Path $backup 'docker-manifests.json') -Value $destinationManifests
    foreach($database in $sourceManifests.Keys){
        $latest=Get-TtmDatabaseManifest -Database $database -Provider Native
        Assert-TtmDatabaseManifest -Expected $sourceManifests[$database] -Actual $latest -Message "Nguồn $database đã đổi trong khi chuyển. Giữ nguyên nguồn để sao lưu lại."
        $clients=Invoke-TtmNativeSql -Database $database -Sql "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();"
        if($clients -ne '0'){throw 'Có kết nối mới tới nguồn. Chưa ngắt PostgreSQL cũ.'}
    }
    Assert-TtmNativeInstance
    & (Join-Path $ttmDatabasePgBin 'pg_ctl.exe') -D $nativeData -m fast -w -t 25 stop
    if($LASTEXITCODE -ne 0){throw 'Chưa dừng được PostgreSQL nguồn. Không chuyển cổng.'}
    $nativeStopped=$true
    Start-TtmDockerDatabase -Port 5548
    foreach($database in $sourceManifests.Keys){
        $actual=Get-TtmDatabaseManifest -Database $database -Provider Docker
        Assert-TtmDatabaseManifest -Expected $sourceManifests[$database] -Actual $actual -Message 'Dữ liệu đổi sau khi tạo lại container ở cổng chính.'
    }
    # Test TCP authentication using the same endpoint/password the backend and DBeaver use.
    $connected=Invoke-TtmNativeSql -Database ttm_cantin -Sql "SELECT current_database()||'|'||current_user;"
    if($connected -ne 'ttm_cantin|ttm'){throw 'Chưa xác nhận được kết nối TCP ở 127.0.0.1:5548.'}
    $runtime=[ordered]@{provider='docker';verified=$true;container=$ttmDatabaseContainer;volume=$ttmDatabaseVolume;host='127.0.0.1';port=5548;databases=@($sourceManifests.Keys);nativeDataDirectory=$nativeData;backupDirectory=$backup;migratedAtUtc=[DateTime]::UtcNow.ToString('o');backendWasStopped=$backendStopped}
    Write-TtmMigrationFile -Path $ttmDatabaseRuntime -Value $runtime
    Write-TtmMigrationFile -Path (Join-Path $backup 'completed.json') -Value $runtime
    $completed=$true
    Write-Output 'Đã chuyển và xác minh CSDL trong Docker tại 127.0.0.1:5548. Mật khẩu không đổi.'
    Write-Output ('Bản sao lưu riêng: '+$backup)
} finally {
    if($nativeStopped -and -not $completed){
        # The source data directory was never removed. Release only this container's port.
        try{Invoke-TtmCompose -Arguments @('stop','postgres') | Out-Host}catch{}
        $log=Join-Path $ttmDatabaseRoot '.local/postgres-docker-rollback.log'
        $ttmRollbackPgArgs=@('-D',('"'+$nativeData+'"'),'-l',('"'+$log+'"'),'-o','"-p 5548 -h 127.0.0.1"','-w','-t','20','start')
        $process=Start-Process -FilePath (Join-Path $ttmDatabasePgBin 'pg_ctl.exe') -ArgumentList $ttmRollbackPgArgs -WindowStyle Hidden -PassThru
        if(-not $process.WaitForExit(30000) -or $process.ExitCode -ne 0){Write-Warning 'Cần bật lại PostgreSQL cũ; dữ liệu nguồn và bản dump vẫn được giữ.'}
    }
}
