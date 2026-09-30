/*
 * How long the adhan screen lasts, the iqamah and prayer duration of every prayer, whether the mosque holds
 * Jumu'a and the Eid prayer, how long its khutba lasts, and what changes in Ramadan. Builds { prayers,
 * ramadan, display: { adhanScreenMinutes } } of the settings file; the TV previews it before applying.
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

  /**
   * A kind select with a minutes field or a time field, whichever the kind needs. `id` is the select's,
   * for its visible label; the fields keep their own names for screen readers.
   */
  function iqamahField(el, eid, initial, label, id) {
    var kind = el("select", { id: id, attrs: { "aria-label": label + ": نوع الإقامة" } },
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
      nodes: [kind, minutes, time],
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

  /** The iqamah and the duration side by side, each under a visible label (wrapping on a narrow phone). */
  function fieldsRow(el, iqamah, iqamahId, iqamahLabel, duration, durationLabel) {
    return el("div", { class: "fields" },
      el("div", null,
        el("label", { text: iqamahLabel, attrs: { for: iqamahId } }),
        el("div", { class: "inline" }, iqamah.nodes)),
      el("div", null,
        el("label", { text: durationLabel, attrs: { for: duration.id } }),
        duration));
  }

  Dashboard.registerView({
    id: "prayers",
    title: "الإقامة",
    // Its edited fields are kept by the page until applied (another tab, the back button).
    form: true,
    render: function (root, ctx) {
      var el = ctx.el;
      var settings = ctx.settingsCopy() || {};
      var prayers = settings.prayers || {};
      var ramadan = settings.ramadan || {};
      var display = settings.display || {};
      var today = {};
      var todayPrayers = ctx.state && ctx.state.today && ctx.state.today.prayers;
      (Array.isArray(todayPrayers) ? todayPrayers : []).forEach(function (p) { if (p) today[p.id] = p; });

      // ---- the usual iqamah and duration
      var rows = [];
      var usual = el("section", { class: "card" },
        el("h2", { text: "الإقامة ومدة الصلاة" }),
        el("p", { class: "hint", text: "الإقامة: دقائق بعد الأذان (بعد الشروق للعيدين) أو وقت ثابت. مدة الصلاة: مدة الشاشة السوداء. الدقائق من 1 إلى 90." }));

      // ---- how long the adhan screen lasts: an iqamah set sooner waits for its end
      var adhanInput = el("input", {
        type: "number", min: 1, max: 5, step: 1, inputMode: "numeric", class: "tabular", id: "adhan-screen-minutes",
        value: String(display.adhanScreenMinutes || 2)
      });
      var adhanScreen = tracked(function () { return inputSnapshot(adhanInput); }, function (errors) {
        var text = String(adhanInput.value || "").trim();
        var n = /^\d+$/.test(text) ? Number(text) : NaN;
        if (isNaN(n) || n < 1 || n > 5) {
          errors.push("مدة شاشة الأذان: عدد دقائق من 1 إلى 5");
          return undefined;
        }
        return n;
      });
      usual.appendChild(el("div", { class: "list-item" },
        el("label", { text: "مدة شاشة الأذان بالدقائق", attrs: { for: adhanInput.id } }),
        adhanInput,
        el("p", { class: "hint", text: "تعرض الشاشة ما يقوله السامع مع المؤذّن طوال هذه المدة. الإقامة الأقرب إلى الأذان من هذه المدة تنتظر نهايتها، فلا تسودّ الشاشة والمؤذّن يؤذّن." })));
      ctx.PRAYERS.forEach(function (prayer) {
        var current = prayers[prayer.key] || {};
        var id = "prayer-" + prayer.key;
        var iqamah = iqamahField(el, prayer.eid, parseIqamah(current.iqamah), prayer.name, id + "-kind");
        var duration = numberInput(el, current.duration, prayer.name + ": مدة الصلاة بالدقائق");
        duration.id = id + "-duration";
        var now = today[prayer.id];
        usual.appendChild(el("div", { class: "list-item" },
          el("div", { class: "head" },
            el("strong", { text: prayer.name }),
            now ? el("span", { class: "muted small" }, "اليوم: الأذان ", el("span", { class: "ltr tabular", text: now.adhan || "—" }),
              " · الإقامة ", el("span", { class: "ltr tabular", text: now.iqamah || "—" })) : null),
          prayer.eid ? el("div", { class: "muted small", text: "بعد الشروق" }) : null,
          fieldsRow(el, iqamah, id + "-kind", "الإقامة", duration, "المدة بالدقائق")));
        rows.push({
          prayer: prayer,
          iqamah: tracked(iqamah.snapshot, function (errors) { return iqamah.read(errors, prayer.name, false); }),
          duration: tracked(function () { return inputSnapshot(duration); },
            function (errors) { return readDuration(duration, errors, prayer.name, false); })
        });
      });
      root.appendChild(usual);

      // ---- whether the mosque holds Jumu'a and the Eid prayer at all (a neighbourhood masjid holds neither)
      function held(key) { return !(prayers[key] && prayers[key].held === false); }
      var heldRows = [
        { keys: ["jumua"], id: "held-jumua", text: "تقام صلاة الجمعة في هذا المسجد",
          hint: "إن لم تُقم يبقى الظهر يوم الجمعة، دون شاشة الخطبة." },
        { keys: ["eidFitr", "eidAdha"], id: "held-eid", text: "تقام صلاة العيد في هذا المسجد",
          hint: "إن لم تُقم لا يُعرض وقتها ولا عدّها التنازلي، وتبقى تهنئة العيد." }
      ].map(function (row) {
        var check = el("input", { type: "checkbox", id: row.id, checked: row.keys.every(held) });
        return {
          keys: row.keys,
          check: check,
          wasChecked: check.checked,
          node: el("div", { class: "list-item" },
            el("label", { class: "check", attrs: { for: row.id } }, check, row.text),
            el("p", { class: "hint", text: row.hint }))
        };
      });
      // ---- how long Jumu'a's khutba lasts: 0 keeps its quiet screen from the adhan to the iqamah
      var khutbaInput = el("input", {
        type: "number", min: 0, max: 60, step: 1, inputMode: "numeric", class: "tabular", id: "khutba-minutes",
        value: String((prayers.jumua && prayers.jumua.khutba) || 0)
      });
      var khutba = tracked(function () { return inputSnapshot(khutbaInput); }, function (errors) {
        var text = String(khutbaInput.value || "").trim();
        var n = /^\d+$/.test(text) ? Number(text) : NaN;
        if (isNaN(n) || n > 60) {
          errors.push("مدة خطبة الجمعة: عدد دقائق من 0 إلى 60");
          return undefined;
        }
        return n;
      });
      root.appendChild(el("section", { class: "card" },
        el("h2", { text: "الجمعة والعيد" }),
        heldRows.map(function (row) { return row.node; }),
        el("div", { class: "list-item" },
          el("label", { text: "مدة خطبة الجمعة بالدقائق", attrs: { for: khutbaInput.id } }),
          khutbaInput,
          el("p", { class: "hint", text: "شاشة الخطبة الهادئة بهذه المدة قبل الإقامة، وقبلها العدّ التنازلي. 0: من الأذان إلى الإقامة." }))));

      // ---- what changes in Ramadan
      var ramadanRows = [];
      var ramadanCard = el("section", { class: "card" },
        el("h2", { text: "تغييرات رمضان" }),
        el("p", { class: "hint", text: "ما يتغيّر في رمضان فقط. الحقل الفارغ يُبقي الإعداد المعتاد، وإلغاء الاختيار يعيد الصلاة إلى إعدادها المعتاد." }));
      ctx.PRAYERS.filter(function (p) { return !p.eid; }).forEach(function (prayer) {
        var current = ramadan[prayer.key] || {};
        var changed = (current.iqamah !== undefined && current.iqamah !== null) ||
          (current.duration !== undefined && current.duration !== null);
        var id = "ramadan-" + prayer.key;
        var check = el("input", { type: "checkbox", id: id + "-on", checked: changed });
        var name = prayer.name + " في رمضان";
        var iqamah = iqamahField(el, false, parseIqamah(current.iqamah), name, id + "-kind");
        var duration = numberInput(el, current.duration, name + ": مدة الصلاة بالدقائق");
        duration.id = id + "-duration";
        var details = fieldsRow(el, iqamah, id + "-kind", "الإقامة (فارغ: دون تغيير)",
          duration, "مدة الصلاة بالدقائق (فارغ: دون تغيير)");
        function sync() { details.hidden = !check.checked; }
        check.addEventListener("change", sync);
        sync();
        ramadanCard.appendChild(el("div", { class: "list-item" },
          el("strong", { text: prayer.name }),
          el("label", { class: "check", attrs: { for: check.id } }, check, "تغيير " + prayer.name + " في رمضان"),
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
      root.appendChild(ramadanCard);

      // ---- preview and apply
      var apply = el("button", { class: "primary", type: "button", text: "معاينة وتطبيق", on: { click: submit } });
      root.appendChild(el("div", { class: "actions" }, apply));

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
        // A switch left as drawn sends nothing; the Eid switch sets both Eids.
        heldRows.forEach(function (row) {
          if (row.check.checked === row.wasChecked) return;
          row.keys.forEach(function (key) {
            partialPrayers[key] = partialPrayers[key] || {};
            partialPrayers[key].held = row.check.checked;
          });
        });
        var adhanChange = adhanScreen.change(errors);
        var khutbaChange = khutba.change(errors);
        if (khutbaChange.changed) {
          partialPrayers.jumua = partialPrayers.jumua || {};
          partialPrayers.jumua.khutba = khutbaChange.value;
        }
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
        if (adhanChange.changed) partial.display = { adhanScreenMinutes: adhanChange.value };
        if (!partial.prayers && !partial.ramadan && !partial.display) {
          ctx.toast("لا تغيير");
          return;
        }
        apply.disabled = true;
        ctx.submit(partial).then(function () { apply.disabled = false; }, function () { apply.disabled = false; });
      }
    }
  });
})();
