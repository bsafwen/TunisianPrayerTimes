/*
 * Overview: what the TV shows now and what comes next, today's times, the TV's clock against the
 * phone's, the checks of the screen and the app version. It follows the TV (autoRefresh, and its
 * countdown ticks every second on the TV's clock) and changes nothing except the TV's clock (set to
 * the phone's time, or confirmed) and starting an update.
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
  // A clock problem is announced when it appears, not again with each redraw every 30 s.
  var clockAnnounced = null;
  // Further apart than this, the TV's clock and the phone's disagree.
  var CLOCK_TOLERANCE = 60 * 1000;
  // How the TV's time was confirmed (clock.source).
  var SOURCES = {
    NETWORK: "مصدر الوقت: الإنترنت",
    ADMIN: "مصدر الوقت: تأكيد المشرف على الشاشة",
    PHONE: "مصدر الوقت: هاتف المشرف",
    ZONE: "مصدر الوقت: ساعة الجهاز، ومنطقته الزمنية على توقيت تونس"
  };

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

  /** The clock warning of a TV that says only whether its clock is plausible; an alert only when `announce`. */
  function plainClockCard(el, announce) {
    return el("section", { class: "card level-BAD", attrs: { role: announce ? "alert" : false } },
      el("h2", { text: "ساعة الشاشة غير صحيحة" }),
      el("p", { class: "muted", text: "قد تكون أوقات الصلاة المعروضة خاطئة. اضبط تاريخ الشاشة وساعتها من إعدادات التلفاز." }));
  }

  /**
   * The TV's clock against the phone's (ctx.clockCheck): "same" within a minute, "ahead" or "behind" by
   * more, or null when the request's round trip leaves it unsure (or nothing is known).
   */
  function comparison(check) {
    if (!check) return null;
    var off = Math.abs(check.difference);
    if (off + check.margin <= CLOCK_TOLERANCE) return "same";
    if (off - check.margin > CLOCK_TOLERANCE) return check.difference > 0 ? "ahead" : "behind";
    return null;
  }

  /** What is wrong with the TV's clock, to announce once: "BAD", "DIFFERS", "UNVERIFIED", or null. */
  function clockProblem(clock, check) {
    if (clock.trusted === false) return "BAD";
    var compared = comparison(check);
    if (compared === "ahead" || compared === "behind") return "DIFFERS";
    return clock.verified === true ? null : "UNVERIFIED";
  }

  /** "دقيقة", "دقيقتين", "5 دقائق", "11 دقيقة": the noun agrees with the count. */
  function counted(n, one, two, few, many) {
    if (n === 1) return one;
    if (n === 2) return two;
    var rest = n % 100;
    return n + " " + (rest >= 3 && rest <= 10 ? few : many);
  }

  /** "بدقيقتين", "بـ 5 دقائق", "بساعة و10 دقائق", "بأكثر من يوم". */
  function by(ms) {
    var minutes = Math.max(1, Math.round(Math.abs(ms) / 60000));
    if (minutes >= 24 * 60) return "بأكثر من يوم";
    var hours = Math.floor(minutes / 60);
    var parts = [];
    if (hours) parts.push(counted(hours, "ساعة", "ساعتين", "ساعات", "ساعة"));
    if (minutes % 60) parts.push(counted(minutes % 60, "دقيقة", "دقيقتين", "دقائق", "دقيقة"));
    var text = parts.join(" و");
    return /^\d/.test(text) ? "بـ " + text : "ب" + text;
  }

  function hm(date) { return pad(date.getUTCHours()) + ":" + pad(date.getUTCMinutes()); }

  /** A button that sends `body()` (made when pressed) to /api/clock, says the TV's answer, then reads the TV again. */
  function clockButton(ctx, text, body) {
    var button = ctx.el("button", { class: "primary", text: text, attrs: { type: "button" } });
    button.addEventListener("click", function () {
      button.disabled = true;
      ctx.api.post("/api/clock", JSON.stringify(body())).then(function (result) {
        var ok = !!(result && result.ok);
        ctx.toast((result && (result.message || result.error)) || (ok ? "تمّ" : "تعذّر ذلك"), ok ? "ok" : "error");
        return ctx.reload();
      }, function (error) {
        button.disabled = false;
        if (error.message !== "forbidden" && error.message !== "closed") ctx.toast(error.message, "error");
      });
    });
    return button;
  }

  /**
   * The TV's clock: whether its time is confirmed and how, the TV's time beside the phone's, and the fix
   * that fits: the phone's time when they disagree, a confirmation when an unconfirmed time agrees. An
   * alert (read out at once) only when `announce`, a plain card on later redraws.
   */
  function clockCard(ctx, clock, check, announce) {
    var el = ctx.el;
    var compared = comparison(check);
    var implausible = clock.trusted === false;
    var verified = !implausible && clock.verified === true;
    var differs = compared === "ahead" || compared === "behind";
    var level = implausible ? " level-BAD" : differs || !verified ? " level-WARNING" : "";
    var card = el("section", { class: "card" + level, attrs: { role: announce ? "alert" : false } },
      el("h2", { text: implausible ? "ساعة الشاشة غير صحيحة" : verified ? "ساعة الشاشة مؤكَّدة" : "ساعة الشاشة غير مؤكَّدة" }),
      el("p", { class: "muted", text: implausible ? "لا تعرض الشاشة أوقات الصلاة حتى يُضبط وقتها."
        : verified ? (SOURCES[clock.source] || "")
        : "لم يُؤكَّد وقتها بعد: قارِنه بساعة هاتفك." }));
    if (check) {
      var tv = ctx.digits("");
      var phone = ctx.digits("");
      card.appendChild(el("p", null, "على الشاشة ", tv, " · في هاتفك ", phone));
      // Both in Tunisia's time: the phone's is the TV's less their difference, whatever the phone's own zone.
      var update = function (now) {
        ctx.setDigits(tv, hm(now));
        ctx.setDigits(phone, hm(new Date(now.getTime() - check.difference)));
      };
      update(ctx.now());
      ctx.onTick(update);
      card.appendChild(el("p", { text: compared === "same" ? "تطابق ساعة هاتفك."
        : compared === "ahead" ? "متقدّمة على ساعة هاتفك " + by(check.difference) + "."
        : compared === "behind" ? "متأخّرة عن ساعة هاتفك " + by(check.difference) + "."
        : "تعذّرت المقارنة بساعة هاتفك: الاتصال بطيء، وتُعاد بعد قليل." }));
    }
    if (clock.zoneDiffers === true && typeof clock.deviceZone === "string" && clock.deviceZone) {
      card.appendChild(el("p", { class: "muted small" }, "منطقة الجهاز الزمنية ", el("span", { class: "ltr", text: clock.deviceZone }),
        verified ? "، ولا أثر لها: الأوقات بتوقيت تونس." : " ليست توقيت تونس، فقد تكون ساعته ضُبطت على توقيت آخر."));
    }
    if (differs) {
      // The network is a good clock too: the phone's may be the wrong one.
      if (verified && clock.source === "NETWORK") {
        card.appendChild(el("p", { class: "muted small", text: "أكّد الإنترنت وقت الشاشة: تحقّق من ساعة هاتفك قبل أن تضبطها عليها." }));
      }
      card.appendChild(el("div", { class: "row" },
        clockButton(ctx, "اضبط الشاشة على وقت هاتفي", function () { return { epochMillis: Date.now() }; })));
    } else if (compared === "same" && !verified) {
      card.appendChild(el("div", { class: "row" }, clockButton(ctx, "الوقت صحيح", function () { return { confirm: true }; })));
    }
    return card;
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
      var clock = state.clock || {};
      // A TV that sends its instant is compared with the phone; an older one only says whether its clock is plausible.
      var known = typeof clock.epochMillis === "number";
      var problem = known ? clockProblem(clock, ctx.clockCheck) : clock.trusted === false ? "BAD" : null;
      var announce = !!problem && problem !== clockAnnounced;
      clockAnnounced = problem;
      // A problem comes first; a confirmed clock that agrees with the phone waits under today's times.
      if (problem) root.appendChild(known ? clockCard(ctx, clock, ctx.clockCheck, announce) : plainClockCard(el, announce));
      root.appendChild(nowCard(ctx, state));
      root.appendChild(timesCard(ctx, state));
      if (known && !problem) root.appendChild(clockCard(ctx, clock, ctx.clockCheck, false));
      var weather = state.weather;
      if (weather && weather.enabled && weather.text) root.appendChild(weatherCard(el, weather));
      if (Array.isArray(state.kiosk) && state.kiosk.length) root.appendChild(kioskCard(el, state.kiosk));
      root.appendChild(appCard(el, ctx, state));
    }
  });
})();
