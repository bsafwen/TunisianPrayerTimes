import {
    daysOfYear,
    prayerTimesEndpoint,
    gouvernorat,
    delegation,
    type PrayerTimesApiResponse,
} from "./common";

import fs from "fs";



(async () => {
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
})();
