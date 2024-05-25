import {
    daysOfYear,
    prayerTimesEndpoint,
    gouvernorat,
    delegation,
} from "./common";

import fs from "fs";

export type PrayerTimesApiResponse = {
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
        sobh: string;
        dhohr: string;
        aser: string;
        magreb: string;
        isha: string;
        lat: string;
        lng: string;
        annee: number;
        active: boolean;
    };
    method: "GET";
};

export async function fetchPrayerTimes() {
    for (let i = 0; i < daysOfYear.length; i++) {
        const response = await fetch(
            `${prayerTimesEndpoint}/${daysOfYear[i]}/${gouvernorat}/${delegation}/`
        );
        const data: PrayerTimesApiResponse = await response.json();

        fs.writeFileSync(
            `./data/${daysOfYear[i]}.${gouvernorat}.${delegation}.json`,
            JSON.stringify(data, null, 4)
        );

        await new Promise((resolve) => setTimeout(resolve, 1000));
    }
}
