"""Checks of the shared engine's Python reference: python -m unittest scripts/prayer_formula/test_prayer_engine_reference.py."""
import datetime
import importlib.util
import math
from pathlib import Path
import sys
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
CASES_PATH = REPO / "test-data/engine-reference/cases.json"
EXPECTED_PATH = REPO / "test-data/engine-reference/expected.tsv"

SPEC = importlib.util.spec_from_file_location("prayer_engine_reference", HERE / "prayer_engine_reference.py")
ref = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ref  # dataclasses look the module up while it loads
SPEC.loader.exec_module(ref)

CASES = {case["id"]: case for case in ref.load_cases(CASES_PATH)}


def readme_sun(jd: float) -> tuple[float, float]:
    """README section 2, transcribed again on its own: (δ in degrees, EoT in minutes)."""
    r = math.pi / 180
    t = (jd - 2415020.0) / 36525
    big_l = 279.69668 + 36000.76892 * t + 0.0003025 * t ** 2
    m = 358.47583 + 35999.04975 * t - 0.000150 * t ** 2 - 0.0000033 * t ** 3
    e = 0.01675104 - 0.0000418 * t - 0.000000126 * t ** 2
    c = ((1.919460 - 0.004789 * t - 0.000014 * t ** 2) * math.sin(m * r)
         + (0.020094 - 0.000100 * t) * math.sin(2 * m * r) + 0.000293 * math.sin(3 * m * r))
    omega = 259.18 - 1934.142 * t
    lam = big_l + c - 0.00569 - 0.00479 * math.sin(omega * r)
    eps = (23.452294 - 0.0130125 * t - 0.00000164 * t ** 2 + 0.000000503 * t ** 3
           + 0.00256 * math.cos(omega * r))
    delta = math.asin(math.sin(eps * r) * math.sin(lam * r)) / r
    y = math.tan(eps / 2 * r) ** 2
    big_e = (y * math.sin(2 * big_l * r) - 2 * e * math.sin(m * r)
             + 4 * e * y * math.sin(m * r) * math.cos(2 * big_l * r)
             - 0.5 * y ** 2 * math.sin(4 * big_l * r) - 1.25 * e ** 2 * math.sin(2 * m * r))
    return delta, 4 * big_e * 180 / math.pi


def utc(text: str) -> float:
    return datetime.datetime.fromisoformat(text).replace(tzinfo=datetime.timezone.utc).timestamp()


class JulianDayAndSunTest(unittest.TestCase):
    def test_julian_day_of_2026_01_01(self):
        self.assertEqual(ref.julian_day0(datetime.date(2026, 1, 1)), 2461041.5)

    def test_julian_day_is_days_since_1970(self):
        for day in (datetime.date(2020, 1, 1), datetime.date(2020, 2, 29), datetime.date(2026, 3, 1),
                    datetime.date(2026, 10, 13), datetime.date(2099, 12, 31), datetime.date(2100, 3, 1)):
            self.assertEqual(ref.julian_day0(day), 2440587.5 + (day - datetime.date(1970, 1, 1)).days, day)

    def test_meeus_sun_is_the_readme_formula(self):
        for jd in (2458849.5, 2461041.5, 2461041.5 + 0.37, 2461212.5 + 0.9, 2461342.5 + 0.5, 2488069.5):
            decl, eot, dist = ref.meeus_sun(jd)
            want_decl, want_eot = readme_sun(jd)
            self.assertAlmostEqual(decl, want_decl, places=10)
            self.assertAlmostEqual(eot, want_eot, places=10)
            g = math.radians(357.528 + 0.9856003 * (jd - 2451545.0))   # S1: the Almanac's distance
            self.assertAlmostEqual(dist, 1.00014 - 0.01671 * math.cos(g) - 0.00014 * math.cos(2 * g), places=12)


class RoundingTest(unittest.TestCase):
    def test_e1_modes(self):
        minute = 1791821340.0                                       # 2026-10-12T16:09:00Z
        self.assertEqual(ref.round_instant(minute + 29.999, "NEAREST"), minute)
        self.assertEqual(ref.round_instant(minute + 30.0, "NEAREST"), minute + 60)
        self.assertEqual(ref.round_instant(minute + 0.0009, "UP"), minute)
        self.assertEqual(ref.round_instant(minute + 0.0011, "UP"), minute + 60)
        self.assertEqual(ref.round_instant(minute - 0.0009, "DOWN"), minute)
        self.assertEqual(ref.round_instant(minute - 0.0011, "DOWN"), minute - 60)


