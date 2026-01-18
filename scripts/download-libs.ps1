param(
    [string]$Version = "v1.2.3",
    [string]$Repo = "tangwengang-del/freerdp-harmonyos"
)

$root = (Get-Item $PSScriptRoot).Parent.FullName
$targetDir = Join-Path $root "entry\libs\arm64-v8a"
$tempZip = Join-Path $root "freerdp-libs-temp.zip"

Write-Host "=== Downloading FreeRDP HarmonyOS libs ($Version) ===" -ForegroundColor Cyan
$url = "https://github.com/$Repo/releases/download/$Version/freerdp-harmonyos-libs.zip"
Write-Host "URL: $url"

Invoke-WebRequest -Uri $url -OutFile $tempZip

Write-Host "=== Extracting to $targetDir ===" -ForegroundColor Cyan
if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Path $targetDir -Force }
Get-ChildItem -Path $targetDir -Filter "*.so*" -ErrorAction SilentlyContinue | Remove-Item -Force

$extractPath = Join-Path $root "temp_libs"
if (Test-Path $extractPath) { Remove-Item -Path $extractPath -Recurse -Force }
Expand-Archive -Path $tempZip -DestinationPath $extractPath -Force

$extractedLibs = Join-Path $extractPath "arm64-v8a"
if (Test-Path $extractedLibs) {
    Copy-Item -Path "$extractedLibs\*.so*" -Destination $targetDir -Force
    Write-Host "Success: Libs synchronized to entry/libs/arm64-v8a" -ForegroundColor Green
} else {
    Write-Host "Error: Directory structure mismatch (arm64-v8a not found)" -ForegroundColor Red
}

if (Test-Path $tempZip) { Remove-Item -Path $tempZip -Force }
if (Test-Path $extractPath) { Remove-Item -Path $extractPath -Recurse -Force }

Write-Host "Configuration completed." -ForegroundColor Green
