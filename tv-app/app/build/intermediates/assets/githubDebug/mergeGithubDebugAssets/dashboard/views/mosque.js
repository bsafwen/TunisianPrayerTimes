/*
 * Mosque TV dashboard: the mosque's name, its place (the prayer times and the weather follow it),
 * the theme and how the screen shows the weather, the night screen, background images and announcements.
 */
(function () {
  "use strict";

  var NAME_MAX = 60;
  var SLIDE = { min: 5, max: 60 };
  var EVERY = { min: 0, max: 120 };

  /** The first value that is not null or undefined. */
  function pick() {
    for (var i = 0; i < arguments.length; i++) {
      if (arguments[i] !== null && arguments[i] !== undefined) return arguments[i];
    }
    return null;
  }

  function option(el, value, text) {
    return el("option", { value: String(value), text: text });
  }

  /** A label above its input, with an optional muted hint below (a node, to change it later, or a text). */
  function field(el, id, text, input, hint) {
    input.id = id;
    if (typeof hint === "string") hint = el("div", { class: "hint", text: hint });
    if (hint) {
      hint.id = id + "-hint";
      input.setAttribute("aria-describedby", hint.id);
    }
    return el("div", { class: "grow" },
      el("label", { text: text, attrs: { for: id } }),
      input,
      hint || null);
  }

  /** A checkbox and its label on one line tall enough to tap, with an optional hint below. */
  function checkbox(el, id, text, checked, hint) {
    var input = el("input", { type: "checkbox", id: id, checked: !!checked });
    if (hint) input.setAttribute("aria-describedby", id + "-hint");
    return {
      input: input,
      node: el("div", null,
        el("label", { class: "check", attrs: { for: id } }, input, text),
        hint ? el("div", { class: "hint", id: id + "-hint", text: hint }) : null)
    };
  }

  function numberInput(el, range, value) {
    return el("input", {
      type: "number", class: "tabular ltr", value: value === null ? "" : String(value),
      attrs: { min: range.min, max: range.max, step: 1, inputmode: "numeric" }
    });
  }

  /** What an input shows, with a value the browser could not read, to tell whether the admin edited it. */
  function inputSnapshot(input) {
    return String(input.value) + (input.validity && input.validity.badInput ? "|bad" : "");
  }

  /** The name as it is saved: trimmed, with single spaces. */
  function cleanName(text) {
    return String(text || "").trim().replace(/\s+/g, " ");
  }

  /** The whole number typed, or an error naming the field and its range. */
  function readNumber(input, range, name) {
    var value = Number(input.value);
    if (input.value === "" || !Number.isInteger(value) || value < range.min || value > range.max) {
      return { error: name + ": عدد صحيح بين " + range.min + " و" + range.max };
    }
    return { value: value };
  }

  function findById(list, id) {
    return (list || []).filter(function (item) { return item.id === id; })[0] || null;
  }

  function render(root, ctx) {
    var el = ctx.el;
    var state = ctx.state;
    var mosque = state.mosque || {};
    var saved = ctx.settingsCopy() || {};
    var display = saved.display || {};
    var places = null;
    // The place the form showed once the list loaded; null until then.
    var placeStart = null;

    // Only what the admin changes is sent, so a change made meanwhile from the remote, a USB key
    // or another phone is not reverted; each field keeps what it showed when the form was drawn.

    // ------------------------------------------------ name
    var nameInput = el("input", {
      type: "text", value: pick(mosque.name, saved.mosque && saved.mosque.name, ""),
      attrs: { maxlength: NAME_MAX, autocomplete: "off" }
    });
    nameInput.className = "wide";
    var nameStart = cleanName(nameInput.value);

    // ------------------------------------------------ place
    var gouvSelect = el("select", { class: "wide", disabled: true }, option(el, "", "جارٍ التحميل…"));
    var delegSelect = el("select", { class: "wide", disabled: true }, option(el, "", "جارٍ التحميل…"));
    var placeNote = el("div", { class: "hint problem", attrs: { role: "status" } });

    function fillDelegations(gouvernorat) {
      var list = gouvernorat ? gouvernorat.delegations || [] : [];
      var current = findById(list, mosque.delegationId);
      delegSelect.textContent = "";
      if (!current) delegSelect.appendChild(option(el, "", list.length ? "اختر المعتمدية" : "اختر الولاية أولًا"));
      list.forEach(function (d) { delegSelect.appendChild(option(el, d.id, d.name)); });
      delegSelect.value = current ? String(current.id) : "";
      delegSelect.disabled = !list.length;
    }

    gouvSelect.addEventListener("change", function () {
      fillDelegations(findById(places, Number(gouvSelect.value)));
    });

    ctx.places().then(function (list) {
      places = Array.isArray(list) ? list : [];
      var current = findById(places, mosque.gouvernoratId) || places.filter(function (g) {
        return !!findById(g.delegations, mosque.delegationId);
      })[0] || null;
      gouvSelect.textContent = "";
      if (!current) gouvSelect.appendChild(option(el, "", "اختر الولاية"));
      places.forEach(function (g) { gouvSelect.appendChild(option(el, g.id, g.name)); });
      gouvSelect.value = current ? String(current.id) : "";
      gouvSelect.disabled = false;
      fillDelegations(current);
      placeStart = { gouvernorat: gouvSelect.value, delegation: delegSelect.value };
    }, function (error) {
      places = null;
      gouvSelect.textContent = "";
      gouvSelect.appendChild(option(el, "", "تعذّر التحميل"));
      delegSelect.textContent = "";
      delegSelect.appendChild(option(el, "", mosque.delegationName || "تعذّر التحميل"));
      placeNote.textContent = "تعذّر تحميل قائمة الأماكن (" + error.message + "): يبقى المكان الحالي.";
    });

    // ------------------------------------------------ display
    var themes = state.themes || [];
    var themeId = pick(mosque.themeId, display.theme);
    var themeSelect = el("select", { class: "wide" });
    if (!findById(themes, themeId)) themeSelect.appendChild(option(el, "", "اختر المظهر"));
    themes.forEach(function (t) { themeSelect.appendChild(option(el, t.id, t.name)); });
    themeSelect.value = findById(themes, themeId) ? themeId : "";
    var themeStart = themeSelect.value;
    // What the chosen look is, when the TV says it («أفق»: the sky of the prayer times; «مداد»: plain ground).
    var themeHint = el("div", { class: "hint" });
    function describeTheme() {
      var theme = findById(themes, themeSelect.value);
      themeHint.textContent = theme && typeof theme.description === "string" ? theme.description : "";
    }
    themeSelect.addEventListener("change", describeTheme);
    describeTheme();

    var weatherNow = state.weather || {};
    // What the form shows first; each option is sent only if the admin changes it, so saving
    // the name neither pins these guessed defaults nor reverts a change made meanwhile on the TV.
    var initial = {
      weather: !!pick(display.weather, weatherNow.enabled, true),
      nightScreen: !!pick(display.nightScreen, true),
      backgrounds: !!pick(display.backgrounds, true),
      announcements: !!pick(display.announcements, true),
      slideSeconds: pick(display.slideSeconds, 15),
      announcementsEveryMinutes: pick(display.announcementsEveryMinutes, 15)
    };
    var weather = checkbox(el, "mosque-weather", "عرض الطقس عند الاتصال بالإنترنت", initial.weather);
    var nightScreen = checkbox(el, "mosque-night", "شاشة الليل الخافتة", initial.nightScreen,
      "ليلًا بين العشاء والفجر: الساعة وموعد الفجر فقط على أرضية سوداء، تتنقّل كل بضع دقائق حفاظًا على الشاشة.");
    var backgrounds = checkbox(el, "mosque-backgrounds", "صور الخلفية", initial.backgrounds);
    var announcements = checkbox(el, "mosque-announcements", "الإعلانات", initial.announcements);
    var slideInput = numberInput(el, SLIDE, initial.slideSeconds);
    var everyInput = numberInput(el, EVERY, initial.announcementsEveryMinutes);
    var slideStart = inputSnapshot(slideInput);
    var everyStart = inputSnapshot(everyInput);

    // ------------------------------------------------ save
    var saveButton = el("button", { type: "button", class: "primary", text: "معاينة وحفظ" });

    function save() {
      var errors = [];
      var partialMosque = {};
      var partialDisplay = {};

      var name = cleanName(nameInput.value);
      if (name !== nameStart) {
        // An empty name is allowed: the screen then shows the word "مسجد".
        if (name.length > NAME_MAX) errors.push("اسم المسجد " + NAME_MAX + " حرفًا على الأكثر");
        else partialMosque.name = name;
      }

      // The place is sent only when the admin picked another one; a list that did not load keeps it.
      if (places && places.length && placeStart &&
          (gouvSelect.value !== placeStart.gouvernorat || delegSelect.value !== placeStart.delegation)) {
        if (!delegSelect.value) errors.push("اختر الولاية ثم المعتمدية");
        else if (delegSelect.value !== placeStart.delegation) partialMosque.delegation = Number(delegSelect.value);
      }

      if (themeSelect.value && themeSelect.value !== themeStart) partialDisplay.theme = themeSelect.value;

      var boxes = { weather: weather, nightScreen: nightScreen, backgrounds: backgrounds, announcements: announcements };
      Object.keys(boxes).forEach(function (key) {
        if (boxes[key].input.checked !== initial[key]) partialDisplay[key] = boxes[key].input.checked;
      });

      if (inputSnapshot(slideInput) !== slideStart) {
        var slide = readNumber(slideInput, SLIDE, "مدة عرض كل إعلان");
        if (slide.error) errors.push(slide.error);
        else if (slide.value !== initial.slideSeconds) partialDisplay.slideSeconds = slide.value;
      }
      if (inputSnapshot(everyInput) !== everyStart) {
        var every = readNumber(everyInput, EVERY, "تواتر الإعلانات بين الصلوات");
        if (every.error) errors.push(every.error);
        else if (every.value !== initial.announcementsEveryMinutes) partialDisplay.announcementsEveryMinutes = every.value;
      }

      if (errors.length) {
        ctx.toast(errors.join(" · "), "error");
        return;
      }

      var partial = {};
      if (Object.keys(partialMosque).length) partial.mosque = partialMosque;
      if (Object.keys(partialDisplay).length) partial.display = partialDisplay;
      if (!partial.mosque && !partial.display) {
        ctx.toast("لا تغيير");
        return;
      }

      saveButton.disabled = true;
      ctx.submit(partial).then(function () {
        saveButton.disabled = false;
      }, function (error) {
        saveButton.disabled = false;
        ctx.toast(error.message, "error");
      });
    }
    saveButton.addEventListener("click", save);

    // ------------------------------------------------ layout
    root.appendChild(el("section", { class: "card" },
      el("h2", { text: "المسجد" }),
      field(el, "mosque-name-input", "اسم المسجد", nameInput, "يظهر أعلى الشاشة، " + NAME_MAX + " حرفًا على الأكثر.")));

    root.appendChild(el("section", { class: "card" },
      el("h2", { text: "المكان" }),
      el("div", { class: "row" },
        field(el, "mosque-gouvernorat", "الولاية", gouvSelect),
        field(el, "mosque-delegation", "المعتمدية", delegSelect)),
      el("div", { class: "hint", text: "تنبيه: تغيير المكان يغيّر مواقيت الصلاة على الشاشة، والطقس يتبع المكان نفسه." }),
      placeNote));

    root.appendChild(el("section", { class: "card" },
      el("h2", { text: "العرض" }),
      themes.length ? field(el, "mosque-theme", "المظهر", themeSelect, themeHint) : null,
      weather.node,
      weatherNow.text ? el("div", { class: "hint", text: "الطقس الآن: " + weatherNow.text + (weatherNow.updated ? " (" + weatherNow.updated + ")" : "") + " · Open-Meteo.com" }) : null,
      nightScreen.node,
      backgrounds.node,
      announcements.node,
      el("div", { class: "row" },
        field(el, "mosque-slide", "مدة عرض كل إعلان بالثواني", slideInput, "من " + SLIDE.min + " إلى " + SLIDE.max),
        field(el, "mosque-every", "عرض الإعلانات كل … دقيقة بين الصلوات، 0 = بعد الصلاة فقط", everyInput,
          "من " + EVERY.min + " إلى " + EVERY.max))));

    root.appendChild(el("div", { class: "actions" }, saveButton));
  }

  Dashboard.registerView({ id: "mosque", title: "المسجد", render: render });
})();
