/*
 * Mosque TV dashboard: the start of Ramadan and the two Eids of this Hijri year. Automatic by default
 * (the official announcement when the TV was online, else the estimate); a mosque that follows its
 * own sighting sets them by hand.
 */
(function () {
  "use strict";

  var SOURCES = { MANUAL: "يدوي", OFFICIAL: "رسمي", ESTIMATE: "تقديري" };
  var ISO = /^\d{4}-\d{2}-\d{2}$/;
  var KEYS = ["ramadanStart", "eidFitr", "eidAdha"];

  /** "الاثنين 8 فيفري 2027", or the ISO date when the phone's browser cannot write it in Arabic. */
  function longDate(iso) {
    if (!iso || !ISO.test(iso)) return iso || "—";
    try {
      var p = iso.split("-");
      return new Date(Date.UTC(+p[0], +p[1] - 1, +p[2])).toLocaleDateString("ar-TN-u-nu-latn", {
        weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC"
      });
    } catch (e) {
      return iso;
    }
  }

  function isoSpan(el, iso) {
    return el("span", { class: "ltr tabular", text: iso || "—" });
  }

  /** One event: its card, and a reader giving null (automatic), the manual date, or an error. */
  function eventCard(el, event) {
    var manual = event.source === "MANUAL";
    var id = "dates-" + event.id;
    var auto = el("input", { type: "checkbox", id: id + "-auto", checked: !manual });
    var date = el("input", {
      type: "date", id: id, class: "ltr tabular", value: event.date || event.automatic || "",
      disabled: !manual, attrs: { min: event.min || false, max: event.max || false }
    });
    var badge = el("span", { class: "badge" + (manual ? " current" : ""), text: SOURCES[event.source] || event.source || "" });

    auto.addEventListener("change", function () {
      date.disabled = auto.checked;
      if (!auto.checked && !date.value) date.value = event.automatic || event.date || "";
      if (!auto.checked) date.focus();
    });

    var node = el("div", { class: "card " + (manual ? "level-WARNING" : "level-INFO") },
      el("div", { class: "row" },
        el("strong", { class: "grow", text: event.name || event.id }),
        badge),
      el("div", { class: "muted", text: "على الشاشة: " + longDate(event.date) }),
      el("label", { attrs: { for: auto.id } }, auto, " تلقائي: " + longDate(event.automatic)),
      el("label", { text: "التاريخ يدويًا", attrs: { for: id } }),
      date,
      event.min && event.max
        ? el("div", { class: "muted" }, "يُقبل من ", isoSpan(el, event.min), " إلى ", isoSpan(el, event.max))
        : null);

    function read() {
      if (auto.checked) return { value: null };
      var value = date.value;
      var name = event.name || event.id;
      if (!ISO.test(value)) return { error: name + ": اختر تاريخًا أو اجعله تلقائيًا" };
      if (event.min && value < event.min) return { error: name + ": التاريخ لا يسبق " + event.min };
      if (event.max && value > event.max) return { error: name + ": التاريخ لا يتجاوز " + event.max };
      return { value: value };
    }

    return { event: event, node: node, read: read };
  }

  function render(root, ctx) {
    var el = ctx.el;
    var data = ctx.state.islamicDates;
    // Only the three keys the settings file knows, so the form never sends a field the TV refuses.
    var events = data && Array.isArray(data.events)
      ? data.events.filter(function (event) { return event && KEYS.indexOf(event.id) >= 0; })
      : [];
    if (!data || !data.hijriYear || !events.length) {
      root.appendChild(el("div", { class: "card level-INFO", text: "لا تتوفّر تواريخ رمضان والعيد على الشاشة الآن." }));
      return;
    }

    root.appendChild(el("h2", { text: "السنة " + data.hijriYear + " هـ" }));
    root.appendChild(el("p", {
      class: "muted",
      text: "التواريخ تلقائية: الإعلان الرسمي إن كانت الشاشة متصلة بالإنترنت عند صدوره، وإلا فالتقدير الفلكي. " +
        "المسجد الذي يتبع رؤيته الخاصة يُلغي «تلقائي» ويختار التاريخ بنفسه."
    }));

    var cards = events.map(function (event) { return eventCard(el, event); });
    cards.forEach(function (card) { root.appendChild(card.node); });

    var saveButton = el("button", { type: "button", class: "primary", text: "معاينة وحفظ" });
    saveButton.addEventListener("click", function () {
      var errors = [];
      var dates = {};
      cards.forEach(function (card) {
        var result = card.read();
        if (result.error) errors.push(result.error);
        else dates[card.event.id] = result.value;
      });
      if (errors.length) {
        ctx.toast(errors.join(" · "), "error");
        return;
      }
      var years = {};
      years[String(data.hijriYear)] = dates;
      saveButton.disabled = true;
      ctx.submit({ islamicDates: years }).then(function () {
        saveButton.disabled = false;
      }, function (error) {
        saveButton.disabled = false;
        ctx.toast(error.message, "error");
      });
    });
    root.appendChild(el("div", { class: "row" }, saveButton));
  }

  Dashboard.registerView({ id: "dates", title: "رمضان والعيد", render: render });
})();
