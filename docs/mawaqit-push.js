/* ═══════════════════════════════════════════════════════════
   Mawaqit Push — mawaqit-push.js
   Lets the page owner update prayer times on mawaqit.net
   directly from the browser via a Cloudflare Worker proxy.
   ═══════════════════════════════════════════════════════════ */

"use strict";

// ── Config ────────────────────────────────────────────────────
// Replace this with your deployed Cloudflare Worker URL.
const WORKER_URL = "https://mawaqittn.mawaqittn.workers.dev";

const LS_COOKIES = "mawaqit_auth_cookies"; // base64(JSON) from the Worker
const LS_MOSQUE_ID = "mawaqit_mosque_id";

// ── DOM refs ──────────────────────────────────────────────────
const mosqueIdInput = () => document.getElementById("mp-mosque-id");
const cookiesInput = () => document.getElementById("mp-cookies");
const btnLogin = () => document.getElementById("mp-btn-login");
const btnLogout = () => document.getElementById("mp-btn-logout");
const btnPush = () => document.getElementById("mp-btn-push");
const pushStatus = () => document.getElementById("mp-status");
const sessionBadge = () => document.getElementById("mp-session-badge");

// ── Helpers ───────────────────────────────────────────────────
function getWorkerUrl() {
  return WORKER_URL;
}
function getMosqueId() {
  const el = mosqueIdInput();
  const stored = localStorage.getItem(LS_MOSQUE_ID);
  return (el && el.value.trim()) || stored || "";
}
function getAuthCookies() {
  return localStorage.getItem(LS_COOKIES) || "";
}

function setPushStatus(text, type = "") {
  const el = pushStatus();
  if (!el) return;
  el.textContent = text;
  el.className = "mp-status" + (type ? " mp-status--" + type : "");
}
function setPushLoading(btn, on) {
  if (!btn) return;
  btn.disabled = on;
  btn.classList.toggle("loading", on);
}

// ── Session UI ────────────────────────────────────────────────
function updateSessionUI() {
  const loggedIn = !!getAuthCookies();
  const badge = sessionBadge();
  const logoutBtn = btnLogout();

  if (badge) {
    badge.textContent = loggedIn ? "✓ متصل" : "غير متصل";
    badge.className =
      "mp-badge " + (loggedIn ? "mp-badge--ok" : "mp-badge--off");
  }
  if (logoutBtn) logoutBtn.style.display = loggedIn ? "" : "none";

  refreshPushButton();
}

function refreshPushButton() {
  const btn = btnPush();
  if (!btn) return;
  const app = window.prayerTimesApp || {};
  // Mosque ID is validated at push time so its absence doesn't keep the button
  // permanently disabled when the settings panel is collapsed.
  const ready = !!getAuthCookies() && !!app.activeEntry && !!app.year;
  btn.disabled = !ready;
}
// Expose so app.js can call it after delegation/year selection
window.mawaqitPushRefresh = refreshPushButton;

// ── Persist settings ──────────────────────────────────────────
function loadPersistedSettings() {
  const mi = mosqueIdInput();
  if (mi) {
    mi.value = localStorage.getItem(LS_MOSQUE_ID) || "";
    mi.addEventListener("change", () => {
      localStorage.setItem(LS_MOSQUE_ID, mi.value.trim());
      refreshPushButton();
    });
  }
}

// ── Save session cookies (manual login flow) ─────────────────────────────────
// mawaqit.net uses CAPTCHA + email 2FA, so automated login is not possible.
// The user logs in manually in their browser and pastes the resulting session
// cookies here.  We encode them as base64(JSON) for the Worker's X-Auth-Cookies.
function handleSaveCookies() {
  const raw = cookiesInput()?.value.trim() || "";

  if (!raw) {
    setPushStatus("الصق قيمة الـ Cookie أولاً", "error");
    return;
  }

  // Accept either:
  //   • a bare PHPSESSID value (no "=" present)
  //   • a full cookie string: "Name1=val1; Name2=val2; …"
  //   • a raw cookie header value copied from DevTools
  let cookieObj = {};

  if (!raw.includes("=")) {
    // Treat the whole string as the PHPSESSID value
    cookieObj = { PHPSESSID: raw };
  } else {
    for (const pair of raw.split(/[;\n]+/)) {
      const eq = pair.indexOf("=");
      if (eq === -1) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (name) cookieObj[name] = value;
    }
  }

  if (!Object.keys(cookieObj).length) {
    setPushStatus("لم يُتعرَّف على أي cookie في النص المُدخَل", "error");
    return;
  }

  localStorage.setItem(LS_COOKIES, btoa(JSON.stringify(cookieObj)));
  if (cookiesInput()) cookiesInput().value = "";
  setPushStatus("تم حفظ الجلسة بنجاح ✓", "success");
  updateSessionUI();
}

