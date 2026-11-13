$ttmDatabaseRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ttmDatabaseContainer='ttm-cuoiky-postgres'
$ttmDatabaseVolume='ttm_cuoiky_postgres18_data'
$ttmDatabaseRuntime=Join-Path $ttmDatabaseRoot '.local/database.runtime.json'
$ttmDatabasePgBin='C:\Program Files\PostgreSQL\18\bin'

function Read-TtmDatabaseConfig {
    $file=Join-Path $ttmDatabaseRoot '.local/server.properties'
    if(-not(Test-Path -LiteralPath $file)){throw 'Chưa có .local/server.properties. Cần chuẩn bị cấu hình project trước.'}
    $result=@{}
    foreach($line in [IO.File]::ReadAllLines($file)){if($line -match '^([^#=]+)=(.*)$'){$result[$matches[1]]=$matches[2]}}
    if($result['db.url'] -ne 'jdbc:postgresql://127.0.0.1:5548/ttm_cantin' -or $result['db.user'] -ne 'ttm' -or -not $result['db.password']){throw 'Cấu hình CSDL không khớp project ttm_cantin/ttm/5548. Dừng để không chuyển nhầm CSDL.'}
    foreach($name in @('TTM_DB_URL','TTM_DB_USER','TTM_DB_PASSWORD')){if([Environment]::GetEnvironmentVariable($name)){throw "Đang có biến $name ghi đè kết nối. Bỏ ghi đè trước khi chuyển CSDL."}}
    return $result
}

function Invoke-TtmDocker {
    param([Parameter(Mandatory=$true)][string[]]$Arguments)
    $result=& docker @Arguments
    if($LASTEXITCODE -ne 0){throw "Docker chưa hoàn tất thao tác '$($Arguments[0])' (exit $LASTEXITCODE)."}
    return $result
}

function Test-TtmDockerReady {
    try{$null=& docker info --format '{{.ServerVersion}}' 2>$null;return $LASTEXITCODE -eq 0}catch{return $false}
}

function Start-TtmDockerDesktop {
    if(-not(Get-Command docker -ErrorAction SilentlyContinue)){throw 'Chưa cài Docker Desktop hoặc docker chưa có trong PATH.'}
    if(Test-TtmDockerReady){return}
    $desktop=Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if(-not(Test-Path -LiteralPath $desktop)){throw 'Mở Docker Desktop và đợi engine sẵn sàng trước.'}
    Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
    for($attempt=0;$attempt -lt 20;$attempt++){Start-Sleep -Seconds 2;if(Test-TtmDockerReady){return}}
    throw 'Docker Desktop chưa sẵn sàng. Mở ứng dụng, kiểm tra engine Linux rồi chạy lại.'
}

function Assert-TtmDockerOwnership {
    $names=@(Invoke-TtmDocker -Arguments @('ps','-a','--format','{{.Names}}'))
    if($names -contains $ttmDatabaseContainer){
        $labels=(Invoke-TtmDocker -Arguments @('inspect','--format','{{json .Config.Labels}}',$ttmDatabaseContainer)) | ConvertFrom-Json
        if($labels.'com.docker.compose.project' -ne 'ttm-cuoiky'){throw 'Tên container đã thuộc tài nguyên khác. Không thay đổi container đó.'}
    }
    $volumes=@(Invoke-TtmDocker -Arguments @('volume','ls','--format','{{.Name}}'))
    if($volumes -contains $ttmDatabaseVolume){
        $labels=(Invoke-TtmDocker -Arguments @('volume','inspect','--format','{{json .Labels}}',$ttmDatabaseVolume)) | ConvertFrom-Json
        if($labels.'com.docker.compose.project' -ne 'ttm-cuoiky'){throw 'Tên volume đã thuộc tài nguyên khác. Không ghi đè volume đó.'}
    }
}

function Initialize-TtmDockerSecret {
    $config=Read-TtmDatabaseConfig
    $directory=Join-Path $ttmDatabaseRoot '.local/docker'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $file=Join-Path $directory 'postgres-password.txt'
    if(Test-Path -LiteralPath $file){
        if([IO.File]::ReadAllText($file) -cne $config['db.password']){throw 'Mật khẩu file Docker khác cấu hình backend. Không tự đổi mật khẩu CSDL.'}
    }else{[IO.File]::WriteAllText($file,$config['db.password'],[Text.UTF8Encoding]::new($false))}
}

function Invoke-TtmCompose {
    param([Parameter(Mandatory=$true)][string[]]$Arguments,[int]$Port=5548)
    $previous=$env:TTM_DB_DOCKER_PORT
    try{
        $env:TTM_DB_DOCKER_PORT=[string]$Port
        Invoke-TtmDocker -Arguments (@('compose','--project-name','ttm-cuoiky','--file',(Join-Path $ttmDatabaseRoot 'compose.yaml'))+$Arguments)
    }finally{$env:TTM_DB_DOCKER_PORT=$previous}
}

function Start-TtmDockerDatabase {
    param([int]$Port=5548)
    Start-TtmDockerDesktop
    Assert-TtmDockerOwnership
    Initialize-TtmDockerSecret
    Invoke-TtmCompose -Port $Port -Arguments @('up','-d','--wait','--wait-timeout','45','postgres') | Out-Host
    $null=Invoke-TtmDocker -Arguments @('exec',$ttmDatabaseContainer,'psql','-X','-q','-U','ttm','-d','ttm_cantin','-v','ON_ERROR_STOP=1','-Atc','SELECT 1')
}

