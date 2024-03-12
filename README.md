This will fetch prayer times from https://www.meteo.tn and store them in a CSV in mawaqit format.

1. Create a folder called data in the root of the repository
2. Run ts-node ./src/fetchPrayerTimes.ts
3. Run ts-node ./src/toCsv.ts

Now you have 12 csv files that you can upload to mawaqit.
