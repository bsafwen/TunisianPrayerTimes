"""Private one-publication task-source path lookup for the pinned stock reader.

Only the repeated task/evidence association path expression is replaced.
Original evidence/hash/scope/geometry checks and output ordering are untouched.
Every resolved association path is rechecked before returning a report.
"""
import ast
from contextlib import contextmanager
from pathlib import Path
import types

from scripts.locality_automation.run_sealed_boundary_queue import checked


class TaskSourcePaths:
    def __init__(self, resolver=None):
        self.resolver = resolver or (lambda root, source: str((Path(root) / source).resolve()))
        self.values = {}
        self.hits = 0
        self.misses = 0

    def lookup(self, root, source):
        # Actual stock JSON tasks have immutable source strings. Refuse custom
        # coercion objects; do not change repeated __str__ side effects.
        if not isinstance(root, Path) or type(source) is not str:
            raise ValueError("Actual Path root and literal task-source string required")
        key = root, source
        if key in self.values:
            self.hits += 1
            return self.values[key]
        value = self.resolver(root, source)
        self.values[key] = value
        self.misses += 1
        return value

    def verify_stable(self):
        for (root, source), value in self.values.items():
            if self.resolver(root, source) != value:
                raise ValueError("Canonical task-source association changed during publication")


def compiled_candidate(reader, source, lookup):
    tree = ast.parse(source)
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "build_task_report"]
    if len(functions) != 1 or "__codex_task_source_path" in reader.__dict__:
        raise ValueError("Exactly one unmodified stock function and private namespace required")
    original = ast.dump(ast.parse('str((root / str(task["_source"])).resolve())', mode="eval").body, include_attributes=False)
    replacement = ast.parse('__codex_task_source_path(root, task["_source"])', mode="eval").body
    class ReplaceAssociation(ast.NodeTransformer):
        count = 0
        def visit_Call(self, node):
            if ast.dump(node, include_attributes=False) == original:
                self.count += 1
                return ast.copy_location(replacement, node)
            return self.generic_visit(node)
    rewrite = ReplaceAssociation()
    function = rewrite.visit(functions[0])
    if rewrite.count != 1:
        raise ValueError("Unexpected stock association expression; refuse compatibility inference")
    namespace = dict(reader.__dict__)
    namespace["__codex_task_source_path"] = lookup
    module = ast.fix_missing_locations(ast.Module(body=[function], type_ignores=[]))
    exec(compile(module, reader.__file__, "exec"), namespace)
    return namespace["build_task_report"]


@contextmanager
def indexed_task_source_paths(reader, reader_pin):
    path = checked(reader_pin)
    if Path(reader.__file__).resolve() != path.resolve():
        raise ValueError("Loaded stock reader differs from exact released file")
    original = reader.build_task_report
    paths = TaskSourcePaths()
    candidate = compiled_candidate(reader, path.read_text(encoding="utf-8-sig"), paths.lookup)
    def build(*args, **kwargs):
        paths.values.clear()
        result = candidate(*args, **kwargs)
        paths.verify_stable()
        checked(reader_pin)
        return result
    reader.build_task_report = build
    statistics = {"hits": 0, "misses": 0, "stabilityRechecks": 0}
    try:
        yield statistics
    finally:
        reader.build_task_report = original
        statistics.update(hits=paths.hits, misses=paths.misses, stabilityRechecks=len(paths.values))
        checked(reader_pin)
