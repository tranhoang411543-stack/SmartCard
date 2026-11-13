[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$privateRoot = Join-Path $projectRoot '.local'
$targetRoot = Join-Path $projectRoot 'target'
$jdk = 'C:\Program Files\Java\jdk-25'
$java = Join-Path $jdk 'bin\java.exe'
$config = Join-Path $privateRoot 'server.properties'

function Assert-BackendStopped {
    $pidFile = Join-Path $privateRoot 'backend.pid'
    if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
        $backendProcessId = 0
        if ([int]::TryParse([IO.File]::ReadAllText($pidFile).Trim(), [ref]$backendProcessId) -and
            (Get-Process -Id $backendProcessId -ErrorAction SilentlyContinue)) {
            throw 'Backend có thể đang chạy. Hãy đóng backend trước khi tạo thẻ.'
        }
    }
    $port = 18080
    if ($env:TTM_PORT -and (-not [int]::TryParse($env:TTM_PORT, [ref]$port) -or
            $port -lt 1 -or $port -gt 65535)) {
        throw 'TTM_PORT không hợp lệ.'
    }
    $listeners = & netstat.exe -ano
    if ($LASTEXITCODE -ne 0) { throw 'Không kiểm tra được cổng backend.' }
    if (@($listeners | Where-Object { $_ -match "^\s*TCP\s+\S+:$port\s+\S+\s+LISTENING\s+\d+\s*$" }).Count) {
        throw "Cổng $port đang được sử dụng. Hãy đóng backend trước khi tạo thẻ."
    }
}

if (-not $PSCmdlet.ShouldProcess('SIM0011–SIM0060', 'Tạo 50 thẻ demo trên CSDL cục bộ')) { return }
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw 'Chưa có .local/server.properties. Hãy chuẩn bị project trước.'
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Không tìm thấy JDK 25 của project.' }
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw 'Không tìm thấy Maven.' }

$lock = $null
$backendJarLock = $null
$stage = $null
$targetDir = $null
$env:JAVA_HOME = $jdk
Push-Location -LiteralPath $projectRoot
try {
    $lock = [IO.File]::Open((Join-Path $privateRoot 'demo-50-cards.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Assert-BackendStopped
    $installedJar = Join-Path $projectRoot 'backend\target\ttm-backend-1.0-SNAPSHOT.jar'
    if (Test-Path -LiteralPath $installedJar -PathType Leaf) {
        try {
            $backendJarLock = [IO.File]::Open($installedJar, [IO.FileMode]::Open,
                [IO.FileAccess]::Read, [IO.FileShare]::None)
        } catch [IO.IOException] {
            throw 'JAR backend đang được sử dụng. Hãy đóng backend trước khi tạo thẻ.'
        }
    }
    $targetDir = [IO.Directory]::CreateDirectory($targetRoot)
    if ($targetDir.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Thư mục target phải nằm trong project.'
    }
    $stage = [IO.Directory]::CreateDirectory(
        (Join-Path $targetDir.FullName ('demo-50-build-' + [Guid]::NewGuid().ToString('N'))))
    Write-Host 'Đang chuẩn bị công cụ từ mã nguồn hiện tại...'
    & mvn.cmd -o -f build/pom.xml -pl :ttm-backend -am "-Dttm.backend.buildDir=$($stage.FullName)" '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Không đóng gói được công cụ tạo thẻ. Xem thông báo Maven phía trên.' }
    $jar = Join-Path $stage.FullName 'ttm-backend-1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Không tìm thấy công cụ sau khi đóng gói.' }
    Assert-BackendStopped
    Write-Host 'Đang tạo các thẻ SIM0011 đến SIM0060...'
    & $java '--add-opens=java.base/java.lang=ALL-UNNAMED' '--add-opens=java.base/java.math=ALL-UNNAMED' `
        '--add-opens=java.base/java.util=ALL-UNNAMED' "-Dttm.config=$config" -jar $jar --create-demo-cards-0011-0060
    if ($LASTEXITCODE -ne 0) { throw 'Chưa tạo đủ thẻ. Đối chiếu các thẻ đã tạo trước khi chạy lại.' }
    Write-Host 'Bạn có thể mở lại backend và Quầy quản lý.'
} finally {
    if ($stage -and $targetDir) {
        $checked = Get-Item -LiteralPath $stage.FullName -ErrorAction SilentlyContinue
        if ($checked -and $checked.Parent.FullName -eq $targetDir.FullName -and
            -not ($checked.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            Remove-Item -LiteralPath $checked.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if ($backendJarLock) { $backendJarLock.Dispose() }
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
