export const prayerTimesEndpoint = "https://www.meteo.tn/horaire_gouvernorat";
export const dawnEndpoint = "https://www.meteo.tn/lever_coucher_gouvernorat";
export const gouvernorat = 342;
export const delegation = 615;

export const daysOfYear: string[] = [];

const date = new Date();
date.setFullYear(date.getFullYear(), 0, 1);

for (let i = 0; i < 365; i++) {
  date.setDate(date.getDate() + 1);
  const twoDigitsDayFormat =
    date.getDate() < 10 ? `0${date.getDate()}` : date.getDate();
  const twoDigitsMonthFormat =
    date.getMonth() + 1 < 10 ? `0${date.getMonth() + 1}` : date.getMonth() + 1;
  daysOfYear.push(
    `${date.getFullYear()}-${twoDigitsMonthFormat}-${twoDigitsDayFormat}`
  );
}

export type DawnTimesApiResponse = {
  data: {
    id: number;
    gouvernorat: {
      id: number;
      intituleFr: string;
      intituleAr: string;
      intituleAn: string;
    };
    delegation: {
      id: number;
      intituleFr: string;
      intituleAr: string;
      intituleAn: string;
      parent: {
        id: number;
        intituleFr: string;
        intituleAr: string;
        intituleAn: string;
      };
    };
    date: string;
    lever: string;
    pm: string;
    coucher: string;
    lat: string;
    lng: string;
    annee: number;
    active: boolean;
  };
  method: "GET";
};

export const dataDir = "./data";
export const csvDir = "./mawaqit-csv";
