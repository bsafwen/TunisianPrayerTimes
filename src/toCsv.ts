import fs from "fs";
import path from "path";
import {
  csvDir,
  dataDir,
  dawnEndpoint,
  delegation,
  gouvernorat,
  type DawnTimesApiResponse,
} from "./common";
import type { PrayerTimesApiResponse } from "./fetchPrayerTimes";

type CsvRow = {
  Day: string;
  Fajr: string;
  Shuruk: string;
  Duhr: string;
  Asr: string;
  Maghrib: string;
  Isha: string;
};

export async function toCSV() {
  // read all files in the data directory
  const filesPerDay = fs.readdirSync(dataDir);

  // group files per month
  const filesPerMonth: { [k: string]: string[] } = {};

  filesPerDay.forEach((file) => {
    const month = file.split("-")[1];
    filesPerMonth[month] = filesPerMonth[month] || [];
    filesPerMonth[month].push(file);
  });

  // sort files per month
  Object.keys(filesPerMonth).forEach((month) => {
    filesPerMonth[month].sort((a, b) => {
      const dayA = a.split("-")[2].split(".")[0];
      const dayB = b.split("-")[2].split(".")[0];
      return parseInt(dayA) - parseInt(dayB);
    });
  });

  for (let i = 0; i < Object.keys(filesPerMonth).length; i++) {
    const month = Object.keys(filesPerMonth)[i];
    const csvOut = `${csvDir}/${month}.csv`;
    fs.writeFileSync(csvOut, "Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha\n");
    for (let i = 0; i < filesPerMonth[month].length; i++) {
      const file = filesPerMonth[month][i];
      const fullDate = file.split(".")[0];
      const day = fullDate.split("-")[2];
      const data: PrayerTimesApiResponse = JSON.parse(
        fs.readFileSync(path.join(dataDir, file), "utf8")
      );
      const dawnResponse = (await (
        await fetch(`${dawnEndpoint}/${fullDate}/${gouvernorat}/${delegation}/`)
      ).json()) as DawnTimesApiResponse;
      const csvRow: CsvRow = {
        Day: day,
        Fajr: data.data.sobh,
        Shuruk: dawnResponse.data.lever,
        Duhr: data.data.dhohr,
        Asr: data.data.aser,
        Maghrib: data.data.magreb,
        Isha: data.data.isha,
      };
      fs.appendFileSync(
        csvOut,
        `${csvRow.Day},${csvRow.Fajr},${csvRow.Shuruk},${csvRow.Duhr},${csvRow.Asr},${csvRow.Maghrib},${csvRow.Isha}\n`
      );
    }
  }
}
