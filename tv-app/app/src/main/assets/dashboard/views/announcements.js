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
  var DATE = /^\d{4}-\d{2}-\d{2}$/;
  var EXTENSIONS = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
  var GALLERIES = [
    { kind: "announcements", title: "صور الإعلانات", display: "announcements" },
    { kind: "backgrounds", title: "صور الخلفية", display: "backgrounds" }
  ];

  // The list being edited survives a re-render (an upload, another tab) until the TV's own list changes.
  var draft = null;
  var draftBase = null;

  function startDraft(settings, force) {
    settings = settings || {};
    var base = JSON.stringify(settings.announcements || []);
    if (draft && draftBase === base && !force) return;
    draftBase = base;
    var list = Array.isArray(settings.announcements) ? settings.announcements : [];
    draft = list.map(function (item) {
      if (typeof item === "string") return { text: item, from: "", until: "" };
      item = item || {};
      return { text: String(item.text || ""), from: String(item.from || ""), until: String(item.until || "") };
    });
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
    function update(event) { item[key] = event.target.value; }
    return ctx.el("div", { class: "grow" },
      ctx.el("label", { text: label, attrs: { for: id } }),
      ctx.el("input", { id: id, type: "date", value: item[key], class: "ltr", on: { input: update, change: update } }));
  }

  function fillTexts(card, ctx, paint) {
    var el = ctx.el;
    var display = (ctx.settings && ctx.settings.display) || {};
    card.appendChild(el("h2", { text: "الإعلانات المكتوبة" }));
    card.appendChild(el("p", { class: "muted", text: "تظهر على الشاشة بين حين وآخر. اترك التاريخين فارغين ليبقى الإعلان دائماً." }));
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
          on: { input: function (event) { item.text = event.target.value; } }
        }),
        el("div", { class: "row" },
          dateField(ctx, id + "-from", "من", item, "from"),
          dateField(ctx, id + "-until", "حتى", item, "until"),
          el("button", { class: "danger", text: "حذف", on: { click: function () {
            if (item.text.trim() && !confirm("حذف هذا الإعلان؟")) return;
            draft.splice(i, 1);
            paint();
          } } }))));
    });
    card.appendChild(el("div", { class: "row" },
      el("button", { text: "إضافة إعلان", on: { click: function () {
        draft.push({ text: "", from: "", until: "" });
        paint();
        var box = document.getElementById("announcement-" + (draft.length - 1) + "-text");
        if (box) box.focus();
      } } }),
      el("button", { text: "إعادة القيم الحالية", on: { click: function () {
        startDraft(ctx.settings, true);
        paint();
      } } })));
    card.appendChild(el("div", { class: "row" },
      el("button", { class: "primary", text: "معاينة وتطبيق", on: { click: function () {
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
    card.appendChild(el("p", { class: "muted", text: "إعلانات نُسخت من ملفات نصية على مفتاح USB. لا تُعدَّل من هنا، ويمكن حذفها." }));
    files.forEach(function (file) {
      card.appendChild(el("div", { class: "list-item" },
        el("p", { text: String(file.text || ""), attrs: { dir: "auto", style: "white-space: pre-wrap; overflow-wrap: anywhere" } }),
        el("div", { class: "row" },
          el("span", { class: "muted ltr grow", text: file.name }),
          el("button", { class: "danger", text: "حذف", on: { click: function () {
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
   * An upload with the name of an image already on the TV replaces it: a taken name gets "_2", "_3", ...
   * before its extension (compared ignoring case), keeping the base within the 80 characters the TV accepts.
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

  function upload(ctx, kind, input, status) {
    var files = Array.prototype.slice.call(input.files || []);
    if (!files.length) return;
    input.disabled = true;
    // The names on the TV now, then those used by this batch.
    var taken = Object.create(null);
    var listed = ctx.state && ctx.state.images && ctx.state.images[kind];
    (Array.isArray(listed) ? listed : []).forEach(function (image) {
      if (image && typeof image.name === "string") taken[image.name.toLowerCase()] = true;
    });
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
        if (file.size > MAX_BYTES) return fail(file, "أكبر من 15 ميغابايت، لم تُرفع");
        if (file.type && !EXTENSIONS[file.type]) return fail(file, "ليست صورة JPEG أو PNG أو WebP");
        var path = "/api/image?kind=" + kind + "&name=" + encodeURIComponent(freeName(safeName(file, i), taken));
        return ctx.api.postBytes(path, file).then(function (result) {
          if (result && result.ok === false) fail(file, result.error || "تعذّر رفع الصورة");
          else sent++;
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
      el("img", { attrs: { loading: "lazy" }, src: ctx.api.url("/api/image" + query), alt: image.name }),
      el("figcaption", {},
        el("div", {},
          el("div", { class: "ltr", text: image.name }),
          el("div", { class: "tabular ltr", text: formatSize(image.size) })),
        el("button", { class: "danger", text: "حذف", on: { click: function () {
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
    var status = el("span", { class: "muted" });
    var input = el("input", {
      id: "upload-" + spec.kind, type: "file", multiple: true, accept: "image/jpeg,image/png,image/webp",
      on: { change: function () { upload(ctx, spec.kind, input, status); } }
    });
    return el("section", { class: "card" },
      el("h2", { text: spec.title }),
      el("p", { class: "muted", text: "الصور: " + images.length + " من " + MAX_IMAGES + " على الأكثر · JPEG أو PNG أو WebP، حتى 15 ميغابايت للصورة." }),
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
      startDraft(ctx.settings, false);
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
