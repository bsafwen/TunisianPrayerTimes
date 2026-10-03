# Quran CDN — Cloudflare Worker

Serves the Quran media packs (mushaf page scans and recitations) to app installs that did not
come from Google Play: the APK on GitHub Releases and Android Studio runs. Play installs get the
same packs through Play Asset Delivery.

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

Within the free plans: the packs take about 2.4 GB of R2's free 10 GB, R2 never charges for
downloads, and one pack is one request (about 60 for the whole recitation) against the
account's 100,000 free Worker requests a day.

## Deploy

```bash
cd quran-cdn
npm install
CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=… npx wrangler deploy
```

The bucket was created once with `npx wrangler r2 bucket create tunisian-quran-assets --location weur`.

## Publish new or changed media

From the repository root, with the media in local folders (the MP3s and the page scans, as
folders or zips):

```bash
python3 scripts/quran_assets.py layout --from-dir path/to/mp3s --from-dir path/to/pages.zip \
    --cdn https://tunisian-quran-cdn.baroudi-safwen.workers.dev/
CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=… python3 scripts/quran_assets.py publish
```

`layout` rewrites `android-app/quran-assets/manifest.tsv`, `quran/packs.json` and the pack
modules; commit them. `publish` uploads only the archives the CDN does not serve yet.

Changing which files a pack holds makes Play users download that pack again, so keep the layout
stable once it has shipped. The token needs *Workers R2 Storage: Edit* to publish and
*Workers Scripts: Edit* to deploy.
