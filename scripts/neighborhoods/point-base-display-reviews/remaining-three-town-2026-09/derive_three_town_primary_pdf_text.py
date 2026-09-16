"""Export complete reviewed primary PDF pages verbatim; no product writes."""
from pathlib import Path
import datetime
import hashlib
import json
import shutil
import pymupdf

T = Path(__file__).resolve().parent.parent
W = T / 'work/three-town-primary-derived-evidence'
REPORT = T / 'outputs/three-town-primary-text-derivation-review.json'
SOURCE_REVIEW = T / 'outputs/remaining-three-town-source-identity-review.json'
SOURCE_HASH = '8a4b18b96a37d1fb64a7f897a421c6582322862f4b48345b4c5afc6433b38a08'

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def ref(p):
    return {'file': str(p), 'sha256': sha(p), 'bytes': p.stat().st_size}

assert sha(SOURCE_REVIEW) == SOURCE_HASH
assert not REPORT.exists()
W.mkdir(exist_ok=True)
review = json.loads(SOURCE_REVIEW.read_text(encoding='utf-8-sig'))
specifications = [
    ('jendouba-mehat-resume-2018', 'jendouba-mehat-resume2018.pdf', [
        (26, 21, ['ville de Jendouba', 'GOUVERNORAT DE JENDOUBA']),
        (33, 27, ['Jendouba (nord et sud)', 'Jendouba Nord']),
        (51, 42, ['ville de Jendouba', 'GOUVERNORAT DE JENDOUBA']),
    ]),
    ('jemmal-mehat-city-tender-2017', 'jemmal-mehat-tender2017.pdf', [
        (1, None, ['ville de Jemmal', 'Lot 2']),
    ]),
]
records = []
flags = int(pymupdf.TEXTFLAGS_TEXT)
for identity, filename, pages in specifications:
    evidence = next(v for v in review['primaryEvidence'] if v['id'] == identity)
    original = Path(evidence['source']['file'])
    assert sha(original) == evidence['source']['sha256']
    copied = W / filename
    assert not copied.exists()
    shutil.copyfile(original, copied)
    assert copied.read_bytes() == original.read_bytes()
    source = pymupdf.open(copied)
    outputs = []
    for page_number, printed_page, required in pages:
        page = source[page_number - 1]
        # Preserve the extractor's complete page output: no trimming, header,
        # Unicode normalization, spelling change, or replacement of line breaks.
        text = page.get_text('text', flags=flags, sort=False)
        raw = text.encode('utf-8', errors='strict')
        text_path = W / (copied.stem + f'-page{page_number}.txt')
        assert not text_path.exists()
        assert text and all(phrase in text for phrase in required)
        text_path.write_bytes(raw)
        assert text_path.read_bytes() == raw
        assert text_path.read_bytes().decode('utf-8', errors='strict') == text
        assert '\ufffd' not in text
        # Verify derivation again from the original cached PDF, not merely
        # from the copied file or the already held string.
        with pymupdf.open(original) as independent_read:
            assert independent_read[page_number - 1].get_text('text', flags=flags, sort=False).encode('utf-8') == raw
        phrase_links = []
        for phrase in required:
            offset = text.index(phrase)
            phrase_links.append({
                'phrase': phrase,
                'firstCharacterOffset': offset,
                'firstUtf8ByteOffset': len(text[:offset].encode('utf-8')),
                'firstLineOneBased': text[:offset].count('\n') + 1,
                'occurrences': text.count(phrase),
                'sourcePdfPageOneBased': page_number,
                'printedPage': printed_page,
                'sourceIdentityEvidenceId': identity,
            })
        outputs.append({
            'derivedUtf8Text': ref(text_path),
            'artifactKind': 'derived_complete_page_text',
            'isOriginalHttpResponseBytes': False,
            'originalPdf': ref(copied),
            'originalPdfUrl': evidence['url'],
            'sourcePdfPageOneBased': page_number,
            'sourcePdfPageZeroBased': page_number - 1,
            'printedPage': printed_page,
            'completePageExtracted': True,
            'extractionCharacterCount': len(text),
            'requiredText': required,
            'requiredTextLocations': phrase_links,
            'derivationMethod': 'PyMuPDF Page.get_text("text", flags=TEXTFLAGS_TEXT, sort=False), followed by strict UTF-8 encoding without any postprocessing.',
            'derivedUrlQualification': 'The URL identifies the original PDF; this derived TXT file was not served at that URL.',
        })
    records.append({
        'id': identity,
        'placeId': evidence['placeId'],
        'sourceIdentityReport': ref(SOURCE_REVIEW),
        'publisher': evidence['publisher'],
        'originalCachedPdf': ref(original),
        'preservedOriginalPdfCopy': ref(copied),
        'originalPdfUrl': evidence['url'],
        'originalPdfBytesUnchanged': True,
        'originalPdfPages': len(source),
        'derivedPages': outputs,
        'identityFindingUnchanged': evidence.get('finding', evidence.get('reviewedPages')),
        'sourceLimitationsUnchanged': evidence['limits'],
    })
report = {
    'schemaVersion': 1,
    'status': 'DERIVED_UTF8_PRIMARY_PAGE_EVIDENCE_READY_FOR_INDEPENDENT_PROVENANCE_REVIEW',
    'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'Jendouba and Jemmal primary PDFs only. Douz HTML and all catalog/display decisions remain unchanged.',
    'sourceIdentityReport': ref(SOURCE_REVIEW),
    'producer': ref(Path(__file__).resolve()),
    'reproducibleMethod': {
        'tool': 'PyMuPDF',
        'pythonModule': 'pymupdf',
        'bindingVersion': pymupdf.VersionBind,
        'mupdfVersion': pymupdf.VersionFitz,
        'textFlagsSymbol': 'pymupdf.TEXTFLAGS_TEXT',
        'textFlagsValue': flags,
        'pageNumberConvention': 'One-based source page; convert to zero-based PDF array index by subtracting one.',
        'expression': 'pymupdf.open(original_pdf)[pdf_page_number - 1].get_text("text", flags=pymupdf.TEXTFLAGS_TEXT, sort=False).encode("utf-8", errors="strict")',
        'postprocessing': 'None. No stripping, normalization, reflow, headers, inserted prose, corrections, or manual content changes.',
        'outputEncoding': 'UTF-8 without BOM; original extractor line breaks and trailing newline preserved.',
        'originalPdfHandling': 'Full byte-identical copies retained beside derived pages, independently hashed against original cached response bytes.',
    },
    'records': records,
    'interpretation': [
        'Each requiredText phrase is present verbatim in the derived complete-page extraction and linked to its original PDF page.',
        'The derived text hash is a TXT artifact hash, never the original PDF response hash.',
        'A reference URL continues to identify the original PDF, not a fictitious hosted TXT response.',
        'This adapts archived primary evidence to the existing UTF-8 requiredText contract without changing the engine.',
        'No independent provenance approval, source stage, display association, coordinate correction or geometry change is claimed by this derivation.',
    ],
    'operations': {'productWrites': False, 'staging': False, 'generator': False, 'tests': False, 'build': False, 'phone': False, 'ledger': False},
}
assert sha(SOURCE_REVIEW) == SOURCE_HASH
REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'report': ref(REPORT), 'originalPdfs': len(records), 'derivedCompletePages': sum(len(v['derivedPages']) for v in records)}, ensure_ascii=False))
