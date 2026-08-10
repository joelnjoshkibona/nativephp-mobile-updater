# NativePHP Mobile — App Updater

Self-hosted OTA APK updater plugin for NativePHP Mobile. Your mobile app checks a URL you control, downloads the APK when an update is available, and triggers the Android system installer.

**No vendor lock-in. No Google Play required. Any backend in any language.**

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                    Mobile App (WebView)                        │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐   │
│  │  useAppUpdater()  ◄── Vue composable                    │   │
│  │                                                          │   │
│  │  1. GET  VITE_UPDATE_CHECK_URL  ────────┐               │   │
│  │                                          │               │   │
│  │  2. BridgeCall('MobileUpdater.Download') │               │   │
│  │  3. native-event DownloadProgress        │               │   │
│  │  4. BridgeCall('MobileUpdater.InstallApk')│              │   │
│  └──────────────────────────────────┬──────┼───────────────┘   │
│                                     │      │                   │
│                           native bridge    │                   │
│                                     ▼      │                   │
│  ┌──────────────────────────────────────┐  │                   │
│  │  MobileUpdaterFunctions.kt (Native)  │  │                   │
│  │  - Download (DownloadManager)        │  │                   │
│  │  - Install (system APK installer)    │  │                   │
│  │  - Events: DownloadProgress/...      │  │                   │
│  └──────────────────────────────────────┘  │                   │
│                                            │                   │
└────────────────────────────────────────────┼───────────────────┘
                                             │
                                             ▼
                              ┌─────────────────────────────┐
                              │  YOUR Backend (any stack)    │
                              │  - Returns update JSON       │
                              │  - Serves APK binary         │
                              └─────────────────────────────┘
```

## Key Insight: Framework-Agnostic

**Your server can be in any language.** Only the JSON contract matters.

- Check URL returns a simple JSON response (see contract below)
- APK download URL serves the binary file
- Both HTTP endpoints can be implemented in Laravel, Express, Django, Go, Rails, or even static files

The **native bridge calls** (download/install) go through `/_native/api/call`, which is built into NativePHP Mobile — no custom Laravel routes needed on your side.

## The Update Check Contract

Your backend must respond to `GET {VITE_UPDATE_CHECK_URL}?version={current}` with JSON:

### Full response

```json
{
  "status": true,
  "data": {
    "update_available": true,
    "current_version": "1.0.0",
    "latest_version": "1.2.0",
    "mandatory": false,
    "changelog": "Fixed login bug. Added dark mode.",
    "apk": {
      "download_url": "https://cdn.example.com/releases/app-1.2.0.apk",
      "file_size": 15400000,
      "sha256": "a1b2c3d4e5f6..."
    },
    "downloads_disabled": false
  }
}
```

### Minimal response (when no update)

```json
{
  "update_available": false,
  "latest_version": "1.0.0"
}
```

### Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `update_available` | bool | yes | Whether a newer version exists |
| `latest_version` | string | yes | Semver of the latest version (e.g. "1.2.0") |
| `current_version` | string | no | Echo of the version the client sent |
| `mandatory` | bool | no | If `true`, app auto-navigates to `/app-update` — user can't dismiss |
| `changelog` | string | no | HTML or plain text. Rendered in the update page. |
| `apk.download_url` | string | yes (when update available) | Full URL to the APK file |
| `apk.file_size` | int | no | Bytes. Used for storage pre-check and progress |
| `apk.sha256` | string | no | Hash for integrity verification |
| `downloads_disabled` | bool | no | Kill switch — hides the update page |

### Response unwrapping

The composable parses `response.data.data || response.data`, so both shapes work:
- `{ status: true, data: {...} }`  (Laravel-style wrapper)
- `{ update_available: true, ... }`  (direct response)

## Backend Examples

### PHP (Laravel) — reference implementation

```php
Route::get('/api/mobile/ota/check', function (Request $request) {
    $clientVersion = $request->query('version', '0.0.0');
    $latest = Release::active()->latest('version_code')->first();

    if (!$latest || version_compare($latest->version, $clientVersion, '<=')) {
        return response()->json([
            'update_available' => false,
            'latest_version' => $latest?->version ?? $clientVersion,
        ]);
    }

    return response()->json([
        'status' => true,
        'data' => [
            'update_available' => true,
            'current_version' => $clientVersion,
            'latest_version' => $latest->version,
            'mandatory' => (bool) $latest->is_mandatory,
            'changelog' => $latest->changelog,
            'apk' => [
                'download_url' => url("/storage/releases/{$latest->version}.apk"),
                'file_size' => $latest->file_size,
                'sha256' => $latest->sha256,
            ],
        ],
    ]);
});
```

### Express (Node.js)

```javascript
app.get('/api/mobile/ota/check', (req, res) => {
    const clientVersion = req.query.version || '0.0.0';
    const latest = getLatestRelease();

    if (!latest || !isNewer(latest.version, clientVersion)) {
        return res.json({
            update_available: false,
            latest_version: latest?.version || clientVersion,
        });
    }

    res.json({
        update_available: true,
        current_version: clientVersion,
        latest_version: latest.version,
        mandatory: latest.mandatory,
        changelog: latest.changelog,
        apk: {
            download_url: `https://cdn.example.com/releases/${latest.version}.apk`,
            file_size: latest.size,
            sha256: latest.sha256,
        },
    });
});
```

### FastAPI (Python)

```python
@app.get("/api/mobile/ota/check")
async def check_update(version: str = "0.0.0"):
    latest = get_latest_release()

    if not latest or not is_newer(latest.version, version):
        return {
            "update_available": False,
            "latest_version": latest.version if latest else version,
        }

    return {
        "update_available": True,
        "current_version": version,
        "latest_version": latest.version,
        "mandatory": latest.mandatory,
        "changelog": latest.changelog,
        "apk": {
            "download_url": f"https://cdn.example.com/releases/{latest.version}.apk",
            "file_size": latest.size,
            "sha256": latest.sha256,
        },
    }
