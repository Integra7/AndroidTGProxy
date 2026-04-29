$ErrorActionPreference = "Stop"

function Write-Info([string]$msg) {
  Write-Host $msg
}

function New-RandomPassword([int]$length = 24) {
  $chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#%_-"
  $bytes = New-Object "System.Byte[]" $length
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $sb = New-Object System.Text.StringBuilder
  foreach ($b in $bytes) {
    [void]$sb.Append($chars[$b % $chars.Length])
  }
  $sb.ToString()
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

$keystorePath = Join-Path $root "release.keystore"
$propsPath = Join-Path $root "keystore.properties"
$storeFileForProps = "release.keystore"
$alias = if ($env:KEY_ALIAS) { $env:KEY_ALIAS } else { "release" }
$showPasswords = ($env:SHOW_PASSWORDS -eq "1")
$autoRegen = ($env:AUTO_REGEN -eq "1")

$storePassword = if ($env:STORE_PASSWORD) { $env:STORE_PASSWORD } else { "" }
$keyPassword = if ($env:KEY_PASSWORD) { $env:KEY_PASSWORD } else { "" }

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

if ((Test-Path $keystorePath) -and (Test-Path $propsPath)) {
  $props = Read-Props $propsPath
  if ($props.ContainsKey("storeFile")) {
    $storeFileForProps = $props["storeFile"]
    $keystorePath = Join-Path $root $storeFileForProps
  }
  if ([string]::IsNullOrWhiteSpace($storePassword) -and $props.ContainsKey("storePassword")) { $storePassword = $props["storePassword"] }
  if ([string]::IsNullOrWhiteSpace($keyPassword) -and $props.ContainsKey("keyPassword")) { $keyPassword = $props["keyPassword"] }
  if (($alias -eq "release") -and $props.ContainsKey("keyAlias")) { $alias = $props["keyAlias"] }
}

function Backup-File([string]$path) {
  if (Test-Path $path) {
    $ts = Get-Date -Format "yyyyMMdd-HHmmss"
    $bak = "$path.bak.$ts"
    Copy-Item -Path $path -Destination $bak -Force
    Write-Info "Backed up $path -> $bak"

    for ($i = 0; $i -lt 5; $i++) {
      try {
        Remove-Item -Path $path -Force
        break
      } catch {
        if ($i -eq 4) { throw }
        Start-Sleep -Seconds 1
      }
    }
  }
}

function Test-KeystorePassword([string]$ks, [string]$pass, [string]$aliasToCheck) {
  if ([string]::IsNullOrWhiteSpace($pass)) { return $false }
  $out = & keytool -list -keystore $ks -storepass $pass -alias $aliasToCheck 2>&1
  return ($LASTEXITCODE -eq 0)
}

if ((Test-Path $keystorePath) -and (Test-Path $propsPath)) {
  if (-not (Test-KeystorePassword $keystorePath $storePassword $alias)) {
    if ($autoRegen) {
      Write-Info "Keystore password mismatch detected. AUTO_REGEN=1, regenerating signing key."
      Backup-File $propsPath
      $storePassword = ""
      $keyPassword = ""
      $ts = Get-Date -Format "yyyyMMdd-HHmmss"
      $storeFileForProps = "release.regen.$ts.keystore"
      $keystorePath = Join-Path $root $storeFileForProps
      Write-Info "Will generate new keystore: $keystorePath"
    } else {
      throw "Keystore password mismatch: release.keystore does not open with password from keystore.properties. Set correct STORE_PASSWORD/KEY_PASSWORD or run with AUTO_REGEN=1 to regenerate (will backup old files)."
    }
  }
}

if (!(Test-Path $keystorePath)) {
  if ([string]::IsNullOrWhiteSpace($storePassword)) { $storePassword = New-RandomPassword 28 }
  if ([string]::IsNullOrWhiteSpace($keyPassword)) { $keyPassword = $storePassword }

  Write-Info "Generating release keystore at $keystorePath"
  & keytool -genkeypair -v `
    -keystore $keystorePath `
    -alias $alias `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $storePassword `
    -keypass $keyPassword `
    -dname "CN=AndroidTGProxy, OU=Dev, O=AndroidTGProxy, L=Unknown, S=Unknown, C=US"
} else {
  Write-Info "Keystore already exists: $keystorePath"
}

if (!(Test-Path $propsPath)) {
  if ([string]::IsNullOrWhiteSpace($storePassword) -or [string]::IsNullOrWhiteSpace($keyPassword)) {
    throw "keystore.properties is missing, and STORE_PASSWORD/KEY_PASSWORD are not set. Set env vars and re-run, or restore keystore.properties from backup."
  }

  Write-Info "Writing keystore.properties at $propsPath"
  @"
storeFile=$storeFileForProps
storePassword=$storePassword
keyAlias=$alias
keyPassword=$keyPassword
"@ | Set-Content -Path $propsPath -Encoding ASCII -NoNewline
} else {
  Write-Info "keystore.properties already exists: $propsPath"
}

Write-Info "Building Play bundle (AAB): gradle :app:bundleRelease"
& gradle :app:bundleRelease

$aab = Join-Path $root "app\build\outputs\bundle\release\app-release.aab"
if (!(Test-Path $aab)) {
  throw "AAB not found at expected path: $aab"
}

Write-Info "Verifying AAB signature"
& jarsigner -verify -verbose -certs $aab | Out-Host

Write-Info ""
Write-Info "OK. Built AAB:"
Write-Info "  $aab"
Write-Info ""
Write-Info "IMPORTANT: Back up these secrets (do NOT commit them):"
Write-Info "  Keystore: $keystorePath"
Write-Info "  Properties: $propsPath"
Write-Info "  Alias: $alias"
if ($showPasswords) {
  Write-Info "  Store password: $storePassword"
  Write-Info "  Key password: $keyPassword"
} else {
  Write-Info "  Passwords: (hidden) set SHOW_PASSWORDS=1 to print"
}

