"""
Thehro scoring server (Team ThreatForge, RAKSHAM finale).

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
Docs: http://localhost:8000/docs     Analyst: /analyst     Guardian: /guardian/<case_id>

All data is synthetic. No real bank, customer or call data is used.
"""
import csv
import html
import json
import os
import time
import uuid

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel
from sklearn.ensemble import HistGradientBoostingClassifier

app = FastAPI(title="Thehro scoring server")

# ---------------------------------------------------------------- demo users
DEMO_USERS = {
    "demo_user_1": {"name": "Ramesh", "usual_amount": 6000, "balance": 2_500_000, "guardian": "Priya (daughter)"},
    "demo_user_2": {"name": "Anita", "usual_amount": 20000, "balance": 900_000, "guardian": None},
}
DEFAULT_USER = {"name": "Customer", "usual_amount": 10000, "balance": 1_000_000, "guardian": None}

# ------------------------------------------------- synthetic training data
FEATURES = ["amount_ratio", "new_payee", "call_active", "fd_broken_24h",
            "prior_large_6h", "limit_raised_24h", "balance_share"]


def synth(rng, n_normal=200_000, n_life=3_000, n_scam=1_500):
    """Synthetic payments: normal, genuine large (hard negatives), digital-arrest scams."""
    def block(n, ratio, new_p, call_p, fd_p, prior, limit_p, share, label):
        return np.column_stack([ratio, rng.random(n) < new_p, rng.random(n) < call_p, rng.random(n) < fd_p,
                                prior, rng.random(n) < limit_p, share]).astype(float), np.full(n, label)

    Xn, yn = block(n_normal, np.exp(rng.normal(0, 0.8, n_normal)), 0.12, 0.04, 0.002,
                   np.zeros(n_normal), 0.003, rng.beta(1, 12, n_normal), 0)
    Xl, yl = block(n_life, np.exp(rng.normal(np.log(40), 0.7, n_life)), 0.7, 0.15, 0.4,
                   np.where(rng.random(n_life) < 0.3, rng.integers(0, 3, n_life), 0), 0.3, rng.beta(3, 4, n_life), 0)
    adaptive = rng.random(n_scam) < 0.2
    ratio = np.where(adaptive, np.exp(rng.normal(np.log(6), 0.4, n_scam)), np.exp(rng.normal(np.log(40), 0.8, n_scam)))
    call = (rng.random(n_scam) < 0.65) & ~adaptive
    prior = np.where(adaptive, 0, rng.integers(0, 6, n_scam))
    share = np.where(adaptive, rng.beta(2, 8, n_scam), rng.beta(4, 3, n_scam))
    Xs = np.column_stack([ratio, rng.random(n_scam) < 0.95, call, rng.random(n_scam) < 0.55,
                          prior, rng.random(n_scam) < 0.35, share]).astype(float)
    ys = np.ones(n_scam)
    return np.vstack([Xn, Xl, Xs]), np.concatenate([yn, yl, ys])


X_train, y_train = synth(np.random.default_rng(7))
MODEL = HistGradientBoostingClassifier(max_depth=4, max_iter=200, learning_rate=0.08, random_state=0)
MODEL.fit(X_train, y_train)

# ------------------------------------------------------------- API schema
class Account(BaseModel):
    payee_added_minutes_ago: int = 100_000
    fd_broken_24h: bool = False
    limit_raised_24h: bool = False
    prior_large_transfers_6h: int = 0


class Phone(BaseModel):
    call_active: bool = False
    session_seconds: int = 0
    avg_field_pause_ms: int = 0
    account_number_pasted: bool = False
    account_number_bursts: int = 0
    app_switches: int = 0


class Study(BaseModel):
    volunteer: str = ""          # e.g. "V1"; empty = not a study run
    condition: str = "normal"    # "normal" or "pressured"


class ScoreRequest(BaseModel):
    user_id: str
    payee_id: str = "unknown"
    amount: float
    account: Account = Account()
    phone: Phone = Phone()
    study: Study = Study()


class ConfirmRequest(BaseModel):
    case_id: str
    call_active: bool


LABELS = {0: "PASS", 1: "WARN", 2: "HOLD", 3: "REVIEW"}

# Evidence log: every decision is appended to cases.jsonl and reloaded on restart.
CASES_FILE = "cases.jsonl"
STUDY_FILE = "study.csv"
STUDY_FIELDS = ["time", "volunteer", "condition", "call_active", "session_seconds", "avg_field_pause_ms",
                "account_number_pasted", "account_number_bursts", "app_switches", "tier", "label"]
CASES: dict[str, dict] = {}
if os.path.exists(CASES_FILE):
    with open(CASES_FILE) as f:
        for line in f:
            if line.strip():
                c = json.loads(line)
                CASES[c["case_id"]] = c


