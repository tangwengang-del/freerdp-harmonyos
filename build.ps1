# Simple HarmonyOS Build Script
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "HarmonyOS Application Build" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

$projectRoot = $PSScriptRoot

# Check signing files
Write-Host "Checking signing files..." -ForegroundColor Yellow
$signingOk = $true

if (Test-Path ".signing\debug.p12") {
    Write-Host "[OK] debug.p12" -ForegroundColor Green
} else {
    Write-Host "[FAIL] debug.p12 not found" -ForegroundColor Red
    $signingOk = $false
}

if (Test-Path ".signing\debug.cer") {
    Write-Host "[OK] debug.cer" -ForegroundColor Green
} else {
    Write-Host "[FAIL] debug.cer not found" -ForegroundColor Red
    $signingOk = $false
}

if (Test-Path ".signing\debug.p7b") {
    Write-Host "[OK] debug.p7b" -ForegroundColor Green
} else {
    Write-Host "[FAIL] debug.p7b not found" -ForegroundColor Red
    $signingOk = $false
}

Write-Host ""

if (-not $signingOk) {
    Write-Host "ERROR: Signing files are missing!" -ForegroundColor Red
    exit 1
}

# Check for build tools
Write-Host "Checking build tools..." -ForegroundColor Yellow

$buildCommand = $null

# Check for ohpm
$ohpmPath = Get-Command ohpm -ErrorAction SilentlyContinue
if ($ohpmPath) {
    Write-Host "[OK] Found ohpm" -ForegroundColor Green
    $buildCommand = "ohpm"
}

# Check for hvigorw in oh_modules
$hvigorwPath = "oh_modules\.bin\hvigorw.js"
if (Test-Path $hvigorwPath) {
    Write-Host "[OK] Found hvigorw.js" -ForegroundColor Green
    
    # Check for node
    $nodePath = Get-Command node -ErrorAction SilentlyContinue
    if ($nodePath) {
        Write-Host "[OK] Found node" -ForegroundColor Green
        $buildCommand = "node"
    } else {
        Write-Host "[WARN] Node.js not found in PATH" -ForegroundColor Yellow
        
        # Try common Node.js installation paths
        $nodeLocations = @(
            "C:\Program Files\nodejs\node.exe",
            "C:\Program Files (x86)\nodejs\node.exe",
            "$env:LOCALAPPDATA\Programs\nodejs\node.exe",
            "$env:APPDATA\npm\node.exe"
        )
        
        foreach ($loc in $nodeLocations) {
            if (Test-Path $loc) {
                Write-Host "[OK] Found node at: $loc" -ForegroundColor Green
                $buildCommand = $loc
                break
            }
        }
    }
}

Write-Host ""

if (-not $buildCommand) {
    Write-Host "=====================================" -ForegroundColor Red
    Write-Host "Build tools not found!" -ForegroundColor Red
    Write-Host "=====================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "You need either:" -ForegroundColor Yellow
    Write-Host "  1. ohpm (HarmonyOS Package Manager)" -ForegroundColor White
    Write-Host "  2. Node.js + hvigorw" -ForegroundColor White
    Write-Host ""
    Write-Host "Please install Node.js from: https://nodejs.org/" -ForegroundColor Cyan
    Write-Host "Or use DevEco Studio to build this project." -ForegroundColor Cyan
    exit 1
}

# Build the project
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Starting build..." -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

if ($buildCommand -eq "ohpm") {
    Write-Host "Building with ohpm..." -ForegroundColor Yellow
    & ohpm build
} elseif ($buildCommand -like "*node*") {
    Write-Host "Building with hvigorw..." -ForegroundColor Yellow
    if ($buildCommand -eq "node") {
        & node $hvigorwPath assembleHap
    } else {
        & $buildCommand $hvigorwPath assembleHap
    }
} else {
    Write-Host "Unknown build command" -ForegroundColor Red
    exit 1
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=====================================" -ForegroundColor Green
    Write-Host "Build successful!" -ForegroundColor Green
    Write-Host "=====================================" -ForegroundColor Green
    Write-Host ""
    
    # Find HAP files
    $hapFiles = Get-ChildItem -Path "entry\build" -Filter "*.hap" -Recurse -ErrorAction SilentlyContinue
    if ($hapFiles) {
        Write-Host "Generated HAP files:" -ForegroundColor Cyan
        foreach ($hap in $hapFiles) {
            Write-Host ""
            Write-Host "  File: $($hap.FullName)" -ForegroundColor White
            $sizeMB = [math]::Round($hap.Length / 1MB, 2)
            Write-Host "  Size: ${sizeMB} MB" -ForegroundColor Gray
        }
        Write-Host ""
        Write-Host "You can now upload the HAP file to your test server." -ForegroundColor Green
    } else {
        Write-Host "Warning: No HAP files found in entry\build" -ForegroundColor Yellow
        Write-Host "Check: entry\build\default\outputs\default\" -ForegroundColor Yellow
        
        $altPath = "entry\build\default\outputs\default"
        if (Test-Path $altPath) {
            $altHaps = Get-ChildItem -Path $altPath -Filter "*.hap" -ErrorAction SilentlyContinue
            if ($altHaps) {
                Write-Host ""
                Write-Host "Found HAP files:" -ForegroundColor Cyan
                foreach ($hap in $altHaps) {
                    Write-Host "  $($hap.FullName)" -ForegroundColor White
                }
            }
        }
    }
} else {
    Write-Host ""
    Write-Host "=====================================" -ForegroundColor Red
    Write-Host "Build failed!" -ForegroundColor Red
    Write-Host "=====================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please check the error messages above." -ForegroundColor Yellow
    exit 1
}