class DateLineTest(unittest.TestCase):
    """mwl (Fajr 18°, Isha 17°, no offsets, angle-based rule, nearest rounding, MEEUS) across the date line."""

    def check(self, case_id: str, cycle_date: str, transit: str, times: dict[str, str]):
        engine = ref.CaseEngine(CASES[case_id])
        raw = engine.raw_day(engine.exact_site(), engine.day)
        self.assertEqual(raw.cycle.u, datetime.date.fromisoformat(cycle_date))
        self.assertLess(abs(raw.transit - utc(transit)), 60)
        self.assertEqual(ref.round_instant(raw.transit, "NEAREST"), utc(transit))
        day = engine.run()
        for name, minute in times.items():
            self.assertEqual(day.status(name), "COMPUTED", name)
            self.assertEqual(day.minutes[name], utc(minute), name)
            self.assertLess(abs(day.unrounded[name] - utc(minute)), 60, name)

    def test_mata_utu_runs_on_the_previous_utc_date(self):
        # Pacific/Wallis, standard offset +12:00: D 2026-10-13 is the cycle of U 2026-10-12.
        self.check("mwl-mata-utu-2026-10-13", "2026-10-12", "2026-10-12T23:31",
                   {"FAJR": "2026-10-12T16:09", "MAGHRIB": "2026-10-13T05:42"})

    def test_shemya_runs_on_the_next_utc_date(self):
        # America/Adak, standard offset −10:00 (summer −09:00): D 2026-10-13 is the cycle of U 2026-10-14.
        self.check("mwl-shemya-2026-10-13", "2026-10-14", "2026-10-14T00:10",
                   {"FAJR": "2026-10-13T16:52", "ISHA": "2026-10-14T07:19"})


def run_case(case_id: str):
    """(engine, its site, D's steps 1-3, the PrayerDay) of one case."""
    engine = ref.CaseEngine(CASES[case_id])
    site, _ = engine.running_site_and_shift()
    return engine, site, engine.day_steps(site, engine.day), engine.run()


