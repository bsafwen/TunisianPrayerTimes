/*
 * Announcements: the written announcements shown on the TV, and the announcement and background images.
 * Texts come from users: the DOM is built with ctx.el only (textContent and properties, never HTML).
 */
(function () {
  "use strict";

  var MAX_TEXT = 300;
  var MAX_COUNT = 30;
  var MAX_IMAGES = 20;
  var MAX_BYTES = 15 * 1024 * 1024;
  var SCREEN = { width: 1920, height: 1080 };
  var DATE = /^\d{4}-\d{2}-\d{2}$/;
  var EXTENSIONS = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
  var GALLERIES = [
    { kind: "announcements", title: "صور الإعلانات", display: "announcements" },
    { kind: "backgrounds", title: "صور الخلفية", display: "backgrounds" }
  ];

  // The list being edited survives a re-render (an upload, another tab) until the TV's own list changes.
  var draft = null;
  var draftBase = null;
  // What the list said when drawn from the TV (see signature), and what the page keeps of it
  // (ctx.keepDraft): a kept draft that is not this one was restored from an earlier page.
  var draftStart = null;
  var kept = null;

  function entry(item) {
    if (typeof item === "string") return { text: item, from: "", until: "" };
    item = item || {};
    return { text: String(item.text || ""), from: String(item.from || ""), until: String(item.until || "") };
  }

  /** What a list says, blank entries aside: an added empty entry is not an edit to keep. */
  function signature(list) {
    return JSON.stringify(list.filter(function (item) { return item.text.trim(); })
      .map(function (item) { return [item.text.trim(), item.from.trim(), item.until.trim()]; }));
  }

  function startDraft(ctx, force) {
    var settings = ctx.settings || {};
    var base = JSON.stringify(settings.announcements || []);
    var list = Array.isArray(settings.announcements) ? settings.announcements : [];
    var restored = ctx.keptDraft();
    if (!force && restored && restored !== kept && Array.isArray(restored.list)) {
      // Edits kept by an earlier page, which the admin chose to restore.
      draftBase = base;
      draft = restored.list.map(entry);
      draftStart = typeof restored.start === "string" ? restored.start : signature(list.map(entry));
      kept = restored;
      return;
    }
    if (draft && draftBase === base && !force) return;
    draftBase = base;
    draft = list.map(entry);
    draftStart = signature(draft);
    keep(ctx);
  }

  /** Keeps the list in the browser while it differs from the TV's, for another tab, a reload or a new session. */
  function keep(ctx) {
    if (signature(draft) === draftStart) {
      kept = null;
      ctx.keepDraft(null);
      return;
    }
    kept = kept || {};
    kept.list = draft;
    kept.start = draftStart;
    ctx.keepDraft(kept);
  }

  /**
   * A name for a URL. Names copied from a USB key may hold any character; one that cannot be encoded
   * (a broken character) is sent with that character replaced rather than breaking the whole section.
   */
  function encodeName(name) {
    try {
      return encodeURIComponent(name);
    } catch (e) {
      return encodeURIComponent(String(name).replace(/[\uD800-\uDFFF]/g, "�"));
    }
  }

  /** Shows an error unless the session is closed (the page already says so). */
  function report(ctx, error, prefix) {
    if (error.message === "forbidden" || error.message === "closed") return;
    ctx.toast((prefix ? prefix + ": " : "") + error.message, "error");
  }

  /** The announcements to send, or null after showing the first mistake. */
  function collect(ctx) {
    var out = [];
    for (var i = 0; i < draft.length; i++) {
      var text = draft[i].text.trim();
      var from = draft[i].from.trim();
      var until = draft[i].until.trim();
      if (!text) continue;
      var problem = null;
      if (text.length > MAX_TEXT) problem = "النص أطول من " + MAX_TEXT + " حرف";
      else if ((from && !DATE.test(from)) || (until && !DATE.test(until))) problem = "اكتب التاريخ بالصيغة 2026-10-01";
      else if (from && until && from > until) problem = "تاريخ البداية بعد تاريخ النهاية";
      if (problem) {
        ctx.toast("الإعلان " + (i + 1) + ": " + problem, "error");
        return null;
      }
      var entry = { text: text };
      if (from) entry.from = from;
      if (until) entry.until = until;
      out.push(entry);
    }
    if (out.length > MAX_COUNT) {
      ctx.toast("الإعلانات " + MAX_COUNT + " على الأكثر: احذف " + (out.length - MAX_COUNT), "error");
      return null;
    }
    return out;
  }

  function dateField(ctx, id, label, item, key) {
    function update(event) {
      item[key] = event.target.value;
      keep(ctx);
    }
    return ctx.el("div", { class: "grow date" },
      ctx.el("label", { text: label, attrs: { for: id } }),
      ctx.el("input", { id: id, type: "date", value: item[key], class: "ltr wide", on: { input: update, change: update } }));
  }

  function fillTexts(card, ctx, paint) {
    var el = ctx.el;
    var display = (ctx.settings && ctx.settings.display) || {};
    card.appendChild(el("h2", { text: "الإعلانات المكتوبة" }));
    card.appendChild(el("p", { class: "hint", text: "تظهر على الشاشة بين حين وآخر. اترك التاريخين فارغين ليبقى الإعلان دائماً." }));
    if (display.announcements === false) {
      card.appendChild(el("p", { class: "muted", text: "عرض الإعلانات متوقف حالياً في إعدادات العرض." }));
    }
    var usbTexts = ctx.state && Array.isArray(ctx.state.textFiles) && ctx.state.textFiles.length;
    if (!draft.length) card.appendChild(el("p", { class: "muted", text: usbTexts ? "لا توجد إعلانات مكتوبة من هنا." : "لا توجد إعلانات مكتوبة." }));
    draft.forEach(function (item, i) {
      var id = "announcement-" + i;
      card.appendChild(el("div", { class: "list-item" },
        el("label", { text: "الإعلان " + (i + 1) + " (حتى " + MAX_TEXT + " حرف)", attrs: { for: id + "-text" } }),
        el("textarea", {
          id: id + "-text", value: item.text, attrs: { maxlength: MAX_TEXT, rows: 3 },
          on: { input: function (event) {
            item.text = event.target.value;
            keep(ctx);
          } }
        }),
        el("div", { class: "row" },
          dateField(ctx, id + "-from", "من", item, "from"),
          dateField(ctx, id + "-until", "حتى", item, "until"),
          el("button", { class: "danger", type: "button", text: "حذف", attrs: { "aria-label": "حذف الإعلان " + (i + 1) }, on: { click: function () {
            if (item.text.trim() && !confirm("حذف هذا الإعلان؟")) return;
            draft.splice(i, 1);
            keep(ctx);
            paint();
          } } }))));
    });
    card.appendChild(el("div", { class: "row" },
      el("button", { type: "button", text: "إضافة إعلان", on: { click: function () {
        draft.push({ text: "", from: "", until: "" });
        paint();
        var box = document.getElementById("announcement-" + (draft.length - 1) + "-text");
        if (box) box.focus();
      } } }),
      el("button", { type: "button", class: "quiet", text: "إعادة القيم الحالية", on: { click: function () {
        startDraft(ctx, true);
        paint();
      } } })));
    card.appendChild(el("div", { class: "row" },
      el("button", { class: "primary", type: "button", text: "معاينة وتطبيق", on: { click: function () {
        var list = collect(ctx);
        if (!list) return;
        // Rebuilt from the TV's list on the next render; the form stays usable if that reload fails.
        ctx.submit({ announcements: list }).then(function (applied) { if (applied) draftBase = null; });
      } } })));
    fillTextFiles(card, ctx);
  }

  /** The announcements that came as .txt files on a USB key: read-only here, they can only be deleted. */
  function fillTextFiles(card, ctx) {
    var el = ctx.el;
    var listed = ctx.state && ctx.state.textFiles;
    var files = (Array.isArray(listed) ? listed : []).filter(function (file) { return file && typeof file.name === "string"; });
    if (!files.length) return;
    card.appendChild(el("h3", { text: "من مفتاح USB" }));
    card.appendChild(el("p", { class: "hint", text: "إعلانات نُسخت من ملفات نصية على مفتاح USB. لا تُعدَّل من هنا، ويمكن حذفها." }));
    files.forEach(function (file) {
      card.appendChild(el("div", { class: "list-item" },
        el("p", { text: String(file.text || ""), attrs: { dir: "auto", style: "white-space: pre-wrap; overflow-wrap: anywhere" } }),
        el("div", { class: "row" },
          el("span", { class: "muted small ltr grow", text: file.name }),
          el("button", { class: "danger", type: "button", text: "حذف", attrs: { "aria-label": "حذف الإعلان «" + file.name + "»" }, on: { click: function () {
            if (!confirm("حذف الإعلان «" + file.name + "» من الشاشة؟")) return;
            ctx.api.post("/api/image/delete?kind=announcements&name=" + encodeName(file.name)).then(function (result) {
              if (result && result.ok === false) ctx.toast(result.error || "تعذّر حذف الإعلان", "error");
              else ctx.toast("حُذف الإعلان", "ok");
              ctx.reload();
            }, function (error) { report(ctx, error); });
          } } }))));
    });
  }

  function formatSize(bytes) {
    if (typeof bytes !== "number") return "";
    return bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024)) + " KB" : (bytes / 1024 / 1024).toFixed(1) + " MB";
  }

  /**
   * A name the TV accepts (DashboardRoutes.IMAGE_NAME): [A-Za-z0-9._-] only, not starting with ".",
   * at most 80 characters before a jpg, jpeg, png or webp extension. Other characters become "_".
   */
  function safeName(file, index) {
    var name = file.name || "";
    var dot = name.lastIndexOf(".");
    var base = (dot > 0 ? name.slice(0, dot) : name).replace(/[^A-Za-z0-9._-]/g, "_").replace(/^\.+/, "").slice(0, 80);
    var ext = (dot > 0 ? name.slice(dot + 1) : "").toLowerCase();
    if (!/^(jpg|jpeg|png|webp)$/.test(ext)) ext = EXTENSIONS[file.type] || "jpg";
    // A name with no Latin letter or digit (an Arabic name) would become "____": make it unique instead.
    if (!/[A-Za-z0-9]/.test(base)) base = "image_" + Date.now() + "_" + index;
    return base + "." + ext;
  }

  /**
   * A first guess at a free name: a taken name gets "_2", "_3", ... before its extension (compared
   * ignoring case), keeping the base within the 80 characters the TV accepts. The TV makes the same
   * choice again with the names it has now (another phone may have sent one since), and never replaces.
   * `taken` holds lower-case names and receives the one chosen.
   */
  function freeName(name, taken) {
    var dot = name.lastIndexOf(".");
    var base = name.slice(0, dot);
    var ext = name.slice(dot);
    var candidate = name;
    for (var n = 2; taken[candidate.toLowerCase()]; n++) {
      var suffix = "_" + n;
      candidate = base.slice(0, 80 - suffix.length) + suffix + ext;
    }
    taken[candidate.toLowerCase()] = true;
    return candidate;
  }

  /**
   * The image to send: scaled down on the phone to fit the screen (1920×1080) when it is larger, in its
   * own format, so a 50 MP photo crosses a hotspot in seconds and the TV never holds it whole. The
   * original is sent when it already fits, or when this browser cannot decode or encode it.
   */
  function fitScreen(file) {
    if (typeof createImageBitmap !== "function" || !EXTENSIONS[file.type]) return Promise.resolve(file);
    return createImageBitmap(file, { imageOrientation: "from-image" }).then(function (bitmap) {
      var scale = Math.min(1, SCREEN.width / bitmap.width, SCREEN.height / bitmap.height);
      if (!(scale < 1)) {
        if (bitmap.close) bitmap.close();
        return file;
      }
      var canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      if (bitmap.close) bitmap.close();
      return new Promise(function (resolve) {
        // A browser that cannot write this format gives another one (PNG): the original then.
        canvas.toBlob(function (blob) { resolve(blob && blob.type === file.type ? blob : file); }, file.type, 0.85);
      });
    }).then(null, function () { return file; });
  }

  function upload(ctx, kind, input, status) {
    var files = Array.prototype.slice.call(input.files || []);
    if (!files.length) return;
    // The names on the TV now, then those used by this batch.
    var taken = Object.create(null);
    var listed = ctx.state && ctx.state.images && ctx.state.images[kind];
    var onTv = (Array.isArray(listed) ? listed : []).filter(function (image) { return image && typeof image.name === "string"; });
    // Said before sending anything: the TV would refuse the extra ones only after receiving them.
    if (onTv.length + files.length > MAX_IMAGES) {
      var room = Math.max(0, MAX_IMAGES - onTv.length);
      ctx.toast("الصور " + MAX_IMAGES + " على الأكثر وعلى الشاشة " + onTv.length + ": " +
        (room ? "اختر " + room + " على الأكثر، أو احذف بعض الصور أولًا" : "احذف بعض الصور أولًا"), "error");
      input.value = "";
      return;
    }
    input.disabled = true;
    onTv.forEach(function (image) { taken[image.name.toLowerCase()] = true; });
    var sent = 0;
    var errors = [];
    function fail(file, message) {
      errors.push("«" + file.name + "»: " + message);
      ctx.toast(errors[errors.length - 1], "error");
    }
    var chain = Promise.resolve();
    files.forEach(function (file, i) {
      chain = chain.then(function () {
        status.textContent = "جارٍ الرفع " + (i + 1) + " من " + files.length + "…";
        if (file.type && !EXTENSIONS[file.type]) return fail(file, "ليست صورة JPEG أو PNG أو WebP");
        return fitScreen(file).then(function (image) {
          if (image.size > MAX_BYTES) return fail(file, "أكبر من 15 ميغابايت، لم تُرفع");
          var path = "/api/image?kind=" + kind + "&name=" + encodeURIComponent(freeName(safeName(file, i), taken));
          return ctx.api.postBytes(path, image);
        }).then(function (result) {
          if (result === undefined) return; // refused above, already said
          if (result && result.ok === false) fail(file, result.error || "تعذّر رفع الصورة");
          else {
            sent++;
            if (result && typeof result.name === "string") taken[result.name.toLowerCase()] = true;
          }
        }, function (error) {
          if (error.message === "forbidden" || error.message === "closed") return;
          fail(file, error.message);
        });
      });
    });
    chain.then(function () {
      status.textContent = "";
      input.disabled = false;
      if (errors.length > 1) ctx.toast(errors.join(" · "), "error");
      else if (!errors.length && sent) ctx.toast("رُفعت الصور إلى الشاشة: " + sent, "ok");
      ctx.reload();
    });
  }

  function figure(ctx, kind, image) {
    var el = ctx.el;
    // Shown and deleted by the exact name listed, whatever its characters (names copied from a USB key).
    var query = "?kind=" + encodeURIComponent(kind) + "&name=" + encodeName(image.name);
    return el("figure", {},
      // A small copy made by the TV: the originals weigh up to 15 MB each.
      el("img", { attrs: { loading: "lazy" }, src: ctx.api.url("/api/image" + query + "&thumb=1"), alt: image.name }),
      el("figcaption", {},
        el("div", {},
          el("div", { class: "ltr", text: image.name }),
          el("div", { class: "tabular ltr", text: formatSize(image.size) })),
        el("button", { class: "danger", type: "button", text: "حذف", attrs: { "aria-label": "حذف الصورة «" + image.name + "»" }, on: { click: function () {
          if (!confirm("حذف الصورة «" + image.name + "» من الشاشة؟")) return;
          ctx.api.post("/api/image/delete" + query).then(function (result) {
            if (result && result.ok === false) ctx.toast(result.error || "تعذّر حذف الصورة", "error");
            else ctx.toast("حُذفت الصورة", "ok");
            ctx.reload();
          }, function (error) { report(ctx, error); });
        } } })));
  }

  function gallery(ctx, spec) {
    var el = ctx.el;
    var listed = ctx.state && ctx.state.images && ctx.state.images[spec.kind];
    var images = (Array.isArray(listed) ? listed : []).filter(function (image) { return image && typeof image.name === "string"; });
    var display = (ctx.settings && ctx.settings.display) || {};
    var status = el("span", { class: "muted", attrs: { role: "status" } });
    var input = el("input", {
      id: "upload-" + spec.kind, type: "file", multiple: true, accept: "image/jpeg,image/png,image/webp",
      on: { change: function () { upload(ctx, spec.kind, input, status); } }
    });
    return el("section", { class: "card" },
      el("h2", { text: spec.title }),
      el("p", { class: "hint", text: "الصور: " + images.length + " من " + MAX_IMAGES + " على الأكثر · JPEG أو PNG أو WebP، حتى 15 ميغابايت للصورة. تُصغَّر الصورة الأكبر من مقاس الشاشة على الهاتف قبل إرسالها." }),
      display[spec.display] === false ? el("p", { class: "muted", text: "عرض هذه الصور متوقف حالياً في إعدادات العرض." }) : null,
      images.length
        ? el("div", { class: "gallery" }, images.map(function (image) { return figure(ctx, spec.kind, image); }))
        : el("p", { class: "muted", text: "لا توجد صور." }),
      el("label", { text: "إضافة صور", attrs: { for: input.id } }),
      el("div", { class: "row" }, input, status));
  }

  Dashboard.registerView({
    id: "announcements",
    title: "الإعلانات",
    render: function (root, ctx) {
      startDraft(ctx, false);
      var texts = ctx.el("section", { class: "card" });
      function paint() {
        texts.textContent = "";
        fillTexts(texts, ctx, paint);
      }
      root.appendChild(texts);
      paint();
      GALLERIES.forEach(function (spec) { root.appendChild(gallery(ctx, spec)); });
    }
  });
})();
