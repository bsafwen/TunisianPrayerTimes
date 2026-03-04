/* ═══════════════════════════════════════════════════════════
   Tunisian Prayer Times — app.js
   ═══════════════════════════════════════════════════════════ */

"use strict";

// ── DOM refs ──────────────────────────────────────────────────
const inputDelegation = document.getElementById("delegation-input");
const suggestionsEl = document.getElementById("suggestions-list");
const selectYear = document.getElementById("year-select");
const btnDownload = document.getElementById("btn-download");
const statusMsg = document.getElementById("status-msg");

// Calendar DOM refs
const btnShowCalendar = document.getElementById("btn-show-calendar");
const btnHideCalendar = document.getElementById("btn-hide-calendar");
const calendarSection = document.getElementById("calendar-section");
const calMonthTitle = document.getElementById("cal-month-title");
const calTbody = document.getElementById("cal-tbody");
const calPrev = document.getElementById("cal-prev");
const calNext = document.getElementById("cal-next");

// ── State ─────────────────────────────────────────────────────
/** @type {Map<string, {delegationId: number, nameAr: string, nameFr: string}>} */
const delegationMap = new Map(); // nameAr or nameFr → entry

/** @type {{delegationId: number, nameAr: string, nameFr: string}[]} */
let allDelegations = [];

/** @type {Object.<string, number[]>} */
let csvIndex = {};

/** @type {{delegationId: number, nameAr: string, nameFr: string}|null} */
let activeEntry = null;

/** @type {{delegationId: number, nameAr: string, nameFr: string}[]} */
let currentSuggestions = [];

let activeSuggestionIndex = -1;

// Calendar state
const ARABIC_MONTHS = [
  "جانفي",
  "فيفري",
  "مارس",
  "أفريل",
  "ماي",
  "جوان",
  "جويلية",
  "أوت",
  "سبتمبر",
  "أكتوبر",
  "نوفمبر",
  "ديسمبر",
];
let calCurrentMonth = 0; // 0-based (0 = January)
let calCsvCache = {}; // { "delegationId-year-month" : csvText }
let calendarVisible = false;

// ── Generic helpers ───────────────────────────────────────────
function setStatus(text, type = "") {
  statusMsg.textContent = text;
  statusMsg.className = "status-msg" + (type ? " " + type : "");
}

function setLoading(on) {
  btnDownload.classList.toggle("loading", on);
  btnDownload.disabled = on;
}

function stripHeader(csv) {
  const idx = csv.indexOf("\n");
  return idx === -1 ? "" : csv.slice(idx + 1);
}

