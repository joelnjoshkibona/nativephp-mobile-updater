<?php

namespace Blutrixx\MobileUpdater;

class MobileUpdater
{
    /**
     * Read the installed app version from the embedded .env file.
     */
    public function checkVersion(): array
    {
        return nativephp_call('MobileUpdater.CheckVersion');
    }

    /**
     * Start downloading an APK update.
     *
     * @param  string       $url           Direct URL to the APK file
     * @param  int|null     $expectedSize  File size in bytes (used for storage pre-check and progress %)
     * @param  string|null  $sha256        SHA-256 hex digest for integrity verification
     */
    public function download(string $url, ?int $expectedSize = null, ?string $sha256 = null): array
    {
        return nativephp_call('MobileUpdater.Download', array_filter([
            'url'          => $url,
            'expectedSize' => $expectedSize,
            'sha256'       => $sha256,
        ], fn ($v) => $v !== null));
    }

    /**
     * Cancel the in-progress download. Partial file is preserved for resume.
     */
    public function cancelDownload(): array
    {
        return nativephp_call('MobileUpdater.CancelDownload');
    }

    /**
     * Launch the Android system package installer for the downloaded APK.
     */
    public function installApk(): array
    {
        return nativephp_call('MobileUpdater.InstallApk');
    }

    /**
     * Check if an APK is already downloaded and ready to install, without
     * starting a new download. Returns ['ready' => bool, 'size' => int, 'inProgress' => bool].
     */
    public function checkDownloadStatus(): array
    {
        return nativephp_call('MobileUpdater.CheckDownloadStatus');
    }
}
