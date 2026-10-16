"""
Shared plumbing for the official-table collectors (plan 9.3).

Fetches are read-only and politely paced (one request every few seconds), go through the system proxy (urllib reads the
environment and Windows' Internet settings) and always verify TLS: never turn verification off. Raw files are saved,
byte for byte, under the git-ignored test-data-local/official/<cc>/, each with a row in that folder's MANIFEST.tsv:
file, bytes, SHA-256, fetch time (UTC), source URL and a note. Nothing here is committed (A7).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import ssl
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
# The main checkout's test-data-local when run from a worktree under .claude/worktrees/, as the fits read it.
_SHARED = ROOT.parent.parent.parent / "test-data-local"
LOCAL_DATA = _SHARED if _SHARED.exists() else ROOT / "test-data-local"
OFFICIAL = LOCAL_DATA / "official"

USER_AGENT = "TunisianPrayerTimes-collector/1 (read-only; one request every few seconds)"
PAUSE_SECONDS = 3.0
MANIFEST_COLUMNS = ("file", "bytes", "sha256", "fetched", "source", "note")


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def clean(text: str) -> str:
    """A manifest cell: no tabs or line breaks."""
    return " ".join(str(text).split())


class Session:
    """One collector run: paced requests through the system proxy, and the files it saved."""

    def __init__(self, *, root: Path = OFFICIAL, pause: float = PAUSE_SECONDS, now=None):
        self.root = root
        self.pause = pause
        self.now = now or (lambda: dt.datetime.now(dt.timezone.utc))
        self.saved: list[Path] = []
        self._last = 0.0
        self._tls = ssl.create_default_context()

    def fetch(self, url: str, *, data: dict | bytes | None = None, headers: dict | None = None, timeout: float = 60.0) -> bytes:
        """GET, or POST when [data] is given (a dict is form-encoded). The system proxy applies; TLS is verified."""
        wait = self.pause - (time.monotonic() - self._last)
        if wait > 0:
            time.sleep(wait)
        body = urllib.parse.urlencode(data).encode("utf-8") if isinstance(data, dict) else data
        request = urllib.request.Request(url, data=body, headers={"User-Agent": USER_AGENT, **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=timeout, context=self._tls) as response:
                return response.read()
        finally:
            self._last = time.monotonic()

    def save(self, cc: str, name: str, content: bytes, source: str, note: str = "", once: bool = False) -> Path | None:
        """
        Writes official/<cc>/<name> (name may hold subfolders) and its manifest row. The same bytes already saved under
        that name are kept and give no new row (None); different bytes under a name already used are saved beside it
        with the fetch time appended, so a page that changed during the day keeps both copies. With [once], a name
        already saved is kept whatever the new bytes: a page named by its day whose only change is a token.
        """
        folder = self.root / cc.lower()
        path = folder / name
        fetched = self.now().strftime("%Y-%m-%dT%H:%M:%SZ")
        if path.exists():
            if once or sha256(path.read_bytes()) == sha256(content):
                return None
            path = path.with_name(f"{path.stem}-{fetched[11:19].replace(':', '')}{path.suffix}")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        manifest = folder / "MANIFEST.tsv"
        new = not manifest.exists()
        with open(manifest, "a", encoding="utf-8", newline="") as f:
            if new:
                f.write("\t".join(MANIFEST_COLUMNS) + "\n")
            row = (path.relative_to(folder).as_posix(), str(len(content)), sha256(content), fetched, source, note)
            f.write("\t".join(clean(cell) for cell in row) + "\n")
        self.saved.append(path)
        return path
