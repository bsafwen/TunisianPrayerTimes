"""Inspect explicit JSON fields with a hard output cap; default to keys only."""
import argparse
import json
from pathlib import Path


def select(document, path):
    value = document
    for component in path.split("."):
        if isinstance(value, dict):
            if component not in value:
                raise KeyError("Missing field " + path + "; available keys: " + ", ".join(list(value)[:32]))
            value = value[component]
        elif isinstance(value, list) and component.isdecimal():
            value = value[int(component)]
        else:
            raise KeyError("Cannot descend into field " + path)
    return value


def inspect(path, fields, maximum):
    if not 256 <= maximum <= 12000 or len(fields) > 24:
        raise ValueError("Output cap must be256..12000 characters and fields at most24")
    document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    keys = list(document) if isinstance(document, dict) else []
    try:
        values = {field: select(document, field) for field in fields}
    except (KeyError, IndexError) as exc:
        result = {"status": "UNKNOWN_FIELD", "error": str(exc)[:maximum // 2], "topLevelKeys": keys[:32]}
        encoded = json.dumps(result, ensure_ascii=False)
        if len(encoded) > maximum:
            encoded = json.dumps({"status": "UNKNOWN_FIELD", "error": str(exc)[:maximum // 4]}, ensure_ascii=False)
        return encoded, 1
    result = {"status": "OK", "fields": values} if fields else {"status": "KEYS_ONLY", "topLevelKeys": keys[:64]}
    encoded = json.dumps(result, ensure_ascii=False)
    if len(encoded) > maximum:
        result = {"status": "SELECT_NARROWER_FIELDS", "selectedCharacterCount": len(encoded),
                  "maximumCharacters": maximum, "fieldCount": len(fields)}
        return json.dumps(result), 2
    return encoded, 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--path", type=Path, required=True)
    parser.add_argument("--field", action="append", default=[])
    parser.add_argument("--max-chars", type=int, default=6000)
    args = parser.parse_args()
    encoded, status = inspect(args.path, args.field, args.max_chars)
    print(encoded)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
