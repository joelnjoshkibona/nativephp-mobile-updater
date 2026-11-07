<?php

namespace Blutrixx\MobileUpdater;

class MobileUpdater
{
    /**
     * Read the installed app version from the embedded .env file.
     */
    public function checkVersion(): array
    {
        return $this->call('MobileUpdater.CheckVersion');
    }

    /**
     * Start downloading an APK update.
     *
     * @param  string       $url           Direct URL to the APK file
     * @param  int|null     $expectedSize  File size in bytes (used for storage pre-check and progress %)
     * @param  string|null  $sha256        SHA-256 hex digest for integrity verification
     * @param  string|null  $version       The version this file is. It is stored with the finished download, and checkDownloadStatus($version)
     *                                     only calls the file ready when it matches, so a leftover of another version is never installed.
     */
    public function download(string $url, ?int $expectedSize = null, ?string $sha256 = null, ?string $version = null): array
    {
        return $this->call('MobileUpdater.Download', array_filter([
            'url'          => $url,
            'expectedSize' => $expectedSize,
            'sha256'       => $sha256,
            'version'      => $version,
        ], fn ($v) => $v !== null));
    }

    /**
     * Cancel the in-progress download. Partial file is preserved for resume.
     */
    public function cancelDownload(): array
    {
        return $this->call('MobileUpdater.CancelDownload');
    }

    /**
     * Launch the Android system package installer for the downloaded APK.
     */
    public function installApk(): array
    {
        return $this->call('MobileUpdater.InstallApk');
    }

    /**
     * Check if an APK is already downloaded and ready to install, without
     * starting a new download. Returns ['ready' => bool, 'size' => int, 'inProgress' => bool].
     * Give the version you are offering: a finished download of another version, or one with no version stored, is cleared and not ready.
     */
    public function checkDownloadStatus(?string $expectedVersion = null): array
    {
        return $this->call('MobileUpdater.CheckDownloadStatus', array_filter(['expectedVersion' => $expectedVersion], fn ($v) => $v !== null));
    }

    /**
     * One bridge call. NativePHP 4 defines nativephp_call($function, $json) with both arguments required and answers with a JSON string
     * (3.x allowed leaving the parameters out), so the parameters are always sent, as `{}` when there are none.
     */
    private function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['error' => 'NOT_ON_DEVICE', 'message' => 'The updater only works inside the NativePHP app.'];
        }
        $result = nativephp_call($function, json_encode($params === [] ? new \stdClass() : $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
