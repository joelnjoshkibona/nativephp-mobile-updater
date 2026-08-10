<?php

namespace Blutrixx\MobileUpdater\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array checkVersion()
 * @method static array download(string $url, ?int $expectedSize = null, ?string $sha256 = null)
 * @method static array cancelDownload()
 * @method static array installApk()
 * @method static array checkDownloadStatus()
 *
 * @see \Blutrixx\MobileUpdater\MobileUpdater
 */
class MobileUpdater extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'blutrixx.mobile-updater';
    }
}
