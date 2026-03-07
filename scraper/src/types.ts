type LocalizedName = {
  intituleFr: string;
  intituleAr: string;
  intituleAn: string;
};

type ApiLocation = {
  id: number;
  gouvernorat: { id: number } & LocalizedName;
  delegation: {
    id: number;
    parent: { id: number } & LocalizedName;
  } & LocalizedName;
  date: string;
  lat: string;
  lng: string;
  annee: number;
  active: boolean;
};

export type PrayerTimesApiResponse = {
  data: ApiLocation & {
    sobh: string;
    dhohr: string;
    aser: string;
    magreb: string;
    isha: string;
  };
  method: "GET";
};

export type DawnTimesApiResponse = {
  data: ApiLocation & {
    lever: string;
    pm: string;
    coucher: string;
  };
  method: "GET";
};

export type CsvRow = {
  date: string;
  fajr: string;
  shuruk: string;
  duhr: string;
  asr: string;
  maghrib: string;
  isha: string;
};

export type Delegation = {
  gouvernoratId: number;
  delegationId: number;
  nameFr: string;
};

export type CheckResult = {
  delegation: string;
  month: string;
  fileMissing: boolean;
  missingDates: string[];
};

export type GouvernoratConfig = {
  gouvernorats: Array<{
    id: number;
    nomFr: string;
    delegations: Array<{ id: number; nomFr: string }>;
  }>;
};
