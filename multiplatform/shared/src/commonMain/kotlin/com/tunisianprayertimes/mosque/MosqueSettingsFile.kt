package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.TunisianHijriCalendar
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
 *       "display": { "theme": "midnight_navy" },
 *       "prayers": { "fajr": { "iqamah": "+20", "duration": 10 }, "isha": { "iqamah": "20:00" },
 *                    "eid": { "iqamah": "+30", "duration": 30 } },
 *       "ramadan": { "isha": { "duration": 75 } },
 *       "islamicDates": { "1448": { "ramadanStart": "2027-02-08", "eidFitr": "2027-03-10" } } }
 *
 * "iqamah" is "+N" minutes after the adhan (after sunrise for the Eid prayers) or a fixed "HH:MM";
 * "duration" is the prayer's length in minutes (the black screen). "ramadan" changes prayers on the
 * days of Ramadan (null returns a field to the usual setting). "islamicDates" sets the year's Ramadan
 * and Eid dates by hand (null returns a date to automatic). "mosque" and "display" carry the rest of a
 * TV's settings, so a file written by one TV sets up another. "adhkar" puts the mosque's own texts
 * after the bundled, reviewed ones ("mode": "append") or in their place ("replace"); each needs a
 * "reference"; null returns to the bundled texts. "announcements" lists written announcements with
 * optional "from" and "until" dates, and replaces the TV's list. Every field is optional. Files are edited
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

    enum class Field { IQAMAH, DURATION }

    /** One setting that the file changes, rendered as in the file ("+15", "20:00", "10"); [ramadan] for Ramadan changes. */
    data class Change(val prayer: Prayer, val field: Field, val before: String, val after: String, val ramadan: Boolean = false)

    enum class DateEvent { RAMADAN_START, EID_FITR, EID_ADHA }

    /** A Ramadan or Eid date the file sets or returns to automatic (null). */
    data class DateChange(val hijriYear: Int, val event: DateEvent, val before: LocalDate?, val after: LocalDate?)

    enum class ProfileField { NAME, DELEGATION, THEME }

    /** The mosque's name, place or theme changed by the file, as the admin reads them ("—" when unset). */
    data class ProfileChange(val field: ProfileField, val before: String, val after: String)

    enum class ContentList { AFTER_SALAH, TICKER, ANNOUNCEMENTS }

    /** The texts shown after the prayer or in the ticker, before and after the file, described for the admin. */
    data class ContentChange(val list: ContentList, val before: String, val after: String)

    enum class ErrorCode {
        TOO_LARGE, INVALID_JSON, NOT_AN_OBJECT, UNKNOWN_FORMAT, UNSUPPORTED_VERSION, NO_PRAYERS,
        UNKNOWN_PRAYER, DUPLICATE_PRAYER, NOT_A_PRAYER_OBJECT, UNKNOWN_FIELD, DUPLICATE_FIELD,
        INVALID_IQAMAH, IQAMAH_OUT_OF_RANGE, INVALID_DURATION, DURATION_OUT_OF_RANGE,
        INVALID_YEAR, INVALID_DATE, DATE_OUT_OF_RANGE, INVALID_NAME, UNKNOWN_DELEGATION, UNKNOWN_THEME,
        INVALID_ADHKAR, INVALID_ANNOUNCEMENT,
    }

    /** A problem in the file: [path] locates it (for example prayers.isha.iqamah); [message] is for the TV screen. */
    data class SettingsError(val code: ErrorCode, val path: String, val message: String)

    sealed interface ParseResult {
        /** [islamicDates] holds the complete admin dates of every year the file mentions. */
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
        ) : ParseResult {
            val hasChanges: Boolean
                get() = changes.isNotEmpty() || dateChanges.isNotEmpty() || profileChanges.isNotEmpty() || contentChanges.isNotEmpty()
        }

        data class Failure(val errors: List<SettingsError>) : ParseResult
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowTrailingComma = true
        allowComments = true
    }

    /**
     * Reads [text] against the [current] settings, the admin's [currentDates] and [currentProfile];
     * [catalog] tells which places and themes exist. Never throws, even for hostile input (deep nesting).
     */
    fun parse(
        text: String,
        current: MosqueSchedule,
        currentDates: Map<Int, ManualIslamicDates> = emptyMap(),
        currentProfile: MosqueProfile = MosqueProfile(),
        catalog: ProfileCatalog = ProfileCatalog.NONE,
        currentContent: AdhkarContent = AdhkarContent(),
        currentAnnouncements: List<TextAnnouncement> = emptyList(),
    ): ParseResult = try {
        parseOrFail(text, current, currentDates, currentProfile, catalog, currentContent, currentAnnouncements)
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
    ): ParseResult {
        if (text.length > MAX_CHARS) return fail(ErrorCode.TOO_LARGE, "", "الملف كبير جدًا: هذا ليس ملف إعدادات شاشة المسجد")
        val root = runCatching { json.parseToJsonElement(text.removePrefix("﻿")) }.getOrNull()
            ?: return fail(ErrorCode.INVALID_JSON, "", MESSAGE_INVALID_JSON)
        if (root !is JsonObject) return fail(ErrorCode.NOT_AN_OBJECT, "", MESSAGE_INVALID_JSON)
        // The JSON reader keeps the last of two identical keys: the admin's added line would vanish.
        duplicateKey(text)?.let { path ->
            return fail(ErrorCode.DUPLICATE_FIELD, path, "«${path.substringAfterLast('.')}» مذكور مرتين في الملف: احذف أحدهما")
        }
        rootErrors(root).takeIf { it.isNotEmpty() }?.let { return ParseResult.Failure(it) }

        root["format"]?.let { format ->
            if (format.stringOrNull()?.let(::clean)?.trim() != FORMAT) {
                return fail(ErrorCode.UNKNOWN_FORMAT, "format", "هذا الملف ليس ملف إعدادات شاشة المسجد")
            }
        }
        root["version"]?.let { version ->
            if (version.intOrNull() != VERSION) {
                return fail(ErrorCode.UNSUPPORTED_VERSION, "version", "إصدار الملف غير مدعوم: حدّث التطبيق أو استعمل \"version\": 1")
            }
        }
        val prayers = root.section("prayers", "الصلوات")
        val ramadan = root.section("ramadan", "رمضان")
        val dates = root.section("islamicdates", "islamic_dates", "dates", "التواريخ")
        val mosque = root.section("mosque", "masjid", "المسجد")
        val display = root.section("display", "screen", "العرض", "الشاشة")
        val adhkar = root.entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in ADHKAR_SECTION }
        val announcementsEntry = root.entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in ANNOUNCEMENTS_SECTION }
        if (adhkar == null && announcementsEntry == null && listOf(prayers, ramadan, dates, mosque, display).all { it == null || it.second.isEmpty() }) {
            return fail(ErrorCode.NO_PRAYERS, "prayers", "لا يحتوي الملف على إعدادات الصلوات (\"prayers\")")
        }

        val errors = mutableListOf<SettingsError>()
        var schedule = current
        prayers?.let { (sectionKey, section) ->
            forEachPrayer(section, sectionKey, errors, allowEid = true) { prayer, fields, path ->
                val settings = readFields(fields, prayer, path, errors, schedule.settings(prayer)) { base, field, value ->
                    when (field) {
                        Field.IQAMAH -> base.copy(iqamah = value as IqamahRule)
                        Field.DURATION -> base.copy(salahMinutes = value as Int)
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
                    }
                }
                schedule = schedule.withRamadan(prayer, override)
            }
        }
        val newDates = dates?.let { (sectionKey, section) -> readDates(section, sectionKey, currentDates, errors) }.orEmpty()
        val profile = readProfile(mosque, display, currentProfile, catalog, errors)
        val content = adhkar?.let { (key, value) -> readAdhkar(key, value, currentContent, errors) } ?: currentContent
        val announcements = announcementsEntry?.let { (key, value) -> readAnnouncements(key, value, errors) } ?: currentAnnouncements

        if (errors.isNotEmpty()) return ParseResult.Failure(errors)
        val announcementChange = ContentChange(ContentList.ANNOUNCEMENTS, "${currentAnnouncements.size}", "${announcements.size}")
            .takeIf { announcements != currentAnnouncements }
        return ParseResult.Success(
            schedule, changes(current, schedule), newDates, dateChanges(currentDates, newDates),
            profile, profileChanges(currentProfile, profile, catalog),
            content, contentChanges(currentContent, content) + listOfNotNull(announcementChange),
            announcements,
        )
    }

    /** The "announcements" list, which replaces the TV's; null or [] removes them all. */
    private fun readAnnouncements(sectionKey: String, element: JsonElement, errors: MutableList<SettingsError>): List<TextAnnouncement> {
        if (element is JsonNull) return emptyList()
        val items = element as? JsonArray
        if (items == null || items.size > TextAnnouncement.MAX_COUNT) {
            errors += SettingsError(ErrorCode.INVALID_ANNOUNCEMENT, sectionKey,
                "الإعلانات قائمة مثل [ { \"text\": \"...\", \"until\": \"2026-10-31\" } ]، ${TextAnnouncement.MAX_COUNT} على الأكثر")
            return emptyList()
        }
        return items.mapIndexedNotNull { index, item ->
            val path = "$sectionKey[$index]"
            val fields = item as? JsonObject
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
                fields == null -> "كل إعلان يُكتب مثل { \"text\": \"...\", \"from\": \"2026-10-01\", \"until\": \"2026-10-31\" }"
                text.isNullOrEmpty() || text.length > TextAnnouncement.MAX_LENGTH -> "نص الإعلان فارغ أو أطول من ${TextAnnouncement.MAX_LENGTH} حرف"
                !fromOk || !untilOk -> "التاريخ غير صالح: اكتب مثل 2026-10-31"
                from != null && until != null && until.isBefore(from) -> "تاريخ النهاية قبل تاريخ البداية"
                else -> null
            }
            if (problem != null) {
                errors += SettingsError(ErrorCode.INVALID_ANNOUNCEMENT, path, problem)
                null
            } else {
                TextAnnouncement(text!!, from, until)
            }
        }
    }

    /** The "adhkar" section: null lists return to the bundled texts; a bare array of items is appended. */
    private fun readAdhkar(sectionKey: String, element: JsonElement, current: AdhkarContent, errors: MutableList<SettingsError>): AdhkarContent {
        if (element is JsonNull) return AdhkarContent()
        if (element !is JsonObject) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, sectionKey, "«adhkar» يجب أن يكون مثل { \"afterSalah\": { \"mode\": \"append\", \"items\": [...] } }")
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
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, path, "حقل غير معروف «$key»: استعمل \"afterSalah\" أو \"ticker\"")
                continue
            }
            seenLists.put(list, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "القائمة مذكورة مرتين («$first» و«$key»)")
                continue
            }
            val parsed = if (value is JsonNull) null else readAdhkarList(path, value, errors) ?: continue
            content = when (list) {
                ContentList.AFTER_SALAH -> content.copy(afterSalah = parsed)
                ContentList.TICKER -> content.copy(ticker = parsed)
                ContentList.ANNOUNCEMENTS -> content // not an adhkar list; ADHKAR_LISTS never maps to it
            }
        }
        return content
    }

    private fun readAdhkarList(path: String, value: JsonElement, errors: MutableList<SettingsError>): CustomAdhkarList? {
        val (mode, items) = when (value) {
            is JsonArray -> CustomAdhkarList.Mode.APPEND to value
            is JsonObject -> {
                val modeText = value.entries.firstOrNull { clean(it.key).trim().lowercase() in MODE_KEYS }?.value?.stringOrNull()
                val mode = when (modeText?.let { clean(it).trim().lowercase() }) {
                    null, "append", "add", "إضافة", "اضافة" -> CustomAdhkarList.Mode.APPEND
                    "replace", "استبدال" -> CustomAdhkarList.Mode.REPLACE
                    else -> {
                        errors += SettingsError(ErrorCode.INVALID_ADHKAR, "$path.mode", "«mode» يكون \"append\" (بعد النصوص المضمّنة) أو \"replace\" (بدلها)")
                        return null
                    }
                }
                val items = value.entries.firstOrNull { clean(it.key).trim().lowercase() in ITEMS_KEYS }?.value as? JsonArray
                if (items == null) {
                    errors += SettingsError(ErrorCode.INVALID_ADHKAR, "$path.items", "النصوص تُكتب في «items»: [ { \"text\": \"...\", \"reference\": \"...\" } ]")
                    return null
                }
                mode to items
            }
            else -> {
                errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "النصوص تُكتب مثل { \"mode\": \"append\", \"items\": [...] }")
                return null
            }
        }
        if (items.isEmpty() || items.size > MAX_ADHKAR_ITEMS) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, "عدد النصوص يجب أن يكون بين 1 و$MAX_ADHKAR_ITEMS")
            return null
        }
        val before = errors.size
        val parsed = items.mapIndexedNotNull { index, item -> readDhikr("$path.items[$index]", item, errors) }
        return if (errors.size == before) CustomAdhkarList(mode, parsed) else null
    }

    private fun readDhikr(path: String, element: JsonElement, errors: MutableList<SettingsError>): CustomDhikr? {
        val fields = element as? JsonObject
        fun field(keys: Set<String>) = fields?.entries?.firstOrNull { clean(it.key).trim().lowercase() in keys }?.value
        val text = field(TEXT_KEYS)?.stringOrNull()?.let { cleanText(it).trim() }
        val reference = field(REFERENCE_KEYS)?.stringOrNull()?.let { cleanText(it).trim() }
        val countElement = field(COUNT_KEYS)
        val count = if (countElement == null || countElement is JsonNull) 1 else countElement.intOrNull()
        val problem = when {
            fields == null -> "كل نص يُكتب مثل { \"text\": \"...\", \"reference\": \"...\", \"count\": 3 }"
            text.isNullOrEmpty() || text.length > MAX_ADHKAR_TEXT -> "النص فارغ أو أطول من $MAX_ADHKAR_TEXT حرف"
            reference.isNullOrEmpty() || reference.length > MAX_ADHKAR_REFERENCE -> "لكل نص مصدر في «reference» (مثل «صحيح مسلم 591»)"
            count == null || count !in 1..MAX_ADHKAR_COUNT -> "«count» عدد المرات بين 1 و$MAX_ADHKAR_COUNT"
            else -> null
        }
        if (problem != null) {
            errors += SettingsError(ErrorCode.INVALID_ADHKAR, path, problem)
            return null
        }
        return CustomDhikr(text!!, reference!!, count!!)
    }

    private fun contentChanges(before: AdhkarContent, after: AdhkarContent): List<ContentChange> = listOfNotNull(
        ContentChange(ContentList.AFTER_SALAH, describe(before.afterSalah), describe(after.afterSalah)).takeIf { before.afterSalah != after.afterSalah },
        ContentChange(ContentList.TICKER, describe(before.ticker), describe(after.ticker)).takeIf { before.ticker != after.ticker },
    )

    private fun describe(list: CustomAdhkarList?): String = when (list?.mode) {
        null -> "النصوص المضمّنة"
        CustomAdhkarList.Mode.APPEND -> "النصوص المضمّنة + ${list.items.size} من الملف"
        CustomAdhkarList.Mode.REPLACE -> "${list.items.size} من الملف بدل النصوص المضمّنة"
    }

    /** The "mosque" (name, delegation) and "display" (theme) sections over [current]. */
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
                    errors += SettingsError(ErrorCode.UNKNOWN_FIELD, path, "حقل غير معروف «$key»: استعمل $hint")
                    continue
                }
                seen[field]?.let { first ->
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "الحقل مذكور مرتين («$first» و«$key»)")
                    continue
                }
                seen[field] = key
                read(field, element, path)
            }
        }
        fields(mosque, MOSQUE_FIELDS, "\"name\" أو \"delegation\"") { field, element, path ->
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
                            "المعتمدية «${element.display()}» غير معروفة: انسخ رقمها من ملف كتبته شاشة أخرى")
                    } else {
                        profile = profile.copy(delegationId = id)
                    }
                }
            }
        }
        fields(display, DISPLAY_FIELDS, "\"theme\"") { _, element, path ->
            val wanted = element.stringOrNull()?.let { clean(it).trim() }
            val theme = catalog.themes.entries.firstOrNull { (id, name) -> wanted != null && (id.equals(wanted, ignoreCase = true) || name == wanted) }
            if (theme == null) {
                errors += SettingsError(ErrorCode.UNKNOWN_THEME, path,
                    "المظهر «${element.display()}» غير معروف: استعمل ${catalog.themes.keys.joinToString(" أو ")}")
            } else {
                profile = profile.copy(themeId = theme.key)
            }
        }
        return profile
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
        )
    }

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
                errors += SettingsError(ErrorCode.UNKNOWN_PRAYER, path, "صلاة غير معروفة «$key»: استعمل $names")
                continue
            }
            val duplicate = targets.firstOrNull { it in seen }
            if (duplicate != null) {
                errors += SettingsError(ErrorCode.DUPLICATE_PRAYER, path,
                    "صلاة ${arabicName(duplicate)} مذكورة مرتين («${seen.getValue(duplicate)}» و«$key»)")
                continue
            }
            targets.forEach { seen[it] = key }
            if (value !is JsonObject) {
                errors += SettingsError(ErrorCode.NOT_A_PRAYER_OBJECT, path,
                    "إعدادات صلاة ${arabicName(targets.first())} يجب أن تكون مثل { \"iqamah\": \"+10\", \"duration\": 10 }")
                continue
            }
            targets.forEach { onPrayer(it, value, path) }
        }
    }

    /**
     * Applies the "iqamah" and "duration" fields of one prayer object to [start]. A null value is
     * ignored, or clears the field when [clearOnNull] (Ramadan changes returning to the usual setting).
     */
    private fun <T> readFields(
        fields: JsonObject,
        prayer: Prayer,
        path: String,
        errors: MutableList<SettingsError>,
        start: T,
        clearOnNull: Boolean = false,
        set: (T, Field, Any?) -> T,
    ): T {
        var result = start
        val seen = mutableMapOf<Field, String>()
        for ((fieldKey, element) in fields) {
            val fieldPath = "$path.$fieldKey"
            val cleanKey = clean(fieldKey).trim().lowercase()
            if (cleanKey in IGNORED_FIELDS) continue
            val field = FIELD_ALIASES[cleanKey]
            if (field == null) {
                // Ignoring it would apply the file without the setting the admin meant to change.
                errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                    "حقل غير معروف «$fieldKey» في صلاة ${arabicName(prayer)}: استعمل \"iqamah\" أو \"duration\"")
                continue
            }
            seen[field]?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, fieldPath,
                    "الحقل مذكور مرتين في صلاة ${arabicName(prayer)} («$first» و«$fieldKey»)")
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
                            "مدة صلاة ${arabicName(prayer)} غير صالحة «${element.display()}»: اكتب عدد الدقائق، مثلًا 10")
                        minutes !in range -> errors += SettingsError(ErrorCode.DURATION_OUT_OF_RANGE, fieldPath,
                            "مدة صلاة ${arabicName(prayer)} يجب أن تكون بين ${range.first} و${range.last} دقيقة (في الملف: $minutes)")
                        else -> result = set(result, field, minutes)
                    }
                }
            }
        }
        return result
    }

    /** The admin's Ramadan and Eid dates per Hijri year, checked against the calendar estimate. */
    private fun readDates(
        section: JsonObject,
        sectionKey: String,
        currentDates: Map<Int, ManualIslamicDates>,
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
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, path, "السنة $year مذكورة مرتين («$first» و«$yearKey»)")
                    continue
                }
            }
            val supported = year != null && runCatching { estimate.month(year, 12) }.isSuccess
            if (!supported || value !is JsonObject) {
                errors += SettingsError(ErrorCode.INVALID_YEAR, path,
                    "السنة الهجرية «$yearKey» غير صالحة: اكتب مثل \"1448\": { \"ramadanStart\": \"2027-02-08\" }")
                continue
            }
            var dates = currentDates[year] ?: ManualIslamicDates()
            val seen = mutableMapOf<DateEvent, String>()
            for ((fieldKey, element) in value) {
                val fieldPath = "$path.$fieldKey"
                val cleanKey = clean(fieldKey).trim().lowercase()
                if (cleanKey in IGNORED_FIELDS) continue
                val event = DATE_ALIASES[cleanKey]
                if (event == null) {
                    errors += SettingsError(ErrorCode.UNKNOWN_FIELD, fieldPath,
                        "حقل غير معروف «$fieldKey»: استعمل \"ramadanStart\" أو \"eidFitr\" أو \"eidAdha\"")
                    continue
                }
                seen[event]?.let { first ->
                    errors += SettingsError(ErrorCode.DUPLICATE_FIELD, fieldPath, "التاريخ مذكور مرتين («$first» و«$fieldKey»)")
                    continue
                }
                seen[event] = fieldKey
                val date = if (element is JsonNull) null else parseDate(element)
                if (element !is JsonNull && date == null) {
                    errors += SettingsError(ErrorCode.INVALID_DATE, fieldPath,
                        "التاريخ «${element.display()}» غير صالح: اكتب مثل 2027-02-08")
                    continue
                }
                if (date != null) {
                    val expected = estimatedDate(estimate, year, event)
                    if (kotlin.math.abs(ChronoUnit.DAYS.between(expected, date)) > MAX_DAYS_FROM_ESTIMATE) {
                        errors += SettingsError(ErrorCode.DATE_OUT_OF_RANGE, fieldPath,
                            "التاريخ $date بعيد عن ${dateEventName(event)} المتوقَّع لسنة $year ($expected تقريبًا)")
                        continue
                    }
                }
                dates = when (event) {
                    DateEvent.RAMADAN_START -> dates.copy(ramadanStart = date)
                    DateEvent.EID_FITR -> dates.copy(eidFitr = date)
                    DateEvent.EID_ADHA -> dates.copy(eidAdha = date)
                }
            }
            result[year] = dates
        }
        return result
    }

    /**
     * The canonical file for [schedule], the admin's [dates] and the [profile], for exporting the TV's
     * settings to a USB key. The delegation's name is written next to its number for the admin to read.
     * A [complete] file also writes what is unset as null (every Ramadan field, every year of [dates]),
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
        profile.themeId?.let { append("  \"display\": { \"theme\": ").append(JsonPrimitive(it)).append(" },\n") }
        val daily = MosqueSchedule.CONFIGURABLE + MosqueSchedule.EID
        append("  \"prayers\": {\n")
        daily.forEachIndexed { index, prayer ->
            val settings = schedule.settings(prayer)
            append("    \"").append(KEYS.getValue(prayer)).append("\": { \"iqamah\": \"")
                .append(iqamahText(settings.iqamah)).append("\", \"duration\": ").append(settings.salahMinutes).append(" }")
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
                    "        { \"text\": ${JsonPrimitive(item.text)}, \"reference\": ${JsonPrimitive(item.reference)}, \"count\": ${item.count} }"
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
    private val FROM_KEYS = setOf("from", "start", "من")
    private val UNTIL_KEYS = setOf("until", "to", "end", "إلى", "الى", "حتى")
    private val ADHKAR_LISTS = mapOf(
        "aftersalah" to ContentList.AFTER_SALAH, "after_salah" to ContentList.AFTER_SALAH, "بعد الصلاة" to ContentList.AFTER_SALAH,
        "ticker" to ContentList.TICKER, "الشريط" to ContentList.TICKER,
    )
    private val MODE_KEYS = setOf("mode", "الطريقة")
    private val ITEMS_KEYS = setOf("items", "texts", "النصوص")
    private val TEXT_KEYS = setOf("text", "النص")
    private val REFERENCE_KEYS = setOf("reference", "source", "المصدر", "المرجع")
    private val COUNT_KEYS = setOf("count", "repetitions", "العدد")
    private const val MAX_ADHKAR_ITEMS = 100
    private const val MAX_ADHKAR_TEXT = 1000
    private const val MAX_ADHKAR_REFERENCE = 200
    private const val MAX_ADHKAR_COUNT = 1000

    /** Written by the TV for the admin to read; ignored when read back. */
    private val INFORMATION_FIELDS = setOf("delegationname", "delegation_name")

    private val MOSQUE_FIELDS: Map<String, ProfileField> = buildMap {
        listOf("name", "mosquename", "الاسم", "اسم المسجد").forEach { put(it, ProfileField.NAME) }
        listOf("delegation", "delegationid", "delegation_id", "المعتمدية").forEach { put(it, ProfileField.DELEGATION) }
    }

    private val DISPLAY_FIELDS: Map<String, ProfileField> = buildMap {
        listOf("theme", "المظهر").forEach { put(it, ProfileField.THEME) }
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
                    "قسم غير معروف «$key»: الأقسام هي ${SECTIONS.joinToString("، ") { it.first }}")
                continue
            }
            val name = SECTIONS[index].first
            seen.put(name, key)?.let { first ->
                errors += SettingsError(ErrorCode.DUPLICATE_FIELD, key, "القسم «$name» مذكور مرتين («$first» و«$key»)")
                continue
            }
            if (index < OBJECT_SECTIONS && value !is JsonObject) {
                errors += SettingsError(ErrorCode.NOT_AN_OBJECT, key, "القسم «$key» يُكتب بين قوسين { }")
            }
        }
        return errors
    }

    /**
     * The path of the first key written twice in the same object ("prayers.isha"), or null. Reads the
     * raw text (comments included) because the parsed tree has already kept only the last value.
     */
    private fun duplicateKey(text: String): String? {
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
                    val path = stack.lastOrNull()?.child().orEmpty()
                    stack.addLast(Frame(c == '{', path))
                }
                c == '}' || c == ']' -> stack.removeLastOrNull()
                c == ':' -> stack.lastOrNull()?.takeIf { it.isObject }?.let { frame ->
                    val key = lastString.orEmpty()
                    if (!frame.keys.add(key)) return listOfNotNull(frame.path.ifEmpty { null }, key).joinToString(".")
                    frame.key = key
                }
                c == ',' -> stack.lastOrNull()?.let { frame -> if (frame.isObject) frame.key = null else frame.index++ }
            }
            i++
        }
        return null
    }

    /** A top-level section under any of its accepted names, with the name used in the file (for error paths). */
    private fun JsonObject.section(vararg names: String): Pair<String, JsonObject>? =
        entries.firstOrNull { (key, _) -> clean(key).trim().lowercase() in names }
            ?.let { (key, value) -> (value as? JsonObject)?.let { key to it } }

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
        val invalid = IqamahParse.Error(SettingsError(ErrorCode.INVALID_IQAMAH, path,
            "وقت الإقامة لصلاة ${arabicName(prayer)} غير صالح «${element.display()}»: اكتب +10 (بعد الأذان) أو 20:00 (وقت ثابت)"))
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
                    "الإقامة لصلاة ${arabicName(prayer)} بعد الأذان بـ $minutes دقيقة: اختر بين ${range.first} و${range.last} دقيقة"))
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

    /** The value as quoted in an error: short, and never a stringified tree (deep nesting would overflow the stack). */
    private fun JsonElement.display(): String = when (this) {
        is JsonPrimitive -> content.let { if (it.length > 40) it.take(40) + "…" else it }
        is JsonObject -> "{…}"
        else -> "[…]"
    }

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

    private const val MESSAGE_INVALID_JSON = "تعذّرت قراءة الملف: تحقّق من الأقواس والفواصل وعلامات التنصيص"
}
