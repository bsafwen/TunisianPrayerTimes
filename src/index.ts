import fs from "fs";
import path from "path";
import { config } from "./config";
import { fetchDay, fetchDawnTimes } from "./api";
import {
  CSV_HEADER,
  getDaysInMonth,
  rowToCsvLine,
  emptyRow,
  parseExistingCsv,
  writeAtomicCsv,
  rowFromCache,
} from "./csv";
import type { Delegation, CheckResult, GouvernoratConfig } from "./types";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function getMaxMonth(): number {
  if (!config.partialYear) return 12;
  const now = new Date();
  return config.year < now.getFullYear() ? 12 : now.getMonth();
}

function csvFilePath(delegationId: number, month: number): string {
  const mm = String(month).padStart(2, "0");
  return path.join(
    config.docsDir,
    "csv",
    String(delegationId),
    String(config.year),
    `${mm}.csv`,
  );
}

function tag(d: Delegation, i: number, total: number, month?: string): string {
  const prefix = `[${i}/${total} — ${d.nameFr}]`;
  return month ? `${prefix} [${month}]` : prefix;
}

function loadDelegations(): Delegation[] {
  const raw: GouvernoratConfig = JSON.parse(
    fs.readFileSync("./gouvernorats.json", "utf-8"),
  );
  return raw.gouvernorats.flatMap((g) =>
    g.delegations.map((d) => ({
      gouvernoratId: g.id,
      delegationId: d.id,
      nameFr: d.nomFr.trim(),
    })),
  );
}

async function runPool(
  tasks: (() => Promise<void>)[],
  concurrency: number,
): Promise<void> {
  let i = 0;
  const worker = async () => {
    while (i < tasks.length) await tasks[i++]();
  };
  await Promise.all(Array.from({ length: concurrency }, worker));
}

function checkDelegation(
  d: Delegation,
  index: number,
  total: number,
): CheckResult[] {
  const maxMonth = getMaxMonth();
  const problems: CheckResult[] = [];

  for (let month = 1; month <= maxMonth; month++) {
    const mm = String(month).padStart(2, "0");
    const filePath = csvFilePath(d.delegationId, month);
    const days = getDaysInMonth(config.year, month);

    if (!fs.existsSync(filePath)) {
      problems.push({
        delegation: d.nameFr,
        month: `${config.year}/${mm}`,
        fileMissing: true,
        missingDates: days,
      });
      continue;
    }

    const rows = parseExistingCsv(filePath, config.year, month);
    const bad = days.filter((dt) => !rows.has(dt) || rows.get(dt) === null);
    if (bad.length > 0) {
      problems.push({
        delegation: d.nameFr,
        month: `${config.year}/${mm}`,
        fileMissing: false,
        missingDates: bad,
      });
    }
  }

  if (problems.length === 0) {
    console.log(`${tag(d, index, total)} ✓ ok`);
  } else {
    const totalBad = problems.reduce((s, p) => s + p.missingDates.length, 0);
    const months = problems.map((p) => p.month).join(", ");
    console.log(
      `${tag(d, index, total)} ✗ ${totalBad} problem(s) in: ${months}`,
    );
  }

  return problems;
}

function runCheck(delegations: Delegation[]): never {
  console.log(
    `CHECK mode: scanning ${delegations.length} delegations, year ${config.year}…`,
  );

  const allProblems = delegations.flatMap((d, i) =>
    checkDelegation(d, i + 1, delegations.length),
  );

  if (allProblems.length === 0) {
    console.log("\n✓ All files are complete. No empty rows found.");
    process.exit(0);
  }

  console.log("\n─── Problems found ───────────────────────────────────");
  for (const p of allProblems) {
    if (p.fileMissing) {
      console.log(
        `  [${p.month}] ${p.delegation} — file missing (${p.missingDates.length} days)`,
      );
    } else {
      console.log(
        `  [${p.month}] ${p.delegation} — ${p.missingDates.length} empty day(s):`,
      );
      for (const dt of p.missingDates) console.log(`    • ${dt}`);
    }
  }

  const totalBad = allProblems.reduce((s, p) => s + p.missingDates.length, 0);
  console.log(
    `\n✗ ${totalBad} empty/missing row(s) across ${allProblems.length} file(s).`,
  );
  console.log("  Run with REPAIR=1 to fix them.");
  process.exit(1);
}

