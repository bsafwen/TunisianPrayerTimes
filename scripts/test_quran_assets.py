"""Quran media pack checks: python3 -m unittest scripts/test_quran_assets.py."""
import collections
import contextlib
import functools
import http.server
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
import threading
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('quran_assets', Path(__file__).parent / 'quran_assets.py')
assets = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = assets  # dataclasses look the module up while it loads
SPEC.loader.exec_module(assets)

MB = assets.MB


def recording(fill: int, size: int, kbps_index: int = 5, mono: bool = True, tag: bytes = b'Info') -> bytes:
    """Starts like an MPEG-1 Layer III file (64 kbps mono CBR by default): a frame header, then the tag."""
    header = bytes([0xFF, 0xFB, kbps_index << 4 | 0x00, 0xC0 if mono else 0x00])
    start = header + bytes(17 if mono else 32) + tag
    return start + bytes([fill]) * max(0, size - len(start))


def run(*argv):
    output = io.StringIO()
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        code = assets.main(list(argv))
    return code, output.getvalue()


class GroupingTest(unittest.TestCase):
    def test_packs_hold_consecutive_surahs_up_to_the_target(self):
        groups = assets.group_surahs([10, 10, 10, 25, 5, 5], target=30, limit=100)
        self.assertEqual([[1, 2, 3], [4, 5], [6]], groups)
        self.assertEqual(list(range(1, 7)), [n for group in groups for n in group])

    def test_a_surah_over_the_target_gets_its_own_pack(self):
        self.assertEqual([[1], [2], [3, 4]], assets.group_surahs([5, 80, 10, 10], target=30, limit=100))

    def test_a_surah_over_the_consent_limit_is_refused(self):
        with self.assertRaises(assets.LayoutError):
            assets.group_surahs([5, 200], target=30, limit=200)

    def test_play_reciters_are_split_as_evenly_as_consecutive_packs_allow(self):
        sizes = [1, 84, 50, 40, 9, 30] + [n % 7 + 1 for n in range(108)]
        groups = assets.split_surahs(sizes, 9, limit=200)
        self.assertLessEqual(len(groups), 9)
        self.assertEqual(list(range(1, 115)), [n for group in groups for n in group])
        largest = max(sum(sizes[n - 1] for n in group) for group in groups)
        # The best largest pack over every split into 9 runs of consecutive surahs.
        best = [[float('inf')] * (len(sizes) + 1) for _ in range(10)]
        best[0][0] = 0
        for packs in range(1, 10):
            for end in range(1, len(sizes) + 1):
                best[packs][end] = min(max(best[packs - 1][start], sum(sizes[start:end])) for start in range(end))
        self.assertEqual(best[9][len(sizes)], largest)

    def test_the_first_ten_reciters_get_play_and_keep_it(self):
        reciters = [f'r{n:02d}' for n in range(12)]
        first = assets.assign_deliveries(reciters, {})
        self.assertEqual(['play'] * 10 + ['cdn'] * 2, [first[r] for r in reciters])
        # A new reciter that sorts first does not take the place of one already laid out.
        later = assets.assign_deliveries(['a_new'] + reciters, first)
        self.assertEqual('cdn', later['a_new'])
        self.assertEqual(first, {r: later[r] for r in reciters})
        # A place a removed reciter frees goes to a new reciter; the others stay where they are.
        freed = assets.assign_deliveries(reciters[1:] + ['z_new'], first)
        self.assertEqual('play', freed['z_new'])
        self.assertEqual('cdn', freed['r10'])


