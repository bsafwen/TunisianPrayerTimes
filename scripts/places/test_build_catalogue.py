"""The catalogue build on a few synthetic GeoNames rows (python -m unittest scripts/places/test_build_catalogue.py)."""

import sys
import tempfile
import unittest
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_catalogue as bc  # noqa: E402


def geo(gid, name, lat, lng, cc, zone, fcode="PPL", admin1="", admin2="", population=0, dem="10"):
    cols = [str(gid), name, name, "", str(lat), str(lng), "P", fcode, cc, "", admin1, admin2, "", "", str(population), "", dem, zone, "2026-01-01"]
    return "\t".join(cols)


def read_rows(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    return {int(c[0]): dict(zip(header, c)) for c in (line.split("\t") for line in lines[1:])}


class BuildCatalogueTest(unittest.TestCase):
    def test_rows_columns_and_rules(self):
        with tempfile.TemporaryDirectory() as tmp:
            g = Path(tmp)
            europe = [
                geo(2988507, "Paris", 48.85341, 2.3488, "FR", "Europe/Paris", "PPLC", "11", "75", 2138551),
                geo(2996944, "Lyon", 45.74846, 4.84671, "FR", "Europe/Paris", "PPLA", "84", "69", 522969),
                geo(524901, "Moscow", 55.75222, 37.61556, "RU", "Europe/Moscow", "PPLC", "48", "", 10381222),
                geo(6362987, "Ceuta", 35.88919, -5.32042, "ES", "Europe/Madrid", "PPLA", "CE", "CE", 83179, "22"),
                geo(3117735, "Madrid", 40.4165, -3.70256, "ES", "Europe/Madrid", "PPLC", "29", "M", 3255944, "667"),
            ]
            arab = [
                geo(108410, "Riyadh", 24.68773, 46.72185, "SA", "Asia/Riyadh", "PPLC", "10", "", 4205961, "612"),
                geo(999001, "Riyadh duplicate", 24.69, 46.72, "SA", "Asia/Riyadh", "PPL", "10", "", 100, "612"),
                geo(2462881, "Laayoune", 27.1418, -13.18797, "EH", "Africa/El_Aaiun", "PPLA", "", "", 196331, "60"),
                geo(361058, "Alexandria", 31.20176, 29.91582, "EG", "Africa/Cairo", "PPLA", "06", "", 3811516, "5"),
                geo(935264, "Saint-Denis", -20.88231, 55.4504, "RE", "Indian/Reunion", "PPLA", "", "", 145000, "40"),
                geo(99532, "Basra", 30.50852, 47.7804, "IQ", "Asia/Baghdad", "PPLA", "02", "", 2600000, "5"),
                geo(371760, "Kosti", 13.1629, 32.6635, "SD", "Africa/Khartoum", "PPL", "", "", 345068),
                geo(112931, "Khorramshahr", 30.44079, 48.18428, "IR", "Asia/Tehran", "PPL", "", "", 5000),
                geo(112932, "Far Iranian town", 35.7, 51.4, "IR", "Asia/Tehran", "PPL", "", "", 8000000),
            ]
            (g / "cities5000.txt").write_text("\n".join(europe) + "\n", encoding="utf-8")
            (g / "cities1000.txt").write_text("\n".join(arab) + "\n", encoding="utf-8")
            (g / "allCountries.txt").write_text(geo(2729907, "Longyearbyen", 78.22334, 15.64689, "SJ", "Arctic/Longyearbyen", "PPLA") + "\n", encoding="utf-8")
            (g / "alternateNamesV2.txt").write_text("\n".join([
                "1\t2988507\tar\tباريس\t1\t\t\t\t\t",
                "2\t2988507\ten\tParis\t\t\t\t\t\t",
                "3\t108410\tar\tالرياض\t1\t\t\t\t\t",
                "4\t361058\tar\tالإسكندرية\t\t\t\t\t\t",
                "5\t361058\tar\tالاسكندرية القديمة\t\t\t\t1\t\t",
                "6\t524901\tfr\tMoscou\t\t\t\t\t\t",
                "7\t371760\tar\tربك\t\t\t\t\t\t",
            ]) + "\n", encoding="utf-8")
            (g / "admin1CodesASCII.txt").write_text("IQ.11\tArbīl\tArbil\t94824\nIQ.07\tBaghdad\tBaghdad\t98180\nFR.11\tÎle-de-France\tIle-de-France\t3012874\n", encoding="utf-8")
            (g / "admin2Codes.txt").write_text("", encoding="utf-8")
            out = g / "out"
            self.assertEqual(0, bc.main(["--geonames", str(g), "--out", str(out)]))

            def rows(name):
                lines = (out / name).read_text(encoding="utf-8").splitlines()
                header = lines[0].split("\t")
                return {int(c[0]): dict(zip(header, c)) for c in (l.split("\t") for l in lines[1:])}

            europe_rows, arab_rows = rows("europe-cities.tsv"), rows("arab-cities.tsv")
            self.assertEqual("باريس", europe_rows[2988507]["arabic"])
            self.assertEqual("", europe_rows[2988507]["ascii"])  # equal to the name: left empty (the budget)
            # Sorted by country then id, a zone repeating the row above's is left empty (Lyon after Paris).
            self.assertEqual("Europe/Paris", europe_rows[2988507]["zone"])
            self.assertEqual("", europe_rows[2996944]["zone"])
            self.assertEqual("75", europe_rows[2988507]["admin2"])  # FR keeps its département
            self.assertEqual("", europe_rows[2988507]["admin1"])
            self.assertEqual("48", europe_rows[524901]["admin1"])  # RU keeps its federal subject
            # Ceuta and Melilla keep their admin1 and dem (their own choice keys, Habous localities); mainland Spain neither.
            self.assertEqual("CE", europe_rows[6362987]["admin1"])
            self.assertEqual("22", europe_rows[6362987]["dem"])
            self.assertEqual("", europe_rows[3117735]["admin1"])
            self.assertEqual("", europe_rows[3117735]["dem"])
            self.assertIn("Moscou", europe_rows[524901]["aliases"])
            self.assertEqual("NO", europe_rows[2729907]["country"])  # Svalbard under Norway
            self.assertEqual("RE", europe_rows[935264]["country"])  # overseas France keeps its code
            self.assertEqual("40", europe_rows[935264]["dem"])
            self.assertEqual("MA", arab_rows[2462881]["country"])  # Western Sahara under Morocco (A1)
            self.assertEqual("الإسكندرية", arab_rows[361058]["arabic"])  # a historic name is never used
            self.assertNotIn(999001, arab_rows)  # a duplicate within 2 km of a larger row
            self.assertEqual("612", arab_rows[108410]["dem"])
            # GeoNames gives Kosti only Rabak's «ربك»: the build names it «كوستي», and «ربك» no longer finds it.
            self.assertEqual("كوستي", arab_rows[371760]["arabic"])
            self.assertNotIn("ربك", arab_rows[371760]["aliases"])
            self.assertEqual("n", arab_rows[112931]["flags"])  # a land neighbour within 60 km, country and zone only
            self.assertEqual("", arab_rows[112931]["arabic"])
            self.assertNotIn(112932, arab_rows)
            self.assertTrue((out / "PLACES-LICENSE.txt").exists())
            admin1 = (out / "admin1-names.tsv").read_text(encoding="utf-8").splitlines()
            self.assertIn("IQ\t11\tArbil", admin1)  # Iraq's admin1 names (A6's Kurdistan chip)
            self.assertFalse(any(line.startswith("FR\t") for line in admin1))

    def test_one_row_per_place(self):
        # The owner saw «القاهرة الجديدة» twice in the picker (2026-10-19): GeoNames' New Cairo and Al Qāhirah al Jadīdah.
        def row(gid, name, lat, lng, cc, population):
            return {"id": gid, "name": name, "ascii": name, "lat": lat, "lng": lng, "cc": cc, "population": population}

        rows = [
            row(7799991, "New Cairo", 30.03, 31.47, "EG", 313139),
            row(8134081, "Al Qahirah al Jadidah", 30.04214, 31.44446, "EG", 313139),  # 2.8 km: the same town
            row(1090186, "Mirereni", -12.95, 45.10, "YT", 3364),
            row(1090383, "Mirereni", -12.83, 45.13, "YT", 1372),  # 13 km: another village of the same name
            row(740560, "Piraziz", 40.9518, 38.1240, "TR", 0),
            row(740561, "Piraziz", 40.9500, 38.1500, "TR", 9959),  # 2.2 km: the same town
        ]
        arabic = {7799991: "القاهرة الجديدة", 8134081: "القاهرة الجديدة"}
        aliases = defaultdict(set)
        kept = bc.merge_same_places(rows, lambda r: arabic.get(r["id"], ""), aliases, referenced={740560})
        self.assertEqual([7799991, 1090186, 1090383, 740560], [r["id"] for r in kept])
        self.assertIn("Al Qahirah al Jadidah", aliases[7799991])  # still found by the dropped row's name
        # The row an official zone names is kept, with the larger population.
        self.assertEqual(9959, next(r for r in kept if r["id"] == 740560)["population"])

    def test_from_cities1000_and_alternate_names_only_zipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            g = Path(tmp)
            places = [
                geo(2988507, "Paris", 48.85341, 2.3488, "FR", "Europe/Paris", "PPLC", "11", "75", 2138551),
                geo(3000001, "Small town", 48.0, 2.0, "FR", "Europe/Paris", "PPL", "11", "91", 3000),
                geo(3000002, "Prefecture", 44.09, 6.24, "FR", "Europe/Paris", "PPLA", "93", "04", 2000),
                geo(2729907, "Longyearbyen", 78.22334, 15.64689, "SJ", "Arctic/Longyearbyen", "PPLC", "", "", 2368),
                geo(108410, "Riyadh", 24.68773, 46.72185, "SA", "Asia/Riyadh", "PPLC", "10", "", 4205961, "612"),
                geo(110000, "District seat", 26.0, 44.0, "SA", "Asia/Riyadh", "PPLA3", "05", "", 0, "650"),
            ]
            with zipfile.ZipFile(g / "cities1000.zip", "w") as z:
                z.writestr("cities1000.txt", "\n".join(places) + "\n")
            out = g / "out"
            # Without the Arabic names the build stops.
            self.assertEqual(1, bc.main(["--geonames", str(g), "--out", str(out)]))
            with zipfile.ZipFile(g / "alternateNamesV2.zip", "w") as z:
                z.writestr("alternateNamesV2.txt", "1\t2988507\tar\tباريس\t1\t\t\t\t\t\n")
            self.assertEqual(0, bc.main(["--geonames", str(g), "--out", str(out)]))
            europe_rows, arab_rows = read_rows(out / "europe-cities.tsv"), read_rows(out / "arab-cities.tsv")
            # cities5000 as GeoNames cuts it: more than 5,000 people, or a first-order seat.
            self.assertIn(2988507, europe_rows)
            self.assertNotIn(3000001, europe_rows)
            self.assertIn(3000002, europe_rows)
            self.assertEqual("NO", europe_rows[2729907]["country"])
            self.assertEqual("باريس", europe_rows[2988507]["arabic"])
            # A seat with no recorded population, which cities1000 keeps down to PPLA3.
            self.assertIn(110000, arab_rows)


if __name__ == "__main__":
    unittest.main()
