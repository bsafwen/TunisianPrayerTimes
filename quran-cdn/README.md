# Quran CDN — Cloudflare Worker

Serves the Quran media packs (mushaf page scans and recitations). Installs that did not come from
Google Play (the APK on GitHub Releases, Android Studio runs) download every pack from it. Play
installs get the page scans and the first ten reciters through Play Asset Delivery, and only later
reciters from here.

```
GET | HEAD  https://tunisian-quran-cdn.baroudi-safwen.workers.dev/v1/packs/<pack>-<sha12>.zip
```

- The packs live in the R2 bucket `tunisian-quran-assets` (location hint: Western Europe).
- Keys are content-addressed and never overwritten, so responses are cached as immutable.
- `Range` requests let an interrupted download resume; a range past the end gets `416`.
- Every other path is `404`; the Worker cannot write to the bucket.
- The app checks each archive's size and SHA-256 against `quran/packs.json` before unpacking it.

It is separate from the legacy `worker/` (mawaqit proxy), which can be removed independently.

## Cost

R2 never charges for downloads, and each reciter takes about 1.1 GB of R2's free 10 GB. One pack
is one request: 9 for a whole Play reciter, about 40 for a later one, against the account's
100,000 free Worker requests a day. Past that, the Workers Paid plan ($5 a month) includes 10
million requests a month.

## Deploy

```bash
cd quran-cdn
npm install
CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=… npx wrangler deploy
```

The bucket was created once with `npx wrangler r2 bucket create tunisian-quran-assets --location weur`.

## Publish new or changed media

From the repository root, with the media in local folders (the MP3s and the page scans, as
folders or zips). Recitations are first converted to 64 kbps mono MP3 at a constant bitrate (needs
ffmpeg); keep the converted folder, since the layout records those exact files:

```bash
python3 scripts/quran_assets.py convert --from-dir path/to/original-mp3s --to path/to/hosary
python3 scripts/quran_assets.py layout --from-dir path/to/hosary --from-dir path/to/pages.zip \
    --cdn https://tunisian-quran-cdn.baroudi-safwen.workers.dev/
CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=… python3 scripts/quran_assets.py publish
```

`layout` rewrites `android-app/quran-assets/manifest.tsv`, `quran/packs.json` and the pack
modules; commit them. `publish` uploads only the archives the CDN does not serve yet, through
Cloudflare's R2 API: it needs Python alone, not Node or wrangler.

Changing which files a pack holds makes Play users download that pack again, so keep the layout
stable once it has shipped. The token needs *Workers R2 Storage: Edit* to publish and
*Workers Scripts: Edit* to deploy.