class Mp3FormatTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())

    def format_of(self, content):
        path = self.root / 'x.mp3'
        path.write_bytes(content)
        return assets.mp3_format(path)

    def test_reads_bitrate_channels_and_constant_bitrate_from_the_first_frame(self):
        self.assertEqual((64, True, True), self.format_of(recording(1, 600)))
        self.assertEqual((128, False, True), self.format_of(recording(1, 600, kbps_index=9, mono=False)))
        self.assertEqual((64, True, False), self.format_of(recording(1, 600, tag=b'Xing')))
        # An ID3v2 tag with cover art comes first in mp3quran's files.
        tagged = b'ID3\x04\x00\x00\x00\x00\x00\x20' + b'\xff' * 32 + recording(1, 600, kbps_index=9, mono=False)
        self.assertEqual((128, False, True), self.format_of(tagged))
        self.assertIsNone(self.format_of(b'RIFF' + bytes(600)))

    def test_reads_mpeg2_frames_without_matching_look_alikes_in_their_audio(self):
        # MPEG-2 Layer III, index 4 = 32 kbps, 16 kHz, mono; its audio data happens to hold an MPEG-1 header.
        mpeg2 = bytes([0xFF, 0xF3, 4 << 4 | 0x08, 0xC0]) + bytes(17) + b'Info' + b'\xff\xfb\x90\x00' + bytes(600)
        self.assertEqual((32, True, True), self.format_of(mpeg2))

    def test_recitations_are_64_kbps_mono_constant_bitrate(self):
        def accepted(content):
            path = self.root / 'x.mp3'
            path.write_bytes(content)
            return assets.is_recitation_format(path)
        self.assertTrue(accepted(recording(1, 600)))
        self.assertFalse(accepted(recording(1, 600, kbps_index=1)))  # 32 kbps
        self.assertFalse(accepted(recording(1, 600, kbps_index=7)))  # 96 kbps
        self.assertFalse(accepted(recording(1, 600, mono=False)))
        self.assertFalse(accepted(recording(1, 600, tag=b'Xing')))


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())

    def file(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def test_equal_files_give_byte_identical_archives(self):
        a = self.file('a/001.mp3', b'first')
        b = self.file('a/002.mp3', b'second')
        one = assets.build_archive([('quran/audio/x/002.mp3', b), ('quran/audio/x/001.mp3', a)], self.root / 'one.zip')
        a.touch()  # A different modification time must not change the archive.
        two = assets.build_archive([('quran/audio/x/001.mp3', a), ('quran/audio/x/002.mp3', b)], self.root / 'two.zip')
        self.assertEqual(one, two)
        with zipfile.ZipFile(self.root / 'one.zip') as zipped:
            self.assertEqual(['quran/audio/x/001.mp3', 'quran/audio/x/002.mp3'], zipped.namelist())
            self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED for i in zipped.infolist()))

    def test_extraction_refuses_entries_that_escape_the_folder(self):
        evil = self.root / 'evil.zip'
        with zipfile.ZipFile(evil, 'w') as zipped:
            zipped.writestr('../outside.mp3', b'x')
        with self.assertRaises(assets.LayoutError):
            assets.extract_archive(evil, self.root / 'out', {'../outside.mp3': 1})
        self.assertFalse((self.root / 'outside.mp3').exists())

    def test_extraction_refuses_unexpected_or_resized_files(self):
        archive = self.root / 'pack.zip'
        assets.build_archive([('quran/pages/1.webp', self.file('1.webp', b'abc'))], archive)
        with self.assertRaises(assets.LayoutError):
            assets.extract_archive(archive, self.root / 'out', {'quran/pages/1.webp': 4})
        with self.assertRaises(assets.LayoutError):
            assets.extract_archive(archive, self.root / 'out', {'quran/pages/2.webp': 3})
        assets.extract_archive(archive, self.root / 'out', {'quran/pages/1.webp': 3})
        self.assertEqual(b'abc', (self.root / 'out' / 'quran/pages/1.webp').read_bytes())


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    """Serves files like the CDN, which refuses urllib's default user agent."""

    def refused(self):
        if self.headers.get('User-Agent', '').startswith('Python-urllib'):
            self.send_error(403)
            return True
        return False

    def do_GET(self):
        if not self.refused():
            super().do_GET()

    def do_HEAD(self):
        if not self.refused():
            super().do_HEAD()

    def do_PUT(self):
        # Cloudflare's R2 API: PUT /accounts/<id>/r2/buckets/<bucket>/objects/<key> with a bearer token.
        prefix = '/accounts/test-account/r2/buckets/test-bucket/objects/'
        if self.refused():
            return
        if self.headers.get('Authorization') != 'Bearer test-token' or not self.path.startswith(prefix):
            body = b'{"success": false, "errors": [{"code": 10000, "message": "Authentication error"}]}'
            self.send_response(403)
        else:
            target = Path(self.directory) / self.path[len(prefix):]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(self.rfile.read(int(self.headers['Content-Length'])))
            body = b'{"success": true, "errors": []}'
            self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class EndToEndTest(unittest.TestCase):
    """layout → serve the archives over HTTP → stage, in a throwaway repository."""

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        patches = {
            'REPO': self.repo,
            'ANDROID': self.repo / 'android-app',
            'TIMINGS_DIR': self.repo / 'android-app/app/src/main/assets/quran/audio',
            'PAGES_JSON': self.repo / 'android-app/app/src/main/assets/quran/pages/pages.json',
            'LAYOUT_JSON': self.repo / 'android-app/app/src/main/assets/quran/packs.json',
            'MANIFEST': self.repo / 'android-app/quran-assets/manifest.tsv',
            'BUILD': self.repo / 'android-app/quran-assets/build',
            'PACKS_ROOT': self.repo / 'android-app/quran-packs',
        }
        for name, value in patches.items():
            original = getattr(assets, name)
            setattr(assets, name, value)
            self.addCleanup(setattr, assets, name, original)

        timings = assets.TIMINGS_DIR / 'hosary' / 'timings.json'
        timings.parent.mkdir(parents=True)
        timings.write_text(json.dumps({'version': 1, 'reciterId': 'hosary-qaloun', 'surahs': [
            {'number': n, 'assetPath': f'quran/audio/hosary/{n:03d}.mp3'} for n in range(1, 115)]}))
        assets.PAGES_JSON.parent.mkdir(parents=True)
        assets.PAGES_JSON.write_text(json.dumps({'pages': [{'n': n, 'file': f'{n:03d}.webp'} for n in (1, 2, 3)]}))

        self.media = self.repo / 'media'
        self.audio = self.media / 'hosary'
        self.audio.mkdir(parents=True)
        (self.media / 'pages').mkdir()
        for n in range(1, 115):
            (self.audio / f'{n:03d}.mp3').write_bytes(recording(n, 4000 if n == 2 else 300))
        for n in (1, 2, 3):
            (self.media / 'pages' / f'{n:03d}.webp').write_bytes(b'page%d' % n)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=str(self.repo / 'served')))
        (self.repo / 'served' / 'v1' / 'packs').mkdir(parents=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.cdn = f'http://127.0.0.1:{server.server_address[1]}/'

    def run_command(self, *argv):
        return run(*argv)

    def add_reciter(self, folder):
        timings = assets.TIMINGS_DIR / folder / 'timings.json'
        timings.parent.mkdir(parents=True)
        timings.write_text(json.dumps({'version': 1, 'reciterId': f'{folder}-id', 'surahs': [
            {'number': n, 'assetPath': f'quran/audio/{folder}/{n:03d}.mp3'} for n in range(1, 115)]}))
        (self.media / folder).mkdir()
        for n in range(1, 115):
            (self.media / folder / f'{n:03d}.mp3').write_bytes(recording(n, 200 + n))

    def layout(self, *sources):
        sources = sources or (self.audio, self.media / 'pages')
        code, output = self.run_command('layout', *[a for s in sources for a in ('--from-dir', str(s))],
                                        '--cdn', self.cdn, '--target-mb', str(1000 / MB))
        self.assertEqual(0, code, output)
        for archive in assets.BUILD.glob('*.zip'):
            (self.repo / 'served' / 'v1' / 'packs' / archive.name).write_bytes(archive.read_bytes())
        return json.loads(assets.LAYOUT_JSON.read_text())

    def pack_holding(self, layout, path):
        return next(p for p in layout['packs'] if any(f['path'] == path for f in p['files']))

    def test_layout_covers_every_file_once_and_writes_pack_modules(self):
        layout = self.layout()
        names = [p['name'] for p in layout['packs']]
        self.assertEqual('quran_pages', names[0])
        audio = [p for p in layout['packs'] if p['kind'] == 'audio']
        self.assertLessEqual(len(audio), assets.PLAY_PACKS_PER_RECITER)
        surahs = [n for p in audio for n in range(p['surahs'][0], p['surahs'][1] + 1)]
        self.assertEqual(list(range(1, 115)), surahs)
        self.assertEqual({'play'}, {p['delivery'] for p in layout['packs']})
        rows = assets.read_manifest()
        self.assertEqual(114 + 3, len(rows))
        self.assertEqual({'play'}, {r.delivery for r in rows})
        self.assertEqual('https://cdn.mp3quran.net/audio/mahmoud-husary/r5/001.mp3',
                         next(r.origin for r in rows if r.path.endswith('/001.mp3')))
        for name in names:
            self.assertEqual(assets.GENERATED_BUILD_FILE, (assets.PACKS_ROOT / name / 'build.gradle.kts').read_text())
            self.assertRegex(name, r'^[A-Za-z][A-Za-z0-9_]*$')

    def test_reciters_after_the_tenth_come_from_the_worker_only(self):
        for n in range(1, 11):  # With hosary, r10 is the eleventh reciter.
            self.add_reciter(f'r{n:02d}')
        layout = self.layout(self.media)
        deliveries = collections.defaultdict(set)
        for pack in layout['packs']:
            if pack['kind'] == 'audio':
                deliveries[pack['reciterId']].add(pack['delivery'])
        self.assertEqual({'cdn'}, deliveries.pop('r10-id'))
        self.assertEqual({'play'}, set.union(*deliveries.values()))
        self.assertEqual(10, len(deliveries))
        play = [p for p in layout['packs'] if p['delivery'] == 'play']
        cdn = [p for p in layout['packs'] if p['delivery'] == 'cdn']
        self.assertLessEqual(len(play), assets.MAX_PLAY_PACKS)
        # Each reciter's files come from its own folder, though every reciter has a 001.mp3.
        r05 = self.pack_holding(layout, 'quran/audio/r05/001.mp3')
        self.assertEqual(200 + 1, r05['files'][0]['bytes'])
        # Only Play packs become modules and get staged; the Worker serves the rest.
        self.assertTrue(all((assets.PACKS_ROOT / p['name'] / 'build.gradle.kts').is_file() for p in play))
        self.assertFalse(any((assets.PACKS_ROOT / p['name']).exists() for p in cdn))
        self.assertEqual({'cdn'}, {r.delivery for r in assets.read_manifest() if r.pack in {p['name'] for p in cdn}})
        code, output = self.run_command('stage', '--from-dir', str(self.media))
        self.assertEqual(0, code, output)
        self.assertTrue(all(assets.is_staged(p) for p in play))
        self.assertFalse(any((assets.PACKS_ROOT / p['name']).exists() for p in cdn))

        # A reciter added later goes to the Worker, though its folder sorts first; the others stay.
        self.add_reciter('a_new')
        again = self.layout(self.media)
        self.assertEqual({p['name']: p['delivery'] for p in layout['packs']},
                         {p['name']: p['delivery'] for p in again['packs'] if p.get('reciterId') != 'a_new-id'})
        self.assertEqual({'cdn'}, {p['delivery'] for p in again['packs'] if p.get('reciterId') == 'a_new-id'})

    def test_layout_refuses_recordings_not_yet_converted(self):
        (self.audio / '050.mp3').write_bytes(recording(50, 300, kbps_index=9, mono=False))
        code, output = self.run_command('layout', '--from-dir', str(self.audio), '--from-dir', str(self.media / 'pages'),
                                        '--cdn', self.cdn)
        self.assertEqual(1, code)
        self.assertIn('050.mp3 is 128 kbps stereo', output)
        self.assertIn('convert', output)

    def publish(self, token):
        api, environ = assets.CLOUDFLARE_API, dict(assets.os.environ)
        assets.CLOUDFLARE_API = self.cdn.rstrip('/')
        assets.os.environ.update(CLOUDFLARE_ACCOUNT_ID='test-account', CLOUDFLARE_API_TOKEN=token)
        try:
            return self.run_command('publish', '--bucket', 'test-bucket')
        finally:
            assets.CLOUDFLARE_API = api
            assets.os.environ.clear()
            assets.os.environ.update(environ)

    def test_publish_uploads_what_the_cdn_lacks_and_skips_the_rest(self):
        self.layout()
        served = self.repo / 'served' / 'v1' / 'packs'
        kept = sorted(served.iterdir())[0]
        for archive in served.iterdir():
            if archive != kept:
                archive.unlink()
        code, output = self.publish('test-token')
        self.assertEqual(0, code, output)
        self.assertIn('already published', output)
        self.assertEqual(sorted(p.name for p in assets.BUILD.glob('*.zip')), sorted(p.name for p in served.iterdir()))
        for archive in assets.BUILD.glob('*.zip'):
            self.assertEqual(archive.read_bytes(), (served / archive.name).read_bytes())

    def test_publish_reports_what_cloudflare_refused(self):
        self.layout()
        for archive in (self.repo / 'served' / 'v1' / 'packs').iterdir():
            archive.unlink()
        code, output = self.publish('wrong-token')
        self.assertEqual(1, code)
        self.assertIn('Authentication error', output)

    def test_stage_downloads_verifies_and_skips_what_is_already_staged(self):
        layout = self.layout()
        cache = self.repo / 'cache'
        code, output = self.run_command('stage', '--cache', str(cache))
        self.assertEqual(0, code, output)
        staged = assets.PACKS_ROOT / 'quran_pages' / 'src/main/assets/quran/pages/002.webp'
        self.assertEqual(b'page2', staged.read_bytes())
        self.assertEqual(len(layout['packs']), len(list(cache.glob('*.zip'))))
        code, output = self.run_command('stage', '--cache', str(cache))
        self.assertEqual(0, code, output)
        self.assertNotIn('staged\n', output.replace('Every pack is staged', ''))

    def test_stage_refuses_an_archive_that_does_not_match(self):
        layout = self.layout()
        pages = next(p for p in layout['packs'] if p['name'] == 'quran_pages')
        served = self.repo / 'served' / pages['archive']['key']
        served.write_bytes(served.read_bytes().replace(b'page1', b'PAGE1'))
        code, output = self.run_command('stage', '--cache', str(self.repo / 'cache'), '--packs', 'quran_pages')
        self.assertEqual(1, code)
        self.assertIn('does not match', output)

    def test_a_stale_partial_download_is_replaced_when_the_server_cannot_resume(self):
        layout = self.layout()
        pages = next(p for p in layout['packs'] if p['name'] == 'quran_pages')
        cache = self.repo / 'cache'
        cache.mkdir()
        partial = cache / (Path(pages['archive']['key']).name + '.part')
        partial.write_bytes(b'stale bytes from an older attempt')
        code, output = self.run_command('stage', '--cache', str(cache), '--packs', 'quran_pages')
        self.assertEqual(0, code, output)
        self.assertFalse(partial.exists())

    def test_a_download_cut_short_is_kept_and_resumed(self):
        target = self.repo / 'cache' / 'pack.zip'
        target.parent.mkdir()
        body = bytes(range(256)) * 400

        class CutShort(QuietHandler):
            # Promises the whole file, then closes after the first half.
            def do_GET(self):
                if self.refused():
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body[:len(body) // 2])
                self.wfile.flush()
                self.close_connection = True

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), CutShort)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with self.assertRaises(assets.Incomplete):
            assets.download(f'http://127.0.0.1:{server.server_address[1]}/pack.zip', target, len(body))
        self.assertFalse(target.exists())
        self.assertEqual(len(body) // 2, (target.parent / 'pack.zip.part').stat().st_size)

    def test_check_finds_a_changed_local_file(self):
        self.layout()
        code, _ = self.run_command('check', '--from-dir', str(self.audio), '--from-dir', str(self.media / 'pages'))
        self.assertEqual(0, code)
        (self.audio / '050.mp3').write_bytes(b're-encoded')
        code, output = self.run_command('check', '--from-dir', str(self.audio), '--only-present')
        self.assertEqual(1, code)
        self.assertIn('050.mp3', output)

    def test_stage_from_local_folders_needs_no_network(self):
        pack = self.pack_holding(self.layout(), 'quran/audio/hosary/002.mp3')['name']
        code, output = self.run_command('stage', '--from-dir', str(self.audio),
                                        '--from-dir', str(self.media / 'pages'), '--packs', pack)
        self.assertEqual(0, code, output)
        self.assertEqual(4000, (assets.PACKS_ROOT / pack / 'src/main/assets/quran/audio/hosary/002.mp3').stat().st_size)


@unittest.skipUnless(shutil.which('ffmpeg'), 'needs ffmpeg')
class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.originals = self.root / 'originals'
        self.originals.mkdir()

    def encode(self, name, *options):
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=3', *options,
                        str(self.originals / name)], check=True)

    def test_convert_gives_constant_bitrate_mono_and_resumes(self):
        self.encode('001.mp3', '-ac', '2', '-c:a', 'libmp3lame', '-b:a', '128k')
        self.encode('002.mp3', '-ac', '1', '-c:a', 'libmp3lame', '-b:a', '64k')
        out = self.root / 'converted'
        code, output = run('convert', '--from-dir', str(self.originals), '--to', str(out), '--jobs', '2')
        self.assertEqual(0, code, output)
        self.assertEqual((64, True, True), assets.mp3_format(out / '001.mp3'))
        # Already in the format: copied, not encoded a second time.
        self.assertEqual((self.originals / '002.mp3').read_bytes(), (out / '002.mp3').read_bytes())
        converted = (out / '001.mp3').read_bytes()
        self.assertNotIn(b'ID3', converted[:3])
        code, output = run('convert', '--from-dir', str(self.originals), '--to', str(out))
        self.assertEqual(0, code, output)
        self.assertNotIn('001.mp3:', output)
        self.assertEqual(converted, (out / '001.mp3').read_bytes())

    def test_convert_never_writes_over_the_originals(self):
        self.encode('001.mp3', '-ac', '2', '-c:a', 'libmp3lame', '-b:a', '128k')
        before = (self.originals / '001.mp3').read_bytes()
        code, output = run('convert', '--from-dir', str(self.originals), '--to', str(self.originals))
        self.assertEqual(1, code, output)
        self.assertEqual(before, (self.originals / '001.mp3').read_bytes())


if __name__ == '__main__':
    unittest.main()