class HighLatitudeTest(unittest.TestCase):
    """M2: the polar rules (H2, H8) and the high-latitude rules (H3-H7), from the README's text."""

    def test_dum_rf_share(self):
        # H4: 0.30 in June, then 1/240 less every 3 days (n counted from the day after the edge), floor 0.25.
        for day, n in (("2026-06-01", 0), ("2026-06-30", 0), ("2026-05-31", 1), ("2026-05-29", 1), ("2026-05-28", 2),
                       ("2026-07-01", 1), ("2026-07-03", 1), ("2026-07-04", 2), ("2026-04-29", 11)):
            self.assertAlmostEqual(ref.dum_rf_share(datetime.date.fromisoformat(day)), 0.30 - n / 240, places=12, msg=day)
        for day in ("2026-04-26", "2026-08-03", "2026-01-15", "2026-12-31"):
            self.assertEqual(ref.dum_rf_share(datetime.date.fromisoformat(day)), 0.25, day)

    def test_switch_day_numbers_and_mirror(self):
        # H7: day numbers of a common year, 29 February counting as 28 February; last = 341 − first.
        self.assertEqual(ref.common_day_number(datetime.date(2024, 2, 29)), 59)
        self.assertEqual(ref.common_day_number(datetime.date(2024, 3, 1)), 60)
        self.assertEqual(ref.common_day_number(datetime.date(2026, 6, 21)), 172)
        self.assertEqual(ref.date_of_day_number(172, 2028), datetime.date(2028, 6, 21))
        engine, site, _, _ = run_case("irn-oslo-2026-06-21")
        for name in ("ISHA", "FAJR"):
            first, last = engine.switch_days(site, engine.day, name)
            self.assertTrue(32 <= first <= 172)
            self.assertEqual(first + last, 341, name)
        # The fixture zone's switchDays (ISHA 90-251, FAJR 103-238) decide, at both ends.
        for case_id, name, status in (("irn-switch-fixture-2026-03-30", "ISHA", "COMPUTED"),
                                      ("irn-switch-fixture-2026-03-31", "ISHA", "ESTIMATED"),
                                      ("irn-switch-fixture-2026-09-08", "ISHA", "ESTIMATED"),
                                      ("irn-switch-fixture-2026-09-09", "ISHA", "COMPUTED"),
                                      ("irn-switch-fixture-2026-04-13", "FAJR", "ESTIMATED"),
                                      ("irn-switch-fixture-2026-08-27", "FAJR", "COMPUTED")):
            day = run_case(case_id)[3]
            self.assertEqual(day.status(name), status, case_id)
            if status == "ESTIMATED":
                self.assertEqual(day.rules[name], "IRN_SOUTHERN_SUMMER", case_id)

    def test_five_hour_minimum_at_tromso(self):
        # H2: a midnight sun clamps to T ∓ 9.5 h, a polar night to T ∓ 2.5 h with the Asr at (T + sunset)/2.
        engine, site, steps, day = run_case("polar-tromso-2026-06-21")
        t = steps.transit
        self.assertAlmostEqual(day.unrounded["SUNRISE"], t - 9.5 * 3600, delta=0.001)
        self.assertAlmostEqual(day.unrounded["MAGHRIB"], t + 9.5 * 3600, delta=0.001)
        self.assertEqual((day.rules["SUNRISE"], day.rules["MAGHRIB"]), ("MIN_DAY_NIGHT_5H", "MIN_DAY_NIGHT_5H"))
        # D5 on the 5-hour night: Isha = sunset + 17/60 of N(D).
        night = engine.night_length(site, engine.day)
        self.assertAlmostEqual(day.unrounded["ISHA"], t + 9.5 * 3600 + 17 / 60 * night, delta=0.001)
        self.assertEqual(day.rules["ISHA"], "ANGLE_BASED")
        _, _, steps, day = run_case("polar-tromso-2026-12-21")
        t = steps.transit
        self.assertAlmostEqual(day.unrounded["SUNRISE"], t - 2.5 * 3600, delta=0.001)
        self.assertAlmostEqual(day.unrounded["MAGHRIB"], t + 2.5 * 3600, delta=0.001)
        self.assertAlmostEqual(day.unrounded["ASR"], t + 1.25 * 3600, delta=0.001)
        self.assertEqual(day.rules["ASR"], "POLAR_ASR_MIDPOINT")

    def test_diyanet_clamp_at_bodo(self):
        # H2: the Diyanet presets centre on T + 5 min every day and clamp their published sunrise and Maghrib (the
        # offsets − 7 and + 7 inside the clamp); the polar Asr is T, tied to Dhuhr + 5.
        _, _, steps, day = run_case("polar-bodo-diyanet-2026-12-21")
        t = steps.transit
        self.assertAlmostEqual(day.unrounded["SUNRISE"], t + 300 - 2.5 * 3600, delta=0.001)
        self.assertAlmostEqual(steps.sunrise, t + 300 - 2.5 * 3600 + 7 * 60, delta=0.001)
        self.assertAlmostEqual(day.unrounded["MAGHRIB"], t + 300 + 2.5 * 3600, delta=0.001)
        self.assertEqual(day.minutes["ASR"], day.minutes["DHUHR"])
        self.assertEqual(day.rules["ASR"], "DIYANET_CLAMP")

    def test_takdir_case_a_at_cuneo(self):
        # H6, case A: on 21 June, Isha = M + S/3 and Fajr = R − (18/16)·S/3, S = F + 24 h − M, with the published
        # sunrise and Maghrib (no E19 outside mainland France).
        engine, site, _, day = run_case("diyanet-cuneo-2026-06-21")
        f = engine.angle_on(site, engine.day, "FAJR")
        r, m = day.unrounded["SUNRISE"], day.unrounded["MAGHRIB"]
        s = f + 86400 - m
        self.assertAlmostEqual(day.unrounded["ISHA"], m + s / 3, delta=0.001)
        self.assertAlmostEqual(day.unrounded["FAJR"], r - 18 / 16 * s / 3, delta=0.001)
        self.assertEqual((day.rules["FAJR"], day.rules["ISHA"]), ("DIYANET_TAKDIR", "DIYANET_TAKDIR"))

    def test_takdir_cutoff_and_south(self):
        # H6: nothing south of 44.375° N (Rodez keeps its angles on 21 June); in the south the angle times stay and
        # an absent Fajr takes α/60 of the night before (Ushuaia, 21 December).
        _, _, _, rodez = run_case("diyanet-rodez-2026-06-21")
        self.assertNotIn("FAJR", rodez.rules)
        self.assertNotIn("ISHA", rodez.rules)
        engine, site, steps, ushuaia = run_case("diyanet-ushuaia-2026-12-21")
        self.assertIsNone(engine.angle_on(site, engine.day, "FAJR"))
        night = engine.night_length(site, engine.day - ref.ONE_DAY)
        self.assertAlmostEqual(ushuaia.unrounded["FAJR"], steps.sunrise - 18 / 60 * night, delta=0.001)
        self.assertEqual(ushuaia.rules["FAJR"], "DIYANET_TAKDIR")
        _, _, _, winter = run_case("diyanet-ushuaia-2026-06-21")
        self.assertNotIn("FAJR", winter.rules)

    def test_takdir_held_night_at_gavle(self):
        # H2 and H6 where 21 June holds its night: no Fajr earlier, no Isha later than 21 June's (times of day), the
        # sunrise of 22 June to 31 October never earlier than the day before's, the sunset to 21 June never earlier.
        engine, site, _, _ = run_case("diyanet-gavle-2026-06-21")
        self.assertTrue(engine.holds_its_night(site, 2026))
        table = engine.takdir_table(site, 2026)
        june = table[datetime.date(2026, 6, 21)]
        for d, (fajr, isha) in table.items():
            self.assertGreaterEqual(fajr, june[0] - 1e-6, d)
            self.assertLessEqual(isha, june[1] + 1e-6, d)
        d = datetime.date(2026, 3, 2)
        while d <= datetime.date(2026, 10, 31):
            here, before = engine.day_steps(site, d), engine.day_steps(site, d - ref.ONE_DAY)
            if d <= datetime.date(2026, 6, 21):
                self.assertGreaterEqual(here.sunset + 1e-6, before.sunset + 86400, d)
            else:
                self.assertGreaterEqual(here.sunrise + 1e-6, before.sunrise + 86400, d)
            d += ref.ONE_DAY

    def test_dum_rt_at_kazan(self):
        # H4: N's lowest altitude above −18°: Isha = sunset + 90 min, Fajr = sunrise − 120 min.
        _, _, _, day = run_case("dum-rt-kazan-2026-06-21")
        self.assertAlmostEqual(day.unrounded["ISHA"] - day.unrounded["MAGHRIB"], 90 * 60, delta=0.001)
        self.assertAlmostEqual(day.unrounded["SUNRISE"] - day.unrounded["FAJR"], 120 * 60, delta=0.001)
        self.assertEqual((day.rules["FAJR"], day.rules["ISHA"]), ("DUM_RT", "DUM_RT"))

    def test_clock_caps_at_amsterdam(self):
        # H4: Isha no later than 23:30 and Fajr no earlier than 03:30, read at UTC+02:00 on 21 June.
        _, _, _, day = run_case("nl-caps-amsterdam-2026-06-21")
        self.assertEqual(day.unrounded["ISHA"], utc("2026-06-21T21:30"))
        self.assertEqual(day.unrounded["FAJR"], utc("2026-06-21T01:30"))

    def test_moonsighting_curves_at_london(self):
        # H4: on 21 June (dyy = 172 + 10 = 182, the c-to-d segment): value = c + (d − c)·45/46 minutes.
        x = 51.5074 / 55
        fajr = 75 + 32.74 * x + (48.10 - 32.74) * x * 45 / 46
        isha = 75 - 9.21 * x + (6.14 + 9.21) * x * 45 / 46
        self.assertAlmostEqual(fajr, 119.73285, places=5)
        self.assertAlmostEqual(isha, 80.43759, places=5)
        june21 = datetime.date(2026, 6, 21)
        self.assertAlmostEqual(ref.twilight_curve(51.5074, june21, ref.MOONSIGHTING_FAJR), fajr, places=9)
        self.assertAlmostEqual(ref.twilight_curve(51.5074, june21, ref.MOONSIGHTING_ISHA), isha, places=9)
        # Neither angle exists that night in London, so both times are the curves.
        _, _, steps, day = run_case("mcw-london-2026-06-21")
        self.assertAlmostEqual(day.unrounded["FAJR"], steps.sunrise - 60 * fajr, delta=0.001)
        self.assertAlmostEqual(day.unrounded["ISHA"], steps.sunset + 60 * isha, delta=0.001)

    def test_middle_of_night_unramped(self):
        # H3: without the angle, MiddleOfNight(false) puts Isha at the middle of N(D) before any hold.
        engine, site, _, _ = run_case("middle-unramped-oslo-2026-06-21")
        times = engine.steps_1_7(site, engine.day, {}, False)[0]
        start, end = engine.night(site, engine.day)
        self.assertAlmostEqual(times["ISHA"], (start + end) / 2, delta=0.001)

    def test_mecca_cycle_at_tromso(self):
        # H8: inside the summer, the four times keep Mecca's offsets from its transit, Mecca's day being computed
        # with the method's own evaluations and Tromsø's local noon, 18°/18° and the standard horizon.
        case = CASES["irn-tromso-2026-06-21"]
        _, _, steps, day = run_case("irn-tromso-2026-06-21")
        method = dict(case["method"], fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 18.0},
                      twilightLatitudeScale=1.0, polar="MIN_DAY_NIGHT_5H", minDayNightHours=None,
                      highLatitude={"type": "angle_based"},
                      horizon={"sunrise": {"type": "standard"}, "sunset": {"type": "standard"}})
        place = {"latE5": 2142250, "lngE5": 3982620, "elevationM": None, "zoneId": case["place"]["zoneId"], "countryCode": None}
        mecca = ref.CaseEngine(dict(case, id="mecca", method=method, place=place, zone=None, baseZone=None))
        raw = mecca.raw_day(mecca.exact_site(), mecca.day)
        t, mt = steps.transit, raw.transit
        self.assertAlmostEqual(steps.sunrise - t, raw.sunrise - mt, delta=0.001)
        self.assertAlmostEqual(steps.sunset - t, raw.sunset - mt, delta=0.001)
        self.assertAlmostEqual(steps.fajr - t, raw.angle_event("FAJR", 18.0) - mt, delta=0.001)
        self.assertAlmostEqual(steps.isha - t, raw.angle_event("ISHA", 18.0) - mt, delta=0.001)
        maghrib = case["method"]["offsets"].get("MAGHRIB", 0)
        self.assertAlmostEqual(day.unrounded["MAGHRIB"] - 60 * maghrib, steps.sunset, delta=0.001)
        for name in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA"):
            self.assertEqual(day.rules[name], "IRN_MECCA_CYCLE", name)

    def test_local_noon_evaluations(self):
        # S4: DAILY_LOCAL_NOON is the model at 12:00 local time, whatever JD; EVENT_DECLINATION takes δ at JD with
        # E and r at that noon.
        model = ref.SUN_MODELS["MEEUS"]
        noon = 2461212.5 + 10 / 24          # 2026-06-21 12:00 in Oslo (10:00 UT)
        fixed = ref.SunFunction(model, "DAILY_LOCAL_NOON", 2461212.5, noon)
        self.assertEqual(fixed(2461212.9), model(noon))
        mixed = ref.SunFunction(model, "EVENT_DECLINATION", 2461212.5, noon)
        jd = 2461213.4
        self.assertEqual(mixed(jd), (model(jd)[0], model(noon)[1], model(noon)[2]))

    def test_dated_offsets(self):
        # E18: both ends included; a span past 31 December wraps.
        irn = [{"event": "DHUHR", "fromMonth": 3, "fromDay": 21, "toMonth": 9, "toDay": 20, "minutes": 5.0}]
        for day, minutes in (("2026-03-20", 0), ("2026-03-21", 5), ("2026-09-20", 5), ("2026-09-21", 0)):
            self.assertEqual(ref.dated_value(irn, "DHUHR", datetime.date.fromisoformat(day)), minutes, day)
        self.assertEqual(ref.dated_value(irn, "ASR", datetime.date(2026, 6, 1)), 0)
        winter = [{"event": "ISHA", "fromMonth": 12, "fromDay": 1, "toMonth": 1, "toDay": 31, "minutes": -2.0}]
        self.assertEqual(ref.dated_value(winter, "ISHA", datetime.date(2026, 1, 15)), -2)
        self.assertEqual(ref.dated_value(winter, "ISHA", datetime.date(2026, 2, 1)), 0)


class ExpectedFixtureTest(unittest.TestCase):
    def test_expected_tsv_is_what_the_reference_writes(self):
        text = ref.expected_tsv(list(CASES.values()))
        self.assertEqual(len([line for line in text.splitlines() if not line.startswith("#")]), 6 * len(CASES))
        self.assertEqual(EXPECTED_PATH.read_bytes(), text.encode("utf-8"),
                         "expected.tsv is stale: rerun prayer_engine_reference.py --cases ... --out ...")


if __name__ == "__main__":
    unittest.main()