```

### Go (net/http)

```go
http.HandleFunc("/api/mobile/ota/check", func(w http.ResponseWriter, r *http.Request) {
    clientVersion := r.URL.Query().Get("version")
    if clientVersion == "" {
        clientVersion = "0.0.0"
    }
    latest := getLatestRelease()

    if latest == nil || !isNewer(latest.Version, clientVersion) {
        json.NewEncoder(w).Encode(map[string]any{
            "update_available": false,
            "latest_version":   clientVersion,
        })
        return
    }

    json.NewEncoder(w).Encode(map[string]any{
        "update_available": true,
        "current_version":  clientVersion,
        "latest_version":   latest.Version,
        "mandatory":        latest.Mandatory,
        "changelog":        latest.Changelog,
        "apk": map[string]any{
            "download_url": fmt.Sprintf("https://cdn.example.com/releases/%s.apk", latest.Version),
            "file_size":    latest.FileSize,
            "sha256":       latest.SHA256,
        },
    })
})
```

### Static JSON (simplest)

You can even host a static `update.json` file:

```json
{
  "update_available": true,
  "latest_version": "1.2.0",
  "mandatory": false,
  "changelog": "Bug fixes and performance improvements.",
  "apk": {
    "download_url": "https://github.com/myorg/myapp/releases/download/v1.2.0/app.apk",
    "file_size": 15400000
  }
}
```

Update it manually or via CI when you publish a new release.

## Client Configuration

In your mobile app's `.env`:

```bash
# Your backend's check endpoint
VITE_UPDATE_CHECK_URL=https://api.example.com/api/mobile/ota/check

# Hours between auto-checks on app launch (0 to disable)
VITE_UPDATE_CHECK_INTERVAL_HOURS=6

# Current app version (reported to the check endpoint)
NATIVEPHP_APP_VERSION=1.0.0
```

## Client Usage (Vue)

### Auto-check on app start

```typescript
// In App.vue
import { useAppUpdater } from '@/composables/useAppUpdater'

const { initAutoCheck } = useAppUpdater()

