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
  var current = null;
  var sessionClosed = false;

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

  function request(method, path, body, contentType) {
    if (sessionClosed) return Promise.reject(new Error("closed"));
    var options = { method: method, cache: "no-store" };
    if (body !== undefined) {
      options.body = body;
      options.headers = { "Content-Type": contentType || "text/plain; charset=utf-8" };
    }
    return fetch(url(path), options).then(function (response) {
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
      throw new Error("تعذّر الاتصال بالشاشة: تأكّد أن الهاتف على الشبكة نفسها");
    });
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

  var toastTimer = null;
  function toast(text, kind) {
    var box = document.getElementById("toast");
    box.textContent = text;
    box.className = "toast" + (kind ? " " + kind : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.textContent = ""; }, 5000);
  }

  function showClosed(message) {
    var view = document.getElementById("view");
    view.textContent = "";
    view.appendChild(el("div", { class: "card level-BAD", attrs: { role: "alert" } },
      el("p", { text: message }),
      el("p", { class: "muted", text: "ابدأ جلسة جديدة من إعدادات الشاشة (الإدارة من الهاتف) وامسح الرمز من جديد." })));
  }

  // ---------------------------------------------------------------- preview and apply

  /** Applies a previewed settings file; resolves true when the TV applied it. */
  function applyText(text) {
    return api.post("/api/apply", text).then(function (done) {
      if (done && done.ok) {
        toast("طُبّقت الإعدادات على الشاشة", "ok");
        reload();
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
  function previewAndApply(text) {
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
        return window.confirm(lines.join("\n") + "\n\nتطبيق؟") ? applyText(text) : false;
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
          applyText(text).then(function (applied) {
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
    return api.get("/api/state").then(function (next) {
      state = next;
      try { settings = JSON.parse(state.settingsFile || "{}"); } catch (e) { settings = {}; }
      // The TV's wall time in Tunisia, read as if it were UTC so the phone's own zone does not shift it.
      var tvNow = state.clock && state.clock.now ? Date.parse(state.clock.now + "Z") : NaN;
      clockOffset = isNaN(tvNow) ? 0 : tvNow - Date.now();
      document.getElementById("mosque-name").textContent = (state.mosque && state.mosque.name) || "شاشة المسجد";
      document.getElementById("mosque-place").textContent = (state.mosque && state.mosque.delegationName) || "";
      return state;
    }, function (error) {
      if (!sessionClosed) toast(error.message, "error");
    });
  }

  function reload() {
    return loadState().then(function (fresh) {
      if (fresh) render();
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
    settingsCopy: settingsCopy,
    PRAYERS: PRAYERS,
    api: api,
    el: el,
    toast: toast,
    places: places,
    reload: reload,
    /** Previews then applies a partial settings file given as an object. */
    submit: function (partial) { return previewAndApply(JSON.stringify(partial, null, 2)); },
    /** The same, for a settings file written by hand. */
    submitText: previewAndApply
  };

  // ---------------------------------------------------------------- views and navigation

  function registerView(view) { views.push(view); }

  /** The section asked for last: it replaces the one shown once the TV's state arrived. */
  var wanted = null;
  var navigation = 0;

  function show(id) {
    var next = views.filter(function (v) { return v.id === id; })[0] || views[0];
    wanted = next;
    if (location.hash !== "#" + next.id) history.replaceState(null, "", location.pathname + location.search + "#" + next.id);
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
    tabs.textContent = "";
    views.forEach(function (view) {
      tabs.appendChild(el("button", {
        text: view.title,
        attrs: { "aria-current": view === current ? "page" : false, type: "button" },
        on: { click: function () { show(view.id); } }
      }));
    });
    var root = document.getElementById("view");
    root.textContent = "";
    try {
      current.render(root, context);
    } catch (error) {
      root.appendChild(el("div", { class: "card level-BAD", text: "تعذّر عرض هذا القسم: " + error.message, attrs: { role: "alert" } }));
    }
    // Redrawing removed the focused control: focus the current tab, so keyboards and screen readers
    // keep their place (without scrolling the page back up).
    var active = document.activeElement;
    if (!active || active === document.body) {
      var tab = tabs.querySelector('[aria-current="page"]');
      if (tab) {
        try { tab.focus({ preventScroll: true }); } catch (e) { tab.focus(); }
      }
    }
  }

  function tickClock() {
    var now = new Date(Date.now() + clockOffset);
    var pad = function (n) { return (n < 10 ? "0" : "") + n; };
    // The TV's time is Tunisia's; shown as the TV says it, whatever the phone's own zone.
    document.getElementById("clock").textContent = state
      ? pad(now.getUTCHours()) + ":" + pad(now.getUTCMinutes()) + ":" + pad(now.getUTCSeconds())
      : "";
  }

  function start() {
    if (!token) {
      showClosed("افتح هذه الصفحة من رمز QR الظاهر على شاشة المسجد");
      return;
    }
    // A browser without <dialog> would show its content in the page: previews use its own boxes instead.
    var dialog = document.getElementById("preview");
    if (typeof dialog.showModal !== "function") dialog.hidden = true;
    show((location.hash || "").replace("#", ""));
    setInterval(tickClock, 1000);
    // The phone's back button and links move between sections.
    window.addEventListener("hashchange", function () {
      var id = (location.hash || "").replace("#", "");
      if (!wanted || wanted.id !== id) show(id);
    });
    // The overview follows the TV; forms are left alone while the admin edits them.
    // Nothing shown yet means the first load failed: try again.
    setInterval(function () {
      if (dialog.open || sessionClosed) return;
      if (!current) { if (wanted) show(wanted.id); }
      else if (current.autoRefresh) reload();
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
