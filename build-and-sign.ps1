# HarmonyOS应用构建和签名脚本
# 用于自动构建HAP并确保签名配置正确

param(
    [switch]$Clean,
    [switch]$Release,
    [switch]$Help
)

if ($Help) {
    Write-Host "HarmonyOS应用构建脚本" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "用法:" -ForegroundColor Yellow
    Write-Host "  .\build-and-sign.ps1              # 构建debug版本"
    Write-Host "  .\build-and-sign.ps1 -Release     # 构建release版本"
    Write-Host "  .\build-and-sign.ps1 -Clean       # 清理后构建"
    Write-Host ""
    exit 0
}

Write-Host "==================================" -ForegroundColor Cyan
Write-Host "HarmonyOS应用构建工具" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""

# 检查签名配置
$projectRoot = $PSScriptRoot
$signingDir = Join-Path $projectRoot ".signing"

Write-Host "检查签名配置..." -ForegroundColor Yellow

if (Test-Path $signingDir) {
    $p12Files = Get-ChildItem -Path $signingDir -Filter "*.p12" -ErrorAction SilentlyContinue
    $cerFiles = Get-ChildItem -Path $signingDir -Filter "*.cer" -ErrorAction SilentlyContinue
    $p7bFiles = Get-ChildItem -Path $signingDir -Filter "*.p7b" -ErrorAction SilentlyContinue
    
    if ($p12Files -and $cerFiles -and $p7bFiles) {
        Write-Host "签名文件检查通过" -ForegroundColor Green
        Write-Host "  - P12: $($p12Files.Name)" -ForegroundColor Gray
        Write-Host "  - CER: $($cerFiles.Name)" -ForegroundColor Gray
        Write-Host "  - P7B: $($p7bFiles.Name)" -ForegroundColor Gray
    } else {
        Write-Host "警告: 签名文件不完整" -ForegroundColor Yellow
        Write-Host "  缺少文件: " -ForegroundColor Yellow
        if (!$p12Files) { Write-Host "    - *.p12 (密钥库文件)" -ForegroundColor Red }
        if (!$cerFiles) { Write-Host "    - *.cer (证书文件)" -ForegroundColor Red }
        if (!$p7bFiles) { Write-Host "    - *.p7b (profile文件)" -ForegroundColor Red }
        Write-Host ""
        Write-Host "请运行以下命令获取签名配置说明:" -ForegroundColor Cyan
        Write-Host "  .\generate-debug-signature.ps1" -ForegroundColor White
        Write-Host ""
        Write-Host "继续构建将使用未签名配置..." -ForegroundColor Yellow
        Start-Sleep -Seconds 2
    }
} else {
    Write-Host "警告: 未找到签名目录" -ForegroundColor Yellow
    Write-Host "  请运行: .\generate-debug-signature.ps1" -ForegroundColor Cyan
    Write-Host ""
}

Write-Host ""
Write-Host "开始构建应用..." -ForegroundColor Yellow
Write-Host ""

# 清理构建
if ($Clean) {
    Write-Host "清理构建缓存..." -ForegroundColor Yellow
    if (Test-Path "hvigorw.bat") {
        & .\hvigorw.bat clean
    } else {
        Write-Host "错误: 未找到 hvigorw.bat" -ForegroundColor Red
        exit 1
    }
    Write-Host ""
}

# 构建应用
$buildMode = if ($Release) { "release" } else { "debug" }
Write-Host "构建模式: $buildMode" -ForegroundColor Cyan

if (Test-Path "hvigorw.bat") {
    Write-Host "执行构建命令..." -ForegroundColor Yellow
    & .\hvigorw.bat assembleHap --mode $buildMode
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "==================================" -ForegroundColor Green
        Write-Host "构建成功!" -ForegroundColor Green
        Write-Host "==================================" -ForegroundColor Green
        Write-Host ""
        
        # 查找生成的HAP文件
        $outputDir = Join-Path $projectRoot "entry\build\default\outputs\default"
        if (Test-Path $outputDir) {
            $hapFiles = Get-ChildItem -Path $outputDir -Filter "*.hap" -Recurse -ErrorAction SilentlyContinue
            if ($hapFiles) {
                Write-Host "生成的HAP文件:" -ForegroundColor Cyan
                foreach ($hap in $hapFiles) {
                    Write-Host "  $($hap.FullName)" -ForegroundColor White
                    $size = [math]::Round($hap.Length / 1MB, 2)
                    Write-Host "  大小: ${size}MB" -ForegroundColor Gray
                }
                Write-Host ""
                Write-Host "HAP文件已准备就绪，可以上传到测试服务器" -ForegroundColor Green
            }
        }
    } else {
        Write-Host ""
        Write-Host "==================================" -ForegroundColor Red
        Write-Host "构建失败!" -ForegroundColor Red
        Write-Host "==================================" -ForegroundColor Red
        Write-Host ""
        Write-Host "请检查上方的错误信息" -ForegroundColor Yellow
        exit 1
    }
} else {
    Write-Host "错误: 未找到 hvigorw.bat" -ForegroundColor Red
    Write-Host "请确保在项目根目录执行此脚本" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
