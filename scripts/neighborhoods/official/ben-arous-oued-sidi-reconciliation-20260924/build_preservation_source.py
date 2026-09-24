#!/usr/bin/env python3
"""Build the WGS84 Sidi Salem preservation ring from pinned GeoPDF extraction metadata."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / "evidence"
PDF = EVIDENCE / "sidi-salem-el-garsi.pdf"
METADATA = EVIDENCE / "sidi-salem-el-garsi-extraction-metadata.json"
GEOJSON = HERE / "sidi-salem-preserved-junction.geojson"
REVIEW = HERE / "source-review.json"
PDF_URL = "https://www.isie.tn/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/%D8%A8%D9%86%20%D8%B9%D8%B1%D9%88%D8%B3/%D9%85%D8%B1%D9%86%D8%A7%D9%82/%D8%B3%D9%8A%D8%AF%D9%8A%20%D8%B3%D8%A7%D9%84%D9%85%20%D8%A7%D9%84%D9%82%D8%A7%D8%B1%D8%B5%D9%8A.pdf"
FEATURE_ID = "sidi-salem-el-garsi-red-sector-ring"

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")

def main() -> None:
    metadata = json.loads(METADATA.read_text(encoding="utf-8"))
    pdf_sha = digest(PDF)
    if metadata.get("sha256") != pdf_sha:
        raise ValueError("Extraction metadata does not pin the copied Sidi Salem PDF")
    rings = metadata.get("candidateRings")
    if not isinstance(rings, list) or len(rings) != 1:
        raise ValueError("Expected one candidate ring from the Sidi Salem source sheet")
    ring = rings[0]
    geometry = ring.get("geometry")
    if not isinstance(geometry, dict) or geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        raise ValueError("Preservation source must be a GeoJSON polygon")
    if ring.get("visualReview") != "PENDING":
        raise ValueError("Unexpected source-ring review state; reassess before rebuilding")
    feature = {
        "type": "Feature",
        "id": FEATURE_ID,
        "properties": {
            "purpose": "preserve_only_unresolved_cross_governorate_seam_overlap",
            "officialSectorCode": metadata["inputSectorCode"],
            "sourcePdfSha256": pdf_sha,
            "extractionMetadataSha256": digest(METADATA),
            "sourceRingStatus": ring.get("status"),
            "visualReviewAtExtraction": ring.get("visualReview"),
        },
        "geometry": geometry,
    }
    collection = {"type": "FeatureCollection", "features": [feature]}
    write_json(GEOJSON, collection)
    geojson_sha = digest(GEOJSON)
    review = {
        "schemaVersion": 1,
        "status": "provisional_best_effort_boundary_candidate",
        "sourceGeojsonSha256": geojson_sha,
        "sourcePdfURL": PDF_URL,
        "sourcePdfSha256": pdf_sha,
        "extractionMetadata": "evidence/sidi-salem-el-garsi-extraction-metadata.json",
        "extractionMetadataSha256": digest(METADATA),
        "sourceFeatureId": FEATURE_ID,
        "purpose": "Preservation mask only; this ring is not installed as the Sidi Salem boundary.",
        "boundaryEvidence": {
            "ouedPdfSha256": "0a4d25fd54e5efb7d9c7768c4feb549d0cf61c2c6c2624dfe6d5969b1f8ab84b",
            "sidiPdfSha256": pdf_sha,
            "blackGovernorateLineHausdorffMeters": 9.1,
            "ouedGeoPdfMaximumControlResidualMeters": 0.1698565984668559,
            "sidiGeoPdfMaximumControlResidualMeters": 0.21831580757678234,
            "installedIntersectionSupportedByOuedRingKm2": 0.474293,
            "installedIntersectionSupportedBySidiRingKm2": 0.006438,
        },
        "clipPolicy": "Subtract only the intersection of the installed Sidi Salem polygon with the Oued Ezzit official candidate, excluding the portion covered by this Sidi Salem source ring plus one 1e-6 degree app-grid unit of preservation margin. Keep the source-supported junction overlap unresolved so runtime conflict handling can suppress a confident label there.",
        "preserveBufferGridUnits": 1,
        "preserveBufferSizeDegrees": 0.000001,
        "uncertainty": "The extracted Sidi Salem ring remains PENDING for standalone whole-boundary review. It is used only as an exemption mask at the cross-governorate junction. The retained source-supported overlap remains unresolved; no 100 percent geographic reliability is claimed.",
    }
    write_json(REVIEW, review)
    print(json.dumps({"featureId": FEATURE_ID, "pdfSha256": pdf_sha, "geojsonSha256": geojson_sha,
                      "sourceReviewSha256": digest(REVIEW)}, ensure_ascii=True, indent=2))

if __name__ == "__main__":
    main()

