import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.locality_automation.prepare_native_shadow_proposal_v2 import literal_identity


class NativeIdentityAliasTests(unittest.TestCase):
    def fixtures(self, directory, duplicate=False, wrong_code=False, wrong_parent=False):
        row = {"officialCode": "426262", "officialName": "عبدالعظيم", "officialParent": "فريانة"}
        feature = {"id": "osm:relation:7164931", "name": "عبد العظيم", "parentName": "معتمدية فريانة"}
        ministry = {"name": row["officialName"], "delegation": row["officialParent"], "governorate": "القصرين",
            "frozenFunctionalExpectedId": feature["id"], "identityCandidateCodes": '["426262"]', "ministryCsvLine": "1693"}
        records = [dict(ministry, frozenFunctionalExpectedId="other:" + str(i)) for i in range(2084)]
        records[0] = ministry
        if duplicate:
            records[1] = ministry
        path = directory / "ministry.csv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(ministry))
            writer.writeheader()
            writer.writerows(records)
        registry = directory / "ins.json"
        registry.write_text(json.dumps({"sectors": [{"sectorCode": "999999" if wrong_code else row["officialCode"],
            "sectorAr": feature["name"], "delegationAr": "other" if wrong_parent else row["officialParent"],
            "governorateAr": "القصرين", "sourceRows": {"original.xlsx": 20}}]}, ensure_ascii=False), encoding="utf-8")
        ref = lambda p: {"file": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
        return row, feature, {"ministryMatrix": ref(path), "insRegistry": ref(registry)}

    def test_two_literal_names_bound_by_exact_authoritative_code(self):
        with tempfile.TemporaryDirectory() as temp:
            result = literal_identity(*self.fixtures(Path(temp)))
            self.assertEqual(result["officialCode"], "426262")
            self.assertEqual(result["currentName"], "عبد العظيم")
            self.assertFalse(result["currentIdentityChanged"])

    def test_duplicate_binding_or_wrong_ins_code_or_parent_cannot_form_alias(self):
        for flag in ("duplicate", "wrong_code", "wrong_parent"):
            with tempfile.TemporaryDirectory() as temp:
                with self.assertRaises(ValueError):
                    literal_identity(*self.fixtures(Path(temp), **{flag: True}))

    def test_similar_text_without_authoritative_binding_is_rejected(self):
        row = {"officialCode": "426262", "officialName": "عبدالعظيم", "officialParent": "فريانة"}
        feature = {"id": "some", "name": "عبد العظيم", "parentName": "معتمدية فريانة"}
        with self.assertRaises(KeyError):
            literal_identity(row, feature, {})

    def test_parent_mismatch_rejected_even_with_equal_name(self):
        with self.assertRaisesRegex(ValueError, "parent"):
            literal_identity({"officialName": "same", "officialParent": "a"}, {"name": "same", "parentName": "معتمدية b"}, {})


if __name__ == "__main__":
    unittest.main()