// ── Logout ────────────────────────────────────────────────────
function handleLogout() {
  localStorage.removeItem(LS_COOKIES);
  updateSessionUI();
  setPushStatus("تم تسجيل الخروج", "");
}

// ── CSV helpers ───────────────────────────────────────────────
/** Parse one monthly CSV text → { day: {fajr,shuruk,duhr,asr,maghrib,isha} } */
function parseCsv(text) {
  const days = {};
  const lines = text.trim().split("\n");
  for (let i = 1; i < lines.length; i++) {
    // skip header
    const cols = lines[i].trim().split(",");
    if (cols.length < 7) continue;
    const day = parseInt(cols[0], 10);
    if (!day) continue;
    days[day] = {
      fajr: cols[1].trim(),
      shuruk: cols[2].trim(),
      duhr: cols[3].trim(),
      asr: cols[4].trim(),
      maghrib: cols[5].trim(),
      isha: cols[6].trim(),
    };
  }
  return days;
}

/** Fetch all 12 monthly CSVs → { 1:{dayMap}, …, 12:{dayMap} } */
async function loadAllCsvs(delegationId, year) {
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
  const texts = await Promise.all(
    months.map((m) =>
      fetch(`csv/${delegationId}/${year}/${m}.csv`).then((r) => {
        if (!r.ok) throw new Error(`شهر ${parseInt(m, 10)}: HTTP ${r.status}`);
        return r.text();
      }),
    ),
  );
  const cal = {};
  for (let i = 0; i < 12; i++) cal[i + 1] = parseCsv(texts[i]); // 1-indexed
  return cal;
}

// ── Build full form body ──────────────────────────────────────
/**
 * Parse the configure-page HTML from the Worker,
 * serialise every form field (preserving iqama offsets & mosque settings),
 * then overwrite calendar fields with the new prayer times.
 *
 * calendar shape: { month(1-12): { day(1-31): {fajr,shuruk,duhr,asr,maghrib,isha} } }
 */
function buildFormBody(html, calendar) {
  const doc = new DOMParser().parseFromString(html, "text/html");

  // Symfony CSRF token lives in: <input name="configuration[_token]" …>
  const tokenEl = doc.querySelector('input[name="configuration[_token]"]');
  if (!tokenEl) {
    throw new Error(
      "لم يُعثر على configuration[_token] في صفحة الإعداد — " +
        "تحقق من رقم المسجد وتأكد من أن حسابك يملك صلاحية تعديله",
    );
  }

  const form = tokenEl.closest("form");
  if (!form) throw new Error("لم يُعثر على النموذج داخل صفحة الإعداد");

  const params = new URLSearchParams();

  for (const el of form.elements) {
    const name = el.getAttribute("name");
    if (!name) continue;

    if (el.tagName === "SELECT") {
      params.append(name, el.value);
    } else if (el.type === "checkbox") {
      if (el.checked) params.append(name, el.value || "on");
    } else if (el.type === "radio") {
      if (el.checked) params.set(name, el.value);
    } else {
      // text, hidden, textarea …
      params.append(name, el.value ?? "");
    }
  }

  // Override prayer-time calendar fields.
  // Form key: configuration[calendar][monthIndex0][day][prayerIndex1to6]
  const PRAYER_KEYS = ["fajr", "shuruk", "duhr", "asr", "maghrib", "isha"];
  for (const [mStr, days] of Object.entries(calendar)) {
    const m0 = parseInt(mStr, 10) - 1; // 0-indexed month
    for (const [dStr, prayers] of Object.entries(days)) {
      const day = parseInt(dStr, 10);
      for (let p = 0; p < 6; p++) {
        params.set(
          `configuration[calendar][${m0}][${day}][${p + 1}]`,
          prayers[PRAYER_KEYS[p]],
        );
      }
    }
  }

  return params;
}

// ── Session refresh ────────────────────────────────────────────────────────────
/**
 * Uses the stored refreshToken to obtain a fresh accessToken from mawaqit.net
 * without any user interaction.  Updates localStorage and returns true;
 * returns false when the refresh token is itself expired or missing.
 */
async function tryRefreshSession() {
  const cookies = getAuthCookies();
  if (!cookies) return false;
  try {
    const resp = await fetch(`${WORKER_URL}/api/refresh`, {
      method: "POST",
      headers: { "X-Auth-Cookies": cookies },
    });
    if (!resp.ok) return false;
    const data = await resp.json().catch(() => ({}));
    if (!data.ok || !data.cookies) return false;
    localStorage.setItem(LS_COOKIES, data.cookies);
    return true;
  } catch {
    return false;
  }
}

