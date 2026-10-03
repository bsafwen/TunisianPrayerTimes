import copy
import unittest
from scripts.locality_automation.verify_retained_activity_publication import verify


class RetainedActivityTests(unittest.TestCase):
    def fixture(self):
        summary = dict(validationIssues=[], sourceOnlyAuditIssues=[], reportingIssues=[],
                       uniqueValidatedLocations=1, validatedLocationCodes=["1"], unverifiedHistoricalClaimCount=0)
        before = dict(summary=summary, validations=[{"id": "kept"}], historicalClaims=[],
                      sourceOnlyAcceptances=[], cumulativeSeries=[dict(hour="2026-10-03T18:00:00Z", label="18",
                      validatedLocationCount=0, validatedLocationCodes=[], newValidatedLocationCount=0,
                      repeatValidatedLocationCount=0, cumulativeValidatedLocationCount=1)], hours=[])
        phase = dict(taskId="source-only", title="Native replay", locationCodes=["2", "3"], endAction="complete",
                     detail="No geographic credit", startedAtUtc="2026-10-03T18:30:00Z", finishedAtUtc="2026-10-03T18:30:04Z")
        task = dict(taskId=phase["taskId"], title=phase["title"], locationCodes=phase["locationCodes"],
                    result=phase["detail"], status="complete", startedAtUtc=phase["startedAtUtc"],
                    endedAtUtc=phase["finishedAtUtc"], sharedClock=True, validationIds=[], sourceOnlyAcceptanceIds=[])
        after = copy.deepcopy(before)
        after["hours"] = [{"locationGroups": [{"tasks": [task]}]}]
        activity = dict(status="APPENDED_ORIGINAL_TASK_ACTIVITY_NO_REFRESH", newGeographicCredit=0,
                        ledgerOrAssetChanges=False, phases=[phase])
        model = dict(generatedAtUtc="old", locations=[{"code": "1", "geometry": [1, 2]}])
        return before, after, model, copy.deepcopy(model), activity

    def test_shared_clock_zero_credit_and_unverified_text_claim(self):
        values = self.fixture()
        values[3]["generatedAtUtc"] = "new"
        self.assertEqual(verify(*values)[0]["sharedClock"], True)
        phase = values[4]["phases"][0]
        values[1]["historicalClaims"] = [dict(eventId="text", taskId=phase["taskId"],
            countedAsGeographicValidation=False, qualification=phase["detail"], locationCodes=phase["locationCodes"])]
        values[1]["summary"]["unverifiedHistoricalClaimCount"] = 1
        self.assertEqual(len(verify(*values)), 1)
        next_hour = dict(values[1]["cumulativeSeries"][-1], hour="2026-10-03T19:00:00Z", label="19")
        values[1]["cumulativeSeries"].append(next_hour)
        self.assertEqual(len(verify(*values)), 1)
        next_hour["cumulativeValidatedLocationCount"] = 2
        with self.assertRaises(ValueError):
            verify(*values)

    def test_missing_task_or_changed_clock_rejected(self):
        values = self.fixture()
        values[1]["hours"] = []
        with self.assertRaises(ValueError):
            verify(*values)
        values = self.fixture()
        values[1]["hours"][0]["locationGroups"][0]["tasks"][0]["endedAtUtc"] = "2026-10-03T18:30:05Z"
        with self.assertRaises(ValueError):
            verify(*values)

    def test_map_history_credit_and_issue_changes_rejected(self):
        for mutation in (lambda v: v[3]["locations"][0].update(geometry=[2, 1]),
                         lambda v: v[1]["validations"][0].update(id="changed"),
                         lambda v: v[1]["summary"].update(uniqueValidatedLocations=2),
                         lambda v: v[1]["summary"].update(reportingIssues=["hold"])):
            values = self.fixture()
            mutation(values)
            with self.assertRaises(ValueError):
                verify(*values)


if __name__ == "__main__":
    unittest.main()
