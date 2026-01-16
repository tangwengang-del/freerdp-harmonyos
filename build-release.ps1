# Build Release Version HAP
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Building Release Version" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Clean build
Write-Host "Cleaning project..." -ForegroundColor Yellow
if (Test-Path "hvigorw.bat") {
    cmd /c "hvigorw.bat clean"
} else {
    Write-Host "Error: hvigorw.bat not found" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Building Release HAP..." -ForegroundColor Yellow
Write-Host ""

# Build Release
cmd /c "hvigorw.bat --mode module -p product=default -p buildMode=release assembleHap"

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "Build Successful!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    
    # Find HAP files
    $haps = Get-ChildItem -Path "entry\build" -Recurse -Filter "*-signed.hap" -ErrorAction SilentlyContinue
    if ($haps) {
        Write-Host "Generated HAP files:" -ForegroundColor Cyan
        foreach ($hap in $haps) {
            Write-Host ""
            Write-Host "  File: $($hap.Name)" -ForegroundColor White
            Write-Host "  Path: $($hap.FullName)" -ForegroundColor Gray
            $sizeMB = [math]::Round($hap.Length / 1MB, 2)
            Write-Host "  Size: ${sizeMB} MB" -ForegroundColor Gray
            Write-Host "  Time: $($hap.LastWriteTime)" -ForegroundColor Gray
        }
        Write-Host ""
        Write-Host "Upload the signed HAP file to your server!" -ForegroundColor Green
    }
} else {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "Build Failed!" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    exit 1
}
