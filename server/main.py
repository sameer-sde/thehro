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
# Personal baseline: each user's normal pace, learned from their ordinary (passed) payments.
BASELINE_FILE = "baselines.json"
BASELINES: dict[str, list] = json.load(open(BASELINE_FILE)) if os.path.exists(BASELINE_FILE) else {}
BASELINE_MIN_SESSIONS = 3


def baseline_of(user_id: str):
    hist = BASELINES.get(user_id, [])
    if len(hist) < BASELINE_MIN_SESSIONS:
        return None
    med = lambda k: float(np.median([h[k] for h in hist]))
    return {"sessions": len(hist), "pause": med("pause"), "bursts": med("bursts"),
            "switches": med("switches"), "session": med("session")}


def update_baseline(user_id: str, p: Phone):
    hist = BASELINES.setdefault(user_id, [])
    hist.append({"pause": p.avg_field_pause_ms, "bursts": p.account_number_bursts,
                 "switches": p.app_switches, "session": p.session_seconds})
    del hist[:-20]                                  # keep the last 20 normal sessions
    with open(BASELINE_FILE, "w") as f:
        json.dump(BASELINES, f)


def phone_pressure(p: Phone, base=None) -> bool:
    """Behaviour signal: 2+ of these in one session. Uses the user's own baseline when known."""
    if base:
        hits = [p.avg_field_pause_ms > max(2 * base["pause"], 1500),
                p.account_number_pasted or p.account_number_bursts >= base["bursts"] + 2,
                p.app_switches >= base["switches"] + 2,
                p.session_seconds > max(2 * base["session"], 120)]
    else:                                           # new user: fixed limits until 3 normal payments
        hits = [p.avg_field_pause_ms > 3000,
                p.account_number_pasted or p.account_number_bursts >= 3,
                p.app_switches >= 2,
                p.session_seconds > 300]
    return sum(hits) >= 2


def rule_part(req: ScoreRequest, use_baseline: bool = True):
    """Rule signals and the model's feature vector for one payment."""
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
    base = baseline_of(req.user_id) if use_baseline else None
    if phone_pressure(p, base):
        signals.append((2, "Much slower and more distracted than your usual payments" if base
                        else "Unusual hesitation and app switching during this payment"))

    x = [ratio, new_payee, p.call_active, a.fd_broken_24h, a.prior_large_transfers_6h, a.limit_raised_24h, share]
    return sum(sg[0] for sg in signals), len(signals), [sg[1] for sg in signals], x, user


def decide(points: int, n: int, prob: float, amount: float) -> int:
    # Rule guard: no single signal can raise an alert.
    tier = 0
    if points >= 5 and n >= 2:
        tier = 1
    if points >= 8 and n >= 3:
        tier = 2
    # Model refines within the guard.
    if tier == 2 and prob < 0.05:
        tier = 1                                   # looks like a genuine large payment
    if tier == 2 and prob >= 0.6 and amount >= 100_000:
        tier = 3                                   # highest risk and amount -> human review
    return tier


def score(req: ScoreRequest):
    points, n, reasons, x, user = rule_part(req)
    prob = float(MODEL.predict_proba(np.array([x], dtype=float))[0, 1])
    tier = decide(points, n, prob, req.amount)
    return tier, prob, reasons if tier > 0 else [], user


def score_many(reqs):
    """Batch version used by the evaluation (same logic as score, fixed limits so results are repeatable)."""
    parts = [rule_part(r, use_baseline=False) for r in reqs]
    probs = MODEL.predict_proba(np.array([pt[3] for pt in parts], dtype=float))[:, 1]
    out = []
    for r, (points, n, reasons, x, user), pr in zip(reqs, parts, probs):
        t = decide(points, n, float(pr), r.amount)
        out.append((t, float(pr), reasons if t > 0 else []))
    return out


@app.get("/")
def health():
    return {"status": "ok", "cases": len(CASES)}


@app.post("/score")
def score_payment(req: ScoreRequest):
    tier, prob, reasons, user = score(req)
    case_id = "c_" + uuid.uuid4().hex[:6]
    if req.study.volunteer:
        log_study(req, tier)
    elif tier == 0:
        update_baseline(req.user_id, req.phone)     # only ordinary payments teach the baseline
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


@app.get("/baseline/{user_id}")
def get_baseline(user_id: str):
    base = baseline_of(user_id)
    return {"user_id": user_id, "normal_sessions": len(BASELINES.get(user_id, [])),
            "needed": BASELINE_MIN_SESSIONS, "baseline": base}


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


