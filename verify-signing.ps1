# Verify Signing Configuration
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Signing Configuration Verification" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

$allGood = $true

# Check signing files
Write-Host "1. Checking signing files..." -ForegroundColor Yellow
Write-Host ""

$files = @(
    @{Name="debug.p12"; Path=".signing\debug.p12"; MinSize=1000},
    @{Name="debug.cer"; Path=".signing\debug.cer"; MinSize=2000},
    @{Name="debug.p7b"; Path=".signing\debug.p7b"; MinSize=3000}
)

foreach ($file in $files) {
    if (Test-Path $file.Path) {
        $fileInfo = Get-Item $file.Path
        $sizeKB = [math]::Round($fileInfo.Length / 1KB, 1)
        
        if ($fileInfo.Length -ge $file.MinSize) {
            Write-Host "  [OK] $($file.Name) - ${sizeKB} KB" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] $($file.Name) - ${sizeKB} KB (seems too small)" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  [FAIL] $($file.Name) - NOT FOUND" -ForegroundColor Red
        $allGood = $false
    }
}

Write-Host ""

# Check build-profile.json5
Write-Host "2. Checking signing configuration..." -ForegroundColor Yellow
Write-Host ""

if (Test-Path "build-profile.json5") {
    $content = Get-Content "build-profile.json5" -Raw
    
    $checks = @(
        @{Name="signingConfigs section"; Pattern="signingConfigs"},
        @{Name="storeFile path"; Pattern=".signing/debug.p12"},
        @{Name="certpath"; Pattern=".signing/debug.cer"},
        @{Name="profile path"; Pattern=".signing/debug.p7b"}
    )
    
    foreach ($check in $checks) {
        if ($content -match $check.Pattern) {
            Write-Host "  [OK] $($check.Name)" -ForegroundColor Green
        } else {
            Write-Host "  [FAIL] $($check.Name) - not found" -ForegroundColor Red
            $allGood = $false
        }
    }
} else {
    Write-Host "  [FAIL] build-profile.json5 not found" -ForegroundColor Red
    $allGood = $false
}

Write-Host ""

# Check build tools
Write-Host "3. Checking build tools..." -ForegroundColor Yellow
Write-Host ""

$hasDevEco = $false
$hasNode = $false
$hasOhpm = $false

# Check for DevEco Studio
$devecoPath = "C:\Program Files\Huawei\DevEco Studio"
if (Test-Path $devecoPath) {
    Write-Host "  [OK] DevEco Studio found" -ForegroundColor Green
    $hasDevEco = $true
}

# Check for Node.js
$nodePath = Get-Command node -ErrorAction SilentlyContinue
if ($nodePath) {
    $nodeVersion = & node --version 2>$null
    Write-Host "  [OK] Node.js found - $nodeVersion" -ForegroundColor Green
    $hasNode = $true
}

# Check for ohpm
$ohpmPath = Get-Command ohpm -ErrorAction SilentlyContinue
if ($ohpmPath) {
    Write-Host "  [OK] ohpm found" -ForegroundColor Green
    $hasOhpm = $true
}

if (-not $hasDevEco -and -not $hasNode -and -not $hasOhpm) {
    Write-Host "  [WARN] No build tools found" -ForegroundColor Yellow
    Write-Host "         Install Node.js or use DevEco Studio" -ForegroundColor Gray
}

Write-Host ""

# Check project structure
Write-Host "4. Checking project structure..." -ForegroundColor Yellow
Write-Host ""

$projectFiles = @(
    "hvigorfile.ts",
    "oh-package.json5",
    "AppScope\app.json5",
    "entry\src\main"
)

foreach ($pf in $projectFiles) {
    if (Test-Path $pf) {
        Write-Host "  [OK] $pf" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] $pf - not found" -ForegroundColor Yellow
    }
}

Write-Host ""

# Summary
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Verification Summary" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

if ($allGood) {
    Write-Host "✓ Signing configuration is complete!" -ForegroundColor Green
    Write-Host ""
    
    if ($hasDevEco) {
        Write-Host "You can now build with DevEco Studio:" -ForegroundColor Cyan
        Write-Host "  1. Open DevEco Studio" -ForegroundColor White
        Write-Host "  2. Open project: $PWD" -ForegroundColor White
        Write-Host "  3. Build → Build Hap(s)" -ForegroundColor White
    } elseif ($hasNode) {
        Write-Host "You can now build with command line:" -ForegroundColor Cyan
        Write-Host "  .\build.ps1" -ForegroundColor White
    } else {
        Write-Host "To build, you need:" -ForegroundColor Yellow
        Write-Host "  - Install Node.js (https://nodejs.org/)" -ForegroundColor White
        Write-Host "  - Or use DevEco Studio" -ForegroundColor White
    }
} else {
    Write-Host "✗ Some issues found" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please check the errors above" -ForegroundColor Yellow
}

Write-Host ""
