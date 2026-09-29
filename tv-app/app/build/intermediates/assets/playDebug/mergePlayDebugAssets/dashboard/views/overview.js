/*
 * Overview: what the TV shows now and what comes next, today's times, the checks of the screen and
 * the app version. Read-only; it follows the TV (autoRefresh, and its countdown ticks every second
 * on the TV's clock) and changes nothing except starting an update.
 */
(function () {
  "use strict";

  var PHASES = {
    IDLE: "أوقات الصلاة",
    ADHAN: "الأذان",
    IQAMAH_COUNTDOWN: "انتظار الإقامة",
    KHUTBA: "الخطبة",
    SALAH: "الصلاة قائمة",
    AFTER_SALAH: "أذكار بعد الصلاة"
  };
  // What the wall shows instead of the timetable while no prayer holds it (flow.screen).
  var SCREENS = {
    NIGHT: "شاشة الليل الخافتة",
    EID: "صباح العيد",
    ANNOUNCEMENTS: "الإعلانات"
  };
  // While the screen waits for the iqamah, the countdown is to the iqamah; otherwise to the next adhan.
  var BEFORE_IQAMAH = { ADHAN: true, IQAMAH_COUNTDOWN: true, KHUTBA: true };
  var LEVELS = { GOOD: "جيد", WARNING: "تنبيه", BAD: "مشكلة", INFO: "معلومة" };
  // The clock warning is announced when it appears, not again with each redraw every 30 s.
  var clockAnnounced = false;

  /** "HH:MM" from "HH:MM", "HH:MM:SS" or "YYYY-MM-DDTHH:MM:SS"; anything else as given. */
  function shortTime(value) {
    if (!value) return "";
    var text = String(value);
    var t = text.indexOf("T");
    if (t >= 0) text = text.substring(t + 1);
    return /^\d{2}:\d{2}/.test(text) ? text.substring(0, 5) : String(value);
  }

  /** The seconds since midnight of "HH:MM", or null. */
  function secondsOf(value) {
    var match = /^(\d{1,2}):(\d{2})/.exec(shortTime(value));
    return match ? Number(match[1]) * 3600 + Number(match[2]) * 60 : null;
  }

  function pad(n) { return (n < 10 ? "0" : "") + n; }

  /** "01:24:48" from an hour on, "29:12" below it, as the TV counts down. */
  function countdown(seconds) {
    var s = Math.max(0, Math.floor(seconds));
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    return (h ? pad(h) + ":" : "") + pad(m) + ":" + pad(s % 60);
  }

  /** A time kept left to right inside the Arabic text. */
  function ltr(el, text) {
    return el("span", { class: "ltr tabular", text: text || "—" });
  }

  function prayersOf(state) {
    var today = state.today || {};
    return (Array.isArray(today.prayers) ? today.prayers : []).filter(Boolean);
  }

  function phaseOf(state) {
    var flow = state.flow || {};
    return typeof flow.phase === "string" && Object.prototype.hasOwnProperty.call(PHASES, flow.phase) ? flow.phase : "IDLE";
  }

  /** The night, Eid morning or announcements screen the idle wall shows, or null for the timetable. */
  function screenOf(state, phase) {
    var screen = (state.flow || {}).screen;
    return phase === "IDLE" && typeof screen === "string" && Object.prototype.hasOwnProperty.call(SCREENS, screen) ? screen : null;
  }

  /** The prayer the screen is busy with (its adhan, iqamah, prayer or adhkar), or null when idle. */
  function currentPrayer(state, prayers) {
    var flow = state.flow || {};
    if (phaseOf(state) === "IDLE" || !flow.prayer) return null;
    return prayers.filter(function (p) { return p.id === flow.prayer || p.name === flow.prayer; })[0] || null;
  }

  /** The first adhan still to come today, or null (after the isha adhan: tomorrow's times are not known yet). */
  function nextAdhan(prayers, nowSeconds) {
    return prayers.filter(function (p) {
      var at = secondsOf(p.adhan);
      return at !== null && at > nowSeconds;
    })[0] || null;
  }

  function nowSeconds(now) {
    return now.getUTCHours() * 3600 + now.getUTCMinutes() * 60 + now.getUTCSeconds();
  }

  /**
   * What the dark card says at `seconds` past midnight: { label, count (text or null), sub }. `sub` is
   * a list of parts shown joined by " · ", each a list of strings and { time: "HH:MM" } kept left to right.
   */
  function onScreen(state, seconds) {
    var flow = state.flow || {};
    var phase = phaseOf(state);
    var prayers = prayersOf(state);
    var current = currentPrayer(state, prayers);
    var name = flow.prayer || (current && current.name) || "";
    // The Eid prayer has no adhan and no iqamah: the TV counts down to the prayer itself, and so does the card.
    var eid = flow.eid === true;
    var screen = screenOf(state, phase);
    var shows = screen ? SCREENS[screen] : eid && phase === "IQAMAH_COUNTDOWN" ? "انتظار صلاة العيد" : PHASES[phase];
    var sub = [];
    var until = phase !== "IDLE" ? shortTime(flow.until) : "";
    sub.push(until ? ["الشاشة: " + shows + " حتى ", { time: until }] : ["الشاشة: " + shows]);

    if (BEFORE_IQAMAH[phase]) {
      // The adhan screen ends before the iqamah: its own countdown is to the iqamah of today's list.
      var target = phase === "ADHAN" ? secondsOf(current && current.iqamah) : secondsOf(flow.until);
      if (target !== null && target >= seconds) {
        var awaited = eid ? "صلاة " + (name || "العيد") : name ? "إقامة " + name : "الإقامة";
        return { label: awaited + " بعد", count: countdown(target - seconds), sub: sub };
      }
    }
    var next = nextAdhan(prayers, seconds);
    if (!next) return { label: "الأذان القادم: الفجر غدًا", count: null, sub: sub };
    if (next.iqamah) sub.unshift(["الإقامة ", { time: shortTime(next.iqamah) }]);
    return { label: "أذان " + (next.name || "") + " بعد", count: countdown(secondsOf(next.adhan) - seconds), sub: sub };
  }

  function subNodes(el, sub) {
    var nodes = [];
    sub.forEach(function (part, i) {
      if (i) nodes.push(" · ");
      part.forEach(function (piece) { nodes.push(typeof piece === "string" ? piece : ltr(el, piece.time)); });
    });
    return nodes;
  }

  function nowCard(ctx, state) {
    var el = ctx.el;
    var label = el("div", { class: "now-label" });
    var count = ctx.digits("", "now-count");
    var sub = el("p", { class: "now-sub" });
    var banner = state.today && state.today.banner;
    var card = el("section", { class: "card now", attrs: { "aria-labelledby": "now-title" } },
      el("h2", { id: "now-title", text: "على الشاشة الآن" }),
      el("div", { class: "now-main" }, label, count),
      sub,
      banner ? el("p", { class: "now-sub", text: banner }) : null);
    var shownSub = null;
    function update(now) {
      var model = onScreen(state, nowSeconds(now));
      if (label.textContent !== model.label) label.textContent = model.label;
      ctx.setDigits(count, model.count || "");
      count.hidden = !model.count;
      var key = JSON.stringify(model.sub);
      if (key !== shownSub) {
        shownSub = key;
        sub.textContent = "";
        subNodes(el, model.sub).forEach(function (node) { sub.appendChild(typeof node === "string" ? document.createTextNode(node) : node); });
      }
    }
    update(ctx.now());
    ctx.onTick(update);
    return card;
  }

  function timesCard(ctx, state) {
    var el = ctx.el;
    var today = state.today || {};
    var prayers = prayersOf(state);
    var card = el("section", { class: "card" }, el("h2", { text: "مواقيت اليوم" }));
    var dateParts = [today.date ? ctx.longDate(today.date) : null, today.hijri || null].filter(Boolean);
    if (dateParts.length) card.appendChild(el("p", { class: "muted", text: dateParts.join(" · ") }));
    if (!prayers.length) {
      card.appendChild(el("p", { class: "muted", text: "لا توجد أوقات لهذا اليوم" }));
      return card;
    }
    var rows = [];
    var body = el("tbody");
    prayers.forEach(function (prayer) {
      var mark = el("span");
      var tr = el("tr", null,
        el("td", null, prayer.name || prayer.id || "", mark),
        el("td", { class: "time" }, prayer.adhan ? ctx.digits(prayer.adhan) : "—"),
        el("td", { class: "time" }, prayer.iqamah ? ctx.digits(prayer.iqamah) : "—"));
      rows.push({ prayer: prayer, tr: tr, mark: mark });
      body.appendChild(tr);
    });
    card.appendChild(el("table", { class: "times" },
      el("thead", null, el("tr", null,
        el("th", { text: "الصلاة", attrs: { scope: "col" } }),
        el("th", { class: "time", text: "الأذان", attrs: { scope: "col" } }),
        el("th", { class: "time", text: "الإقامة", attrs: { scope: "col" } }))),
      body));
    if (today.sunrise) card.appendChild(el("p", { class: "muted" }, "الشروق ", ltr(el, today.sunrise)));

    // One row stands out: the prayer the screen is busy with, else the next adhan. Past ones are muted.
    var shown = null;
    function update(now) {
      var seconds = nowSeconds(now);
      var current = currentPrayer(state, prayers);
      var focus = current || nextAdhan(prayers, seconds);
      var key = (focus ? prayers.indexOf(focus) : -1) + "|" + (current ? 1 : 0) + "|" +
        rows.map(function (row) { var at = secondsOf(row.prayer.adhan || row.prayer.iqamah); return at !== null && at <= seconds ? 1 : 0; }).join("");
      if (key === shown) return;
      shown = key;
      rows.forEach(function (row) {
        var at = secondsOf(row.prayer.adhan || row.prayer.iqamah);
        var isFocus = row.prayer === focus;
        row.tr.className = isFocus ? "focus" : at !== null && at <= seconds ? "past" : "";
        row.mark.textContent = "";
        if (isFocus && current) row.mark.appendChild(el("span", null, " ", el("span", { class: "badge current", text: "الآن" })));
        else if (isFocus) row.mark.appendChild(el("span", { class: "sr", text: " (القادمة)" }));
      });
    }
    update(ctx.now());
    ctx.onTick(update);
    return card;
  }

  /** The clock warning; an alert (read out at once) only when `announce`, a plain card on later redraws. */
  function clockCard(el, announce) {
    return el("section", { class: "card level-BAD", attrs: { role: announce ? "alert" : false } },
      el("h2", { text: "ساعة الشاشة غير صحيحة" }),
      el("p", { class: "muted", text: "قد تكون أوقات الصلاة المعروضة خاطئة. اضبط تاريخ الشاشة وساعتها من إعدادات التلفاز." }));
  }

  function weatherCard(el, weather) {
    return el("section", { class: "card" },
      el("h2", { text: "الطقس" }),
      el("p", { text: weather.text }),
      el("p", { class: "muted small" },
        weather.updated ? "آخر تحديث " : null,
        weather.updated ? ltr(el, shortTime(weather.updated)) : null,
        weather.updated ? " · " : null,
        // Open-Meteo's data is CC BY 4.0: credited wherever the weather is shown.
        el("span", { class: "ltr", text: "Open-Meteo.com" })));
  }

  function kioskCard(el, rows) {
    var list = el("ul", { class: "health" });
    rows.forEach(function (row) {
      if (!row) return;
      var level = Object.prototype.hasOwnProperty.call(LEVELS, row.level) ? row.level : "INFO";
      list.appendChild(el("li", null,
        el("span", { class: "dot " + level, attrs: { "aria-hidden": "true" } }),
        el("div", null,
          el("span", { class: "sr", text: LEVELS[level] + ": " }),
          el("div", { text: row.text || "" }),
          row.fix ? el("div", { class: "muted small", text: row.fix }) : null,
          row.command ? el("div", null, el("code", { class: "ltr", text: row.command })) : null)));
    });
    return el("section", { class: "card" }, el("h2", { text: "حالة الشاشة" }), list);
  }

  function appCard(el, ctx, state) {
    var app = state.app || {};
    var update = state.update || {};
    var card = el("section", { class: "card" },
      el("h2", { text: "التطبيق" }),
      el("p", null, "الإصدار ", ltr(el, app.versionName)));
    if (!update.supported) return card;
    if (update.status) card.appendChild(el("p", { class: "muted", text: update.status }));
    if (update.available) {
      var button = el("button", {
        class: "primary",
        text: "تحديث إلى " + update.available,
        attrs: { type: "button" }
      });
      button.addEventListener("click", function () {
        button.disabled = true;
        ctx.api.post("/api/update").then(function (result) {
          button.disabled = false;
          var ok = result && result.ok;
          ctx.toast((result && (result.message || result.error)) || (ok ? "بدأ التحديث" : "تعذّر التحديث"), ok ? "ok" : "error");
        }, function (error) {
          button.disabled = false;
          if (error.message !== "forbidden" && error.message !== "closed") ctx.toast(error.message, "error");
        });
      });
      card.appendChild(el("div", { class: "row" }, button));
    }
    return card;
  }

  Dashboard.registerView({
    id: "overview",
    title: "نظرة عامة",
    autoRefresh: true,
    render: function (root, ctx) {
      var el = ctx.el;
      var state = ctx.state || {};
      var untrusted = !!(state.clock && state.clock.trusted === false);
      if (untrusted) root.appendChild(clockCard(el, !clockAnnounced));
      clockAnnounced = untrusted;
      root.appendChild(nowCard(ctx, state));
      root.appendChild(timesCard(ctx, state));
      var weather = state.weather;
      if (weather && weather.enabled && weather.text) root.appendChild(weatherCard(el, weather));
      if (Array.isArray(state.kiosk) && state.kiosk.length) root.appendChild(kioskCard(el, state.kiosk));
      root.appendChild(appCard(el, ctx, state));
    }
  });
})();