def save_case(case: dict):
    """Append the latest state of a case (later lines win on reload)."""
    CASES[case["case_id"]] = case
    with open(CASES_FILE, "a") as f:
        f.write(json.dumps(case) + "\n")


def log_study(req, tier: int):
    new = not os.path.exists(STUDY_FILE)
    with open(STUDY_FILE, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=STUDY_FIELDS)
        if new:
            w.writeheader()
        p = req.phone
        w.writerow({"time": time.strftime("%H:%M:%S"), "volunteer": req.study.volunteer,
                    "condition": req.study.condition, "call_active": p.call_active,
                    "session_seconds": p.session_seconds, "avg_field_pause_ms": p.avg_field_pause_ms,
                    "account_number_pasted": p.account_number_pasted,
                    "account_number_bursts": p.account_number_bursts, "app_switches": p.app_switches,
                    "tier": tier, "label": LABELS[tier]})


# ------------------------------------------------------------------ scoring
def phone_pressure(p: Phone) -> bool:
    """Behaviour signal: 2+ of these in one session (heuristic until the on-device model is trained)."""
    hits = [p.avg_field_pause_ms > 3000,
            p.account_number_pasted or p.account_number_bursts >= 3,
            p.app_switches >= 2,
            p.session_seconds > 300]
    return sum(hits) >= 2


def score(req: ScoreRequest):
    user = DEMO_USERS.get(req.user_id, DEFAULT_USER)
    ratio = req.amount / max(user["usual_amount"], 1)
    share = req.amount / max(user["balance"], 1)
    a, p = req.account, req.phone
    new_payee = a.payee_added_minutes_ago < 24 * 60

    signals = []   # (points, reason shown to the customer)
    if p.call_active:
        signals.append((2, "You are on a call while making this payment"))
    if new_payee:
        signals.append((2, f"New payee added {a.payee_added_minutes_ago} minutes ago"))
    if ratio >= 10:
        signals.append((2, f"Amount is {ratio:.0f}x your usual"))
    elif ratio >= 5:
        signals.append((1, f"Amount is {ratio:.0f}x your usual"))
    if a.fd_broken_24h:
        signals.append((3, "Fixed deposit broken today"))
    if a.prior_large_transfers_6h >= 1:
        signals.append((2, "Another large transfer in the last 6 hours"))
    if a.limit_raised_24h:
        signals.append((2, "Transfer limit raised today"))
    if share >= 0.5:
        signals.append((2, "This sends more than half your balance"))
    if phone_pressure(p):
        signals.append((2, "Unusual hesitation and app switching during this payment"))

    points, n = sum(s[0] for s in signals), len(signals)
    x = np.array([[ratio, new_payee, p.call_active, a.fd_broken_24h,
                   a.prior_large_transfers_6h, a.limit_raised_24h, share]], dtype=float)
    prob = float(MODEL.predict_proba(x)[0, 1])

    # Rule guard: no single signal can raise an alert.
    tier = 0
    if points >= 5 and n >= 2:
        tier = 1
    if points >= 8 and n >= 3:
        tier = 2
    # Model refines within the guard.
    if tier == 2 and prob < 0.05:
        tier = 1                                   # looks like a genuine large payment
    if tier == 2 and prob >= 0.6 and req.amount >= 100_000:
        tier = 3                                   # highest risk and amount -> human review
    return tier, prob, [s[1] for s in signals] if tier > 0 else [], user


@app.get("/")
def health():
    return {"status": "ok", "cases": len(CASES)}


@app.post("/score")
def score_payment(req: ScoreRequest):
    tier, prob, reasons, user = score(req)
    case_id = "c_" + uuid.uuid4().hex[:6]
    if req.study.volunteer:
        log_study(req, tier)
    save_case({
        "case_id": case_id, "time": time.strftime("%H:%M:%S"), "user_id": req.user_id,
        "user_name": user["name"], "guardian": user["guardian"], "payee": req.payee_id,
        "amount": req.amount, "tier": tier, "label": LABELS[tier], "score": round(prob, 3),
        "reasons": reasons, "call_active": req.phone.call_active,
        "status": {0: "sent", 1: "warned", 2: "held", 3: "awaiting review"}[tier],
        "study": req.study.volunteer or None,
    })
    return {"case_id": case_id, "tier": tier, "label": LABELS[tier], "score": round(prob, 3),
            "reasons": reasons, "hold_minutes": 240 if tier >= 2 else 0}


@app.post("/confirm")
def confirm(req: ConfirmRequest):
    case = CASES.get(req.case_id)
    if not case:
        raise HTTPException(404, "Unknown case")
    if req.call_active:
        return {"status": "still_held", "reason": "A call is still active. End the call, then confirm again."}
    if case["tier"] == 3 and case["status"] != "released":
        return {"status": "still_held", "reason": "Waiting for a bank officer to review this payment."}
    # Demo shortcut: the 4-hour cooling period is skipped so the flow can be shown live.
    case["status"] = "released"
    save_case(case)
    return {"status": "released"}


