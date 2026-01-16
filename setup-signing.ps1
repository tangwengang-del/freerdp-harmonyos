# HarmonyOS Signing Setup Script
# Usage: .\setup-signing.ps1

Write-Host "=================================="
Write-Host "HarmonyOS Signing Setup"
Write-Host "=================================="
Write-Host ""

$projectRoot = $PSScriptRoot
$signingDir = Join-Path $projectRoot ".signing"

# Create signing directory
if (!(Test-Path $signingDir)) {
    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
    Write-Host "[OK] Created .signing directory"
} else {
    Write-Host "[OK] .signing directory exists"
}

# Check for signing files
$p12Files = Get-ChildItem -Path $signingDir -Filter "*.p12" -ErrorAction SilentlyContinue
$cerFiles = Get-ChildItem -Path $signingDir -Filter "*.cer" -ErrorAction SilentlyContinue
$p7bFiles = Get-ChildItem -Path $signingDir -Filter "*.p7b" -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Signing files status:"
if ($p12Files) {
    Write-Host "[OK] Found P12 file: $($p12Files.Name)"
} else {
    Write-Host "[!] Missing P12 file (keystore)"
}

if ($cerFiles) {
    Write-Host "[OK] Found CER file: $($cerFiles.Name)"
} else {
    Write-Host "[!] Missing CER file (certificate)"
}

if ($p7bFiles) {
    Write-Host "[OK] Found P7B file: $($p7bFiles.Name)"
} else {
    Write-Host "[!] Missing P7B file (profile)"
}

Write-Host ""
if ($p12Files -and $cerFiles -and $p7bFiles) {
    Write-Host "=================================="
    Write-Host "Signing configuration complete!"
    Write-Host "=================================="
    Write-Host ""
    Write-Host "You can now build your app:"
    Write-Host "  .\hvigorw.bat assembleHap"
    Write-Host ""
} else {
    Write-Host "=================================="
    Write-Host "Signing files needed"
    Write-Host "=================================="
    Write-Host ""
    Write-Host "To get signing files:"
    Write-Host ""
    Write-Host "Option 1: From Huawei Developer Console"
    Write-Host "  1. Visit: https://developer.huawei.com/consumer/cn/service/josp/agc/index.html"
    Write-Host "  2. Login and create/select your app"
    Write-Host "  3. Generate debug certificate"
    Write-Host "  4. Download and place files in .signing folder:"
    Write-Host "     - debug.p12"
    Write-Host "     - debug.cer"
    Write-Host "     - debug.p7b"
    Write-Host ""
    Write-Host "Option 2: From DevEco Studio"
    Write-Host "  1. Open project in DevEco Studio"
    Write-Host "  2. File -> Project Structure -> Signing Configs"
    Write-Host "  3. Check 'Automatically generate signature'"
    Write-Host "  4. Copy generated files to .signing folder"
    Write-Host ""
    Write-Host "After adding files, run this script again to verify."
    Write-Host ""
}