async function fetchOrReuseDay(
  date: string,
  d: Delegation,
  existingRows: Map<string, string | null> | null,
): Promise<string> {
  if (existingRows) {
    const existing = existingRows.get(date);
    if (existing != null) return existing;
  }

  const cached = rowFromCache(
    date,
    d.gouvernoratId,
    d.delegationId,
    config.dataDir,
  );
  if (cached) {
    try {
      const dawn = await fetchDawnTimes(date, d.gouvernoratId, d.delegationId);
      await sleep(config.delayMs);
      return rowToCsvLine({ ...cached, shuruk: dawn.data.lever });
    } catch (e) {
      console.warn(`[${d.nameFr}] [${date}] shuruk fetch error: ${e}`);
      return rowToCsvLine({ ...cached, shuruk: "" });
    }
  }

  try {
    const row = await fetchDay(date, d.gouvernoratId, d.delegationId);
    await sleep(config.delayMs);
    return rowToCsvLine(row);
  } catch (e) {
    console.warn(
      `[${d.nameFr}] [${date}] fetch error: ${e}. Writing empty row.`,
    );
    return emptyRow(date);
  }
}

async function ingestMonth(
  d: Delegation,
  month: number,
  label: string,
): Promise<void> {
  const filePath = csvFilePath(d.delegationId, month);
  const days = getDaysInMonth(config.year, month);

  let existingRows: Map<string, string | null> | null = null;
  if (fs.existsSync(filePath)) {
    if (!config.repair) {
      console.log(`${label} skipped (exists)`);
      return;
    }
    existingRows = parseExistingCsv(filePath, config.year, month);
    const badCount = days.filter(
      (dt) => !existingRows!.has(dt) || existingRows!.get(dt) === null,
    ).length;
    if (badCount === 0) {
      console.log(`${label} ok (no empty rows)`);
      return;
    }
    console.log(`${label} repairing ${badCount} day(s)…`);
  }

  const lines = [CSV_HEADER];
  for (const date of days) {
    lines.push(await fetchOrReuseDay(date, d, existingRows));
  }

  writeAtomicCsv(filePath, lines);
  console.log(
    existingRows
      ? `${label} repaired`
      : `${label} written (${days.length} days)`,
  );
}

async function ingestDelegation(
  d: Delegation,
  index: number,
  total: number,
): Promise<void> {
  const maxMonth = getMaxMonth();
  for (let month = 1; month <= maxMonth; month++) {
    const mm = String(month).padStart(2, "0");
    await ingestMonth(d, month, tag(d, index, total, `${config.year}/${mm}`));
  }
}

function writeIndexJson(delegations: Delegation[]): void {
  const csvRoot = path.join(config.docsDir, "csv");
  const index: Record<string, number[]> = {};

  for (const d of delegations) {
    const dir = path.join(csvRoot, String(d.delegationId));
    if (!fs.existsSync(dir)) continue;

    const years = fs
      .readdirSync(dir)
      .filter((e) => /^\d{4}$/.test(e))
      .map(Number)
      .sort((a, b) => a - b);

    if (years.length > 0) index[String(d.delegationId)] = years;
  }

  fs.mkdirSync(csvRoot, { recursive: true });
  fs.writeFileSync(
    path.join(csvRoot, "index.json"),
    JSON.stringify(index, null, 2),
    "utf-8",
  );
  console.log(`index.json written (${Object.keys(index).length} delegations)`);
}

async function main() {
  const delegations = loadDelegations();

  if (config.check) runCheck(delegations);

  console.log(
    `Starting ingest: ${delegations.length} delegations, year ${config.year}, ` +
      `concurrency ${config.concurrency}, delay ${config.delayMs}ms`,
  );

  const tasks = delegations.map(
    (d, i) => () => ingestDelegation(d, i + 1, delegations.length),
  );
  await runPool(tasks, config.concurrency);

  writeIndexJson(delegations);
  fs.mkdirSync(config.docsDir, { recursive: true });
  fs.copyFileSync(
    "./gouvernorats.json",
    path.join(config.docsDir, "gouvernorats.json"),
  );
  console.log("gouvernorats.json copied to docs/");
  console.log("Ingest complete.");
}

main().catch((e) => {
  console.error("Fatal error:", e);
  process.exit(1);
});
