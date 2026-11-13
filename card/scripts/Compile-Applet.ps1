param([string]$SdkRoot='C:\JavaCardKit\SDK\Tools\JCIDE\lib\java_card_kit\3_0_4', [string]$JdkBin='C:\Program Files\Java\jdk1.8.0_202\bin')
$ErrorActionPreference='Stop'
$cardRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $cardRoot 'build\classic-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& (Join-Path $JdkBin 'javac.exe') -source 1.5 -target 1.5 -encoding UTF-8 -g:none -bootclasspath (Join-Path $SdkRoot 'lib\api_classic.jar') -d $output (Get-ChildItem -LiteralPath (Join-Path $cardRoot 'src\main\java\vn\edu\smartcard\card') -Filter '*.java').FullName
if($LASTEXITCODE -ne 0){throw 'Biên dịch applet thất bại.'}
Write-Output 'Applet đã biên dịch với Java Card API 3.0.4. Đây là file class, chưa phải CAP.'
