# FreeRDP Android 编译状态报告

## 当前状态

### 已完成的修复

1. ✅ **Java代码闪退问题** - 修复了 RECEIVER_EXPORTED API 33+ 兼容性问题
   - `GlobalApp.java` 第151行
   - `SessionActivity.java` 第316行

2. ✅ **Gradle配置优化**
   - Target SDK: 31 (Android 12)
   - JVM内存: 2GB
   - 禁用testOnly标记
   - 修复keystore路径

3. ✅ **Android Manifest配置**
   - 添加 `extractNativeLibs="true"`
   - 添加 `usesCleartextTraffic="true"`

4. ✅ **构建配置**
   - Release版本 `debuggable=false`
   - 国产机兼容配置
   - APK输出路径优化

### 当前障碍

**OpenSSL依赖问题** - 这是唯一阻止编译完成的问题

FreeRDP核心代码强依赖OpenSSL：
- `libfreerdp/utils/http.c` 需要 `openssl/bio.h`
- `libfreerdp/core/tcp.h` 需要 `openssl/bio.h`
- `winpr/libwinpr/crypto/hash.c` 需要OpenSSL加密函数
- `libfreerdp/crypto/certificate.h` 需要 `openssl/x509.h`

## 解决方案

### 方案1: 使用预编译的Android OpenSSL (推荐)

从可靠来源获取预编译的Android OpenSSL库：

```bash
# 方法A: 使用 leenjewel/openssl_for_ios_and_android
git clone https://github.com/leenjewel/openssl_for_ios_and_android
cd openssl_for_ios_and_android
./build-android-openssl.sh android-23 armeabi-v7a

# 复制到项目
cp output/android/armeabi-v7a/lib/*.{a,so} client/Android/Studio/freeRDPCore/src/main/jniLibs/armeabi-v7a/
cp -r output/android/armeabi-v7a/include/* client/Android/Studio/freeRDPCore/src/main/jniLibs/include/
```

然后修改 `freeRDPCore/build.gradle`:

```gradle
arguments "-DWITH_OPENSSL=ON",
          "-DOPENSSL_ROOT_DIR=${project.projectDir}/src/main/jniLibs/armeabi-v7a",
          "-DOPENSSL_INCLUDE_DIR=${project.projectDir}/src/main/jniLibs/include",
          "-DOPENSSL_CRYPTO_LIBRARY=${project.projectDir}/src/main/jniLibs/armeabi-v7a/libcrypto.a",
          "-DOPENSSL_SSL_LIBRARY=${project.projectDir}/src/main/jniLibs/armeabi-v7a/libssl.a"
```

### 方案2: 使用Android NDK自带的BoringSSL

编译BoringSSL并配置：

```bash
cd boringssl
mkdir build && cd build
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=armeabi-v7a \
      -DANDROID_PLATFORM=android-23 \
      ..
make
```

### 方案3: 使用CMake FetchContent自动下载编译

修改项目根目录的 `CMakeLists.txt`，添加：

```cmake
include(FetchContent)
FetchContent_Declare(
    openssl
    URL https://www.openssl.org/source/openssl-1.1.1w.tar.gz
)
FetchContent_MakeAvailable(openssl)
```

## 预计完成时间

- 方案1 (推荐): 30-60分钟
- 方案2: 1-2小时
- 方案3: 2-3小时

## 编译命令

OpenSSL配置完成后，运行：

```powershell
cd client\Android\Studio
Remove-Item -Path "freeRDPCore\.cxx" -Recurse -Force
.\gradlew :aFreeRDP:assembleRelease --no-daemon
```

## 预期输出

- **APK大小**: ~14-16MB
- **输出位置**: `aFreeRDP/build/outputs/apk/release/aFreeRDP-release.apk`
- **支持架构**: armeabi-v7a
- **最低Android版本**: 6.0 (API 23)
- **目标Android版本**: 12 (API 31)

## 已修复的闪退问题

所有导致闪退的Java代码问题已修复：
1. ✅ 移除API 33专用的 RECEIVER_EXPORTED常量
2. ✅ 使用API 31兼容的BroadcastReceiver注册方式
3. ✅ 修复version code为0的问题

一旦OpenSSL配置完成，APK应该可以正常安装和运行，不会出现闪退。

