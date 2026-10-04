from pathlib import Path
import types
import unittest

from scripts.locality_automation.task_source_path_index import TaskSourcePaths, compiled_candidate


SOURCE = '''
def build_task_report(root, tasks, validations):
    result = []
    for task in tasks:
        for validation in validations:
            direct_source = any(str(ref["path"]) == str((root / str(task["_source"])).resolve()) for ref in validation["evidenceRefs"])
            if direct_source:
                result.append((task["id"], validation["id"]))
    return result
'''


class TaskSourceIndexTests(unittest.TestCase):
    def reader(self, source=SOURCE):
        namespace = {"__file__": "unchanged-reader.py"}
        exec(compile(source, namespace["__file__"], "exec"), namespace)
        return types.SimpleNamespace(**namespace)

    def test_complete_ordered_associations_with_duplicate_refs_and_empty_scopes(self):
        root = Path.cwd()
        tasks = [{"id": "a", "_source": "A"}, {"id": "b", "_source": "B"}, {"id": "repeat", "_source": "A"}]
        validations = [{"id": "empty", "evidenceRefs": []},
            {"id": "first", "evidenceRefs": [{"path": str((root/"A").resolve())}, {"path": str((root/"A").resolve())}]},
            {"id": "second", "evidenceRefs": [{"path": str((root/"B").resolve())}]},
            {"id": "third", "evidenceRefs": [{"path": str((root/"A").resolve())}]}]
        reader, paths = self.reader(), TaskSourcePaths()
        candidate = compiled_candidate(reader, SOURCE, paths.lookup)
        self.assertEqual(candidate(root, tasks, validations), reader.build_task_report(root, tasks, validations))
        self.assertEqual(paths.misses, 2)
        paths.verify_stable()

    def test_empty_validation_refs_do_not_resolve_missing_task_source(self):
        reader, paths = self.reader(), TaskSourcePaths()
        candidate = compiled_candidate(reader, SOURCE, paths.lookup)
        self.assertEqual(candidate(Path.cwd(), [{"id": "missing"}], [{"id": "empty", "evidenceRefs": []}]), [])
        self.assertEqual(paths.misses, 0)

    def test_missing_evidence_path_failure_precedes_task_lookup(self):
        reader, paths = self.reader(), TaskSourcePaths()
        candidate = compiled_candidate(reader, SOURCE, paths.lookup)
        with self.assertRaises(KeyError) as caught:
            candidate(Path.cwd(), [{"id": "missing"}], [{"id": "bad", "evidenceRefs": [{}]}])
        self.assertEqual(caught.exception.args, ("path",))
        self.assertEqual(paths.misses, 0)

    def test_changed_canonical_target_is_refused_before_report_return(self):
        current = ["first"]
        paths = TaskSourcePaths(lambda root, source: current[0])
        paths.lookup(Path.cwd(), "alias")
        current[0] = "second"
        with self.assertRaises(ValueError):
            paths.verify_stable()

    def test_unexpected_ast_and_coercion_objects_are_refused(self):
        for source in (SOURCE.replace('.resolve()', '.absolute()'), SOURCE + SOURCE):
            with self.subTest(source=source), self.assertRaises(ValueError):
                compiled_candidate(self.reader(source), source, lambda *args: "x")
        with self.assertRaises(ValueError):
            TaskSourcePaths().lookup(Path.cwd(), object())


if __name__ == "__main__":
    unittest.main()
