$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Stop-Desktop.ps1')

$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$jar = Join-Path $taskRoot 'target\TTM_CuoiKy-1.0-SNAPSHOT.jar'
$java = '"C:\Program Files\Java\jdk-25\bin\javaw.exe"'
$quotedJar = '"{0}"' -f $jar

function Assert-Role {
    param([string]$Name, [string]$Command, [AllowNull()][string]$Expected, [string]$Case)
    $actual = Get-DesktopRole -ProcessName $Name -CommandLine $Command -JarPath $jar
    if ([string]$actual -cne [string]$Expected) {
        throw "Sai kết quả nhận diện ($Case): mong đợi '$Expected', nhận '$actual'."
    }
}

Assert-Role 'javaw.exe' "$java -jar $quotedJar --quay=quanly" 'quầy quản lý' 'management'
Assert-Role 'javaw.exe' "$java -jar $quotedJar --quay=thanhtoan" 'quầy thanh toán' 'checkout'
Assert-Role 'java.exe' "java -jar $quotedJar" 'quầy quản lý' 'default management'
Assert-Role 'javaw.exe' "$java -jar $jar --quay=quanly" 'quầy quản lý' 'unquoted path'
Assert-Role 'javaw.exe' "$java -jar `"$jar.backup`" --quay=quanly" $null 'similar filename'
Assert-Role 'javaw.exe' "$java -jar `"C:\OtherProject\TTM_CuoiKy-1.0-SNAPSHOT.jar`" --quay=quanly" $null 'other project'
Assert-Role 'javaw.exe' "$java -cp $quotedJar example.Main --quay=quanly" $null 'jar on classpath'
Assert-Role 'javaw.exe' "$java -jar `"C:\JavaCard\TTM_CuoiKy\backend\target\ttm-backend-1.0-SNAPSHOT.jar`"" $null 'backend'
Assert-Role 'other.exe' "other -jar $quotedJar --quay=quanly" $null 'non-Java process'
Assert-Role 'javaw.exe' '' $null 'missing command line'
Write-Host 'PASS: nhận diện đúng hai quầy và bỏ qua tiến trình không liên quan.'
