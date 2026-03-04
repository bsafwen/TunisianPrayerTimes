export const config = {
  year: parseInt(process.env.YEAR ?? String(new Date().getFullYear())),
  concurrency: parseInt(process.env.CONCURRENCY ?? "1"),
  delayMs: parseInt(process.env.DELAY_MS ?? "1000"),
  docsDir: process.env.DOCS_DIR ?? "./docs",
  dataDir: "./data",
  partialYear: process.env.PARTIAL_YEAR === "1",
  repair: process.env.REPAIR === "1",
  check: process.env.CHECK === "1",
};
