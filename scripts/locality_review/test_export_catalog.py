"""Focused regression checks for grouped rows in the pinned catalog export."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import export_catalog


def pin_bytes(path: Path, data: bytes) -> dict[str, str]:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"file": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest()}


def pin_json(path: Path, value) -> dict[str, str]:
    return pin_bytes(path, (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"))


class ExportCatalogGroupedRowsTest(unittest.TestCase):
    def build_pins(self, root: Path, *, unknown_manual_id: bool = False) -> Path:
        node_id = "osm:node:332247375"
        relation_id = "osm:relation:7174576"
        singleton_id = "osm:node:unaffected"
        metadata = {
            "features": [
                {
                    "id": node_id, "name": "المروج 5", "aliases": ["Mourouj 5"],
                    "contextAliases": ["El Mourouj", "Délégation El Mourouj"],
                    "parentName": "معتمدية المروج", "governorateId": 349,
                    "delegationId": 447, "kind": "village", "hasBoundary": False,
                    "lat": 36.7133259, "lng": 10.2045405,
                    "pickerGroupId": relation_id,
                },
                {
                    "id": relation_id, "name": "المروج 5", "aliases": ["El Mourouj (5)"],
                    "contextAliases": ["El Mourouj", "Délégation El Mourouj"],
                    "parentName": "معتمدية المروج", "governorateId": 349,
                    "delegationId": 447, "kind": "sector", "hasBoundary": True,
                    "lat": 36.704885, "lng": 10.216152,
                    "pickerGroupId": relation_id,
                },
                {
                    "id": singleton_id, "name": "حي الهدى", "aliases": ["Hay El Houda"],
                    "contextAliases": ["El Mourouj"], "parentName": "المروج",
                    "governorateId": 349, "delegationId": 447, "kind": "neighbourhood",
                    "hasBoundary": False, "lat": 36.72, "lng": 10.22,
                    "pickerGroupId": singleton_id,
                },
            ],
        }
        metadata_pin = pin_json(root / "metadata.json", metadata)
        relation_row = {
            "id": relation_id, "name": "المروج 5", "aliases": ["El Mourouj (5)"],
            "contextAliases": ["El Mourouj", "Délégation El Mourouj"],
            "parentName": "معتمدية المروج", "governorate": "بن عروس",
            "governorateFr": "Ben Arous", "delegationId": 447,
            "kind": "sector", "lat": 36.704885, "lng": 10.216152,
            "hasBoundary": True, "members": sorted([node_id, relation_id]),
            "geometrySha256": "geometry", "fingerprint": "row-fingerprint",
        }
        delegation_447 = {
            "id": "delegation:447", "name": "المروج", "aliases": ["El Mourouj"],
            "contextAliases": [], "parentName": "بن عروس", "governorate": "بن عروس",
            "governorateFr": "Ben Arous", "delegationId": 447,
            "kind": "delegation", "lat": 36.733, "lng": 10.205,
            "hasBoundary": False, "members": ["delegation:447"],
            "geometrySha256": None, "fingerprint": "delegation-447",
        }
        # This broad helper row must remain excluded because source 495 is unavailable.
        delegation_495 = {
            **delegation_447, "id": "delegation:495", "delegationId": 495,
            "members": ["delegation:495"], "fingerprint": "delegation-495",
        }
        singleton_row = {
            "id": singleton_id, "name": "حي الهدى", "aliases": ["Hay El Houda"],
            "contextAliases": ["El Mourouj"], "parentName": "المروج",
            "governorate": "بن عروس", "governorateFr": "Ben Arous",
            "delegationId": 447, "kind": "neighbourhood", "lat": 36.72, "lng": 10.22,
            "hasBoundary": False, "members": [singleton_id],
            "geometrySha256": None, "fingerprint": "singleton-fingerprint",
        }
        helper_text = (
            "def catalog():\n"
            f"    return ({metadata_pin['sha256']!r}, { [relation_row, delegation_447, delegation_495, singleton_row]!r})\n"
        )
        helper_pin = pin_bytes(root / "catalog_helper.py", helper_text.encode("utf-8"))
        governors_pin = pin_json(root / "governors.json", {
            "gouvernorats": [{
                "id": 349, "nomAr": "بن عروس", "nomFr": "Ben Arous",
                "delegations": [
                    {"id": 447, "nomAr": "المروج", "lat": 36.733, "lng": 10.205},
                    {"id": 495, "nomAr": "سيدي فرج", "lat": 36.7, "lng": 10.1},
                ],
            }],
        })
        display_pin = pin_json(root / "display-names.json", {
            "names": [
                {"id": relation_id, "nameAr": "المروج 5", "searchAliases": ["المروج الخامس"]},
            ],
        })
        manual = [
            {"id": relation_id, "lat": 36.704885, "lng": 10.216152, "sourceId": 447},
            {"id": node_id, "lat": 36.7133259, "lng": 10.2045405, "sourceId": 447},
            {"id": singleton_id, "lat": 36.72, "lng": 10.22, "sourceId": 447},
        ]
        if unknown_manual_id:
            manual.append({"id": "osm:node:missing", "lat": 36.71, "lng": 10.21, "sourceId": 447})
        coverage_pin = pin_json(root / "coverage.json", {
            "prayerSelection": {"currentManualSelections": manual, "currentSourceIds": [447]},
        })
        binary_pin = pin_bytes(root / "neighborhoods.bin", b"NPOL\x00\x00\x00\x01")
        pins_file = root / "pins.json"
        pins_file.write_text(json.dumps({
            "helper": helper_pin,
            "metadata": metadata_pin,
            "binary": binary_pin,
            "governors": governors_pin,
            "displayNames": display_pin,
            "coverage": coverage_pin,
        }, indent=2) + "\n", encoding="utf-8")
        return pins_file

    def test_collapses_grouped_node_and_preserves_search_names_and_source_filter(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pins = self.build_pins(root)
            output = root / "catalog.json"

            result = export_catalog.export_catalog(pins, output)
            catalog = json.loads(output.read_text(encoding="utf-8"))
            by_id = {row["id"]: row for row in catalog["locations"]}

            self.assertEqual(result["locations"], 3)
            self.assertEqual(set(by_id), {
                "osm:relation:7174576", "delegation:447", "osm:node:unaffected",
            })
            relation = by_id["osm:relation:7174576"]
            self.assertEqual(relation["prayerSource"]["id"], 447)
            self.assertEqual(relation["lat"], 36.704885)
            self.assertIn("El Mourouj (5)", relation["aliases"])
            self.assertIn("المروج الخامس", relation["aliases"])
            self.assertIn("Délégation El Mourouj", relation["searchTerms"])
            self.assertNotIn("delegation:495", by_id)
            old_format_singleton = {
                "id": "osm:node:unaffected", "nameAr": "حي الهدى",
                "aliases": ["Hay El Houda"],
                "searchTerms": ["El Mourouj", "Ben Arous"],
                "governorateAr": "بن عروس", "parentAr": "المروج",
                "kind": "neighbourhood", "lat": 36.72, "lng": 10.22,
                "hasBoundary": False,
                "prayerSource": {"id": 447, "nameAr": "المروج", "lat": 36.733, "lng": 10.205},
            }
            old_format_singleton["fingerprint"] = hashlib.sha256(export_catalog.canonical({
                **old_format_singleton, "members": ["osm:node:unaffected"],
                "geometrySha256": None, "catalogRowFingerprint": "singleton-fingerprint",
            })).hexdigest()
            self.assertEqual(by_id["osm:node:unaffected"], old_format_singleton)

    def test_rejects_an_unexplained_missing_helper_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pins = self.build_pins(root, unknown_manual_id=True)

            with self.assertRaisesRegex(ValueError, "missing from the helper"):
                export_catalog.export_catalog(pins, root / "catalog.json")


if __name__ == "__main__":
    unittest.main()
