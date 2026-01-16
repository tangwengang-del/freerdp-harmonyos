# HarmonyOS Signing Files

This directory contains signing files for the HarmonyOS application.

## Required Files

Place the following files in this directory:

1. **debug.p12** - Keystore file
   - Contains private key
   - Password protected

2. **debug.cer** - Certificate file
   - Contains public key information
   - Used for app signature verification

3. **debug.p7b** - Provision Profile
   - Contains app signing configuration
   - Links certificate to app bundle ID

## How to Get Signing Files

### Option 1: Huawei Developer Console (Recommended for Cloud Testing)

1. Visit [AppGallery Connect](https://developer.huawei.com/consumer/cn/service/josp/agc/index.html)
2. Login with your Huawei Developer account
3. Create a new app or select existing app
4. Go to: My Projects → Certificates → Generate Debug Certificate
5. Download the three files and place them here

### Option 2: DevEco Studio (If available locally)

1. Open project in DevEco Studio
2. File → Project Structure → Signing Configs
3. Check "Automatically generate signature"
4. Wait for generation to complete
5. Copy generated files from `.signing` folder to server

## Security Notes

- **DO NOT commit these files to Git**
  - Already configured in `.gitignore`
- **Keep passwords secure**
  - Don't share keystore passwords
- **Use different keys for production**
  - These are debug keys only
  - Generate separate keys for release builds

## Verification

Run the setup script to verify files:

```powershell
.\setup-signing.ps1
```

## Build After Setup

Once files are in place, build your app:

```powershell
# Using build script (recommended)
.\build-and-sign.ps1

# Or directly
.\hvigorw.bat assembleHap
```

## Troubleshooting

### "Unable to create profile" error

- Verify all three files exist
- Check file permissions
- Ensure bundle ID matches certificate

### Files not recognized

- Check file names exactly match: `debug.p12`, `debug.cer`, `debug.p7b`
- Verify files are not corrupted
- Re-download from source if needed

## Resources

- [HarmonyOS Signing Guide](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-signing-V5)
- [AppGallery Connect](https://developer.huawei.com/consumer/cn/service/josp/agc/index.html)
