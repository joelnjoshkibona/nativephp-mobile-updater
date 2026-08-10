package com.nativephp.mobileupdater

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest

object MobileUpdaterFunctions {

    private const val TAG = "MobileUpdater"
    private const val APK_FILE = "update_download.apk"
    private const val ENV_FILE = ".env"
    private const val DIR_LARAVEL = "laravel"
    private const val REGEX_APP_VERSION = """NATIVEPHP_APP_VERSION=(.+)"""
    private const val CHANNEL_ID = "mobileupdater_updates"
    private const val NOTIFICATION_ID = 9901
    private const val PREFS_NAME = "mobileupdater"
    private const val PREF_COMPLETED_ID = "completed_download_id"
    private const val PREF_COMPLETED_VERSION = "completed_download_version"

    // In-progress download state
    @Volatile private var downloadId: Long = -1L
    @Volatile private var downloadReceiver: BroadcastReceiver? = null
    @Volatile private var activityRef: WeakReference<FragmentActivity>? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    @Volatile private var progressRunnable: Runnable? = null

    // The destination file for the active/completed download
    private fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(null), APK_FILE)

    // SharedPreferences helpers — persist the completed download ID across restarts
    private fun saveCompletedId(context: Context, id: Long) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(PREF_COMPLETED_ID, id).apply()

    private fun loadCompletedId(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_COMPLETED_ID, -1L)

    private fun clearCompletedId(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_COMPLETED_ID).apply()

    // Which app version the completed download corresponds to — lets CheckDownloadStatus
    // tell an up-to-date completed download apart from a stale one left over from a
    // previous update that was never installed.
    private fun saveCompletedVersion(context: Context, version: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_COMPLETED_VERSION, version).apply()

    private fun loadCompletedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_COMPLETED_VERSION, null)

    private fun clearCompletedVersion(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_COMPLETED_VERSION).apply()

    // Remove a completed-download entry entirely (DownloadManager record, prefs, and the
    // physical APK file). Shared by both cleanup triggers: CheckVersion (the running app
    // now IS the version that was downloaded — install confirmed, no longer needed) and
    // CheckDownloadStatus (the download doesn't match what's currently advertised as latest).
    private fun clearCompletedDownload(context: Context, reason: String) {
        val completedId = loadCompletedId(context)
        if (completedId != -1L) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(completedId)
        }
        clearCompletedId(context)
        clearCompletedVersion(context)
        apkFile(context).delete()
        Log.d(TAG, "🧹 Cleared completed download — reason: $reason")
    }

    /**
     * Get a content:// URI for a completed download entry.
     * Returns null if the entry no longer exists in DownloadManager.
     * This URI is already READ-granted by DownloadManager — no FileProvider needed.
     */
    private fun getDownloadUri(context: Context, id: Long): Uri? {
        if (id == -1L) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            dm.getUriForDownloadedFile(id)
        } catch (e: Exception) {
            null
        }
    }

    // ── CheckVersion ─────────────────────────────────────────────────────────

    class CheckVersion(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            Log.d(TAG, "CheckVersion called")
            return try {
                val envFile = File(
                    File(context.getDir("storage", Context.MODE_PRIVATE), DIR_LARAVEL),
                    ENV_FILE
                )
                val version = if (envFile.exists()) {
                    Regex(REGEX_APP_VERSION).find(envFile.readText())
                        ?.groupValues?.get(1)?.trim()
                        ?: readBundleMeta()
                } else {
                    Log.d(TAG, "CheckVersion: .env not extracted yet — reading bundle_meta.json")
                    readBundleMeta()
                }
                Log.d(TAG, "CheckVersion: version=$version")

                // If the app that's running RIGHT NOW is the same version as a previously
                // completed download, that download's job is done — the update was already
                // installed. Clean it up immediately so it can never be offered again,
                // regardless of what any later network check does or doesn't determine.
                val completedVersion = loadCompletedVersion(context)
                if (completedVersion != null) {
                    if (completedVersion == version) {
                        Log.d(TAG, "✅ CheckVersion: running version ($version) matches completed download — install confirmed")
                        clearCompletedDownload(context, "installed version ($version) now confirmed running")
                    } else {
                        Log.d(TAG, "ℹ️ CheckVersion: completed download version ($completedVersion) differs from running version ($version) — leaving for CheckDownloadStatus to validate")
                    }
                }

                BridgeResponse.success(mapOf("version" to version))
            } catch (e: Exception) {
                Log.e(TAG, "CheckVersion failed", e)
                BridgeResponse.success(mapOf("version" to "DEBUG"))
            }
        }

        private fun readBundleMeta(): String = try {
            val json = context.assets.open("bundle_meta.json").bufferedReader().use { it.readText() }
            JSONObject(json).optString("version", "DEBUG").trim()
        } catch (e: Exception) {
            Log.w(TAG, "CheckVersion: bundle_meta.json not readable", e)
            "DEBUG"
        }
    }

    // ── CheckDownloadStatus ──────────────────────────────────────────────────

    /**
     * Check whether a completed APK download is sitting ready to install.
     *
     * Returns: { ready: boolean, size: number, inProgress: boolean }
     *
     * Uses the persisted DownloadManager entry ID (survives app restarts).
     * If ready=true, call InstallApk directly — no re-download needed.
     */
    class CheckDownloadStatus(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val inProgress = downloadId != -1L
            val expectedVersion = parameters["expectedVersion"] as? String

            val completedId = loadCompletedId(context)
            val uri = getDownloadUri(context, completedId)
            var ready = uri != null

            // A completed download only counts as "ready" if it matches the version the
            // caller is currently offering as latest — otherwise it's a leftover from an
            // earlier update that was downloaded but never installed, and offering to
            // "install" it would silently install the wrong build.
            //
            // IMPORTANT: treat an UNKNOWN completedVersion (null — e.g. a download that
            // completed before version-tracking existed, or any other untagged entry) as
            // a mismatch too, not a free pass. Only an exact, confirmed match is trusted.
            if (ready && !expectedVersion.isNullOrEmpty()) {
                val completedVersion = loadCompletedVersion(context)
                if (completedVersion != expectedVersion) {
                    clearCompletedDownload(context, "stale/unverified (version=${completedVersion ?: "unknown"}, expected=$expectedVersion)")
                    ready = false
                }
            }

            // Verify the file still physically exists
            val size = if (ready) apkFile(context).let { if (it.exists()) it.length() else 0L } else 0L

            Log.d(TAG, "CheckDownloadStatus: ready=$ready, size=$size, inProgress=$inProgress")
            return BridgeResponse.success(mapOf(
                "ready" to ready,
                "size" to size,
                "inProgress" to inProgress
            ))
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Enqueue an APK download via the system DownloadManager.
     *
     * The download runs entirely in the background — navigating away does NOT stop it.
     * The BroadcastReceiver is registered on applicationContext so it survives activity
     * recreation. Progress events fire while the activity is in the foreground;
     * a "ready to install" notification fires if the download completes in the background.
     *
     * JS params: { url: string, sha256?: string, version?: string }
     */
    class Download(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            Log.d(TAG, "Download called, params=$parameters")
            val url = parameters["url"] as? String
                ?: return BridgeResponse.error("MISSING_URL", "url is required")
            val expectedSha256 = parameters["sha256"] as? String
            val version = parameters["version"] as? String

            val appContext = activity.applicationContext
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // Cancel in-progress download and clear any previous completed download entry
            stopAndCleanAll(appContext, dm)

            activityRef = WeakReference(activity)

            val appLabel = activity.applicationInfo.loadLabel(activity.packageManager).toString()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(appLabel)
                setDescription("Downloading update…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(apkFile(appContext)))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadId = dm.enqueue(request)
            Log.d(TAG, "Download enqueued, id=$downloadId")

            // Register receiver on applicationContext — survives activity recreation
            registerCompletionReceiver(appContext, dm, expectedSha256, version)
            startProgressPolling(appContext, dm)

            return BridgeResponse.success(mapOf("status" to "downloading"))
        }
    }

    // ── CancelDownload ───────────────────────────────────────────────────────

    class CancelDownload(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            Log.d(TAG, "CancelDownload called")
            val appContext = context.applicationContext
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            stopAndCleanAll(appContext, dm)
            Log.d(TAG, "CancelDownload: done")
            return BridgeResponse.success(mapOf("status" to "cancelled"))
        }
    }

    // ── InstallApk ───────────────────────────────────────────────────────────

    /**
     * Launch the Android system package installer for the downloaded APK.
     *
     * Uses dm.getUriForDownloadedFile() — a content:// URI already READ-granted by
     * DownloadManager. No FileProvider configuration is required in the host app.
     */
    class InstallApk(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            Log.d(TAG, "InstallApk called")

            val appContext = activity.applicationContext
            val completedId = loadCompletedId(appContext)
            val uri = getDownloadUri(appContext, completedId)
                ?: return BridgeResponse.error("NO_APK", "No downloaded APK found. Run Download first.")

            return launchInstaller(activity, uri)
        }
    }

    // ── Private: stop / clean ────────────────────────────────────────────────

    private fun stopAndCleanAll(context: Context, dm: DownloadManager) {
        // Stop in-progress download
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null

        val activeId = downloadId
        if (activeId != -1L) {
            dm.remove(activeId)
            downloadId = -1L
        }

        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            downloadReceiver = null
        }

        activityRef = null

        // Remove any previously completed download so the file doesn't accumulate
        val completedId = loadCompletedId(context)
        if (completedId != -1L) {
            dm.remove(completedId)
            clearCompletedId(context)
            clearCompletedVersion(context)
        }

        apkFile(context).delete()
    }

    // ── Private: progress polling ────────────────────────────────────────────

    private fun startProgressPolling(appContext: Context, dm: DownloadManager) {
        val runnable = object : Runnable {
            override fun run() {
                val id = downloadId
                if (id == -1L) return

                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (!cursor.moveToFirst()) { cursor.close(); return }

                val bytesDownloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val totalBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                cursor.close()

                val percent = if (totalBytes > 0)
                    ((bytesDownloaded.toDouble() / totalBytes) * 100).toInt() else 0

                // Dispatch progress only if the activity is still alive
                activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }?.let { activity ->
                    dispatchProgress(activity, bytesDownloaded, totalBytes, percent)
                }

                if (status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_PAUSED) {
                    progressHandler.postDelayed(this, 500)
                }
            }
        }
        progressRunnable = runnable
        progressHandler.postDelayed(runnable, 500)
    }

    // ── Private: completion receiver ─────────────────────────────────────────

    private fun registerCompletionReceiver(
        appContext: Context,
        dm: DownloadManager,
        expectedSha256: String?,
        version: String?
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                Log.d(TAG, "DownloadComplete broadcast for id=$id")

                progressRunnable?.let { progressHandler.removeCallbacks(it) }
                progressRunnable = null
                downloadId = -1L
                downloadReceiver = null
                try { context.unregisterReceiver(this) } catch (_: Exception) {}

                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    dispatchOrNotifyFailed(appContext, "Download record missing", 0)
                    return
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                val bytesDownloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                cursor.close()

                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Log.e(TAG, "Download failed with status=$status")
                    dm.remove(id)
                    apkFile(appContext).delete()
                    dispatchOrNotifyFailed(appContext, "Download failed (status $status)", bytesDownloaded)
                    return
                }

                // SHA-256 integrity check
                if (!expectedSha256.isNullOrEmpty()) {
                    val file = apkFile(appContext)
                    val actual = computeSha256(file)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        dm.remove(id)
                        file.delete()
                        dispatchOrNotifyFailed(
                            appContext,
                            "SHA-256 mismatch: expected $expectedSha256, got $actual",
                            0
                        )
                        return
                    }
                }

                // Keep the DownloadManager entry alive — dm.getUriForDownloadedFile(id)
                // is what InstallApk uses. Saving the ID so it survives app restarts.
                saveCompletedId(appContext, id)
                if (!version.isNullOrEmpty()) {
                    saveCompletedVersion(appContext, version)
                } else {
                    clearCompletedVersion(appContext)
                }
                Log.d(TAG, "Download ready, id=$id, version=$version, file=${apkFile(appContext).length() / 1024}KB")

                val activity = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
                if (activity != null) {
                    dispatchDownloadComplete(activity, apkFile(appContext).absolutePath, apkFile(appContext).length())
                } else {
                    showUpdateReadyNotification(appContext)
                }
                activityRef = null
            }
        }

        downloadReceiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    // ── Private: notification ────────────────────────────────────────────────

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications when an app update is ready to install" }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun showUpdateReadyNotification(context: Context) {
        try {
            ensureNotificationChannel(context)

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent ?: Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("$appLabel update ready")
                .setContentText("Open the app to install the update")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val nm = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || nm.areNotificationsEnabled()) {
                nm.notify(NOTIFICATION_ID, notification)
                Log.d(TAG, "Update-ready notification sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "showUpdateReadyNotification failed", e)
        }
    }

    // ── Private: installer ───────────────────────────────────────────────────

    private fun launchInstaller(activity: FragmentActivity, uri: Uri): Map<String, Any> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Log.w(TAG, "REQUEST_INSTALL_PACKAGES not granted — opening Settings")
                activity.runOnUiThread {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${activity.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not open install-unknown-apps settings", e)
                    }
                }
                return BridgeResponse.success(mapOf(
                    "permissionRequired" to true,
                    "message" to "Please grant 'Install unknown apps' permission for this app in Settings, then try again."
                ))
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.runOnUiThread {
                try {
                    Log.d(TAG, "launchInstaller: startActivity uri=$uri")
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "launchInstaller: startActivity FAILED: ${e.message}", e)
                }
            }
            Log.d(TAG, "launchInstaller: system installer queued")
            BridgeResponse.success(mapOf("status" to "installer_launched"))
        } catch (e: Exception) {
            Log.e(TAG, "launchInstaller failed", e)
            BridgeResponse.error("INSTALL_ERROR", e.message ?: "Failed to launch installer")
        }
    }

    // ── Private: helpers ─────────────────────────────────────────────────────

    private fun dispatchOrNotifyFailed(appContext: Context, error: String, bytesDownloaded: Long) {
        val activity = activityRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
        if (activity != null) {
            dispatchDownloadFailed(activity, error, bytesDownloaded, false)
        } else {
            Log.w(TAG, "Download failed (no active UI): $error")
        }
        activityRef = null
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dispatchProgress(activity: FragmentActivity, bytesDownloaded: Long, totalBytes: Long, percent: Int) {
        activity.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(
                activity,
                "Native\\Mobile\\Events\\MobileUpdater\\DownloadProgress",
                JSONObject().apply {
                    put("percent", percent)
                    put("bytesDownloaded", bytesDownloaded)
                    put("totalBytes", totalBytes)
                }.toString()
            )
        }
    }

    private fun dispatchDownloadComplete(activity: FragmentActivity, path: String, size: Long) {
        activity.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(
                activity,
                "Native\\Mobile\\Events\\MobileUpdater\\DownloadComplete",
                JSONObject().apply {
                    put("path", path)
                    put("size", size)
                }.toString()
            )
        }
    }

    private fun dispatchDownloadFailed(activity: FragmentActivity, error: String, bytesDownloaded: Long, resumable: Boolean) {
        activity.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(
                activity,
                "Native\\Mobile\\Events\\MobileUpdater\\DownloadFailed",
                JSONObject().apply {
                    put("error", error)
                    put("bytesDownloaded", bytesDownloaded)
                    put("resumable", resumable)
                }.toString()
            )
        }
    }
}
