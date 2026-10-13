import json
import unittest
from export_screening_evidence import validate

class ExportTests(unittest.TestCase):
    def row(self):
        event = "11111111-1111-4111-8111-111111111111"
        return dict(schemaVersion=1, eventId=event, receivedAtMs=100, respondedAtMs=102,
            latencyMs=2, receivedWallTimeMs=1700000000000, pid=42, api=36,
            versionName="0.1.0-wp01-diag1", versionCode=1, direction="INCOMING", decision="ALLOW",
            responseStatus="respondToCall_returned",
            receivedLog=f"received eventId={event} api=36 receivedAtMs=100 direction=0",
            respondedLog=f"responded eventId={event} decision=ALLOW respondedAtMs=102 latencyMs=2")
    def check(self, row):
        return validate(json.dumps(row).encode(), self.row()["eventId"] + ".json")
    def test_valid_record_round_trip(self):
        self.assertEqual(self.row(), self.check(self.row()))
    def test_wp02_version_round_trip(self):
        row=self.row(); row.update(versionName="0.2.0-wp02", versionCode=2)
        self.assertEqual(row, self.check(row))
    def test_wp03_version_round_trip(self):
        row=self.row(); row.update(versionName="0.3.0-wp03", versionCode=3)
        self.assertEqual(row, self.check(row))
    def policy_row(self, decision="BLOCK"):
        row=self.row()
        row.update(schemaVersion=2,versionName="0.4.0-wp04",versionCode=4,decision=decision,
            fallbackUsed=False,policyVersion="wp03-local-v1",matchedRules=["BLOCK_RULE"],riskScore=None)
        row["respondedLog"]=row["respondedLog"].replace("decision=ALLOW",f"decision={decision}")
        return row
    def test_wp05_version_round_trip(self):
        row=self.policy_row();row.update(versionName="0.5.0-wp05",versionCode=5)
        self.assertEqual(row,self.check(row))
    def test_wp06_feedback_reason_round_trip(self):
        row=self.policy_row("SILENCE");row.update(versionName="0.6.0-wp06",versionCode=6,matchedRules=["USER_HARASSMENT"],riskScore=67)
        self.assertEqual(row,self.check(row))
    def test_wp07_cloud_fallback_round_trip(self):
        row=self.policy_row("SILENCE");row.update(versionName="0.7.0-wp07",versionCode=7,matchedRules=["USER_HARASSMENT","CLOUD_TIMEOUT"],fallbackUsed=True,riskScore=65)
        self.assertEqual(row,self.check(row))
    def test_wp08_local_evidence_round_trip(self):
        row=self.policy_row();row.update(versionName="0.8.0-wp08",versionCode=8)
        self.assertEqual(row,self.check(row))
    def test_wp04_all_decisions(self):
        for decision in ("ALLOW","SILENCE","BLOCK"):
            row=self.policy_row(decision);self.assertEqual(row,self.check(row))
    def test_policy_rejects_raw_reason_or_bad_score(self):
        for changes in ({"matchedRules":["phone=123"]},{"riskScore":True},{"riskScore":101},{"fallbackUsed":"false"}):
            row=self.policy_row();row.update(changes)
            with self.assertRaises(ValueError):self.check(row)
    def test_legacy_cannot_claim_block(self):
        row=self.row();row["decision"]="BLOCK"
        with self.assertRaises(ValueError):self.check(row)
    def test_rejects_inconsistent_elapsed_time(self):
        row=self.row();row["latencyMs"]=9
        with self.assertRaises(ValueError):self.check(row)
    def test_rejects_unexpected_private_field(self):
        row=self.row();row["phoneNumber"]="13812345678"
        with self.assertRaises(ValueError):self.check(row)
    def test_rejects_extra_log_payload(self):
        row=self.row();row["receivedLog"]+=" phone=13812345678"
        with self.assertRaises(ValueError):self.check(row)
    def test_rejects_wrong_event_file(self):
        with self.assertRaises(ValueError):validate(json.dumps(self.row()).encode(), "../../wrong.json")

if __name__ == "__main__":unittest.main()
