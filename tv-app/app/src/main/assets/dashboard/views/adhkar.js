/*
 * Adhkar: the two lists the screen shows, after the prayer and in the ticker. Each list mixes the
 * reviewed texts that ship with the app (GET /api/adhkar, loaded once per page) and the mosque's own
 * texts; the admin orders, hides, restores and adds them. Texts come from users and files: the DOM
 * is built with ctx.el and textContent only (never HTML).
 */
(function () {
  "use strict";

  var MAX_ITEMS = 100;
  var MAX_TEXT = 1000;
  var MAX_REFERENCE = 200;
  var MAX_COUNT = 1000;
  var MAX_RESULTS = 30;
  // The TV refuses an after-prayer list longer than this.
  var MAX_AFTER_SALAH_MINUTES = 30;
  // A long text said several times after the prayer is shown whole again each time, 3 times at most.
  var MAX_PAGED_REPETITIONS = 3;
  // The ticker holds each page or step at least this long (longer while a long line scrolls).
  var TICKER_MIN_SLIDE_MILLIS = 12000;
  var SECTIONS = [
    { key: "afterSalah", title: "أذكار بعد الصلاة", category: "SALAH",
      hint: "تظهر على الشاشة بعد كل صلاة مفروضة، بهذا الترتيب." },
    { key: "ticker", title: "شريط الأذكار", category: null,
      hint: "تمرّ في الشريط تحت مواقيت الصلاة بهذا الترتيب، ويُقرأ كل نص مرة في كل دورة." }
  ];

  // The library never changes while the TV runs: loaded once per page.
  var library = null;
  var loading = null;

  // The lists being edited survive a re-render (another tab) until the TV's own adhkar change.
  var draft = null;
  var draftBase = null;
  // Per list, what the page would send for the TV's own list (JSON): a list still equal to it is not sent.
  var draftFile = null;
  var nextUid = 1;
  // The library picker: open under one list at a time, with its category and search.
  var picker = null;

  // ---------------------------------------------------------------- the library

  function loadLibrary(ctx) {
    if (library) return Promise.resolve(library);
    if (!loading) {
      // Forgotten once settled, whatever happened (an unexpected answer included), so a retry asks again.
      loading = ctx.api.get("/api/adhkar").then(function (data) {
        loading = null;
        library = indexLibrary(data);
        // The TV says how long it lets the adhkar after the prayer last (30 minutes today).
        if (typeof data.afterSalahMaxMinutes === "number" && data.afterSalahMaxMinutes > 0) MAX_AFTER_SALAH_MINUTES = data.afterSalahMaxMinutes;
        return library;
      }, function (error) {
        loading = null;
        throw error;
      });
    }
    return loading;
  }

  function indexLibrary(data) {
    if (!data || !data.lists || !Array.isArray(data.entries)) throw new Error("ردّ غير متوقَّع من الشاشة");
    function ids(list) { return Array.isArray(list) ? list.filter(function (id) { return typeof id === "string"; }) : []; }
    var entries = data.entries.filter(function (entry) { return entry && typeof entry.id === "string"; });
    var byId = Object.create(null);
    var search = Object.create(null);
    entries.forEach(function (entry) {
      byId[entry.id] = entry;
      search[entry.id] = normalize([entry.title, entry.text, entry.reference, entry.id].join(" "));
    });
    return {
      entries: entries,
      byId: byId,
      search: search,
      categories: (Array.isArray(data.categories) ? data.categories : []).filter(function (c) { return c && typeof c.id === "string"; }),
      lists: { afterSalah: ids(data.lists.afterSalah), ticker: ids(data.lists.ticker) }
    };
  }

  /**
   * Arabic text as searched: without tashkeel, tatweel and Quranic marks, with one alef, ي for ى and
   * ه for ة, Latin in lower case, spaces collapsed. A query without tashkeel finds a text with it.
   */
  function normalize(text) {
    return String(text || "")
      .replace(/[\u064B-\u065F\u0670\u0640\u06D6-\u06ED]/g, "")
      .replace(/[\u0623\u0625\u0622\u0671]/g, "\u0627")
      .replace(/\u0649/g, "\u064A")
      .replace(/\u0629/g, "\u0647")
      .toLowerCase()
      .replace(/\s+/g, " ")
      .trim();
  }

  /** The first `max` characters of a text on one line, cut between words, with "…" when cut. */
  function shorten(text, max) {
    var line = String(text || "").replace(/\s+/g, " ").trim();
    if (line.length <= max) return line;
    var cut = line.slice(0, max);
    var space = cut.lastIndexOf(" ");
    return (space > max / 2 ? cut.slice(0, space) : cut) + "…";
  }

  // ---------------------------------------------------------------- how long the texts last (the TV's pacer)

  var PAGE_CHARS = 260;
  var PAGE_BREAK = /(\u06DD[0-9\u0660-\u0669]+|[\u06D6\u06D7\u06DA\u060C,.\u061B;\u061F?!:\n]+)\s*/g;
  var SPACES = /\s+/g;

  function paceMillis(text, count) {
    var words = String(text).split(/\s+/).filter(function (word) { return word; }).length;
    var perRepetition = Math.max(2500, 450 * words);
    return Math.min(150000, Math.max(6000, perRepetition * Math.max(1, count)));
  }

  /** A long text split as the TV splits it: pages of at most 260 characters, never inside a word. */
  function pages(text) {
    if (text.length <= PAGE_CHARS) return [text];
    function ends(pattern) {
      var found = [];
      var match;
      pattern.lastIndex = 0;
      while ((match = pattern.exec(text))) {
        var end = match.index + match[0].length;
        if (end >= 1 && end < text.length) found.push(end);
        if (!match[0].length) pattern.lastIndex++;
      }
      return found;
    }
    var breaks = ends(PAGE_BREAK);
    var spaces = ends(SPACES);
    var result = [];
    var start = 0;
    function inReach(cuts) {
      for (var i = cuts.length - 1; i >= 0; i--) if (cuts[i] > start && cuts[i] - start <= PAGE_CHARS) return cuts[i];
      return -1;
    }
    while (text.length - start > PAGE_CHARS) {
      var cut = inReach(breaks);
      if (cut < 0) cut = inReach(spaces);
      if (cut < 0) break;
      result.push(text.slice(start, cut));
      start = cut;
    }
    result.push(text.slice(start));
    result = result.filter(function (page) { return page.trim(); });
    return result.length ? result : [text];
  }

  function sumPages(text) {
    return pages(text).reduce(function (sum, page) { return sum + paceMillis(page, 1); }, 0);
  }

  function number(value) { return typeof value === "number" && isFinite(value) ? value : 0; }

  /** How many times a reviewed text is said after the prayer, as the library says. */
  function libraryCount(entry) { return number(entry.count) || 1; }

  /** A count typed in a field: a whole number in 1..1000, or NaN. */
  function readCount(value) {
    var text = String(value === undefined || value === null ? "" : value).trim();
    if (!/^\d+$/.test(text)) return NaN;
    var n = Number(text);
    return n >= 1 && n <= MAX_COUNT ? n : NaN;
  }

  /**
   * A text said `count` times after the prayer: one page is paced for all its repetitions; a long
   * text is shown whole, page by page, once for each repetition (3 times at most).
   */
  function repeatedMillis(text, count) {
    return pages(text).length === 1 ? paceMillis(text, count) : sumPages(text) * Math.min(count, MAX_PAGED_REPETITIONS);
  }

  /** The ticker reads a text once, each page at least 12 s. */
  function tickerPagesMillis(text) {
    return pages(text).reduce(function (sum, page) { return sum + Math.max(TICKER_MIN_SLIDE_MILLIS, paceMillis(page, 1)); }, 0);
  }

  /** About how long the TV shows one item of a list, in milliseconds (in the ticker, the least). */
  function itemMillis(key, item) {
    if (item.own) {
      var text = item.text.trim();
      if (!text) return 0;
      if (key === "ticker") return tickerPagesMillis(text);
      return repeatedMillis(text, readCount(item.count) || 1);
    }
    var entry = library.byId[item.id];
    if (!entry) return 0;
    if (key === "ticker") return number(entry.tickerMillis);
    var n = readCount(item.count);
    if (!countChanged(key, item) || isNaN(n)) return number(entry.afterSalahMillis);
    return repeatedMillis(String(entry.text || ""), n);
  }

  function listMillis(key, list) {
    return list.reduce(function (sum, item) { return sum + itemMillis(key, item); }, 0);
  }

  /** True when the TV would refuse the list for lasting too long after the prayer. */
  function tooLong(key, list) {
    return key === "afterSalah" && listMillis(key, list) > MAX_AFTER_SALAH_MINUTES * 60000;
  }

  /** "نحو 4 د", or "نحو 40 ث" under a minute: rounded up. */
  function about(millis) {
    return millis < 60000 ? "نحو " + Math.ceil(millis / 1000) + " ث" : "نحو " + Math.ceil(millis / 60000) + " د";
  }

  /** "1 د", or "40 ث" under a minute: rounded down, for a least time. */
  function atLeast(millis) {
    return millis < 60000 ? Math.floor(millis / 1000) + " ث" : Math.floor(millis / 60000) + " د";
  }

  /** "نص واحد", "نصان", "3 نصوص", "11 نصًا", "100 نص", as the TV writes it (by the last two digits). */
  function texts(count) {
    if (count === 1) return "نص واحد";
    if (count === 2) return "نصان";
    var rest = count % 100;
    if (rest >= 3 && rest <= 10) return count + " نصوص";
    if (rest >= 11) return count + " نصًا";
    return count + " نص";
  }

  // ---------------------------------------------------------------- the working lists

  function reviewedItem(id, count) {
    var entry = library.byId[id];
    var start = count !== undefined && count !== null ? count : entry ? libraryCount(entry) : 1;
    return { uid: nextUid++, id: id, count: String(start) };
  }

  function ownItem(text, reference, count) {
    return { uid: nextUid++, own: true, text: text, reference: reference, count: count };
  }

  function bundledItems(key) {
    return library.lists[key].map(function (id) { return reviewedItem(id); });
  }

  function fromFile(key, item) {
    item = item && typeof item === "object" ? item : {};
    if (item.id !== undefined && item.id !== null) {
      // Only the list after the prayer keeps a count of its own for a reviewed text.
      return reviewedItem(String(item.id), key === "afterSalah" && typeof item.count === "number" ? item.count : null);
    }
    // In the ticker the count is never shown, read or sent: every text there is read once.
    return ownItem(String(item.text || ""), String(item.reference || ""),
      item.count === undefined || item.count === null ? "1" : String(item.count));
  }

  /** A list as the screen shows it: null is the bundled list; a bare array or "append" follows it. */
  function workingList(key, value) {
    if (value === null || value === undefined || typeof value !== "object") return bundledItems(key);
    if (Array.isArray(value)) return bundledItems(key).concat(value.map(function (item) { return fromFile(key, item); }));
    var items = (Array.isArray(value.items) ? value.items : []).map(function (item) { return fromFile(key, item); });
    var mode = String(value.mode || "").trim().toLowerCase();
    return mode === "replace" || mode === "استبدال" ? items : bundledItems(key).concat(items);
  }

  function startDraft(settings, force) {
    var adhkar = settings && settings.adhkar && typeof settings.adhkar === "object" && !Array.isArray(settings.adhkar) ? settings.adhkar : {};
    var base = JSON.stringify(adhkar);
    if (draft && draftBase === base && !force) return;
    draftBase = base;
    draft = {};
    draftFile = {};
    SECTIONS.forEach(function (section) {
      draft[section.key] = workingList(section.key, adhkar[section.key]);
      draftFile[section.key] = JSON.stringify(fileList(section.key, draft[section.key]));
    });
  }

  /** True when a reviewed text after the prayer is said another number of times than in the library. */
  function countChanged(key, item) {
    if (key !== "afterSalah" || item.own) return false;
    var entry = library.byId[item.id];
    if (!entry || (entry.steps && entry.steps.length)) return false;
    return readCount(item.count) !== libraryCount(entry);
  }

  /** True when the list starts with the whole bundled list, unchanged. */
  function startsWithBundled(key, list) {
    var bundled = library.lists[key];
    if (list.length < bundled.length) return false;
    for (var i = 0; i < bundled.length; i++) {
      var item = list[i];
      if (item.own || item.id !== bundled[i] || countChanged(key, item)) return false;
    }
    return true;
  }

  function isBundled(key, list) {
    return list.length === library.lists[key].length && startsWithBundled(key, list);
  }

  function has(list, id) {
    return list.some(function (item) { return !item.own && item.id === id; });
  }

  /** The bundled texts the list no longer shows, in their bundled order. */
  function hiddenIds(key, list) {
    return library.lists[key].filter(function (id) { return !has(list, id); });
  }

  /**
   * Where a hidden bundled text goes back: before the first later bundled text still shown, else
   * right after the last earlier one (so the mosque's own texts stay after the bundled ones), else first.
   */
  function restorePosition(key, list, id) {
    var bundled = library.lists[key];
    var index = bundled.indexOf(id);
    function position(other) {
      for (var i = 0; i < list.length; i++) if (!list[i].own && list[i].id === other) return i;
      return -1;
    }
    for (var later = index + 1; later < bundled.length; later++) {
      var at = position(bundled[later]);
      if (at >= 0) return at;
    }
    var after = -1;
    for (var earlier = 0; earlier < index; earlier++) after = Math.max(after, position(bundled[earlier]));
    return after + 1;
  }

  /**
   * "النصوص المضمّنة · 8 نصوص · نحو 4 د بعد الصلاة": where the list comes from, its size and length
   * (with what is wrong when the TV would refuse it for being too long).
   */
  function summaryText(section, list) {
    var key = section.key;
    var parts = [];
    if (isBundled(key, list)) parts.push("النصوص المضمّنة");
    else if (startsWithBundled(key, list)) parts.push("النصوص المضمّنة و" + texts(list.length - library.lists[key].length) + " بعدها");
    else parts.push("قائمة المسجد");
    parts.push(list.length ? texts(list.length) : "لا نص");
    var millis = listMillis(key, list);
    if (millis > 0 && key === "afterSalah") {
      parts.push(about(millis) + " بعد الصلاة" + (tooLong(key, list)
        ? " — أطول من " + MAX_AFTER_SALAH_MINUTES + " د، احذف بعض النصوص أو قلّل العدد" : ""));
    } else if (millis > 0) {
      // Scrolling a long line and the announcements between the texts add to it.
      parts.push(atLeast(millis) + " على الأقل لدورة واحدة من الشريط");
    }
    return parts.join(" · ");
  }

  function summaryClass(section, list) {
    return "muted" + (tooLong(section.key, list) ? " problem" : "");
  }

  // ---------------------------------------------------------------- saving

  /** The first mistake of a list, or null. */
  function problem(section, list) {
    var key = section.key;
    if (isBundled(key, list)) return null;
    if (!list.length) return section.title + ": القائمة فارغة: أضف نصاً أو عُد إلى القائمة المضمّنة";
    var mistake = itemProblem(section, list);
    if (mistake) return mistake;
    if (tooLong(key, list)) {
      return section.title + ": " + about(listMillis(key, list)) + "، أطول من " + MAX_AFTER_SALAH_MINUTES +
        " د: احذف بعض النصوص أو قلّل العدد";
    }
    return null;
  }

  /** The first mistake in the texts of a list, or null. */
  function itemProblem(section, list) {
    var key = section.key;
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      var where = section.title + " · النص " + (i + 1) + ": ";
      if (!item.own) {
        var entry = library.byId[item.id];
        if (!entry) return where + "غير موجود في مكتبة التطبيق: احذفه";
        if (key === "afterSalah" && !(entry.steps && entry.steps.length) && isNaN(readCount(item.count))) {
          return where + "العدد عدد صحيح من 1 إلى " + MAX_COUNT;
        }
        continue;
      }
      var text = item.text.trim();
      var reference = item.reference.trim();
      if (!text) return where + "النص فارغ";
      if (text.length > MAX_TEXT) return where + "النص أطول من " + MAX_TEXT + " حرف";
      if (!reference) return where + "المصدر مطلوب";
      if (reference.length > MAX_REFERENCE) return where + "المصدر أطول من " + MAX_REFERENCE + " حرف";
      if (key !== "ticker" && isNaN(readCount(item.count))) return where + "العدد عدد صحيح من 1 إلى " + MAX_COUNT;
    }
    return null;
  }

  function fileItem(key, item) {
    var out;
    if (item.own) {
      out = { text: item.text.trim(), reference: item.reference.trim() };
      // The ticker reads every text once: the TV refuses a count there.
      if (key !== "ticker") out.count = readCount(item.count);
      return out;
    }
    out = { id: item.id };
    if (countChanged(key, item)) out.count = readCount(item.count);
    return out;
  }

  /** A list for the settings file: null (the bundled list), "append" after it, or "replace". */
  function fileList(key, list) {
    if (isBundled(key, list)) return null;
    var items = list.map(function (item) { return fileItem(key, item); });
    if (startsWithBundled(key, list)) return { mode: "append", items: items.slice(library.lists[key].length) };
    return { mode: "replace", items: items };
  }

  /**
   * The adhkar section to send, with only the lists the admin changed (so a change made meanwhile on
   * the TV to the other one stays), or null after showing the first mistake or that nothing changed.
   */
  function collect(ctx) {
    var out = {};
    var changed = false;
    for (var s = 0; s < SECTIONS.length; s++) {
      var section = SECTIONS[s];
      var list = draft[section.key];
      var built = fileList(section.key, list);
      if (JSON.stringify(built) === draftFile[section.key]) continue;
      var mistake = problem(section, list);
      if (!mistake && built && built.items.length > MAX_ITEMS) mistake = section.title + ": " + MAX_ITEMS + " نص على الأكثر";
      if (mistake) {
        ctx.toast(mistake, "error");
        return null;
      }
      out[section.key] = built;
      changed = true;
    }
    if (!changed) {
      ctx.toast("لا تغيير");
      return null;
    }
    return out;
  }

  // ---------------------------------------------------------------- drawing

  /** The whole text in a <details> (its first words as the summary); a short text as it is. */
  function textBlock(el, entry) {
    var text = String(entry.text || "");
    var steps = Array.isArray(entry.steps) ? entry.steps : [];
    var preview = shorten(text, 90);
    if (!steps.length && preview === text.replace(/\s+/g, " ").trim()) return el("p", { class: "dhikr-text", text: text });
    var body = steps.length
      ? el("ol", { class: "dhikr-text" }, steps.map(function (step) { return el("li", { text: String(step.text) + " × " + step.count }); }))
      : el("p", { class: "dhikr-text", text: text });
    var summary = el("summary", { text: preview });
    var details = el("details", { class: "dhikr" }, summary, body);
    details.addEventListener("toggle", function () { summary.textContent = details.open ? "إخفاء النص" : preview; });
    return details;
  }

  /** A library id, small and left to right, for an admin who edits the USB file by hand. */
  function idLine(el, id) {
    return el("span", { class: "muted id", text: id, attrs: { dir: "ltr" } });
  }

  /** Deletes the text at `i`; focus goes to the next text, else the one before, else the list's heading. */
  function removeAt(v, key, list, i) {
    list.splice(i, 1);
    var next = list[i] || list[i - 1];
    v.paint(next ? { uid: next.uid, what: "row" } : { uid: key, what: "heading" });
  }

  /** Up, down and delete for a row; `name` says which text for a screen reader. */
  function rowButtons(v, key, list, item, i, name) {
    var el = v.el;
    function move(delta) {
      var to = i + delta;
      list.splice(i, 1);
      list.splice(to, 0, item);
      // Focus follows the text; at the top or the bottom the other button is the one still usable.
      var what = delta < 0 ? (to === 0 ? "down" : "up") : (to === list.length - 1 ? "up" : "down");
      v.paint({ uid: item.uid, what: what });
    }
    return el("div", { class: "row" },
      v.track(el("button", {
        type: "button", text: "أعلى", disabled: i === 0, attrs: { "aria-label": "نقل " + name + " إلى الأعلى" },
        on: { click: function () { move(-1); } }
      }), item.uid, "up"),
      v.track(el("button", {
        type: "button", text: "أسفل", disabled: i === list.length - 1, attrs: { "aria-label": "نقل " + name + " إلى الأسفل" },
        on: { click: function () { move(1); } }
      }), item.uid, "down"),
      el("button", {
        type: "button", class: "danger", text: "حذف", attrs: { "aria-label": "حذف " + name },
        on: { click: function () {
          if (item.own && (item.text.trim() || item.reference.trim()) && !confirm("حذف هذا النص؟")) return;
          removeAt(v, key, list, i);
        } }
      }));
  }

  function reviewedRow(v, section, list, item, i, refresh) {
    var el = v.el;
    var entry = library.byId[item.id];
    if (!entry) {
      return el("div", { class: "list-item level-BAD" },
        el("strong", { text: (i + 1) + ". نص غير موجود في مكتبة التطبيق" }),
        el("div", { class: "muted ltr", text: item.id }),
        el("div", { class: "row" }, el("button", {
          type: "button", class: "danger", text: "حذف", attrs: { "aria-label": "حذف النص " + (i + 1) },
          on: { click: function () { removeAt(v, section.key, list, i); } }
        })));
    }
    var id = "adhkar-" + section.key + "-" + item.uid;
    var steps = entry.steps && entry.steps.length;
    var count = null;
    if (section.key === "afterSalah" && !steps) {
      // A long text is not said in parts: it is shown whole again for each repetition, 3 times at most.
      var hint = pages(String(entry.text || "")).length > 1
        ? el("span", { id: id + "-hint", class: "muted", text: "النص الطويل يُعرض كاملًا، حتى " + MAX_PAGED_REPETITIONS + " مرات" })
        : null;
      count = el("div", { class: "row" },
        el("label", { text: "العدد (في المكتبة: " + libraryCount(entry) + ")", attrs: { for: id + "-count" } }),
        el("input", {
          id: id + "-count", type: "number", value: item.count, class: "tabular",
          attrs: { min: 1, max: MAX_COUNT, step: 1, inputmode: "numeric", required: true,
            "aria-describedby": id + "-title" + (hint ? " " + hint.id : "") },
          on: { input: function (event) { item.count = event.target.value; refresh(); } }
        }),
        hint);
    } else if (section.key === "afterSalah") {
      count = el("p", { class: "muted", text: "يُقال بالعدد المذكور في خطواته." });
    }
    return el("div", { class: "list-item" },
      el("div", { class: "dhikr-head" },
        el("strong", { id: id + "-title", text: (i + 1) + ". " + entry.title }),
        el("span", { class: "badge reviewed", text: "نص مراجَع" }),
        idLine(el, entry.id)),
      el("div", { class: "muted", text: entry.reference }),
      textBlock(el, entry),
      count,
      rowButtons(v, section.key, list, item, i, "«" + entry.title + "»"));
  }

  function ownRow(v, section, list, item, i, refresh) {
    var el = v.el;
    var id = "adhkar-" + section.key + "-" + item.uid;
    function bind(key) { return { input: function (event) { item[key] = event.target.value; refresh(); } }; }
    // The ticker reads every text once: no count there.
    var count = section.key === "ticker" ? null : el("div", {},
      el("label", { text: "العدد", attrs: { for: id + "-count" } }),
      el("input", {
        id: id + "-count", type: "number", value: item.count, class: "tabular",
        attrs: { min: 1, max: MAX_COUNT, step: 1, inputmode: "numeric", required: true }, on: bind("count")
      }));
    return el("div", { class: "list-item" },
      el("div", { class: "dhikr-head" },
        el("strong", { text: (i + 1) + "." }),
        el("span", { class: "badge own", text: "نص المسجد" })),
      el("label", { text: "النص (حتى " + MAX_TEXT + " حرف)", attrs: { for: id + "-text" } }),
      v.track(el("textarea", {
        id: id + "-text", value: item.text, attrs: { maxlength: MAX_TEXT, rows: 4, dir: "auto" }, on: bind("text")
      }), item.uid, "text"),
      el("div", { class: "row" },
        el("div", { class: "grow" },
          el("label", { text: "المصدر", attrs: { for: id + "-reference" } }),
          el("input", {
            id: id + "-reference", type: "text", value: item.reference,
            attrs: { maxlength: MAX_REFERENCE, required: true, placeholder: "مثال: رواه مسلم", style: "width: 100%" }, on: bind("reference")
          })),
        count),
      rowButtons(v, section.key, list, item, i, "النص " + (i + 1)));
  }

  function hiddenBlock(v, section, list) {
    var el = v.el;
    var hidden = hiddenIds(section.key, list);
    if (!hidden.length) return null;
    return el("div", {},
      el("h3", { text: "نصوص مضمّنة غير معروضة" }),
      hidden.map(function (id) {
        var title = library.byId[id] ? library.byId[id].title : id;
        return el("div", { class: "row" },
          el("span", { class: "grow", text: title }),
          el("button", {
            type: "button", text: "إعادة", attrs: { "aria-label": "إعادة «" + title + "» إلى القائمة" },
            on: { click: function () {
              var restored = reviewedItem(id);
              list.splice(restorePosition(section.key, list, id), 0, restored);
              v.paint({ uid: restored.uid, what: "row" });
            } }
          }));
      }));
  }

  function resultRow(v, section, list, entry) {
    var el = v.el;
    var present = has(list, entry.id);
    return el("div", { class: "list-item" },
      el("div", { class: "dhikr-head" },
        el("strong", { text: entry.title }),
        idLine(el, entry.id)),
      el("div", { class: "muted", text: entry.reference }),
      el("p", { class: "dhikr-text", text: shorten(entry.text, 80) }),
      el("div", { class: "row" }, el("button", {
        type: "button", text: present ? "في القائمة" : "إضافة", disabled: present,
        attrs: { "aria-label": present ? "«" + entry.title + "» في القائمة" : "إضافة «" + entry.title + "» إلى " + section.title },
        on: { click: function () {
          list.push(reviewedItem(entry.id));
          v.ctx.toast("أُضيف «" + entry.title + "»", "ok");
          // This button is now «في القائمة» (disabled). With a keyboard the search stays focused to add
          // more; on a phone that would open its keyboard again after every addition, so focus goes to the
          // count of results instead.
          var touch = typeof window !== "undefined" && window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
          v.paint({ uid: section.key, what: touch ? "count" : "search" });
        } }
      })));
  }

  /**
   * The results for the picker's search and category. They are not read aloud as they change (every
   * letter typed would queue them all): `status` says how many there are.
   */
  function fillResults(v, section, list, results, status) {
    var el = v.el;
    var words = normalize(picker.query).split(" ").filter(function (word) { return word; });
    var matching = library.entries.filter(function (entry) {
      var haystack = library.search[entry.id];
      return words.every(function (word) { return haystack.indexOf(word) >= 0; });
    });
    var found = matching.filter(function (entry) {
      return !picker.category || (entry.categories || []).indexOf(picker.category) >= 0;
    });
    var howMany = found.length ? texts(found.length) : "لا نتائج";
    // Set only when it changes, so a screen reader does not repeat it for every letter.
    if (status.textContent !== howMany) status.textContent = howMany;
    results.textContent = "";
    if (!found.length) {
      results.appendChild(el("p", { class: "muted", text: matching.length
        ? "لا نص يطابق البحث في هذه الفئة، وفي الفئات الأخرى " + texts(matching.length) + ": اختر «الكل»."
        : "لا نص يطابق البحث." }));
      return;
    }
    found.slice(0, MAX_RESULTS).forEach(function (entry) { results.appendChild(resultRow(v, section, list, entry)); });
    if (found.length > MAX_RESULTS) {
      results.appendChild(el("p", { class: "muted", text:
        "لم تُعرض " + (found.length - MAX_RESULTS) + " من النتائج الأخرى: ضيّق البحث بكلمة أو بفئة." }));
    }
  }

  function pickerPanel(v, section, list) {
    var el = v.el;
    var id = "adhkar-" + section.key;
    // The list's own category first (SALAH after the prayer), then the others in the library's order.
    var categories = library.categories.filter(function (c) { return c.id === section.category; })
      .concat(library.categories.filter(function (c) { return c.id !== section.category; }));
    var select = el("select", { id: id + "-category" },
      el("option", { value: "", text: "الكل" }),
      categories.map(function (c) { return el("option", { value: c.id, text: String(c.title || c.id) }); }));
    select.value = picker.category;
    var search = el("input", {
      id: id + "-search", type: "search", value: picker.query,
      attrs: { placeholder: "كلمة من العنوان أو النص أو المصدر", autocomplete: "off" }
    });
    var status = v.track(el("p", { class: "muted", attrs: { role: "status", tabindex: "-1" } }), section.key, "count");
    var results = el("div", { attrs: { "aria-live": "off" } });
    select.addEventListener("change", function () { picker.category = select.value; fillResults(v, section, list, results, status); });
    search.addEventListener("input", function () { picker.query = search.value; fillResults(v, section, list, results, status); });
    fillResults(v, section, list, results, status);
    return el("div", { class: "panel" },
      el("h3", { text: "إضافة من مكتبة التطبيق" }),
      el("div", { class: "row" },
        el("div", { class: "grow" }, el("label", { text: "الفئة", attrs: { for: select.id } }), v.track(select, section.key, "picker")),
        el("div", { class: "grow" }, el("label", { text: "بحث", attrs: { for: search.id } }), v.track(search, section.key, "search"))),
      status,
      results,
      el("div", { class: "row" }, el("button", {
        type: "button", text: "إغلاق", attrs: { "aria-label": "إغلاق مكتبة التطبيق" },
        on: { click: function () { picker = null; v.paint({ uid: section.key, what: "library" }); } }
      })));
  }

  function sectionCard(v, section) {
    var el = v.el;
    var key = section.key;
    var list = draft[key];
    var summary = el("p", { class: summaryClass(section, list), text: summaryText(section, list) });
    function refresh() {
      summary.textContent = summaryText(section, list);
      summary.className = summaryClass(section, list);
    }
    return el("section", { class: "card" },
      // Focusable from the page only: where focus goes once the last text of the list is deleted.
      v.track(el("h2", { text: section.title, attrs: { tabindex: "-1" } }), key, "heading"),
      el("p", { class: "muted", text: section.hint }),
      list.length ? null : el("p", { class: "muted", text: "لا نص في هذه القائمة: أضف نصاً أو عُد إلى القائمة المضمّنة." }),
      list.map(function (item, i) {
        return v.track(item.own ? ownRow(v, section, list, item, i, refresh) : reviewedRow(v, section, list, item, i, refresh),
          item.uid, "row");
      }),
      summary,
      hiddenBlock(v, section, list),
      el("div", { class: "row" },
        v.track(el("button", {
          type: "button", text: "إضافة من المكتبة", attrs: { "aria-expanded": picker && picker.key === key ? "true" : "false" },
          on: { click: function () {
            // A disclosure button: pressed again, it closes the panel (and keeps nothing typed in it).
            if (picker && picker.key === key) {
              picker = null;
              v.paint({ uid: key, what: "library" });
              return;
            }
            picker = { key: key, category: categoryOf(section), query: "" };
            v.paint({ uid: key, what: "picker" });
          } }
        }), key, "library"),
        el("button", { type: "button", text: "إضافة نص خاص", on: { click: function () {
          var item = ownItem("", "", "1");
          list.push(item);
          v.paint({ uid: item.uid, what: "text" });
        } } }),
        v.track(el("button", { type: "button", text: "العودة إلى القائمة المضمّنة", on: { click: function () {
          if (!isBundled(key, list) && !confirm("إعادة «" + section.title + "» إلى النصوص المضمّنة؟ تُحذف تغييرات هذه القائمة.")) return;
          draft[key] = bundledItems(key);
          v.paint({ uid: key, what: "bundled" });
        } } }), key, "bundled")),
      picker && picker.key === key ? pickerPanel(v, section, list) : null);
  }

  /** The list's own category when the library has it (SALAH after the prayer), else all. */
  function categoryOf(section) {
    return library.categories.some(function (c) { return c.id === section.category; }) ? section.category : "";
  }

  /** The first control of a row that can take focus (a field, a button, a text's summary), or null. */
  function firstControl(node) {
    var tag = String(node.tagName || "").toLowerCase();
    if (/^(input|textarea|select|button|summary)$/.test(tag) && !node.disabled) return node;
    var children = node.children || [];
    for (var i = 0; i < children.length; i++) {
      var found = firstControl(children[i]);
      if (found) return found;
    }
    return null;
  }

  function editor(box, ctx) {
    var el = ctx.el;
    startDraft(ctx.settings, false);
    var sections = el("div");
    var focusWanted = null;
    var focusNode = null;
    var v = {
      ctx: ctx,
      el: el,
      /**
       * Redraws both lists; `focus` ({ uid, what }) names the control to focus afterwards, "row" being
       * the first control of that text's row.
       */
      paint: function (focus) {
        focusWanted = focus || null;
        focusNode = null;
        sections.textContent = "";
        SECTIONS.forEach(function (section) { sections.appendChild(sectionCard(v, section)); });
        var target = focusNode && focusWanted.what === "row" ? firstControl(focusNode) : focusNode;
        if (target && !target.disabled && typeof target.focus === "function") target.focus();
      },
      /** Marks a node as the one to focus after a redraw when it is the one asked for. */
      track: function (node, uid, what) {
        if (focusWanted && focusWanted.uid === uid && focusWanted.what === what) focusNode = node;
        return node;
      }
    };
    var apply = el("button", { type: "button", class: "primary", text: "معاينة وتطبيق", on: { click: function () {
      var adhkar = collect(ctx);
      if (!adhkar) return;
      apply.disabled = true;
      // Rebuilt from the TV's lists on the next render; the form stays usable if that reload fails,
      // and until then the lists just applied are what the TV has.
      ctx.submit({ adhkar: adhkar }).then(function (applied) {
        apply.disabled = false;
        if (!applied) return;
        draftBase = null;
        Object.keys(adhkar).forEach(function (key) { draftFile[key] = JSON.stringify(adhkar[key]); });
      }, function () { apply.disabled = false; });
    } } });
    box.appendChild(sections);
    box.appendChild(el("div", { class: "row" },
      apply,
      el("button", { type: "button", text: "إعادة القيم الحالية", on: { click: function () {
        startDraft(ctx.settings, true);
        v.paint();
      } } })));
    v.paint();
  }

  /** Loads the library into `box`, unless another section replaced it meanwhile. */
  function loadInto(root, box, ctx) {
    var el = ctx.el;
    box.textContent = "";
    box.appendChild(el("p", { class: "muted", text: "جارٍ تحميل مكتبة الأذكار…" }));
    loadLibrary(ctx).then(function () {
      if (box.parentNode !== root) return;
      box.textContent = "";
      editor(box, ctx);
    }, function (error) {
      if (box.parentNode !== root || ctx.sessionClosed) return;
      box.textContent = "";
      box.appendChild(el("div", { class: "card level-BAD", attrs: { role: "alert" } },
        el("p", { text: "تعذّر تحميل مكتبة الأذكار: " + error.message }),
        el("div", { class: "row" }, el("button", {
          type: "button", text: "إعادة المحاولة", on: { click: function () { loadInto(root, box, ctx); } }
        }))));
    });
  }

  Dashboard.registerView({
    id: "adhkar",
    title: "الأذكار",
    render: function (root, ctx) {
      root.appendChild(ctx.el("p", { class: "muted", text:
        "النصوص المراجَعة مضمّنة في التطبيق ولا تُعدَّل كلماتها: رتّبها أو أخفها أو أضف غيرها من المكتبة. " +
        "كل نص يضيفه المسجد يحتاج إلى مصدره (السورة والآية، أو من روى الحديث)." }));
      var box = ctx.el("div");
      root.appendChild(box);
      if (library) editor(box, ctx);
      else loadInto(root, box, ctx);
    }
  });
})();
