/*
 * «حساب المواقيت»: how the TV computes the prayer times for the mosque's delegation (the sun's path
 * on a chosen day, the rule of each time, the Asr shadow), and the formula's values a mosque may
 * change («تخصيص الحساب», locked on INM's official values until the admin turns it on). Everything
 * is recomputed here with formula.js as the admin edits; saving sends the settings file's
 * { prayerTimes: {...} } section, or { prayerTimes: null } to return to the official times.
 */
(function () {
  "use strict";

  var F = PrayerFormula;
  var SVG_NS = "http://www.w3.org/2000/svg";
  var MONTHS = ["جانفي", "فيفري", "مارس", "أفريل", "ماي", "جوان", "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"];
  /** The six times of the formula, in its order. */
  var NAMES = ["الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"];
  /** The settings file's key of each time's adjustment, in the same order: the sunrise has none. */
  var ADJUST_KEYS = ["fajr", null, "dhuhr", "asr", "maghrib", "isha"];
  /** The page's colours (style.css) for SVG attributes, which cannot read its custom properties everywhere. */
  var C = {
    page: "#EFECE4", surface: "#FAF8F3", line: "#E0DBD0", ink: "#0E1B2A", muted: "#3C4856", blue: "#0B5E9E",
    blueSoft: "#DCE8F2", turquoise: "#08627A", turquoiseSoft: "#D7ECEE", warning: "#7D560A", warningSoft: "#F2E9D6",
    idle: "#9A9384", gold: "#D9B45C", night: "#0E1B2A", nightText: "#EAF0F1", dusk: "#3D6F86"
  };
  // What stays from one drawing to the next (after an apply, back from another tab): the day shown and the time picked.
  var shown = { year: null, doy: null, pick: 3 };

  function pad(n) { return (n < 10 ? "0" : "") + n; }

  /** "04:47" from minutes after midnight. */
  function hm(minutes) {
    var m = ((Math.round(minutes) % 1440) + 1440) % 1440;
    return pad(Math.floor(m / 60)) + ":" + pad(m % 60);
  }

  /** A number with at most `digits` decimals, with a true minus sign. */
  function num(x, digits) {
    var f = Math.pow(10, digits);
    return String(Math.round(x * f) / f).replace("-", "−");
  }

  /** "+4", "−2" or "0". */
  function signed(n) { return n > 0 ? "+" + n : n < 0 ? "−" + (-n) : "0"; }

  function isLeap(year) { return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0); }

  /** { year, month, day } of the `doy`-th day of `year` (0 = 1 January). */
  function dateOf(year, doy) {
    var d = new Date(Date.UTC(year, 0, 1 + doy));
    return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
  }

  function dayOfYear(year, month, day) {
    return Math.round((Date.UTC(year, month - 1, day) - Date.UTC(year, 0, 1)) / 86400000);
  }

  function dateText(date) { return date.day + " " + MONTHS[date.month - 1] + " " + date.year; }

  /** An SVG element: svg("circle", { cx: 1 }, child, …); text children become its text. */
  function svg(tag, attrs) {
    var node = document.createElementNS(SVG_NS, tag);
    Object.keys(attrs || {}).forEach(function (name) {
      if (attrs[name] !== null && attrs[name] !== undefined) node.setAttribute(name, String(attrs[name]));
    });
    for (var i = 2; i < arguments.length; i++) {
      var child = arguments[i];
      if (child === null || child === undefined) continue;
      node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    }
    return node;
  }

  /** A number kept left to right inside the Arabic text. */
  function ltr(el, text, className) {
    return el("span", { class: "ltr tabular" + (className ? " " + className : ""), text: text });
  }

  /** The number an input's text holds, or NaN when it is empty or not a number. */
  function typedNumber(input) {
    var text = String(input.value || "").trim();
    return text ? Number(text) : NaN;
  }

  /**
   * The value an input holds when it is a number of the range on the step's grid, else NaN. A number
   * a hair off the grid (16.5000000001) counts as its grid value, and that value is what is returned
   * and sent: the TV accepts only whole minutes and angles on the 0.5 grid.
   */
  function readValue(input, spec) {
    var n = typedNumber(input);
    if (!isFinite(n) || n < spec.range.min || n > spec.range.max) return NaN;
    var steps = n / spec.step;
    return Math.abs(steps - Math.round(steps)) < 1e-9 ? Math.round(steps) * spec.step : NaN;
  }

  // ------------------------------------------------ the sun's altitude through the day
  // 02:00 to 23:00 across, 80° to −36° down, in a 350 × 326 drawing (the approved mockup's geometry).
  var X0 = 30, X1 = 320, T0 = 2, T1 = 23, A0 = 80, A1 = -36, Y0 = 36, Y1 = 300;
  function chartX(t) { return X0 + (t - T0) * (X1 - X0) / (T1 - T0); }
  function chartY(a) { return Y0 + (A0 - a) * (Y1 - Y0) / (A0 - A1); }
  function r1(v) { return Math.round(v * 10) / 10; }
  // Where each time's two-line label sits around its dot: light on the night, dark on the day.
  var PLACES = [
    { anchor: "start", dx: 7, dy1: 16, dy2: 30, color: C.nightText },
    { anchor: "end", dx: -7, dy1: -22, dy2: -8, color: C.ink },
    { anchor: "middle", dx: 0, dy1: -24, dy2: -10, color: C.ink },
    { anchor: "start", dx: 7, dy1: -20, dy2: -6, color: C.ink },
    { anchor: "start", dx: 7, dy1: -22, dy2: -8, color: C.ink },
    { anchor: "end", dx: -7, dy1: 16, dy2: 30, color: C.nightText }
  ];

  /** Draws the chart of `model` (see compute) into the <svg> `node`, replacing what it showed. */
  function drawChart(node, model, location) {
    while (node.firstChild) node.removeChild(node.firstChild);
    var date = model.date;
    var s = model.settings;
    var day = model.day;
    function alt(t) { return F.altitudeAt(location, date.year, date.month, date.day, t); }
    var points = [];
    for (var i = 0; i <= (T1 - T0) * 6; i++) points.push(r1(chartX(T0 + i / 6)) + " " + r1(chartY(alt(T0 + i / 6))));
    var noonT = day.noon / 60;
    var noonX = r1(chartX(noonT));
    var horizonY = r1(chartY(0));
    var twilightY = r1(chartY(-18));
    var fajrY = r1(chartY(-(s.fajrAngle + day.dip)));
    var ishaY = r1(chartY(-(s.ishaAngle + day.dip)));
    var asrY = r1(chartY(day.asrAltitude));

    var plot = svg("g", { "clip-path": "url(#formula-plot)" },
      svg("rect", { x: 30, y: 0, width: 290, height: horizonY, fill: C.blueSoft }),
      svg("rect", { x: 30, y: horizonY, width: 290, height: r1(twilightY - horizonY), fill: "url(#formula-twilight)" }),
      svg("rect", { x: 30, y: twilightY, width: 290, height: r1(Y1 - twilightY + 10), fill: C.night }),
      svg("line", { x1: 30, x2: 320, y1: horizonY, y2: horizonY, stroke: C.ink, "stroke-width": 1.5 }),
      // Fajr's threshold on the morning half, Isha's and Asr's on the afternoon half, and the noon.
      svg("line", { x1: 30, x2: noonX, y1: fajrY, y2: fajrY, stroke: C.turquoiseSoft, "stroke-width": 1, "stroke-dasharray": "4 4" }),
      svg("line", { x1: noonX, x2: 320, y1: ishaY, y2: ishaY, stroke: C.turquoiseSoft, "stroke-width": 1, "stroke-dasharray": "4 4" }),
      svg("line", { x1: noonX, x2: 320, y1: asrY, y2: asrY, stroke: C.turquoise, "stroke-width": 1, "stroke-dasharray": "4 4" }),
      svg("line", { x1: noonX, x2: noonX, y1: r1(chartY(alt(noonT))), y2: horizonY, stroke: C.blue, "stroke-width": 1, "stroke-dasharray": "2 3" }),
      svg("path", { d: "M" + points.join(" L"), fill: "none", stroke: C.gold, "stroke-width": 3, "stroke-linejoin": "round", "stroke-linecap": "round" }));
    // Where the official times would be, when the values shown move them. Brown on the day and the
    // light twilight, pale on the dark twilight and the night (Fajr, Isha), where brown is 2.6:1.
    model.officialDay.times.forEach(function (m, i) {
      if (model.diffs[i] === 0) return;
      var a = alt(m / 60);
      plot.appendChild(svg("circle", { cx: r1(chartX(m / 60)), cy: r1(chartY(a)), r: 5, fill: "none",
        stroke: a < -9 ? C.warningSoft : C.warning, "stroke-width": 1.5, "stroke-dasharray": "2 2" }));
    });
    var labels = [];
    day.times.forEach(function (m, i) {
      var cx = r1(chartX(m / 60));
      var cy = r1(chartY(alt(m / 60)));
      var picked = shown.pick === i;
      var place = PLACES[i];
      plot.appendChild(svg("circle", { cx: cx, cy: cy, r: picked ? 7 : 5, fill: picked ? C.blue : C.surface,
        stroke: picked ? C.surface : C.ink, "stroke-width": 2 }));
      var lx = r1(cx + place.dx);
      labels.push(svg("text", { x: lx, y: r1(cy + place.dy1), "text-anchor": place.anchor, "font-size": 11, fill: place.color }, NAMES[i]));
      labels.push(svg("text", { x: lx, y: r1(cy + place.dy2), "text-anchor": place.anchor, "font-size": 11, "font-weight": 600, fill: place.color }, hm(m)));
    });

    node.appendChild(svg("defs", null,
      svg("linearGradient", { id: "formula-twilight", x1: 0, y1: 0, x2: 0, y2: 1 },
        svg("stop", { offset: 0, "stop-color": C.warningSoft }),
        svg("stop", { offset: 0.45, "stop-color": C.dusk }),
        svg("stop", { offset: 1, "stop-color": C.night })),
      svg("clipPath", { id: "formula-plot" }, svg("rect", { x: 30, y: 36, width: 290, height: 264, rx: 10 }))));
    node.appendChild(plot);
    labels.forEach(function (label) { node.appendChild(label); });
    var small = { "font-size": 10, fill: C.muted };
    function text(x, y, anchor, content, extra) {
      var attrs = { x: x, y: y, "text-anchor": anchor };
      Object.keys(extra || small).forEach(function (key) { attrs[key] = (extra || small)[key]; });
      node.appendChild(svg("text", attrs, content));
    }
    text(26, r1(horizonY + 3), "end", "0°");
    text(26, r1(fajrY + 3), "end", "−" + num(s.fajrAngle, 1) + "°");
    text(324, r1(ishaY + 3), "start", "−" + num(s.ishaAngle, 1) + "°");
    text(324, r1(asrY + 3), "start", num(day.asrAltitude, 1) + "°", { "font-size": 10, fill: C.turquoise });
    // Under the line, where the sunrise's label (above it) never reaches. Dark, like the line: the
    // twilight darkens fast below it (blue there is 2:1).
    text(36, r1(horizonY + 12), "start", "الأفق", { "font-size": 10, fill: C.ink });
    text(noonX, r1(horizonY + 12), "middle", "الجنوب", { "font-size": 10, fill: C.ink });
    [3, 6, 9, 12, 15, 18, 21].forEach(function (h) {
      var x = r1(chartX(h));
      node.appendChild(svg("line", { x1: x, x2: x, y1: 300, y2: 305, stroke: C.idle, "stroke-width": 1 }));
      text(x, 318, "middle", pad(h) + ":00");
    });
    text(30, 30, "start", "الشرق");
    text(320, 30, "end", "الغرب");
  }

  /**
   * Draws the Asr figure into the <svg> `node` (274 × 170): a stick 60 px tall at x 240 on the ground,
   * its noon shadow and its Asr shadow (the setting's lengths plus the noon one), and the sun's ray.
   * The longest shadow (21 December, two lengths) is 3.75 sticks: 225 px, from 240 to 15.
   */
  function drawShadow(node, model) {
    while (node.firstChild) node.removeChild(node.firstChild);
    var H = 60, BASE = 240, GROUND = 132;
    var noonShadow = model.day.noonShadow;
    var factor = model.settings.asrShadow + noonShadow;
    var asrLength = H * factor;
    var noonLength = H * noonShadow;
    var tipX = BASE - asrLength;
    var t = Math.min((260 - BASE) / asrLength, (GROUND - H - 14) / H);
    var sunX = r1(BASE + asrLength * t);
    var sunY = r1(GROUND - H - H * t);
    var far = tipX > 44;
    [
      svg("rect", { x: 0, y: 0, width: 274, height: 170, rx: 12, fill: C.blueSoft }),
      svg("path", { d: "M0 132 H274 V158 A12 12 0 0 1 262 170 H12 A12 12 0 0 1 0 158 Z", fill: C.line }),
      svg("line", { x1: r1(tipX), y1: GROUND, x2: sunX, y2: sunY, stroke: C.gold, "stroke-width": 1.5, "stroke-dasharray": "4 3" }),
      svg("circle", { cx: sunX, cy: sunY, r: 9, fill: C.gold }),
      svg("rect", { x: r1(tipX), y: 130, width: r1(asrLength), height: 6, rx: 3, fill: C.ink, opacity: 0.8 }),
      svg("rect", { x: r1(BASE - noonLength), y: 139, width: r1(Math.max(noonLength, 2)), height: 6, rx: 3, fill: C.turquoise }),
      svg("line", { x1: BASE, y1: GROUND, x2: BASE, y2: GROUND - H, stroke: C.ink, "stroke-width": 4, "stroke-linecap": "round" }),
      svg("text", { x: 248, y: GROUND - H / 2, "font-size": 11, fill: C.ink }, "1"),
      svg("text", { x: r1(BASE - noonLength / 2), y: 158, "text-anchor": "middle", "font-size": 11, fill: C.turquoise }, num(noonShadow, 2)),
      svg("text", { x: 12, y: 24, "text-anchor": "start", "font-size": 12, "font-weight": 600, fill: C.ink },
        model.settings.asrShadow + " + " + num(noonShadow, 2) + " = " + num(factor, 2)),
      svg("text", { x: r1(far ? tipX - 4 : tipX + 6), y: far ? 128 : 104, "text-anchor": far ? "end" : "start", "font-size": 11, fill: C.warning },
        num(model.day.asrAltitude, 1) + "°")
    ].forEach(function (child) { node.appendChild(child); });
    node.setAttribute("aria-label", "عود وظلّه: عند الزوال " + num(noonShadow, 2) + " من طوله، وعند العصر " + num(factor, 2) +
      "، والشمس على ارتفاع " + num(model.day.asrAltitude, 1) + "°");
  }

  /** The settings file's "prayerTimes" section of full settings: every value, each prayer's adjustment included. */
  function sectionOf(settings) {
    var adjust = {};
    F.ADJUSTABLE.forEach(function (key) { adjust[key] = settings.adjust[key]; });
    return {
      fajrAngle: settings.fajrAngle, ishaAngle: settings.ishaAngle, asrShadow: settings.asrShadow,
      dhuhrMinutes: settings.dhuhrMinutes, maghribMinutes: settings.maghribMinutes, elevation: settings.elevation,
      adjust: adjust
    };
  }

  /** A signed number kept left to right in a plain Arabic text (a toast, a hint): "+15" never reads "15+". */
  function isolated(text) { return "⁦" + text + "⁩"; }

  /** An angle for a plain Arabic text, its ° kept after the number («18°», never «°18»). */
  function degrees(x, digits) { return isolated(num(x, digits) + "°"); }

  /**
   * A value between − and + buttons around a real number input, so the page keeps it among the
   * unsaved edits and puts it back. The buttons move it one step within its range, as if typed.
   * `spec`: { id, step, range: { min, max }, official, inputMode, name }, `name` being the input's own
   * accessible name when its visible label says too little; `less` and `more` name the buttons.
   */
  function stepper(el, spec, value, less, more) {
    // Left to right, so a negative adjustment reads «−3», not «3-».
    var input = el("input", {
      type: "number", id: spec.id, value: String(value), class: "tabular ltr",
      attrs: { min: spec.range.min, max: spec.range.max, step: spec.step, inputmode: spec.inputMode || false,
        "aria-describedby": spec.id + "-hint", "aria-label": spec.name || false }
    });
    // Like a browser's own stepDown/stepUp: from the number shown (off the grid: to the grid value
    // below or above it; out of the range: back inside it), and from the official value only when
    // the field holds no number at all.
    function move(direction) {
      var n = typedNumber(input);
      var steps = (isFinite(n) ? n : spec.official) / spec.step;
      steps = direction < 0 ? Math.floor(steps - 1e-9) : Math.ceil(steps + 1e-9);
      // "|| 0": −0 (one step up from −1) is shown as 0.
      n = Math.min(spec.range.max, Math.max(spec.range.min, steps * spec.step)) || 0;
      if (String(n) === input.value) return;
      input.value = String(n);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }
    function button(direction, label) {
      return el("button", { type: "button", text: direction < 0 ? "−" : "+", attrs: { "aria-label": label }, on: { click: function () { move(direction); } } });
    }
    var down = button(-1, less);
    var up = button(1, more);
    /**
     * At an end of the range its button says it can do no more (aria-disabled, not disabled, so it
     * keeps the focus once pressed there). Called on every edit.
     */
    function sync() {
      var n = readValue(input, spec);
      down.setAttribute("aria-disabled", n === spec.range.min ? "true" : "false");
      up.setAttribute("aria-disabled", n === spec.range.max ? "true" : "false");
    }
    sync();
    return { input: input, sync: sync, node: el("div", { class: "stepper" }, down, input, up) };
  }

  function render(root, ctx) {
    var el = ctx.el;
    var formula = (ctx.state && ctx.state.formula) || {};
    var saved = ctx.settingsCopy() || {};
    // What the TV computes with now: the state's full values, else its settings file's section.
    var tv = F.normalize(formula.settings || saved.prayerTimes || null);
    var location = formula.location || null;
    // Everything that follows the values shown: called on every edit.
    var updates = [];
    function refresh() {
      var model = compute();
      updates.forEach(function (update) { update(model); });
    }

    // The day shown: the TV's today, until the admin picks another one this year.
    var now = ctx.now();
    var year = now.getUTCFullYear();
    if (shown.year !== year || shown.doy === null) {
      shown.year = year;
      shown.doy = dayOfYear(year, now.getUTCMonth() + 1, now.getUTCDate());
    }
    var yearDays = isLeap(year) ? 366 : 365;

    // ------------------------------------------------ heading
    var banner = el("div", { class: "caution", attrs: { role: "status" } });
    root.appendChild(el("div", { class: "intro" },
      el("h2", { class: "page", text: "كيف تُحسب المواقيت" }),
      el("p", { class: "muted", text: "تحسب الشاشة المواقيت بنفسها، دون إنترنت، بطريقة المعهد الوطني للرصد الجوي، لموقع مسجدك. كل وقت هو لحظة تبلغ فيها الشمس ارتفاعًا معيّنًا في السماء." }),
      banner));
    updates.push(function (model) {
      banner.hidden = model.official;
      if (model.official) return;
      var n = model.diffs ? model.diffs.filter(function (d) { return d !== 0; }).length : 0;
      banner.textContent = !model.diffs ? "قيم مخصّصة: تختلف المواقيت عن الرسمية."
        : n === 0 ? "قيم مخصّصة، ولا يختلف وقت عن الرسمي في هذا اليوم."
        : "مواقيت مخصّصة: " + (n === 1 ? "وقت واحد يختلف" : n === 2 ? "وقتان يختلفان" : n + " أوقات تختلف") + " عن الرسمية في هذا اليوم.";
    });

    // ------------------------------------------------ place
    var toMosque = el("a", { href: "#mosque", class: "go", text: "تغيير من قسم «المسجد»" });
    if (!location) {
      root.appendChild(el("section", { class: "card level-WARNING" },
        el("h2", { text: "الموقع" }),
        el("p", { text: "اختر مكان المسجد أولًا (الولاية ثم المعتمدية) في قسم «المسجد»: تُحسب المواقيت لإحداثيات معتمديته." }),
        el("a", { href: "#mosque", class: "go", text: "الذهاب إلى «المسجد»" })));
    } else {
      root.appendChild(el("section", { class: "card" },
        el("div", { class: "head" }, el("h2", { text: "الموقع" }), toMosque),
        el("p", null, "المعتمدية: ", el("strong", { text: location.delegationName || "—" }),
          " · الولاية: ", el("strong", { text: location.gouvernoratName || "—" })),
        el("div", { class: "tiles" },
          tile("العرض شمالًا", ltr(el, num(location.latitude, 4) + "°")),
          tile("الطول شرقًا", ltr(el, num(location.longitude, 4) + "°")),
          tile("الارتفاع", el("span", null, ltr(el, num(location.elevation, 1)), " م"))),
        el("p", { class: "hint" }, "إحداثيات المعهد لمعتمديتك. التوقيت: غرينتش ", ltr(el, "+1"), " طوال السنة، دون توقيت صيفي.")));
    }

    /** A small figure with its name above it (`value`: a node, filled later when it changes). */
    function tile(name, value) {
      return el("div", { class: "tile" }, el("div", { class: "tile-name", text: name }), el("div", { class: "tile-value" }, value));
    }

    // ------------------------------------------------ the day shown
    if (location) {
      var dayText = el("span", { class: "day-text" });
      // Not a setting: it only chooses what the page explains, so it is kept out of the page's unsaved edits.
      var slider = el("input", {
        type: "range", id: "formula-day", class: "wide", value: String(shown.doy),
        attrs: { min: 0, max: yearDays - 1, step: 1 }
      });
      var quick = [
        { label: "اليوم", doy: dayOfYear(year, now.getUTCMonth() + 1, now.getUTCDate()) },
        { label: "أطول نهار", doy: dayOfYear(year, 6, 21) },
        { label: "أقصر نهار", doy: dayOfYear(year, 12, 21) }
      ].map(function (q) {
        q.button = el("button", { type: "button", text: q.label, on: { click: function () { showDay(q.doy); } } });
        return q;
      });
      var pickDay = function (event) {
        event.stopPropagation();
        showDay(Number(slider.value));
      };
      slider.addEventListener("input", pickDay);
      slider.addEventListener("change", pickDay);
      root.appendChild(el("section", { class: "card" },
        el("div", { class: "head" }, el("label", { class: "day-label", text: "اليوم المعروض", attrs: { for: slider.id } }), dayText),
        slider,
        el("div", { class: "row pills" }, quick.map(function (q) { return q.button; }))));
      updates.push(function (model) {
        var text = dateText(model.date);
        dayText.textContent = text;
        slider.setAttribute("aria-valuetext", text);
        if (Number(slider.value) !== shown.doy) slider.value = String(shown.doy);
        quick.forEach(function (q) { q.button.setAttribute("aria-pressed", q.doy === shown.doy ? "true" : "false"); });
      });
    }

    function showDay(doy) {
      shown.doy = Math.max(0, Math.min(yearDays - 1, doy));
      refresh();
    }

    // ------------------------------------------------ the chart
    if (location) {
      var chartTitle = el("h2");
      var chart = svg("svg", { viewBox: "0 0 350 326", role: "img", class: "chart" });
      var noonTile = el("span", { class: "ltr tabular" });
      var peakTile = el("span", { class: "ltr tabular" });
      var lengthTile = el("span", { class: "ltr tabular" });
      var astronomy = {
        declination: el("span", { class: "ltr tabular" }),
        equation: el("span", { class: "ltr tabular" }),
        dip: el("span", { class: "ltr tabular" }),
        asr: el("span", { class: "ltr tabular" })
      };
      var dipName = el("span");
      var fact = function (name, value) { return el("div", { class: "fact" }, el("span", null, name), el("strong", null, value)); };
      root.appendChild(el("section", { class: "card chart-card" },
        chartTitle,
        el("p", { class: "hint", text: "المنحنى الذهبي: الشمس ساعةً بساعة، فوق الأفق نهارًا وتحته ليلًا. عند كل نقطة يدخل وقت. الدوائر المنقّطة: الأوقات الرسمية حين تختلف." }),
        chart,
        el("div", { class: "tiles" }, tile("الزوال", noonTile), tile("أعلى ارتفاع", peakTile), tile("طول النهار", lengthTile)),
        el("details", { class: "more" },
          el("summary", { text: "الأرقام الفلكية لهذا اليوم" }),
          fact("ميل الشمس (بُعدها شمال خط الاستواء أو جنوبه)", astronomy.declination),
          fact("معادلة الزمن (تقدّم الشمس على الساعة)", el("span", null, astronomy.equation, " د")),
          fact(dipName, astronomy.dip),
          fact("ارتفاع الشمس عند العصر", astronomy.asr),
          el("p", { class: "hint", text: "موقع الشمس من معادلات «ميوس» الفلكية، والنتيجة تُقرَّب إلى أقرب دقيقة، كما يفعل المعهد تمامًا." }))));
      updates.push(function (model) {
        var day = model.day;
        var text = dateText(model.date);
        var date = model.date;
        var peak = F.altitudeAt(location, date.year, date.month, date.day, day.noon / 60);
        chartTitle.textContent = "ارتفاع الشمس يوم " + text;
        drawChart(chart, model, location);
        chart.setAttribute("aria-label", "منحنى ارتفاع الشمس يوم " + text + "، ونقاط المواقيت عليه: " +
          day.times.map(function (m, i) { return NAMES[i] + " " + hm(m); }).join("، ") +
          ". الزوال " + hm(day.noon) + "، وأعلى ارتفاع للشمس " + num(peak, 1) + "°.");
        noonTile.textContent = hm(day.noon);
        peakTile.textContent = num(peak, 1) + "°";
        lengthTile.textContent = hm(day.sunset - day.sunrise);
        astronomy.declination.textContent = num(day.declination, 2) + "°";
        astronomy.equation.textContent = (day.equationOfTime >= 0 ? "+" : "") + num(day.equationOfTime, 1);
        dipName.textContent = model.settings.elevation
          ? "انخفاض الأفق لارتفاع " + num(location.elevation, 1) + " م"
          : "انخفاض الأفق (ارتفاع المسجد غير محسوب)";
        astronomy.dip.textContent = num(day.dip, 3) + "°";
        astronomy.asr.textContent = num(day.asrAltitude, 2) + "°";
      });
    }

    // ------------------------------------------------ the rule of each time
    if (location) {
      var rules = NAMES.map(function (name, i) {
        var row = {
          time: el("span", { class: "ltr tabular rule-time" }),
          badge: el("span", { class: "badge diff" }),
          text: el("span", { class: "rule-text" }),
          note: el("span", { class: "rule-note" })
        };
        row.button = el("button", {
          type: "button", class: "rule",
          on: { click: function () { shown.pick = i; refresh(); } }
        },
          el("span", { class: "rule-head" }, el("strong", { text: name }), el("span", { class: "rule-end" }, row.badge, row.time)),
          row.text,
          row.note);
        return row;
      });
      root.appendChild(el("section", { class: "card rules" },
        el("h2", { text: "قاعدة كل وقت" }),
        el("p", { class: "hint", text: "اختر وقتًا لتراه على المنحنى." }),
        rules.map(function (row) { return row.button; })));
      updates.push(function (model) {
        var s = model.settings;
        var day = model.day;
        var dipPart = function (dip) { return s.elevation ? " (و" + degrees(dip, 1) + " لانخفاض الأفق)" : ""; };
        var texts = [
          "حين تكون الشمس " + degrees(s.fajrAngle, 1) + " تحت الأفق قبل طلوعها" + dipPart(day.dip) + ".",
          "حين يطلع أعلى قرص الشمس: مركزها " + degrees(0.83, 2) + " تحت الأفق، نصف القرص وانكسار الضوء في الجوّ" + dipPart(day.sunriseDip) + ".",
          "بعد الزوال (" + hm(day.noon) + ") بـ " + s.dhuhrMinutes + " د. الزوال لحظة أعلى ارتفاع للشمس، وهي في جهة الجنوب.",
          "حين يصير ظلّ العود " + (s.asrShadow === 1 ? "مثل طوله" : "مثلَي طوله") + " زائدًا ظلّه عند الزوال: الشمس على ارتفاع " + degrees(day.asrAltitude, 1) + ".",
          "بعد غروب الشمس (" + hm(day.sunset) + ") بـ " + s.maghribMinutes + " د.",
          "حين تكون الشمس " + degrees(s.ishaAngle, 1) + " تحت الأفق بعد غروبها" + dipPart(day.dip) + "."
        ];
        rules.forEach(function (row, i) {
          var diff = model.diffs[i];
          var adjust = ADJUST_KEYS[i] ? s.adjust[ADJUST_KEYS[i]] : 0;
          row.button.setAttribute("aria-pressed", shown.pick === i ? "true" : "false");
          row.time.textContent = hm(day.times[i]);
          row.text.textContent = texts[i];
          row.badge.hidden = diff === 0;
          row.badge.textContent = "";
          if (diff !== 0) {
            row.badge.appendChild(ltr(el, signed(diff)));
            row.badge.appendChild(document.createTextNode(" د"));
            row.badge.appendChild(el("span", { class: "sr", text: " عن الرسمي" }));
          }
          row.note.hidden = adjust === 0 && diff === 0;
          row.note.textContent = "";
          if (adjust !== 0) row.note.appendChild(el("span", null, "مع تعديل يدوي ", ltr(el, signed(adjust)), " د. "));
          if (diff !== 0) row.note.appendChild(el("span", null, "الرسمي ", ltr(el, hm(model.officialDay.times[i])), "."));
        });
      });
    }

    // ------------------------------------------------ the Asr shadow
    if (location) {
      var shadowText = el("p", { class: "muted" });
      var shadow = svg("svg", { viewBox: "0 0 274 170", role: "img", class: "chart shadow" });
      var swatch = function (kind, text) { return el("span", null, el("span", { class: "swatch " + kind, attrs: { "aria-hidden": "true" } }), text); };
      root.appendChild(el("section", { class: "card" },
        el("h2", { text: "العصر: طول الظلّ" }),
        shadowText,
        shadow,
        el("div", { class: "legend" },
          swatch("noon", "ظلّ العود عند الزوال (الشريط السفلي)"),
          swatch("asr", "ظلّه عند دخول العصر (العود = 1)"))));
      updates.push(function (model) {
        var s = model.settings;
        var ns = model.day.noonShadow;
        shadowText.textContent = "عند الزوال يكون ظلّ العود " + num(ns, 2) + " من طوله في هذا اليوم. يدخل العصر حين يبلغ ظلّه " +
          (s.asrShadow === 1 ? "طوله" : "مثلَي طوله") + " زائدًا ذلك: " + num(s.asrShadow + ns, 2) +
          "، والشمس على ارتفاع " + degrees(model.day.asrAltitude, 1) + ".";
        drawShadow(shadow, model);
      });
    }

    // ------------------------------------------------ the values («تخصيص الحساب»)
    var R = F.RANGES;
    var VALUES = [
      { key: "fajrAngle", id: "formula-fajr-angle", label: "زاوية الفجر", unit: "°", step: R.angle.step, range: R.angle, inputMode: "decimal" },
      { key: "ishaAngle", id: "formula-isha-angle", label: "زاوية العشاء", unit: "°", step: R.angle.step, range: R.angle, inputMode: "decimal" },
      { key: "dhuhrMinutes", id: "formula-dhuhr", label: "الظهر بعد الزوال", unit: " د", step: 1, range: R.dhuhrMinutes, inputMode: "numeric" },
      { key: "maghribMinutes", id: "formula-maghrib", label: "المغرب بعد الغروب", unit: " د", step: 1, range: R.maghribMinutes, inputMode: "numeric" }
    ];
    // Negative minutes: no inputmode, so a phone's keyboard keeps its minus sign.
    var ADJUSTS = F.ADJUSTABLE.map(function (key) {
      var label = NAMES[ADJUST_KEYS.indexOf(key)];
      // Shown by the prayer's name only; heard as what it changes.
      return { key: key, id: "formula-adjust-" + key, label: label, name: "تعديل " + label + " بالدقائق", step: 1, range: R.adjust, official: 0, adjust: true };
    });
    var customBox = el("input", { type: "checkbox", id: "formula-custom", checked: !F.isOfficial(tv), attrs: { "aria-label": "تفعيل تخصيص الحساب" } });
    var customState = el("div", { class: "hint" });
    var valueGrid = el("div", { class: "values" });
    VALUES.forEach(function (spec) {
      spec.official = F.OFFICIAL[spec.key];
      var control = stepper(el, spec, tv[spec.key], "إنقاص " + spec.label, "زيادة " + spec.label);
      spec.input = control.input;
      spec.sync = control.sync;
      spec.hint = el("div", { class: "hint", id: spec.id + "-hint" });
      valueGrid.appendChild(el("div", { class: "value" },
        el("label", { text: spec.label, attrs: { for: spec.id } }), control.node, spec.hint));
    });
    var shadowChoices = [
      { value: 1, id: "formula-asr-1", label: "مثل واحد", sub: "الرسمي، وهو قول الجمهور" },
      { value: 2, id: "formula-asr-2", label: "مثلان", sub: "المذهب الحنفي" }
    ].map(function (choice) {
      choice.input = el("input", { type: "radio", name: "formula-asr", id: choice.id, value: String(choice.value), checked: tv.asrShadow === choice.value });
      choice.node = el("label", { class: "choice", attrs: { for: choice.id } },
        choice.input, el("span", null, el("strong", { text: choice.label }), el("span", { class: "small", text: choice.sub })));
      return choice;
    });
    var elevationBox = el("input", { type: "checkbox", id: "formula-elevation", checked: tv.elevation, attrs: { "aria-describedby": "formula-elevation-hint" } });
    var adjustSummary = el("summary");
    var adjustRows = ADJUSTS.map(function (spec) {
      var control = stepper(el, spec, tv.adjust[spec.key], "تقديم " + spec.label + " دقيقة", "تأخير " + spec.label + " دقيقة");
      spec.input = control.input;
      spec.sync = control.sync;
      spec.hint = el("div", { class: "sr", id: spec.id + "-hint" });
      return el("div", { class: "adjust" },
        el("label", { text: spec.label, attrs: { for: spec.id } }),
        el("span", { class: "inline" }, control.node, " د"),
        spec.hint);
    });
    var adjustments = el("details", { class: "more", open: ADJUSTS.some(function (spec) { return tv.adjust[spec.key] !== 0; }) },
      adjustSummary,
      el("p", { class: "hint" }, "من ", ltr(el, "−15"), " إلى ", ltr(el, "+15"), " دقيقة لكل صلاة: الموجب يؤخّر الوقت، والسالب يقدّمه. لا يتغيّر الشروق."),
      adjustRows);
    var resetButton = el("button", { type: "button", text: "الرجوع إلى الأوقات الرسمية", on: { click: resetValues } });
    var locked = el("div", { class: "locked" },
      svg("svg", { width: 20, height: 20, viewBox: "0 0 24 24", fill: "none", stroke: C.muted, "stroke-width": 2,
        "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true", focusable: "false" },
        svg("rect", { x: 5, y: 11, width: 14, height: 10, rx: 2 }),
        svg("path", { d: "M8 11V7a4 4 0 0 1 8 0v4" })),
      el("div", { text: "القيم الرسمية للمعهد: الفجر والعشاء " + degrees(18, 1) + "، ظلّ العصر مثل واحد، الظهر بعد الزوال بـ 7 د، المغرب بعد الغروب بـ 2 د، مع حساب ارتفاع المسجد." }));
    var customFields = el("div", { class: "custom" },
      el("div", { class: "caution", text: "المواقيت الرسمية في تونس هي مواقيت المعهد الوطني للرصد الجوي، وعليها يعتمد المصلّون. كل تغيير هنا يجعل شاشة المسجد تختلف عنها." }),
      valueGrid,
      el("fieldset", { class: "choices" }, el("legend", { text: "ظلّ العصر" }), shadowChoices.map(function (c) { return c.node; })),
      el("div", null,
        el("label", { class: "check", attrs: { for: elevationBox.id } }, elevationBox,
          "حساب ارتفاع المسجد" + (location ? " (" + num(location.elevation, 1) + " م)" : "")),
        el("div", { class: "hint", id: "formula-elevation-hint", text: "من مكان مرتفع ينخفض الأفق قليلًا: يتقدّم الفجر والشروق ويتأخّر المغرب والعشاء. الرسمي: مفعّل." })),
      adjustments,
      el("div", { class: "row" }, resetButton));
    var customCard = el("section", { class: "card" },
      el("div", { class: "head" },
        el("div", { class: "grow" }, el("h2", { text: "تخصيص الحساب" }), customState),
        el("label", { class: "check", attrs: { for: customBox.id } }, customBox, "تفعيل")),
      locked,
      customFields);
    root.appendChild(customCard);
    customCard.addEventListener("input", refresh);
    customCard.addEventListener("change", refresh);
    // Choosing one shadow unchecks the other without an event of its own: the page is told, so the
    // unsaved edits it keeps (and puts back) hold both radios as they are. Only for the admin's own
    // choice: while the page puts both back, telling it about the other one would drop that one's edit.
    shadowChoices.forEach(function (choice) {
      choice.input.addEventListener("change", function (event) {
        if (!event.isTrusted) return;
        shadowChoices.forEach(function (other) {
          if (other !== choice) other.input.dispatchEvent(new Event("change", { bubbles: true }));
        });
      });
    });

    /** The values the form shows (official while «تخصيص» is off, and for a value not accepted), and the mistakes. */
    function readForm() {
      var custom = customBox.checked;
      var errors = [];
      var values = { adjust: {} };
      VALUES.forEach(function (spec) {
        var n = readValue(spec.input, spec);
        spec.invalid = isNaN(n);
        if (spec.invalid) errors.push(spec.label + ": " + rangeText(spec));
        values[spec.key] = spec.invalid ? spec.official : n;
      });
      ADJUSTS.forEach(function (spec) {
        var n = readValue(spec.input, spec);
        spec.invalid = isNaN(n);
        if (spec.invalid) errors.push("تعديل " + spec.label + ": " + rangeText(spec));
        values.adjust[spec.key] = spec.invalid ? 0 : n;
      });
      values.asrShadow = shadowChoices[1].input.checked ? 2 : 1;
      values.elevation = elevationBox.checked;
      return { custom: custom, errors: custom ? errors : [], settings: F.normalize(custom ? values : null) };
    }

    /** What a field accepts, for its hint and the mistakes. */
    function rangeText(spec) {
      if (spec.step !== 1) return "من " + spec.range.min + " إلى " + spec.range.max + " درجة، بخطوات " + spec.step;
      if (spec.range.min < 0) return "عدد دقائق من " + isolated("−" + -spec.range.min) + " إلى " + isolated("+" + spec.range.max);
      return "عدد دقائق من " + spec.range.min + " إلى " + spec.range.max;
    }

    /** Every value back to INM's, each as if the admin set it (so the page's unsaved edits follow). */
    function resetValues() {
      function set(input, value) {
        var checkable = input.type === "checkbox" || input.type === "radio";
        if (checkable ? input.checked === value : input.value === value) return;
        if (checkable) input.checked = value;
        else input.value = value;
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
      }
      VALUES.forEach(function (spec) { set(spec.input, String(spec.official)); });
      shadowChoices.forEach(function (choice) { set(choice.input, choice.value === F.OFFICIAL.asrShadow); });
      set(elevationBox, F.OFFICIAL.elevation);
      ADJUSTS.forEach(function (spec) { set(spec.input, "0"); });
    }

    updates.push(function (model) {
      var tvOfficial = F.isOfficial(tv);
      customState.textContent = (!model.custom ? "مقفل على القيم الرسمية" : model.official ? "مفعّل، ولم تتغيّر قيمة بعد" : "مفعّل: قيم مخصّصة") +
        " · على الشاشة الآن: " + (tvOfficial ? "المواقيت الرسمية" : "قيم مخصّصة");
      locked.hidden = model.custom;
      customFields.hidden = !model.custom;
      // Under each value: the official one with its unit (which the field has no room for), or what
      // the field accepts when it holds something else. An adjustment says its range to screen
      // readers only, until it is wrong.
      VALUES.concat(ADJUSTS).forEach(function (spec) {
        spec.sync();
        spec.input.setAttribute("aria-invalid", spec.invalid ? "true" : "false");
        spec.hint.className = spec.invalid ? "hint problem" : spec.adjust ? "sr" : "hint";
        spec.hint.textContent = spec.invalid || spec.adjust ? rangeText(spec)
          : "الرسمي: " + (spec.unit === "°" ? degrees(spec.official, 1) : num(spec.official, 1) + spec.unit);
      });
      if (ADJUSTS.some(function (spec) { return spec.invalid; })) adjustments.open = true;
      var moved = ADJUSTS.filter(function (spec) { return model.settings.adjust[spec.key] !== 0; }).length;
      adjustSummary.textContent = "تعديل كل صلاة بالدقائق" + (moved ? " (" + moved + ")" : "");
      shadowChoices.forEach(function (choice) { choice.node.className = "choice" + (choice.input.checked ? " on" : ""); });
      // Not disabled, so the button keeps the focus once pressed; pressed again it changes nothing.
      resetButton.setAttribute("aria-disabled", model.official && !model.errors.length ? "true" : "false");
    });

    // ------------------------------------------------ preview and save
    var saveButton = el("button", { type: "button", class: "primary", text: "معاينة وحفظ", on: { click: save } });
    root.appendChild(el("div", { class: "actions" }, saveButton));

    /**
     * Sends every value (the whole section, so a copied file or another phone's partial edit cannot
     * leave a mix), or null when the result is INM's official values.
     */
    function save() {
      var read = readForm();
      if (read.errors.length) {
        ctx.toast(read.errors.slice(0, 3).join(" · ") + (read.errors.length > 3 ? " …" : ""), "error");
        return;
      }
      if (F.same(read.settings, tv)) {
        ctx.toast("لا تغيير");
        return;
      }
      saveButton.disabled = true;
      ctx.submit({ prayerTimes: F.isOfficial(read.settings) ? null : sectionOf(read.settings) }).then(function () {
        saveButton.disabled = false;
      }, function () {
        saveButton.disabled = false;
      });
    }

    /** The values shown and the times they give on the day shown, next to the official ones. */
    function compute() {
      var read = readForm();
      var date = dateOf(year, shown.doy);
      var model = { settings: read.settings, errors: read.errors, custom: read.custom, date: date };
      model.official = F.isOfficial(read.settings);
      if (!location) return model;
      model.day = F.day(location, date.year, date.month, date.day, read.settings);
      model.officialDay = F.day(location, date.year, date.month, date.day, F.OFFICIAL);
      model.diffs = model.day.times.map(function (m, i) { return m - model.officialDay.times[i]; });
      return model;
    }

    refresh();
  }

  Dashboard.registerView({ id: "formula", title: "حساب المواقيت", form: true, render: render });
})();
