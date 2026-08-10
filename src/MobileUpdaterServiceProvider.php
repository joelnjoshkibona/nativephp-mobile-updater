<?php

namespace Blutrixx\MobileUpdater;

use Illuminate\Support\ServiceProvider;

class MobileUpdaterServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton('blutrixx.mobile-updater', fn () => new MobileUpdater());
    }

    public function boot(): void {}
}
