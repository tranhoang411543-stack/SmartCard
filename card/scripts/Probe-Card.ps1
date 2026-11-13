param([string]$Reader, [switch]$ListReaders, [string]$Java='C:\Program Files\Java\jdk-25\bin\java.exe')
$ErrorActionPreference='Stop'
$source=Join-Path $PSScriptRoot '..\tools\ProbeCard.java'
if($ListReaders -or [string]::IsNullOrWhiteSpace($Reader)){
  & $Java $source --list
} else {
  & $Java $source $Reader
}
if($LASTEXITCODE -ne 0){throw 'PC/SC probe did not complete. No applet was installed or deleted.'}
