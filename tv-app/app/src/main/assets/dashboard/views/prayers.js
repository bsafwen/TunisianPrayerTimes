/*
 * Iqamah and prayer duration of every prayer, and what changes in Ramadan.
 * Builds { prayers, ramadan } of the settings file; the TV previews it before applying.
 */
(function () {
  "use strict";

  function pad(n) { return (n < 10 ? "0" : "") + n; }

  /** "5:07", "05:07" or "05:07:00" as "05:07"; null when it is not a time of the day. */
  function normalizeTime(text) {
    var match = /^(\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/.exec(String(text || "").trim());
    if (!match) return null;
    var h = Number(match[1]);
    var m = Number(match[2]);
    return h < 24 && m < 60 ? pad(h) + ":" + pad(m) : null;
  }

  /** "+15" or "20:00" as the form's fields; anything else as an empty "after" field. */
  function parseIqamah(value) {
    var text = typeof value === "string" ? value.trim() : "";
    var after = /^\+(\d+)$/.exec(text);
    if (after) return { kind: "after", minutes: String(Number(after[1])), time: "" };
    var fixed = normalizeTime(text);
    if (fixed) return { kind: "fixed", minutes: "", time: fixed };
    return { kind: "after", minutes: "", time: "" };
  }

  /** A minutes field: a whole number in 1..90, null when empty, NaN when wrong. */
  function readMinutes(input) {
    var text = String(input.value || "").trim();
    if (!text) return input.validity && input.validity.badInput ? NaN : null;
    if (!/^\d+$/.test(text)) return NaN;
    var n = Number(text);
    return n >= 1 && n <= 90 ? n : NaN;
  }

  /** The duration in minutes; null when empty and optional; undefined (and a mistake) when wrong. */
  function readDuration(input, errors, name, optional) {
    var n = readMinutes(input);
    if (n === null && optional) return null;
    if (n === null || isNaN(n)) {
      errors.push(name + ": مدة الصلاة عدد دقائق من 1 إلى 90");
      return undefined;
    }
    return n;
  }

  /** What an input shows, with a value the browser could not read, to tell whether the admin edited it. */
  function inputSnapshot(input) {
    return String(input.value) + (input.validity && input.validity.badInput ? "|bad" : "");
  }

  /**
   * A field and the value it had when the form was drawn. At submit only a field the admin edited
   * is read (and checked), and it is sent only when its value differs from the first one, so a change
   * made meanwhile from the remote, a USB key or another phone is not reverted.
   * read(errors) gives the value, or undefined after adding a mistake to errors.
   */
  function tracked(snapshot, read) {
    var startSnapshot = snapshot();
    var startValue = read([]);
    return {
      /** { changed, value }: changed is false when the field is as drawn or holds the same value. */
      change: function (errors) {
        if (snapshot() === startSnapshot) return { changed: false };
        var value = read(errors);
        return { changed: value !== undefined && value !== startValue, value: value };
      }
    };
  }

  function numberInput(el, value, label) {
    return el("input", {
      type: "number", min: 1, max: 90, step: 1, inputMode: "numeric", class: "tabular",
      value: value === undefined || value === null ? "" : String(value),
      attrs: { "aria-label": label }
    });
  }

  /** A kind select with a minutes field or a time field, whichever the kind needs. */
  function iqamahField(el, eid, initial, label) {
    var kind = el("select", { attrs: { "aria-label": label + ": نوع الإقامة" } },
      el("option", { value: "after", text: eid ? "بعد الشروق" : "بعد الأذان" }),
      el("option", { value: "fixed", text: "وقت ثابت" }));
    kind.value = initial.kind;
    var minutes = numberInput(el, initial.minutes, label + ": دقائق الإقامة");
    var time = el("input", { type: "time", class: "ltr tabular", value: initial.time, attrs: { "aria-label": label + ": وقت الإقامة" } });
    function sync() {
      minutes.hidden = kind.value !== "after";
      time.hidden = kind.value !== "fixed";
    }
    kind.addEventListener("change", sync);
    sync();
    return {
      nodes: [kind, " ", minutes, time],
      /** What the admin sees in the field: it changes only when the admin edits it. */
      snapshot: function () {
        return kind.value + "|" + inputSnapshot(kind.value === "fixed" ? time : minutes);
      },
      /** "+N" or "HH:MM"; null when empty and optional; undefined (and a mistake) when wrong. */
      read: function (errors, name, optional) {
        if (kind.value === "fixed") {
          var raw = String(time.value || "").trim();
          var bad = time.validity && time.validity.badInput;
          if (!raw && !bad && optional) return null;
          var fixed = normalizeTime(raw);
          if (!fixed) {
            errors.push(name + ": أدخل وقت الإقامة بالشكل س:د (مثلًا 20:00)");
            return undefined;
          }
          return fixed;
        }
        var n = readMinutes(minutes);
        if (n === null && optional) return null;
        if (n === null || isNaN(n)) {
          errors.push(name + ": دقائق الإقامة عدد من 1 إلى 90");
          return undefined;
        }
        return "+" + n;
      }
    };
  }

  Dashboard.registerView({
    id: "prayers",
    title: "الإقامة",
    render: function (root, ctx) {
      var el = ctx.el;
      var settings = ctx.settingsCopy() || {};
      var prayers = settings.prayers || {};
      var ramadan = settings.ramadan || {};
      var today = {};
      var todayPrayers = ctx.state && ctx.state.today && ctx.state.today.prayers;
      (Array.isArray(todayPrayers) ? todayPrayers : []).forEach(function (p) { if (p) today[p.id] = p; });

      // ---- the usual iqamah and duration
      var rows = [];
      var body = el("tbody");
      ctx.PRAYERS.forEach(function (prayer) {
        var current = prayers[prayer.key] || {};
        var iqamah = iqamahField(el, prayer.eid, parseIqamah(current.iqamah), prayer.name);
        var duration = numberInput(el, current.duration, prayer.name + ": مدة الصلاة بالدقائق");
        var now = today[prayer.id];
        body.appendChild(el("tr", null,
          el("td", null,
            el("strong", { text: prayer.name }),
            prayer.eid ? el("div", { class: "muted", text: "بعد الشروق" }) : null,
            now ? el("div", { class: "muted tabular", text: "اليوم: الأذان " + (now.adhan || "—") + " · الإقامة " + (now.iqamah || "—") }) : null),
          el("td", null, iqamah.nodes),
          el("td", null, duration)));
        rows.push({
          prayer: prayer,
          iqamah: tracked(iqamah.snapshot, function (errors) { return iqamah.read(errors, prayer.name, false); }),
          duration: tracked(function () { return inputSnapshot(duration); },
            function (errors) { return readDuration(duration, errors, prayer.name, false); })
        });
      });

      root.appendChild(el("h2", { text: "الإقامة ومدة الصلاة" }));
      root.appendChild(el("div", { class: "card" },
        el("p", { class: "muted", text: "الإقامة: دقائق بعد الأذان (بعد الشروق للعيدين) أو وقت ثابت. مدة الصلاة: مدة الشاشة السوداء. الدقائق من 1 إلى 90." }),
        el("table", null,
          el("thead", null, el("tr", null,
            el("th", { text: "الصلاة" }),
            el("th", { text: "الإقامة" }),
            el("th", { text: "مدة الصلاة بالدقائق" }))),
          body)));

      // ---- what changes in Ramadan
      var ramadanRows = [];
      var ramadanList = el("div");
      ctx.PRAYERS.filter(function (p) { return !p.eid; }).forEach(function (prayer) {
        var current = ramadan[prayer.key] || {};
        var changed = (current.iqamah !== undefined && current.iqamah !== null) ||
          (current.duration !== undefined && current.duration !== null);
        var check = el("input", { type: "checkbox", checked: changed });
        var name = prayer.name + " في رمضان";
        var iqamah = iqamahField(el, false, parseIqamah(current.iqamah), name);
        var duration = numberInput(el, current.duration, name + ": مدة الصلاة بالدقائق");
        var details = el("div", null,
          el("label", { text: "الإقامة (فارغ: دون تغيير)" }),
          el("div", null, iqamah.nodes),
          el("label", { text: "مدة الصلاة بالدقائق (فارغ: دون تغيير)" }),
          duration);
        function sync() { details.hidden = !check.checked; }
        check.addEventListener("change", sync);
        sync();
        ramadanList.appendChild(el("div", { class: "list-item" },
          el("strong", { text: prayer.name }),
          el("label", null, check, " تغيير في رمضان"),
          details));
        ramadanRows.push({
          prayer: prayer,
          check: check,
          wasChecked: check.checked,
          iqamah: tracked(iqamah.snapshot, function (errors) { return iqamah.read(errors, name, true); }),
          duration: tracked(function () { return inputSnapshot(duration); },
            function (errors) { return readDuration(duration, errors, name, true); })
        });
      });

      root.appendChild(el("h2", { text: "تغييرات رمضان" }));
      root.appendChild(el("div", { class: "card" },
        el("p", { class: "muted", text: "ما يتغيّر في رمضان فقط. الحقل الفارغ يُبقي الإعداد المعتاد، وإلغاء الاختيار يعيد الصلاة إلى إعدادها المعتاد." }),
        ramadanList));

      // ---- preview and apply
      var apply = el("button", { class: "primary", type: "button", text: "معاينة وتطبيق", on: { click: submit } });
      root.appendChild(el("div", { class: "row" }, apply));

      /** Sends only what the admin changed, so a change made meanwhile on the TV is kept. */
      function submit() {
        var errors = [];
        var partialPrayers = {};
        var partialRamadan = {};
        /** The changed fields of a row, or null when none changed. */
        function changes(row) {
          var change = {};
          var iqamah = row.iqamah.change(errors);
          var duration = row.duration.change(errors);
          if (iqamah.changed) change.iqamah = iqamah.value;
          if (duration.changed) change.duration = duration.value;
          return Object.keys(change).length ? change : null;
        }
        rows.forEach(function (row) {
          var change = changes(row);
          if (change) partialPrayers[row.prayer.key] = change;
        });
        ramadanRows.forEach(function (row) {
          // Unchecking returns the prayer to its usual setting; a box left unchecked sends nothing.
          var change = row.check.checked ? changes(row)
            : row.wasChecked ? { iqamah: null, duration: null } : null;
          if (change) partialRamadan[row.prayer.key] = change;
        });
        if (errors.length) {
          ctx.toast(errors.slice(0, 3).join(" · ") + (errors.length > 3 ? " …" : ""), "error");
          return;
        }
        var partial = {};
        if (Object.keys(partialPrayers).length) partial.prayers = partialPrayers;
        if (Object.keys(partialRamadan).length) partial.ramadan = partialRamadan;
        if (!partial.prayers && !partial.ramadan) {
          ctx.toast("لا تغيير");
          return;
        }
        apply.disabled = true;
        ctx.submit(partial).then(function () { apply.disabled = false; }, function () { apply.disabled = false; });
      }
    }
  });
})();
