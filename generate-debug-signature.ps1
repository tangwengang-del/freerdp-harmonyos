# HarmonyOS应用自动签名脚本
# 用于在虚拟服务器环境中生成debug签名

Write-Host "==================================" -ForegroundColor Cyan
Write-Host "HarmonyOS应用自动签名工具" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""

# 设置变量
$projectRoot = $PSScriptRoot
$signingDir = Join-Path $projectRoot ".signing"
$p12File = Join-Path $signingDir "debug.p12"
$cerFile = Join-Path $signingDir "debug.cer"
$p7bFile = Join-Path $signingDir "debug.p7b"

# 检查签名目录
if (!(Test-Path $signingDir)) {
    Write-Host "创建签名目录: $signingDir" -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
}

# 检查是否已存在签名文件
if ((Test-Path $p12File) -and (Test-Path $cerFile) -and (Test-Path $p7bFile)) {
    Write-Host "签名文件已存在，跳过生成" -ForegroundColor Green
    Write-Host "  - $p12File" -ForegroundColor Gray
    Write-Host "  - $cerFile" -ForegroundColor Gray
    Write-Host "  - $p7bFile" -ForegroundColor Gray
    Write-Host ""
    Write-Host "如需重新生成，请先删除 .signing 目录中的文件" -ForegroundColor Yellow
    exit 0
}

Write-Host "检查签名工具..." -ForegroundColor Yellow

# 查找DevEco Studio SDK路径
$possibleSdkPaths = @(
    "$env:USERPROFILE\AppData\Local\Huawei\Sdk",
    "$env:USERPROFILE\Huawei\Sdk",
    "C:\Huawei\Sdk",
    "$env:DEVECO_SDK_HOME"
)

$sdkPath = $null
foreach ($path in $possibleSdkPaths) {
    if ($path -and (Test-Path $path)) {
        $sdkPath = $path
        break
    }
}

if ($sdkPath) {
    Write-Host "找到SDK路径: $sdkPath" -ForegroundColor Green
    
    # 查找签名工具
    $signToolPaths = @(
        Join-Path $sdkPath "openharmony\*\toolchains\lib\hap-sign-tool.jar",
        Join-Path $sdkPath "HarmonyOS-NEXT\*\toolchains\lib\hap-sign-tool.jar"
    )
    
    $signTool = $null
    foreach ($pattern in $signToolPaths) {
        $found = Get-ChildItem $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $signTool = $found.FullName
            break
        }
    }
    
    if ($signTool) {
        Write-Host "找到签名工具: $signTool" -ForegroundColor Green
    }
} else {
    Write-Host "未找到HarmonyOS SDK" -ForegroundColor Red
}

Write-Host ""
Write-Host "==================================" -ForegroundColor Cyan
Write-Host "签名配置说明" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "由于在虚拟服务器环境中，建议使用以下方式之一：" -ForegroundColor Yellow
Write-Host ""
Write-Host "方案1: 使用华为开发者平台自动签名" -ForegroundColor Green
Write-Host "  1. 访问 https://developer.huawei.com/consumer/cn/service/josp/agc/index.html"
Write-Host "  2. 登录并创建应用"
Write-Host "  3. 在 '我的项目' -> '证书' 中生成调试证书"
Write-Host "  4. 下载以下文件到 .signing 目录:"
Write-Host "     - debug.p12 (密钥库文件)"
Write-Host "     - debug.cer (证书文件)"
Write-Host "     - debug.p7b (profile文件)"
Write-Host ""
Write-Host "方案2: 从本地DevEco Studio复制" -ForegroundColor Green
Write-Host "  1. 在有DevEco Studio的机器上打开项目"
Write-Host "  2. File -> Project Structure -> Signing Configs"
Write-Host "  3. 勾选 'Automatically generate signature'"
Write-Host "  4. 复制生成的签名文件到服务器的 .signing 目录"
Write-Host ""
Write-Host "方案3: 使用云调试的自动签名" -ForegroundColor Green
Write-Host "  1. 在DevEco Studio中配置云调试"
Write-Host "  2. 连接到远程设备时会自动处理签名"
Write-Host ""
Write-Host "配置完成后，运行以下命令构建应用:" -ForegroundColor Cyan
Write-Host "  hvigorw assembleHap" -ForegroundColor White
Write-Host ""

# 创建签名配置模板
$templateContent = @"
# HarmonyOS签名配置模板

## 签名文件清单

将以下文件放置在此目录中：

1. debug.p12 - 密钥库文件
   - Store Password: (加密后的密码)
   - Key Alias: debugKey
   - Key Password: (加密后的密码)

2. debug.cer - 数字证书文件
   - 包含公钥信息

3. debug.p7b - Provision Profile文件
   - 包含应用签名配置信息

## 获取签名文件

### 从华为开发者平台获取：
https://developer.huawei.com/consumer/cn/service/josp/agc/index.html

### 从DevEco Studio生成：
File -> Project Structure -> Signing Configs -> Automatically generate signature

## 安全提示

- 不要将签名文件提交到版本控制系统
- .signing 目录已添加到 .gitignore
- 生产环境请使用正式签名证书
"@

$templatePath = Join-Path $signingDir "签名文件说明.txt"
Set-Content -Path $templatePath -Value $templateContent -Encoding UTF8

Write-Host "已创建签名配置模板: $templatePath" -ForegroundColor Green
Write-Host ""
