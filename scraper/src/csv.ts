import fs from "fs";
import path from "path";
import type { CsvRow, PrayerTimesApiResponse } from "./types";

export const CSV_HEADER = "Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha";

export function getDaysInMonth(year: number, month: number): string[] {
  const days: string[] = [];
  const mm = String(month).padStart(2, "0");
  const d = new Date(year, month - 1, 1);
  while (d.getMonth() === month - 1) {
    days.push(`${year}-${mm}-${String(d.getDate()).padStart(2, "0")}`);
    d.setDate(d.getDate() + 1);
  }
  return days;
}

export function rowToCsvLine(r: CsvRow): string {
  const dd = r.date.split("-")[2];
  return `${dd},${r.fajr},${r.shuruk},${r.duhr},${r.asr},${r.maghrib},${r.isha}`;
}

export function emptyRow(date: string): string {
  return `${date.split("-")[2]},,,,,,`;
}

export function parseExistingCsv(
  csvPath: string,
  year: number,
  month: number,
): Map<string, string | null> {
  const result = new Map<string, string | null>();
  const mm = String(month).padStart(2, "0");

  for (const line of fs.readFileSync(csvPath, "utf-8").split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const fields = trimmed.split(",");
    const dd = fields[0];
    if (!dd || dd === "Day") continue;

    const date = `${year}-${mm}-${dd}`;
    const hasEmpty = fields.slice(1).some((f) => !f?.trim());
    result.set(date, hasEmpty ? null : trimmed);
  }
  return result;
}

export function writeAtomicCsv(csvPath: string, lines: string[]): void {
  const tmpPath = csvPath + ".tmp";
  fs.mkdirSync(path.dirname(csvPath), { recursive: true });
  fs.writeFileSync(tmpPath, lines.join("\n") + "\n", "utf-8");
  fs.renameSync(tmpPath, csvPath);
}

export function rowFromCache(
  date: string,
  gouvernoratId: number,
  delegationId: number,
  dataDir: string,
): Omit<CsvRow, "shuruk"> | null {
  const cachePath = path.join(
    dataDir,
    `${date}.${gouvernoratId}.${delegationId}.json`,
  );
  if (!fs.existsSync(cachePath)) return null;

  try {
    const cached: PrayerTimesApiResponse = JSON.parse(
      fs.readFileSync(cachePath, "utf-8"),
    );
    return {
      date,
      fajr: cached.data.sobh,
      duhr: cached.data.dhohr,
      asr: cached.data.aser,
      maghrib: cached.data.magreb,
      isha: cached.data.isha,
    };
  } catch {
    return null;
  }
}
