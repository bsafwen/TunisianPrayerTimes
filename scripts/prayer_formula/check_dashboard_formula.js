#!/usr/bin/env node
/*
 * Checks the TV dashboard's copy of the formula (tv-app/app/src/main/assets/dashboard/formula.js,
 * the tab «حساب المواقيت») against INM's published times: every delegation and day of 2026 in
 * docs/csv with the official values, then the custom values pinned in the shared module's tests
 * (Tunis, 2026-09-30). Node only, no dependencies:
 *
 *   node scripts/prayer_formula/check_dashboard_formula.js
 *
 * Prints a summary and exits with 1 on any miss.
 */
"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..", "..");
const PrayerFormula = require(path.join(ROOT, "tv-app/app/src/main/assets/dashboard/formula.js"));
const YEAR = 2026;
const NAMES = ["Fajr", "Shuruk", "Duhr", "Asr", "Maghrib", "Isha"];

function hm(minutes) {
  const m = ((minutes % 1440) + 1440) % 1440;
  return String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
}

/** The formula's location from a delegation_params.json entry, overrides included. */
function locationOf(entry) {
  const sunriseElevations = {};
  Object.keys(entry.overrides || {}).forEach((year) => {
    const sunrise = entry.overrides[year].sunrise_elevation_m;
    if (typeof sunrise === "number") sunriseElevations[year] = sunrise;
  });
  return { latitude: entry.lat, longitude: entry.lng, elevation: entry.elevation_m, sunriseElevations };
}

/** The rows of one month's CSV that hold times: [{ day, times: ["HH:MM" × 6] }]. */
function readMonth(file) {
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/).filter((line) => line.trim());
  const header = lines.shift().split(",").map((cell) => cell.trim());
  const columns = NAMES.map((name) => header.indexOf(name));
  if (header[0] !== "Day" || columns.indexOf(-1) >= 0) throw new Error(file + ": unexpected header " + header.join(","));
  return lines.map((line) => line.split(",").map((cell) => cell.trim()))
    .filter((cells) => columns.every((c) => /^\d{2}:\d{2}$/.test(cells[c] || "")))
    .map((cells) => ({ day: Number(cells[0]), times: columns.map((c) => cells[c]) }));
}

const params = JSON.parse(fs.readFileSync(path.join(ROOT, "data/prayer-formula/delegation_params.json"), "utf8"));
const csvRoot = path.join(ROOT, "docs/csv");
const misses = [];
const skipped = [];
let delegations = 0;
let days = 0;
let times = 0;

fs.readdirSync(csvRoot).filter((id) => /^\d+$/.test(id)).sort((a, b) => a - b).forEach((id) => {
  const yearDir = path.join(csvRoot, id, String(YEAR));
  const entry = params[id];
  if (!entry || !fs.existsSync(yearDir)) {
    skipped.push(id + (entry ? " (no " + YEAR + ")" : " (no parameters)"));
    return;
  }
  const location = locationOf(entry);
  let checked = 0;
  for (let month = 1; month <= 12; month++) {
    const file = path.join(yearDir, String(month).padStart(2, "0") + ".csv");
    if (!fs.existsSync(file)) continue;
    readMonth(file).forEach((row) => {
      const computed = PrayerFormula.day(location, YEAR, month, row.day).times.map(hm);
      days++;
      checked++;
      computed.forEach((time, i) => {
        times++;
        if (time !== row.times[i]) {
          misses.push(id + " " + YEAR + "-" + String(month).padStart(2, "0") + "-" + String(row.day).padStart(2, "0") +
            " " + NAMES[i] + ": INM " + row.times[i] + ", formula.js " + time);
        }
      });
    });
  }
  if (checked) delegations++;
  else skipped.push(id + " (no times)");
});

// The custom values of multiplatform/shared's tests (PrayerFormulaSettingsTest), Tunis on 2026-09-30.
const tunis = locationOf(params["615"]);
const pinned = [
  ["official", {}, "04:47 06:13 12:16 15:31 18:07 19:31"],
  ["fajrAngle 16", { fajrAngle: 16 }, "04:57 06:13 12:16 15:31 18:07 19:31"],
  ["asrShadow 2", { asrShadow: 2 }, "04:47 06:13 12:16 16:22 18:07 19:31"],
  ["elevation false", { elevation: false }, "04:47 06:14 12:16 15:31 18:06 19:30"],
  ["fajr 16, Hanafi, no elevation, isha +2", { fajrAngle: 16, asrShadow: 2, elevation: false, adjust: { isha: 2 } },
    "04:58 06:14 12:16 16:22 18:06 19:32"],
  ["dhuhr 6, maghrib 3, fajr 17.5, isha 18.5", { dhuhrMinutes: 6, maghribMinutes: 3, fajrAngle: 17.5, ishaAngle: 18.5 },
    "04:50 06:13 12:15 15:31 18:08 19:33"]
];
pinned.forEach(([name, settings, expected]) => {
  const got = PrayerFormula.day(tunis, 2026, 9, 30, settings).times.map(hm).join(" ");
  if (got !== expected) misses.push("pinned «" + name + "»: expected " + expected + ", formula.js " + got);
});

console.log("Official 2026: " + delegations + " delegations, " + days + " days, " + times + " times checked");
if (skipped.length) console.log("Skipped (no data): " + skipped.join(", "));
console.log("Pinned custom cases (Tunis 2026-09-30): " + pinned.length);
console.log("Misses: " + misses.length);
misses.slice(0, 50).forEach((miss) => console.log("  " + miss));
if (misses.length > 50) console.log("  … and " + (misses.length - 50) + " more");
process.exit(misses.length ? 1 : 0);
