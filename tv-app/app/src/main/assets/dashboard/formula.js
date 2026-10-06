/*
 * INM's prayer-time formula, as the TV computes it (InmPrayerFormula.kt in multiplatform/shared), for
 * the tab «حساب المواقيت»: the sun of Meeus's 1900-epoch formulae, UTC+1 without summer time, the
 * horizon dip in single precision, minutes rounded half up. The values a mosque may change (the
 * twilight angles, the Asr shadow, the minutes after noon and sunset, the elevation, a whole-minute
 * adjustment per prayer) come in `settings`, with the key names of the settings file's "prayerTimes"
 * section. No DOM: scripts/prayer_formula/check_dashboard_formula.js checks it under node against
 * every official time of 2026.
 */
var PrayerFormula = (function () {
  "use strict";

  var DEG = Math.PI / 180;
  var TIME_ZONE_HOURS = 1;
  var HORIZON_ANGLE = 0.83;
  var EARTH_RADIUS_M = 6378137;
  var ITERATIONS = 5;

  /** The prayers a mosque may move by whole minutes (not the sunrise), in the file's key names. */
  var ADJUSTABLE = ["fajr", "dhuhr", "asr", "maghrib", "isha"];

  /** INM's values: the TV's default, and what «الرجوع إلى الأوقات الرسمية» returns to. */
  var OFFICIAL = {
    fajrAngle: 18, ishaAngle: 18, asrShadow: 1, dhuhrMinutes: 7, maghribMinutes: 2, elevation: true,
    adjust: Object.freeze({ fajr: 0, dhuhr: 0, asr: 0, maghrib: 0, isha: 0 })
  };
  Object.freeze(OFFICIAL);

  /** What the TV accepts, as PrayerFormulaSettings's companion says. */
  var RANGES = {
    angle: { min: 15, max: 20, step: 0.5 },
    asrShadow: { min: 1, max: 2 },
    dhuhrMinutes: { min: 0, max: 15 },
    maghribMinutes: { min: 0, max: 10 },
    adjust: { min: -15, max: 15 }
  };

  /** A number, or the fallback when it is not a finite one. */
  function numberOr(value, fallback) {
    return typeof value === "number" && isFinite(value) ? value : fallback;
  }

  /** Full settings from partial ones (a missing value is the official one), as a new object. */
  function normalize(settings) {
    var s = settings || {};
    var adjust = s.adjust || {};
    var result = {
      fajrAngle: numberOr(s.fajrAngle, OFFICIAL.fajrAngle),
      ishaAngle: numberOr(s.ishaAngle, OFFICIAL.ishaAngle),
      asrShadow: numberOr(s.asrShadow, OFFICIAL.asrShadow),
      dhuhrMinutes: numberOr(s.dhuhrMinutes, OFFICIAL.dhuhrMinutes),
      maghribMinutes: numberOr(s.maghribMinutes, OFFICIAL.maghribMinutes),
      elevation: typeof s.elevation === "boolean" ? s.elevation : OFFICIAL.elevation,
      adjust: {}
    };
    ADJUSTABLE.forEach(function (key) { result.adjust[key] = numberOr(adjust[key], 0); });
    return result;
  }

  /** Whether the settings give INM's official times (every value official, no adjustment). */
  function isOfficial(settings) {
    return same(normalize(settings), OFFICIAL);
  }

  /** Whether two settings hold the same values. */
  function same(a, b) {
    var x = normalize(a);
    var y = normalize(b);
    return x.fajrAngle === y.fajrAngle && x.ishaAngle === y.ishaAngle && x.asrShadow === y.asrShadow &&
      x.dhuhrMinutes === y.dhuhrMinutes && x.maghribMinutes === y.maghribMinutes && x.elevation === y.elevation &&
      ADJUSTABLE.every(function (key) { return x.adjust[key] === y.adjust[key]; });
  }

  /** Julian day at 0h UT of a Gregorian civil date. */
  function julianDay(year, month, day) {
    var a = Math.floor((14 - month) / 12);
    var y = year + 4800 - a;
    var m = month + 12 * a - 3;
    var jdn = day + Math.floor((153 * m + 2) / 5) + 365 * y + Math.floor(y / 4) - Math.floor(y / 100) + Math.floor(y / 400) - 32045;
    return jdn - 0.5;
  }

  /**
   * Horizon dip in degrees. INM rounds R / (R + h) to single precision (Math.fround), which
   * quantises the dip; in double precision hundreds of minutes a year would differ.
   */
  function dipFromElevation(elevationM) {
    return Math.acos(Math.fround(EARTH_RADIUS_M / (EARTH_RADIUS_M + elevationM))) / DEG;
  }

  /** Meeus 1900-epoch sun: [declination in degrees, equation of time in minutes]. */
  function sun(jd) {
    var t = (jd - 2415020.0) / 36525;
    var meanLong = (279.69668 + 36000.76892 * t + 0.0003025 * t * t) % 360;
    var anomaly = 358.47583 + 35999.04975 * t - 0.000150 * t * t - 0.0000033 * Math.pow(t, 3);
    var e = 0.01675104 - 0.0000418 * t - 0.000000126 * t * t;
    var center = (1.919460 - 0.004789 * t - 0.000014 * t * t) * Math.sin(anomaly * DEG) +
      (0.020094 - 0.000100 * t) * Math.sin(2 * anomaly * DEG) +
      0.000293 * Math.sin(3 * anomaly * DEG);
    var omega = 259.18 - 1934.142 * t;
    var apparentLong = meanLong + center - 0.00569 - 0.00479 * Math.sin(omega * DEG);
    var obliquity = 23.452294 - 0.0130125 * t - 0.00000164 * t * t + 0.000000503 * Math.pow(t, 3) +
      0.00256 * Math.cos(omega * DEG);
    var decl = Math.asin(Math.sin(obliquity * DEG) * Math.sin(apparentLong * DEG)) / DEG;
    var y = Math.pow(Math.tan(obliquity / 2 * DEG), 2);
    var eot = y * Math.sin(2 * meanLong * DEG) - 2 * e * Math.sin(anomaly * DEG) +
      4 * e * y * Math.sin(anomaly * DEG) * Math.cos(2 * meanLong * DEG) -
      0.5 * y * y * Math.sin(4 * meanLong * DEG) - 1.25 * e * e * Math.sin(2 * anomaly * DEG);
    return [decl, 4 * eot / DEG];
  }

  /** Hours between solar noon and the moment the sun reaches `altitude`. */
  function hourAngle(altitude, lat, decl) {
    var cosH = (Math.sin(altitude * DEG) - Math.sin(lat * DEG) * Math.sin(decl * DEG)) /
      (Math.cos(lat * DEG) * Math.cos(decl * DEG));
    return Math.acos(Math.min(1, Math.max(-1, cosH))) / DEG / 15;
  }

  /** Local time in hours when the sun is at `altitude`; `sign` -1 = morning, +1 = evening. */
  function horizonEvent(jd0, lat, lng, altitude, sign) {
    var s = sun(jd0);
    for (var i = 0; i < ITERATIONS; i++) s = sun(jd0 + (12 + sign * hourAngle(altitude, lat, s[0])) / 24);
    return 12 + sign * hourAngle(altitude, lat, s[0]) - s[1] / 60 - lng / 15 + TIME_ZONE_HOURS;
  }

  /**
   * One day at a place, computed with `settings` (official when left out). `location` is
   * { latitude, longitude, elevation (m), sunriseElevations: { "2026": 15.6 } }: with the elevation
   * counted, a year's sunrise elevation stands in for the sunrise alone (a quirk of INM's own tables).
   * Gives `minutes`, the unrounded minutes after local midnight of Fajr, Sunrise, Dhuhr, Asr, Maghrib
   * and Isha, adjustments included; `times`, the same rounded half up (what the TV shows); and what
   * explains them: `noon`, `sunrise`, `sunset` (unrounded minutes), `declination`, `equationOfTime`
   * (minutes), `asrAltitude`, `noonShadow` (in stick lengths), `dip` and `sunriseDip` (degrees).
   */
  function day(location, year, month, dayOfMonth, settings) {
    var s = normalize(settings);
    var lat = location.latitude;
    var lng = location.longitude;
    var jd0 = julianDay(year, month, dayOfMonth);
    var s0 = sun(jd0);
    var noon = 12 - s0[1] / 60 - lng / 15 + TIME_ZONE_HOURS;
    var noonShadow = Math.tan(Math.abs(lat - s0[0]) * DEG);
    var asrAltitude = Math.atan(1 / (s.asrShadow + noonShadow)) / DEG;
    var asr = noon + hourAngle(asrAltitude, lat, s0[0]);
    var dip = s.elevation ? dipFromElevation(location.elevation) : 0;
    var overrides = location.sunriseElevations || {};
    var override = overrides[String(year)];
    var sunriseDip = !s.elevation ? 0 : typeof override === "number" && isFinite(override) ? dipFromElevation(override) : dip;
    var sunrise = horizonEvent(jd0, lat, lng, -(HORIZON_ANGLE + sunriseDip), -1) * 60;
    var sunset = horizonEvent(jd0, lat, lng, -(HORIZON_ANGLE + dip), 1) * 60;
    var minutes = [
      horizonEvent(jd0, lat, lng, -(s.fajrAngle + dip), -1) * 60 + s.adjust.fajr,
      sunrise,
      noon * 60 + s.dhuhrMinutes + s.adjust.dhuhr,
      asr * 60 + s.adjust.asr,
      sunset + s.maghribMinutes + s.adjust.maghrib,
      horizonEvent(jd0, lat, lng, -(s.ishaAngle + dip), 1) * 60 + s.adjust.isha
    ];
    return {
      minutes: minutes,
      times: minutes.map(function (m) { return Math.floor(m + 0.5); }),
      noon: noon * 60,
      sunrise: sunrise,
      sunset: sunset,
      declination: s0[0],
      equationOfTime: s0[1],
      asrAltitude: asrAltitude,
      noonShadow: noonShadow,
      dip: dip,
      sunriseDip: sunriseDip
    };
  }

  /** The sun's altitude in degrees at a local time (`hours` after midnight, UTC+1), for the chart. */
  function altitudeAt(location, year, month, dayOfMonth, hours) {
    var lat = location.latitude;
    var ut = hours - TIME_ZONE_HOURS;
    var s = sun(julianDay(year, month, dayOfMonth) + ut / 24);
    var h = 15 * (ut + location.longitude / 15 + s[1] / 60 - 12);
    return Math.asin(Math.sin(lat * DEG) * Math.sin(s[0] * DEG) +
      Math.cos(lat * DEG) * Math.cos(s[0] * DEG) * Math.cos(h * DEG)) / DEG;
  }

  return {
    ADJUSTABLE: ADJUSTABLE,
    OFFICIAL: OFFICIAL,
    RANGES: RANGES,
    normalize: normalize,
    isOfficial: isOfficial,
    same: same,
    julianDay: julianDay,
    dipFromElevation: dipFromElevation,
    sun: sun,
    day: day,
    altitudeAt: altitudeAt
  };
})();

if (typeof module !== "undefined" && module.exports) module.exports = PrayerFormula;
