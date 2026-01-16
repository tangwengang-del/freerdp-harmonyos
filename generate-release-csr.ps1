# 生成发布证书CSR文件
# Generate Release Certificate CSR

Write-Host "================================" -ForegroundColor Cyan
Write-Host "生成发布证书CSR文件" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""

# 检查OpenSSL是否可用
$opensslPath = "openssl"
try {
    $null = & $opensslPath version 2>&1
} catch {
    Write-Host "[错误] 未找到 OpenSSL" -ForegroundColor Red
    Write-Host "请安装 OpenSSL 或使用 DevEco Studio 生成" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "下载地址: https://slproweb.com/products/Win32OpenSSL.html" -ForegroundColor Yellow
    exit 1
}

# 设置文件路径
$signingDir = ".signing"
$keyFile = Join-Path $signingDir "release.key"
$csrFile = Join-Path $signingDir "release.csr"

# 确保.signing目录存在
if (-not (Test-Path $signingDir)) {
    New-Item -ItemType Directory -Path $signingDir | Out-Null
}

Write-Host "[步骤 1/3] 生成私钥..." -ForegroundColor Yellow

# 生成2048位RSA私钥
& $opensslPath genrsa -out $keyFile 2048 2>&1 | Out-Null

if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ 私钥生成成功: $keyFile" -ForegroundColor Green
} else {
    Write-Host "✗ 私钥生成失败" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[步骤 2/3] 生成CSR文件..." -ForegroundColor Yellow
Write-Host "请输入证书信息 (可以直接按回车使用默认值):" -ForegroundColor Cyan
Write-Host ""

# 生成CSR文件
& $opensslPath req -new -key $keyFile -out $csrFile

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "✓ CSR文件生成成功: $csrFile" -ForegroundColor Green
} else {
    Write-Host "✗ CSR文件生成失败" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[步骤 3/3] 验证CSR文件..." -ForegroundColor Yellow

# 验证CSR文件
& $opensslPath req -text -noout -verify -in $csrFile | Out-Null

if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ CSR文件验证通过" -ForegroundColor Green
} else {
    Write-Host "✗ CSR文件验证失败" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "================================" -ForegroundColor Green
Write-Host "✓ 发布证书CSR生成完成！" -ForegroundColor Green
Write-Host "================================" -ForegroundColor Green
Write-Host ""
Write-Host "生成的文件:" -ForegroundColor Cyan
Write-Host "  - 私钥: $keyFile" -ForegroundColor White
Write-Host "  - CSR: $csrFile" -ForegroundColor White
Write-Host ""
Write-Host "下一步操作:" -ForegroundColor Yellow
Write-Host "1. 在华为开发者平台上传 $csrFile" -ForegroundColor White
Write-Host "2. 下载生成的发布证书 (.cer文件)" -ForegroundColor White
Write-Host "3. 下载发布Profile (.p7b文件)" -ForegroundColor White
Write-Host "4. 更新 build-profile.json5 配置" -ForegroundColor White
Write-Host ""
Write-Host "⚠️  重要提示:" -ForegroundColor Red
Write-Host "  - 请妥善保管 release.key 私钥文件" -ForegroundColor Yellow
Write-Host "  - 不要将私钥上传到Git或分享给他人" -ForegroundColor Yellow
Write-Host ""
