/*
 * Overview: what the TV shows now, today's times, the checks of the screen and the app version.
 * Read-only; it follows the TV (autoRefresh) and changes nothing except starting an update.
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

  /** "HH:MM" from "HH:MM", "HH:MM:SS" or "YYYY-MM-DDTHH:MM:SS"; anything else as given. */
  function shortTime(value) {
    if (!value) return "";
    var text = String(value);
    var t = text.indexOf("T");
    if (t >= 0) text = text.substring(t + 1);
    return /^\d{2}:\d{2}/.test(text) ? text.substring(0, 5) : String(value);
  }

  /** A time or date, kept left-to-right inside the Arabic text. */
  function ltr(el, text) {
    return el("span", { class: "ltr tabular", text: text || "—" });
  }

  function isCurrent(prayer, flow) {
    if (!flow || !flow.prayer) return false;
    return flow.prayer === prayer.id || flow.prayer === prayer.name;
  }

  function todayCard(el, state) {
    var today = state.today || {};
    var flow = state.flow || {};
    var card = el("section", { class: "card" }, el("h2", { text: "اليوم" }));
    card.appendChild(el("div", { class: "row" },
      el("span", { class: "grow" }, "التاريخ: ", ltr(el, today.date)),
      el("span", { class: "grow", text: today.hijri || "" })));
    if (today.banner) card.appendChild(el("p", { class: "badge current", text: today.banner }));
    card.appendChild(el("p", { class: "muted" }, "الشروق: ", ltr(el, today.sunrise)));

    var prayers = (Array.isArray(today.prayers) ? today.prayers : []).filter(Boolean);
    if (!prayers.length) {
      card.appendChild(el("p", { class: "muted", text: "لا توجد أوقات لهذا اليوم" }));
      return card;
    }
    var body = el("tbody");
    prayers.forEach(function (prayer) {
      var current = isCurrent(prayer, flow);
      body.appendChild(el("tr", null,
        el("td", null, prayer.name || prayer.id || "",
          current ? " " : null,
          current ? el("span", { class: "badge current", text: "الآن" }) : null),
        el("td", { class: "tabular" }, ltr(el, prayer.adhan)),
        el("td", { class: "tabular" }, ltr(el, prayer.iqamah))));
    });
    card.appendChild(el("table", null,
      el("thead", null, el("tr", null,
        el("th", { text: "الصلاة" }),
        el("th", { text: "الأذان" }),
        el("th", { text: "الإقامة" }))),
      body));
    return card;
  }

  function flowCard(el, state) {
    var flow = state.flow || {};
    var known = typeof flow.phase === "string" && Object.prototype.hasOwnProperty.call(PHASES, flow.phase);
    var phase = known ? PHASES[flow.phase] : (typeof flow.phase === "string" && flow.phase) || PHASES.IDLE;
    var card = el("section", { class: "card" },
      el("h2", { text: "ما تعرضه الشاشة الآن" }),
      el("p", null, el("strong", { text: phase })));
    if (flow.until) card.appendChild(el("p", { class: "muted" }, "حتى الساعة ", ltr(el, shortTime(flow.until))));
    return card;
  }

  function clockCard(el) {
    return el("section", { class: "card level-BAD" },
      el("h2", { text: "ساعة الشاشة غير صحيحة" }),
      el("p", { class: "muted", text: "قد تكون أوقات الصلاة المعروضة خاطئة. اضبط تاريخ الشاشة وساعتها من إعدادات التلفاز." }));
  }

  function weatherCard(el, weather) {
    var card = el("section", { class: "card" },
      el("h2", { text: "الطقس" }),
      el("p", { text: weather.text }));
    card.appendChild(el("p", { class: "muted" },
      weather.updated ? "آخر تحديث: " : null,
      weather.updated ? ltr(el, shortTime(weather.updated)) : null,
      weather.updated ? " · " : null,
      el("span", { class: "ltr", text: "Open-Meteo" })));
    return card;
  }

  function kioskCard(el, rows) {
    var card = el("section", { class: "card" }, el("h2", { text: "حالة الشاشة" }));
    rows.forEach(function (row) {
      if (!row) return;
      var level = /^(GOOD|WARNING|BAD|INFO)$/.test(row.level) ? row.level : "INFO";
      card.appendChild(el("div", { class: "list-item level-" + level },
        el("div", { text: row.text || "" }),
        row.fix ? el("div", { class: "muted", text: row.fix }) : null,
        row.command ? el("div", null, el("code", { class: "ltr", text: row.command })) : null));
    });
    return card;
  }

  function appCard(el, ctx, state) {
    var app = state.app || {};
    var update = state.update || {};
    var card = el("section", { class: "card" },
      el("h2", { text: "التطبيق" }),
      el("p", null, "الإصدار: ", ltr(el, app.versionName)));
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
      if (state.clock && state.clock.trusted === false) root.appendChild(clockCard(el));
      root.appendChild(todayCard(el, state));
      root.appendChild(flowCard(el, state));
      var weather = state.weather;
      if (weather && weather.enabled && weather.text) root.appendChild(weatherCard(el, weather));
      if (Array.isArray(state.kiosk) && state.kiosk.length) root.appendChild(kioskCard(el, state.kiosk));
      root.appendChild(appCard(el, ctx, state));
    }
  });
})();
