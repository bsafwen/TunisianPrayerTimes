# Sourced by release.sh, android-app/build-aab.sh and android-app/test-asset-packs.sh.
# Sets PYTHON to a Python 3.8+ command, as an array (e.g. "py -3"). Each candidate is run, not just
# looked up: on Windows "python3" may be the Microsoft Store placeholder, which only prints a hint.
find_python() {
    local candidate
    for candidate in python3 python "py -3"; do
        # Unquoted on purpose: "py -3" is a command and its argument.
        if $candidate -c 'import sys; sys.exit(sys.version_info < (3, 8))' >/dev/null 2>&1; then
            read -r -a PYTHON <<< "$candidate"
            return 0
        fi
    done
    echo "✗ Python 3.8+ is needed to stage the Quran packs. On Windows, install it from python.org" >&2
    echo "  or turn off the python3 'App execution alias' in Settings." >&2
    return 1
}
