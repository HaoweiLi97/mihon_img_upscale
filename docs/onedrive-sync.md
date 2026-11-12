# OneDrive setup

In **Settings → Remote sync → Cloud storage**, select **OneDrive**. Tap **OneDrive account**, sign in with Microsoft, and grant access in your browser. Back in Mihon Upscale, select a sync destination. To return to WebDAV, change the storage selection; the previous WebDAV connection and destination are retained. Switching storage does not move files between the services.

## Microsoft Entra registration for custom builds

Mihon Upscale builds use the project's public client ID. To use a different registration in a custom build:

1. [Register an app in Microsoft Entra](https://learn.microsoft.com/en-us/graph/auth-register-app-v2). To support both personal OneDrive and work or school OneDrive, select **Accounts in any organizational directory and personal Microsoft accounts**.
2. Under **Authentication**, add the **Mobile and desktop applications** platform. Register `<applicationId>://onedrive-auth` for each variant you distribute. For example, the standard release uses `app.mihon.upscale://onedrive-auth` and the debug build uses `app.mihon.upscale.dev://onedrive-auth`.
3. Add the Microsoft Graph **delegated** permissions `Files.ReadWrite` and `offline_access` so background sync can refresh access. The browser handles Microsoft sign-in and individual consent. The Android app requires no client secret.
4. Set `ONEDRIVE_CLIENT_ID` in the build environment or pass `-PoneDriveClientId=<application-client-id>` to Gradle.

The app uses the signed-in account's default OneDrive. If an organization's consent policy blocks authorization, its administrator must allow the app. To use a different OneDrive account, choose **Sign in again** and pick a destination for that account.
