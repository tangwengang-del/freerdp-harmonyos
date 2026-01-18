param(
  [string]$NdkRoot = $env:OHOS_NDK_HOME,
  [string]$Arch = "arm64-v8a",
  [string]$BuildType = "Release",
  [string]$OpenSslRoot = "",
  [string]$ZlibRoot = "",
  [string]$CJsonRoot = "",
  [switch]$WithFFmpeg,
  [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")

function Resolve-NdkRoot {
  param([string]$Provided)
  if ($Provided) { return $Provided }

  $candidates = @()
  if ($env:OHOS_NDK_HOME) { $candidates += $env:OHOS_NDK_HOME }

  $devEcoConfig = Join-Path $env:APPDATA "Huawei\DevEcoStudio6.0\options\other.xml"
  if (Test-Path $devEcoConfig) {
    $content = Get-Content $devEcoConfig -Raw
    if ($content -match 'arkuix\.sdk\.location":\s*"([^"]+)"') {
      $sdkPath = $Matches[1] -replace '\\\\','\'
      $candidates += $sdkPath
    }
  }

  $candidates += @(
    "C:\huawei\Sdk",
    "C:\Users\Administrator\AppData\Local\Huawei\Sdk"
  )

  foreach ($base in $candidates) {
    if (-not (Test-Path $base)) { continue }
    $toolchain = Get-ChildItem -Path $base -Filter "ohos.toolchain.cmake" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($toolchain) {
      $cmakeDir = Split-Path $toolchain.FullName -Parent
      $buildDir = Split-Path $cmakeDir -Parent
      $ndkRoot = Split-Path $buildDir -Parent
      return $ndkRoot
    }
  }
  return $null
}

$NdkRoot = Resolve-NdkRoot $NdkRoot
if (-not $NdkRoot) {
  throw "未指定 OHOS NDK 路径。请设置 OHOS_NDK_HOME 或传入 -NdkRoot。"
}

$toolchain = Join-Path $NdkRoot "build\cmake\ohos.toolchain.cmake"
if (-not (Test-Path $toolchain)) {
  throw "未找到 ohos.toolchain.cmake: $toolchain"
}

$freerdpSrc = Join-Path $root "entry\libs\FreeRDP-3.10.3"
if (-not (Test-Path $freerdpSrc)) {
  throw "未找到 FreeRDP 源码目录: $freerdpSrc"
}

$buildRoot = Join-Path $root "native-build"
$installRoot = Join-Path $root "native-install"
$freerdpBuild = Join-Path $buildRoot "freerdp\$Arch"
$freerdpInstall = Join-Path $installRoot "freerdp\$Arch"
$wrapperBuild = Join-Path $buildRoot "wrapper\$Arch"
$depsInstall = Join-Path $installRoot "deps\$Arch"

if ($Clean) {
  Remove-Item -Recurse -Force $buildRoot, $installRoot -ErrorAction SilentlyContinue
}

if (-not $OpenSslRoot) {
  $defaultOpenSsl = Join-Path $depsInstall "openssl"
  if (Test-Path $defaultOpenSsl) {
    $OpenSslRoot = $defaultOpenSsl
  }
}

if (-not $OpenSslRoot) {
  throw "未设置 OpenSSL 路径。请先编译依赖并设置 -OpenSslRoot 或确保 $depsInstall\\openssl 存在。"
}

if (-not (Test-Path (Join-Path $OpenSslRoot "include"))) {
  throw "OpenSSL include 目录不存在: $OpenSslRoot\\include"
}

if (-not $ZlibRoot) {
  $defaultZlib = Join-Path $depsInstall "zlib"
  if (Test-Path $defaultZlib) {
    $ZlibRoot = $defaultZlib
  }
}

if (-not $CJsonRoot) {
  $defaultCJson = Join-Path $depsInstall "cjson"
  if (Test-Path $defaultCJson) {
    $CJsonRoot = $defaultCJson
  }
}

New-Item -ItemType Directory -Path $freerdpBuild, $freerdpInstall, $wrapperBuild -Force | Out-Null

Write-Host "=== 构建 FreeRDP ($Arch, $BuildType) ===" -ForegroundColor Cyan

$freerdpArgs = @(
  "-S", $freerdpSrc,
  "-B", $freerdpBuild,
  "-G", "Ninja",
  "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
  "-DOHOS_ARCH=$Arch",
  "-DCMAKE_BUILD_TYPE=$BuildType",
  "-DCMAKE_INSTALL_PREFIX=$freerdpInstall",
  "-DBUILD_SHARED_LIBS=ON",
  "-DWITH_CLIENT=ON",
  "-DWITH_CLIENT_COMMON=ON",
  "-DWITH_SERVER=OFF",
  "-DWITH_SAMPLE=OFF",
  "-DWITH_CUPS=OFF",
  "-DWITH_PULSE=OFF",
  "-DWITH_ALSA=OFF",
  "-DWITH_X11=OFF",
  "-DWITH_WAYLAND=OFF",
  "-DWITH_GSTREAMER_0_10=OFF",
  "-DWITH_GSTREAMER_1_0=OFF",
  "-DWITH_FFMPEG=" + ($(if ($WithFFmpeg) { "ON" } else { "OFF" })),
  "-DWITH_OPENSSL=ON",
  "-DOPENSSL_ROOT_DIR=$OpenSslRoot"
)

if ($ZlibRoot) {
  $freerdpArgs += "-DZLIB_ROOT=$ZlibRoot"
}
if ($CJsonRoot) {
  $freerdpArgs += "-DcJSON_DIR=$CJsonRoot"
}

cmake @freerdpArgs
cmake --build $freerdpBuild --parallel
cmake --install $freerdpBuild

Write-Host "=== 构建 NAPI Wrapper ($Arch, $BuildType) ===" -ForegroundColor Cyan

$wrapperSrc = Join-Path $root "entry\src\main\cpp"
$wrapperArgs = @(
  "-S", $wrapperSrc,
  "-B", $wrapperBuild,
  "-G", "Ninja",
  "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
  "-DOHOS_ARCH=$Arch",
  "-DCMAKE_BUILD_TYPE=$BuildType",
  "-DFREERDP_DIR=$freerdpInstall",
  "-DFREERDP_LIB_DIR=$freerdpInstall\\lib",
  "-DOPENSSL_DIR=$OpenSslRoot"
)

cmake @wrapperArgs
cmake --build $wrapperBuild --parallel

Write-Host "=== 同步库到 entry/libs/$Arch ===" -ForegroundColor Cyan

$targetLibDir = Join-Path $root "entry\libs\$Arch"
New-Item -ItemType Directory -Path $targetLibDir -Force | Out-Null
Get-ChildItem -Path $targetLibDir -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$freerdpLibDir = Join-Path $freerdpInstall "lib"
$allowPatterns = @(
  "libfreerdp*.so*",
  "libwinpr*.so*"
)
if ($WithFFmpeg) {
  $allowPatterns += "libav*.so*"
}

foreach ($pattern in $allowPatterns) {
  Get-ChildItem -Path $freerdpLibDir -Filter $pattern -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item $_.FullName $targetLibDir -Force
  }
}

$wrapperOut = Join-Path $wrapperBuild "libfreerdp_harmonyos.so"
if (Test-Path $wrapperOut) {
  Copy-Item $wrapperOut $targetLibDir -Force
} else {
  Write-Warning "未找到 wrapper 输出: $wrapperOut"
}

Write-Host "完成。请重新构建 HAP 并测试连接。" -ForegroundColor Green
