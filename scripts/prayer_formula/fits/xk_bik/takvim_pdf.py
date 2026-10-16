"""
Reads BIK's Takvim PDF (the calendar part, «Pjesa kalendarike») into its daily rows, standard library only.

A minimal PDF text reader: objects (object streams included), FlateDecode, fonts with a ToUnicode map or a
single-byte encoding, and the text-showing operators with their positions (CTM and text matrix, form XObjects
followed). Enough for the Takvim's vector-text tables; not a general PDF reader.

    rows = read_takvim(path, year)   # date -> [(h, m)] * 8: Imsaku, Sabahu, Lindja e diellit, Dreka, Ikindia,
                                     #                     Akshami, Jacia, Gjatësia e ditës (local clock time)
"""

from __future__ import annotations

import datetime
import re
import zlib

WS = b"\x00\t\n\x0c\r "
DELIM = b"()<>[]{}/%"


class Ref:
    __slots__ = ("num", "gen")

    def __init__(self, num, gen):
        self.num, self.gen = num, gen


class Name(str):
    pass


class Op(str):
    pass


class Stream:
    def __init__(self, dic, raw):
        self.dic, self.raw = dic, raw


class Lexer:
    def __init__(self, data: bytes, pos: int = 0):
        self.data, self.pos = data, pos

    def skip(self):
        d, n = self.data, len(self.data)
        while self.pos < n:
            c = d[self.pos]
            if c in WS:
                self.pos += 1
            elif c == 0x25:  # % comment
                while self.pos < n and d[self.pos] not in b"\r\n":
                    self.pos += 1
            else:
                break

    def token(self):
        """The next object or operator; None at the end."""
        self.skip()
        d = self.data
        if self.pos >= len(d):
            return None
        c = d[self.pos]
        if c == 0x2F:  # /Name
            self.pos += 1
            start = self.pos
            while self.pos < len(d) and d[self.pos] not in WS and d[self.pos] not in DELIM:
                self.pos += 1
            raw = re.sub(rb"#([0-9A-Fa-f]{2})", lambda m: bytes([int(m.group(1), 16)]), d[start:self.pos])
            return Name(raw.decode("latin-1"))
        if c == 0x28:  # (string)
            return self.literal()
        if c == 0x3C:  # << dict >> or <hex>
            if d[self.pos:self.pos + 2] == b"<<":
                self.pos += 2
                out = {}
                while True:
                    self.skip()
                    if d[self.pos:self.pos + 2] == b">>":
                        self.pos += 2
                        return out
                    key = self.token()
                    out[key] = self.value()
            self.pos += 1
            end = d.index(b">", self.pos)
            hexs = re.sub(rb"\s", b"", d[self.pos:end])
            self.pos = end + 1
            if len(hexs) % 2:
                hexs += b"0"
            return bytes.fromhex(hexs.decode("ascii"))
        if c == 0x5B:  # [array]
            self.pos += 1
            out = []
            while True:
                self.skip()
                if d[self.pos] == 0x5D:
                    self.pos += 1
                    return out
                out.append(self.value())
        if c in b"]>":
            self.pos += 1
            return Op(chr(c))
        start = self.pos
        while self.pos < len(d) and d[self.pos] not in WS and d[self.pos] not in DELIM:
            self.pos += 1
        if self.pos == start:
            self.pos += 1
            return Op(chr(c))
        word = d[start:self.pos]
        if re.fullmatch(rb"[+-]?\d+", word):
            return int(word)
        if re.fullmatch(rb"[+-]?(\d+\.\d*|\.\d+|\d+\.)", word):
            return float(word)
        return Op(word.decode("latin-1"))

    def value(self):
        """An object, with N G R read as a reference."""
        tok = self.token()
        if isinstance(tok, int):
            save = self.pos
            t2 = self.token()
            if isinstance(t2, int) and self.token() == "R":
                return Ref(tok, t2)
            self.pos = save
        if tok == "true":
            return True
        if tok == "false":
            return False
        if tok == "null":
            return None
        return tok

    def literal(self) -> bytes:
        d = self.data
        self.pos += 1
        out = bytearray()
        depth = 1
        esc = {0x6E: 10, 0x72: 13, 0x74: 9, 0x62: 8, 0x66: 12, 0x28: 0x28, 0x29: 0x29, 0x5C: 0x5C}
        while True:
            c = d[self.pos]
            self.pos += 1
            if c == 0x5C:
                e = d[self.pos]
                self.pos += 1
                if e in esc:
                    out.append(esc[e])
                elif 0x30 <= e <= 0x37:
                    digits = bytes([e])
                    while len(digits) < 3 and 0x30 <= d[self.pos] <= 0x37:
                        digits += bytes([d[self.pos]])
                        self.pos += 1
                    out.append(int(digits, 8) & 0xFF)
                elif e in b"\r\n":
                    if e == 0x0D and d[self.pos] == 0x0A:
                        self.pos += 1
                else:
                    out.append(e)
            elif c == 0x28:
                depth += 1
                out.append(c)
            elif c == 0x29:
                depth -= 1
                if depth == 0:
                    return bytes(out)
                out.append(c)
            else:
                out.append(c)


