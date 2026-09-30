package com.tunisianprayertimes.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.tunisianprayertimes.tv.kiosk.EventLog
import com.tunisianprayertimes.tv.kiosk.KioskController
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
 * it (size, SHA-256 from GitHub, package name, advertised version, same signing certificate), and
 * installs it at night when no prayer is on screen, once the release has been out a couple of days
 * (UpdatePolicy.SETTLE_MILLIS). That night install happens only where Android installs without
 * asking (the device owner, or Android 12+ once the app installed itself before); otherwise the
 * update waits for the admin to install it from settings or the dashboard, where the system asks
 * once, so no confirmation dialog is ever left on the mosque's screen through the night.
 * While the app keeps crashing, the watchdog runs [rescue] outside the display, so a fixed release
 * can replace a broken one. [refusal] says why installing must wait (a prayer on screen), or null.
 */
class GithubUpdater(
    context: Context,
    private val log: EventLog,
    private val refusal: () -> String? = { null },
) : AppUpdater {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val folder = File(app.filesDir, "updates")

    private val currentVersionCode: Int =
        runCatching { PackageInfoCompat.getLongVersionCode(packageInfo(app.packageName, 0)).toInt() }.getOrDefault(0)

    @Volatile private var message: String = ""

    /** When this updater last committed an install (wall clock), so a later failure shows instead of «جارٍ التثبيت». */
    @Volatile private var committedAt: Long = 0L

    override val status: UpdateStatus
        get() {
            val candidate = candidate()
            val failure = prefs.getString(KEY_INSTALL_ERROR, null)
                ?.takeIf { candidate != null && (message.isEmpty() || committedAt in 1..prefs.getLong(KEY_INSTALL_FAILED_AT, 0L)) }
            return UpdateStatus(
                supported = true,
                available = candidate?.versionName,
                message = failure?.let { "فشل تثبيت التحديث ${candidate?.versionName}: $it" }
                    ?: message.ifEmpty { if (candidate == null) "لا يوجد تحديث" else "التحديث ${candidate.versionName} جاهز" },
                needsPermission = !deviceOwner() && !canInstall(),
            )
        }

    override suspend fun tick(online: Boolean, quiet: Boolean) = step(online, quiet, rescue = false)

    override suspend fun rescue(online: Boolean) = step(online, quiet = true, rescue = true)

    private suspend fun step(online: Boolean, quiet: Boolean, rescue: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // A debug build's package is not the released one: every download would be refused.
                if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return@withContext
                if (online && isCheckDue(rescue)) checkReleases()
                val candidate = candidate() ?: return@withContext
                if (online && downloaded(candidate) == null && isDownloadDue()) download(candidate)
                val file = downloaded(candidate) ?: return@withContext
                val installFails = prefs.getInt(KEY_INSTALL_FAILS, 0)
                when {
                    !installsSilently() -> message = "التحديث ${candidate.versionName} جاهز: ثبّته من الإعدادات أو من لوحة الإدارة"
                    installFails >= UpdatePolicy.MAX_INSTALL_FAILS ->
                        message = "تعذّر تثبيت التحديث ${candidate.versionName} عدة مرات: ثبّته من الإعدادات أو من لوحة الإدارة"
                    // The rescue of a crashing app waits neither for the night nor for the release to settle.
                    quiet && UpdatePolicy.installDue(installFails, prefs.getLong(KEY_INSTALL_FAILED_AT, 0L), System.currentTimeMillis()) &&
                        (rescue || UpdatePolicy.settled(candidate.publishedAt, System.currentTimeMillis())) ->
                        install(file, candidate, automatic = true)
                }
            }
        }
    }

    override suspend fun installNow(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Kept in the message too: the kiosk page shows only the status.
            refusal()?.let {
                message = it
                return@withContext it
            }
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
        verify(file, candidate)?.let { return it.reason }
        install(file, candidate, automatic = true)
        return message
    }

    // ------------------------------------------------------------------ check

    /**
     * Once a day, at a time of day of its own, so the TVs behind one mosque router do not all ask at
     * once; a failed check is retried within hours, and a crashing app asks every hour.
     */
    private fun isCheckDue(rescue: Boolean): Boolean {
        val jitter = prefs.getLong(KEY_JITTER, -1L).takeIf { it >= 0 } ?: Random.nextLong(UpdatePolicy.MAX_JITTER_MILLIS).also {
            prefs.edit().putLong(KEY_JITTER, it).apply()
        }
        return UpdatePolicy.checkDue(
            now = System.currentTimeMillis(),
            lastSuccess = prefs.getLong(KEY_LAST_CHECK, 0L),
            lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
            fails = prefs.getInt(KEY_CHECK_FAILS, 0),
            jitter = jitter,
            rescue = rescue,
        )
    }

    private fun checkReleases() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()
        val listing = runCatching { listReleases() }.getOrElse {
            prefs.edit().putInt(KEY_CHECK_FAILS, prefs.getInt(KEY_CHECK_FAILS, 0) + 1).apply()
            message = "تعذّر الاتصال بـ GitHub"
            Log.w(TAG, "release check", it)
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).remove(KEY_CHECK_FAILS).apply()
        // Null: GitHub says the list did not change since the last check, so neither did the candidate.
        val (releases, etag) = listing ?: run {
            message = candidate()?.let { "التحديث ${it.versionName} متوفر" } ?: "لا يوجد تحديث أحدث من ${versionName()}"
            return
        }
        val found = GithubReleases.newest(UpdatePolicy.notRejected(releases, prefs.getString(KEY_REJECTED, null)), currentVersionCode)
        when (UpdatePolicy.onReleasesListed(found, releases.isNotEmpty())) {
            UpdatePolicy.Listing.REPLACE -> {
                found!!
                prefs.edit().apply {
                    if (found.versionCode != prefs.getInt(KEY_CODE, 0)) CANDIDATE_STATE.forEach(::remove)
                    putString(KEY_NAME, found.versionName)
                    putInt(KEY_CODE, found.versionCode)
                    putString(KEY_URL, found.url)
                    putLong(KEY_SIZE, found.size)
                    putString(KEY_SHA, found.sha256)
                    if (found.publishedAt != null) putLong(KEY_PUBLISHED, found.publishedAt) else remove(KEY_PUBLISHED)
                }.apply()
                message = "التحديث ${found.versionName} متوفر"
                log.append(KioskEvent.UPDATE, "found ${found.versionName} (${found.versionCode})")
            }
            UpdatePolicy.Listing.WITHDRAW -> {
                forgetCandidate()
                message = "لا يوجد تحديث أحدث من ${versionName()}"
            }
            UpdatePolicy.Listing.KEEP ->
                message = candidate()?.let { "التحديث ${it.versionName} متوفر" } ?: "لا يوجد تحديث أحدث من ${versionName()}"
        }
        prefs.edit().apply { if (etag != null) putString(KEY_ETAG, etag) else remove(KEY_ETAG) }.apply()
    }

    /**
     * The TV releases GitHub lists and the first page's ETag, or null when that page is unchanged
     * since the last check (a 304, which GitHub does not count against its limit). Throws when
     * GitHub cannot be read.
     */
    private fun listReleases(): Pair<List<ReleaseAsset>, String?>? {
        val (json, etag) = get(GithubReleases.FIRST_PAGE_URL, prefs.getString(KEY_ETAG, null)) ?: return null
        val first = GithubReleases.page(json) ?: error("not a release list")
        if (first.tvReleases.isNotEmpty() || first.size < GithubReleases.FIRST_PAGE_SIZE) return first.tvReleases to etag
        val releases = mutableListOf<ReleaseAsset>()
        for (number in 1..GithubReleases.MAX_PAGES) {
            val page = GithubReleases.page(get(GithubReleases.pageUrl(number))!!.first) ?: error("not a release list")
            releases += page.tvReleases
            // Newest first: past the first TV releases, the next pages only hold older ones.
            if (page.tvReleases.isNotEmpty() || page.size < GithubReleases.PAGE_SIZE) break
        }
        return releases to etag
    }

    private fun candidate(): ReleaseAsset? {
        val code = prefs.getInt(KEY_CODE, 0).takeIf { it > currentVersionCode } ?: return null
        return ReleaseAsset(
            versionName = prefs.getString(KEY_NAME, null) ?: return null,
            versionCode = code,
            url = prefs.getString(KEY_URL, null) ?: return null,
            size = prefs.getLong(KEY_SIZE, -1L),
            sha256 = prefs.getString(KEY_SHA, null),
            publishedAt = prefs.getLong(KEY_PUBLISHED, -1L).takeIf { prefs.contains(KEY_PUBLISHED) },
        )
    }

    private fun forgetCandidate() {
        prefs.edit().apply { (listOf(KEY_NAME, KEY_CODE, KEY_URL, KEY_SIZE, KEY_SHA, KEY_PUBLISHED) + CANDIDATE_STATE).forEach(::remove) }.apply()
        folder.listFiles().orEmpty().forEach { it.delete() }
    }

    /**
     * The downloaded release can never be installed here (another package, a version other than the
     * advertised one, another signature, or not newer than the installed one): it is dropped and
     * skipped until GitHub lists another, instead of being downloaded again every day.
     */
    private fun reject(candidate: ReleaseAsset, reason: String) {
        forgetCandidate()
        // The ETag goes too: the next check must read the list again to find a release worth taking.
        prefs.edit().putString(KEY_REJECTED, UpdatePolicy.rejectionKey(candidate)).remove(KEY_ETAG).apply()
        message = "رُفض التحديث ${candidate.versionName}: $reason"
        log.append(KioskEvent.UPDATE, "rejected ${candidate.versionName} (${candidate.versionCode}): $reason")
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
            verify(part, candidate)?.let { problem ->
                if (problem.final) {
                    part.delete()
                    reject(candidate, problem.reason)
                    return null
                }
                error(problem.reason)
            }
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
    private fun isDownloadDue(): Boolean =
        UpdatePolicy.downloadDue(prefs.getInt(KEY_FAILS, 0), prefs.getLong(KEY_FAILED_AT, 0L), System.currentTimeMillis())

    /** Why a file is not the update: [final] when downloading it again cannot change that. */
    private class Problem(val reason: String, val final: Boolean = false)

    /** Null when [file] is the expected, genuine update; otherwise why not. */
    private fun verify(file: File, candidate: ReleaseAsset): Problem? {
        if (file.length() != candidate.size) return Problem("الحجم غير مطابق")
        candidate.sha256?.let { expected -> if (sha256(file) != expected) return Problem("البصمة غير مطابقة") }
        val archive = archiveInfo(file) ?: return Problem("ليس ملف تطبيق صالحًا")
        if (archive.packageName != app.packageName) return Problem("تطبيق آخر", final = true)
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code <= currentVersionCode) return Problem("ليس أحدث من الإصدار الحالي", final = true)
        // The version GitHub's asset name advertised is the one kept and compared: the APK must be it.
        if (code != candidate.versionCode.toLong() || archive.versionName != candidate.versionName) return Problem("الإصدار غير مطابق", final = true)
        val theirs = signers(archive)
        val ours = signers(packageInfo(app.packageName, signingFlags()))
        if (theirs.isEmpty() || theirs.intersect(ours).isEmpty()) return Problem("التوقيع غير مطابق", final = true)
        return null
    }

    // ------------------------------------------------------------------ install

    private fun canInstall(): Boolean = runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** A device owner installs without asking and without the "unknown apps" permission, on any Android version. */
    private fun deviceOwner(): Boolean = KioskController.isDeviceOwner(app)

    /**
     * Whether Android installs without asking: always for the device owner; otherwise from Android 12,
     * and not on a box that asked for a confirmation for this installed version (the first update after
     * installing the APK by hand, or a box that refuses the silent path). An update installed since then
     * is tried silently again.
     */
    private fun installsSilently(): Boolean =
        UpdatePolicy.installsSilently(Build.VERSION.SDK_INT, deviceOwner(), prefs.getInt(KEY_ASKED_AT_VERSION, -1), currentVersionCode)

    private fun install(file: File, candidate: ReleaseAsset, automatic: Boolean) {
        if (!deviceOwner() && !canInstall()) {
            message = "اسمح للتطبيق بتثبيت التحديثات: إعدادات الجهاز ← التطبيقات ← تثبيت التطبيقات غير المعروفة"
            return
        }
        // The file may have lain here for days: it must still be this update, and newer than the installed one.
        verify(file, candidate)?.let { problem ->
            if (problem.final) reject(candidate, problem.reason) else file.delete().also { message = "فشل تثبيت التحديث: ${problem.reason}" }
            return
        }
        // Checked again right before installing: a download can take minutes, and installing ends the app.
        refusal()?.let {
            message = it
            return
        }
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRequestUpdateOwnership(true)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("tv.apk", 0, file.length()).use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val result = Intent(app, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.EXTRA_AUTOMATIC, automatic)
                val callback = PendingIntent.getBroadcast(app, sessionId, result, flags)
                committedAt = System.currentTimeMillis()
                session.commit(callback.intentSender)
            }
            message = if (installsSilently()) "جارٍ تثبيت التحديث ${candidate.versionName}: تعود الشاشة بعد لحظات"
            else "اضغط «تثبيت» على شاشة المسجد لتثبيت التحديث ${candidate.versionName}"
            log.append(KioskEvent.UPDATE, "installing ${candidate.versionName} (${candidate.versionCode})")
        }.onFailure {
            // Closing a session does not abandon it: a half-written one would stay on the box's storage.
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            InstallResultReceiver.recordFailure(app, it.message.orEmpty())
            message = "فشل تثبيت التحديث: ${it.message}"
            log.append(KioskEvent.UPDATE, "install failed: ${it.message}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun versionName(): String = runCatching { packageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The body and its ETag; null when [etag] still matches (304 Not Modified). */
    private fun get(url: String, etag: String? = null): Pair<String, String?>? {
        val connection = open(url)
        try {
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            if (connection.responseCode == 304 && etag != null) return null
            if (connection.responseCode == 403 || connection.responseCode == 429) error("GitHub limit reached")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to connection.getHeaderField("ETag")
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
        const val KEY_INSTALL_FAILS = "install_fails"
        const val KEY_INSTALL_FAILED_AT = "install_failed_at"
        const val KEY_INSTALL_ERROR = "install_error"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_LAST_ATTEMPT = "last_check_attempt"
        const val KEY_CHECK_FAILS = "check_fails"
        const val KEY_ETAG = "releases_etag"
        const val KEY_JITTER = "check_jitter"
        const val KEY_REJECTED = "rejected_release"
        const val KEY_NAME = "candidate_name"
        const val KEY_CODE = "candidate_code"
        const val KEY_URL = "candidate_url"
        const val KEY_SIZE = "candidate_size"
        const val KEY_SHA = "candidate_sha256"
        const val KEY_PUBLISHED = "candidate_published_at"

        /** What belongs to one update and starts over with another: download and install failures. */
        val CANDIDATE_STATE = listOf(KEY_FAILS, KEY_FAILED_AT, KEY_INSTALL_FAILS, KEY_INSTALL_FAILED_AT, KEY_INSTALL_ERROR)

        /** One for the whole process: the display and the watchdog's rescue must not download or install at once. */
        val mutex = Mutex()
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
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                // The admin pressing «إلغاء» on the system's dialog is not a failure to back off from.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) recordFailure(context, "($status) $detail")
                log.append(KioskEvent.UPDATE, "failed ($status): $detail")
            }
        }
    }

    companion object {
        const val EXTRA_AUTOMATIC = "automatic"

        /** A failed install: the night install backs off (UpdatePolicy.installDue), and the admin sees why. */
        fun recordFailure(context: Context, error: String) {
            val prefs = context.getSharedPreferences(GithubUpdater.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(GithubUpdater.KEY_INSTALL_FAILS, prefs.getInt(GithubUpdater.KEY_INSTALL_FAILS, 0) + 1)
                .putLong(GithubUpdater.KEY_INSTALL_FAILED_AT, System.currentTimeMillis())
                .putString(GithubUpdater.KEY_INSTALL_ERROR, error)
                .apply()
        }
    }
}
