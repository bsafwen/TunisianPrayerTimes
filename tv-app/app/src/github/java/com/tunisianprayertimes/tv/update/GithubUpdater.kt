package com.tunisianprayertimes.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.tunisianprayertimes.tv.kiosk.EventLog
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskStore
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Updates of the GitHub build, for mosque boxes without Play Store. About once a day, when online,
 * it looks for a newer TV release on GitHub, downloads its APK into the app's own storage, checks
 * it (size, SHA-256 from GitHub, package name, higher version, same signing certificate), and
 * installs it at night when no prayer is on screen. That night install happens only where Android
 * installs without asking (Android 12+, once the app installed itself before); otherwise the update
 * waits for the admin to install it from settings or the dashboard, where the system asks once, so
 * no confirmation dialog is ever left on the mosque's screen through the night.
 */
class GithubUpdater(context: Context, private val log: EventLog) : AppUpdater {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val folder = File(app.filesDir, "updates")

    private val currentVersionCode: Int =
        runCatching { PackageInfoCompat.getLongVersionCode(packageInfo(app.packageName, 0)).toInt() }.getOrDefault(0)

    @Volatile private var message: String = ""

    override val status: UpdateStatus
        get() {
            val candidate = candidate()
            return UpdateStatus(
                supported = true,
                available = candidate?.versionName,
                message = message.ifEmpty { if (candidate == null) "لا يوجد تحديث" else "التحديث ${candidate.versionName} جاهز" },
                needsPermission = !canInstall(),
            )
        }

