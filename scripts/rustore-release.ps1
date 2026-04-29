$ErrorActionPreference = "Stop"

function Write-Info([string]$msg) {
  Write-Host $msg
}

function Read-Props([string]$path) {
  $map = @{}
  foreach ($line in (Get-Content $path)) {
    if ($line -match "^\s*#") { continue }
    if ($line -match "^\s*$") { continue }
    $parts = $line.Split("=", 2)
    if ($parts.Length -eq 2) {
      $map[$parts[0].Trim()] = $parts[1]
    }
  }
  return $map
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

$propsPath = Join-Path $root "keystore.properties"
if (!(Test-Path $propsPath)) {
  throw "keystore.properties not found. Run scripts/play-release.ps1 first to generate signing config."
}

$props = Read-Props $propsPath
foreach ($k in @("storeFile","storePassword","keyAlias")) {
  if (-not $props.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($props[$k])) {
    throw "keystore.properties is missing '$k'."
  }
}

$storeFile = $props["storeFile"]
$storePass = $props["storePassword"]
$alias = $props["keyAlias"]
$ksPath = Join-Path $root $storeFile

if (!(Test-Path $ksPath)) {
  throw "Keystore file not found: $ksPath (from storeFile=$storeFile)"
}

$certOut = Join-Path $root "rustore_cert.pem"
Write-Info "Exporting signing certificate to $certOut"
& keytool -export -rfc -alias $alias -keystore $ksPath -storepass $storePass -file $certOut

Write-Info "Building signed release APK: gradle :app:assembleRelease"
& gradle :app:assembleRelease

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (!(Test-Path $apk)) {
  throw "Release APK not found at expected path: $apk"
}

Write-Info "Verifying APK signature (jarsigner)"
& jarsigner -verify -verbose -certs $apk | Out-Host

Write-Info ""
Write-Info "OK. Files for RuStore:"
Write-Info "  APK:  $apk"
Write-Info "  CERT: $certOut"

