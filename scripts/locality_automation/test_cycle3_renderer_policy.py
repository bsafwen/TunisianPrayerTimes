"""Offline AST-isolated tests for the cycle3 exact-renderer accommodation.

Neither helper is imported, and main() / checked() / live replay never run.
Fixtures contain reference metadata only; they do not claim geographic credit.
"""
from __future__ import annotations

import argparse
import ast
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import unittest


CYCLE_ROOT = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows\work\locality-efficiency-cycles-20261001")
BASELINE = CYCLE_ROOT / "intake-cycle2/2f00b843c5a1a104-pre_record_check.py"
CANDIDATE = CYCLE_ROOT / "intake-cycle3/candidate-exact-renderer-pre-record-check-v2.py"
BASELINE_SHA = "2f00b843c5a1a104fba6132906b17d07ba9b0af8173324ff97f66a56f9179378"
CANDIDATE_SHA = "56c3968912c50492a656e990f7bc374542f79bf6afb30fd475103e254762e2cf"


def assignment_name(node):
    return node.targets[0].id if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) else None


def isolated_functions():
    """Compile the exact reviewed policy statements, without importing the helper."""
    source = CANDIDATE.read_text(encoding="utf-8")
    tree = ast.parse(source)
    main = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main")
    scope_loop = next(n for n in main.body if isinstance(n, ast.For) and ast.unparse(n.target) == "pair")
    namespace = {"Path": Path}
    constants = {"REPO", "EVIDENCE", "ROOTS", "PINNED_READER_ROOT", "PINNED_RENDERER_ROOT", "PINNED_RENDERER_FILE", "PINNED_RENDERER_SHA"}
    constant_nodes = [copy.deepcopy(n) for n in tree.body if assignment_name(n) in constants]
    # These assignments contain only literal paths, Path construction, and strings.
    exec(compile(ast.fix_missing_locations(ast.Module(body=constant_nodes, type_ignores=[])), str(CANDIDATE), "exec"), namespace)
    start = next(i for i, n in enumerate(scope_loop.body) if assignment_name(n) == "declared_roots")
    end = next(i for i, n in enumerate(scope_loop.body) if isinstance(n, ast.For) and ast.unparse(n.target) == "ref")
    policy_nodes = []
    for node in scope_loop.body[start:end]:
        # Exclude companion/file reads and their unchanged integrity gates.
        if assignment_name(node) == "companion" or "companion" in {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}:
            continue
        if isinstance(node, ast.Expr):
            continue
        policy_nodes.append(copy.deepcopy(node))
    role_start = next(i for i, n in enumerate(main.body) if assignment_name(n) == "codes")
    role_end = next(i for i, n in enumerate(main.body[role_start:], role_start) if isinstance(n, ast.For) and ast.unparse(n.target) == "code")
    for name, args, nodes in (("scope_policy", ["audit"], policy_nodes), ("role_policy", ["report", "spec"], copy.deepcopy(main.body[role_start:role_end]))):
        fn = ast.FunctionDef(name=name, args=ast.arguments(posonlyargs=[], args=[ast.arg(arg=a) for a in args], kwonlyargs=[], kw_defaults=[], defaults=[]), body=nodes, decorator_list=[])
        exec(compile(ast.fix_missing_locations(ast.Module(body=[fn], type_ignores=[])), str(CANDIDATE), "exec"), namespace)
    return namespace, tree


POLICY, TREE = isolated_functions()