// ── Main push flow ────────────────────────────────────────────
async function handlePush() {
  const app = window.prayerTimesApp || {};
  const entry = app.activeEntry;
  const year = app.year;
  const mosqueId = getMosqueId();
  const wUrl = getWorkerUrl();

  if (!entry || !year) {
    setPushStatus("اختر المعتمدية والسنة أولاً", "error");
    return;
  }
  if (!mosqueId) {
    setPushStatus("أدخل رقم المسجد (Mosque ID)", "error");
    return;
  }
  if (!getAuthCookies()) {
    setPushStatus("سجّل الدخول إلى mawaqit.net أولاً", "error");
    return;
  }

  const push = btnPush();
  setPushLoading(push, true);

  try {
    // ── 1. Load CSVs ──────────────────────────────────────────
    setPushStatus(`جارٍ تحميل بيانات ${entry.nameAr || ""}…`);
    const calendar = await loadAllCsvs(entry.delegationId, year);

    // ── 2–4. Fetch configure page, build body, submit ──────────────────
    // On 401, auto-refresh the session once using the stored refreshToken,
    // then retry (GET fresh CSRF token + re-POST).
    let retried = false;
    // eslint-disable-next-line no-constant-condition
    while (true) {
      // Re-read cookies each iteration — updated by tryRefreshSession.
      const authCookies = getAuthCookies();

      // ── 2. Fetch configure page (CSRF + existing settings) ──
      setPushStatus(
        "جارٍ جلب إعدادات المسجد من mawaqit.net…" +
        (retried ? " (إعادة المحاولة…)" : ""),
      );
      const getResp = await fetch(
        `${wUrl}/api/configure?mosqueId=${encodeURIComponent(mosqueId)}`,
        { headers: { "X-Auth-Cookies": authCookies } },
      );

      if (getResp.status === 401) {
        if (!retried) {
          setPushStatus("انتهت صلاحية الجلسة، جارٍ تجديدها تلقائياً…");
          const ok = await tryRefreshSession();
          if (ok) { retried = true; continue; }
        }
        localStorage.removeItem(LS_COOKIES);
        updateSessionUI();
        setPushStatus("انتهت صلاحية الجلسة — أعد لصق الـ Cookie وحاول مجدداً", "error");
        return;
      }
      if (!getResp.ok) {
        const e = await getResp.json().catch(() => ({}));
        setPushStatus(e.error || `خطأ HTTP ${getResp.status} عند جلب الصفحة`, "error");
        return;
      }

      const configHtml = await getResp.text();

      // ── 3. Build form body ───────────────────────────────────────
      setPushStatus("جارٍ تجهيز البيانات…");
      const formBody = buildFormBody(configHtml, calendar);

      // ── 4. Submit ───────────────────────────────────────────────
      setPushStatus("جارٍ رفع مواقيت الصلاة إلى mawaqit.net…");
      const postResp = await fetch(
        `${wUrl}/api/configure?mosqueId=${encodeURIComponent(mosqueId)}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Auth-Cookies": authCookies,
          },
          body: formBody.toString(),
        },
      );

      const result = await postResp.json().catch(() => ({}));

      if (postResp.status === 401) {
        if (!retried) {
          setPushStatus("انتهت صلاحية الجلسة أثناء الرفع، جارٍ تجديدها…");
          const ok = await tryRefreshSession();
          if (ok) { retried = true; continue; } // re-GET (fresh CSRF) then re-POST
        }
        localStorage.removeItem(LS_COOKIES);
        updateSessionUI();
        setPushStatus(
          "انتهت صلاحية الجلسة — أعد لصق الـ Cookie وحاول مجدداً",
          "error",
        );
        return;
      }
      if (!postResp.ok || !result.ok) {
        setPushStatus(result.error || `خطأ HTTP ${postResp.status}`, "error");
        return;
      }

      setPushStatus(
        `✓ تم تحديث مواقيت الصلاة على mawaqit.net` +
          ` (${entry.nameAr || entry.delegationId} — ${year})`,
        "success",
      );
      break; // done
    }
  } catch (err) {
    console.error("[mawaqit-push]", err);
    setPushStatus("خطأ غير متوقع: " + err.message, "error");
  } finally {
    setPushLoading(push, false);
  }
}

// ── Toggle settings panel ─────────────────────────────────────
function toggleSettings() {
  const panel = document.getElementById("mp-settings-panel");
  if (!panel) return;
  const hidden = panel.hasAttribute("hidden");
  if (hidden) {
    panel.removeAttribute("hidden");
  } else {
    panel.setAttribute("hidden", "");
  }
}

// ── Boot ──────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {
  loadPersistedSettings();
  updateSessionUI();

  btnLogin()?.addEventListener("click", handleSaveCookies);
  btnLogout()?.addEventListener("click", handleLogout);
  btnPush()?.addEventListener("click", handlePush);

  document
    .getElementById("mp-btn-settings")
    ?.addEventListener("click", toggleSettings);

  // Re-check button state when year selector changes (main app fires change)
  document
    .getElementById("year-select")
    ?.addEventListener("change", refreshPushButton);
});
