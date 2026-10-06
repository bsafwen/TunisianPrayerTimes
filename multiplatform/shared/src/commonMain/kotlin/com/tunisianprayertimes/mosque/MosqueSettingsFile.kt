package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.TunisianHijriCalendar
import com.tunisianprayertimes.YearDates
import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.countForCollection
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The settings file a mosque admin puts on a USB key (see [write] for the canonical form):
 *
 *     { "format": "tunisian-prayer-times-tv", "version": 1,
 *       "mosque": { "name": "مسجد الفتح", "delegation": 615 },
 *       "display": { "theme": "horizon" },
 *       "prayerTimes": { "fajrAngle": 17.5, "asrShadow": 1, "adjust": { "isha": "+2" } },
 *       "prayers": { "fajr": { "iqamah": "+20", "duration": 10 }, "isha": { "iqamah": "20:00" },
 *                    "eid": { "iqamah": "+30", "duration": 30 } },
 *       "ramadan": { "isha": { "duration": 75 } },
 *       "islamicDates": { "1448": { "ramadanStart": "2027-02-08", "eidFitr": "2027-03-10" } } }
 *
 * "iqamah" is "+N" minutes after the adhan (after sunrise for the Eid prayers) or a fixed "HH:MM";
 * "duration" is the prayer's length in minutes (the black screen); "held": false says the mosque
 * does not hold Jumu'a or an Eid prayer; Jumu'a's "khutba" is the sermon's length in minutes before
 * its iqamah (0: the whole wait after the adhan), and its "dua": false puts the khutba screen right after
 * the adhan screen, without the minute of the dua after the adhan (true, as by default, shows it: the imam
 * says it too). "ramadan" changes prayers on the
 * days of Ramadan (null returns a field to the usual setting). "islamicDates" sets the year's Ramadan
 * and Eid dates by hand (null returns a date to automatic). "mosque" and "display" carry the rest of a
 * TV's settings, so a file written by one TV sets up another. "adhkar" puts the mosque's own texts
 * after the bundled, reviewed ones ("mode": "append") or in their place ("replace"); each needs a
 * "reference"; null returns to the bundled texts. "announcements" lists written announcements with
 * optional "from" and "until" dates, and replaces the TV's list. "prayerTimes" changes how the times are
 * computed (PrayerFormulaSettings: "fajrAngle", "ishaAngle", "asrShadow", "dhuhrMinutes", "maghribMinutes",
 * "elevation", and "adjust" in whole minutes per prayer); a field left out stays as the TV has it, null
 * returns a field (or the whole section) to INM's official value. Every field is optional. Files are edited
 * by hand, so Arabic names, Arabic-Indic digits, "20h00", comments and trailing commas are accepted.
 * A file is applied whole or not at all.
 */
object MosqueSettingsFile {

    const val FILE_NAME = "mosque-tv.json"
    const val FOLDER = "MosqueTV"
    const val FORMAT = "tunisian-prayer-times-tv"
    const val VERSION = 1
    /** Room for everything a TV can hold (two lists of 100 own texts, announcements), written back whole. */
    const val MAX_CHARS = 400_000

    /** How far a hand-set Ramadan or Eid date may be from the calendar estimate. */
    private const val MAX_DAYS_FROM_ESTIMATE = 5L

    enum class Field { IQAMAH, DURATION, HELD, KHUTBA, DUA }

    /** One setting that the file changes, rendered as in the file ("+15", "20:00", "10", "false"); [ramadan] for Ramadan changes. */
    data class Change(val prayer: Prayer, val field: Field, val before: String, val after: String, val ramadan: Boolean = false)

    enum class DateEvent { RAMADAN_START, EID_FITR, EID_ADHA }

    /** A Ramadan or Eid date the file sets or returns to automatic (null). */
    data class DateChange(val hijriYear: Int, val event: DateEvent, val before: LocalDate?, val after: LocalDate?)

    enum class ProfileField { NAME, DELEGATION, THEME, WEATHER, BACKGROUNDS, ANNOUNCEMENTS, SLIDE_SECONDS, ANNOUNCEMENTS_EVERY, NIGHT_SCREEN, ADHAN_SCREEN }

    /**
     * The mosque's name, place, theme or a display option changed by the file: the name, place and
     * theme as the admin reads them, the options as in the file ("true", "15"); "—" when unset.
     */
    data class ProfileChange(val field: ProfileField, val before: String, val after: String)

    enum class FormulaField { FAJR_ANGLE, ISHA_ANGLE, ASR_SHADOW, DHUHR_MINUTES, MAGHRIB_MINUTES, ELEVATION, ADJUSTMENT }

    /**
     * A value of the prayer-time computation ("prayerTimes") that the file changes, rendered as in the
     * file: "17.5", "2", "7", "false", and an [ADJUSTMENT][FormulaField.ADJUSTMENT] of [prayer] signed
     * ("+2", "-1", "0"). Every value has an official one, so there is no "—".
     */
    data class FormulaChange(val field: FormulaField, val prayer: Prayer?, val before: String, val after: String)

    enum class ContentList { AFTER_SALAH, TICKER, ANNOUNCEMENTS }

    /**
     * The texts shown after the prayer or in the ticker, or the written announcements, before and
     * after the file, described for the admin: a summary of each, the texts [added] and [removed]
     * (by their titles, or first words), the texts kept but changed ([recounted]: "title: العدد 3 ← 5";
     * [resourced]: another source; [reworded]: the mosque's own text edited; [redated]: an
     * announcement's dates), or only a new order ([reordered]).
     */
    data class ContentChange(
        val list: ContentList,
        val before: String,
        val after: String,
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val recounted: List<String> = emptyList(),
        val reordered: Boolean = false,
        val resourced: List<String> = emptyList(),
        val reworded: List<String> = emptyList(),
        val redated: List<String> = emptyList(),
    )

    enum class ErrorCode {
        TOO_LARGE, INVALID_JSON, NOT_AN_OBJECT, UNKNOWN_FORMAT, UNSUPPORTED_VERSION, NO_PRAYERS,
        UNKNOWN_PRAYER, DUPLICATE_PRAYER, NOT_A_PRAYER_OBJECT, UNKNOWN_FIELD, DUPLICATE_FIELD,
        INVALID_IQAMAH, IQAMAH_OUT_OF_RANGE, INVALID_DURATION, DURATION_OUT_OF_RANGE,
        INVALID_YEAR, INVALID_DATE, DATE_OUT_OF_RANGE, INVALID_NAME, UNKNOWN_DELEGATION, UNKNOWN_THEME,
        INVALID_ADHKAR, INVALID_ANNOUNCEMENT, INVALID_OPTION, DATES_CONFLICT, INVALID_ENCODING, INVALID_FORMULA,
    }

    /** A problem in the file: [path] locates it (for example prayers.isha.iqamah); [message] is for the TV screen. */
    data class SettingsError(val code: ErrorCode, val path: String, val message: String)

    sealed interface ParseResult {
        /**
         * [islamicDates] holds the complete admin dates of every year the file mentions; the prayer-time
         * values are the [profile]'s ([formula]), with [formulaChanges] from the TV's.
         */
        data class Success(
            val schedule: MosqueSchedule,
            val changes: List<Change>,
            val islamicDates: Map<Int, ManualIslamicDates> = emptyMap(),
            val dateChanges: List<DateChange> = emptyList(),
            val profile: MosqueProfile = MosqueProfile(),
            val profileChanges: List<ProfileChange> = emptyList(),
            val content: AdhkarContent = AdhkarContent(),
            val contentChanges: List<ContentChange> = emptyList(),
            val announcements: List<TextAnnouncement> = emptyList(),
            val formulaChanges: List<FormulaChange> = emptyList(),
        ) : ParseResult {
            val hasChanges: Boolean
                get() = changes.isNotEmpty() || dateChanges.isNotEmpty() || profileChanges.isNotEmpty() || contentChanges.isNotEmpty() ||
                    formulaChanges.isNotEmpty()

            /** The values the TV computes its times with after the file (INM's official ones unless set). */
            val formula: PrayerFormulaSettings get() = profile.formulaSettings
        }

        /** The first [MAX_ERRORS] mistakes, and how many [more] a hostile or badly broken file has. */
        data class Failure(val errors: List<SettingsError>, val more: Int = 0) : ParseResult
    }

