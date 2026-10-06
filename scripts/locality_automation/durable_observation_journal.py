"""Persist one complete four-mode probe before classifying any observation.

The barrier is shared by four already-written records, not postponed past a
classification. Original order, JSON serialization and all observations remain.
"""
import json
import os


MODES = (None, 5, 20, 50)


def validate_probe(records):
    if len(records) != 4:
        raise ValueError("Exactly four original accuracy modes required")
    first = records[0]
    identity = (first["probeOrdinal"], first["id"], first["lat"], first["lng"])
    for ordinal, (row, mode) in enumerate(zip(records, MODES)):
        if (row["modeOrdinal"] != ordinal or row["accuracyMeters"] != mode
                or (row["probeOrdinal"], row["id"], row["lat"], row["lng"]) != identity):
            raise ValueError("Original probe identity, coordinates or mode order differs")


def persist_probe(stream, records, classify, barrier=os.fsync):
    validate_probe(records)
    for row in records:
        stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
    stream.flush()
    barrier(stream.fileno())
    for row in records:
        classify(row)


def persist_original_rows(stream, rows, classify, barrier=os.fsync):
    """Historical one-record barrier retained for counterbalanced comparison."""
    for row in rows:
        stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
        stream.flush()
        barrier(stream.fileno())
        classify(row)
