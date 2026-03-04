# Mawaqit Proxy — Cloudflare Worker

This Worker acts as a **CORS-transparent proxy** between your GitHub Pages site and `mawaqit.net`.  
It handles authentication, session management, and form submission server-side so the browser never hits a CORS wall.

## How it works

```
Browser (GitHub Pages)
  │
  │  POST /api/login  {email, password}
  │  GET  /api/configure?mosqueId=xxx  + X-Auth-Cookies
  │  POST /api/configure?mosqueId=xxx  + X-Auth-Cookies + form body
  ▼
Cloudflare Worker (this folder)
  │
  │  Forwards requests with proper cookies and headers
  ▼
mawaqit.net  (no CORS issue — server-to-server)
```

## Deploy in 5 minutes

### 1 · Install Wrangler

```bash
cd worker
npm install
```

### 2 · Log in to Cloudflare

```bash
npx wrangler login
```

### 3 · Add your GitHub Pages origin

Open `index.js` and add your site to `ALLOWED_ORIGINS`:

```js
const ALLOWED_ORIGINS = [
  // …existing entries…
  "https://YOUR-GITHUB-USERNAME.github.io",
];
```

### 4 · Deploy

```bash
npm run deploy
```

Wrangler will print a URL like:

```
https://mawaqit-proxy.YOUR-SUBDOMAIN.workers.dev
```

Copy that URL — you'll paste it into the **Worker URL** field on the website.

### 5 · Test locally (optional)

```bash
npm run dev
# Worker runs on http://localhost:8787
```

---

## API reference

### `POST /api/login`

```json
// Request body
{ "email": "you@example.com", "password": "secret" }

// Success response
{ "ok": true, "cookies": "<base64-encoded-json>" }

// Error response
{ "error": "Login failed — check your credentials" }
```

The `cookies` value is a `base64(JSON({name: value}))` blob.  
It is stored in the browser's `localStorage` and sent back in the `X-Auth-Cookies` header on subsequent calls.

### `GET /api/configure?mosqueId=<id>`

Header: `X-Auth-Cookies: <blob>`  
Returns the raw HTML of the mawaqit.net configure page.  
The browser parses it with `DOMParser` to extract form fields and the CSRF token.

### `POST /api/configure?mosqueId=<id>`

Header: `X-Auth-Cookies: <blob>`  
Header: `Content-Type: application/x-www-form-urlencoded`  
Body: full URL-encoded form (assembled by `mawaqit-push.js` in the browser)

```json
// Success response
{ "ok": true, "message": "Prayer times updated on mawaqit.net ✓" }
```

---

## Security notes

- **Credentials are never stored** in the Worker — they are used once to authenticate with mawaqit.net and then discarded.  
- The resulting session token is stored only in the user's own `localStorage`.  
- Lock `ALLOWED_ORIGINS` to your exact GitHub Pages URL before deploying to production.  
- The Workers free tier (100 000 requests/day) is more than sufficient for this use case.
