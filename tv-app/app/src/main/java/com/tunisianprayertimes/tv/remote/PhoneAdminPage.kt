package com.tunisianprayertimes.tv.remote

/**
 * The page a phone opens from the QR code: the TV's settings file to edit, a check that lists what
 * would change (or the mistakes), and an apply button. Self-contained: no script or font is loaded
 * from the internet, so it works on a hotspot with no connection. Minimal; to be redesigned.
 */
internal object PhoneAdminPage {

    fun html(token: String): String = """<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>إعدادات شاشة المسجد</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; padding: 16px; background: #0b1627; color: #f1f1f1; }
  h1 { font-size: 20px; color: #d4a843; margin: 0 0 8px; }
  p { color: #9fb0c7; font-size: 14px; }
  textarea { width: 100%; box-sizing: border-box; height: 55vh; font: 13px/1.5 monospace; direction: ltr;
             background: #13223a; color: #f1f1f1; border: 1px solid #2b3f5e; border-radius: 8px; padding: 8px; }
  .row { display: flex; gap: 8px; margin: 12px 0; }
  button { flex: 1; font-size: 16px; padding: 12px; border: 0; border-radius: 8px; background: #1f7a5c; color: #fff; }
  button:disabled { background: #34465f; color: #8a97a8; }
  #out { white-space: pre-wrap; padding: 12px; border-radius: 8px; background: #13223a; min-height: 2em; }
  #out.ok { border-right: 4px solid #4caf50; }
  #out.err { border-right: 4px solid #e53935; }
</style>
</head>
<body>
<h1>إعدادات شاشة المسجد</h1>
<p>هذا ملف إعدادات الشاشة نفسه الذي يُكتب على مفتاح USB. عدّله ثم اضغط «معاينة» لترى ما سيتغيّر، ثم «تطبيق».</p>
<textarea id="file" spellcheck="false"></textarea>
<div class="row">
  <button id="check">معاينة</button>
  <button id="apply" disabled>تطبيق</button>
</div>
<div id="out"></div>
<script>
  var token = "$token";
  var area = document.getElementById("file");
  var out = document.getElementById("out");
  var applyButton = document.getElementById("apply");
  function show(text) {
    var lines = text.split("\n");
    var ok = lines[0] === "OK";
    out.className = ok ? "ok" : "err";
    out.textContent = lines.slice(1).join("\n") || (ok ? "لا تغيير" : "");
    return ok;
  }
  function call(path, body) {
    return fetch(path + "?t=" + encodeURIComponent(token), body === undefined ? {} : { method: "POST", body: body })
      .then(function (response) { return response.text(); });
  }
  call("/settings").then(function (text) { area.value = text; });
  area.addEventListener("input", function () { applyButton.disabled = true; });
  document.getElementById("check").addEventListener("click", function () {
    call("/preview", area.value).then(function (text) { applyButton.disabled = !show(text); });
  });
  applyButton.addEventListener("click", function () {
    applyButton.disabled = true;
    call("/apply", area.value).then(show);
  });
</script>
</body>
</html>
"""
}
