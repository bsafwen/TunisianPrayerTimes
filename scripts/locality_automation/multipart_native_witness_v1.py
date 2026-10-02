"""Verify completed ordered multipart native evidence without reparsing originals."""
def verify_completed_native(report, code, row, checked, read, check_tree):
    proof = read(checked(report["inputs"]["nativeOnce"]))
    if proof["status"] != "PASS_ONCE_INDEPENDENT_TWO_ORIGINAL_COMPONENTS_ORDERED_UNION_AND_CURRENT" or proof["structuralFailures"] != []:
        raise ValueError("Completed multipart native witness did not pass")
    for field in ("currentJson", "currentBin", "currentNames"):
        if proof[field] != report["inputs"][field]:
            raise ValueError("Native witness current snapshot differs")
    native_row = proof["decodedCurrentChecks"][code]
    for field in ("id", "officialCode", "rawSourceGeometry", "expectedAdoptedGeometry", "sourcePdfParts", "freshOriginalNativeInspection"):
        if native_row[field] != row[field]:
            raise ValueError("Native witness identity/geometry/ordered parts differ")
    inspection = row["freshOriginalNativeInspection"]
    parts = inspection["actualOriginalPartInspections"]
    if inspection["nativeTokenVerificationCompletedOnce"] is not True or inspection["completeContributingComponentCount"] != len(parts) or len(parts) != len(row["sourcePdfParts"]):
        raise ValueError("Complete ordered native contributions required")
    if inspection["getDrawingsCalled"] is not False or inspection["sourceExtractorRerun"] is not False:
        raise ValueError("Completed native proof must be reused")
    for part, pdf in zip(parts, row["sourcePdfParts"]):
        native = part["originalNative"]
        if native["completedOriginalNativeProofReused"] is not True:
            raise ValueError("Original native completion witness missing")
        check_tree(native)
        actual = read(checked(native["actualOriginalNativeControlClipVerification"]))
        if actual["sourcePdf"] != pdf or actual["oneTimeIndependentOriginalOperatorTokenOrderVerification"] is not True or actual["allOriginalGeometricDecimalOperationsExactlyVerified"] is not True:
            raise ValueError("Original ordered PDF/native operation witness differs")
    return {"completedPartCount": len(parts), "nativeTokenParsesPerformed": 0}
