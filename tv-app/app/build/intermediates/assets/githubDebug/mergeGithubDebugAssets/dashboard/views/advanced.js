/*
 * Advanced: the TV's whole settings file as text, to edit by hand, save as mosque-tv.json
 * (the same file as the USB key) or undo the last change. The TV checks the text itself
 * (it accepts comments and trailing commas), so the page only refuses an empty file.
 */
(function () {
  "use strict";

  function download(el, text) {
    var blob = new Blob([text], { type: "application/json;charset=utf-8" });
    var href = URL.createObjectURL(blob);
    var link = el("a", { href: href, download: "mosque-tv.json", hidden: true });
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(function () { URL.revokeObjectURL(href); }, 1000);
  }

  function quiet(error) {
    return error && (error.message === "forbidden" || error.message === "closed");
  }

  Dashboard.registerView({
    id: "advanced",
    title: "متقدّم",
    render: function (root, ctx) {
      var el = ctx.el;
      var state = ctx.state || {};

      var area = el("textarea", {
        class: "code",
        id: "settings-text",
        value: state.settingsFile || "",
        attrs: { spellcheck: "false", autocomplete: "off", autocapitalize: "off", dir: "ltr" }
      });

      var apply = el("button", {
        class: "primary",
        text: "معاينة وتطبيق",
        attrs: { type: "button" },
        on: {
          click: function () {
            var text = area.value;
            if (!text.trim()) {
              ctx.toast("الملف فارغ: اكتب الإعدادات أو استعد الملف الحالي", "error");
              return;
            }
            apply.disabled = true;
            Promise.resolve(ctx.submitText(text)).then(function () { apply.disabled = false; },
              function () { apply.disabled = false; });
          }
        }
      });

      var save = el("button", {
        text: "تنزيل الملف",
        attrs: { type: "button" },
        on: {
          click: function () {
            try {
              download(el, area.value);
            } catch (e) {
              ctx.toast("تعذّر تنزيل الملف من هذا المتصفح", "error");
            }
          }
        }
      });

      var reset = el("button", {
        text: "استعادة الملف الحالي",
        attrs: { type: "button" },
        on: { click: function () { area.value = (ctx.state && ctx.state.settingsFile) || ""; } }
      });

      var undo = null;
      if (state.canUndo) {
        undo = el("button", {
          class: "danger",
          text: "التراجع عن آخر تغيير",
          attrs: { type: "button" },
          on: {
            click: function () {
              undo.disabled = true;
              ctx.api.get("/api/undo").then(function (result) {
                undo.disabled = false;
                if (result && result.available && typeof result.text === "string") {
                  return ctx.submitText(result.text);
                }
                ctx.toast("لا يوجد تغيير سابق للتراجع عنه", "error");
              }, function (error) {
                undo.disabled = false;
                if (!quiet(error)) ctx.toast(error.message, "error");
              });
            }
          }
        });
      }

      root.appendChild(el("section", { class: "card" },
        el("h2", { text: "ملف الإعدادات" }),
        el("p", { class: "muted", text: "هذا هو ملف mosque-tv.json نفسه الذي يُنسخ على مفتاح USB. عدّله بحذر: تعرض الشاشة ما سيتغيّر أو الأخطاء قبل أي تطبيق." }),
        el("label", { text: "نص الملف", attrs: { "for": "settings-text" } }),
        area,
        el("div", { class: "row" }, apply, save, reset)));

      if (undo) {
        root.appendChild(el("section", { class: "card level-WARNING" },
          el("h2", { text: "التراجع" }),
          el("p", { class: "muted", text: "يعيد الإعدادات كما كانت قبل آخر تغيير، بعد أن تعرض الشاشة ما سيتغيّر." }),
          el("div", { class: "row" }, undo)));
      }
    }
  });
})();