function triggerDownload(content, filename) {
  const blob =
    content instanceof Blob
      ? content
      : new Blob([content], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function escapeHtml(str) {
  return str.replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// ── Fuzzy-search helpers ──────────────────────────────────────
/**
 * Lowercase + strip Latin accent marks + strip Arabic tashkeel,
 * so "Sfax" matches "sfax", "Tunis" matches "tunis", etc.
 */
function normalizeStr(s) {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "") // Latin combining diacritics
    .replace(/[\u064B-\u065F\u0670]/g, ""); // Arabic tashkeel
}

/**
 * Score how well `query` matches `name` (0 = no match, 4 = exact).
 * Checks: exact → starts-with → word-start → contains.
 */
function scoreMatch(query, name) {
  if (!name || !query) return 0;
  const q = normalizeStr(query);
  const n = normalizeStr(name);
  if (!n.includes(q)) return 0;
  if (n === q) return 4;
  if (n.startsWith(q)) return 3;
  if (n.split(/[\s\-]+/).some((w) => w.startsWith(q))) return 2;
  return 1;
}

/** Return up to `limit` best-matching entries for `query`. */
function getSuggestions(query, limit = 10) {
  const q = query.trim();
  if (q.length === 0) return [];

  const scored = [];
  for (const entry of allDelegations) {
    const s = Math.max(
      scoreMatch(q, entry.nameAr),
      scoreMatch(q, entry.nameFr),
    );
    if (s > 0) scored.push({ entry, score: s });
  }

  scored.sort(
    (a, b) => b.score - a.score || a.entry.nameAr.localeCompare(b.entry.nameAr),
  );
  return scored.slice(0, limit).map((x) => x.entry);
}

/**
 * Wrap the first occurrence of `query` inside `text` in a <mark> tag.
 * Works case-insensitively via the `iu` regex flags.
 */
function highlight(text, query) {
  if (!text) return "";
  const safe = escapeHtml(text);
  const trimQ = query.trim();
  if (!trimQ) return safe;
  try {
    const re = new RegExp(`(${escapeRegex(escapeHtml(trimQ))})`, "iu");
    return safe.replace(re, "<mark>$1</mark>");
  } catch {
    return safe;
  }
}

// ── Autocomplete dropdown ─────────────────────────────────────
function showSuggestions(entries, query) {
  currentSuggestions = entries;
  activeSuggestionIndex = -1;

  if (entries.length === 0) {
    hideSuggestions();
    return;
  }

  suggestionsEl.innerHTML = "";

  for (let i = 0; i < entries.length; i++) {
    const { nameAr, nameFr } = entries[i];
    const li = document.createElement("li");
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", "false");
    li.dataset.index = String(i);

    li.innerHTML =
      `<span class="suggestion-ar">${highlight(nameAr, query)}</span>` +
      `<span class="suggestion-fr">${highlight(nameFr, query)}</span>`;

    // mousedown + preventDefault keeps focus on the input while selecting
    li.addEventListener("mousedown", (e) => {
      e.preventDefault();
      selectEntry(entries[i]);
    });

    suggestionsEl.appendChild(li);
  }

  suggestionsEl.hidden = false;
  inputDelegation.setAttribute("aria-expanded", "true");
}

function hideSuggestions() {
  suggestionsEl.hidden = true;
  activeSuggestionIndex = -1;
  inputDelegation.setAttribute("aria-expanded", "false");
}

function setActiveItem(index) {
  const items = suggestionsEl.querySelectorAll("li");
  items.forEach((li, i) =>
    li.setAttribute("aria-selected", i === index ? "true" : "false"),
  );
  activeSuggestionIndex = index;
  if (index >= 0 && items[index]) {
    items[index].scrollIntoView({ block: "nearest" });
  }
}

function selectEntry(entry) {
  activeEntry = entry;
  inputDelegation.value = entry.nameAr;
  hideSuggestions();
  populateYears(entry.delegationId);
  btnDownload.disabled = selectYear.disabled || !selectYear.value;
  setStatus("");
  hideCalendar();
  calCsvCache = {};
  updateCalendarButtonState();
  // Notify mawaqit-push.js of the new selection
  window.prayerTimesApp = window.prayerTimesApp || {};
  window.prayerTimesApp.activeEntry = entry;
  if (typeof window.mawaqitPushRefresh === "function")
    window.mawaqitPushRefresh();
}

// ── Populate delegation list ──────────────────────────────────
function buildDelegationList(gouvernorats) {
  for (const gouv of gouvernorats) {
    for (const del of gouv.delegations) {
      const nameAr = (del.nomAr || "").trim();
      const nameFr = (del.nomFr || "").trim();
      if (!nameAr && !nameFr) continue;

      const entry = { delegationId: del.id, nameAr, nameFr };

      if (nameAr) delegationMap.set(nameAr, entry);
      if (nameFr) delegationMap.set(nameFr, entry);

      allDelegations.push(entry);
    }
  }
}

// ── Populate year dropdown ────────────────────────────────────
function populateYears(delegationId) {
  const years = csvIndex[String(delegationId)] ?? [];
  selectYear.innerHTML = "";

  if (years.length === 0) {
    selectYear.innerHTML = '<option value="">لا توجد بيانات متاحة</option>';
    selectYear.disabled = true;
    return;
  }

  for (const y of years.slice().sort((a, b) => b - a)) {
    const opt = document.createElement("option");
    opt.value = y;
    opt.textContent = y;
    selectYear.appendChild(opt);
  }

  selectYear.disabled = false;
  // Sync the auto-selected year so mawaqit-push.js sees it immediately
  window.prayerTimesApp = window.prayerTimesApp || {};
  window.prayerTimesApp.year = selectYear.value;
  if (typeof window.mawaqitPushRefresh === "function")
    window.mawaqitPushRefresh();
}

// ── Delegation input events ───────────────────────────────────
function onDelegationInput() {
  const raw = inputDelegation.value;
  const trimmed = raw.trim();

  // Already have a valid selection and value didn't change — do nothing
  if (activeEntry && trimmed === activeEntry.nameAr) return;

  // Exact match — handles paste / browser auto-fill
  const exact = delegationMap.get(trimmed);
  if (exact) {
    selectEntry(exact);
    return;
  }

  // Reset active state
  activeEntry = null;
  btnDownload.disabled = true;
  selectYear.innerHTML = '<option value="">— اختر المعتمدية أوّلاً —</option>';
  selectYear.disabled = true;
  setStatus("");
  hideCalendar();
  updateCalendarButtonState();

  showSuggestions(getSuggestions(raw), trimmed);
}

function onDelegationKeydown(e) {
  // Arrow-down on empty-but-focused input: open suggestions
  if (suggestionsEl.hidden) {
    if (e.key === "ArrowDown" && inputDelegation.value.trim()) {
      const q = inputDelegation.value.trim();
      showSuggestions(getSuggestions(q), q);
      setActiveItem(0);
    }
    return;
  }

  const items = suggestionsEl.querySelectorAll("li");

  if (e.key === "ArrowDown") {
    e.preventDefault();
    setActiveItem(Math.min(activeSuggestionIndex + 1, items.length - 1));
  } else if (e.key === "ArrowUp") {
    e.preventDefault();
    if (activeSuggestionIndex <= 0) {
      hideSuggestions();
    } else {
      setActiveItem(activeSuggestionIndex - 1);
    }
  } else if (e.key === "Enter") {
    e.preventDefault();
    if (
      activeSuggestionIndex >= 0 &&
      currentSuggestions[activeSuggestionIndex]
    ) {
      selectEntry(currentSuggestions[activeSuggestionIndex]);
    }
  } else if (e.key === "Escape") {
    e.preventDefault(); // prevent type=search from clearing the field
    hideSuggestions();
  }
}

// ── Year change ───────────────────────────────────────────────
function onYearChange() {
  btnDownload.disabled = !activeEntry || !selectYear.value;
  hideCalendar();
  calCsvCache = {};
  updateCalendarButtonState();
  // Notify mawaqit-push.js of the new year
  window.prayerTimesApp = window.prayerTimesApp || {};
  window.prayerTimesApp.year = selectYear.value;
  if (typeof window.mawaqitPushRefresh === "function")
    window.mawaqitPushRefresh();
}

// ── Download CSV ──────────────────────────────────────────────
async function onDownload() {
  if (!activeEntry) {
    setStatus("المعتمدية غير موجودة، تحقق من الاسم", "error");
    return;
  }

  const year = selectYear.value;
  if (!year) {
    setStatus("الرجاء اختيار السنة", "error");
    return;
  }

  const { delegationId, nameAr } = activeEntry;
  const months = [
    "01",
    "02",
    "03",
    "04",
    "05",
    "06",
    "07",
    "08",
    "09",
    "10",
    "11",
    "12",
  ];

  setLoading(true);
  setStatus("جارٍ التحميل…");

  try {
    const responses = await Promise.all(
      months.map((m) =>
        fetch(`csv/${delegationId}/${year}/${m}.csv`).then((r) => {
          if (!r.ok) throw new Error(`HTTP ${r.status} for ${m}.csv`);
          return r.text();
        }),
      ),
    );

    // Pack all 12 monthly CSVs into a single ZIP archive
    const zip = new JSZip();
    for (let i = 0; i < months.length; i++) {
      zip.file(`${months[i]}.csv`, responses[i]);
    }
    const blob = await zip.generateAsync({ type: "blob" });
    triggerDownload(blob, `مواقيت-الصلاة-${nameAr}-${year}.zip`);

    setStatus("تمّ التحميل بنجاح ✓", "success");
  } catch (err) {
    console.error("Download error:", err);
    setStatus("حدث خطأ أثناء التحميل، حاول مجدداً", "error");
  } finally {
    setLoading(false);
    btnDownload.disabled = false;
  }
}

// ── Calendar ──────────────────────────────────────────────────
function updateCalendarButtonState() {
  const ready = !!activeEntry && !!selectYear.value;
  btnShowCalendar.disabled = !ready;
  if (!ready && calendarVisible) hideCalendar();
}

function hideCalendar() {
  calendarSection.hidden = true;
  calendarVisible = false;
  btnShowCalendar.querySelector(".btn-text").textContent =
    "📅 عرض جدول المواقيت";
}

async function showCalendar() {
  if (!activeEntry || !selectYear.value) return;
  const now = new Date();
  const selYear = parseInt(selectYear.value, 10);
  // Default to current month if same year, otherwise January
  calCurrentMonth = selYear === now.getFullYear() ? now.getMonth() : 0;
  calendarVisible = true;
  calendarSection.hidden = false;
  btnShowCalendar.querySelector(".btn-text").textContent =
    "📅 إخفاء جدول المواقيت";
  await renderCalendarMonth();
  calendarSection.scrollIntoView({ behavior: "smooth", block: "start" });
}

function toggleCalendar() {
  if (calendarVisible) {
    hideCalendar();
  } else {
    showCalendar();
  }
}

async function fetchMonthCsv(delegationId, year, month) {
  const key = `${delegationId}-${year}-${month}`;
  if (calCsvCache[key] !== undefined) return calCsvCache[key];
  const mm = String(month + 1).padStart(2, "0");
  try {
    const r = await fetch(`csv/${delegationId}/${year}/${mm}.csv`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const text = await r.text();
    calCsvCache[key] = text;
    return text;
  } catch {
    calCsvCache[key] = null;
    return null;
  }
}

function parseCalendarCsv(text) {
  if (!text) return [];
  const lines = text.trim().replace(/\r/g, "").split("\n");
  const result = [];
  // Skip header line
  for (let i = 1; i < lines.length; i++) {
    const parts = lines[i].split(",");
    if (parts.length < 7) continue;
    result.push({
      day: (parts[0] || "").trim(),
      fajr: (parts[1] || "").trim(),
      shuruk: (parts[2] || "").trim(),
      duhr: (parts[3] || "").trim(),
      asr: (parts[4] || "").trim(),
      maghrib: (parts[5] || "").trim(),
      isha: (parts[6] || "").trim(),
    });
  }
  return result;
}

async function renderCalendarMonth() {
  if (!activeEntry || !selectYear.value) return;
  const year = parseInt(selectYear.value, 10);
  const { delegationId } = activeEntry;

  calMonthTitle.textContent = `${ARABIC_MONTHS[calCurrentMonth]} ${year}`;
  calPrev.disabled = calCurrentMonth <= 0;
  calNext.disabled = calCurrentMonth >= 11;

  calTbody.innerHTML =
    '<tr><td colspan="7" style="padding:24px;color:var(--ink-muted)">جارٍ التحميل…</td></tr>';

  let rows = [];
  try {
    const csv = await fetchMonthCsv(delegationId, year, calCurrentMonth);
    console.log(
      "[CAL] csv type:",
      typeof csv,
      "csv is null:",
      csv === null,
      "csv length:",
      csv ? csv.length : "N/A",
    );
    console.log(
      "[CAL] csv first 120 chars:",
      csv ? JSON.stringify(csv.slice(0, 120)) : "null",
    );
    rows = parseCalendarCsv(csv);
    console.log(
      "[CAL] parseCalendarCsv returned type:",
      typeof rows,
      "isArray:",
      Array.isArray(rows),
      "length:",
      rows ? rows.length : "N/A",
    );
    console.log("[CAL] rows value:", rows);
  } catch (err) {
    console.error("[CAL] Calendar render error:", err);
  }
  console.log(
    "[CAL] final rows type:",
    typeof rows,
    "isArray:",
    Array.isArray(rows),
    "iterable:",
    rows != null && typeof rows[Symbol.iterator] === "function",
  );

  if (!Array.isArray(rows) || rows.length === 0) {
    calTbody.innerHTML =
      '<tr><td colspan="7" style="padding:24px;color:#b33a2e">لا توجد بيانات لهذا الشهر</td></tr>';
    return;
  }

  const now = new Date();
  const todayDay = now.getDate();
  const todayMonth = now.getMonth();
  const todayYear = now.getFullYear();

  calTbody.innerHTML = "";
  for (const row of rows) {
    const tr = document.createElement("tr");
    const dayNum = parseInt(row.day, 10);
    if (
      year === todayYear &&
      calCurrentMonth === todayMonth &&
      dayNum === todayDay
    ) {
      tr.classList.add("cal-today");
    }
    tr.innerHTML = `<td>${row.day}</td><td>${row.fajr}</td><td>${row.shuruk}</td><td>${row.duhr}</td><td>${row.asr}</td><td>${row.maghrib}</td><td>${row.isha}</td>`;
    calTbody.appendChild(tr);
  }
}

// ── Boot ──────────────────────────────────────────────────────
async function init() {
  setStatus("جارٍ تحميل البيانات…");
  inputDelegation.disabled = true;
  selectYear.disabled = true;
  btnDownload.disabled = true;

  try {
    const [gouvData, indexData] = await Promise.all([
      fetch("gouvernorats.json").then((r) => r.json()),
      fetch("csv/index.json")
        .then((r) => r.json())
        .catch(() => ({})),
    ]);

    csvIndex = indexData;
    buildDelegationList(gouvData.gouvernorats ?? gouvData);
    setStatus("");
  } catch (err) {
    console.error("Init error:", err);
    setStatus("تعذّر تحميل البيانات، يرجى تحديث الصفحة", "error");
    return;
  }

  inputDelegation.disabled = false;

  // ── Events ─────────────────────────────────────────────────
  inputDelegation.addEventListener("input", onDelegationInput);
  inputDelegation.addEventListener("keydown", onDelegationKeydown);

  // Close dropdown when clicking outside the autocomplete widget
  document.addEventListener("click", (e) => {
    const wrap = inputDelegation.closest(".autocomplete-wrap");
    if (wrap && !wrap.contains(e.target)) hideSuggestions();
  });

  selectYear.addEventListener("change", onYearChange);
  btnDownload.addEventListener("click", onDownload);

  // Calendar events
  btnShowCalendar.addEventListener("click", toggleCalendar);
  btnHideCalendar.addEventListener("click", hideCalendar);
  calPrev.addEventListener("click", async () => {
    if (calCurrentMonth > 0) {
      calCurrentMonth--;
      await renderCalendarMonth();
    }
  });
  calNext.addEventListener("click", async () => {
    if (calCurrentMonth < 11) {
      calCurrentMonth++;
      await renderCalendarMonth();
    }
  });
}

document.addEventListener("DOMContentLoaded", init);