function Invoke-TtmNativeSql {
    param([Parameter(Mandatory=$true)][string]$Database,[Parameter(Mandatory=$true)][string]$Sql)
    if($Database -notin @('postgres','ttm_cantin','ttm_test')){throw 'CSDL nguồn ngoài phạm vi project.'}
    $config=Read-TtmDatabaseConfig
    $previous=$env:PGPASSWORD;$previousTimeout=$env:PGCONNECT_TIMEOUT;$previousEncoding=$env:PGCLIENTENCODING
    try{
        $env:PGPASSWORD=$config['db.password'];$env:PGCONNECT_TIMEOUT='5';$env:PGCLIENTENCODING='UTF8'
        $result=& (Join-Path $ttmDatabasePgBin 'psql.exe') -X -q -w -h 127.0.0.1 -p 5548 -U ttm -d $Database -v ON_ERROR_STOP=1 -Atc ("SET timezone='UTC'; SET extra_float_digits=3; "+$Sql)
        if($LASTEXITCODE -ne 0){throw "Không truy vấn được CSDL nguồn $Database."}
        return [string]::Join("`n",@($result)).Trim()
    }finally{$env:PGPASSWORD=$previous;$env:PGCONNECT_TIMEOUT=$previousTimeout;$env:PGCLIENTENCODING=$previousEncoding}
}

function Invoke-TtmContainerSql {
    param([Parameter(Mandatory=$true)][string]$Database,[Parameter(Mandatory=$true)][string]$Sql)
    if($Database -notin @('postgres','ttm_cantin','ttm_test')){throw 'CSDL đích ngoài phạm vi project.'}
    $result=Invoke-TtmDocker -Arguments @('exec',$ttmDatabaseContainer,'psql','-X','-q','-U','ttm','-d',$Database,'-v','ON_ERROR_STOP=1','-Atc',("SET timezone='UTC'; SET extra_float_digits=3; "+$Sql))
    return [string]::Join("`n",@($result)).Trim()
}

function Get-TtmDatabaseManifest {
    param([Parameter(Mandatory=$true)][string]$Database,[ValidateSet('Native','Docker')][string]$Provider)
    $query=if($Provider -eq 'Native'){${function:Invoke-TtmNativeSql}}else{${function:Invoke-TtmContainerSql}}
    $manifest=[ordered]@{database=$Database;tables=@();sequences=@();objects=@();largeObjects=@()}
    $catalog=(& $query -Database $Database -Sql "SELECT schemaname,tablename FROM pg_tables WHERE schemaname NOT LIKE 'pg_%' AND schemaname<>'information_schema' ORDER BY schemaname,tablename;") -split "`n"
    # Use one hash per row before aggregation; no row values leave PostgreSQL.
    foreach($entry in $catalog){
        if(-not $entry){continue};$parts=$entry -split '\|',2
        $schema=$parts[0].Replace('"','""');$table=$parts[1].Replace('"','""')
        $sql='SELECT count(*)::text||''|''||md5(COALESCE(string_agg(md5(row_to_json(t)::text),'''' ORDER BY md5(row_to_json(t)::text)),'''')) FROM "'+$schema+'"."'+$table+'" t;'
        $data=(& $query -Database $Database -Sql $sql) -split '\|',2
        $manifest.tables+= [ordered]@{schema=$parts[0];name=$parts[1];rows=[long]$data[0];hash=$data[1]}
    }
    $sequences=(& $query -Database $Database -Sql "SELECT schemaname,sequencename FROM pg_sequences WHERE schemaname NOT LIKE 'pg_%' ORDER BY schemaname,sequencename;") -split "`n"
    foreach($entry in $sequences){
        if(-not $entry){continue};$parts=$entry -split '\|',2
        $sql='SELECT last_value::text||''|''||is_called::text FROM "'+$parts[0].Replace('"','""')+'"."'+$parts[1].Replace('"','""')+'";'
        $data=(& $query -Database $Database -Sql $sql) -split '\|',2
        $manifest.sequences+= [ordered]@{schema=$parts[0];name=$parts[1];lastValue=$data[0];isCalled=$data[1]}
    }
    $manifest.objects=(& $query -Database $Database -Sql "SELECT n.nspname,c.relkind,count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' GROUP BY n.nspname,c.relkind ORDER BY n.nspname,c.relkind;") -split "`n"
    $manifest.largeObjects=(& $query -Database $Database -Sql "SELECT count(*)::text||'|'||md5(COALESCE(string_agg(oid::text,',' ORDER BY oid),'')) FROM pg_largeobject_metadata; SELECT count(*)::text||'|'||md5(COALESCE(string_agg(md5(loid::text||':'||pageno::text||':'||encode(data,'hex')),'' ORDER BY loid,pageno),'')) FROM pg_largeobject;") -split "`n"
    return $manifest
}

function Assert-TtmDatabaseManifest {
    param($Expected,$Actual,[string]$Message)
    $a=ConvertTo-Json -InputObject $Expected -Depth 12 -Compress
    $b=ConvertTo-Json -InputObject $Actual -Depth 12 -Compress
    if($a -cne $b){throw $Message}
}
