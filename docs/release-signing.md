# Mihon Upscale release signing

Starting with version 1.3.17-beta.4, the release application ID is `app.mihon.upscale` and the launcher name is **Mihon Upscale**. The original kana icon has a dark red ring (`#8B0000`). This package can coexist with upstream Mihon and older fork releases using `app.mihon`.

## Permanent signing identity

- Keystore: `mihon-upscale-release-2026.p12` (PKCS12, RSA 4096).
- Key alias: `mihon-upscale`.
- Certificate SHA-256: `b47258260607adf71cb91360f012e8312fb924b56deeeee961d9bab68bbc4527`.
- The key is valid for 100 years from its creation on 2026-10-31.

Reuse this key for all subsequent Mihon Upscale releases. Do not generate a replacement key for a routine release; Android requires the same signing identity for in-place upgrades.

## Local configuration and backup

The release machine keeps the key and a companion `mihon-upscale-release-2026.properties` file in the user's `Documents/Keys` directory. That companion file contains the saved store password, alias and key password. A complete project-root `keystore.properties` is configured for Gradle, with the same values. These credential files are restricted to the local user and SYSTEM on Windows.

Keep a separate protected backup of both the PKCS12 key and its companion properties file. Passwords are stored in the designated credential configuration; no chat history is required. Neither the key nor actual passwords belong in Git, release assets or build logs.

To restore this build on another machine, copy `keystore.properties.example` to `keystore.properties`, restore the saved credentials, and update `storeFile` to the restored key location. Verify the certificate fingerprint before publishing. On Windows, forward slashes avoid Java properties escaping issues.

## Build

Normal local release builds read `keystore.properties` directly:

```powershell
.\gradlew.bat :app:assembleRelease -Pdist=github -Pinclude-telemetry=false
```

For CI, the existing `MIHON_GITHUB_RELEASE=true` flow reads `storeFile`, `storePassword`, `keyAlias` and `keyPassword` from the CI environment. Configure those values through the CI secret store using this same release key.

Verify every final APK with Android SDK `apksigner verify --print-certs`. Check its package ID, version and the certificate SHA-256 above before uploading. Beta tags remain GitHub prereleases and must not replace the latest stable designation. Version 1.3.17 is the stable release concluding that beta cycle (versionCode 48), reuses this key, and can be designated latest.

## Migration from the previous package

The new package and signing key do not replace the previous installation. Export a backup from the old app, restore it in Mihon Upscale, and reselect storage directories as needed. The new default storage directory is `Mihon Upscale`. Keep the old app until the restored library and settings have been checked.