class Pdf:
    def __init__(self, data: bytes):
        self.objects: dict[int, object] = {}
        pending = []
        for m in re.finditer(rb"(\d+)\s+(\d+)\s+obj\b", data):
            num = int(m.group(1))
            lex = Lexer(data, m.end())
            obj = lex.value()
            lex.skip()
            if isinstance(obj, dict) and data[lex.pos:lex.pos + 6] == b"stream":
                p = lex.pos + 6
                p += 2 if data[p:p + 2] == b"\r\n" else 1 if data[p:p + 1] in (b"\n", b"\r") else 0
                length = obj.get("Length")
                if isinstance(length, int):
                    obj = Stream(obj, data[p:p + length])
                else:
                    obj = Stream(obj, data[p:data.index(b"endstream", p)])
                    if isinstance(length, Ref):
                        pending.append((obj, length))
            self.objects[num] = obj
        for obj, length in pending:
            n = self.objects.get(length.num)
            if isinstance(n, int):
                obj.raw = obj.raw[:n]
        for obj in list(self.objects.values()):
            if isinstance(obj, Stream) and obj.dic.get("Type") == "ObjStm":
                body = self.decode(obj)
                head = Lexer(body[:obj.dic["First"]])
                for _ in range(obj.dic["N"]):
                    onum, off = head.token(), head.token()
                    self.objects.setdefault(onum, Lexer(body, obj.dic["First"] + off).value())

    def get(self, obj):
        while isinstance(obj, Ref):
            obj = self.objects.get(obj.num)
        return obj

    def decode(self, stream: Stream) -> bytes:
        filters = self.get(stream.dic.get("Filter"))
        out = stream.raw
        for f in ([] if filters is None else filters if isinstance(filters, list) else [filters]):
            if self.get(f) != "FlateDecode":
                raise ValueError(f"filter {f}")
            out = zlib.decompress(out)
        return out

    def pages(self):
        root = next(self.get(d["Pages"]) for d in (o.dic if isinstance(o, Stream) else o for o in self.objects.values())
                    if isinstance(d, dict) and d.get("Type") == "Catalog")
        out = []

        def walk(node, inherited):
            node = self.get(node)
            res = node.get("Resources", inherited)
            if node.get("Type") == "Pages":
                for kid in self.get(node["Kids"]):
                    walk(kid, res)
            else:
                out.append((node, self.get(res)))

        walk(root, None)
        return out


def parse_cmap(data: bytes) -> dict[bytes, str]:
    out = {}
    for block in re.findall(rb"beginbfchar(.*?)endbfchar", data, re.S):
        items = re.findall(rb"<([0-9A-Fa-f]+)>", block)
        for src, dst in zip(items[::2], items[1::2]):
            out[bytes.fromhex(src.decode())] = bytes.fromhex(dst.decode()).decode("utf-16-be", errors="replace")
    for block in re.findall(rb"beginbfrange(.*?)endbfrange", data, re.S):
        lex = Lexer(block)
        while True:
            lo = lex.value()
            if not isinstance(lo, bytes):
                break
            hi, dst = lex.value(), lex.value()
            for k, code in enumerate(range(int.from_bytes(lo, "big"), int.from_bytes(hi, "big") + 1)):
                key = code.to_bytes(len(lo), "big")
                if isinstance(dst, list):
                    out[key] = dst[k].decode("utf-16-be", errors="replace")
                else:
                    out[key] = (int.from_bytes(dst, "big") + k).to_bytes(len(dst), "big").decode("utf-16-be", errors="replace")
    return out


GLYPHS = {"zero": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7",
          "eight": "8", "nine": "9", "colon": ":", "period": ".", "space": " "}


class Font:
    def __init__(self, pdf: Pdf, dic: dict):
        self.two = dic.get("Subtype") == "Type0"
        tu = pdf.get(dic.get("ToUnicode"))
        self.map = parse_cmap(pdf.decode(tu)) if isinstance(tu, Stream) else {}
        self.diff = {}
        enc = pdf.get(dic.get("Encoding"))
        if isinstance(enc, dict):
            code = 0
            for item in pdf.get(enc.get("Differences", [])):
                if isinstance(item, int):
                    code = item
                else:
                    self.diff[code] = str(item)
                    code += 1

    def text(self, s: bytes) -> str:
        if self.two:
            return "".join(self.map.get(s[i:i + 2], "?") for i in range(0, len(s) - 1, 2))
        out = []
        for b in s:
            if bytes([b]) in self.map:
                out.append(self.map[bytes([b])])
            elif b in self.diff:
                out.append(GLYPHS.get(self.diff[b], "?"))
            else:
                out.append(bytes([b]).decode("cp1252", errors="replace"))
        return "".join(out)


def _mul(a, b):
    return [a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]]


