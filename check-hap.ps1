# Check HAP file signature
Write-Host "Checking for HAP files..." -ForegroundColor Cyan
Write-Host ""

$searchPaths = @(
    "entry\build\default\outputs\default",
    "entry\build\default\outputs\debug",
    "entry\build\outputs\default",
    "entry\build\outputs\debug"
)

$foundFiles = @()

foreach ($path in $searchPaths) {
    if (Test-Path $path) {
        $haps = Get-ChildItem -Path $path -Filter "*.hap" -ErrorAction SilentlyContinue
        if ($haps) {
            $foundFiles += $haps
        }
    }
}

if ($foundFiles.Count -eq 0) {
    Write-Host "No HAP files found!" -ForegroundColor Red
    Write-Host "Please build the project first." -ForegroundColor Yellow
    exit 1
}

Write-Host "Found HAP files:" -ForegroundColor Green
Write-Host ""

foreach ($hap in $foundFiles) {
    Write-Host "File: $($hap.Name)" -ForegroundColor White
    Write-Host "Path: $($hap.FullName)" -ForegroundColor Gray
    
    $sizeMB = [math]::Round($hap.Length / 1MB, 2)
    Write-Host "Size: ${sizeMB} MB" -ForegroundColor Gray
    
    # Check if signed
    if ($hap.Name -match "-signed") {
        Write-Host "Status: SIGNED ✓" -ForegroundColor Green
    } else {
        Write-Host "Status: UNSIGNED ✗" -ForegroundColor Red
    }
    
    Write-Host "Modified: $($hap.LastWriteTime)" -ForegroundColor Gray
    Write-Host ""
}

Write-Host "================================================" -ForegroundColor Cyan
if ($foundFiles | Where-Object { $_.Name -match "-signed" }) {
    Write-Host "Signed HAP file is ready for upload!" -ForegroundColor Green
} else {
    Write-Host "Warning: HAP file appears to be unsigned!" -ForegroundColor Yellow
    Write-Host "Please check signing configuration in DevEco Studio." -ForegroundColor Yellow
}
