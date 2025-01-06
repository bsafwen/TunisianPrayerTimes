import { fetchPrayerTimes } from "./fetchPrayerTimes";
import { toCSV } from "./toCsv";
import { csvDir, dataDir } from "./common";
import { createFolderIfNotExists } from "./utils";

(async () => {
  createFolderIfNotExists(dataDir);
  await fetchPrayerTimes();

  createFolderIfNotExists(csvDir);
  await toCSV();
})();
