"""Bounded call-scoped immutable packed-slice reuse, including dynamic readers.

Constructors, physical reads/hashes and every catalog/source/acceptance gate
still execute. Only identical successfully decoded immutable coordinate tuples
are reused. Original functions are restored after any exit. Serial use only.
"""
import ast
from collections import OrderedDict
from contextlib import contextmanager, ExitStack
import math
from pathlib import Path
import threading

from scripts.locality_automation import packed_gps_replay as replay
from scripts.locality_automation.run_sealed_boundary_queue import checked


class PackedSliceDecodeCache:
    def __init__(self, max_entries=4096):
        if type(max_entries) is not int or max_entries <= 0:
            raise ValueError("Positive finite decode cache size required")
        self.max_entries = max_entries
        self.cache, self.bound = OrderedDict(), []
        self.thread = threading.get_ident()
        self.statistics = dict(hits=0, misses=0, delegatedUncached=0, evictions=0, peakEntries=0, modulesBound=0)

    def bind(self, module):
        original = module._decode_record
        if getattr(original, "_scoped_packed_cache", False):
            raise ValueError("Nested decoder cache contexts are not permitted")
        dependencies = (module.valid_coordinates, module._finite_number, module._need, module._INT)
        def decode(binary, row, scale, identifier):
            if threading.get_ident() != self.thread:
                raise ValueError("Scoped decoder reuse is serial only")
            if dependencies != (module.valid_coordinates, module._finite_number, module._need, module._INT):
                raise ValueError("Decoder dependencies changed during scoped reuse")
            offset, length = row["offset"], row["length"]
            module._need(type(offset) is int and type(length) is int
                         and offset >= 8 and length >= 36 and offset % 4 == length % 4 == 0
                         and offset <= len(binary) - length, f"Invalid packed slice: {identifier}")
            if (type(binary) is not bytes or type(scale) not in (int, float)
                    or not math.isfinite(scale) or scale <= 0 or type(identifier) is not str):
                self.statistics["delegatedUncached"] += 1
                return original(binary, row, scale, identifier)
            key = (identifier, type(scale), scale, binary[offset:offset + length])
            if key in self.cache:
                self.statistics["hits"] += 1
                self.cache.move_to_end(key)
                return self.cache[key]
            self.statistics["misses"] += 1
            result = original(binary, row, scale, identifier)
            self.cache[key] = result
            if len(self.cache) > self.max_entries:
                self.cache.popitem(last=False)
                self.statistics["evictions"] += 1
            self.statistics["peakEntries"] = max(self.statistics["peakEntries"], len(self.cache))
            return result
        decode._scoped_packed_cache = True
        module._decode_record = decode
        self.bound.append((module, original))
        self.statistics["modulesBound"] += 1

    def close(self):
        for module, original in reversed(self.bound):
            module._decode_record = original
        self.cache.clear()


@contextmanager
def reuse_identical_packed_slices(max_entries=4096):
    cache = PackedSliceDecodeCache(max_entries)
    try:
        cache.bind(replay)
        yield cache.statistics
    finally:
        cache.close()


@contextmanager
def reuse_reader_packed_slices(reader, reader_reference, decoder_reference, max_entries=4096):
    """Inject only path checking/binding into an exact original dynamic loader."""
    source = checked(reader_reference).read_text(encoding="utf-8-sig")
    decoder_path = checked(decoder_reference).resolve()
    original_loader = reader._load_replay
    function = next(node for node in ast.parse(source).body
                    if isinstance(node, ast.FunctionDef) and node.name == "_load_replay")
    body, inserted = [], False
    for node in function.body:
        if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                and ast.unparse(node.value.func) == "spec.loader.exec_module"):
            body.extend(ast.parse("_checked_decoder_path(module_path)").body)
            body.append(node)
            body.extend(ast.parse("_bind_decoder_cache(module)").body)
            inserted = True
        else:
            body.append(node)
    if not inserted:
        raise ValueError("Exact reader dynamic decoder loader ABI is unavailable")
    function.body = body
    cache = PackedSliceDecodeCache(max_entries)
    def check_path(path):
        if Path(path).resolve() != decoder_path:
            raise ValueError("Dynamic decoder path differs from exact pin")
        checked(decoder_reference)
    namespace = dict(reader.__dict__, _checked_decoder_path=check_path, _bind_decoder_cache=cache.bind)
    exec(compile(ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[])),
                 reader_reference["file"], "exec"), namespace)
    reader._load_replay = namespace["_load_replay"]
    try:
        yield cache.statistics
    finally:
        reader._load_replay = original_loader
        cache.close()


@contextmanager
def reuse_caf_reader(caf, caf_reference, reader_reference, decoder_reference):
    """Keep the complete pinned CAF record body, adding only scoped reader reuse."""
    source = checked(caf_reference).read_text(encoding='utf-8-sig')
    reader_path = checked(reader_reference).resolve()
    checked(decoder_reference)
    original = caf.record
    function = next(node for node in ast.parse(source).body
                    if isinstance(node, ast.FunctionDef) and node.name == 'record')
    body, insertions = [], 0
    for node in function.body:
        if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                and ast.unparse(node.value.func) == 'module_spec.loader.exec_module'):
            body.extend(ast.parse('_check_reader_path(args.report_data)').body)
            body.append(node)
            body.extend(ast.parse('_attach_reader_cache(module)').body)
            insertions += 1
        else:
            body.append(node)
    if insertions != 1:
        raise ValueError('Exact CAF reader-loader ABI is unavailable')
    function.body = body
    statistics = []
    with ExitStack() as stack:
        def check_reader(path):
            if Path(path).resolve() != reader_path:
                raise ValueError('CAF dynamic reader path differs from exact pin')
            checked(reader_reference)
        def attach_reader(module):
            statistics.append(stack.enter_context(reuse_reader_packed_slices(
                module, reader_reference, decoder_reference)))
        namespace = dict(caf.__dict__, _check_reader_path=check_reader,
                         _attach_reader_cache=attach_reader)
        exec(compile(ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[])),
                     caf_reference['file'], 'exec'), namespace)
        caf.record = namespace['record']
        try:
            yield statistics
        finally:
            caf.record = original
