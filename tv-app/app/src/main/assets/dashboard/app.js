/*
 * Mosque TV dashboard: the core. Views (views/*.js) register themselves with Dashboard.registerView
 * and get a context to read the TV's state and submit changes. Every change is previewed on the TV
 * (what would change, or the mistakes) and applied only when the admin confirms.
 * Plain browser JavaScript, no build step and no external files: it works on a hotspot without internet.
 */
var Dashboard = (function () {
  "use strict";

  var token = new URLSearchParams(location.search).get("t") || "";
  var views = [];
  var state = null;
  var settings = {};
  var placesCache = null;
  var clockOffset = 0;
  // The TV's clock against the phone's, from the last state: { difference: TV minus phone, margin }, in ms, or null.
  var clockCheck = null;
  var current = null;
  var sessionClosed = false;
  // Whether the TV answered the last request: true, false, or "closed" once it ended the session.
  var link = null;
  // What the section shown wants done every second with the TV's time (a countdown); reset on each render.
  var tickers = [];
  // When the TV last answered, and when it ends the session at the latest (the phone's clock, ms): once
  // the TV no longer answers, this tells an ended session from a Wi-Fi problem.
  var lastAnswer = 0;
  var sessionEndsAt = 0;
  // The TV ends a session 15 minutes after its last request, and warns 10 minutes before its 2-hour cap.
  var SESSION_IDLE = 15 * 60 * 1000;
  var SESSION_WARNING = 10 * 60 * 1000;

  /** The prayers of the settings file, in screen order: key in the file, id in the state, Arabic name. */
  var PRAYERS = [
    { key: "fajr", id: "FAJR", name: "الفجر" },
    { key: "dhuhr", id: "DHUHR", name: "الظهر" },
    { key: "asr", id: "ASR", name: "العصر" },
    { key: "maghrib", id: "MAGHRIB", name: "المغرب" },
    { key: "isha", id: "ISHA", name: "العشاء" },
    { key: "jumua", id: "JOMOAA", name: "الجمعة" },
    { key: "eidFitr", id: "AID_FITR", name: "عيد الفطر", eid: true },
    { key: "eidAdha", id: "AID_ADHA", name: "عيد الأضحى", eid: true }
  ];

  // ---------------------------------------------------------------- HTTP

  function url(path) {
    return path + (path.indexOf("?") < 0 ? "?" : "&") + "t=" + encodeURIComponent(token);
  }

  // A 503 means all the TV's connections stayed busy (another phone loading images): nothing was done.
  var BUSY_RETRIES = 3;

  function request(method, path, body, contentType, attempt) {
    if (sessionClosed) return Promise.reject(new Error("closed"));
    attempt = attempt || 0;
    var options = { method: method, cache: "no-store" };
    if (body !== undefined) {
      options.body = body;
      options.headers = { "Content-Type": contentType || "text/plain; charset=utf-8" };
    }
    return fetch(url(path), options).then(function (response) {
      // The TV answered, whatever it said: it is reachable.
      lastAnswer = Date.now();
      if (!sessionClosed) setLink(true);
      if (response.status === 503 && attempt < BUSY_RETRIES) {
        return new Promise(function (resolve) { setTimeout(resolve, 1000 * (attempt + 1)); })
          .then(function () { return request(method, path, body, contentType, attempt + 1); });
      }
      return response.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = { error: text }; }
        if (response.status === 403) {
          sessionClosed = true;
          showClosed((data && data.error) || "انتهت الجلسة");
          throw new Error("forbidden");
        }
        if (!response.ok) throw new Error((data && data.error) || ("HTTP " + response.status));
        return data;
      });
    }, function () {
      if (sessionClosed) throw new Error("closed");
      // Past the session's limits the TV has stopped its server: scanning again helps, not the Wi-Fi.
      if (sessionEnded()) {
        sessionClosed = true;
        showClosed("انتهت الجلسة");
        throw new Error("closed");
      }
      setLink(false);
      throw new Error("تعذّر الاتصال بالشاشة: تأكّد أن الهاتف على الشبكة نفسها");
    });
  }

  /** Whether the TV has ended the session by now: idle for too long, or past its cap. */
  function sessionEnded() {
    var at = Date.now();
    return lastAnswer > 0 && (at - lastAnswer > SESSION_IDLE || (sessionEndsAt > 0 && at >= sessionEndsAt));
  }

  var api = {
    get: function (path) { return request("GET", path); },
    post: function (path, text) { return request("POST", path, text === undefined ? "" : text); },
    postBytes: function (path, blob) { return request("POST", path, blob, "application/octet-stream"); },
    /** A URL for <img src>, with the session token. */
    url: url
  };

  // ---------------------------------------------------------------- DOM helpers

  /** el("div", { class: "card", text: "x", on: { click: f }, attrs: { dir: "ltr" } }, child1, child2) */
  function el(tag, props) {
    var node = document.createElement(tag);
    props = props || {};
    Object.keys(props).forEach(function (key) {
      var value = props[key];
      if (value === undefined || value === null) return;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key === "on") Object.keys(value).forEach(function (event) { node.addEventListener(event, value[event]); });
      else if (key === "attrs") Object.keys(value).forEach(function (name) { if (value[name] !== false && value[name] !== null) node.setAttribute(name, value[name] === true ? "" : value[name]); });
      else node[key] = value;
    });
    for (var i = 2; i < arguments.length; i++) {
      var child = arguments[i];
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) child.forEach(function (c) { if (c) node.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
      else node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    }
    return node;
  }

  /**
   * A number that ticks (a clock, a countdown) in cells of one width, so the line never shifts as its
   * digits change: Readex Pro's figures are proportional and the font has no tabular feature. Always
   * left to right; screen readers read the plain text, not the cells.
   */
  function digits(text, className) {
    var node = el("span", { class: "digits" + (className ? " " + className : ""), attrs: { dir: "ltr" } });
    setDigits(node, text);
    return node;
  }

  function setDigits(node, text) {
    text = String(text === undefined || text === null ? "" : text);
    if (node.getAttribute("data-text") === text) return;
    node.setAttribute("data-text", text);
    node.textContent = "";
    if (!text) return;
    var cells = el("span", { attrs: { "aria-hidden": "true" } });
    for (var i = 0; i < text.length; i++) {
      var c = text.charAt(i);
      cells.appendChild(el("span", { class: c >= "0" && c <= "9" ? "d" : null, text: c }));
    }
    node.appendChild(el("span", { class: "sr", text: text }));
    node.appendChild(cells);
  }

  var MONTHS = ["جانفي", "فيفري", "مارس", "أفريل", "ماي", "جوان", "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"];
  var WEEKDAYS = ["الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"];

  /**
   * "الثلاثاء 29 سبتمبر 2026" from "2026-09-29", with the months as Tunisia names them, as the TV
   * writes it (a phone's browser may not know them); anything else as given.
   */
  function longDate(iso) {
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ""));
    if (!match) return iso || "—";
    var day = new Date(Date.UTC(+match[1], +match[2] - 1, +match[3]));
    if (isNaN(day.getTime()) || +match[2] < 1 || +match[2] > 12) return iso;
    return WEEKDAYS[day.getUTCDay()] + " " + (+match[3]) + " " + MONTHS[+match[2] - 1] + " " + match[1];
  }

  /** The TV's wall time in Tunisia, as a Date to read with its getUTC… methods. */
  function tvNow() { return new Date(Date.now() + clockOffset); }

  /** The header's dot: green while the TV answers, red when it does not or ended the session. */
  function setLink(next) {
    if (link === next) return;
    link = next;
    document.getElementById("link").className = "link " + (next === true ? "on" : "off");
    document.getElementById("link-text").textContent =
      next === true ? "متصل بالشاشة" : next === "closed" ? "الجلسة مغلقة" : "غير متصل";
  }

  var toastTimer = null;
  function toast(text, kind) {
    var box = document.getElementById("toast");
    box.textContent = text;
    box.className = "toast" + (kind ? " " + kind : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.textContent = ""; }, 5000);
  }

  function showClosed(message) {
    setLink("closed");
    tickers = [];
    var view = document.getElementById("view");
    view.textContent = "";
    endingSoon = false;
    paintNotice();
    view.appendChild(el("div", { class: "card level-BAD", attrs: { role: "alert" } },
      el("h2", { text: message }),
      el("p", { class: "muted", text: "ابدأ جلسة جديدة من إعدادات الشاشة (الإدارة من الهاتف) وامسح الرمز من جديد." }),
      hasEdits() && draftsStored
        ? el("p", { class: "muted", text: "التعديلات التي لم تُطبَّق محفوظة في هذا الهاتف: تعرض الصفحة الجديدة استعادتها." })
        : null));
  }

  // ---------------------------------------------------------------- preview and apply

  /** Applies a previewed settings file from a section; resolves true when the TV applied it. */
  function applyText(text, view) {
    return api.post("/api/apply", text).then(function (done) {
      if (done && done.ok) {
        toast("طُبّقت الإعدادات على الشاشة", "ok");
        // What the section kept unapplied is on the TV now.
        keepDraft(view, null);
        reloadFor(view);
        return true;
      }
      toast(((done && done.lines) || []).join(" · ") || "لم تُطبَّق الإعدادات", "error");
      return false;
    }, function (error) {
      if (!sessionClosed) toast(error.message, "error");
      return false;
    });
  }

  /** Shows what the TV would change; resolves true when the admin applied it (exactly once, whatever closes it). */
  function previewAndApply(text, view) {
    return api.post("/api/preview", text).then(function (result) {
      result = result || {};
      var dialog = document.getElementById("preview");
      var canApply = !!(result.ok && result.lines && result.lines.length);
      var title = result.ok ? "ما سيتغيّر على الشاشة" : "لم تُطبَّق: صحّح ما يلي";
      var lines = result.lines && result.lines.length ? result.lines : [result.ok ? "لا تغيير: هذه هي الإعدادات الحالية" : "تعذّرت قراءة الإعدادات"];
      if (typeof dialog.showModal !== "function") {
        // An old browser without <dialog>: its own boxes instead.
        if (!canApply) {
          window.alert(title + "\n\n" + lines.join("\n"));
          return false;
        }
        return window.confirm(lines.join("\n") + "\n\nتطبيق؟") ? applyText(text, view) : false;
      }
      var list = document.getElementById("preview-lines");
      var apply = document.getElementById("preview-apply");
      var cancel = document.getElementById("preview-cancel");
      list.textContent = "";
      list.className = "lines" + (result.ok ? "" : " errors");
      lines.forEach(function (line) { list.appendChild(el("li", { text: line })); });
      document.getElementById("preview-title").textContent = title;
      apply.hidden = !canApply;
      return new Promise(function (resolve) {
        var settled = false;
        var applying = false;
        function finish(applied) {
          if (settled) return;
          settled = true;
          if (apply.onclick === onApply) apply.onclick = null;
          if (cancel.onclick === onCancel) cancel.onclick = null;
          dialog.removeEventListener("close", onClose);
          dialog.removeEventListener("cancel", onEscape);
          if (dialog.open) dialog.close();
          resolve(applied);
        }
        function onCancel() { finish(false); }
        // Escape or the back gesture closes the dialog without a button.
        function onClose() { finish(false); }
        // While the TV is applying, the dialog stays until it answers (when the browser lets it).
        function onEscape(event) { if (applying) event.preventDefault(); }
        function onApply() {
          if (applying) return;
          applying = true;
          apply.disabled = true;
          applyText(text, view).then(function (applied) {
            applying = false;
            apply.disabled = false;
            finish(applied);
          });
        }
        cancel.onclick = onCancel;
        apply.onclick = onApply;
        dialog.addEventListener("close", onClose);
        dialog.addEventListener("cancel", onEscape);
        try {
          if (!dialog.open) dialog.showModal();
        } catch (e) {
          finish(false);
        }
      });
    }, function (error) {
      if (!sessionClosed) toast(error.message, "error");
      return false;
    });
  }

  // ---------------------------------------------------------------- state

  /** Reads the TV's state without drawing the section; resolves undefined (after a toast) when it fails. */
  function loadState() {
    var sentAt = Date.now();
    return api.get("/api/state").then(function (next) {
      var receivedAt = Date.now();
      state = next;
      try { settings = JSON.parse(state.settingsFile || "{}"); } catch (e) { settings = {}; }
      // The TV's wall time in Tunisia, read as if it were UTC so the phone's own zone does not shift it.
      var tvNow = state.clock && state.clock.now ? Date.parse(state.clock.now + "Z") : NaN;
      clockOffset = isNaN(tvNow) ? 0 : tvNow - Date.now();
      // The TV read its clock while the request was on its way: half-way on average, give or take half the round trip.
      var epoch = state.clock ? state.clock.epochMillis : null;
      clockCheck = typeof epoch === "number" && isFinite(epoch) && receivedAt >= sentAt
        ? { difference: epoch - (sentAt + receivedAt) / 2, margin: (receivedAt - sentAt) / 2 }
        : null;
      var left = state.session ? state.session.remainingMillis : null;
      if (typeof left === "number" && isFinite(left)) sessionEndsAt = receivedAt + left;
      document.getElementById("mosque-name").textContent = (state.mosque && state.mosque.name) || "شاشة المسجد";
      document.getElementById("mosque-place").textContent = (state.mosque && state.mosque.delegationName) || "";
      // Which screen this is is known now: offer the edits an earlier page kept for it.
      if (!draftsChecked) {
        draftsChecked = true;
        storedDrafts = readStoredDrafts();
        paintNotice();
      }
      return state;
    }, function (error) {
      if (!sessionClosed) toast(error.message, "error");
    });
  }

  /**
   * Reads the TV's state again after something a section did; the section is drawn again only if it is
   * still the one shown (an upload may end after the admin moved on to another form).
   */
  function reloadFor(view) {
    return loadState().then(function (fresh) {
      if (fresh && view && current === view) render();
      return fresh;
    });
  }

  function places() {
    if (placesCache) return Promise.resolve(placesCache);
    return api.get("/api/places").then(function (list) { placesCache = list || []; return placesCache; });
  }

  /** A deep copy of the TV's settings file, for a form to start from. */
  function settingsCopy() { return JSON.parse(JSON.stringify(settings)); }

  var context = {
    get state() { return state; },
    /** True once the TV refused the token (the session ended): views stop showing their own errors. */
    get sessionClosed() { return sessionClosed; },
    get settings() { return settings; },
    /** How far the TV's clock is from the phone's: { difference (TV minus phone), margin } in ms, or null when unknown. */
    get clockCheck() { return clockCheck; },
    settingsCopy: settingsCopy,
    PRAYERS: PRAYERS,
    api: api,
    el: el,
    digits: digits,
    setDigits: setDigits,
    longDate: longDate,
    /** The TV's time now (read it with getUTCHours() and the like). */
    now: tvNow,
    /** Calls fn(now) every second while this section is shown (until it is drawn again). */
    onTick: function (fn) { tickers.push(fn); },
    toast: toast,
    places: places
  };

  /** The context of one section: what it reloads, applies and keeps unapplied is its own. */
  function contextFor(view) {
    var ctx = Object.create(context);
    /** Reads the TV's state again, drawing the section again if it is still shown. */
    ctx.reload = function () { return reloadFor(view); };
    /** Previews then applies a partial settings file given as an object. */
    ctx.submit = function (partial) { return previewAndApply(JSON.stringify(partial, null, 2), view); };
    /** The same, for a settings file written by hand. */
    ctx.submitText = function (text) { return previewAndApply(text, view); };
    /** A form section: the value the admin gave the field `key` (its id) and has not applied, or undefined. */
    ctx.edited = function (key) {
      var kept = drafts[view.id];
      return kept && Object.prototype.hasOwnProperty.call(kept, key) ? kept[key] : undefined;
    };
    /**
     * A form section: the field `key` now shows the TV's `value` (a list loaded after the form was
     * drawn). A kept value equal to it is no longer an edit; call before applying ctx.edited(key).
     */
    ctx.drawn = function (key, value) {
      if (current !== view || !drawnFields) return;
      drawnFields[key] = value;
      var kept = drafts[view.id];
      if (!kept || kept[key] !== value) return;
      delete kept[key];
      keepDraft(view, Object.keys(kept).length ? kept : null);
    };
    /**
     * A section with its own draft (the adhkar, the announcements): keeps it (plain data, stored in this
     * browser), or null once nothing differs from the TV. keptDraft() gives it back, also after a reload.
     */
    ctx.keepDraft = function (data) { keepDraft(view, data); };
    ctx.keptDraft = function () { return drafts[view.id] || null; };
    return ctx;
  }

  // ---------------------------------------------------------------- unsaved edits

  // What the admin changed and has not applied, per section id, so it survives another tab, the back
  // button, a redraw after an upload and, stored in this browser, a reloaded page or a new session.
  // A form section (view.form: true) has its edited fields kept here by the core, by id (else by place
  // among the section's fields); the adhkar and the announcements keep their own lists (ctx.keepDraft).
  var drafts = {};
  // The fields of the form shown, as drawn: a field put back as it was is no longer an edit.
  var drawnFields = null;
  // Edits stored by an earlier page, offered to the admin until restored or dropped.
  var storedDrafts = null;
  // Whether the last write to the browser's storage worked (a private window may refuse it).
  var draftsStored = false;
  var DRAFTS_KEY = "mosque-tv-drafts";
  var DRAFTS_MAX_AGE = 24 * 60 * 60 * 1000;
  var draftsChecked = false;

  function hasEdits() { return Object.keys(drafts).length > 0; }

  /**
   * Where this screen's edits are stored, or null before its state arrived. Per installation: two
   * mosques' TVs often have the same address, and one's edits must never be offered on the other.
   */
  function draftsKey() {
    var app = state && state.app;
    return app && app.packageName && app.installedAt ? DRAFTS_KEY + ":" + app.packageName + ":" + app.installedAt : null;
  }

  function keepDraft(view, data) {
    if (!view) return;
    if (data) drafts[view.id] = data;
    else delete drafts[view.id];
    saveDrafts();
  }

  /** Writes the edits (with those still offered from an earlier page) to the browser's storage. */
  function saveDrafts() {
    var all = {};
    Object.keys(storedDrafts || {}).forEach(function (id) { all[id] = storedDrafts[id]; });
    Object.keys(drafts).forEach(function (id) { all[id] = drafts[id]; });
    var key = draftsKey();
    draftsStored = false;
    if (!key) return;
    try {
      if (Object.keys(all).length) localStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), drafts: all }));
      else localStorage.removeItem(key);
      draftsStored = true;
    } catch (e) {
      draftsStored = false;
    }
  }

  /** The edits an earlier page for this screen stored in the last day, by section, or null. */
  function readStoredDrafts() {
    var key = draftsKey();
    if (!key) return null;
    try {
      var stored = JSON.parse(localStorage.getItem(key) || "null");
      if (!stored || typeof stored.drafts !== "object" || !stored.drafts || !(Date.now() - stored.savedAt < DRAFTS_MAX_AGE)) return null;
      var found = {};
      views.forEach(function (view) { if (stored.drafts[view.id]) found[view.id] = stored.drafts[view.id]; });
      return Object.keys(found).length ? found : null;
    } catch (e) {
      return null;
    }
  }

  function formFields(root) {
    return Array.prototype.filter.call(root.querySelectorAll("input, select, textarea"), function (node) { return node.type !== "file"; });
  }

  function fieldKey(node, index) { return node.id || "#" + index; }

  function fieldValue(node) { return node.type === "checkbox" || node.type === "radio" ? node.checked : node.value; }

  function setField(node, value) {
    if (typeof value === "boolean") node.checked = value;
    else node.value = value;
  }

  /** Notes the form as drawn from the TV's state, then puts back the fields the admin had edited. */
  function restoreForm(root, view) {
    var fields = formFields(root);
    drawnFields = {};
    fields.forEach(function (node, i) { drawnFields[fieldKey(node, i)] = fieldValue(node); });
    var kept = drafts[view.id];
    if (!kept) return;
    // In the section's order: a box ticked back first enables the field it governs.
    fields.forEach(function (node, i) {
      var key = fieldKey(node, i);
      // A field not ready yet (a list still loading) is filled by the section itself (ctx.edited).
      if (!Object.prototype.hasOwnProperty.call(kept, key) || node.disabled) return;
      setField(node, kept[key]);
      // A value the field no longer offers (an option gone) is dropped.
      if (fieldValue(node) !== kept[key]) {
        setField(node, drawnFields[key]);
        return;
      }
      // The section updates what follows the field (fields shown or hidden, hints).
      node.dispatchEvent(new Event("input", { bubbles: true }));
      node.dispatchEvent(new Event("change", { bubbles: true }));
    });
  }

  /** An edit in a form section: kept while it differs from the field as drawn. */
  function formEdited(event) {
    if (!current || !current.form || !drawnFields || sessionClosed) return;
    var fields = formFields(document.getElementById("view"));
    var index = fields.indexOf(event.target);
    if (index < 0) return;
    var key = fieldKey(event.target, index);
    var value = fieldValue(event.target);
    var kept = drafts[current.id] || {};
    if (value === drawnFields[key]) delete kept[key];
    else kept[key] = value;
    keepDraft(current, Object.keys(kept).length ? kept : null);
  }

  /**
   * Above the section: edits from an earlier page to restore or drop, and the session's end drawing
   * near (the TV ends every session after 2 hours).
   */
  var endingSoon = false;
  function paintNotice() {
    var notice = document.getElementById("notice");
    notice.textContent = "";
    if (storedDrafts && !sessionClosed) {
      var titles = views.filter(function (view) { return storedDrafts[view.id]; }).map(function (view) { return "«" + view.title + "»"; });
      notice.appendChild(el("p", { text: "في هذا الهاتف تعديلات لم تُطبَّق من صفحة سابقة (" + titles.join("، ") + "). استعادتها؟" }));
      notice.appendChild(el("div", { class: "row" },
        el("button", { type: "button", class: "primary", text: "استعادة التعديلات", on: { click: function () { restoreStored(true); } } }),
        el("button", { type: "button", class: "quiet", text: "تجاهلها", on: { click: function () { restoreStored(false); } } })));
    }
    if (endingSoon && !sessionClosed) {
      var minutes = Math.max(1, Math.ceil((sessionEndsAt - Date.now()) / 60000));
      notice.appendChild(el("p", { text: "تنتهي هذه الجلسة بعد نحو " + minutes + " د (ساعتان على الأكثر لكل جلسة): طبّق تعديلاتك قبل ذلك، ثم ابدأ جلسة جديدة من إعدادات الشاشة إن احتجت." }));
    }
    notice.hidden = !notice.firstChild;
  }

  /** The admin's answer to the offer: the stored edits become the sections' drafts, or are dropped. */
  function restoreStored(restore) {
    var stored = storedDrafts;
    storedDrafts = null;
    // What was edited on this page since it opened stays over what is restored.
    if (restore) Object.keys(stored || {}).forEach(function (id) { if (!drafts[id]) drafts[id] = stored[id]; });
    saveDrafts();
    paintNotice();
    if (restore) render();
  }

  // ---------------------------------------------------------------- views and navigation

  function registerView(view) { views.push(view); }

  /** The section asked for last: it replaces the one shown once the TV's state arrived. */
  var wanted = null;
  var navigation = 0;

  /**
   * Shows a section. A tab tapped adds a history entry (`push`), so the phone's back button returns
   * to the section before rather than leaving the page.
   */
  function show(id, push) {
    var next = views.filter(function (v) { return v.id === id; })[0] || views[0];
    wanted = next;
    if (location.hash !== "#" + next.id) {
      var address = location.pathname + location.search + "#" + next.id;
      if (push) history.pushState(null, "", address);
      else history.replaceState(null, "", address);
    }
    // Each section opens on the TV's current state (another phone, the remote or a USB key may have changed it),
    // and only once it arrived: until then the previous section stays as it is.
    var ticket = ++navigation;
    loadState().then(function (fresh) {
      if (!fresh || ticket !== navigation) return;
      current = next;
      render();
    });
  }

  function render() {
    if (!current || !state || sessionClosed) return;
    var tabs = document.getElementById("tabs");
    var root = document.getElementById("view");
    // Whether the redraw takes away the control that has focus (a tab or a field of the section).
    var before = document.activeElement;
    var hadFocus = !!before && before !== document.body && (tabs.contains(before) || root.contains(before));
    tabs.textContent = "";
    views.forEach(function (view) {
      tabs.appendChild(el("button", {
        text: view.title,
        attrs: { "aria-current": view === current ? "page" : false, type: "button" },
        on: { click: function () { show(view.id, true); } }
      }));
    });
    revealTab(tabs);
    root.textContent = "";
    tickers = [];
    drawnFields = null;
    try {
      current.render(root, contextFor(current));
      if (current.form) restoreForm(root, current);
    } catch (error) {
      root.appendChild(el("div", { class: "card level-BAD", text: "تعذّر عرض هذا القسم: " + error.message, attrs: { role: "alert" } }));
    }
    // Redrawing removed the focused control: focus the current tab, so keyboards and screen readers
    // keep their place (without scrolling the page back up). Nothing had focus (the first load): none.
    var active = document.activeElement;
    if (hadFocus && (!active || active === document.body)) {
      var tab = tabs.querySelector('[aria-current="page"]');
      if (tab) {
        try { tab.focus({ preventScroll: true }); } catch (e) { tab.focus(); }
      }
    }
  }

  /**
   * The tabs scroll sideways on a phone and are drawn again with every section: brings the current one
   * back into sight. Relative scrolling works whichever way a browser counts scrollLeft in RTL.
   */
  function revealTab(tabs) {
    var tab = tabs.querySelector('[aria-current="page"]');
    if (!tab || typeof tab.getBoundingClientRect !== "function") return;
    var box = tabs.getBoundingClientRect();
    var r = tab.getBoundingClientRect();
    if (r.left < box.left) tabs.scrollLeft -= box.left - r.left + 20;
    else if (r.right > box.right) tabs.scrollLeft += r.right - box.right + 20;
  }

  function tickClock() {
    var now = tvNow();
    var pad = function (n) { return (n < 10 ? "0" : "") + n; };
    // The TV's time is Tunisia's; shown as the TV says it, whatever the phone's own zone.
    setDigits(document.getElementById("clock"), state
      ? pad(now.getUTCHours()) + ":" + pad(now.getUTCMinutes()) + ":" + pad(now.getUTCSeconds())
      : "");
    if (sessionClosed) return;
    // Drawn again once a minute while the session's end is near.
    var soon = sessionEndsAt > 0 && sessionEndsAt - Date.now() <= SESSION_WARNING;
    if (soon !== endingSoon || (soon && now.getUTCSeconds() === 0)) {
      endingSoon = soon;
      paintNotice();
    }
    tickers.forEach(function (fn) {
      try { fn(now); } catch (e) { /* a countdown that fails stays as it was */ }
    });
  }

  function start() {
    if (!token) {
      showClosed("افتح هذه الصفحة من رمز QR الظاهر على شاشة المسجد");
      return;
    }
    // A browser without <dialog> would show its content in the page: previews use its own boxes instead.
    var dialog = document.getElementById("preview");
    if (typeof dialog.showModal !== "function") dialog.hidden = true;
    var view = document.getElementById("view");
    view.addEventListener("input", formEdited);
    view.addEventListener("change", formEdited);
    // Leaving the page (a reload, closing the tab) with edits not applied: the browser asks first.
    window.addEventListener("beforeunload", function (event) {
      if (!hasEdits()) return;
      event.preventDefault();
      event.returnValue = "";
    });
    show((location.hash || "").replace("#", ""));
    setInterval(tickClock, 1000);
    // The phone's back button moves between the sections tapped (each tap adds a history entry), and links too.
    window.addEventListener("hashchange", function () {
      var id = (location.hash || "").replace("#", "");
      if (!wanted || wanted.id !== id) show(id);
    });
    // The overview follows the TV; forms are left alone while the admin edits them.
    // Nothing shown yet means the first load failed: try again. After a failed request the TV is
    // asked again, so the header says as soon as it answers once more.
    setInterval(function () {
      if (dialog.open || sessionClosed) return;
      if (!current) { if (wanted) show(wanted.id); }
      else if (current.autoRefresh) reloadFor(current);
      else if (link === false) keepAlive();
    }, 30000);
    // Keeps the session alive while the page is open (the TV ends it after 15 minutes without requests).
    // The answer is ignored: nothing is redrawn, so the forms keep what the admin typed.
    function keepAlive() {
      if (!sessionClosed && document.visibilityState === "visible") api.get("/api/state").then(null, function () {});
    }
    var keepAliveTimer = setInterval(function () {
      if (sessionClosed) {
        clearInterval(keepAliveTimer);
        return;
      }
      keepAlive();
    }, 4 * 60 * 1000);
    // Back on the page (screen was off, another app): at once, since the timer may be up to 4 minutes away.
    document.addEventListener("visibilitychange", keepAlive);
  }

  return { registerView: registerView, start: start, PRAYERS: PRAYERS };
})();
