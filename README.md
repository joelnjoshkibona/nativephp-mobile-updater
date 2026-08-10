# Blutrixx Mobile Updater

A [NativePHP Mobile](https://nativephp.com) plugin for self-hosted over-the-air APK updates — no Play Store required. It downloads a full APK from a URL you supply, verifies its size/checksum, and hands off to the Android system installer. Your app is responsible for deciding *when* an update is available (fetching your own update manifest and comparing versions); this package only handles the download/verify/install mechanics.

Composer package: `blutrixx/nativephp-mobile-updater`
Repo: `joelnjoshkibona/nativephp-mobile-updater`
Current release: `v1.0.0`

## Requirements

- PHP ^8.1
- A Laravel app running under `nativephp/mobile`
- Android: requests `REQUEST_INSTALL_PACKAGES` (to launch the system installer) and `POST_NOTIFICATIONS` (download progress notification) permissions.
- A place to host APKs + an update manifest — see [Recommended manifest shape](#recommended-manifest-shape) below.

## Installation

Not on Packagist yet.

**As a git submodule (how this repo itself consumes it):**

```bash
git submodule add https://github.com/joelnjoshkibona/nativephp-mobile-updater.git packages/nativephp-mobile-updater
```

```json
// composer.json
{
    "repositories": [
        {"type": "path", "url": "packages/nativephp-mobile-updater"}
    ],
    "require": {
        "blutrixx/nativephp-mobile-updater": "@dev"
    }
}
```

**Without a submodule:**

```json
{
    "repositories": [
        {"type": "vcs", "url": "https://github.com/joelnjoshkibona/nativephp-mobile-updater"}
    ],
    "require": {
        "blutrixx/nativephp-mobile-updater": "^1.0"
    }
}
```

Laravel auto-discovers `MobileUpdaterServiceProvider` and the `MobileUpdater` facade alias.

## How the bridge works

`checkVersion()`, `cancelDownload()`, `installApk()`, and `checkDownloadStatus()` are **synchronous** — they return a real result immediately. `download()` is **asynchronous**: it starts a background download and returns right away; progress and completion arrive later as native-core events (`Native\Mobile\Events\MobileUpdater\DownloadProgress` / `DownloadComplete` / `DownloadFailed`) that NativePHP itself dispatches — not classes this package defines, since they're part of NativePHP's own event surface. Listen for them the same way described in `blutrixx/nativephp-device-utils`' README (matching by `.endsWith()` on the event name), or poll `checkDownloadStatus()` if you'd rather not wire up a listener.

## API reference

| Method | Sync? | Returns | Notes |
|---|---|---|---|
| `checkVersion()` | Sync | `array` | Reads the **currently installed** app version from the embedded `.env` — does not check a remote manifest itself |
| `download(string $url, ?int $expectedSize = null, ?string $sha256 = null)` | **Async** | `array` | Starts the download; fires `DownloadProgress`/`DownloadComplete`/`DownloadFailed` |
| `cancelDownload()` | Sync | `array` | Cancels an in-progress download; the partial file is kept so a later `download()` call to the same URL can resume |
| `installApk()` | Sync | `array` | Launches the Android system package installer for the already-downloaded APK |
| `checkDownloadStatus()` | Sync | `{ready: bool, size: int, inProgress: bool}` | Check whether an APK is already downloaded and ready to install, without starting a new download |

```php
use Blutrixx\MobileUpdater\Facades\MobileUpdater;

$installed = MobileUpdater::checkVersion();

MobileUpdater::download(
    url: 'https://updates.example.com/apps/myapp/2.1.0.apk',
    expectedSize: 41_943_040,
    sha256: 'a1b2c3...',
);

$status = MobileUpdater::checkDownloadStatus();
if ($status['ready']) {
    MobileUpdater::installApk();
}
```

## Recommended manifest shape

This package doesn't fetch or parse a remote manifest for you — your app hits its own update-check endpoint (however you host it) and decides what to pass to `download()`. A shape that works well in practice, one entry per architecture/variant:

```json
{
  "latestVersion": "2.1.0",
  "versions": [
    {
      "version": "2.1.0",
      "architecture": "universal",
      "apkUrl": "https://updates.example.com/apps/myapp/2.1.0-universal.apk",
      "checksum": "sha256:a1b2c3...",
      "size": 41943040,
      "changelog": "Bug fixes and performance improvements",
      "mandatory": false,
      "releaseDate": "2026-08-01",
      "buildNumber": 210,
      "minSdkVersion": 26,
      "targetSdkVersion": 36
    }
  ]
}
```

Compare `latestVersion` against `MobileUpdater::checkVersion()`'s result client-side (or server-side, in the controller that serves your update-check route), and only call `download()` if there's actually a newer version — the `mandatory` flag is a convention for your own UI to decide whether the update screen is skippable, not something this package enforces.

## Quick start: full update flow

```php
// routes/api.php
Route::get('/app/check-update', function () {
    $installed = \Blutrixx\MobileUpdater\Facades\MobileUpdater::checkVersion();
    $manifest  = Http::get(config('services.updates.manifest_url'))->json();

    return [
        'installed' => $installed,
        'latest'    => $manifest['latestVersion'],
        'update'    => $manifest['versions'][0] ?? null,
    ];
});

Route::post('/app/start-update', function (Request $request) {
    return \Blutrixx\MobileUpdater\Facades\MobileUpdater::download(
        url: $request->string('apkUrl'),
        expectedSize: $request->integer('size'),
        sha256: $request->string('checksum')->after('sha256:'),
    );
});

Route::post('/app/install-update', fn () => \Blutrixx\MobileUpdater\Facades\MobileUpdater::installApk());
```
