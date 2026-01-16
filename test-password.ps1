# Test P12 Password Script
Write-Host "Testing P12 keystore password..." -ForegroundColor Cyan
Write-Host ""

$p12File = ".signing\debug.p12"

if (!(Test-Path $p12File)) {
    Write-Host "Error: debug.p12 not found" -ForegroundColor Red
    exit 1
}

# Common passwords to test
$passwords = @(
    "",              # Empty password
    "123456",        # Very common
    "000000",
    "password",
    "debugKey",
    "tangwengang",
    "Huawei123",
    "harmonyos"
)

Write-Host "Testing common passwords..." -ForegroundColor Yellow
Write-Host "(This will help identify the correct password)" -ForegroundColor Gray
Write-Host ""

foreach ($pwd in $passwords) {
    if ($pwd -eq "") {
        $displayPwd = "(empty)"
    } else {
        $displayPwd = $pwd
    }
    
    Write-Host "Testing: $displayPwd" -ForegroundColor White
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Unfortunately, PowerShell cannot directly test" -ForegroundColor Yellow
Write-Host "P12 passwords without Java keytool." -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Please try to remember:" -ForegroundColor Cyan
Write-Host "  - What password did you set when generating the certificate?" -ForegroundColor White
Write-Host "  - Did you use the default password?" -ForegroundColor White
Write-Host "  - Was it left empty?" -ForegroundColor White
Write-Host ""
Write-Host "Common scenarios:" -ForegroundColor Yellow
Write-Host "  1. Empty password (just press Enter)" -ForegroundColor White
Write-Host "  2. 123456 (very common default)" -ForegroundColor White
Write-Host "  3. The password shown in AGC console" -ForegroundColor White
Write-Host ""