class RendererPolicyTests(unittest.TestCase):
    def audit(self, roots=(), refs=()):
        return {"allowedRoots": sorted(POLICY["ROOTS"] | set(roots)), "references": list(refs), "explicitPinReferences": len(refs)}

    def ref(self, path=None, sha=None, pointer="/renderer"):
        return {"pointer": pointer, "resolvedFile": path or POLICY["PINNED_RENDERER_FILE"], "expectedSha256": sha or POLICY["PINNED_RENDERER_SHA"]}

    def hold(self, audit, message=None):
        with self.assertRaisesRegex(AssertionError, message or ".*"):
            POLICY["scope_policy"](audit)

    def test_reviewed_file_hashes(self):
        for path, expected in ((BASELINE, BASELINE_SHA), (CANDIDATE, CANDIDATE_SHA)):
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)

    def test_all_existing_gates_are_ast_identical(self):
        normalized = copy.deepcopy(TREE)
        normalized.body = [n for n in normalized.body if assignment_name(n) not in {"PINNED_RENDERER_ROOT", "PINNED_RENDERER_FILE", "PINNED_RENDERER_SHA"}]
        main = next(n for n in normalized.body if isinstance(n, ast.FunctionDef) and n.name == "main")
        scope = next(n for n in main.body if isinstance(n, ast.For) and ast.unparse(n.target) == "pair")
        cleaned = []
        for node in scope.body:
            if assignment_name(node) == "renderer_refs":
                continue
            if isinstance(node, ast.Assert) and "renderer_refs" in {n.id for n in ast.walk(node.test) if isinstance(n, ast.Name)}:
                continue
            if isinstance(node, ast.If) and ast.unparse(node.test) == "renderer_refs":
                continue
            for part in ast.walk(node):
                if isinstance(part, ast.Set):
                    part.elts = [v for v in part.elts if not (isinstance(v, ast.Name) and v.id == "PINNED_RENDERER_ROOT")]
            cleaned.append(node)
        scope.body = cleaned
        self.assertEqual(ast.dump(normalized), ast.dump(ast.parse(BASELINE.read_text(encoding="utf-8"))))

    def test_renderer_without_root_declaration_holds(self):
        self.hold(self.audit(refs=[self.ref()]), "Renderer references require")

    def test_declared_unused_renderer_root_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]]), "Extra root must bind")

    def test_renderer_root_bound_only_to_reader_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref(str(Path(POLICY["PINNED_READER_ROOT"]) / "__init__.py"))]), "Extra root must bind")

    def test_wrong_file_in_renderer_root_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref(str(Path(POLICY["PINNED_RENDERER_ROOT"]) / "pdfinfo.exe"))]), "only the exact bundled")

    def test_nested_same_basename_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref(str(Path(POLICY["PINNED_RENDERER_ROOT"]) / "other/pdftoppm.exe"))]), "only the exact bundled")

    def test_wrong_renderer_sha_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref(sha="0" * 64)]), "only the exact bundled")

    def test_exact_renderer_is_admitted(self):
        POLICY["scope_policy"](self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref()]))

    def test_mixed_valid_and_wrong_renderer_holds(self):
        self.hold(self.audit([POLICY["PINNED_RENDERER_ROOT"]], [self.ref(), self.ref(sha="0" * 64, pointer="/second")]), "only the exact bundled")

    def test_repo_and_evidence_scopes_remain_admitted(self):
        refs = [self.ref(str(POLICY[root] / "synthetic-proof.json"), pointer="/" + root) for root in ("REPO", "EVIDENCE")]
        POLICY["scope_policy"](self.audit(refs=refs))

    def test_existing_reader_scope_remains_admitted(self):
        root = POLICY["PINNED_READER_ROOT"]
        POLICY["scope_policy"](self.audit([root], [self.ref(str(Path(root) / "__init__.py"))]))

    def test_existing_reader_and_renderer_coexist(self):
        reader = POLICY["PINNED_READER_ROOT"]
        POLICY["scope_policy"](self.audit([reader, POLICY["PINNED_RENDERER_ROOT"]], [self.ref(), self.ref(str(Path(reader) / "__init__.py"), pointer="/reader")]))

    def test_existing_unused_reader_root_still_holds(self):
        self.hold(self.audit([POLICY["PINNED_READER_ROOT"]]), "Extra root must bind")

    def test_unapproved_root_still_holds(self):
        self.hold(self.audit([str(Path(POLICY["PINNED_RENDERER_ROOT"]).parent)]))

    def role(self, report_support=(), claim_support=(), full=("125657",)):
        report = {"decodedCurrentChecks": {"125657": {}}, "fullSourceBodyCodes": list(full), "supportingOnlyCodes": list(report_support)}
        spec = {"codes": ["125657"], "handoffClaims": {"supportingOnlyCodes": list(claim_support)}}
        POLICY["role_policy"](report, spec)

    def test_full_role_with_distinct_support_passes(self):
        self.role(report_support=["125658"], claim_support=["125658"])

    def test_report_own_support_overlap_holds(self):
        with self.assertRaisesRegex(AssertionError, "also declared supporting only"):
            self.role(report_support=["125657"])

    def test_handoff_own_support_overlap_holds(self):
        with self.assertRaisesRegex(AssertionError, "Handoff declares"):
            self.role(claim_support=["125657"])

    def test_missing_full_role_holds(self):
        with self.assertRaisesRegex(AssertionError, "Full-body role list differs"):
            self.role(full=())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-output", type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RendererPolicyTests))
    if args.review_output:
        args.review_output.parent.mkdir(parents=True, exist_ok=True)
        review = {"status": "PASS_ISOLATED_RENDERER_POLICY_REVIEW" if result.wasSuccessful() else "FAIL_ISOLATED_RENDERER_POLICY_REVIEW", "checkedAtUtc": datetime.now(timezone.utc).isoformat(), "candidate": {"file": str(CANDIDATE), "sha256": hashlib.sha256(CANDIDATE.read_bytes()).hexdigest()}, "baseline": {"file": str(BASELINE), "sha256": hashlib.sha256(BASELINE.read_bytes()).hexdigest()}, "testsRun": result.testsRun, "failures": len(result.failures), "errors": len(result.errors), "allExistingGatesAstIdentical": result.wasSuccessful(), "unconditionalRendererRootReferenceDualBinding": True, "rendererAdmission": {"file": POLICY["PINNED_RENDERER_FILE"], "sha256": POLICY["PINNED_RENDERER_SHA"]}, "completeSourceFactsAndHashesPreserved": True, "selectedFullSupportingRoleDisjointnessPreserved": True, "limits": ["Exact helper policy AST compiled in isolation with synthetic metadata; no helper imported and no main() invoked.", "No source, GPS, runtime, acceptance, recorder, live ledger, application asset, or network work performed.", "Existing content hash checks remain unchanged but are not rerun by these isolated tests."], "geographicCreditAdded": 0, "assetsChanged": False, "ledgersChanged": False}
        with args.review_output.open("x", encoding="utf-8") as handle:
            handle.write(json.dumps(review, ensure_ascii=False, indent=2) + "\n")
    raise SystemExit(0 if result.wasSuccessful() else 1)