    override suspend fun tick(online: Boolean, quiet: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (online && isCheckDue()) checkReleases()
                val candidate = candidate() ?: return@withContext
                if (online && downloaded(candidate) == null && isDownloadDue()) download(candidate)
                val file = downloaded(candidate) ?: return@withContext
                if (!installsSilently()) message = "التحديث ${candidate.versionName} جاهز: ثبّته من الإعدادات أو من لوحة الإدارة"
                else if (quiet) install(file, candidate, automatic = true)
            }
        }
    }

    override suspend fun installNow(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            checkReleases()
            val candidate = candidate() ?: return@withContext message.ifEmpty { "لا يوجد تحديث أحدث من ${versionName()}" }
            val file = downloaded(candidate) ?: download(candidate) ?: return@withContext message
            install(file, candidate, automatic = false)
            message
        }
    }

    /**
     * Installs [file] through the same checks and installer as a downloaded update (package name,
     * higher version, same signature). Only the debug build's test receiver calls it.
     */
    internal fun installLocal(file: File): String {
        val archive = archiveInfo(file) ?: return "ليس ملف تطبيق صالحًا"
        val candidate = ReleaseAsset(archive.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(archive).toInt(), "local", file.length(), null)
        verify(file, candidate)?.let { return it }
        install(file, candidate, automatic = true)
        return message
    }

    // ------------------------------------------------------------------ check

    /** Once a day, at a time of day of its own, so the TVs behind one mosque router do not all ask at once. */
    private fun isCheckDue(): Boolean {
        val jitter = prefs.getLong(KEY_JITTER, -1L).takeIf { it >= 0 } ?: Random.nextLong(MAX_JITTER_MILLIS).also {
            prefs.edit().putLong(KEY_JITTER, it).apply()
        }
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        return now < last || now - last >= CHECK_EVERY_MILLIS + jitter
    }

    private fun checkReleases() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val releases = mutableListOf<ReleaseAsset>()
        for (number in 1..GithubReleases.MAX_PAGES) {
            val json = runCatching { get(GithubReleases.pageUrl(number)) }.getOrElse {
                message = "تعذّر الاتصال بـ GitHub"
                Log.w(TAG, "release check", it)
                return
            }
            val page = GithubReleases.page(json) ?: run {
                message = "تعذّر الاتصال بـ GitHub"
                return
            }
            releases += page.tvReleases
            // Newest first: past the first TV releases, the next pages only hold older ones.
            if (page.tvReleases.isNotEmpty() || page.size < GithubReleases.PAGE_SIZE) break
        }
        val found = GithubReleases.newest(releases, currentVersionCode)
        when {
            found != null -> {
                prefs.edit().apply {
                    if (found.versionCode != prefs.getInt(KEY_CODE, 0)) remove(KEY_FAILS).remove(KEY_FAILED_AT)
                    putString(KEY_NAME, found.versionName)
                    putInt(KEY_CODE, found.versionCode)
                    putString(KEY_URL, found.url)
                    putLong(KEY_SIZE, found.size)
                    putString(KEY_SHA, found.sha256)
                }.apply()
                message = "التحديث ${found.versionName} متوفر"
                log.append(KioskEvent.UPDATE, "found ${found.versionName} (${found.versionCode})")
            }
            // TV releases were listed and none is newer: an update seen before was withdrawn.
            releases.isNotEmpty() -> {
                prefs.edit().apply { listOf(KEY_NAME, KEY_CODE, KEY_URL, KEY_SIZE, KEY_SHA, KEY_FAILS, KEY_FAILED_AT).forEach(::remove) }.apply()
                folder.listFiles().orEmpty().forEach { it.delete() }
                message = "لا يوجد تحديث أحدث من ${versionName()}"
            }
            // No TV release in the pages read: what was found before stays.
            else -> message = candidate()?.let { "التحديث ${it.versionName} متوفر" } ?: "لا يوجد تحديث أحدث من ${versionName()}"
        }
    }

    private fun candidate(): ReleaseAsset? {
        val code = prefs.getInt(KEY_CODE, 0).takeIf { it > currentVersionCode } ?: return null
        return ReleaseAsset(
            versionName = prefs.getString(KEY_NAME, null) ?: return null,
            versionCode = code,
            url = prefs.getString(KEY_URL, null) ?: return null,
            size = prefs.getLong(KEY_SIZE, -1L),
            sha256 = prefs.getString(KEY_SHA, null),
        )
    }

    // ------------------------------------------------------------------ download and verify

    private fun apkFile(candidate: ReleaseAsset) = File(folder, "tv-${candidate.versionCode}.apk")

    /** The verified APK of [candidate] if it is already here. */
    private fun downloaded(candidate: ReleaseAsset): File? = apkFile(candidate).takeIf { it.isFile && it.length() == candidate.size }

    private fun download(candidate: ReleaseAsset): File? {
        folder.mkdirs()
        folder.listFiles().orEmpty().filter { it.name != apkFile(candidate).name }.forEach { it.delete() } // older ones
        val part = File(folder, "tv-${candidate.versionCode}.part")
        val result = runCatching {
            val connection = open(candidate.url)
            try {
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            val problem = verify(part, candidate)
            check(problem == null) { problem.orEmpty() }
            check(part.renameTo(apkFile(candidate))) { "rename" }
            apkFile(candidate)
        }
        return result.fold(
            onSuccess = {
                prefs.edit().remove(KEY_FAILS).remove(KEY_FAILED_AT).apply()
                it
            },
            onFailure = {
                part.delete()
                prefs.edit().putInt(KEY_FAILS, prefs.getInt(KEY_FAILS, 0) + 1).putLong(KEY_FAILED_AT, System.currentTimeMillis()).apply()
                message = "فشل تنزيل التحديث: ${it.message}"
                log.append(KioskEvent.UPDATE, "download failed: ${it.message}")
                null
            },
        )
    }

    /**
     * After a failed download, the next try waits 1 hour, then 2, 4… up to a day, so a box on a
     * phone's metered hotspot does not download the same 20 MB every half hour.
     */
    private fun isDownloadDue(): Boolean {
        val fails = prefs.getInt(KEY_FAILS, 0)
        if (fails == 0) return true
        val wait = minOf(RETRY_FIRST_MILLIS shl (fails - 1).coerceAtMost(10), RETRY_MAX_MILLIS)
        val last = prefs.getLong(KEY_FAILED_AT, 0L)
        val now = System.currentTimeMillis()
        return now < last || now - last >= wait
    }

    /** Null when [file] is the expected, genuine update; otherwise why not. */
    private fun verify(file: File, candidate: ReleaseAsset): String? {
        if (file.length() != candidate.size) return "الحجم غير مطابق"
        candidate.sha256?.let { expected -> if (sha256(file) != expected) return "البصمة غير مطابقة" }
        val archive = archiveInfo(file) ?: return "ليس ملف تطبيق صالحًا"
        if (archive.packageName != app.packageName) return "تطبيق آخر"
        if (PackageInfoCompat.getLongVersionCode(archive) <= currentVersionCode) return "ليس أحدث من الإصدار الحالي"
        val theirs = signers(archive)
        val ours = signers(packageInfo(app.packageName, signingFlags()))
        if (theirs.isEmpty() || theirs.intersect(ours).isEmpty()) return "التوقيع غير مطابق"
        return null
    }

    // ------------------------------------------------------------------ install

    private fun canInstall(): Boolean = runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * Whether Android installs without asking: from Android 12, and not on a box that asked for a
     * confirmation for this installed version (the first update after installing the APK by hand, or
     * a box that refuses the silent path). An update installed since then is tried silently again.
     */
    private fun installsSilently(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.getInt(KEY_ASKED_AT_VERSION, -1) != currentVersionCode

    private fun install(file: File, candidate: ReleaseAsset, automatic: Boolean) {
        if (!canInstall()) {
            message = "اسمح للتطبيق بتثبيت التحديثات: إعدادات الجهاز ← التطبيقات ← تثبيت التطبيقات غير المعروفة"
            return
        }
        runCatching {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRequestUpdateOwnership(true)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("tv.apk", 0, file.length()).use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val result = Intent(app, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.EXTRA_AUTOMATIC, automatic)
                val callback = PendingIntent.getBroadcast(app, sessionId, result, flags)
                session.commit(callback.intentSender)
            }
            message = if (installsSilently()) "جارٍ تثبيت التحديث ${candidate.versionName}: تعود الشاشة بعد لحظات"
            else "اضغط «تثبيت» على شاشة المسجد لتثبيت التحديث ${candidate.versionName}"
            log.append(KioskEvent.UPDATE, "installing ${candidate.versionName} (${candidate.versionCode})")
        }.onFailure {
            message = "فشل تثبيت التحديث: ${it.message}"
            log.append(KioskEvent.UPDATE, "install failed: ${it.message}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun versionName(): String = runCatching { packageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun get(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode == 403 || connection.responseCode == 429) error("GitHub limit reached")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** HTTPS only, following GitHub's redirect to its download host, with no credentials at all. */
    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "not https" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TunisianPrayerTimesTV/${versionName()}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? =
        app.packageManager.getPackageArchiveInfo(file.path, signingFlags())

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String, flags: Int): PackageInfo = app.packageManager.getPackageInfo(name, flags)

    /** SHA-256 of each signing certificate; the old field on Android 8-10 (the new one may be empty there for archives). */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return certificates.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
        }.toSet()
    }

    internal companion object {
        const val TAG = "Updates"
        const val PREFS = "updates"
        const val KEY_ASKED_AT_VERSION = "asked_at_version"
        const val KEY_FAILS = "download_fails"
        const val KEY_FAILED_AT = "download_failed_at"
        const val RETRY_FIRST_MILLIS = 60 * 60 * 1000L
        const val RETRY_MAX_MILLIS = 24 * 60 * 60 * 1000L
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_JITTER = "check_jitter"
        const val KEY_NAME = "candidate_name"
        const val KEY_CODE = "candidate_code"
        const val KEY_URL = "candidate_url"
        const val KEY_SIZE = "candidate_size"
        const val KEY_SHA = "candidate_sha256"
        const val CHECK_EVERY_MILLIS = 20 * 60 * 60 * 1000L
        const val MAX_JITTER_MILLIS = 4 * 60 * 60 * 1000L
    }
}

/**
 * The system's answer to an install. Where Android still wants a confirmation, an install the admin
 * asked for shows the system's dialog for them to press OK. A night install that turns out to need
 * one is dropped instead (no dialog left on the screen), and the box waits for the admin.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val log = KioskStore(context).eventLog
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (intent.getBooleanExtra(EXTRA_AUTOMATIC, false)) {
                    val version = runCatching {
                        @Suppress("DEPRECATION")
                        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
                    }.getOrDefault(0)
                    context.getSharedPreferences(GithubUpdater.PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(GithubUpdater.KEY_ASKED_AT_VERSION, version).apply()
                    val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
                    log.append(KioskEvent.UPDATE, "the box asks for a confirmation: waiting for the admin")
                    return
                }
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                log.append(KioskEvent.UPDATE, "waiting for confirmation on screen")
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> log.append(KioskEvent.UPDATE, "installed")
            else -> log.append(KioskEvent.UPDATE, "failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        }
    }

    companion object {
        const val EXTRA_AUTOMATIC = "automatic"
    }
}
