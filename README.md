# Thehro – coerced-payer detection for digital arrest scams

**Team ThreatForge · RAKSHAM AI Cybersecurity Hackathon (IIT Delhi) · Track 02: AI-Driven Scam Pattern Recognition**

Sameer Ahmed (team lead, Osmania University) · Bhargab Das · Bhukya Narender (IIT Jodhpur)

> A bank-app safety layer that detects when a customer is under coercion during a digital arrest scam and holds only that payment until someone outside the call is involved.

## The problem

In a digital arrest scam, a fake police or CBI officer keeps the victim on a call (voice or video) for hours and pressures them into transferring their savings. The victim pays from their own phone with their own PIN, so the bank sees a normal, authorised payment. Existing controls look at the receiving account or delay every payment for everyone.

Thehro looks at the **payer**: is the person paying acting under pressure right now?

## How it works

```
 Customer's phone (bank app)              Bank-side scoring server               People
 ───────────────────────────              ─────────────────────────              ──────
 call active? (yes/no only)    ──┐
 pause between fields            ├──►  rule guard (no single signal alerts)  ──► Customer: warning / hold screen
 account no. typed in bursts     │     + gradient-boosted model                  Guardian: alert page (no amounts)
 or pasted, app switches       ──┘     + 24-hour account events                  Analyst: review queue, release
                                        (FD broken, new payee, amount            Evidence log (cases.jsonl)
                                         vs usual, repeat transfers)
```

**Tiers**

| Tier | When | What happens |
|---|---|---|
| T0 Pass | fewer than 2 signals | nothing, payment proceeds |
| T1 Warn | 2+ signals, medium score | one warning with plain-language reasons; user continues or cancels |
| T2 Hold | 3+ signals, high score | payment held; release only after re-confirming with **no call active**; guardian alerted |
| T3 Review | highest score and amount ≥ Rs 1,00,000 | held until a bank analyst reviews and releases |

The model can lower a T2 to a T1 when the pattern looks like a genuine large payment, and raise it to T3 for the highest-risk, largest cases.

## What is real and what is simulated

| Part | Status |
|---|---|
| Call-active detection (`AudioManager` mode: normal calls and VoIP) | Built. Emulator uses a "Simulate call" switch because it cannot place calls |
| Typing pauses, bursts, paste, app switches | Built (timings and counts only, no text) |
| Bank-side events (FD broken, new payee, limit raised, prior transfers) | **Simulated** with demo controls; no real bank connection |
| Scoring: rule guard + gradient-boosted model | Built; model trained on **synthetic** data |
| Phone "pressure" signal | Rule on fixed thresholds (2+ of: pause > 3 s, account typed in 3+ bursts or pasted, 2+ app switches, session > 5 min). The planned on-device ONNX model was cut: there is no real behaviour data to train it on yet |
| Guardian alert, analyst review, evidence log | Built (web pages served by the server) |
| 4-hour cooling period | In the design; **skipped in demo mode** so the release can be shown live |

## Measured accuracy

`GET /metrics` scores ~20,675 fresh synthetic payments (seed 2026) that the model never trained on (training seed 7), using the full decision (rules + model), and lists failure cases.

Latest run: 92.8% of scam payments flagged, 64% held; 1.1% of genuine payments flagged, 0.17% held; 87.6% of holds were real scams. Main weakness: 70.7% of genuine **large** payments (e.g. property purchase) are flagged.

**Limits:** all data is synthetic and the phone-behaviour patterns are assumptions until the volunteer study measures them. Precision on real data would be lower because real scams are rarer.

`POST /evaluate` scores a batch of labelled test cases (e.g. a jury test kit) and returns decisions, reasons and metrics.

## Privacy and safeguards

- No call audio, call logs, phone numbers, contacts, messages or typed text are read or stored. The phone sends only a call yes/no, timings and counts.
- No single signal can raise an alert. Holds are reversible; no automatic blocking or accusation.
- Every alert gives plain-language reasons, never a bare score.
- The guardian sees an alert only: no amounts, cannot move money.
- Human analyst review for the highest-risk cases.

## Run it

**Server** (Python 3.11+):

```bash
pip3 install fastapi uvicorn scikit-learn pandas numpy
cd server
python3 -m uvicorn main:app --host 0.0.0.0 --port 8000
```

Pages: `/analyst` (review queue), `/guardian/<case_id>`, `/metrics`, `/study/summary`, `/docs` (API).

**App** (Android Studio, min SDK 26): open `app/`, run on an emulator or phone, then forward the port:

```bash
adb reverse tcp:8000 tcp:8000
```

The app's server URL defaults to `http://localhost:8000`.

**Demo:** normal payment (Rs 4,000, all switches off) passes. Scam payment (Rs 2,50,000, FD broken, new payee, limit raised, simulate call, 1 prior transfer) is held. Confirming during the call is refused; the guardian page and analyst queue show the case; turning off the call and confirming releases it.

## Volunteer study

Entering a Volunteer ID in the app logs that run's timings to `server/study.csv` (no personal data). `GET /study/summary` compares normal and pressured runs.

## Disclosures

- **AI assistance:** Claude (Anthropic) was used for research, drafting and writing code during the hackathon. No other AI tool was used.
- **Pre-existing code:** the Round 1 evaluation script (synthetic data generator and rule scoring) was written before the finale and declared on the pre-event disclosure form. All product code in this repository was written on-site.
- **Data:** fully synthetic, generated by our own code. No bank, customer or platform data.
- **Libraries:** FastAPI, Uvicorn, scikit-learn, NumPy, pandas, Jetpack Compose (Android).
