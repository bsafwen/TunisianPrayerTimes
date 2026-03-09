/**
 * Cloudflare Worker — Mawaqit.net CORS proxy + Qaloon voice contribution API
 *
 * Routes
 *   GET  /api/configure        ?mosqueId=xxx  + X-Auth-Cookies header → HTML string
 *   POST /api/configure        ?mosqueId=xxx  + X-Auth-Cookies header + URLEncoded body → { ok }
 *   POST /api/contribute       multipart: audio (WAV) + surah + ayah → stores in R2
 *   GET  /api/contribute/stats → { total, byReciter }
 *   GET  /api/contribute/list  → paginated list of all contribution keys + metadata
 *   GET  /api/contribute/download?key=... → stream a single WAV from R2
 *
 * The browser (GitHub Pages) calls these endpoints; the Worker forwards
 * the requests server-side to mawaqit.net, so CORS is never an issue.
 */

const MAWAQIT_BASE = "https://mawaqit.net";

// ── CORS ──────────────────────────────────────────────────────────────────────
// List every origin that is allowed to call this Worker.
// Add your GitHub Pages URL here (e.g. https://yourname.github.io).
const ALLOWED_ORIGINS = [
  "http://localhost",
  "http://localhost:3000",
  "http://localhost:5500",
  "http://localhost:8080",
  "http://127.0.0.1:5500",
  "http://127.0.0.1:8080",
  "https://bsafwen.github.io",
];

function corsHeaders(origin) {
  const allowed = ALLOWED_ORIGINS.includes(origin)
    ? origin
    : ALLOWED_ORIGINS[0];
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers":
      "Content-Type,X-Auth-Cookies,X-Contributor-Id",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

// ── Cookie helpers ────────────────────────────────────────────────────────────

/**
 * Parse all Set-Cookie headers from a Response into a plain {name: value} object.
 * CF Workers exposes Headers.getAll() for Set-Cookie.
 */
function parseSetCookies(headers) {
  const result = {};
  // getAll is a CF Workers extension; fall back to get() + split if unavailable.
  const lines =
    typeof headers.getAll === "function"
      ? headers.getAll("set-cookie")
      : (headers.get("set-cookie") || "").split(/,(?=[^ ])/);

  for (const line of lines) {
    const semi = line.indexOf(";");
    const pair = semi === -1 ? line : line.slice(0, semi);
    const eq = pair.indexOf("=");
    if (eq === -1) continue;
    const name = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1).trim();
    if (name) result[name] = value;
  }
  return result;
}

/** Build a Cookie: header string from a plain {name: value} object. */
function buildCookieStr(obj) {
  return Object.entries(obj)
    .map(([k, v]) => `${k}=${v}`)
    .join("; ");
}

/**
 * Decode the X-Auth-Cookies header (base64(JSON({name:value}))).
 * Returns a cookie string ready to inject as a Cookie: header.
 */
function authCookieStr(req) {
  const raw = req.headers.get("X-Auth-Cookies");
  if (!raw) return "";
  try {
    const obj = JSON.parse(atob(raw));
    return buildCookieStr(obj);
  } catch {
    return "";
  }
}

// ── Common fetch headers ──────────────────────────────────────────────────────
const UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:120.0) Gecko/20100101 Firefox/120.0";

// ── POST /api/login removed ─────────────────────────────────────────────────
// mawaqit.net requires CAPTCHA + email 2FA; automated login is not possible.
// Authentication is now handled by the user logging in manually in their browser
// and pasting the resulting session cookies into the app UI.

// ── POST /api/refresh ────────────────────────────────────────────────────────
// Uses the stored refreshToken to obtain a fresh accessToken from mawaqit.net,
// so the user does not need to re-paste cookies after the 1-hour accessToken expiry.
async function handleRefresh(req) {
  const raw = req.headers.get("X-Auth-Cookies");
  if (!raw) {
    return Response.json(
      { error: "X-Auth-Cookies header is required" },
      { status: 400 },
    );
  }

  let cookieObj;
  try {
    cookieObj = JSON.parse(atob(raw));
  } catch {
    return Response.json(
      { error: "Invalid X-Auth-Cookies value" },
      { status: 400 },
    );
  }

  const refreshToken = cookieObj.refreshToken;
  if (!refreshToken) {
    return Response.json(
      { error: "No refreshToken found in stored cookies" },
      { status: 400 },
    );
  }

  // mawaqit.net uses gesdinet/jwt-refresh-token-bundle (Symfony)
  const resp = await fetch(`${MAWAQIT_BASE}/api/token/refresh`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      "User-Agent": UA,
      Cookie: buildCookieStr(cookieObj),
    },
    body: JSON.stringify({ refresh_token: refreshToken }),
  });

  if (!resp.ok) {
    return Response.json(
      {
        error: `Token refresh failed — mawaqit.net returned HTTP ${resp.status}`,
      },
      { status: resp.status === 401 ? 401 : 502 },
    );
  }

  let data;
  try {
    data = await resp.json();
  } catch {
    return Response.json(
      { error: "mawaqit.net returned a non-JSON refresh response" },
      { status: 502 },
    );
  }

  if (!data || !data.token) {
    return Response.json(
      { error: "Refresh response did not contain a new token" },
      { status: 502 },
    );
  }

  // Merge new token values into the existing cookie object.
  const updated = { ...cookieObj, accessToken: data.token };
  if (data.refresh_token) updated.refreshToken = data.refresh_token;

  // Also absorb any Set-Cookie headers (e.g. renewed PHPSESSID).
  const setCookies = parseSetCookies(resp.headers);
  Object.assign(updated, setCookies);

  return Response.json({
    ok: true,
    cookies: btoa(JSON.stringify(updated)),
  });
}

