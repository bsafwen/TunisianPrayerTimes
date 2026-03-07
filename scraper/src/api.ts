import type {
  PrayerTimesApiResponse,
  DawnTimesApiResponse,
  CsvRow,
} from "./types";

const PRAYER_TIMES_URL = "https://www.meteo.tn/horaire_gouvernorat";
const DAWN_TIMES_URL = "https://www.meteo.tn/lever_coucher_gouvernorat";

function buildUrl(
  base: string,
  date: string,
  gouvernoratId: number,
  delegationId: number,
): string {
  return `${base}/${date}/${gouvernoratId}/${delegationId}/`;
}

export async function fetchPrayerTimes(
  date: string,
  gouvernoratId: number,
  delegationId: number,
): Promise<PrayerTimesApiResponse> {
  const url = buildUrl(PRAYER_TIMES_URL, date, gouvernoratId, delegationId);
  const res = await fetch(url);
  return res.json() as Promise<PrayerTimesApiResponse>;
}

export async function fetchDawnTimes(
  date: string,
  gouvernoratId: number,
  delegationId: number,
): Promise<DawnTimesApiResponse> {
  const url = buildUrl(DAWN_TIMES_URL, date, gouvernoratId, delegationId);
  const res = await fetch(url);
  return res.json() as Promise<DawnTimesApiResponse>;
}

export async function fetchDay(
  date: string,
  gouvernoratId: number,
  delegationId: number,
): Promise<CsvRow> {
  const [prayer, dawn] = await Promise.all([
    fetchPrayerTimes(date, gouvernoratId, delegationId),
    fetchDawnTimes(date, gouvernoratId, delegationId),
  ]);

  return {
    date,
    fajr: prayer.data.sobh,
    shuruk: dawn.data.lever,
    duhr: prayer.data.dhohr,
    asr: prayer.data.aser,
    maghrib: prayer.data.magreb,
    isha: prayer.data.isha,
  };
}