# ------------------------------------------------------------ evaluation
# Fresh synthetic test set, generated with a different seed from training (never seen by the model).
# Phone-behaviour distributions are our assumptions until the volunteer study measures them.
def make_test_set(seed=2026, n_normal=20_000, n_life=300, n_scam=300, n_adaptive=75):
    rng = np.random.default_rng(seed)
    usual = DEMO_USERS["demo_user_1"]["usual_amount"]
    rows = []   # (kind, label, ScoreRequest)

    def req(ratio, new_p, fd, limit, prior, call, sess, pause, pasted, bursts, switches):
        return ScoreRequest(
            user_id="demo_user_1", amount=float(round(ratio * usual)),
            account=Account(payee_added_minutes_ago=int(rng.integers(5, 600)) if new_p else 100_000,
                            fd_broken_24h=bool(fd), limit_raised_24h=bool(limit),
                            prior_large_transfers_6h=int(prior)),
            phone=Phone(call_active=bool(call), session_seconds=int(max(sess, 5)),
                        avg_field_pause_ms=int(max(pause, 0)), account_number_pasted=bool(pasted),
                        account_number_bursts=int(bursts), app_switches=int(switches)))

    for _ in range(n_normal):
        rows.append(("normal", 0, req(np.exp(rng.normal(0, 0.8)), rng.random() < 0.12, rng.random() < 0.002,
                                      rng.random() < 0.003, 0, rng.random() < 0.04, rng.lognormal(np.log(60), 0.5),
                                      rng.normal(1200, 500), rng.random() < 0.3, rng.integers(1, 3),
                                      rng.poisson(0.3))))
    for i in range(n_life):
        rows.append(("genuine_large", 0, req(np.exp(rng.normal(np.log(40), 0.7)), rng.random() < 0.7,
                                             rng.random() < 0.4, rng.random() < 0.3,
                                             (i % 3) if rng.random() < 0.3 else 0, rng.random() < 0.15,
                                             rng.lognormal(np.log(120), 0.5), rng.normal(1800, 600),
                                             rng.random() < 0.4, rng.integers(1, 4), rng.poisson(0.6))))
    for _ in range(n_scam):
        call = rng.random() < 0.65
        rows.append(("scam" if call else "scam_call_hidden", 1,
                     req(np.exp(rng.normal(np.log(40), 0.8)), rng.random() < 0.95, rng.random() < 0.55,
                         rng.random() < 0.35, rng.integers(0, 6), call,
                         rng.lognormal(np.log(300 if call else 200), 0.4), rng.normal(3500 if call else 2500, 1200),
                         rng.random() < 0.3, rng.integers(2, 6), rng.poisson(2.5 if call else 1.5))))
    for _ in range(n_adaptive):
        rows.append(("scam_adaptive", 1, req(np.exp(rng.normal(np.log(6), 0.4)), rng.random() < 0.95,
                                             rng.random() < 0.55, rng.random() < 0.35, 0, False,
                                             rng.lognormal(np.log(150), 0.4), rng.normal(2200, 800),
                                             rng.random() < 0.3, rng.integers(1, 4), rng.poisson(1.0))))
    return rows


def metrics(rows, results):
    def rate(sel, cond):
        idx = [i for i, r in enumerate(rows) if sel(r)]
        return (round(sum(cond(results[i]) for i in idx) / len(idx), 4), len(idx)) if idx else (None, 0)
    flagged = lambda res: res[0] >= 1
    held = lambda res: res[0] >= 2
    scam = lambda r: r[1] == 1
    genuine = lambda r: r[1] == 0
    n_flag = sum(flagged(res) for res in results)
    tp_flag = sum(flagged(res) and r[1] == 1 for r, res in zip(rows, results))
    n_held = sum(held(res) for res in results)
    tp_held = sum(held(res) and r[1] == 1 for r, res in zip(rows, results))
    out = {
        "all_scams_flagged_rate": rate(scam, flagged),
        "scam_held_rate": rate(scam, held),
        "genuine_false_alarm_rate": rate(genuine, flagged),
        "genuine_false_hold_rate": rate(genuine, held),
        "precision_flagged": round(tp_flag / n_flag, 4) if n_flag else None,
        "precision_held": round(tp_held / n_held, 4) if n_held else None,
    }
    for kind in ("normal", "genuine_large", "scam", "scam_call_hidden", "scam_adaptive"):
        out[f"flagged_rate_{kind}"] = rate(lambda r, k=kind: r[0] == k, flagged)
    return out


EVAL_CACHE = {}