onMounted(() => {
  // Deferred 300ms so the Android WebView's fetch interceptor is installed
  // before BridgeCall fires. Without this the POST body is lost on first launch.
  setTimeout(() => initAutoCheck(), 300)
})
```

When an update is detected, `AppUpdateDialog` (a modal dialog) opens automatically showing version info, changelog, and an "Update Now" button. For mandatory updates the dialog cannot be dismissed.

### Manual check

```typescript
const { check } = useAppUpdater()
const result = await check({ force: true })
if (result?.updateAvailable) {
  // navigate to /app-update
}
```

### Reactive state

```typescript
const {
  currentVersion,          // string | null — installed version (from .env or bundle_meta.json)
  latestVersion,           // string | null — from check endpoint
  updateAvailable,         // boolean (computed)
  mandatory,               // boolean
  downloadsDisabled,       // boolean — kill switch from backend
  changelog,               // string (HTML/text)
  apkInfo,                 // { download_url, file_size?, sha256? } | null
  downloadProgress,        // 0–100
  downloadState,           // 'idle' | 'downloading' | 'complete' | 'failed' | 'installing'
  errorMessage,            // string
  needsInstallPermission,  // boolean — true when Android requires "Install unknown apps" grant
  isChecking,              // boolean
  lastCheckedAt,           // number (unix ms)
} = useAppUpdater()
```

### Actions

```typescript
const {
  check,               // ({ force?: boolean }) => Promise<{ updateAvailable, mandatory, downloadsDisabled } | null>
  initAutoCheck,       // () => Promise<void> — throttled by VITE_UPDATE_CHECK_INTERVAL_HOURS
  downloadAndInstall,  // () => Promise<void> — start download; installer launches automatically on completion
  retryInstall,        // () => Promise<void> — re-launch the system installer (APK already on disk)
  cancel,              // () => Promise<void> — cancel in-progress download
  fetchCurrentVersion, // () => Promise<string> — read version from device
} = useAppUpdater()
```

## Native Bridge Functions

All callable from JS via `BridgeCall` (imported from `@nativephp/mobile`):

| Method | Parameters | Description |
|---|---|---|
| `MobileUpdater.CheckVersion` | none | Read installed version from `.env` (falls back to `bundle_meta.json` on fresh install) |
| `MobileUpdater.Download` | `{ url: string, sha256?: string }` | Enqueue APK download via Android DownloadManager |
| `MobileUpdater.CancelDownload` | none | Cancel in-progress download and delete partial file |
| `MobileUpdater.InstallApk` | none | Launch Android system APK installer |

> **Note:** `MobileUpdater.Download` uses the Android system `DownloadManager` which shows a progress
> notification in the status bar automatically. The download continues even if the app is backgrounded.

## Native Events

Events are dispatched as DOM `CustomEvent` on `document` with type `native-event`. The composable listens via:

```typescript
document.addEventListener('native-event', (e: Event) => { ... })
```

| Event (matched via `endsWith`) | Payload | Description |
|---|---|---|
| `MobileUpdater\DownloadProgress` | `{ percent, bytesDownloaded, totalBytes }` | Fires every ~500ms during download |
| `MobileUpdater\DownloadComplete` | `{ path, size }` | Download finished — installer launches automatically |
| `MobileUpdater\DownloadFailed` | `{ error, bytesDownloaded, resumable }` | Download failed |

## UX Flow

1. App mounts → `initAutoCheck()` runs after 300ms (throttled by `VITE_UPDATE_CHECK_INTERVAL_HOURS`)
2. Backend returns `update_available: true`
3. **Mandatory update** → app auto-routes to `/app-update?mandatory=1` (dialog and page cannot be dismissed)
4. **Optional update** → `AppUpdateDialog` modal opens showing current/latest version, file size, and changelog
5. User taps **Update Now** → navigates to `/app-update` (full-screen download page)
6. Tap **Download & Install**:
   - Android `DownloadManager` downloads the APK (system notification with progress bar)
   - `DownloadProgress` events update the in-app progress bar every ~500ms
   - On `DownloadComplete` → system APK installer launches automatically
   - If user dismisses the installer dialog, an **Install** button appears after 2 seconds to re-launch it
   - If **Install unknown apps** permission is missing → Settings opens automatically; on return the installer retries

## Install Permission Flow

On Android 8+ the user must grant "Install unknown apps" to this app before the system installer will open. The plugin handles this automatically:

1. `InstallApk` detects the missing permission → opens **Settings → Install unknown apps**
2. User grants permission and navigates back
3. The app detects the return (`visibilitychange`) and re-launches the installer immediately

## Version Reading Priority

`MobileUpdater.CheckVersion` reads the app version in this order:

1. `NATIVEPHP_APP_VERSION` from the extracted `.env` (most up-to-date, used after the first boot)
2. `"version"` field in `assets/bundle_meta.json` (always available, used on fresh install before `.env` is extracted)
3. Falls back to `"DEBUG"` if neither source is readable

## Installation

```bash
composer require nativephp/mobile-updater:@dev
```

Then rebuild your mobile app (native plugin code needs to be compiled):

```bash
php artisan native:run android
```

## Permissions

The plugin declares one Android permission:
- `REQUEST_INSTALL_PACKAGES` — required to launch the system APK installer

This permission is "sensitive" on Play Store — only required if you're distributing outside the Play Store. Users will be prompted to grant it when the first update is triggered.

## Security

- **Always serve updates over HTTPS.**
- **Set `sha256` on the response** — the native downloader will verify the file integrity before the installer is launched.
- **Signature enforcement:** Android only allows APKs signed with the same key as the currently-installed app. You cannot accidentally install a tampered APK unless it is signed with the matching key.

## License

MIT
