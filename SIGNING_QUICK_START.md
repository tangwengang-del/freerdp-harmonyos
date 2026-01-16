# HarmonyOS Application Signing - Quick Start

## Current Status

Your HarmonyOS project has been configured for automatic signing. The signing configuration is ready, but you need to add signing files.

## What Has Been Configured

✅ Signing directory created: `.signing/`
✅ Build profile updated: `build-profile.json5`
✅ Security configured: `.gitignore` updated
✅ Build scripts created: `build-and-sign.ps1`, `setup-signing.ps1`
✅ CI/CD workflow: `.github/workflows/build-and-sign.yml`

## What You Need to Do

### Step 1: Get Signing Files (Choose One Method)

#### Method A: Huawei Developer Console (Recommended)

For cloud testing on virtual server:

1. Open browser: https://developer.huawei.com/consumer/cn/service/josp/agc/index.html
2. Login with Huawei Developer account
3. Navigate to: **My Projects** → **Certificates**
4. Click **Generate Debug Certificate**
5. Download these 3 files:
   - `debug.p12` (keystore)
   - `debug.cer` (certificate)
   - `debug.p7b` (profile)
6. Copy files to: `c:\huawei\.signing\`

#### Method B: DevEco Studio (If Available)

If you have DevEco Studio on another machine:

1. Open this project in DevEco Studio
2. Menu: **File** → **Project Structure** → **Signing Configs**
3. Check: **"Automatically generate signature"**
4. Wait for files to generate
5. Upload files from `.signing/` folder to server

### Step 2: Verify Setup

Run verification script:

```powershell
.\setup-signing.ps1
```

Expected output:
```
[OK] Found P12 file: debug.p12
[OK] Found CER file: debug.cer
[OK] Found P7B file: debug.p7b
Signing configuration complete!
```

### Step 3: Build Your App

#### Option A: Use Build Script (Recommended)

```powershell
# Build debug version
.\build-and-sign.ps1

# Build release version
.\build-and-sign.ps1 -Release

# Clean build
.\build-and-sign.ps1 -Clean
```

#### Option B: Use Hvigor Directly

```powershell
# Debug build
.\hvigorw.bat assembleHap --mode debug

# Release build
.\hvigorw.bat assembleHap --mode release
```

### Step 4: Find Your HAP File

After successful build, find HAP at:

```
entry\build\default\outputs\default\entry-default-signed.hap
```

### Step 5: Deploy for Testing

Upload HAP to your test server or cloud testing platform.

## Project File Structure

```
c:\huawei\
├── .signing/                      # Signing files (not in Git)
│   ├── debug.p12                 # Keystore (add this)
│   ├── debug.cer                 # Certificate (add this)
│   ├── debug.p7b                 # Profile (add this)
│   └── README.md                 # Documentation
├── build-profile.json5           # ✅ Configured with signing
├── setup-signing.ps1             # Verification script
├── build-and-sign.ps1            # Build script
├── 签名配置指南.md                # Full documentation (Chinese)
└── SIGNING_QUICK_START.md        # This file
```

## Troubleshooting

### Issue: "Unable to create the profile"

**Solution:**
1. Check files exist: `Get-ChildItem .signing`
2. Verify file names match exactly
3. Re-run: `.\setup-signing.ps1`

### Issue: Build fails with signing error

**Solution:**
1. Verify bundle ID matches certificate
2. Check `AppScope\app.json5` → `bundleName`
3. Regenerate signing files if needed

### Issue: Cannot run DevEco Studio on virtual server

**Solution:**
- Use Method A (Huawei Developer Console)
- This method is designed for virtual servers without GUI

## CI/CD Integration

GitHub Actions is configured to build automatically. To enable signing in CI:

1. Convert signing files to base64:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes(".signing\debug.p12")) | Out-File "p12.txt"
   [Convert]::ToBase64String([IO.File]::ReadAllBytes(".signing\debug.cer")) | Out-File "cer.txt"
   [Convert]::ToBase64String([IO.File]::ReadAllBytes(".signing\debug.p7b")) | Out-File "p7b.txt"
   ```

2. Add to GitHub Secrets:
   - `SIGNING_P12` → content of p12.txt
   - `SIGNING_CER` → content of cer.txt
   - `SIGNING_P7B` → content of p7b.txt

## Security Reminders

🔒 **DO NOT commit signing files to Git**
- Already configured in `.gitignore`

🔒 **Keep passwords secure**
- Don't share in chat or email

🔒 **Use debug keys for testing only**
- Generate separate keys for production

## Next Steps

1. ✅ Configuration complete
2. ⏳ Get signing files (see Step 1)
3. ⏳ Verify setup (see Step 2)
4. ⏳ Build app (see Step 3)
5. ⏳ Deploy for testing (see Step 4)

## Resources

- 📖 [Full Guide](./签名配置指南.md) - Detailed documentation in Chinese
- 📖 [Signing README](./.signing/README.md) - Signing files documentation
- 🌐 [AppGallery Connect](https://developer.huawei.com/consumer/cn/service/josp/agc/index.html)
- 📚 [HarmonyOS Signing Docs](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-signing-V5)

---

**Ready to build?** Once you have signing files in `.signing/` folder, run:

```powershell
.\build-and-sign.ps1
```
