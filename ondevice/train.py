import sys, numpy as np
import os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'server'))

import main as m
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score

def feats(p):
    return [np.log1p(p.avg_field_pause_ms), p.account_number_bursts, p.app_switches,
            np.log1p(p.session_seconds), float(p.account_number_pasted)]

tr = m.make_test_set(seed=11); te = m.make_test_set(seed=2026)
X = np.array([feats(r[2].phone) for r in tr]); y = np.array([r[1] for r in tr])
Xt = np.array([feats(r[2].phone) for r in te]); yt = np.array([r[1] for r in te])
clf = LogisticRegression(class_weight='balanced', max_iter=2000).fit(X, y)
pt = clf.predict_proba(Xt)[:, 1]
print('weights', [round(w, 4) for w in clf.coef_[0]], 'bias', round(clf.intercept_[0], 4))
print('test AUC', round(roc_auc_score(yt, pt), 3))
for th in (0.5, 0.7, 0.8):
    print(th, 'scam caught', round((pt[yt == 1] >= th).mean(), 3), 'genuine flagged', round((pt[yt == 0] >= th).mean(), 4))

for name, f in [('normal avg', [4066, 1.5, 0, 67.5, 0]), ('pressured avg', [20379, 3, 2, 169.5, 0])]:
    v = [np.log1p(f[0]), f[1], f[2], np.log1p(f[3]), f[4]]
    print(name, round(clf.predict_proba([v])[0, 1], 3))