def run_eval():
    if "result" not in EVAL_CACHE:
        rows = make_test_set()
        results = score_many([r[2] for r in rows])
        missed = [(r, res) for r, res in zip(rows, results) if r[1] == 1 and res[0] == 0][:5]
        false_alarms = [(r, res) for r, res in zip(rows, results) if r[1] == 0 and res[0] >= 2][:5]
        EVAL_CACHE["result"] = (metrics(rows, results), missed, false_alarms, len(rows))
    return EVAL_CACHE["result"]


@app.get("/metrics.json")
def metrics_json():
    m, missed, fa, n = run_eval()
    return {"test_payments": n, "seed": 2026, "training_seed": 7, "metrics": m}


def describe(req: ScoreRequest) -> str:
    a, p = req.account, req.phone
    bits = [f"Rs {req.amount:,.0f}"]
    if p.call_active: bits.append("on call")
    if a.payee_added_minutes_ago < 1440: bits.append("new payee")
    if a.fd_broken_24h: bits.append("FD broken")
    if a.limit_raised_24h: bits.append("limit raised")
    if a.prior_large_transfers_6h: bits.append(f"{a.prior_large_transfers_6h} prior large")
    bits.append(f"pause {p.avg_field_pause_ms} ms, {p.app_switches} app switches")
    return ", ".join(bits)


@app.get("/metrics", response_class=HTMLResponse)
def metrics_page():
    m, missed, fa, n = run_eval()
    pct = lambda v: "–" if v is None else f"{v * 100:.1f}%"
    labels = [
        ("all_scams_flagged_rate", "Scam payments flagged (warning or hold)"),
        ("scam_held_rate", "Scam payments held"),
        ("genuine_false_alarm_rate", "Genuine payments wrongly flagged"),
        ("genuine_false_hold_rate", "Genuine payments wrongly held"),
        ("flagged_rate_genuine_large", "Genuine LARGE payments flagged (hard case)"),
        ("flagged_rate_scam_call_hidden", "Scams flagged when the call is on another device"),
        ("flagged_rate_scam_adaptive", "Scams flagged when victim is coached to pay small and slow"),
    ]
    rows = "".join(f"<tr><td>{t}</td><td>{pct(m[k][0])}</td><td>{m[k][1]:,}</td></tr>" for k, t in labels)
    rows += f'<tr><td>Precision of holds (share of holds that are real scams)</td><td>{pct(m["precision_held"])}</td><td></td></tr>'
    miss = "".join(f"<li>{html.escape(r[0])}: {html.escape(describe(r[2]))}</li>" for r, res in missed) or "<li>None</li>"
    fal = "".join(f"<li>{html.escape(r[0])}: {html.escape(describe(r[2]))} → {html.escape('; '.join(res[2]))}</li>"
                  for r, res in fa) or "<li>None</li>"
    body = (f"<h1>Thehro – measured accuracy</h1>"
            f"<p>{n:,} fresh synthetic payments (seed 2026). The model was trained on a different synthetic set "
            f"(seed 7) and never saw these. Full decision = rule guard + model.</p>"
            f"<table><tr><th>Measure</th><th>Result</th><th>Payments</th></tr>{rows}</table>"
            f"<h2>Failure cases: scams we missed</h2><ul>{miss}</ul>"
            f"<h2>Failure cases: genuine payments we held</h2><ul>{fal}</ul>"
            f"<p><b>Limits:</b> all data is synthetic and the phone-behaviour patterns are our assumptions until "
            f"the volunteer study. Precision on real data would be lower because real scams are rarer.</p>"
            f"<p>Batch test: POST a list of cases to <code>/evaluate</code> (see <a href='/docs'>/docs</a>).</p>")
    return PAGE.format(title="Measured accuracy", body=body).replace('<meta http-equiv="refresh" content="5">', "")


class LabelledCase(BaseModel):
    case: ScoreRequest
    label: int | None = None     # 1 = scam, 0 = genuine (optional)
    name: str = ""


@app.post("/evaluate")
def evaluate(cases: list[LabelledCase]):
    """Score a batch of test cases (e.g. the jury's kit). If labels are given, also returns metrics."""
    results = score_many([c.case for c in cases])
    preds = [{"name": c.name, "label": c.label, "tier": t, "decision": LABELS[t], "score": round(pr, 3),
              "reasons": rs} for c, (t, pr, rs) in zip(cases, results)]
    labelled = [(c, r) for c, r in zip(cases, results) if c.label is not None]
    summary = None
    if labelled:
        rows = [(c.name or "case", c.label, c.case) for c, _ in labelled]
        summary = metrics(rows, [r for _, r in labelled])
    return {"predictions": preds, "metrics": summary}
