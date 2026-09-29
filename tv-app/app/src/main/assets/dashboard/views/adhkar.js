/*
 * Adhkar: the mosque's own texts after the prayer and in the ticker, after or instead of the bundled,
 * reviewed texts. Texts come from users: the DOM is built with ctx.el only (never HTML).
 */
(function () {
  "use strict";

  var MAX_ITEMS = 100;
  var MAX_TEXT = 1000;
  var MAX_REFERENCE = 200;
  var MAX_COUNT = 1000;
  var SECTIONS = [
    { key: "afterSalah", title: "أذكار بعد الصلاة" },
    { key: "ticker", title: "شريط الأذكار" }
  ];
  var MODES = [
    { value: null, label: "النصوص المراجَعة المضمّنة فقط" },
    { value: "append", label: "إضافة نصوص المسجد بعدها" },
    { value: "replace", label: "نصوص المسجد بدلها" }
  ];

  // The lists being edited survive a re-render (another tab) until the TV's own adhkar change.
  var draft = null;
  var draftBase = null;

  function blank() { return { text: "", reference: "", count: "1" }; }

  function startDraft(settings, force) {
    var adhkar = settings && settings.adhkar && typeof settings.adhkar === "object" ? settings.adhkar : {};
    var base = JSON.stringify(adhkar);
    if (draft && draftBase === base && !force) return;
    draftBase = base;
    draft = {};
    SECTIONS.forEach(function (section) {
      var list = adhkar[section.key];
      // The file also accepts a bare array of items, read as "append".
      var items = Array.isArray(list) ? list : (list && Array.isArray(list.items) ? list.items : []);
      draft[section.key] = {
        mode: !list ? null : (list.mode === "replace" ? "replace" : "append"),
        items: items.map(function (item) {
          item = item || {};
          return {
            text: String(item.text || ""),
            reference: String(item.reference || ""),
            count: item.count === undefined || item.count === null ? "1" : String(item.count)
          };
        })
      };
    });
  }

  function fail(ctx, message) {
    ctx.toast(message, "error");
    return null;
  }

  /** The adhkar section to send, or null after showing the first mistake. */
  function collect(ctx) {
    var out = {};
    for (var s = 0; s < SECTIONS.length; s++) {
      var section = SECTIONS[s];
      var part = draft[section.key];
      if (!part.mode) {
        out[section.key] = null;
        continue;
      }
      if (!part.items.length) return fail(ctx, section.title + ": أضف نصاً واحداً على الأقل، أو اختر النصوص المضمّنة فقط");
      if (part.items.length > MAX_ITEMS) return fail(ctx, section.title + ": " + MAX_ITEMS + " نص على الأكثر");
      var items = [];
      for (var i = 0; i < part.items.length; i++) {
        var item = part.items[i];
        var where = section.title + " · النص " + (i + 1) + ": ";
        var text = item.text.trim();
        var reference = item.reference.trim();
        var count = Number(String(item.count).trim());
        if (!text) return fail(ctx, where + "النص فارغ");
        if (text.length > MAX_TEXT) return fail(ctx, where + "النص أطول من " + MAX_TEXT + " حرف");
        if (!reference) return fail(ctx, where + "المصدر مطلوب");
        if (reference.length > MAX_REFERENCE) return fail(ctx, where + "المصدر أطول من " + MAX_REFERENCE + " حرف");
        if (!(count >= 1 && count <= MAX_COUNT && Math.floor(count) === count)) {
          return fail(ctx, where + "العدد عدد صحيح من 1 إلى " + MAX_COUNT);
        }
        items.push({ text: text, reference: reference, count: count });
      }
      out[section.key] = { mode: part.mode, items: items };
    }
    return out;
  }

  function itemEditor(ctx, section, part, item, i, paint) {
    var el = ctx.el;
    var id = "adhkar-" + section.key + "-" + i;
    function move(delta) {
      part.items.splice(i, 1);
      part.items.splice(i + delta, 0, item);
      paint();
    }
    function bind(key) { return { input: function (event) { item[key] = event.target.value; } }; }
    return el("div", { class: "list-item" },
      el("label", { text: "النص " + (i + 1), attrs: { for: id + "-text" } }),
      el("textarea", { id: id + "-text", value: item.text, attrs: { maxlength: MAX_TEXT, rows: 4 }, on: bind("text") }),
      el("div", { class: "row" },
        el("div", { class: "grow" },
          el("label", { text: "المصدر", attrs: { for: id + "-reference" } }),
          el("input", {
            id: id + "-reference", type: "text", value: item.reference,
            attrs: { maxlength: MAX_REFERENCE, required: true, placeholder: "مثال: رواه مسلم" }, on: bind("reference")
          })),
        el("div", {},
          el("label", { text: "العدد", attrs: { for: id + "-count" } }),
          el("input", {
            id: id + "-count", type: "number", value: item.count, class: "tabular",
            attrs: { min: 1, max: MAX_COUNT, step: 1, inputmode: "numeric", required: true }, on: bind("count")
          }))),
      el("div", { class: "row" },
        el("button", {
          text: "أعلى", disabled: i === 0, attrs: { "aria-label": "نقل النص " + (i + 1) + " إلى الأعلى" },
          on: { click: function () { move(-1); } }
        }),
        el("button", {
          text: "أسفل", disabled: i === part.items.length - 1, attrs: { "aria-label": "نقل النص " + (i + 1) + " إلى الأسفل" },
          on: { click: function () { move(1); } }
        }),
        el("button", { class: "danger", text: "حذف", on: { click: function () {
          if ((item.text.trim() || item.reference.trim()) && !confirm("حذف هذا النص؟")) return;
          part.items.splice(i, 1);
          paint();
        } } })));
  }

  function sectionCard(ctx, section, paint) {
    var el = ctx.el;
    var part = draft[section.key];
    var card = el("section", { class: "card" }, el("h2", { text: section.title }));
    MODES.forEach(function (mode) {
      card.appendChild(el("label", {},
        el("input", {
          type: "radio", name: "adhkar-mode-" + section.key, checked: part.mode === mode.value,
          on: { change: function () {
            part.mode = mode.value;
            if (part.mode && !part.items.length) part.items.push(blank());
            paint();
          } }
        }),
        " " + mode.label));
    });
    if (!part.mode) return card;
    if (!part.items.length) card.appendChild(el("p", { class: "muted", text: "لا توجد نصوص بعد: أضف نصاً واحداً على الأقل." }));
    part.items.forEach(function (item, i) { card.appendChild(itemEditor(ctx, section, part, item, i, paint)); });
    card.appendChild(el("div", { class: "row" },
      el("button", { text: "إضافة نص", on: { click: function () {
        part.items.push(blank());
        paint();
        var box = document.getElementById("adhkar-" + section.key + "-" + (part.items.length - 1) + "-text");
        if (box) box.focus();
      } } })));
    return card;
  }

  Dashboard.registerView({
    id: "adhkar",
    title: "الأذكار",
    render: function (root, ctx) {
      var el = ctx.el;
      startDraft(ctx.settings, false);
      var sections = el("div");
      function paint() {
        sections.textContent = "";
        SECTIONS.forEach(function (section) { sections.appendChild(sectionCard(ctx, section, paint)); });
      }
      root.appendChild(el("p", { class: "muted", text:
        "النصوص المضمّنة في التطبيق مراجَعة. كل نص يضيفه المسجد يحتاج إلى مصدره (السورة والآية، أو من روى الحديث)." }));
      root.appendChild(sections);
      root.appendChild(el("div", { class: "row" },
        el("button", { class: "primary", text: "معاينة وتطبيق", on: { click: function () {
          var adhkar = collect(ctx);
          if (!adhkar) return;
          // Rebuilt from the TV's lists on the next render; the form stays usable if that reload fails.
          ctx.submit({ adhkar: adhkar }).then(function (applied) { if (applied) draftBase = null; });
        } } }),
        el("button", { text: "إعادة القيم الحالية", on: { click: function () {
          startDraft(ctx.settings, true);
          paint();
        } } })));
      paint();
    }
  });
})();