    /** A screen of mistakes is enough to fix a file by hand; tens of thousands would stall the TV. */
    const val MAX_ERRORS = 50

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowTrailingComma = true
        allowComments = true
    }

    /**
     * Reads [text] against the [current] settings, the admin's [currentDates] and [currentProfile];
     * [catalog] tells which places and themes exist. Never throws, even for hostile input (deep nesting).
     *
     * [stored] is for the TV's own saved state, which passed these checks when it was saved: a
     * reviewed text an app update no longer has, or a count that no longer applies, is then kept
     * or dropped quietly instead of losing all the mosque's lists, and its Ramadan and Eid dates are
     * not checked again against announcements that arrived since, so undoing an import returns
     * whole (a file from a key or the dashboard is always checked in full).
     *
     * [yearDates] gives the TV's Ramadan and Eid dates of a Hijri year (IslamicDays.yearDates), so a
     * date the file sets is checked against the announced ones too; null checks it against the
     * admin's other dates only.
     */
    fun parse(
        text: String,
        current: MosqueSchedule,
        currentDates: Map<Int, ManualIslamicDates> = emptyMap(),
        currentProfile: MosqueProfile = MosqueProfile(),
        catalog: ProfileCatalog = ProfileCatalog.NONE,
        currentContent: AdhkarContent = AdhkarContent(),
        currentAnnouncements: List<TextAnnouncement> = emptyList(),
        stored: Boolean = false,
        yearDates: (Int) -> YearDates? = { null },
    ): ParseResult = try {
        parseOrFail(text, current, currentDates, currentProfile, catalog, currentContent, currentAnnouncements, stored, yearDates)
    } catch (e: Throwable) {
        ParseResult.Failure(listOf(SettingsError(ErrorCode.INVALID_JSON, "", MESSAGE_INVALID_JSON)))
    }

    private fun parseOrFail(
        text: String,
        current: MosqueSchedule,
        currentDates: Map<Int, ManualIslamicDates>,
        currentProfile: MosqueProfile,
        catalog: ProfileCatalog,
        currentContent: AdhkarContent,
        currentAnnouncements: List<TextAnnouncement>,
        stored: Boolean,
        yearDates: (Int) -> YearDates?,
    ): ParseResult {
        if (text.length > MAX_CHARS) return fail(ErrorCode.TOO_LARGE, "", "الملف كبير جدًا: هذا ليس ملف إعدادات شاشة المسجد")
        // What no encoding the TV knows could read (UTF-16 without its mark, a stray binary file).
        if (text.any { it == '�' || it == '\u0000' }) {
            return fail(ErrorCode.INVALID_ENCODING, "", "تعذّرت قراءة حروف الملف: احفظه بترميز UTF-8 ثم أعد المحاولة")
        }
        val keys = scanKeys(text)
        if (keys == KeyScan.TooDeep) return fail(ErrorCode.INVALID_JSON, "", MESSAGE_INVALID_JSON)
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: Exception) {
            return fail(ErrorCode.INVALID_JSON, "", invalidJson(text, e))
        }
        if (root !is JsonObject) return fail(ErrorCode.NOT_AN_OBJECT, "", MESSAGE_INVALID_JSON)
        // The JSON reader keeps the last of two identical keys: the admin's added line would vanish.
        if (keys is KeyScan.Duplicate) {
            return fail(ErrorCode.DUPLICATE_FIELD, keys.path, "«${short(keys.path.substringAfterLast('.'))}» مذكور مرتين في الملف: احذف أحدهما")
        }
        rootErrors(root).takeIf { it.isNotEmpty() }?.let { return failure(it) }

        root["format"]?.let { format ->
            if (format.stringOrNull()?.let(::clean)?.trim() != FORMAT) {
                return fail(ErrorCode.UNKNOWN_FORMAT, "format", "هذا الملف ليس ملف إعدادات شاشة المسجد")
            }
        }
        root["version"]?.let { version ->
            if (version.intOrNull() != VERSION) {
                return fail(ErrorCode.UNSUPPORTED_VERSION, "version", "إصدار الملف غير مدعوم: حدّث التطبيق أو استعمل ${code("\"version\": 1")}")
            }
        }
        val prayers = root.section("prayers", "الصلوات")
        val ramadan = root.section("ramadan", "رمضان")
        val dates = root.section("islamicdates", "islamic_dates", "dates", "التواريخ")
        val mosque = root.section("mosque", "masjid", "المسجد")
        val display = root.section("display", "screen", "العرض", "الشاشة")
        val adhkar = root.entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in ADHKAR_SECTION }
        val announcementsEntry = root.entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in ANNOUNCEMENTS_SECTION }
        // An object or null (rootErrors): null returns the times to INM's official values, which is a setting too.
        val formulaEntry = root.entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in FORMULA_SECTION }
        val noFormula = formulaEntry == null || (formulaEntry.value as? JsonObject)?.isEmpty() == true
        if (adhkar == null && announcementsEntry == null && noFormula && listOf(prayers, ramadan, dates, mosque, display).all { it == null || it.second.isEmpty() }) {
            return fail(ErrorCode.NO_PRAYERS, "prayers", "لا يحتوي الملف على إعدادات الصلوات (${code("\"prayers\"")})")
        }

        val errors = mutableListOf<SettingsError>()
        var schedule = current
        prayers?.let { (sectionKey, section) ->
            forEachPrayer(section, sectionKey, errors, allowEid = true) { prayer, fields, path ->
                val settings = readFields(fields, prayer, path, errors, schedule.settings(prayer), allowHeld = true) { base, field, value ->
                    when (field) {
                        Field.IQAMAH -> base.copy(iqamah = value as IqamahRule)
                        Field.DURATION -> base.copy(salahMinutes = value as Int)
                        Field.HELD -> base.copy(held = value as Boolean)
                        Field.KHUTBA -> base.copy(khutbaMinutes = value as Int)
                        Field.DUA -> base.copy(adhanDua = value as Boolean)
                    }
                }
                schedule = schedule.with(prayer, settings)
            }
        }
        ramadan?.let { (sectionKey, section) ->
            forEachPrayer(section, sectionKey, errors, allowEid = false) { prayer, fields, path ->
                val override = readFields(fields, prayer, path, errors, schedule.ramadan[prayer] ?: PrayerOverride(), clearOnNull = true) { base, field, value ->
                    when (field) {
                        Field.IQAMAH -> base.copy(iqamah = value as IqamahRule?)
                        Field.DURATION -> base.copy(salahMinutes = value as Int?)
                        Field.HELD, Field.KHUTBA, Field.DUA -> base // refused by readFields: the same all year
                    }
                }
                schedule = schedule.withRamadan(prayer, override)
            }
        }
        val newDates = dates?.let { (sectionKey, section) -> readDates(section, sectionKey, currentDates, yearDates.takeUnless { stored }, errors) }.orEmpty()
        val profile = readProfile(mosque, display, currentProfile, catalog, errors).let { profile ->
            val formula = formulaEntry?.let { (key, value) -> readFormula(key, value, currentProfile.formulaSettings, errors) }
                ?: profile.formula
            // Stored as null when official, with or without the section (the TV may hand an explicit
            // OFFICIAL over), so the same times always read as the same profile.
            profile.copy(formula = formula?.takeUnless { it.isOfficial })
        }
        val content = adhkar?.let { (key, value) -> readAdhkar(key, value, currentContent, stored, errors) } ?: currentContent
        val announcements = announcementsEntry?.let { (key, value) -> readAnnouncements(key, value, errors) } ?: currentAnnouncements

        if (errors.isNotEmpty()) return failure(errors)
        return ParseResult.Success(
            schedule, changes(current, schedule), newDates, dateChanges(currentDates, newDates),
            profile, profileChanges(currentProfile, profile, catalog),
            content, contentChanges(currentContent, content) + listOfNotNull(announcementChange(currentAnnouncements, announcements)),
            announcements, formulaChanges(currentProfile.formulaSettings, profile.formulaSettings),
        )
    }

    /** At most [MAX_ERRORS] of [errors], and the count of the rest. */
    private fun failure(errors: List<SettingsError>) =
        ParseResult.Failure(errors.take(MAX_ERRORS), (errors.size - MAX_ERRORS).coerceAtLeast(0))

    /** The "announcements" list, which replaces the TV's; null or [] removes them all. */
    private fun readAnnouncements(sectionKey: String, element: JsonElement, errors: MutableList<SettingsError>): List<TextAnnouncement> {
        if (element is JsonNull) return emptyList()
        val items = element as? JsonArray
        if (items == null || items.size > TextAnnouncement.MAX_COUNT) {
            errors += SettingsError(ErrorCode.INVALID_ANNOUNCEMENT, sectionKey,
                "الإعلانات قائمة مثل ${code("[ { \"text\": \"...\", \"until\": \"2026-10-31\" } ]")}، ${TextAnnouncement.MAX_COUNT} على الأكثر")
            return emptyList()
        }
        return items.mapIndexedNotNull { index, item ->
            val path = "$sectionKey[$index]"
            // Which one, as the admin counts them: a date alone does not say.
            val where = "الإعلان ${index + 1}"
            val fields = item as? JsonObject
            if (fields != null && !itemKeysOk(path, fields, listOf(TEXT_KEYS, FROM_KEYS, UNTIL_KEYS), keyList("text", "from", "until"), errors, where)) {
                return@mapIndexedNotNull null
            }
            fun field(keys: Set<String>) = fields?.entries?.firstOrNull { clean(it.key).trim().lowercase() in keys }?.value
            val text = field(TEXT_KEYS)?.stringOrNull()?.let { cleanText(it).trim() }
            fun date(keys: Set<String>): Pair<Boolean, LocalDate?> {
                val value = field(keys) ?: return true to null
                if (value is JsonNull) return true to null
                val date = parseDate(value)
                return (date != null) to date
            }
            val (fromOk, from) = date(FROM_KEYS)
            val (untilOk, until) = date(UNTIL_KEYS)
            val problem = when {
                fields == null -> "كل إعلان يُكتب مثل ${code("{ \"text\": \"...\", \"from\": \"2026-10-01\", \"until\": \"2026-10-31\" }")}"
                text.isNullOrEmpty() || text.length > TextAnnouncement.MAX_LENGTH -> "نص الإعلان فارغ أو أطول من ${TextAnnouncement.MAX_LENGTH} حرف"
                !fromOk || !untilOk -> "التاريخ غير صالح: اكتب مثل ${code("2026-10-31")}"
                from != null && until != null && until.isBefore(from) -> "تاريخ النهاية قبل تاريخ البداية"
                else -> null
            }
            if (problem != null) {
                errors += SettingsError(ErrorCode.INVALID_ANNOUNCEMENT, path, "$where: $problem")
                null
            } else {
                TextAnnouncement(text!!, from, until)
            }
        }
    }

    /** The "adhkar" section: null lists return to the bundled texts; a bare array of items is appended. */
    private fun readAdhkar(sectionKey: String, element: JsonElement, current: AdhkarContent, stored: Boolean, errors: MutableList<SettingsError>): AdhkarContent {
        if (element is JsonNull) return AdhkarContent()
        if (element !is JsonObject) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, sectionKey,
                "«adhkar» يجب أن يكون مثل ${code("{ \"afterSalah\": { \"mode\": \"append\", \"items\": [...] } }")}")
            return current
        }
        var content = current
        val seenLists = mutableMapOf<ContentList, String>()
        for ((key, value) in element) {
            val path = "$sectionKey.$key"
            val cleanKey = clean(key).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val list = ADHKAR_LISTS[cleanKey]
            if (list == null) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, path, "حقل غير معروف «${short(key)}»: استعمل ${keyList("afterSalah", "ticker", or = true)}")
                continue
            }
            seenLists.put(list, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "القائمة مذكورة مرتين («${short(first)}» و«${short(key)}»)")
                continue
            }
            val saved = if (list == ContentList.TICKER) current.ticker else current.afterSalah
            val before = errors.size
            var parsed = if (value is JsonNull) null else readAdhkarList(path, value, list, stored, errors)
            if (value !is JsonNull && parsed == null) {
                // The TV's own list read back (its template, the dashboard's whole file) stays as it was saved,
                // even with a text an update retired since: that must not refuse the rest of the file.
                if (saved == null || readAdhkarList(path, value, list, stored = true, mutableListOf()) != saved) continue
                errors.subList(before, errors.size).clear()
                parsed = saved
            }
            content = when (list) {
                ContentList.AFTER_SALAH -> content.copy(afterSalah = parsed)
                ContentList.TICKER -> content.copy(ticker = parsed)
                ContentList.ANNOUNCEMENTS -> content // not an adhkar list; ADHKAR_LISTS never maps to it
            }
            // The screen gives the adhkar after the prayer at most FlowTiming.MAX_AFTER_SALAH_MINUTES
            // (a list the TV already holds is not measured again: slower pacing in an update must not refuse it).
            if (list == ContentList.AFTER_SALAH && parsed != null && parsed != saved && !stored) {
                val minutes = (MosqueAdhkar.totalMillis(MosqueAdhkar.afterSalah(content)) + 59_999) / 60_000
                if (minutes > FlowTiming.MAX_AFTER_SALAH_MINUTES) {
                    errors += SettingsError(ErrorCode.INVALID_ADHKAR, path,
                        "أذكار بعد الصلاة تدوم نحو $minutes د، والشاشة تعرضها ${FlowTiming.MAX_AFTER_SALAH_MINUTES} د على الأكثر: احذف بعض النصوص")
                }
            }
        }
        return content
    }

    private fun readAdhkarList(path: String, value: JsonElement, list: ContentList, stored: Boolean, errors: MutableList<SettingsError>): CustomAdhkarList? {
        val (mode, items) = when (value) {
            is JsonArray -> CustomAdhkarList.Mode.APPEND to value
            is JsonObject -> {
                if (!itemKeysOk(path, value, listOf(MODE_KEYS, ITEMS_KEYS), keyList("mode", "items"), errors)) return null
                val modeElement = value.entries.firstOrNull { clean(it.key).trim().lowercase() in MODE_KEYS }?.value
                val modeText = if (modeElement == null || modeElement is JsonNull) null else modeElement.stringOrNull() ?: "?"
                val mode = when (modeText?.let { clean(it).trim().lowercase() }) {
                    null, "append", "add", "إضافة", "اضافة" -> CustomAdhkarList.Mode.APPEND
                    "replace", "استبدال" -> CustomAdhkarList.Mode.REPLACE
                    else -> {
                        errors += SettingsError(ErrorCode.INVALID_ADHKAR, "$path.mode",
                            "«mode» يكون ${code("\"append\"")} (بعد النصوص المضمّنة) أو ${code("\"replace\"")} (بدلها)")
                        return null
                    }
                }
                val items = value.entries.firstOrNull { clean(it.key).trim().lowercase() in ITEMS_KEYS }?.value as? JsonArray
                if (items == null) {
                    errors += SettingsError(ErrorCode.INVALID_ADHKAR, "$path.items",
                        "النصوص تُكتب في «items»: ${code("[ { \"id\": \"ayat_kursi\" }, { \"text\": \"...\", \"reference\": \"...\" } ]")}")
                    return null
                }
                mode to items
            }
            else -> {
                errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "النصوص تُكتب مثل ${code("{ \"mode\": \"append\", \"items\": [...] }")}")
                return null
            }
        }
        if (items.isEmpty() || items.size > MAX_ADHKAR_ITEMS) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "عدد النصوص يجب أن يكون بين 1 و$MAX_ADHKAR_ITEMS")
            return null
        }
        val before = errors.size
        val parsed = items.mapIndexedNotNull { index, item ->
            readDhikr("$path.items[$index]", "${listName(list)}، النص ${index + 1}", item, list, stored, errors)
        }
        return if (errors.size == before) CustomAdhkarList(mode, parsed) else null
    }

    private fun listName(list: ContentList): String = when (list) {
        ContentList.AFTER_SALAH -> "أذكار بعد الصلاة"
        ContentList.TICKER -> "شريط الأذكار"
        ContentList.ANNOUNCEMENTS -> "الإعلانات المكتوبة"
    }

    /**
     * One item of a list: a reviewed text of the app's library by its "id", or the mosque's own "text".
     * [where] names it as the admin counts («أذكار بعد الصلاة، النص 3») at the head of its mistakes.
     */
    private fun readDhikr(path: String, where: String, element: JsonElement, list: ContentList, stored: Boolean, errors: MutableList<SettingsError>): MosqueDhikr? {
        val fields = element as? JsonObject
        val keys = fields?.keys.orEmpty().map { clean(it).trim().lowercase() }
        if (keys.any { it in ID_KEYS }) {
            if (keys.any { it in TEXT_KEYS || it in REFERENCE_KEYS }) {
                errors += SettingsError(ErrorCode.INVALID_ADHKAR, path,
                    "$where: النص إما من مكتبة التطبيق («id») وإما نص المسجد («text» و«reference»)، لا الاثنان معًا")
                return null
            }
            return readReviewed(path, where, fields!!, list, stored, errors)
        }
        if (fields != null && !itemKeysOk(path, fields, listOf(TEXT_KEYS, REFERENCE_KEYS, COUNT_KEYS), keyList("text", "reference", "count"), errors, where)) {
            return null
        }
        fun field(keys: Set<String>) = fields?.entries?.firstOrNull { clean(it.key).trim().lowercase() in keys }?.value
        val text = field(TEXT_KEYS)?.stringOrNull()?.let { cleanText(it).trim() }
        val reference = field(REFERENCE_KEYS)?.stringOrNull()?.let { cleanText(it).trim() }
        val countElement = field(COUNT_KEYS)
        val count = if (countElement == null || countElement is JsonNull) 1 else countElement.intOrNull()
        val problem = when {
            fields == null -> "كل نص يُكتب مثل ${code("{ \"id\": \"ayat_kursi\" }")} أو ${code("{ \"text\": \"...\", \"reference\": \"...\", \"count\": 3 }")}"
            text.isNullOrEmpty() || text.length > MAX_ADHKAR_TEXT -> "النص فارغ أو أطول من $MAX_ADHKAR_TEXT حرف"
            reference.isNullOrEmpty() -> "لكل نص مصدر في «reference» (مثل «صحيح مسلم 591»)"
            reference.length > MAX_ADHKAR_REFERENCE -> "المصدر أطول من $MAX_ADHKAR_REFERENCE حرف"
            count == null || count !in 1..MAX_ADHKAR_COUNT -> "«count» عدد المرات بين 1 و$MAX_ADHKAR_COUNT"
            list == ContentList.TICKER && count != 1 && !stored -> "الشريط يعرض كل نص مرة واحدة: احذف «count»"
            else -> null
        }
        if (problem != null) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "$where: $problem")
            return null
        }
        // The ticker shows every text once (an old saved count is dropped).
        return CustomDhikr(text!!, reference!!, if (list == ContentList.TICKER) 1 else count!!)
    }

    /**
     * A reviewed text by its id. Its text and source stay the catalog's; after the prayer the mosque
     * may change how many times it is said (the ticker reads every text once).
     */
    private fun readReviewed(path: String, where: String, fields: JsonObject, list: ContentList, stored: Boolean, errors: MutableList<SettingsError>): ReviewedDhikr? {
        if (!itemKeysOk(path, fields, listOf(ID_KEYS, COUNT_KEYS), keyList("id", "count"), errors, where)) return null
        fun field(keys: Set<String>) = fields.entries.firstOrNull { clean(it.key).trim().lowercase() in keys }?.value
        val id = field(ID_KEYS)?.stringOrNull()?.let { clean(it).trim() }
        val entry = id?.let(DhikrCatalog::find)
        val countElement = field(COUNT_KEYS)
        val hasCount = countElement != null && countElement !is JsonNull
        val count = if (hasCount) countElement!!.intOrNull() else null
        if (stored && !id.isNullOrEmpty()) {
            // Saved by this TV: an id an update removed shows nothing (the dashboard offers to delete it),
            // and a count that no longer applies is dropped.
            val applies = entry != null && list == ContentList.AFTER_SALAH && entry.steps.isEmpty() && count != null && count in 1..MAX_ADHKAR_COUNT
            return ReviewedDhikr(entry?.id ?: id, count?.takeIf { applies && it != entry!!.countForCollection(DhikrCategory.SALAH) })
        }
        val problem = when {
            id.isNullOrEmpty() -> "«id» اسم نص من مكتبة التطبيق بين علامتي تنصيص، مثل ${code("\"ayat_kursi\"")}"
            entry == null -> "لا يوجد في مكتبة التطبيق نص باسم «${short(id)}»"
            hasCount && list == ContentList.TICKER -> "الشريط يعرض كل نص مرة واحدة: احذف «count»"
            hasCount && entry.steps.isNotEmpty() -> "«${entry.title}» يُقال بالعدد المذكور في خطواته: احذف «count»"
            hasCount && (count == null || count !in 1..MAX_ADHKAR_COUNT) -> "«count» عدد المرات بين 1 و$MAX_ADHKAR_COUNT"
            else -> null
        }
        if (problem != null) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "$where: $problem")
            return null
        }
        // The catalog's own count is kept as "no change", so the same list always reads the same.
        return ReviewedDhikr(entry!!.id, count?.takeIf { it != entry.countForCollection(DhikrCategory.SALAH) })
    }

    private fun contentChanges(before: AdhkarContent, after: AdhkarContent): List<ContentChange> = listOfNotNull(
        listChange(ContentList.AFTER_SALAH, MosqueAdhkar.AFTER_SALAH_IDS, before.afterSalah, after.afterSalah),
        listChange(ContentList.TICKER, MosqueAdhkar.TICKER_IDS, before.ticker, after.ticker),
    )

    private fun listChange(list: ContentList, bundledIds: List<String>, before: CustomAdhkarList?, after: CustomAdhkarList?): ContentChange? {
        if (before == after) return null
        return compare(
            list, describe(list, bundledIds, before), describe(list, bundledIds, after),
            MosqueAdhkar.items(bundledIds, before).map(::shown), MosqueAdhkar.items(bundledIds, after).map(::shown),
        )
    }

    /** The written announcements: how many, and which come, go or change, by their first words. */
    private fun announcementChange(before: List<TextAnnouncement>, after: List<TextAnnouncement>): ContentChange? {
        if (before == after) return null
        return compare(ContentList.ANNOUNCEMENTS, "${before.size}", "${after.size}", before.map(::shown), after.map(::shown))
    }

    private fun compare(list: ContentList, before: String, after: String, old: List<Shown>, new: List<Shown>): ContentChange {
        // Texts matched by identity (a reviewed id, or the wording), each occurrence once; [pairs] holds each new text's old one.
        val unmatchedOld = old.toMutableList()
        val pairs = arrayOfNulls<Shown>(new.size)
        val recounted = mutableListOf<String>()
        val redetailed = mutableListOf<String>()
        new.forEachIndexed { index, text ->
            val same = unmatchedOld.firstOrNull { it.identity == text.identity } ?: return@forEachIndexed
            unmatchedOld.remove(same)
            pairs[index] = same
            if (same.count != text.count) {
                // The wall reads a long text whole each time, at most three times: the preview says so.
                recounted += "${text.name}: العدد ${same.count} ← ${text.count}" + if (text.count > text.shownTimes) " ($LONG_TEXT_REPEATS)" else ""
            }
            if (same.detail != text.detail) redetailed += text.name
        }
        // A text edited past its first words keeps its short name: an edit, not a text added and removed.
        val reworded = mutableListOf<String>()
        new.forEachIndexed { index, text ->
            if (pairs[index] != null) return@forEachIndexed
            val edited = unmatchedOld.firstOrNull { it.name == text.name } ?: return@forEachIndexed
            unmatchedOld.remove(edited)
            pairs[index] = edited
            reworded += text.name
        }
        val added = new.filterIndexed { index, _ -> pairs[index] == null }.map { it.name }
        val removed = unmatchedOld.map { it.name }
        val announcements = list == ContentList.ANNOUNCEMENTS
        return ContentChange(
            list, before, after, added, removed, recounted,
            reordered = added.isEmpty() && removed.isEmpty() && pairs.map { old.indexOf(it) } != old.indices.toList(),
            resourced = if (announcements) emptyList() else redetailed,
            reworded = reworded,
            redated = if (announcements) redetailed else emptyList(),
        )
    }

    /**
     * A text of a list as the admin knows it: a reviewed text by its title, the mosque's text or an
     * announcement by its first words. [detail] is its source, or an announcement's dates;
     * [shownTimes] how many times the wall really shows it.
     */
    private class Shown(val identity: String, val name: String, val count: Int, val detail: String?, val shownTimes: Int = count)

    private fun shown(item: MosqueDhikr): Shown = when (item) {
        is ReviewedDhikr -> DhikrCatalog.find(item.id).let { entry ->
            val count = item.count ?: entry?.countForCollection(DhikrCategory.SALAH) ?: 1
            val times = if (entry == null || entry.steps.isNotEmpty()) count else shownTimes(entry.text, count)
            Shown("id:${item.id}", entry?.title ?: item.id, count, null, times)
        }
        is CustomDhikr -> Shown("own:${item.text}", textName(item.text), item.count, item.reference, shownTimes(item.text, item.count))
    }

    private fun shown(announcement: TextAnnouncement) =
        Shown("text:${announcement.text}", textName(announcement.text), 1, "${announcement.from}/${announcement.until}")

    /** A text longer than a page is read whole for each repetition, at most [MosqueAdhkar.MAX_PAGED_REPETITIONS] times. */
    private fun shownTimes(text: String, count: Int): Int =
        if (AdhkarPacer.pages(text).size > 1) minOf(count, MosqueAdhkar.MAX_PAGED_REPETITIONS) else count

    /** A text without a title (the mosque's own, an announcement) named by its first words: «اللهم اجعل هذا المسجد…». */
    fun textName(text: String): String = "«" + text.take(30).trim() + (if (text.length > 30) "…" else "") + "»"

    private val LONG_TEXT_REPEATS = "النص الطويل يُعرض كاملًا، حتى ${MosqueAdhkar.MAX_PAGED_REPETITIONS} مرات"

    /** "النصوص المضمّنة · 8 نصوص · نحو 4 د": where the list comes from, how many texts, and (after the prayer) how long. */
    private fun describe(list: ContentList, bundledIds: List<String>, custom: CustomAdhkarList?): String {
        val items = MosqueAdhkar.items(bundledIds, custom)
        val source = when (custom?.mode) {
            null -> "النصوص المضمّنة"
            CustomAdhkarList.Mode.APPEND -> "النصوص المضمّنة و${texts(custom.items.size)} بعدها"
            CustomAdhkarList.Mode.REPLACE -> "قائمة المسجد"
        }
        val minutes = if (list == ContentList.AFTER_SALAH) {
            val millis = items.sumOf { item -> MosqueAdhkar.afterSalahSlides(item).sumOf { it.durationMillis } }
            "نحو ${(millis + 59_999) / 60_000} د"
        } else {
            null
        }
        return listOfNotNull(source, texts(items.size), minutes).joinToString(" · ")
    }

    /** "نص واحد", "نصان", "3 نصوص", "11 نصًا", "100 نص", "103 نصوص": the noun agrees with the last two digits. */
    private fun texts(count: Int): String = when {
        count == 1 -> "نص واحد"
        count == 2 -> "نصان"
        count % 100 in 3..10 -> "$count نصوص"
        count % 100 in 11..99 -> "$count نصًا"
        else -> "$count نص"
    }

    /** The "mosque" (name, delegation) and "display" (theme and [DisplayOptions]) sections over [current]. */
    private fun readProfile(
        mosque: Pair<String, JsonObject>?,
        display: Pair<String, JsonObject>?,
        current: MosqueProfile,
        catalog: ProfileCatalog,
        errors: MutableList<SettingsError>,
    ): MosqueProfile {
        var profile = current
        fun fields(section: Pair<String, JsonObject>?, known: Map<String, ProfileField>, hint: String, read: (ProfileField, JsonElement, String) -> Unit) {
            val (sectionKey, values) = section ?: return
            val seen = mutableMapOf<ProfileField, String>()
            for ((key, element) in values) {
                val path = "$sectionKey.$key"
                val cleanKey = clean(key).trim().lowercase()
                if (cleanKey in IGNORED_FIELDS || cleanKey in INFORMATION_FIELDS) continue
                val field = known[cleanKey]
                if (field == null) {
                    errors += SettingsError(ErrorCode.UNKNOWN_FIELD, path, "حقل غير معروف «${short(key)}»: استعمل $hint")
                    continue
                }
                seen[field]?.let { first ->
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "الحقل مذكور مرتين («${short(first)}» و«${short(key)}»)")
                    continue
                }
                seen[field] = key
                read(field, element, path)
            }
        }
        fields(mosque, MOSQUE_FIELDS, keyList("name", "delegation", or = true)) { field, element, path ->
            when (field) {
                ProfileField.NAME -> {
                    val name = element.stringOrNull()?.let { clean(it).trim().replace(Regex("""\s+"""), " ") }
                    if (name == null || name.length > MosqueProfile.MAX_NAME_LENGTH) {
                        errors += SettingsError(ErrorCode.INVALID_NAME, path,
                            "اسم المسجد يجب أن يكون نصًا بين علامتي تنصيص، ${MosqueProfile.MAX_NAME_LENGTH} حرفًا على الأكثر")
                    } else {
                        profile = profile.copy(name = name)
                    }
                }
                else -> {
                    val id = (element as? JsonPrimitive)?.content?.let { clean(normalizeDigits(it)).trim() }
                        ?.takeIf { Regex("""\d{1,7}""").matches(it) }?.toInt()
                    if (id == null || catalog.delegationName(id) == null) {
                        errors += SettingsError(ErrorCode.UNKNOWN_DELEGATION, path,
                            "المعتمدية «${element.quoted()}» غير معروفة: انسخ رقمها من ملف كتبته شاشة أخرى")
                    } else {
                        profile = profile.copy(delegationId = id)
                    }
                }
            }
        }
        mosque?.let { (sectionKey, values) -> profile = readPlaceName(sectionKey, values, profile, current, catalog, errors) }
        fields(display, DISPLAY_FIELDS,
            keyList("theme", "weather", "backgrounds", "announcements", "slideSeconds", "announcementsEveryMinutes", "nightScreen", "adhanScreenMinutes", or = true),
        ) { field, element, path ->
            fun flag(set: (Boolean) -> DisplayOptions) {
                val value = element.booleanOrNull()
                if (value == null) {
                    errors += SettingsError(ErrorCode.INVALID_OPTION, path, "«${short(path.substringAfterLast('.'))}» يكون true (تشغيل) أو false (إيقاف)")
                } else {
                    profile = profile.copy(display = set(value))
                }
            }
            fun number(range: IntRange, set: (Int) -> DisplayOptions) {
                val value = element.intOrNull()
                if (value == null || value !in range) {
                    errors += SettingsError(ErrorCode.INVALID_OPTION, path,
                        "«${short(path.substringAfterLast('.'))}» عدد بين ${range.first} و${range.last} (في الملف: ${element.quoted()})")
                } else {
                    profile = profile.copy(display = set(value))
                }
            }
            val options = profile.display
            when (field) {
                ProfileField.WEATHER -> flag { options.copy(weather = it) }
                ProfileField.BACKGROUNDS -> flag { options.copy(backgrounds = it) }
                ProfileField.ANNOUNCEMENTS -> flag { options.copy(announcements = it) }
                ProfileField.SLIDE_SECONDS -> number(DisplayOptions.SLIDE_SECONDS) { options.copy(slideSeconds = it) }
                ProfileField.ANNOUNCEMENTS_EVERY -> number(DisplayOptions.EVERY_MINUTES) { options.copy(announcementsEveryMinutes = it) }
                ProfileField.NIGHT_SCREEN -> flag { options.copy(nightScreen = it) }
                ProfileField.ADHAN_SCREEN -> number(FlowTiming.ADHAN_SCREEN_MINUTES) { options.copy(adhanScreenMinutes = it) }
                else -> {
                    val wanted = element.stringOrNull()?.let { clean(it).trim() }
                    val theme = catalog.themes.entries.firstOrNull { (id, name) -> wanted != null && (id.equals(wanted, ignoreCase = true) || name == wanted) }
                    if (theme == null) {
                        errors += SettingsError(ErrorCode.UNKNOWN_THEME, path,
                            "المظهر «${element.quoted()}» غير معروف: استعمل ${catalog.themes.keys.joinToString(" أو ")}")
                    } else {
                        profile = profile.copy(themeId = theme.key)
                    }
                }
            }
        }
        return profile
    }

    /**
     * The place's name the TV writes beside its number ("delegationName"), for the admin to read. An
     * admin who knows only names edits the name: when it no longer names the number's place, the place
     * is found by that name, unless the number was changed and the name left as it was.
     */
    private fun readPlaceName(
        sectionKey: String,
        values: JsonObject,
        profile: MosqueProfile,
        current: MosqueProfile,
        catalog: ProfileCatalog,
        errors: MutableList<SettingsError>,
    ): MosqueProfile {
        val (key, element) = values.entries.firstOrNull { clean(it.key).trim().lowercase() in INFORMATION_FIELDS } ?: return profile
        val name = element.stringOrNull()?.let(::placeName)?.takeIf { it.isNotEmpty() } ?: return profile
        fun nameOf(id: Int?) = id?.let(catalog.delegationName)?.let(::placeName)
        val byNumber = profile.delegationId
        if (name == nameOf(byNumber)) return profile
        val numberChanged = byNumber != current.delegationId
        if (numberChanged && name == nameOf(current.delegationId)) return profile
        val named = catalog.delegationIds().filter { nameOf(it) == name }.distinct()
        val problem = when {
            named.size != 1 -> "المعتمدية «${short(name)}» غير معروفة بهذا الاسم: اكتب رقمها في ${code("\"delegation\"")}"
            numberChanged -> "رقم المعتمدية واسمها لا يدلّان على المكان نفسه: احذف ${code("\"delegationName\"")} أو صحّحه"
            else -> return profile.copy(delegationId = named.single())
        }
        errors += SettingsError(ErrorCode.UNKNOWN_DELEGATION, "$sectionKey.$key", problem)
        return profile
    }

    /** A place's name as compared: the catalog's own names carry stray spaces too («بني خيار »). */
    private fun placeName(text: String): String = clean(text).trim().replace(Regex("""\s+"""), " ")

    /** true or false, also written "true"/"false" or نعم/لا by hand. */
    private fun JsonElement.booleanOrNull(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return when (clean(primitive.content).trim().lowercase()) {
            "true", "نعم", "yes", "on" -> true
            "false", "لا", "no", "off" -> false
            else -> null
        }
    }

    private fun profileChanges(before: MosqueProfile, after: MosqueProfile, catalog: ProfileCatalog): List<ProfileChange> {
        fun place(id: Int?) = id?.let { catalog.delegationName(it) ?: it.toString() } ?: "—"
        fun theme(id: String?) = id?.let(catalog::themeName) ?: "—"
        return listOfNotNull(
            ProfileChange(ProfileField.NAME, before.name?.ifEmpty { null } ?: "—", after.name?.ifEmpty { null } ?: "—")
                .takeIf { before.name.orEmpty() != after.name.orEmpty() },
            ProfileChange(ProfileField.DELEGATION, place(before.delegationId), place(after.delegationId))
                .takeIf { before.delegationId != after.delegationId },
            ProfileChange(ProfileField.THEME, theme(before.themeId), theme(after.themeId)).takeIf { before.themeId != after.themeId },
        ) + displayChanges(before.display, after.display)
    }

    /** The options as in the file, "true" or "15" ("—" when unset): the screen says them in its own words. */
    private fun displayChanges(before: DisplayOptions, after: DisplayOptions): List<ProfileChange> {
        fun flag(value: Boolean?) = value?.toString() ?: "—"
        fun number(value: Int?) = value?.toString() ?: "—"
        return listOfNotNull(
            ProfileChange(ProfileField.WEATHER, flag(before.weather), flag(after.weather)).takeIf { before.weather != after.weather },
            ProfileChange(ProfileField.BACKGROUNDS, flag(before.backgrounds), flag(after.backgrounds)).takeIf { before.backgrounds != after.backgrounds },
            ProfileChange(ProfileField.ANNOUNCEMENTS, flag(before.announcements), flag(after.announcements)).takeIf { before.announcements != after.announcements },
            ProfileChange(ProfileField.SLIDE_SECONDS, number(before.slideSeconds), number(after.slideSeconds)).takeIf { before.slideSeconds != after.slideSeconds },
            ProfileChange(ProfileField.ANNOUNCEMENTS_EVERY, number(before.announcementsEveryMinutes), number(after.announcementsEveryMinutes))
                .takeIf { before.announcementsEveryMinutes != after.announcementsEveryMinutes },
            ProfileChange(ProfileField.NIGHT_SCREEN, flag(before.nightScreen), flag(after.nightScreen)).takeIf { before.nightScreen != after.nightScreen },
            ProfileChange(ProfileField.ADHAN_SCREEN, number(before.adhanScreenMinutes), number(after.adhanScreenMinutes))
                .takeIf { before.adhanScreenMinutes != after.adhanScreenMinutes },
        )
    }

    /**
     * The "prayerTimes" section over the TV's [current] values: a field left out stays, a field set to
     * null returns to INM's value, and the section set to null returns them all ("adjust": null clears
     * every adjustment, a prayer's null its own).
     */
    private fun readFormula(sectionKey: String, element: JsonElement, current: PrayerFormulaSettings, errors: MutableList<SettingsError>): PrayerFormulaSettings {
        val official = PrayerFormulaSettings.OFFICIAL
        if (element is JsonNull) return official
        val values = element as? JsonObject ?: return current // refused by rootErrors
        var settings = current
        val seen = mutableMapOf<FormulaField, String>()
        for ((key, value) in values) {
            val path = "$sectionKey.$key"
            val cleanKey = clean(key).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val field = FORMULA_FIELDS[cleanKey]
            if (field == null) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, path, "حقل غير معروف «${short(key)}»: استعمل " +
                    keyList("fajrAngle", "ishaAngle", "asrShadow", "dhuhrMinutes", "maghribMinutes", "elevation", "adjust", or = true))
                continue
            }
            seen.put(field, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "الحقل مذكور مرتين («${short(first)}» و«${short(key)}»)")
                continue
            }
            fun invalid(accepted: String) {
                errors += SettingsError(ErrorCode.INVALID_FORMULA, path, "«${short(key)}» $accepted (في الملف: ${value.quoted()})")
            }
            val isNull = value is JsonNull
            when (field) {
                FormulaField.FAJR_ANGLE, FormulaField.ISHA_ANGLE -> {
                    val fajr = field == FormulaField.FAJR_ANGLE
                    val angle = when {
                        isNull -> if (fajr) official.fajrAngle else official.ishaAngle
                        else -> value.angleOrNull()?.takeIf(PrayerFormulaSettings::angleAccepted)
                    }
                    when {
                        angle == null -> invalid(angleRange(if (fajr) official.fajrAngle else official.ishaAngle))
                        fajr -> settings = settings.copy(fajrAngle = angle)
                        else -> settings = settings.copy(ishaAngle = angle)
                    }
                }
                FormulaField.ASR_SHADOW -> when (val shadow = if (isNull) official.asrShadow else value.intOrNull()) {
                    in PrayerFormulaSettings.ASR_SHADOWS -> settings = settings.copy(asrShadow = shadow!!)
                    else -> invalid("يكون 1 (ظلّ الشيء مثله، الرسمي) أو 2 (مثلاه) على المذهب الحنفي")
                }
                FormulaField.DHUHR_MINUTES -> when (val minutes = if (isNull) official.dhuhrMinutes else value.intOrNull()) {
                    in PrayerFormulaSettings.DHUHR_MINUTES -> settings = settings.copy(dhuhrMinutes = minutes!!)
                    else -> invalid(minutesRange("بعد الزوال", PrayerFormulaSettings.DHUHR_MINUTES, official.dhuhrMinutes))
                }
                FormulaField.MAGHRIB_MINUTES -> when (val minutes = if (isNull) official.maghribMinutes else value.intOrNull()) {
                    in PrayerFormulaSettings.MAGHRIB_MINUTES -> settings = settings.copy(maghribMinutes = minutes!!)
                    else -> invalid(minutesRange("بعد الغروب", PrayerFormulaSettings.MAGHRIB_MINUTES, official.maghribMinutes))
                }
                FormulaField.ELEVATION -> when (val counted = if (isNull) official.elevation else value.booleanOrNull()) {
                    null -> invalid("يكون true (يُحسب ارتفاع المسجد، الرسمي) أو false فلا يُحسب")
                    else -> settings = settings.copy(elevation = counted)
                }
                FormulaField.ADJUSTMENT -> settings = readAdjustments(path, value, settings, errors)
            }
        }
        return settings
    }

    // The official value inside the sentence, so the only parenthesis left is invalid()'s «(في الملف: …)».
    private fun minutesRange(after: String, range: IntRange, official: Int) =
        "عدد الدقائق $after بين ${range.first} و${range.last}، والرسمي $official"

    private fun angleRange(official: Double): String {
        val angles = PrayerFormulaSettings.ANGLES
        return "زاوية بين ${angleText(angles.start)} و${angleText(angles.endInclusive)} درجة بخطوة " +
            "${angleText(PrayerFormulaSettings.ANGLE_STEP)}، مثل 17.5، والرسمية ${angleText(official)}"
    }

    /** "adjust": { "isha": "+2", "fajr": -1 }: whole minutes added to the five prayers' times, the others left as they are. */
    private fun readAdjustments(path: String, element: JsonElement, current: PrayerFormulaSettings, errors: MutableList<SettingsError>): PrayerFormulaSettings {
        if (element is JsonNull) return current.copy(adjustments = emptyMap())
        val values = element as? JsonObject
        if (values == null) {
            errors += SettingsError(ErrorCode.INVALID_FORMULA, path, "التعديل يُكتب مثل ${code("{ \"isha\": \"+2\", \"fajr\": \"-1\" }")}")
            return current
        }
        var settings = current
        val seen = mutableMapOf<Prayer, String>()
        val range = PrayerFormulaSettings.ADJUSTMENT_MINUTES
        for ((key, value) in values) {
            val itemPath = "$path.$key"
            val cleanKey = clean(key).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val targets = prayersFor(key)
            if (targets == null && cleanKey !in SHURUK_NAMES) {
                errors += SettingsError(ErrorCode.UNKNOWN_PRAYER, itemPath, "صلاة غير معروفة «${short(key)}»: استعمل fajr أو dhuhr أو asr أو maghrib أو isha")
                continue
            }
            val prayer = targets?.singleOrNull()?.takeIf { it in PrayerFormulaSettings.ADJUSTABLE }
            if (prayer == null) {
                errors += SettingsError(ErrorCode.INVALID_FORMULA, itemPath,
                    "لا يُعدَّل وقت «${short(key)}»: التعديل للفجر والظهر والعصر والمغرب والعشاء؛ الجمعة تتبع الظهر، والعيدان يتبعان الشروق")
                continue
            }
            seen.put(prayer, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_PRAYER, itemPath, "صلاة ${arabicName(prayer)} مذكورة مرتين («${short(first)}» و«${short(key)}»)")
                continue
            }
            val minutes = if (value is JsonNull) 0 else value.signedIntOrNull()
            if (minutes == null || minutes !in range) {
                errors += SettingsError(ErrorCode.INVALID_FORMULA, itemPath,
                    "تعديل ${arabicName(prayer)} عدد دقائق بين ${code("${range.first}")} و${code("+${range.last}")}، مثل ${code("\"+2\"")} أو ${code("\"-1\"")} (في الملف: ${value.quoted()})")
                continue
            }
            settings = settings.withAdjustment(prayer, minutes)
        }
        return settings
    }

    /**
     * What the file changes in the computation, in the order write() emits them (the values, then each
     * prayer's adjustment from Fajr to Isha), whatever the order of the keys in the file.
     */
    private fun formulaChanges(before: PrayerFormulaSettings, after: PrayerFormulaSettings): List<FormulaChange> {
        fun change(field: FormulaField, old: Any, new: Any) = FormulaChange(field, null, "$old", "$new").takeIf { old != new }
        return listOfNotNull(
            change(FormulaField.FAJR_ANGLE, angleText(before.fajrAngle), angleText(after.fajrAngle)),
            change(FormulaField.ISHA_ANGLE, angleText(before.ishaAngle), angleText(after.ishaAngle)),
            change(FormulaField.ASR_SHADOW, before.asrShadow, after.asrShadow),
            change(FormulaField.DHUHR_MINUTES, before.dhuhrMinutes, after.dhuhrMinutes),
            change(FormulaField.MAGHRIB_MINUTES, before.maghribMinutes, after.maghribMinutes),
            change(FormulaField.ELEVATION, before.elevation, after.elevation),
        ) + PrayerFormulaSettings.ADJUSTABLE.mapNotNull { prayer ->
            val old = before.adjustment(prayer)
            val new = after.adjustment(prayer)
            FormulaChange(FormulaField.ADJUSTMENT, prayer, signedText(old), signedText(new)).takeIf { old != new }
        }
    }

    /** An angle as in the file: "18", "17.5". */
    fun angleText(angle: Double): String = if (angle == kotlin.math.floor(angle)) angle.toInt().toString() else angle.toString()

    /** An adjustment as in the file: "+2", "-1", "0". */
    fun signedText(minutes: Int): String = if (minutes > 0) "+$minutes" else "$minutes"

    /** Calls [onPrayer] for each valid prayer object of a section, reporting unknown, duplicate or malformed entries. */
    private fun forEachPrayer(
        section: JsonObject,
        sectionKey: String,
        errors: MutableList<SettingsError>,
        allowEid: Boolean,
        onPrayer: (Prayer, JsonObject, String) -> Unit,
    ) {
        val seen = mutableMapOf<Prayer, String>()
        for ((key, value) in section) {
            val path = "$sectionKey.$key"
            val targets = prayersFor(key)?.takeIf { allowEid || it.none(MosqueSchedule.EID::contains) }
            if (targets == null) {
                val names = if (allowEid) "fajr أو dhuhr أو asr أو maghrib أو isha أو jumua أو eid" else "fajr أو dhuhr أو asr أو maghrib أو isha أو jumua"
                errors += SettingsError(ErrorCode.UNKNOWN_PRAYER, path, "صلاة غير معروفة «${short(key)}»: استعمل $names")
                continue
            }
            val duplicate = targets.firstOrNull { it in seen }
            if (duplicate != null) {
                errors += SettingsError(ErrorCode.DUPLICATE_PRAYER, path,
                    "صلاة ${arabicName(duplicate)} مذكورة مرتين («${short(seen.getValue(duplicate))}» و«${short(key)}»)")
                continue
            }
            targets.forEach { seen[it] = key }
            if (value !is JsonObject) {
                errors += SettingsError(ErrorCode.NOT_A_PRAYER_OBJECT, path,
                    "إعدادات صلاة ${arabicName(targets.first())} يجب أن تكون مثل ${code("{ \"iqamah\": \"+10\", \"duration\": 10 }")}")
                continue
            }
            targets.forEach { onPrayer(it, value, path) }
        }
    }

    /**
     * Applies the "iqamah" and "duration" fields of one prayer object to [start], and "held" for
     * Jumu'a and the Eids and "khutba" and "dua" for Jumu'a when [allowHeld] (the usual settings). A null value
     * is ignored, or clears the field when [clearOnNull] (Ramadan changes returning to the usual setting).
     */
    private fun <T> readFields(
        fields: JsonObject,
        prayer: Prayer,
        path: String,
        errors: MutableList<SettingsError>,
        start: T,
        clearOnNull: Boolean = false,
        allowHeld: Boolean = false,
        set: (T, Field, Any?) -> T,
    ): T {
        var result = start
        val seen = mutableMapOf<Field, String>()
        val holdable = allowHeld && prayer in MosqueSchedule.HOLDABLE
        val khutba = allowHeld && prayer == Prayer.JOMOAA
        for ((fieldKey, element) in fields) {
            val fieldPath = "$path.$fieldKey"
            val cleanKey = clean(fieldKey).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val field = FIELD_ALIASES[cleanKey]
            if (field == null) {
                // Ignoring it would apply the file without the setting the admin meant to change.
                val names = when {
                    khutba -> keyList("iqamah", "duration", "held", "khutba", "dua", or = true)
                    holdable -> keyList("iqamah", "duration", "held", or = true)
                    else -> keyList("iqamah", "duration", or = true)
                }
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                    "حقل غير معروف «${short(fieldKey)}» في صلاة ${arabicName(prayer)}: استعمل $names")
                continue
            }
            if (field == Field.HELD && !holdable) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                    "«${short(fieldKey)}» يُكتب لصلاة الجمعة وصلاتي العيد في قسم «prayers» فقط، لا لصلاة ${arabicName(prayer)}" +
                        if (allowHeld) "" else " في رمضان")
                continue
            }
            if ((field == Field.KHUTBA || field == Field.DUA) && !khutba) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                    "«${short(fieldKey)}» يُكتب لصلاة الجمعة في قسم «prayers» فقط، لا لصلاة ${arabicName(prayer)}" +
                        if (allowHeld) "" else " في رمضان")
                continue
            }
            seen[field]?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, fieldPath,
                    "الحقل مذكور مرتين في صلاة ${arabicName(prayer)} («${short(first)}» و«${short(fieldKey)}»)")
                continue
            }
            seen[field] = fieldKey
            if (element is JsonNull) {
                if (clearOnNull) result = set(result, field, null)
                continue
            }
            when (field) {
                Field.IQAMAH -> when (val rule = iqamahRule(element, prayer, fieldPath)) {
                    is IqamahParse.Ok -> result = set(result, field, rule.rule)
                    is IqamahParse.Error -> errors += rule.error
                }
                Field.DURATION -> {
                    val minutes = element.intOrNull()
                    val range = MosqueSchedule.SALAH_MINUTES
                    when {
                        minutes == null -> errors += SettingsError(ErrorCode.INVALID_DURATION, fieldPath,
                            "مدة صلاة ${arabicName(prayer)} غير صالحة «${element.quoted()}»: اكتب عدد الدقائق، مثلًا 10")
                        minutes !in range -> errors += SettingsError(ErrorCode.DURATION_OUT_OF_RANGE, fieldPath,
                            "مدة صلاة ${arabicName(prayer)} يجب أن تكون بين ${range.first} و${range.last} دقيقة (في الملف: $minutes)")
                        else -> result = set(result, field, minutes)
                    }
                }
                Field.HELD -> when (val held = element.booleanOrNull()) {
                    null -> errors += SettingsError(ErrorCode.INVALID_OPTION, fieldPath,
                        "«$fieldKey» في صلاة ${arabicName(prayer)} يكون true (تقام في المسجد) أو false (لا تقام فيه)")
                    else -> result = set(result, field, held)
                }
                Field.KHUTBA -> {
                    val minutes = element.intOrNull()
                    val range = MosqueSchedule.KHUTBA_MINUTES
                    when {
                        minutes == null -> errors += SettingsError(ErrorCode.INVALID_DURATION, fieldPath,
                            "مدة خطبة الجمعة غير صالحة «${element.quoted()}»: اكتب عدد الدقائق، مثلًا 30، أو 0 من الأذان إلى الإقامة")
                        minutes !in range -> errors += SettingsError(ErrorCode.DURATION_OUT_OF_RANGE, fieldPath,
                            "مدة خطبة الجمعة يجب أن تكون بين ${range.first} و${range.last} دقيقة (في الملف: $minutes)")
                        else -> result = set(result, field, minutes)
                    }
                }
                Field.DUA -> when (val dua = element.booleanOrNull()) {
                    null -> errors += SettingsError(ErrorCode.INVALID_OPTION, fieldPath,
                        "«$fieldKey» في صلاة الجمعة يكون true (الدعاء بعد الأذان دقيقة قبل الخطبة) أو false (شاشة الخطبة بعد الأذان مباشرة)")
                    else -> result = set(result, field, dua)
                }
            }
        }
        return result
    }

    /**
     * The admin's Ramadan and Eid dates per Hijri year, checked against the calendar estimate, and
     * together against the TV's [yearDates] ([datesConflict]); null for the TV's own former state,
     * whose dates were accepted then.
     */
    private fun readDates(
        section: JsonObject,
        sectionKey: String,
        currentDates: Map<Int, ManualIslamicDates>,
        yearDates: ((Int) -> YearDates?)?,
        errors: MutableList<SettingsError>,
    ): Map<Int, ManualIslamicDates> {
        val estimate = TunisianHijriCalendar()
        val result = mutableMapOf<Int, ManualIslamicDates>()
        val seenYears = mutableMapOf<Int, String>()
        for ((yearKey, value) in section) {
            val path = "$sectionKey.$yearKey"
            val year = clean(normalizeDigits(yearKey)).trim().toIntOrNull()
            if (year != null) {
                val first = seenYears.put(year, yearKey)
                if (first != null) {
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "السنة ${code("$year")} مذكورة مرتين («${keyQuoted(first)}» و«${keyQuoted(yearKey)}»)")
                    continue
                }
            }
            val supported = year != null && runCatching { estimate.month(year, 12) }.isSuccess
            if (!supported || value !is JsonObject) {
                errors += SettingsError(ErrorCode.INVALID_YEAR, path,
                    "السنة الهجرية «${keyQuoted(yearKey)}» غير صالحة: اكتب مثل ${code("\"1448\": { \"ramadanStart\": \"2027-02-08\" }")}")
                continue
            }
            val before = currentDates[year] ?: ManualIslamicDates()
            var dates = before
            val errorsBefore = errors.size
            val seen = mutableMapOf<DateEvent, String>()
            for ((fieldKey, element) in value) {
                val fieldPath = "$path.$fieldKey"
                val cleanKey = clean(fieldKey).trim().lowercase()
                if (cleanKey in IGNORED_FIELDS) continue
                val event = DATE_ALIASES[cleanKey]
                if (event == null) {
                    errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                        "حقل غير معروف «${short(fieldKey)}»: استعمل ${keyList("ramadanStart", "eidFitr", "eidAdha", or = true)}")
                    continue
                }
                seen[event]?.let { first ->
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, fieldPath, "التاريخ مذكور مرتين («${short(first)}» و«${short(fieldKey)}»)")
                    continue
                }
                seen[event] = fieldKey
                val date = if (element is JsonNull) null else parseDate(element)
                if (element !is JsonNull && date == null) {
                    errors += SettingsError(ErrorCode.INVALID_DATE, fieldPath,
                        "التاريخ «${element.quoted()}» غير صالح: اكتب مثل ${code("2027-02-08")}")
                    continue
                }
                if (date != null) {
                    val expected = estimatedDate(estimate, year, event)
                    if (kotlin.math.abs(ChronoUnit.DAYS.between(expected, date)) > MAX_DAYS_FROM_ESTIMATE) {
                        errors += SettingsError(ErrorCode.DATE_OUT_OF_RANGE, fieldPath,
                            "التاريخ ${code("$date")} بعيد عن ${dateEventName(event)} المتوقَّع لسنة $year (${code("$expected")} تقريبًا)")
                        continue
                    }
                }
                dates = when (event) {
                    DateEvent.RAMADAN_START -> dates.copy(ramadanStart = date)
                    DateEvent.EID_FITR -> dates.copy(eidFitr = date)
                    DateEvent.EID_ADHA -> dates.copy(eidAdha = date)
                }
            }
            val changed = DateEvent.entries.filter { dateOf(before, it) != dateOf(dates, it) }.toSet()
            if (yearDates != null && errors.size == errorsBefore && changed.isNotEmpty()) {
                val known = yearDates(year) ?: IslamicDays.yearDates(year, estimate, estimate, ManualIslamicDates())
                datesConflict(known, dates, changed)?.let { errors += SettingsError(ErrorCode.DATES_CONFLICT, path, it) }
            }
            result[year] = dates
        }
        return result
    }

    /**
     * The canonical file for [schedule], the admin's [dates] and the [profile], for exporting the TV's
     * settings to a USB key. The delegation's name is written next to its number for the admin to read.
     * The prayer-time values ("prayerTimes") are written whole, every field and the five prayers'
     * adjustments (0 included), when the profile's [MosqueProfile.formula] is not INM's, and not at all
     * when it is. A [complete] file also writes what is unset as null (every Ramadan field, every year
     * of [dates], "prayerTimes" when official), that Jumu'a and the Eid prayers are held ("held" is
     * otherwise written only when false), a khutba of 0 ("khutba" is otherwise written only when set) and
     * Jumu'a's dua after the adhan shown ("dua" is otherwise written only when false),
     * so reading it back returns to exactly this state: the snapshot behind "undo the last import".
     */
    fun write(
        schedule: MosqueSchedule,
        dates: Map<Int, ManualIslamicDates> = emptyMap(),
        profile: MosqueProfile = MosqueProfile(),
        catalog: ProfileCatalog = ProfileCatalog.NONE,
        complete: Boolean = false,
        content: AdhkarContent = AdhkarContent(),
        announcements: List<TextAnnouncement> = emptyList(),
    ): String = buildString {
        append("{\n")
        append("  \"format\": \"").append(FORMAT).append("\",\n")
        append("  \"version\": ").append(VERSION).append(",\n")
        val mosque = listOfNotNull(
            profile.name?.let { "\"name\": ${JsonPrimitive(it)}" },
            profile.delegationId?.let { "\"delegation\": $it" },
            profile.delegationId?.let(catalog.delegationName)?.let { "\"delegationName\": ${JsonPrimitive(it)}" },
        )
        if (mosque.isNotEmpty()) append("  \"mosque\": { ").append(mosque.joinToString(", ")).append(" },\n")
        val display = listOfNotNull(
            profile.themeId?.let { "\"theme\": ${JsonPrimitive(it)}" },
            profile.display.weather?.let { "\"weather\": $it" },
            profile.display.backgrounds?.let { "\"backgrounds\": $it" },
            profile.display.announcements?.let { "\"announcements\": $it" },
            profile.display.slideSeconds?.let { "\"slideSeconds\": $it" },
            profile.display.announcementsEveryMinutes?.let { "\"announcementsEveryMinutes\": $it" },
            profile.display.nightScreen?.let { "\"nightScreen\": $it" },
            profile.display.adhanScreenMinutes?.let { "\"adhanScreenMinutes\": $it" },
        )
        if (display.isNotEmpty()) append("  \"display\": { ").append(display.joinToString(", ")).append(" },\n")
        val formula = profile.formula?.takeUnless { it.isOfficial }
        if (formula != null) {
            val adjust = PrayerFormulaSettings.ADJUSTABLE.joinToString(", ") { "\"${KEYS.getValue(it)}\": ${formula.adjustment(it)}" }
            append("  \"prayerTimes\": { \"fajrAngle\": ").append(angleText(formula.fajrAngle))
                .append(", \"ishaAngle\": ").append(angleText(formula.ishaAngle))
                .append(", \"asrShadow\": ").append(formula.asrShadow)
                .append(", \"dhuhrMinutes\": ").append(formula.dhuhrMinutes)
                .append(", \"maghribMinutes\": ").append(formula.maghribMinutes)
                .append(", \"elevation\": ").append(formula.elevation)
                .append(", \"adjust\": { ").append(adjust).append(" } },\n")
        } else if (complete) {
            append("  \"prayerTimes\": null,\n")
        }
        val daily = MosqueSchedule.CONFIGURABLE + MosqueSchedule.EID
        append("  \"prayers\": {\n")
        daily.forEachIndexed { index, prayer ->
            val settings = schedule.settings(prayer)
            append("    \"").append(KEYS.getValue(prayer)).append("\": { \"iqamah\": \"")
                .append(iqamahText(settings.iqamah)).append("\", \"duration\": ").append(settings.salahMinutes)
            if (prayer in MosqueSchedule.HOLDABLE && (!settings.held || complete)) append(", \"held\": ").append(settings.held)
            if (prayer == Prayer.JOMOAA && (settings.khutbaMinutes != 0 || complete)) append(", \"khutba\": ").append(settings.khutbaMinutes)
            if (prayer == Prayer.JOMOAA && (!settings.adhanDua || complete)) append(", \"dua\": ").append(settings.adhanDua)
            append(" }")
            append(if (index < daily.lastIndex) ",\n" else "\n")
        }
        append("  }")
        val ramadan = MosqueSchedule.CONFIGURABLE.mapNotNull { prayer ->
            (schedule.ramadan[prayer] ?: PrayerOverride().takeIf { complete })?.let { prayer to it }
        }
        if (ramadan.isNotEmpty()) {
            append(",\n  \"ramadan\": {\n")
            ramadan.forEachIndexed { index, (prayer, override) ->
                val fields = listOfNotNull(
                    override.iqamah?.let { "\"iqamah\": \"${iqamahText(it)}\"" } ?: "\"iqamah\": null".takeIf { complete },
                    override.salahMinutes?.let { "\"duration\": $it" } ?: "\"duration\": null".takeIf { complete },
                )
                append("    \"").append(KEYS.getValue(prayer)).append("\": { ").append(fields.joinToString(", ")).append(" }")
                append(if (index < ramadan.lastIndex) ",\n" else "\n")
            }
            append("  }")
        }
        val years = dates.filterValues { complete || !it.isEmpty }.toSortedMap()
        if (years.isNotEmpty()) {
            append(",\n  \"islamicDates\": {\n")
            years.entries.forEachIndexed { index, (year, value) ->
                fun field(name: String, date: LocalDate?) = date?.let { "\"$name\": \"$it\"" } ?: "\"$name\": null".takeIf { complete }
                val fields = listOfNotNull(
                    field("ramadanStart", value.ramadanStart),
                    field("eidFitr", value.eidFitr),
                    field("eidAdha", value.eidAdha),
                )
                append("    \"").append(year).append("\": { ").append(fields.joinToString(", ")).append(" }")
                append(if (index < years.size - 1) ",\n" else "\n")
            }
            append("  }")
        }
        if (!content.isBundled || complete) {
            fun list(key: String, list: CustomAdhkarList?): String? {
                if (list == null) return "    \"$key\": null".takeIf { complete }
                val mode = if (list.mode == CustomAdhkarList.Mode.REPLACE) "replace" else "append"
                val items = list.items.joinToString(",\n") { item ->
                    when (item) {
                        is ReviewedDhikr -> "        { \"id\": ${JsonPrimitive(item.id)}" + (item.count?.let { ", \"count\": $it" } ?: "") + " }"
                        is CustomDhikr ->
                            "        { \"text\": ${JsonPrimitive(item.text)}, \"reference\": ${JsonPrimitive(item.reference)}, \"count\": ${item.count} }"
                    }
                }
                return "    \"$key\": { \"mode\": \"$mode\", \"items\": [\n$items\n      ] }"
            }
            val lists = listOfNotNull(list("afterSalah", content.afterSalah), list("ticker", content.ticker))
            append(",\n  \"adhkar\": {\n").append(lists.joinToString(",\n")).append("\n  }")
        }
        if (announcements.isNotEmpty() || complete) {
            val items = announcements.joinToString(",\n") { item ->
                listOfNotNull(
                    "\"text\": ${JsonPrimitive(item.text)}",
                    item.from?.let { "\"from\": \"$it\"" },
                    item.until?.let { "\"until\": \"$it\"" },
                ).joinToString(", ", prefix = "    { ", postfix = " }")
            }
            append(",\n  \"announcements\": [").append(if (items.isEmpty()) "" else "\n$items\n  ").append("]")
        }
        append("\n}\n")
    }

    /** "+15" or "20:00", as written in the file. */
    fun iqamahText(rule: IqamahRule): String = when (rule) {
        is IqamahRule.AfterAdhan -> "+${rule.minutes}"
        // ROOT: an Arabic device locale would otherwise write Arabic-Indic digits into the file.
        is IqamahRule.FixedTime -> "%02d:%02d".format(Locale.ROOT, rule.time.hour, rule.time.minute)
    }

    fun arabicName(prayer: Prayer): String = when (prayer) {
        Prayer.FAJR -> "الفجر"
        Prayer.DHUHR -> "الظهر"
        Prayer.ASR -> "العصر"
        Prayer.MAGHRIB -> "المغرب"
        Prayer.ISHA -> "العشاء"
        Prayer.JOMOAA -> "الجمعة"
        Prayer.AID_FITR -> "عيد الفطر"
        Prayer.AID_ADHA -> "عيد الأضحى"
    }

    fun dateEventName(event: DateEvent): String = when (event) {
        DateEvent.RAMADAN_START -> "بداية رمضان"
        DateEvent.EID_FITR -> "عيد الفطر"
        DateEvent.EID_ADHA -> "عيد الأضحى"
    }

    private val KEYS = mapOf(
        Prayer.FAJR to "fajr", Prayer.DHUHR to "dhuhr", Prayer.ASR to "asr",
        Prayer.MAGHRIB to "maghrib", Prayer.ISHA to "isha", Prayer.JOMOAA to "jumua",
        Prayer.AID_FITR to "eidFitr", Prayer.AID_ADHA to "eidAdha",
    )

    private val ALIASES: Map<String, List<Prayer>> = buildMap {
        fun alias(prayers: List<Prayer>, vararg names: String) = names.forEach { put(it, prayers) }
        alias(listOf(Prayer.FAJR), "fajr", "sobh", "subh", "الفجر", "الصبح")
        alias(listOf(Prayer.DHUHR), "dhuhr", "dohr", "zuhr", "thuhr", "الظهر")
        alias(listOf(Prayer.ASR), "asr", "العصر")
        alias(listOf(Prayer.MAGHRIB), "maghrib", "maghreb", "المغرب")
        alias(listOf(Prayer.ISHA), "isha", "icha", "ishaa", "العشاء")
        alias(listOf(Prayer.JOMOAA), "jumua", "jumuah", "jumaa", "jomoaa", "joumouaa", "juma", "الجمعة")
        alias(MosqueSchedule.EID, "eid", "eids", "aid", "العيد", "العيدين")
        alias(listOf(Prayer.AID_FITR), "eidfitr", "eid_fitr", "eid-fitr", "aidfitr", "عيد الفطر")
        alias(listOf(Prayer.AID_ADHA), "eidadha", "eid_adha", "eid-adha", "aidadha", "عيد الأضحى", "عيد الاضحى")
    }

    private fun prayersFor(key: String): List<Prayer>? = ALIASES[clean(key).trim().lowercase()]

    private val FIELD_ALIASES: Map<String, Field> = buildMap {
        listOf("iqamah", "iqama", "iqamat", "الإقامة", "إقامة", "الاقامة", "اقامة").forEach { put(it, Field.IQAMAH) }
        listOf("duration", "duree", "durée", "المدة", "مدة", "مدة الصلاة").forEach { put(it, Field.DURATION) }
        listOf("held", "تقام", "تُقام").forEach { put(it, Field.HELD) }
        listOf("khutba", "الخطبة", "خطبة", "مدة الخطبة").forEach { put(it, Field.KHUTBA) }
        listOf("dua", "adhandua", "adhan_dua", "الدعاء", "دعاء الأذان", "دعاء الاذان").forEach { put(it, Field.DUA) }
    }

    private val DATE_ALIASES: Map<String, DateEvent> = buildMap {
        listOf("ramadanstart", "ramadan_start", "ramadan", "بداية رمضان", "رمضان").forEach { put(it, DateEvent.RAMADAN_START) }
        listOf("eidfitr", "eid_fitr", "fitr", "عيد الفطر").forEach { put(it, DateEvent.EID_FITR) }
        listOf("eidadha", "eid_adha", "adha", "عيد الأضحى", "عيد الاضحى").forEach { put(it, DateEvent.EID_ADHA) }
    }

    /** Free-text fields an admin may add for themselves. */
    private val IGNORED_FIELDS = setOf("note", "notes", "comment", "comments", "ملاحظة", "ملاحظات")

    private val ADHKAR_SECTION = setOf("adhkar", "azkar", "الأذكار", "الاذكار")
    private val ANNOUNCEMENTS_SECTION = setOf("announcements", "الإعلانات", "الاعلانات")
    private val FORMULA_SECTION = setOf("prayertimes", "prayer_times", "formula", "حساب المواقيت")

    private val FORMULA_FIELDS: Map<String, FormulaField> = buildMap {
        listOf("fajrangle", "fajr_angle", "زاوية الفجر").forEach { put(it, FormulaField.FAJR_ANGLE) }
        listOf("ishaangle", "isha_angle", "زاوية العشاء").forEach { put(it, FormulaField.ISHA_ANGLE) }
        listOf("asrshadow", "asr_shadow", "ظل العصر", "ظلّ العصر").forEach { put(it, FormulaField.ASR_SHADOW) }
        listOf("dhuhrminutes", "dhuhr_minutes", "الظهر بعد الزوال").forEach { put(it, FormulaField.DHUHR_MINUTES) }
        listOf("maghribminutes", "maghrib_minutes", "المغرب بعد الغروب").forEach { put(it, FormulaField.MAGHRIB_MINUTES) }
        listOf("elevation", "ارتفاع المكان", "ارتفاع المسجد", "حساب ارتفاع المسجد").forEach { put(it, FormulaField.ELEVATION) }
        listOf("adjust", "adjustments", "تعديل", "التعديل").forEach { put(it, FormulaField.ADJUSTMENT) }
    }

    /** The sunrise, named in "adjust" by an admin who expects to move it: refused with a word on why. */
    private val SHURUK_NAMES = setOf("shuruk", "shurouk", "chourouk", "sunrise", "الشروق")
    private val FROM_KEYS = setOf("from", "start", "من")
    private val UNTIL_KEYS = setOf("until", "to", "end", "إلى", "الى", "حتى")
    private val ADHKAR_LISTS = mapOf(
        "aftersalah" to ContentList.AFTER_SALAH, "after_salah" to ContentList.AFTER_SALAH, "بعد الصلاة" to ContentList.AFTER_SALAH,
        "ticker" to ContentList.TICKER, "الشريط" to ContentList.TICKER,
    )
    private val MODE_KEYS = setOf("mode", "الطريقة")
    private val ITEMS_KEYS = setOf("items", "texts", "النصوص")
    private val TEXT_KEYS = setOf("text", "النص")
    private val ID_KEYS = setOf("id")
    private val REFERENCE_KEYS = setOf("reference", "source", "المصدر", "المرجع")
    private val COUNT_KEYS = setOf("count", "repetitions", "العدد")
    private const val MAX_ADHKAR_ITEMS = 100
    /** A settings file is at most 5 levels deep; far deeper is refused before it is read. */
    private const val MAX_DEPTH = 32
    private const val MAX_ADHKAR_TEXT = 1000
    private const val MAX_ADHKAR_REFERENCE = 200
    private const val MAX_ADHKAR_COUNT = 1000

    /** Written by the TV for the admin to read; followed only when the admin edited it ([readPlaceName]). */
    private val INFORMATION_FIELDS = setOf("delegationname", "delegation_name")

    private val MOSQUE_FIELDS: Map<String, ProfileField> = buildMap {
        listOf("name", "mosquename", "الاسم", "اسم المسجد").forEach { put(it, ProfileField.NAME) }
        listOf("delegation", "delegationid", "delegation_id", "المعتمدية").forEach { put(it, ProfileField.DELEGATION) }
    }

    private val DISPLAY_FIELDS: Map<String, ProfileField> = buildMap {
        listOf("theme", "المظهر").forEach { put(it, ProfileField.THEME) }
        listOf("weather", "الطقس").forEach { put(it, ProfileField.WEATHER) }
        listOf("backgrounds", "الخلفيات").forEach { put(it, ProfileField.BACKGROUNDS) }
        listOf("announcements", "الإعلانات", "الاعلانات").forEach { put(it, ProfileField.ANNOUNCEMENTS) }
        listOf("slideseconds", "slide_seconds", "مدة الإعلان").forEach { put(it, ProfileField.SLIDE_SECONDS) }
        listOf("announcementseveryminutes", "announcements_every_minutes", "تكرار الإعلانات").forEach { put(it, ProfileField.ANNOUNCEMENTS_EVERY) }
        listOf("nightscreen", "night_screen", "شاشة الليل").forEach { put(it, ProfileField.NIGHT_SCREEN) }
        listOf("adhanscreenminutes", "adhan_screen_minutes", "مدة شاشة الأذان").forEach { put(it, ProfileField.ADHAN_SCREEN) }
    }

    /** The sections of the file under their accepted names; the first five must be objects. */
    private val SECTIONS: List<Pair<String, Set<String>>> = listOf(
        "prayers" to setOf("prayers", "الصلوات"),
        "ramadan" to setOf("ramadan", "رمضان"),
        "islamicDates" to setOf("islamicdates", "islamic_dates", "dates", "التواريخ"),
        "mosque" to setOf("mosque", "masjid", "المسجد"),
        "display" to setOf("display", "screen", "العرض", "الشاشة"),
        "adhkar" to ADHKAR_SECTION,
        "announcements" to ANNOUNCEMENTS_SECTION,
        "prayerTimes" to FORMULA_SECTION,
    )
    private const val OBJECT_SECTIONS = 5

    /**
     * Every top-level key must be known: a misspelled section ("ramadhan") would otherwise be left
     * out without a word while the rest of the file is applied. A section named twice is refused too.
     */
    private fun rootErrors(root: JsonObject): List<SettingsError> {
        val errors = mutableListOf<SettingsError>()
        val seen = mutableMapOf<String, String>()
        for ((key, value) in root) {
            val cleanKey = clean(key).trim().lowercase()
            if (cleanKey == "format" || cleanKey == "version" || cleanKey in IGNORED_FIELDS) continue
            val index = SECTIONS.indexOfFirst { cleanKey in it.second }
            if (index < 0) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, key,
                    "قسم غير معروف «${short(key)}»: الأقسام هي ${SECTIONS.joinToString("، ") { it.first }}")
                continue
            }
            val name = SECTIONS[index].first
            seen.put(name, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, key, "القسم «$name» مذكور مرتين («${short(first)}» و«${short(key)}»)")
                continue
            }
            if (index < OBJECT_SECTIONS && value !is JsonObject) {
                errors += SettingsError(ErrorCode.NOT_AN_OBJECT, key, "القسم «${short(key)}» يُكتب بين قوسين ${code("{ }")}")
            }
            if (name == "prayerTimes" && value !is JsonObject && value !is JsonNull) {
                errors += SettingsError(ErrorCode.NOT_AN_OBJECT, key,
                    "القسم «${short(key)}» يُكتب بين قوسين ${code("{ }")}، أو ${code("null")} للرجوع إلى الأوقات الرسمية")
            }
        }
        return errors
    }

    private sealed interface KeyScan {
        data object Clean : KeyScan
        /** Nested deeper than any settings file: a hostile file, refused before it is read further. */
        data object TooDeep : KeyScan
        /** The path of the first key written twice in the same object ("prayers.isha"). */
        data class Duplicate(val path: String) : KeyScan
    }

    /**
     * Keys written twice, and nesting. Reads the raw text (comments included) because the parsed
     * tree has already kept only the last value of a key written twice.
     */
    private fun scanKeys(text: String): KeyScan {
        class Frame(val isObject: Boolean, val path: String) {
            val keys = mutableSetOf<String>()
            var key: String? = null
            var index = 0
            fun child(): String = if (isObject) listOfNotNull(path.ifEmpty { null }, key).joinToString(".") else "$path[$index]"
        }
        val stack = ArrayDeque<Frame>()
        var lastString: String? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val out = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        out.append(text[i])
                        i++
                    }
                    lastString = out.toString()
                }
                c == '/' && text.getOrNull(i + 1) == '/' -> while (i < text.length && text[i] != '\n') i++
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 1 }
                }
                c == '{' || c == '[' -> {
                    if (stack.size >= MAX_DEPTH) return KeyScan.TooDeep
                    val path = stack.lastOrNull()?.child().orEmpty()
                    stack.addLast(Frame(c == '{', path))
                }
                c == '}' || c == ']' -> stack.removeLastOrNull()
                c == ':' -> stack.lastOrNull()?.takeIf { it.isObject }?.let { frame ->
                    val key = lastString.orEmpty()
                    if (!frame.keys.add(key)) return KeyScan.Duplicate(listOfNotNull(frame.path.ifEmpty { null }, key).joinToString("."))
                    frame.key = key
                }
                c == ',' -> stack.lastOrNull()?.let { frame -> if (frame.isObject) frame.key = null else frame.index++ }
            }
            i++
        }
        return KeyScan.Clean
    }

    /**
     * An item's keys ({ "text": …, "from": … }): each must be one of [fields] (a field and its other
     * names) or a note, and no field twice under two of its names. False when one is not. [where]
     * names the item at the head of its mistakes («الإعلان 4»).
     */
    private fun itemKeysOk(
        path: String,
        item: JsonObject,
        fields: List<Set<String>>,
        hint: String,
        errors: MutableList<SettingsError>,
        where: String? = null,
    ): Boolean {
        val before = errors.size
        val seen = mutableMapOf<Set<String>, String>()
        val prefix = where?.let { "$it: " }.orEmpty()
        for (key in item.keys) {
            val cleanKey = clean(key).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val field = fields.firstOrNull { cleanKey in it }
            if (field == null) {
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, "$path.$key", "${prefix}حقل غير معروف «${short(key)}»: استعمل $hint")
                continue
            }
            seen.put(field, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, "$path.$key", "${prefix}الحقل مذكور مرتين («${short(first)}» و«${short(key)}»)")
            }
        }
        return errors.size == before
    }

    /** The accepted names of a field, as the admin types them: "text" و"from" و"until", or with أو. */
    private fun keyList(vararg keys: String, or: Boolean = false): String =
        keys.joinToString(if (or) " أو " else " و") { code("\"$it\"") }

    /** A top-level section under any of its accepted names, with the name used in the file (for error paths). */
    private fun JsonObject.section(vararg names: String): Pair<String, JsonObject>? =
        entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in names }
            ?.let { (key, value) -> (value as? JsonObject)?.let { key to it } }

    /** The dates a file may set for [event] of Hijri [year]: within a few days of the calendar estimate. */
    fun acceptedDates(year: Int, event: DateEvent): ClosedRange<LocalDate> {
        val expected = estimatedDate(TunisianHijriCalendar(), year, event)
        return expected.minusDays(MAX_DAYS_FROM_ESTIMATE)..expected.plusDays(MAX_DAYS_FROM_ESTIMATE)
    }

    /**
     * Why the admin's [manual] dates for the year of [dates] cannot be used, or null. Each date the
     * admin changes ([changed]) must leave months of 29 or 30 days between it and the admin's other
     * dates and the announced ones in [dates]; an estimate moves along and never stands in the way.
     * Across several months, an announcement of another event keeps its day as the calendar does
     * (months of 28 to 31 days between): a mosque with its own Eid al-Fitr may follow the nation's
     * Eid al-Adha. A Ramadan of 28 or 31 days is always refused.
     * The TV's dates page, a settings file and the phone's page all say it the same way.
     */
    fun datesConflict(dates: YearDates, manual: ManualIslamicDates, changed: Set<DateEvent>): String? {
        // An event's date, where it starts its month ([intoMonth] days after 1 Dhul Hijja for Eid al-Adha).
        class Anchor(val event: DateEvent, val month: Int, val date: LocalDate, val intoMonth: Long, val admin: Boolean)
        fun anchor(event: DateEvent, known: EventDate, month: Int, intoMonth: Long): Anchor? {
            dateOf(manual, event)?.let { return Anchor(event, month, it, intoMonth, admin = true) }
            val announced = known.withoutManual.takeIf { known.announced } ?: return null
            return Anchor(event, month, announced, intoMonth, admin = false)
        }
        val anchors = listOfNotNull(
            anchor(DateEvent.RAMADAN_START, dates.ramadanStart, 9, 0),
            anchor(DateEvent.EID_FITR, dates.eidFitr, 10, 0),
            anchor(DateEvent.EID_ADHA, dates.eidAdha, 12, 9),
        )
        for ((first, second) in anchors.zipWithNext()) {
            if (first.event !in changed && second.event !in changed) continue
            val months = (second.month - first.month).toLong()
            val shift = second.intoMonth - first.intoMonth
            val lengths = if (months > 1 && first.admin != second.admin) 28L..31L else 29L..30L
            val possible = (lengths.first * months + shift)..(lengths.last * months + shift)
            val days = ChronoUnit.DAYS.between(first.date, second.date)
            if (days in possible) continue
            val fix = listOf(first, second).firstOrNull { it.event !in changed }?.let { "عدّل ${dateEventName(it.event)} أيضًا" } ?: "عدّل أحدهما"
            return if (months == 1L) "رمضان سيكون $days يومًا، والشهر 29 أو 30 يومًا: $fix"
            else "بين ${dateEventName(first.event)} و${dateEventName(second.event)} $days يومًا، والممكن من ${possible.first} إلى ${possible.last}: $fix"
        }
        return null
    }

    private fun dateOf(dates: ManualIslamicDates, event: DateEvent): LocalDate? = when (event) {
        DateEvent.RAMADAN_START -> dates.ramadanStart
        DateEvent.EID_FITR -> dates.eidFitr
        DateEvent.EID_ADHA -> dates.eidAdha
    }

    private fun estimatedDate(estimate: TunisianHijriCalendar, year: Int, event: DateEvent): LocalDate = when (event) {
        DateEvent.RAMADAN_START -> estimate.month(year, 9).start
        DateEvent.EID_FITR -> estimate.month(year, 10).start
        DateEvent.EID_ADHA -> estimate.month(year, 12).start.plusDays(9)
    }

    private fun dateChanges(before: Map<Int, ManualIslamicDates>, after: Map<Int, ManualIslamicDates>): List<DateChange> =
        after.toSortedMap().flatMap { (year, dates) ->
            val old = before[year] ?: ManualIslamicDates()
            listOfNotNull(
                DateChange(year, DateEvent.RAMADAN_START, old.ramadanStart, dates.ramadanStart).takeIf { old.ramadanStart != dates.ramadanStart },
                DateChange(year, DateEvent.EID_FITR, old.eidFitr, dates.eidFitr).takeIf { old.eidFitr != dates.eidFitr },
                DateChange(year, DateEvent.EID_ADHA, old.eidAdha, dates.eidAdha).takeIf { old.eidAdha != dates.eidAdha },
            )
        }

    private val DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")

    private fun parseDate(element: JsonElement): LocalDate? {
        val text = (element as? JsonPrimitive)?.content?.let { clean(normalizeDigits(it)).trim() } ?: return null
        val match = DATE.matchEntire(text) ?: return null
        val (year, month, day) = match.destructured
        return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }

    private sealed interface IqamahParse {
        data class Ok(val rule: IqamahRule) : IqamahParse
        data class Error(val error: SettingsError) : IqamahParse
    }

    private val AFTER_ADHAN = Regex("""\+?(\d{1,3})""")
    private val FIXED_TIME = Regex("""(\d{1,2})[:hH.](\d{2})""")

    private fun iqamahRule(element: JsonElement, prayer: Prayer, path: String): IqamahParse {
        val raw = (element as? JsonPrimitive)?.content
        val text = raw?.let { clean(normalizeDigits(it)) }?.filterNot(Char::isWhitespace).orEmpty()
        // The Eid prayers have no adhan: their minutes count from sunrise.
        val after = if (prayer in MosqueSchedule.EID) "بعد الشروق" else "بعد الأذان"
        val invalid = IqamahParse.Error(SettingsError(ErrorCode.INVALID_IQAMAH, path,
            "وقت الإقامة لصلاة ${arabicName(prayer)} غير صالح «${element.quoted()}»: اكتب ${code("+10")} ($after) أو ${code("20:00")} (وقت ثابت)"))
        FIXED_TIME.matchEntire(text)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            return if (hour in 0..23 && minute in 0..59) IqamahParse.Ok(IqamahRule.FixedTime(LocalTime.of(hour, minute))) else invalid
        }
        AFTER_ADHAN.matchEntire(text)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            return if (minutes in MosqueSchedule.IQAMAH_MINUTES) {
                IqamahParse.Ok(IqamahRule.AfterAdhan(minutes))
            } else {
                val range = MosqueSchedule.IQAMAH_MINUTES
                IqamahParse.Error(SettingsError(ErrorCode.IQAMAH_OUT_OF_RANGE, path,
                    "الإقامة لصلاة ${arabicName(prayer)} $after بـ $minutes دقيقة: اختر بين ${range.first} و${range.last} دقيقة"))
            }
        }
        return invalid
    }

    private fun changes(before: MosqueSchedule, after: MosqueSchedule): List<Change> {
        val settings = (MosqueSchedule.CONFIGURABLE + MosqueSchedule.EID).flatMap { prayer ->
            val old = before.settings(prayer)
            val new = after.settings(prayer)
            listOfNotNull(
                Change(prayer, Field.IQAMAH, iqamahText(old.iqamah), iqamahText(new.iqamah)).takeIf { old.iqamah != new.iqamah },
                Change(prayer, Field.DURATION, old.salahMinutes.toString(), new.salahMinutes.toString())
                    .takeIf { old.salahMinutes != new.salahMinutes },
                Change(prayer, Field.HELD, old.held.toString(), new.held.toString()).takeIf { old.held != new.held },
                Change(prayer, Field.KHUTBA, old.khutbaMinutes.toString(), new.khutbaMinutes.toString())
                    .takeIf { old.khutbaMinutes != new.khutbaMinutes },
                Change(prayer, Field.DUA, old.adhanDua.toString(), new.adhanDua.toString()).takeIf { old.adhanDua != new.adhanDua },
            )
        }
        val ramadan = MosqueSchedule.CONFIGURABLE.flatMap { prayer ->
            val old = before.ramadan[prayer] ?: PrayerOverride()
            val new = after.ramadan[prayer] ?: PrayerOverride()
            listOfNotNull(
                Change(prayer, Field.IQAMAH, old.iqamah?.let(::iqamahText) ?: "—", new.iqamah?.let(::iqamahText) ?: "—", ramadan = true)
                    .takeIf { old.iqamah != new.iqamah },
                Change(prayer, Field.DURATION, old.salahMinutes?.toString() ?: "—", new.salahMinutes?.toString() ?: "—", ramadan = true)
                    .takeIf { old.salahMinutes != new.salahMinutes },
            )
        }
        return settings + ramadan
    }

    /** Arabic-Indic (٠-٩) and Persian (۰-۹) digits as ASCII. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (char in text) {
            append(
                when (char) {
                    in '٠'..'٩' -> '0' + (char - '٠')
                    in '۰'..'۹' -> '0' + (char - '۰')
                    else -> char
                }
            )
        }
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Whole numbers written as 10, "10" or "١٠"; null for anything else (10.5, true, "ten"). */
    private fun JsonElement.intOrNull(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        val text = clean(normalizeDigits(primitive.content)).trim()
        return if (Regex("""\d{1,4}""").matches(text)) text.toInt() else null
    }

    /** Whole minutes with an optional sign: 2, -1, "+2", "−1" (the minus sign), "-١"; null for anything else. */
    private fun JsonElement.signedIntOrNull(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        val text = clean(normalizeDigits(primitive.content)).replace('−', '-').filterNot(Char::isWhitespace)
        return if (Regex("""[+-]?\d{1,3}""").matches(text)) text.toInt() else null
    }

    /** An angle in degrees written 18, 17.5, "17.5", "١٧٫٥" or "17,5"; null for anything else. */
    private fun JsonElement.angleOrNull(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        val text = clean(normalizeDigits(primitive.content)).trim().replace('٫', '.').replace(',', '.')
        return if (Regex("""\d{1,2}(\.\d{1,3})?""").matches(text)) text.toDouble() else null
    }

    /** The value as quoted in an error: short, and never a stringified tree (deep nesting would overflow the stack). */
    private fun JsonElement.display(): String = when (this) {
        is JsonPrimitive -> content.let { if (it.length > 40) it.take(40) + "…" else it }
        is JsonObject -> "{…}"
        else -> "[…]"
    }

    /**
     * The file's value inside an Arabic message, kept left to right as [code] keeps a sample: a refused
     * "-1" must read «-1», not «1-». The file's own direction marks go first, so none can end the isolate early.
     */
    private fun JsonElement.quoted(): String = code(cleanText(display()))

    /** A Hijri year's key inside a message, like [quoted]: the file's "-1448" must read «-1448», not «1448-». */
    private fun keyQuoted(key: String): String = code(cleanText(short(key)))

    /**
     * Without invisible formatting characters (RLM/LRM/ALM, bidi embeddings, ZWJ): Arabic keyboards insert
     * them around values, and "‏20:00" must still read as 20:00.
     */
    private fun clean(text: String): String = text.filterNot { Character.getType(it) == Character.FORMAT.toInt() }

    /**
     * For texts shown on screen: only the invisible direction marks go. Other format characters are
     * visible Arabic signs there, such as the end-of-verse ornament ۝ (U+06DD) in pasted Quran.
     */
    private fun cleanText(text: String): String = text.filterNot { it in BIDI_MARKS }

    private val BIDI_MARKS: Set<Char> = setOf('\u200E', '\u200F', '\u061C', '\uFEFF') +
        ('\u202A'..'\u202E').toSet() + ('\u2066'..'\u2069').toSet()

    private fun fail(code: ErrorCode, path: String, message: String) = ParseResult.Failure(listOf(SettingsError(code, path, message)))

    /**
     * A sample of the file inside an Arabic message, kept left to right (LRI…PDI, as the TV's verse
     * ranges): otherwise "+10" reads «10+» and a JSON sample loses its quotes and braces.
     */
    private fun code(sample: String): String = "⁦$sample⁩"

    /** A key as quoted in an error: a hostile file's key of a few hundred thousand letters must not fill the screen. */
    private fun short(key: String): String = if (key.length > 40) key.take(40) + "…" else key

    /** Why [text] is not JSON: the Arabic keyboard's usual culprit, or the line where the reader stopped. */
    private fun invalidJson(text: String, error: Exception): String {
        lookalike(text)?.let { (line, char) ->
            return if (char == '،') {
                "في السطر $line فاصلة عربية «،»: اكتب الفاصلة ${code(",")} بين العناصر"
            } else {
                "في السطر $line علامة تنصيص ${code(char.toString())}: اكتب علامة التنصيص المستقيمة ${code("\"")}"
            }
        }
        val offset = OFFSET.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= text.length }
            ?: return MESSAGE_INVALID_JSON
        val line = text.removePrefix("﻿").take(offset).count { it == '\n' } + 1
        return "تعذّرت قراءة الملف قرب السطر $line: تحقّق من الأقواس والفواصل وعلامات التنصيص"
    }

    /**
     * The line and character of the first Arabic comma or typographic quote outside a string and a
     * comment: Arabic keyboards and word processors put them where JSON wants , and ".
     */
    private fun lookalike(text: String): Pair<Int, Char>? {
        var line = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> line++
                c == '"' -> {
                    i++
                    // A string never spans lines: one left open ends at the line's end, not the file's.
                    while (i < text.length && text[i] != '"' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
                    if (text.getOrNull(i) == '\n') line++
                }
                c == '/' && text.getOrNull(i + 1) == '/' -> while (i + 1 < text.length && text[i + 1] != '\n') i++
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 1 }
                    line += text.substring(i, minOf(end, text.length)).count { it == '\n' }
                    i = end
                }
                c in LOOKALIKES -> return line to c
            }
            i++
        }
        return null
    }

    private val LOOKALIKES = setOf('،', '“', '”', '„', '‘', '’', '«', '»')
    private val OFFSET = Regex("""offset (\d+)""")

    private const val MESSAGE_INVALID_JSON = "تعذّرت قراءة الملف: تحقّق من الأقواس والفواصل وعلامات التنصيص"
}
