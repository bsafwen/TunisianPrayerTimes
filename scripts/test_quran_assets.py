"""Quran media pack checks: python3 -m unittest scripts/test_quran_assets.py."""
import contextlib
import functools
import http.server
import importlib.util
import io
import json
from pathlib import Path
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

    def test_the_real_recitation_sizes_fit_play_limits(self):
        # Measured Content-Length of mp3quran r5 001..114 is too long to inline; check the shape instead.
        sizes = [168 * MB] + [10 * MB] * 113
        groups = assets.group_surahs(sizes)
        self.assertLessEqual(len(groups), assets.MAX_AUDIO_PACKS)


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
        (self.media / 'audio').mkdir(parents=True)
        (self.media / 'pages').mkdir()
        for n in range(1, 115):
            (self.media / 'audio' / f'{n:03d}.mp3').write_bytes(bytes([n]) * (4000 if n == 2 else 300))
        for n in (1, 2, 3):
            (self.media / 'pages' / f'{n:03d}.webp').write_bytes(b'page%d' % n)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=str(self.repo / 'served')))
        (self.repo / 'served' / 'v1' / 'packs').mkdir(parents=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.cdn = f'http://127.0.0.1:{server.server_address[1]}/'

    def run_command(self, *argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = assets.main(list(argv))
        return code, output.getvalue()

    def layout(self):
        code, output = self.run_command('layout', '--from-dir', str(self.media / 'audio'),
                                        '--from-dir', str(self.media / 'pages'), '--cdn', self.cdn,
                                        '--target-mb', str(1000 / MB))
        self.assertEqual(0, code, output)
        for archive in assets.BUILD.glob('*.zip'):
            (self.repo / 'served' / 'v1' / 'packs' / archive.name).write_bytes(archive.read_bytes())
        return json.loads(assets.LAYOUT_JSON.read_text())

    def test_layout_covers_every_file_once_and_writes_pack_modules(self):
        layout = self.layout()
        names = [p['name'] for p in layout['packs']]
        self.assertEqual('quran_pages', names[0])
        audio = [p for p in layout['packs'] if p['kind'] == 'audio']
        surahs = [n for p in audio for n in range(p['surahs'][0], p['surahs'][1] + 1)]
        self.assertEqual(list(range(1, 115)), surahs)
        self.assertEqual(['quran/audio/hosary/002.mp3'], [f['path'] for f in audio[1]['files']])
        rows = assets.read_manifest()
        self.assertEqual(114 + 3, len(rows))
        self.assertEqual('https://cdn.mp3quran.net/audio/mahmoud-husary/r5/001.mp3',
                         next(r.origin for r in rows if r.path.endswith('/001.mp3')))
        for name in names:
            self.assertEqual(assets.GENERATED_BUILD_FILE, (assets.PACKS_ROOT / name / 'build.gradle.kts').read_text())
            self.assertRegex(name, r'^[A-Za-z][A-Za-z0-9_]*$')

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
        code, _ = self.run_command('check', '--from-dir', str(self.media / 'audio'), '--from-dir', str(self.media / 'pages'))
        self.assertEqual(0, code)
        (self.media / 'audio' / '050.mp3').write_bytes(b're-encoded')
        code, output = self.run_command('check', '--from-dir', str(self.media / 'audio'), '--only-present')
        self.assertEqual(1, code)
        self.assertIn('050.mp3', output)

    def test_stage_from_local_folders_needs_no_network(self):
        self.layout()
        code, output = self.run_command('stage', '--from-dir', str(self.media / 'audio'),
                                        '--from-dir', str(self.media / 'pages'), '--packs', 'quran_hosary_02')
        self.assertEqual(0, code, output)
        self.assertEqual(4000, (assets.PACKS_ROOT / 'quran_hosary_02/src/main/assets/quran/audio/hosary/002.mp3').stat().st_size)


if __name__ == '__main__':
    unittest.main()
