"""Fetch a finite list of ISIE context PDFs through the required machine proxy.

Availability is provider-specific. A missing fetch never means a location is absent.
No acceptance, face selection, installed geometry or private Google config is used.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit

import fitz
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read

PROXY = 'http://127.0.0.1:8888'
MAX_BYTES = 30 * 1024 * 1024


def official_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or parsed.hostname != 'www.isie.tn'
            or parsed.username or parsed.password or parsed.port
            or not parsed.path.startswith('/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/')
            or not parsed.path.endswith('.pdf') or parsed.query or parsed.fragment):
        raise ValueError('Only exact official ISIE PDF context URLs are allowed')


def capture(row, destination):
    started = datetime.now(timezone.utc).isoformat()
    record = dict(row, startedAtUtc=started, proxy=PROXY, tlsVerification=True,
                  sourceScopeAccepted=False, independentQaPassed=False, credit=0,
                  googleChecked=False, osmChecked=False)
    try:
        with requests.Session() as session:
            session.trust_env = False
            session.proxies = {'http': PROXY, 'https': PROXY}
            # No retry or alternative route. Preserve redirects as a separate hold.
            with session.get(row['url'], timeout=(10, 20), stream=True, allow_redirects=False) as response:
                record.update(httpStatus=response.status_code, finalUrl=response.url,
                              contentType=response.headers.get('Content-Type'),
                              redirectLocation=response.headers.get('Location'))
                payload = bytearray()
                for chunk in response.iter_content(65536):
                    payload.extend(chunk)
                    if len(payload) > MAX_BYTES:
                        raise ValueError('Finite source size limit exceeded')
                raw = destination / (row['code'] + '-response.bin')
                with raw.open('xb') as stream:
                    stream.write(payload)
                record['response'] = pin(raw)
                if response.status_code != 200 or not payload.startswith(b'%PDF-'):
                    record['status'] = 'UNKNOWN_ISIE_FETCH_UNAVAILABLE_OR_NON_PDF'
                else:
                    pdf = destination / (row['code'] + '-original.pdf')
                    with pdf.open('xb') as stream:
                        stream.write(payload)
                    with fitz.open(pdf) as doc:
                        record['pageCount'] = len(doc)
                        record['pages'] = []
                        if len(doc) != 1:
                            raise ValueError('Expected one original locality map page')
                        for index, page in enumerate(doc):
                            image = destination / f"{row['code']}-original-page-{index}.png"
                            page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False).save(image)
                            text_path = destination / f"{row['code']}-native-text-{index}.txt"
                            text_path.write_text(page.get_text(), encoding='utf-8')
                            record['pages'].append({'index': index, 'image': pin(image), 'nativeText': pin(text_path)})
                    record['pdf'] = pin(pdf)
                    record['status'] = 'FETCHED_PDF_REQUIRES_VISUAL_IDENTITY_AND_NATIVE_REVIEW'
    except (requests.RequestException, ValueError, RuntimeError) as error:
        record.update(status='UNKNOWN_ISIE_FETCH_OR_PARSE_FAILURE', error=str(error))
    record['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    checked(manifest['bindings'])
    rows = manifest['sources']
    if len(rows) != 3 or {row['code'] for row in rows} != {'235653', '245858', '425356'}:
        raise ValueError('Only the pinned three missing neighbor contexts are authorized')
    for row in rows:
        official_url(row['url'])
    args.output.mkdir(exist_ok=False)
    with ThreadPoolExecutor(max_workers=3) as executor:
        records = list(executor.map(lambda row: capture(row, args.output), rows))
    checked(manifest['bindings'])
    result = {'schemaVersion': 1, 'manifest': pin(args.manifest), 'sources': records,
              'status': 'FINITE_PROVIDER_CONTEXT_CAPTURE_COMPLETE', 'workers': 3,
              'allNetworkThroughRequiredProxy': True, 'privateGoogleConfigRead': False,
              'absenceAcrossProvidersAsserted': False, 'sourceScopeAccepted': False,
              'geometryChanged': False, 'credit': 0}
    (args.output / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({row['code']: row['status'] for row in records}))


if __name__ == '__main__':
    main()
