$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarName = 'TTM_CuoiKy-1.0-SNAPSHOT.jar'
$targetPath = Join-Path $projectRoot 'target'
$installedJar = Join-Path $targetPath $jarName
$backupJar = Join-Path $targetPath 'TTM_CuoiKy-previous.jar'
$stopDesktop = Join-Path $PSScriptRoot 'Stop-Desktop.ps1'
$updateLock = $null
$stageDir = $null
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'

Push-Location -LiteralPath $projectRoot
try {
    $targetDir = [IO.Directory]::CreateDirectory($targetPath)
    if ($targetDir.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Thu muc target phai nam trong project.'
    }
    $updateLock = [IO.File]::Open(
        (Join-Path $targetPath 'desktop-update.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $stageDir = [IO.Directory]::CreateDirectory(
        (Join-Path $targetPath ('desktop-update-' + [Guid]::NewGuid().ToString('N'))))
    Write-Host 'Dang dong goi ban desktop moi...'
    & mvn.cmd -o -f build/pom.xml -pl :TTM_CuoiKy -am "-Dttm.desktop.buildDir=$($stageDir.FullName)" '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven chua dong goi duoc desktop. Hay xem loi build o phia tren.'
    }

    $stagedJar = Join-Path $stageDir.FullName $jarName
    if (-not [IO.File]::Exists($stagedJar)) {
        throw 'Khong tim thay JAR desktop sau khi dong goi.'
    }
    Write-Host 'Dang dong hai quay cu...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $stopDesktop -SkipUpdateLockCheck
    if ($LASTEXITCODE -ne 0) {
        throw 'Khong dong duoc hai quay; JAR cu chua bi thay. Hay xu ly cua so con mo roi chay lai.'
    }
    if ([IO.File]::Exists($installedJar)) {
        [IO.File]::Replace($stagedJar, $installedJar, $backupJar)
    } else {
        [IO.File]::Move($stagedJar, $installedJar)
    }
    Write-Host 'Da cap nhat JAR desktop cho ca hai quay.'
} catch {
    $errorCode = $_.Exception.GetBaseException().HResult -band 0xffff
    if ($errorCode -in 32, 33) {
        [Console]::Error.WriteLine('Tep desktop dang bi khoa hoac co luot cap nhat khac. Hay doi roi chay lai.')
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
    if ($updateLock) {
        $updateLock.Dispose()
    }
    Pop-Location
}
