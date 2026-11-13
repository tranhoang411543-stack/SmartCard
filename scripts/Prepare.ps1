param([switch]$Test)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$databaseRuntime=Join-Path $projectRoot '.local/database.runtime.json'
if(Test-Path -LiteralPath $databaseRuntime){
    $runtime=Get-Content -LiteralPath $databaseRuntime -Raw | ConvertFrom-Json
    if($runtime.provider -ne 'docker' -or -not $runtime.verified){throw 'Trạng thái CSDL chưa được xác minh. Không tự khởi tạo CSDL khác.'}
    & "$PSScriptRoot\DockerDatabase.ps1" -Action Start
    if($Test){
        . "$PSScriptRoot\DatabaseTools.ps1"
        $live=Read-TtmDatabaseConfig
        $testRoot=Join-Path $projectRoot '.local/test'
        [IO.Directory]::CreateDirectory($testRoot) | Out-Null
        $testFile=Join-Path $testRoot 'server.properties';$fixture=@{}
        if(Test-Path -LiteralPath $testFile){foreach($line in [IO.File]::ReadAllLines($testFile)){if($line -match '^([^#=]+)=(.*)$'){$fixture[$matches[1]]=$matches[2]}}}
        foreach($key in @('master.key','device.key','setup.token')){if(-not $fixture.ContainsKey($key)){$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($bytes)}finally{$rng.Dispose()};$fixture[$key]=[Convert]::ToBase64String($bytes)}}
        $fixture['db.url']='jdbc:postgresql://127.0.0.1:5548/ttm_test';$fixture['db.user']=$live['db.user'];$fixture['db.password']=$live['db.password'];$fixture['bank.account']='000000000000';$fixture['bank.bin']='970418';$fixture['smtp.host']=''
        [IO.File]::WriteAllLines($testFile,($fixture.GetEnumerator()|ForEach-Object{$_.Key+'='+$_.Value}),[Text.UTF8Encoding]::new($false))
        if((Invoke-TtmContainerSql -Database postgres -Sql "SELECT count(*) FROM pg_database WHERE datname='ttm_test';") -eq '0'){$null=Invoke-TtmDocker -Arguments @('exec',$ttmDatabaseContainer,'createdb','-U','ttm','--template=template0','--encoding=UTF8','--locale=C','ttm_test')}
        Write-Output 'CSDL kiểm thử ttm_test sẵn sàng trong Docker, tách biệt dữ liệu ứng dụng.'
    }
    return
}
if($Test){
    & $PSCommandPath
    $live=@{}
    foreach($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.local\server.properties'))){if($line -match '^([^#=]+)=(.*)$'){$live[$matches[1]]=$matches[2]}}
    $testRoot=Join-Path $projectRoot '.local\test'
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $testFile=Join-Path $testRoot 'server.properties'
    $fixture=@{}
    if(Test-Path -LiteralPath $testFile){foreach($line in [IO.File]::ReadAllLines($testFile)){if($line -match '^([^#=]+)=(.*)$'){$fixture[$matches[1]]=$matches[2]}}}
    foreach($key in @('master.key','device.key','setup.token')){if(-not $fixture.ContainsKey($key)){$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();try{$rng.GetBytes($bytes)}finally{$rng.Dispose()};$fixture[$key]=[Convert]::ToBase64String($bytes)}}
    $fixture['db.url']='jdbc:postgresql://127.0.0.1:5548/ttm_test';$fixture['db.user']=$live['db.user'];$fixture['db.password']=$live['db.password'];$fixture['bank.account']='000000000000';$fixture['bank.bin']='970418';$fixture['smtp.host']=''
    [IO.File]::WriteAllLines($testFile,($fixture.GetEnumerator()|ForEach-Object{$_.Key+'='+$_.Value}),[Text.UTF8Encoding]::new($false))
    $previous=$env:PGPASSWORD
    try{$env:PGPASSWORD=$live['db.password'];$exists=& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 5548 -U ttm -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='ttm_test'";if($LASTEXITCODE -ne 0){throw 'Không kết nối được CSDL project.'};if($exists -ne '1'){& 'C:\Program Files\PostgreSQL\18\bin\createdb.exe' -h 127.0.0.1 -p 5548 -U ttm ttm_test;if($LASTEXITCODE -ne 0){throw 'Không tạo được CSDL kiểm thử.'}}}finally{$env:PGPASSWORD=$previous}
    Write-Output 'CSDL kiểm thử ttm_test sẵn sàng, tách biệt dữ liệu ứng dụng.'
    return
}
$privateRoot=Join-Path $projectRoot $(if($Test){'.local\test'}else{'.local'})
$pgBin='C:\Program Files\PostgreSQL\18\bin'
$pgPort=if($Test){5549}else{5548}
$database=if($Test){'ttm_test'}else{'ttm_cantin'}
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
$serverFile=Join-Path $privateRoot 'server.properties'
function New-PrivateToken { $bytes=New-Object byte[] 32; $generator=[Security.Cryptography.RandomNumberGenerator]::Create();try{$generator.GetBytes($bytes)}finally{$generator.Dispose()};[Convert]::ToBase64String($bytes) }
if(-not(Test-Path -LiteralPath $serverFile)) {
    $dbPassword=New-PrivateToken
    $deviceKey=New-PrivateToken
    $setupToken=New-PrivateToken
    $masterKey=New-PrivateToken
    [IO.File]::WriteAllLines($serverFile,@("db.url=jdbc:postgresql://127.0.0.1:$pgPort/$database",'db.user=ttm',"db.password=$dbPassword","master.key=$masterKey","device.key=$deviceKey","setup.token=$setupToken",'smtp.host=','smtp.port=587','smtp.username=','smtp.password=','smtp.from=','bank.account=','bank.name=','bank.bin=','vietqr.client.id=','vietqr.api.key=','casso.webhook.key='),[Text.UTF8Encoding]::new($false))
    if(-not $Test){[IO.File]::WriteAllLines((Join-Path $privateRoot 'client.properties'),@('api.url=http://127.0.0.1:18080',"device.key=$deviceKey"),[Text.UTF8Encoding]::new($false))}
}
$props=@{}
foreach($line in [IO.File]::ReadAllLines($serverFile)){if($line -match '^([^#=]+)=(.*)$'){$props[$matches[1]]=$matches[2]}}
$dataDir=Join-Path $privateRoot 'postgres'
if(-not(Test-Path -LiteralPath (Join-Path $dataDir 'PG_VERSION'))) {
    $passwordFile=Join-Path $privateRoot 'pg-init-password.tmp'
    [IO.File]::WriteAllText($passwordFile,$props['db.password'],[Text.UTF8Encoding]::new($false))
    try { & (Join-Path $pgBin 'initdb.exe') -D $dataDir --username=ttm "--pwfile=$passwordFile" --encoding=UTF8 --locale=C --auth=scram-sha-256 | Out-Null; if($LASTEXITCODE -ne 0){throw 'Không khởi tạo được PostgreSQL riêng cho project.'} }
    finally { Remove-Item -LiteralPath $passwordFile -Force -ErrorAction SilentlyContinue }
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $dataDir status *> $null
if($LASTEXITCODE -ne 0){& (Join-Path $pgBin 'pg_ctl.exe') -D $dataDir -l (Join-Path $privateRoot 'postgres.log') -o "-p $pgPort -h 127.0.0.1" -w -t 20 start | Out-Null;if($LASTEXITCODE -ne 0){throw 'Không khởi động được PostgreSQL của project.'}}
$previousPassword=$env:PGPASSWORD
try {
    $env:PGPASSWORD=$props['db.password']
    $exists=& (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p $pgPort -U ttm -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$database'"
    if($LASTEXITCODE -ne 0){throw 'Không kết nối được PostgreSQL của project.'}
    if($exists -ne '1'){& (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p $pgPort -U ttm $database;if($LASTEXITCODE -ne 0){throw 'Không tạo được CSDL của project.'}}
} finally { $env:PGPASSWORD=$previousPassword }
Write-Output "PostgreSQL project sẵn sàng tại 127.0.0.1:$pgPort / $database. Cấu hình riêng đã lưu, không in thông tin bí mật."
