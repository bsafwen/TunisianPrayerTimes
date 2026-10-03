/**
 * quran-cdn — serves the Quran media packs (recitations and mushaf pages) from R2.
 *
 *   GET | HEAD  /v1/packs/<pack>-<sha12>.zip
 *
 * The Android app downloads these when it was not installed from Google Play; Play
 * installs get the same packs through Play Asset Delivery. Keys are content-addressed
 * and never overwritten (scripts/quran_assets.py publish), so responses are immutable.
 * Range requests let interrupted downloads resume. Everything else is 404.
 */

const PACK = /^\/v1\/packs\/[a-z][a-z0-9_]*-[0-9a-f]{12}\.zip$/;

export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response(null, { status: 405, headers: { Allow: "GET, HEAD" } });
    }
    const { pathname } = new URL(request.url);
    if (!PACK.test(pathname)) return new Response("Not found", { status: 404 });
    const key = pathname.slice(1);

    if (request.method === "HEAD") {
      const object = await env.PACKS.head(key);
      if (object === null) return new Response(null, { status: 404 });
      const headers = packHeaders(object);
      headers.set("Content-Length", String(object.size));
      return new Response(null, { headers });
    }

    let object;
    try {
      object = await env.PACKS.get(key, { range: request.headers, onlyIf: request.headers });
    } catch {
      // R2 rejects a Range it cannot satisfy.
      const head = await env.PACKS.head(key);
      if (head === null) return new Response("Not found", { status: 404 });
      return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${head.size}` } });
    }
    if (object === null) return new Response("Not found", { status: 404 });

    const headers = packHeaders(object);
    if (!("body" in object)) {
      // A precondition stopped the read: If-None-Match matched, or If-Match did not.
      const notModified = request.headers.has("If-None-Match") || request.headers.has("If-Modified-Since");
      return new Response(null, { status: notModified ? 304 : 412, headers });
    }
    const range = request.headers.get("Range");
    if (range !== null && requestedStart(range, object.size) >= object.size) {
      // R2 serves the whole object for a range past its end; a resuming client must not
      // mistake that for the bytes it asked for.
      await object.body.cancel();
      return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${object.size}` } });
    }
    if (range !== null && object.range) {
      const { offset, length } = byteRange(object.range, object.size);
      headers.set("Content-Range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
      return new Response(object.body, { status: 206, headers });
    }
    return new Response(object.body, { headers });
  },
};

function packHeaders(object) {
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("Content-Type", "application/zip");
  headers.set("ETag", object.httpEtag);
  headers.set("Accept-Ranges", "bytes");
  headers.set("Cache-Control", "public, max-age=31536000, immutable");
  return headers;
}

/** First byte a single "bytes=a-b", "bytes=a-" or "bytes=-n" range asks for; -1 for anything else. */
function requestedStart(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (match === null || (match[1] === "" && match[2] === "")) return -1;
  return match[1] === "" ? Math.max(0, size - Number(match[2])) : Number(match[1]);
}

/** R2 reports either {offset, length?} or {suffix}; turn it into absolute bytes. */
function byteRange(range, size) {
  if ("suffix" in range) {
    const length = Math.min(range.suffix, size);
    return { offset: size - length, length };
  }
  const offset = range.offset ?? 0;
  return { offset, length: range.length ?? size - offset };
}
