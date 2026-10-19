"""Write the tab bar's outline/filled VectorDrawables from icons.py into android-app/app/src/main/res/drawable."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import icons  # noqa: E402

RES = Path(r"C:\Users\barou\Desktop\Workspace\TunisianPrayerTimes\android-app\app\src\main\res\drawable")

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:fillType="evenOdd"
        android:pathData="{d}" />
</vector>
"""

# file name -> (icon, state)
FILES = {
    "ic_tab_prayer": ("rug", 0),
    "ic_tab_prayer_filled": ("rug", 1),
    "ic_tab_alarms_outline": ("alarm", 0),
    "ic_tab_alarms_filled": ("alarm", 1),
    "ic_tab_qibla": ("kaaba", 0),
    "ic_tab_qibla_filled": ("kaaba", 1),
    "ic_tab_adhkar": ("misbaha", 1),
    "ic_tab_quran_filled": ("quran", 1),
}

if __name__ == "__main__":
    for name, (icon, state) in FILES.items():
        geom = icons.ICONS[icon]()[state]
        out = RES / f"{name}.xml"
        with open(out, "w", encoding="utf-8", newline="") as f:
            f.write(TEMPLATE.format(d=icons.path_data(geom)))
        print(out.name, out.stat().st_size)
