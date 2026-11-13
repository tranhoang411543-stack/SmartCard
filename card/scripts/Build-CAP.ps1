param([string]$SdkRoot='C:\JavaCardKit\SDK\Tools\JCIDE\lib\java_card_kit\3_0_4', [string]$JdkBin='C:\Program Files\Java\jdk1.8.0_202\bin', [switch]$WithoutDebug)
$ErrorActionPreference='Stop'
$cardRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tools=Join-Path $SdkRoot 'lib\tools.jar'
if(-not(Test-Path -LiteralPath $tools)){throw 'SDK Classic tools.jar is required.'}
$compiler=$JdkBin
$classes=Join-Path $cardRoot 'build\classic-classes'
$output=Join-Path $cardRoot 'build\cap'
New-Item -ItemType Directory -Path $classes,$output -Force | Out-Null
$source=(Get-ChildItem -LiteralPath (Join-Path $cardRoot 'src\main\java\vn\edu\smartcard\card') -Filter '*.java').FullName
$debugOption='-g'
$converterDebug=@('-debug')
if($WithoutDebug){$debugOption='-g:none';$converterDebug=@()}
& (Join-Path $compiler 'javac.exe') -source 1.5 -target 1.5 -encoding UTF-8 $debugOption -bootclasspath (Join-Path $SdkRoot 'lib\api_classic.jar') -d $classes $source
if($LASTEXITCODE -ne 0){throw 'Biên dịch applet cho CAP thất bại.'}
$exports=Join-Path $SdkRoot 'export_files\api_export_files'
$classpath=Join-Path $SdkRoot 'lib\*'
& (Join-Path $compiler 'java.exe') -classpath $classpath com.sun.javacard.converter.Main -classdir $classes -exportpath $exports -d $output -out CAP EXP JCA @converterDebug -applet '0xf0:0x54:0x54:0x4d:0x01:0x01' vn.edu.smartcard.card.StudentCardApplet -applet '0xf0:0x54:0x54:0x4d:0x01:0x7e' vn.edu.smartcard.card.ResourceProbeApplet vn.edu.smartcard.card '0xf0:0x54:0x54:0x4d:0x01' 2.0
if($LASTEXITCODE -ne 0){throw 'Chưa chuyển được CAP. Xem thông báo converter và docs/THIET-KE.md.'}
$cap=Join-Path $output 'vn\edu\smartcard\card\javacard\card.cap'
if(-not(Test-Path -LiteralPath $cap)){throw 'Expected CAP was not produced.'}
$exportFiles=@('java\lang\javacard\lang.exp','javacard\framework\javacard\framework.exp','javacard\security\javacard\security.exp','javacardx\crypto\javacard\crypto.exp') | ForEach-Object {Join-Path $exports $_}
& (Join-Path $compiler 'java.exe') -classpath $classpath com.sun.javacard.offcardverifier.Verifier @exportFiles $cap
if($LASTEXITCODE -ne 0){throw 'Explicit CAP verification failed.'}
$hash=(Get-FileHash -LiteralPath $cap -Algorithm SHA256).Hash.ToLowerInvariant()
[ordered]@{cap=$cap;sha256=$hash;bytes=(Get-Item -LiteralPath $cap).Length;apiTarget='3.0.4 Classic';expectedPlatform='3.0.5 Classic (qualify on card)';packageVersion='2.0';appletAid='F054544D0101';probeAid='F054544D017E';transientArrayBytes=1249;debugSymbols=(-not $WithoutDebug);hardwareVerified=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'cap-manifest.json') -Encoding UTF8
$jcide=Join-Path $cardRoot 'build\jcide'
$jcideSource=Join-Path $jcide 'src\vn\edu\smartcard\card'
$jcideClasses=Join-Path $jcide 'bin\vn\edu\smartcard\card'
$jcideCap=Join-Path $jcideClasses 'javacard'
New-Item -ItemType Directory -Path $jcideSource,$jcideClasses,$jcideCap -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $jcideSource -Force
Get-ChildItem -LiteralPath (Join-Path $classes 'vn\edu\smartcard\card') -Filter '*.class' | Copy-Item -Destination $jcideClasses -Force
Copy-Item -LiteralPath $cap -Destination $jcideCap -Force
$project='<JavaCardProject name="TTM_Card"><USER><PKG name="vn.edu.smartcard.card" aid="F0 54 54 4D 01" major="2" minor="0"><APPLET aid="F0 54 54 4D 01 01" instanceaid="" parameter="" authority="" privileges="" index="0" name="StudentCardApplet"/></PKG><Libs/></USER></JavaCardProject>'
[IO.File]::WriteAllText((Join-Path $jcide 'TTM_Card.jcproj'),$project,[Text.UTF8Encoding]::new($false))
Write-Output ('Verified CAP: '+$cap)
Write-Output ('SHA-256: '+$hash)
