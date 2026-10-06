"""Fetch a finite pinned ISIE URL set through the mandatory local proxy.

No source-scope acceptance, geometry change, or geographic credit is produced.
Every attempt and successful original PDF is retained. No direct-route fallback.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import quote, urlsplit, urlunsplit

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read

PROXY = 'http://127.0.0.1:8888'


def save_new(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def fetch(row, output, allow_unverified_tls):
    code, url = row['code'], row['url']
    parts = urlsplit(url)
    if parts.scheme != 'https' or parts.hostname != 'www.isie.tn' or parts.query or parts.fragment:
        raise ValueError('Only declared official ISIE HTTPS URLs are permitted')
    request_url = urlunsplit((parts.scheme, parts.netloc, quote(parts.path, safe='/%'), '', ''))
    session = requests.Session()
    session.trust_env = False
    session.proxies = {'http': PROXY, 'https': PROXY}
    attempts = []
    modes = (True, False) if allow_unverified_tls else (True,)
    for verify in modes:
        started = datetime.now(timezone.utc).isoformat()
        try:
            response = session.get(request_url, timeout=(10, 55), verify=verify, allow_redirects=False)
            body = response.content
            attempt = {'startedAtUtc': started, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
                       'tlsVerified': verify, 'httpStatus': response.status_code,
                       'responseBytes': len(body), 'responseSha256': hashlib.sha256(body).hexdigest(),
                       'contentType': response.headers.get('Content-Type'), 'redirectLocation': response.headers.get('Location')}
            attempts.append(attempt)
            valid = response.status_code == 200 and body.startswith(b'%PDF-')
            suffix = '.pdf' if valid else '.response'
            target = output / f'{code}-attempt-{len(attempts)}{suffix}'
            with target.open('xb') as stream:
                stream.write(body)
            attempt['savedResponse'] = pin(target)
            if valid:
                result = {**row, 'status': 'FETCHED_ORIGINAL_PDF_SOURCE_ONLY', 'sourcePdf': pin(target),
                          'proxy': PROXY, 'requestUrl': request_url, 'attempts': attempts,
                          'sourceActor': '/root', 'sourceScopeAccepted': False, 'geographicCredit': 0}
                save_new(output / f'{code}-fetch.json', result)
                return result
            break
        except requests.exceptions.SSLError as exc:
            attempts.append({'startedAtUtc': started, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
                             'tlsVerified': verify, 'errorType': type(exc).__name__, 'error': str(exc)})
            if verify and allow_unverified_tls:
                continue
            break
        except requests.exceptions.RequestException as exc:
            attempts.append({'startedAtUtc': started, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
                             'tlsVerified': verify, 'errorType': type(exc).__name__, 'error': str(exc)})
            break
    result = {**row, 'status': 'SOURCE_FETCH_UNAVAILABLE', 'proxy': PROXY,
              'requestUrl': request_url, 'attempts': attempts, 'sourceScopeAccepted': False, 'geographicCredit': 0}
    save_new(output / f'{code}-fetch.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    original_index = read(checked(spec['sourceIndex']))
    declared_urls = {row['url'] for row in original_index}
    rows = spec['sources']
    codes = [row['code'] for row in rows]
    if not 1 <= len(rows) <= 32 or len(set(codes)) != len(codes) or any(row['url'] not in declared_urls for row in rows):
        raise ValueError('Finite unique exact indexed source set required')
    if args.output.exists() or not args.output.parent.is_dir():
        raise ValueError('Fresh output below existing producer required')
    args.output.mkdir()
    results = []
    with ThreadPoolExecutor(max_workers=min(4, len(rows))) as pool:
        futures = [pool.submit(fetch, row, args.output, spec.get('allowUnverifiedTls') is True) for row in rows]
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            print(json.dumps({'code': result['code'], 'status': result['status']}, ensure_ascii=False), flush=True)
    checked(spec['sourceIndex'])
    report = {'status': 'FINITE_PROXY_SOURCE_FETCH_COMPLETE', 'manifest': pin(args.manifest),
              'program': pin(Path(__file__)), 'sources': sorted(results, key=lambda row: row['code']),
              'successfulPdfCount': sum(row['status'] == 'FETCHED_ORIGINAL_PDF_SOURCE_ONLY' for row in results),
              'sourceScopeAccepted': False, 'geographicCredit': 0}
    save_new(args.output / 'fetch-report.json', report)
    print(json.dumps({'successfulPdfCount': report['successfulPdfCount'], 'total': len(rows), 'credit': 0}), flush=True)


if __name__ == '__main__':
    main()