def page_items(pdf: Pdf, page: dict, res: dict) -> list[tuple[float, float, str]]:
    """(x, y, text) of every text-showing operator on the page."""
    items = []

    def run(content: bytes, res, ctm):
        fonts = {k: Font(pdf, pdf.get(v)) for k, v in (pdf.get((res or {}).get("Font")) or {}).items()}
        xobjs = pdf.get((res or {}).get("XObject")) or {}
        lex, stack, args = Lexer(content), [], []
        tm = tlm = [1, 0, 0, 1, 0, 0]
        tl, font = 0.0, None
        while True:
            tok = lex.value()
            if tok is None:
                break
            if not isinstance(tok, Op):
                args.append(tok)
                continue
            op = str(tok)
            if op == "BI":
                lex.pos = content.index(b"EI", lex.pos) + 2
            elif op == "q":
                stack.append(ctm[:])
            elif op == "Q":
                ctm = stack.pop() if stack else ctm
            elif op == "cm" and len(args) >= 6:
                ctm = _mul([float(x) for x in args[-6:]], ctm)
            elif op == "BT":
                tm = tlm = [1, 0, 0, 1, 0, 0]
            elif op == "Tf" and len(args) >= 2:
                font = fonts.get(args[-2])
            elif op == "TL" and args:
                tl = float(args[-1])
            elif op in ("Td", "TD") and len(args) >= 2:
                if op == "TD":
                    tl = -float(args[-1])
                tlm = _mul([1, 0, 0, 1, float(args[-2]), float(args[-1])], tlm)
                tm = tlm[:]
            elif op == "Tm" and len(args) >= 6:
                tlm = [float(x) for x in args[-6:]]
                tm = tlm[:]
            elif op == "T*":
                tlm = _mul([1, 0, 0, 1, 0, -tl], tlm)
                tm = tlm[:]
            elif op in ("Tj", "TJ", "'", '"'):
                if op in ("'", '"'):
                    tlm = _mul([1, 0, 0, 1, 0, -tl], tlm)
                    tm = tlm[:]
                arg = args[-1] if args else b""
                text = ""
                for part in (arg if isinstance(arg, list) else [arg]):
                    if isinstance(part, bytes):
                        text += font.text(part) if font else part.decode("latin-1")
                    elif isinstance(part, (int, float)) and part < -250:
                        text += " "
                m = _mul(tm, ctm)
                items.append((m[4], m[5], text))
            elif op == "Do" and args:
                xo = pdf.get(xobjs.get(args[-1]))
                if isinstance(xo, Stream) and xo.dic.get("Subtype") == "Form":
                    matrix = [float(x) for x in pdf.get(xo.dic.get("Matrix", [1, 0, 0, 1, 0, 0]))]
                    run(pdf.decode(xo), pdf.get(xo.dic.get("Resources")) or res, _mul(matrix, ctm))
            args = []

    contents = pdf.get(page.get("Contents"))
    data = (b"\n".join(pdf.decode(pdf.get(c)) for c in contents) if isinstance(contents, list) else pdf.decode(contents))
    run(data, res, [1, 0, 0, 1, 0, 0])
    return items


def lines(items, tolerance=1.5):
    """Items grouped into lines by y (top first), each line's texts in x order."""
    out = []
    for x, y, text in sorted(items, key=lambda t: (-t[1], t[0])):
        if out and abs(out[-1][0] - y) <= tolerance:
            out[-1][1].append((x, text))
        else:
            out.append([y, [(x, text)]])
    return [" ".join(t for _, t in sorted(cells)) for _, cells in out]


MONTHS = {"JANAR": 1, "SHKURT": 2, "MARS": 3, "PRILL": 4, "MAJ": 5, "QERSHOR": 6, "KORRIK": 7, "GUSHT": 8,
          "SHTATOR": 9, "TETOR": 10, "NËNTOR": 11, "DHJETOR": 12}
_TIME = re.compile(r"^(\d{1,2})[:.](\d{2})$")   # a few Dreka cells are printed 11.54


def read_takvim(path, year: int) -> dict[datetime.date, list[tuple[int, int]]]:
    """The Takvim's «Imsaku dhe kohët e namazit» pages: per date its eight printed clock times."""
    pdf = Pdf(open(path, "rb").read())
    out = {}
    for page, res in pdf.pages():
        text_lines = lines(page_items(pdf, page, res))
        if not any("Imsaku dhe koh" in line for line in text_lines):
            continue
        month = None
        for line in text_lines:
            m = re.search(r"\b(" + "|".join(MONTHS) + r")\b\s+[IVX]+\s*/\s*(\d{4})", line)
            if m:
                if int(m.group(2)) != year:
                    raise ValueError(f"{path}: a page of {m.group(2)}, not {year}")
                month = MONTHS[m.group(1)]
                break
        for line in text_lines:
            tokens = line.split()
            times = [t for t in tokens if _TIME.match(t)]
            if not tokens or not re.fullmatch(r"\d{1,2}", tokens[0]) or len(times) != 8:
                continue
            day = datetime.date(year, month, int(tokens[0]))
            if day in out:
                raise ValueError(f"{path}: {day} twice")
            out[day] = [tuple(int(x) for x in _TIME.match(t).groups()) for t in times]
    return out
