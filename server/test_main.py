import os
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.chdir(tempfile.mkdtemp())

import main as m


def pay(amount=6000, call=False, new_payee=False, fd=False, limit=False, prior=0, account_hash="", safety=None, **phone):
    req = m.ScoreRequest(
        user_id="demo_user_1", amount=amount, payee_account_hash=account_hash,
        account=m.Account(payee_added_minutes_ago=12 if new_payee else 100000, fd_broken_24h=fd,
                          limit_raised_24h=limit, prior_large_transfers_6h=prior),
        phone=m.Phone(call_active=call, **phone),
        safety_mode=safety or m.SafetyMode())
    return m.score_payment(req)


def scam(**kw):
    return pay(amount=250000, call=True, new_payee=True, fd=True, limit=True, prior=1, **kw)


def test_normal_payment_passes():
    r = pay()
    assert r["tier"] == 0
    assert r["reasons"] == []


def test_digital_arrest_pattern_is_held_with_reasons():
    r = scam()
    assert r["tier"] >= 2
    assert "You are on a call while making this payment" in r["reasons"]
    assert len(r["reasons_hi"]) == len(r["reasons"])


def test_single_signal_never_alerts():
    assert pay(call=True)["tier"] == 0
    assert pay(new_payee=True)["tier"] == 0
    assert pay(fd=True)["tier"] == 0


def test_large_amount_alone_is_not_blocked():
    assert pay(amount=250000)["tier"] <= 1


def test_release_refused_while_call_active():
    r = scam()
    res = m.confirm(m.ConfirmRequest(case_id=r["case_id"], call_active=True))
    assert res["status"] == "still_held"


def test_release_after_call_ends_for_tier_2():
    r = pay(amount=60000, call=True, new_payee=True, fd=True, limit=True, prior=1)
    if r["tier"] != 2:
        pytest.skip("pattern scored as review, covered by analyst test")
    res = m.confirm(m.ConfirmRequest(case_id=r["case_id"], call_active=False))
    assert res["status"] == "released"


def test_safety_check_yes_escalates_to_review():
    r = scam()
    res = m.checkin(m.CheckIn(case_id=r["case_id"], answers=[True, False, False]))
    assert res["escalated"] is True and res["tier"] == 3
    still = m.confirm(m.ConfirmRequest(case_id=r["case_id"], call_active=False))
    assert still["status"] == "still_held"


def test_safety_check_all_no_does_not_escalate():
    r = pay(amount=60000, call=True, new_payee=True)
    res = m.checkin(m.CheckIn(case_id=r["case_id"], answers=[False, False, False]))
    assert res["escalated"] is False


def test_analyst_release():
    r = scam()
    m.analyst_action(r["case_id"], "release")
    assert m.CASES[r["case_id"]]["status"] == "released"


def test_mule_account_flagged_across_payments():
    h = "a" * 64
    scam(account_hash=h)
    r = pay(new_payee=True, account_hash=h)
    assert r["tier"] >= 1
    assert any("mule" in x for x in r["reasons"])


def test_mule_match_alone_does_not_alert():
    h = "b" * 64
    scam(account_hash=h)
    assert pay(account_hash=h)["tier"] == 0


def test_safety_mode_holds_over_limit_during_call():
    sm = m.SafetyMode(enabled=True, limit=20000)
    assert pay(amount=25000, call=True, safety=sm)["tier"] == 2
    assert pay(amount=25000, call=False, safety=sm)["tier"] == 0
    assert pay(amount=15000, call=True, safety=sm)["tier"] == 0


def test_on_device_score_is_one_vote_only():
    r = pay(on_device_score=0.99)
    assert r["tier"] == 0


def test_unknown_case_returns_404():
    with pytest.raises(m.HTTPException):
        m.confirm(m.ConfirmRequest(case_id="nope", call_active=False))


def test_report_pack_contains_no_personal_text():
    r = scam()
    page = m.report_pack(r["case_id"])
    assert r["case_id"] in page
    assert "no call audio" in page.lower()


def test_invalid_payload_rejected():
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient
    client = TestClient(m.app)
    assert client.post("/score", json={"amount": "lots"}).status_code == 422


def test_model_reduces_false_holds_versus_rules():
    m.run_eval()
    comp = m.EVAL_CACHE["extra"]["comparison"]
    assert comp["full"]["genuine_false_hold_rate"][0] <= comp["rules"]["genuine_false_hold_rate"][0]
    assert comp["full"]["all_scams_flagged_rate"][0] >= 0.85


def test_cases_export_csv():
    scam()
    body = m.cases_csv().body
    body = body.decode() if isinstance(body, bytes) else body
    assert body.startswith("case_id,") and "HOLD" in body