// ── GET /api/configure?mosqueId=xxx ──────────────────────────────────────────
async function handleGetConfigure(req) {
  const url = new URL(req.url);
  const mosqueId = url.searchParams.get("mosqueId");
  if (!mosqueId) {
    return Response.json(
      { error: "mosqueId query param is required" },
      { status: 400 },
    );
  }

  const cookieStr = authCookieStr(req);
  if (!cookieStr) {
    return Response.json(
      { error: "X-Auth-Cookies header is required" },
      { status: 400 },
    );
  }

  const configUrl = `${MAWAQIT_BASE}/en/backoffice/mosque/${mosqueId}/configure`;

  const resp = await fetch(configUrl, {
    headers: {
      Cookie: cookieStr,
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "en-US,en;q=0.9",
      Referer: `${MAWAQIT_BASE}/en/backoffice/mosque`,
    },
  });

  if (!resp.ok) {
    return Response.json(
      {
        error: `mawaqit.net returned HTTP ${resp.status} for the configure page`,
      },
      { status: resp.status },
    );
  }

  const html = await resp.text();

  // Detect redirect to login page (session expired or wrong mosque ID)
  if (
    html.includes('name="_username"') ||
    html.includes('id="_username"') ||
    html.includes("/en/login")
  ) {
    return Response.json(
      { error: "Session expired or mosque not found — please log in again" },
      { status: 401 },
    );
  }

  // Return the raw HTML; the browser will parse it with DOMParser
  return new Response(html, {
    status: 200,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

// ── POST /api/configure?mosqueId=xxx ─────────────────────────────────────────
async function handlePostConfigure(req) {
  const url = new URL(req.url);
  const mosqueId = url.searchParams.get("mosqueId");
  if (!mosqueId) {
    return Response.json(
      { error: "mosqueId query param is required" },
      { status: 400 },
    );
  }

  const cookieStr = authCookieStr(req);
  if (!cookieStr) {
    return Response.json(
      { error: "X-Auth-Cookies header is required" },
      { status: 400 },
    );
  }

  // Receive the fully-assembled URL-encoded form body from the browser
  const formBody = await req.text();
  if (!formBody) {
    return Response.json({ error: "Empty form body" }, { status: 400 });
  }

  const configUrl = `${MAWAQIT_BASE}/en/backoffice/mosque/${mosqueId}/configure`;

  const resp = await fetch(configUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Cookie: cookieStr,
      "User-Agent": UA,
      "Accept-Language": "en-US,en;q=0.9",
      Referer: configUrl,
      Origin: MAWAQIT_BASE,
    },
    body: formBody,
    redirect: "manual",
  });

  // mawaqit.net redirects (302) to the mosque list on success.
  // Guard against a redirect back to the login page (stale session).
  if (resp.status === 302 || resp.status === 301) {
    const location = resp.headers.get("location") || "";
    if (location.includes("/login") || location.includes("/en/login")) {
      return Response.json(
        { error: "Session expired — please paste fresh cookies and try again" },
        { status: 401 },
      );
    }
    return Response.json({
      ok: true,
      message: "Prayer times updated on mawaqit.net ✓",
    });
  }

  // Any other status: something went wrong
  const detail = await resp.text().catch(() => "");
  return Response.json(
    {
      error: `mawaqit.net returned HTTP ${resp.status} — the form may have validation errors`,
      hint: detail.slice(0, 800),
    },
    { status: 422 },
  );
}

// ── POST /api/contribute ──────────────────────────────────────────────────────
// Receives voice recording + metadata from the Qaloon app, stores in R2.
// Expects multipart/form-data with: audio (WAV blob), surah, ayah, text
const MAX_AUDIO_BYTES = 5 * 1024 * 1024; // 5 MB max per recording

async function handleContribute(req, env) {
  if (!env.CONTRIBUTIONS) {
    return Response.json({ error: "Storage not configured" }, { status: 503 });
  }

  let formData;
  try {
    formData = await req.formData();
  } catch {
    return Response.json(
      { error: "Expected multipart/form-data" },
      { status: 400 },
    );
  }

  const audio = formData.get("audio");
  const surah = parseInt(formData.get("surah"), 10);
  const ayah = parseInt(formData.get("ayah"), 10);
  const text = formData.get("text") || "";
  const contributorId = req.headers.get("X-Contributor-Id") || "anonymous";

  // Validate
  if (!audio || !(audio instanceof File)) {
    return Response.json({ error: "Missing audio file" }, { status: 400 });
  }
  if (audio.size > MAX_AUDIO_BYTES) {
    return Response.json(
      { error: "Audio too large (max 5MB)" },
      { status: 413 },
    );
  }
  if (!surah || surah < 1 || surah > 114) {
    return Response.json({ error: "Invalid surah (1-114)" }, { status: 400 });
  }
  if (!ayah || ayah < 1 || ayah > 286) {
    return Response.json({ error: "Invalid ayah" }, { status: 400 });
  }

  // Generate key: contributor/SSS/SSSAAA_timestamp.wav
  const ts = Date.now();
  const key = `${contributorId}/${String(surah).padStart(3, "0")}/${String(surah).padStart(3, "0")}${String(ayah).padStart(3, "0")}_${ts}.wav`;

  // Store audio in R2 with metadata
  const audioBytes = await audio.arrayBuffer();
  await env.CONTRIBUTIONS.put(key, audioBytes, {
    httpMetadata: { contentType: "audio/wav" },
    customMetadata: {
      surah: String(surah),
      ayah: String(ayah),
      text: text.slice(0, 2000),
      contributor: contributorId,
      timestamp: new Date(ts).toISOString(),
    },
  });

  return Response.json({ ok: true, key });
}

// ── GET /api/contribute/stats ────────────────────────────────────────────────
async function handleContributeStats(env) {
  if (!env.CONTRIBUTIONS) {
    return Response.json({ error: "Storage not configured" }, { status: 503 });
  }

  // List all objects (up to 1000) to build stats
  const listed = await env.CONTRIBUTIONS.list({ limit: 1000 });
  const contributors = new Set();
  let total = 0;

  for (const obj of listed.objects) {
    total++;
    const parts = obj.key.split("/");
    if (parts.length >= 1) contributors.add(parts[0]);
  }

  return Response.json({
    totalRecordings: total,
    contributors: contributors.size,
    truncated: listed.truncated,
  });
}

// ── GET /api/contribute/list ──────────────────────────────────────────────────
async function handleContributeList(env, url) {
  if (!env.CONTRIBUTIONS) {
    return Response.json({ error: "Storage not configured" }, { status: 503 });
  }

  const cursor = url.searchParams.get("cursor") || undefined;
  const limit = Math.min(
    parseInt(url.searchParams.get("limit") || "1000", 10),
    1000,
  );

  const listed = await env.CONTRIBUTIONS.list({ limit, cursor });

  const items = listed.objects.map((obj) => ({
    key: obj.key,
    size: obj.size,
    uploaded: obj.uploaded?.toISOString(),
    surah: obj.customMetadata?.surah,
    ayah: obj.customMetadata?.ayah,
    text: obj.customMetadata?.text,
    contributor: obj.customMetadata?.contributor,
  }));

  return Response.json({
    items,
    cursor: listed.truncated ? listed.cursor : null,
  });
}

// ── GET /api/contribute/download?key=... ─────────────────────────────────────
async function handleContributeDownload(env, url) {
  if (!env.CONTRIBUTIONS) {
    return Response.json({ error: "Storage not configured" }, { status: 503 });
  }

  const key = url.searchParams.get("key");
  if (!key) {
    return Response.json({ error: "Missing ?key= parameter" }, { status: 400 });
  }

  const obj = await env.CONTRIBUTIONS.get(key);
  if (!obj) {
    return Response.json({ error: "Not found" }, { status: 404 });
  }

  return new Response(obj.body, {
    headers: {
      "Content-Type": obj.httpMetadata?.contentType || "audio/wav",
      "Content-Length": String(obj.size),
    },
  });
}

// ── Main export ───────────────────────────────────────────────────────────────
export default {
  async fetch(req, env, _ctx) {
    const origin = req.headers.get("Origin") || "";
    const cors = corsHeaders(origin);

    // CORS preflight
    if (req.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors });
    }

    const url = new URL(req.url);
    let response;

    try {
      if (url.pathname === "/api/configure" && req.method === "GET") {
        response = await handleGetConfigure(req);
      } else if (url.pathname === "/api/configure" && req.method === "POST") {
        response = await handlePostConfigure(req);
      } else if (url.pathname === "/api/refresh" && req.method === "POST") {
        response = await handleRefresh(req);
      } else if (url.pathname === "/api/contribute" && req.method === "POST") {
        response = await handleContribute(req, env);
      } else if (
        url.pathname === "/api/contribute/stats" &&
        req.method === "GET"
      ) {
        response = await handleContributeStats(env);
      } else if (
        url.pathname === "/api/contribute/list" &&
        req.method === "GET"
      ) {
        response = await handleContributeList(env, url);
      } else if (
        url.pathname === "/api/contribute/download" &&
        req.method === "GET"
      ) {
        response = await handleContributeDownload(env, url);
      } else {
        response = Response.json({ error: "Not found" }, { status: 404 });
      }
    } catch (err) {
      response = Response.json({ error: String(err.message) }, { status: 500 });
    }

    // Attach CORS headers to every response
    const newHeaders = new Headers(response.headers);
    for (const [k, v] of Object.entries(cors)) {
      newHeaders.set(k, v);
    }
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: newHeaders,
    });
  },
};