@app.get("/cases")
def cases():
    return list(CASES.values())


@app.get("/study/summary")
def study_summary():
    """Average phone signals per condition, for the results slide."""
    if not os.path.exists(STUDY_FILE):
        return {"runs": 0}
    with open(STUDY_FILE) as f:
        rows = list(csv.DictReader(f))
    out = {"runs": len(rows), "volunteers": len({r["volunteer"] for r in rows})}
    for cond in ("normal", "pressured"):
        rs = [r for r in rows if r["condition"] == cond]
        if not rs:
            continue
        avg = lambda k: round(sum(float(r[k]) for r in rs) / len(rs), 1)
        out[cond] = {"runs": len(rs),
                     "avg_session_seconds": avg("session_seconds"),
                     "avg_field_pause_ms": avg("avg_field_pause_ms"),
                     "avg_account_bursts": avg("account_number_bursts"),
                     "avg_app_switches": avg("app_switches"),
                     "share_pasted": round(sum(r["account_number_pasted"] == "True" for r in rs) / len(rs), 2),
                     "share_held_or_warned": round(sum(int(r["tier"]) >= 1 for r in rs) / len(rs), 2)}
    return out


# ---------------------------------------------------------------- web pages
PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="5"><title>{title}</title>
<style>body{{font-family:-apple-system,Arial,sans-serif;background:#F7F5EF;color:#1B2333;margin:24px}}
h1{{color:#14213D}}table{{border-collapse:collapse;width:100%;background:#fff}}td,th{{border:1px solid #D9D3C4;padding:8px;text-align:left;vertical-align:top}}
th{{background:#14213D;color:#fff}}.t2{{color:#B0401A;font-weight:bold}}.t3{{color:#14213D;font-weight:bold}}
button{{padding:6px 10px;margin:2px}}.card{{background:#fff;border:1px solid #D9D3C4;border-radius:12px;padding:20px;max-width:520px}}</style>
</head><body>{body}</body></html>"""


@app.get("/analyst", response_class=HTMLResponse)
def analyst():
    rows = ""
    for c in reversed(list(CASES.values())):
        if c["tier"] < 2:
            continue
        reasons = "<br>".join(html.escape(r) for r in c["reasons"])
        actions = f'<a href="/guardian/{c["case_id"]}" target="_blank">Guardian view</a><br>'
        if c["status"] != "released":
            actions = (f'<form method="post" action="/analyst/{c["case_id"]}/release" style="display:inline">'
                       f'<button>Release</button></form>'
                       f'<form method="post" action="/analyst/{c["case_id"]}/keep" style="display:inline">'
                       f'<button>Keep holding</button></form>')
        rows += (f'<tr><td>{c["time"]}</td><td>{html.escape(c["user_name"])}</td><td>Rs {c["amount"]:,.0f}</td>'
                 f'<td class="t{c["tier"]}">{c["label"]}</td><td>{c["score"]}</td><td>{reasons}</td>'
                 f'<td>{c["status"]}</td><td>{actions}</td></tr>')
    body = ("<h1>Thehro – fraud analyst queue</h1><p>Held and review cases (refreshes every 5 s). Synthetic demo data.</p>"
            "<table><tr><th>Time</th><th>Customer</th><th>Amount</th><th>Tier</th><th>Model score</th>"
            "<th>Reasons</th><th>Status</th><th>Action</th></tr>" + (rows or '<tr><td colspan="8">No held cases</td></tr>') + "</table>")
    return PAGE.format(title="Analyst queue", body=body)


@app.post("/analyst/{case_id}/{action}")
def analyst_action(case_id: str, action: str):
    case = CASES.get(case_id)
    if not case:
        raise HTTPException(404, "Unknown case")
    case["status"] = "released" if action == "release" else "held by analyst"
    save_case(case)
    return RedirectResponse("/analyst", status_code=303)


@app.get("/guardian/{case_id}", response_class=HTMLResponse)
def guardian(case_id: str):
    c = CASES.get(case_id)
    if not c:
        raise HTTPException(404, "Unknown case")
    who = html.escape(c["user_name"])
    body = (f'<div class="card"><h1>Safety alert</h1>'
            f'<p><b>{who}</b> is trying to make an unusual payment while on an active phone call. '
            f'Their bank has paused it for safety.</p>'
            f'<p>Please call {who} directly now. Scammers often pose as police and ask victims to stay on the call.</p>'
            f'<p style="color:#4A5468">You can only see this alert. You cannot see amounts or move money.</p></div>')
    return PAGE.format(title="Guardian alert", body=body)
