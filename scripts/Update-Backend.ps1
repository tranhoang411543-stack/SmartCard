[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$targetPath = Join-Path $projectRoot 'backend\target'
$jarName = 'ttm-backend-1.0-SNAPSHOT.jar'
$installedJar = Join-Path $targetPath $jarName
$backupJar = Join-Path $targetPath 'ttm-backend-previous.jar'
$launcher = Join-Path $projectRoot '02-Backend.ps1'
$jdk = 'C:\Program Files\Java\jdk-25'
$java = Join-Path $jdk 'bin\java.exe'

function Get-BackendPortOwners {
    $lines = & netstat.exe -ano
    if ($LASTEXITCODE -ne 0) { throw 'Khong kiem tra duoc cong backend 18080.' }
    $owners = foreach ($line in $lines) {
        if ($line -match '^\s*TCP\s+\S+:18080\s+(?:0\.0\.0\.0:0|\[::\]:0)\s+\S+\s+(\d+)\s*$') {
            [int]$Matches[1]
        }
    }
    $owners | Sort-Object -Unique
}

function Test-BackendJarAvailable {
    if (-not [IO.File]::Exists($installedJar)) { return $true }
    try {
        $probe = [IO.File]::Open($installedJar, 'Open', 'Read', 'None')
        $probe.Dispose()
        return $true
    } catch [IO.IOException] {
        if (($_.Exception.GetBaseException().HResult -band 0xffff) -in 32, 33) { return $false }
        throw
    }
}

function Stop-ProjectBackend {
    $listeners = @(Get-BackendPortOwners)
    if ($listeners.Count -eq 0 -and (Test-BackendJarAvailable)) { return }
    if ($listeners.Count -gt 1) { throw 'Cong 18080 co nhieu tien trinh lang nghe.' }

    $filter = if ($listeners.Count -eq 1) {
        "ProcessId=$($listeners[0])"
    } else {
        "Name='java.exe'"
    }
    try {
        $candidates = @(Get-CimInstance Win32_Process -Filter $filter -ErrorAction Stop)
    } catch {
        throw 'Khong doc duoc thong tin tien trinh de xac minh backend. Hay dong backend cu roi chay lai.'
    }

    $jarArgument = '(?:^|\s)-jar\s+(?:"{0}"|{0})(?=\s|$)' -f [regex]::Escape($installedJar)
    $owners = @($candidates | Where-Object {
        $_.ExecutablePath -eq $java -and $_.CommandLine -match $jarArgument
    })
    if ($owners.Count -ne 1) {
        if ($listeners.Count -eq 1) {
            throw "Cong 18080 dang do PID $($listeners[0]) giu. Khong xac minh duoc do la backend cua project."
        }
        throw 'JAR backend dang bi khoa. Hay dong chuong trinh dang giu JAR roi chay lai.'
    }

    $owner = $owners[0]
    $process = Get-Process -Id $owner.ProcessId -ErrorAction Stop
    try {
        [void]$process.Handle
        if (-not $owner.CreationDate -or
            [Math]::Abs(($process.StartTime.ToUniversalTime() - $owner.CreationDate.ToUniversalTime()).TotalMilliseconds) -gt 1) {
            throw 'Tien trinh backend da thay doi. Hay chay lai lenh cap nhat.'
        }
        Write-Host "Dang dung backend cu (PID $($process.Id))..."
        Stop-Process -InputObject $process -ErrorAction Stop
        if (-not $process.WaitForExit(15000)) { throw 'Backend cu chua dung sau 15 giay.' }
    } finally {
        $process.Dispose()
    }
}

if (-not $PSCmdlet.ShouldProcess($installedJar, 'Dong goi backend, luu JAR cu va khoi dong lai')) { return }

$targetDir = $null
$stageDir = $null
$updateLock = $null
$env:JAVA_HOME = $jdk

Push-Location -LiteralPath $projectRoot
try {
    $backendDir = Get-Item -LiteralPath (Join-Path $projectRoot 'backend')
    if ($backendDir.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Thu muc backend phai nam trong project.'
    }
    $targetDir = [IO.Directory]::CreateDirectory($targetPath)
    if ($targetDir.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Thu muc backend/target phai nam trong project.'
    }
    try {
        $updateLock = [IO.File]::Open(
            (Join-Path $targetDir.FullName 'backend-update.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch [IO.IOException] {
        if (($_.Exception.GetBaseException().HResult -band 0xffff) -in 32, 33) {
            throw 'Mot lenh cap nhat backend khac dang chay.'
        }
        throw
    }

    $stageDir = [IO.Directory]::CreateDirectory(
        (Join-Path $targetDir.FullName ('backend-update-' + [Guid]::NewGuid().ToString('N'))))
    Write-Host 'Dang dong goi backend tu source...'
    & mvn.cmd -o -f build/pom.xml -pl :ttm-backend -am "-Dttm.backend.buildDir=$($stageDir.FullName)" '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Dong goi backend that bai. Hay xem loi Maven o tren.' }

    $stagedJar = Join-Path $stageDir.FullName $jarName
    if (-not [IO.File]::Exists($stagedJar)) { throw 'Khong tim thay JAR backend sau khi dong goi.' }
    $entries = & (Join-Path $jdk 'bin\jar.exe') tf $stagedJar
    if ($LASTEXITCODE -ne 0 -or
        $entries -notcontains 'BOOT-INF/classes/vn/edu/smartcard/backend/BackendApplication.class') {
        throw 'JAR backend moi khong dung dinh dang khoi dong.'
    }

    Stop-ProjectBackend
    if (@(Get-BackendPortOwners).Count -gt 0) { throw 'Cong 18080 van dang duoc su dung.' }
    if (-not (Test-BackendJarAvailable)) { throw 'JAR backend van bi Windows khoa.' }

    if ([IO.File]::Exists($installedJar)) {
        [IO.File]::Replace($stagedJar, $installedJar, $backupJar)
        Write-Host "Da luu JAR cu tai $backupJar"
    } else {
        [IO.File]::Move($stagedJar, $installedJar)
    }

    Write-Host 'Dang khoi dong backend moi bang 02-Backend.ps1...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher
    if ($LASTEXITCODE -ne 0) {
        throw 'Da thay JAR, nhung 02-Backend.ps1 bao loi. Xem thong bao o tren va log backend trong .local.'
    }
    Write-Host 'Da cap nhat va khoi dong backend.'
} catch {
    $cause = $_.Exception.GetBaseException()
    if (($cause.HResult -band 0xffff) -in 32, 33) {
        [Console]::Error.WriteLine('Windows dang khoa JAR backend. Dong chuong trinh dang giu JAR roi chay lai.')
    } else {
        [Console]::Error.WriteLine($_.Exception.Message)
    }
    exit 1
} finally {
    if ($stageDir) {
        $resolvedStage = Get-Item -LiteralPath $stageDir.FullName -ErrorAction SilentlyContinue
        if ($resolvedStage -and $resolvedStage.Parent.FullName -eq $targetDir.FullName -and
            -not ($resolvedStage.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            Remove-Item -LiteralPath $resolvedStage.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if ($updateLock) { $updateLock.Dispose() }
    Pop-Location
}
