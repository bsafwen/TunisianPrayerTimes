"""Plan 9.1, "On device": DeviceScenariosInstrumentedTest on a throwaway emulator, then its times on the JVM.

The checks move the phone's zone, so they never run on an existing emulator or AVD. The script creates an AVD of its
own from an installed x86_64 phone system image, in its own folder (``ANDROID_AVD_HOME`` under ``--out``, so Android
Studio's list is untouched), boots it on port 5570 (``emulator-5570``), and afterwards stops it and deletes the
folder. Every adb call names that serial: never another emulator, never a phone. Instrumented tests go through
``am instrument``, not Gradle's connected tasks (they reach every device and uninstall the app afterwards).

Steps: build the debug and test APKs, create and boot the AVD, install both APKs, put the emulated GPS in Dubai (the
relocation scenario also installs test providers), run the class with ``-e deviceScenarios true``, pull
``files/device-scenarios.tsv``, ``device-timings.tsv`` and ``device-notes.tsv``, run ``DeviceScenariosCheckTest`` on the first
(``:shared:javaTest``), stop the emulator and delete the AVD.

    python scripts/device_checks/run_device_scenarios.py [--skip-build] [--keep] [--out DIR]
    python scripts/device_checks/run_device_scenarios.py --remove     # stop and delete an AVD left by --keep

JAVA_HOME must name a JDK for Gradle. Standard library only.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

SERIAL = "emulator-5570"
PORT = "5570"
AVD_NAME = "device_checks_throwaway"
APP = "com.tunisianprayertimes.dev"
RUNNER = f"{APP}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.tunisianprayertimes.DeviceScenariosInstrumentedTest"
RACE_CLASS = "com.tunisianprayertimes.GuardServiceRaceInstrumentedTest"  # its own run: a crash ends the process
DUBAI = ("55.2708", "25.2048")  # geo fix takes the longitude first
BACKGROUND_STATE: list[str] = []  # the app's process and jobs around the background zone change

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "android-app/app/build/device-scenarios"


def sdk() -> pathlib.Path:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            return pathlib.Path(os.environ[name])
    return pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"


def tool(*parts: str) -> str:
    path = sdk().joinpath(*parts)
    return str(path.with_suffix(".exe")) if os.name == "nt" else str(path)


def adb(*args: str, check: bool = True, timeout: float = 600) -> subprocess.CompletedProcess:
    return subprocess.run([tool("platform-tools", "adb"), "-s", SERIAL, *args], check=check, capture_output=True, timeout=timeout)


def gradle(project: str, *args: str) -> None:
    wrapper = ROOT / project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(wrapper), *args, "--console=plain", "-q"], cwd=ROOT / project, check=True)


def running_serials() -> list[str]:
    out = subprocess.run([tool("platform-tools", "adb"), "devices"], check=True, capture_output=True, text=True).stdout
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip()]


def system_image() -> pathlib.Path:
    """The newest installed x86_64 phone image (not a beta, TV, Wear or Automotive one)."""
    def api(folder: pathlib.Path) -> tuple[float, str]:
        number = re.match(r"android-(\d+(?:\.\d+)?)$", folder.parent.parent.name)
        return (float(number.group(1)) if number else -1.0, folder.parent.name)
    images = [
        d for d in (sdk() / "system-images").glob("android-*/*/x86_64")
        if (d / "system.img").is_file() and api(d)[0] > 0
        and not any(word in d.parent.name for word in ("tv", "wear", "automotive", "desktop"))
    ]
    if not images:
        raise SystemExit(f"No x86_64 phone system image under {sdk() / 'system-images'}")
    return max(images, key=api)


def create_avd(home: pathlib.Path) -> pathlib.Path:
    """A new AVD in [home], from [system_image]; the emulator builds its data partition on the first boot."""
    image = system_image()
    props = dict(line.split("=", 1) for line in (image / "source.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
    tag = props.get("SystemImage.TagId", image.parent.name)
    tags = props.get("SystemImage.TagIds", tag)
    folder = home / f"{AVD_NAME}.avd"
    folder.mkdir(parents=True)
    (home / f"{AVD_NAME}.ini").write_text(f"avd.ini.encoding=UTF-8\npath={folder}\ntarget={image.parent.parent.name}\n", encoding="utf-8")
    sysdir = str(image.relative_to(sdk())) + os.sep
    config = {
        "AvdId": AVD_NAME, "avd.ini.displayname": AVD_NAME, "abi.type": "x86_64", "hw.cpu.arch": "x86_64",
        "hw.cpu.ncore": "4", "hw.ramSize": "4096", "disk.dataPartition.size": "6G", "image.sysdir.1": sysdir,
        "tag.id": tag, "tag.ids": tags, "PlayStore.enabled": "true" if "playstore" in tag else "false",
        "hw.lcd.width": "1080", "hw.lcd.height": "2400", "hw.lcd.density": "420", "hw.keyboard": "yes",
        "hw.gpu.enabled": "yes", "hw.gpu.mode": "auto", "hw.gps": "yes", "hw.sdCard": "no", "fastboot.forceColdBoot": "yes",
    }
    (folder / "config.ini").write_text("".join(f"{k}={v}\n" for k, v in config.items()), encoding="utf-8")
    print(f"Created AVD {AVD_NAME} from {sysdir} in {home}")
    return folder


def wait_for_boot(emulator: subprocess.Popen, limit_s: float = 900) -> None:
    end = time.monotonic() + limit_s
    while time.monotonic() < end:
        if emulator.poll() is not None:
            raise SystemExit(f"The emulator exited with {emulator.returncode} before booting; see emulator.log")
        if SERIAL in running_serials():
            done = adb("shell", "getprop", "sys.boot_completed", check=False).stdout.decode().strip()
            if done == "1":
                return
        time.sleep(5)
    raise SystemExit(f"{SERIAL} did not finish booting in {limit_s:.0f} s")


def instrument(*extra: str, timeout: float = 1800) -> str:
    """One ``am instrument`` run of the class (``-e deviceScenarios true`` plus [extra]), its raw report."""
    run = adb("shell", "am", "instrument", "-w", "-r", "-e", "deviceScenarios", "true", *extra, RUNNER, check=False, timeout=timeout)
    return run.stdout.decode("utf-8", "replace")


def background_relocation() -> list[str]:
    """
    Plan 6.8's background case, which an instrumented process cannot show (it may count as a foreground one): the
    test leaves Paris stored with auto-location on and Dubai as the last location of its test providers; the end of
    the instrumentation stops the app's process; the zone changes, so the broadcast starts the app in the background
    for the travel check; after up to 150 s the test reads the outcome (a move, or the job's picker notification),
    the location app-ops and the travel log, and restores the state. The process and job state go to [out].
    """
    report = instrument("-e", "phase", "prepare-background", "-e", "class", f"{TEST_CLASS}#prepareBackgroundRelocation")
    time.sleep(5)
    state = ["before the zone change, pid: " + adb("shell", "pidof", APP, check=False).stdout.decode().strip()]
    adb("shell", "cmd", "alarm", "set-timezone", "Asia/Dubai", check=False)
    end = time.monotonic() + 150
    sampled = False
    while time.monotonic() < end:
        stored = adb("exec-out", "run-as", APP, "cat", "shared_prefs/prayer_silence_prefs.xml", check=False).stdout
        if b'name="place_country">AE<' in stored:
            break
        if not sampled and time.monotonic() > end - 120:
            sampled = True
            state.append("30 s after, pid: " + adb("shell", "pidof", APP, check=False).stdout.decode().strip())
            jobs = adb("shell", "dumpsys", "jobscheduler", check=False).stdout.decode("utf-8", "replace")
            state += [line.strip() for line in jobs.splitlines() if APP in line][:12]
        time.sleep(5)
    BACKGROUND_STATE.extend(state)
    return [report, instrument("-e", "phase", "finish-background", "-e", "class", f"{TEST_CLASS}#finishBackgroundRelocation")]


def remove(home: pathlib.Path, emulator: subprocess.Popen | None = None) -> None:
    """Stops emulator-5570 when it runs this script's AVD, then deletes the AVD's folder."""
    if SERIAL in running_serials():
        name = adb("emu", "avd", "name", check=False).stdout.decode(errors="replace").split()
        if name[:1] == [AVD_NAME]:
            adb("emu", "kill", check=False)
        else:
            print(f"{SERIAL} runs {name[:1]}, not {AVD_NAME}: left alone")
    if emulator is not None:
        try:
            emulator.wait(timeout=120)
        except subprocess.TimeoutExpired:
            # Still booting, or adb lost it: stop this script's own process and its qemu child.
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(emulator.pid), "/T", "/F"], capture_output=True)
            else:
                emulator.kill()
            emulator.wait(timeout=30)
    for _ in range(12):  # the emulator releases its image files a moment after it exits
        if not home.exists():
            break
        shutil.rmtree(home, ignore_errors=True)
        time.sleep(5)
    print(f"Deleted {home}" if not home.exists() else f"Could not delete {home} yet; delete it by hand")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keep", action="store_true", help="leave the throwaway emulator running (then --remove)")
    parser.add_argument("--remove", action="store_true", help="stop and delete a throwaway emulator left by --keep")
    parser.add_argument("--skip-build", action="store_true", help="reuse the APKs already built")
    parser.add_argument("--race-only", action="store_true", help="run only the guard service's start-stop race")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="folder for the dump, the logs and the throwaway AVD")
    args = parser.parse_args()
    out = pathlib.Path(args.out)
    home = out / "avd"
    if args.remove:
        remove(home)
        return 0
    out.mkdir(parents=True, exist_ok=True)

    if SERIAL in running_serials():
        raise SystemExit(f"{SERIAL} is already running: these checks use only the throwaway emulator this script creates")
    if home.exists():
        raise SystemExit(f"{home} is left from an earlier run: run with --remove first")

    if not args.skip_build:
        gradle("android-app", ":app:assembleDebug", ":app:assembleDebugAndroidTest")
    apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

    create_avd(home)
    emulator = subprocess.Popen(
        [tool("emulator", "emulator"), "-avd", AVD_NAME, "-port", PORT, "-no-snapshot", "-no-boot-anim", "-no-audio", "-no-window"],
        stdout=open(out / "emulator.log", "wb"), stderr=subprocess.STDOUT, env=dict(os.environ, ANDROID_AVD_HOME=str(home)),
    )
    try:
        wait_for_boot(emulator)
        adb("install", "-r", "-t", apk.as_posix())
        adb("install", "-r", "-t", test_apk.as_posix())
        adb("emu", "geo", "fix", *DUBAI, check=False)
        started = time.monotonic()
        if args.race_only:
            report = instrument("-e", "class", RACE_CLASS)
            (out / "instrument.txt").write_text(report, encoding="utf-8", newline="")
            summary = [line for line in report.splitlines() if line.startswith(("OK (", "FAILURES!!!", "INSTRUMENTATION_RESULT: shortMsg"))]
            print(f"Guard service race ({time.monotonic() - started:.0f} s): " + " | ".join(summary))
            return 0 if any(line.startswith("OK (") for line in summary) else 1
        reports = [instrument("-e", "class", TEST_CLASS), *background_relocation(), instrument("-e", "class", RACE_CLASS)]
        report = "\n".join(reports)
        (out / "instrument.txt").write_text(report, encoding="utf-8", newline="")
        dump = adb("exec-out", "run-as", APP, "cat", "files/device-scenarios.tsv", check=False).stdout
        (out / "device-scenarios.tsv").write_bytes(dump)
        timings = adb("exec-out", "run-as", APP, "cat", "files/device-timings.tsv", check=False).stdout
        (out / "device-timings.tsv").write_bytes(timings)
        notes = adb("exec-out", "run-as", APP, "cat", "files/device-notes.tsv", check=False).stdout
        (out / "device-notes.tsv").write_bytes(notes)
        travel = adb("exec-out", "run-as", APP, "cat", "files/device-background-log.txt", check=False).stdout
        (out / "device-background-log.txt").write_bytes(travel + "\n".join(BACKGROUND_STATE).encode("utf-8") + b"\n")
        tail = [line for line in report.splitlines() if line.startswith(("OK (", "FAILURES!!!", "Tests run:", "INSTRUMENTATION_CODE"))]
        print(f"am instrument ({time.monotonic() - started:.0f} s): " + " | ".join(tail))
        print("365-day scans (ms): " + "; ".join(timings.decode("utf-8", "replace").splitlines()[1:]))
        print("Relocation on TIMEZONE_CHANGED: " + "; ".join(notes.decode("utf-8", "replace").splitlines()[1:]))
        if b"\t" not in dump:
            print("No device-scenarios.tsv on the device; see instrument.txt")
            return 1
        env = dict(os.environ, DEVICE_SCENARIOS_DUMP=str(out / "device-scenarios.tsv"))
        jvm = subprocess.run(
            [str(ROOT / "multiplatform" / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":shared:javaTest",
             "--tests", "com.tunisianprayertimes.place.DeviceScenariosCheckTest", "--rerun", "--console=plain", "-i"],
            cwd=ROOT / "multiplatform", env=env, capture_output=True, text=True, errors="replace",
        )
        (out / "jvm-check.txt").write_text(jvm.stdout + jvm.stderr, encoding="utf-8", newline="")
        summary = [line.strip() for line in jvm.stdout.splitlines() if "Device scenarios" in line or "differences" in line]
        print("JVM check: " + ("passed" if jvm.returncode == 0 else "FAILED"))
        for line in summary:
            print("  " + line)
        passed = all(any(line.startswith("OK (") for line in part.splitlines()) for part in reports)
        return 0 if jvm.returncode == 0 and passed else 1
    finally:
        if args.keep:
            print(f"{SERIAL} ({AVD_NAME}) left running; stop and delete it with --remove")
        else:
            remove(home, emulator)


if __name__ == "__main__":
    sys.exit(main())
