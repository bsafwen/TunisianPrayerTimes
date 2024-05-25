import fs from "fs";
import { fetchPrayerTimes } from "./fetchPrayerTimes";
import { toCSV } from "./toCsv";

(async () => {
    fs.mkdirSync("data");
    fs.mkdirSync("mawaqit-csv");
    await fetchPrayerTimes();
    await toCSV();
})();
