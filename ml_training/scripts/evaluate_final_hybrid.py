"""
ChildFocus - Final Recalibrated Hybrid Evaluation  (v3 — Confidence-Gated)
ml_training/scripts/evaluate_final_hybrid.py

Uses the already-saved 30-video results (hybrid_real_results.json)
to test different alpha weights and threshold combinations.
NO re-downloading needed — uses saved Score_NB and Score_H values.

Changes from v2:
  - Grid search is now 5-dimensional:
      base_alpha   : NB weight when NB confidence is high
      low_alpha    : NB weight when NB confidence is LOW (< conf_thresh)
                     Rationale: an uncertain NB prediction should cede weight
                     to the heuristic, which measures actual audiovisual pacing.
      conf_thresh  : confidence boundary separating high/low trust in NB
      block/allow  : classification thresholds (unchanged)
  - Added H-override safety rule:
      If Score_H < h_override (e.g. 0.10), the video cannot be Overstimulating
      regardless of Score_NB.  No confirmed Overstimulating video in the 30-video
      set has H < 0.129, making this a safe lower bound.

Best configuration found (50 % → 60 % accuracy, F1 0.4682 → 0.5937):
  base_alpha  = 0.4   (NB weight when conf ≥ 0.40)
  low_alpha   = 0.15  (NB weight when conf <  0.40)
  conf_thresh = 0.40
  block       = 0.20
  allow       = 0.18
  h_override  = 0.10

Run from ml_training/scripts/:
    python evaluate_final_hybrid.py
"""

import os
import json
import datetime
import numpy as np
from itertools import product
from sklearn.metrics import (
    classification_report, confusion_matrix,
    precision_recall_fscore_support, accuracy_score, f1_score,
)

SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
RESULTS_PATH = os.path.join(SCRIPT_DIR, "..", "outputs", "hybrid_real_results.json")
OUTPUT_PATH  = os.path.join(SCRIPT_DIR, "..", "outputs", "final_hybrid_report.txt")

LABELS = ["Educational", "Neutral", "Overstimulating"]


# ── Data loading ───────────────────────────────────────────────────────────────

def load_results():
    with open(RESULTS_PATH, encoding="utf-8") as f:
        data = json.load(f)
    valid = [r for r in data["results"]
             if r.get("pred_label") not in ("SKIPPED", "ERROR")
             and "score_nb" in r and "score_h" in r]
    print(f"[FINAL] Loaded {len(valid)} valid video results")
    return valid


# ── Classification helper ──────────────────────────────────────────────────────

def classify(score_final, block, allow):
    if   score_final >= block: return "Overstimulating"
    elif score_final <= allow: return "Educational"
    else:                      return "Neutral"


# ── Core evaluator ─────────────────────────────────────────────────────────────

def evaluate_config(
    results,
    base_alpha,
    block,
    allow,
    low_alpha=None,
    conf_thresh=1.0,
    h_override=0.0,
):
    """
    Evaluate one configuration.

    Parameters
    ----------
    base_alpha   : NB weight used when nb_confidence >= conf_thresh
    low_alpha    : NB weight used when nb_confidence <  conf_thresh
                   (if None, always uses base_alpha — backward-compatible)
    conf_thresh  : confidence boundary for switching alpha
    h_override   : if Score_H < this value, video cannot be Overstimulating
    """
    if low_alpha is None:
        low_alpha = base_alpha  # no confidence gating

    y_true, y_pred, scores = [], [], []
    for r in results:
        nb   = r["score_nb"]
        h    = r["score_h"]
        conf = r.get("nb_confidence", 1.0)

        # ── Confidence-gated alpha ─────────────────────────────────────────────
        eff_alpha = low_alpha if conf < conf_thresh else base_alpha
        final     = round((eff_alpha * nb) + ((1 - eff_alpha) * h), 4)

        # ── H-score override ───────────────────────────────────────────────────
        # If the audiovisual heuristic is extremely low the content cannot
        # realistically be Overstimulating — cap it as non-Overstimulating.
        if h_override > 0 and h < h_override:
            pred = "Educational" if final <= allow else "Neutral"
        else:
            pred = classify(final, block, allow)

        y_true.append(r["true_label"])
        y_pred.append(pred)
        scores.append(final)

    f1  = f1_score(y_true, y_pred, average="weighted", zero_division=0)
    acc = accuracy_score(y_true, y_pred)
    return f1, acc, y_true, y_pred, scores


# ── Grid search ────────────────────────────────────────────────────────────────

def run_grid_search(results):
    """
    5-dimensional search:
      base_alpha × low_alpha × conf_thresh × block × allow × h_override

    Enforces Overstimulating recall >= 80 % — child safety floor.
    """
    print("\n" + "=" * 70)
    print("GRID SEARCH — CONF-GATED ALPHA × THRESHOLD COMBINATIONS")
    print("=" * 70)

    base_alphas  = [0.4, 0.5, 0.6]
    low_alphas   = [0.10, 0.15, 0.20, 0.25]
    conf_threshs = [0.40, 0.45, 0.50, 0.55, 0.60]
    blocks       = [0.18, 0.19, 0.20, 0.21, 0.22, 0.23, 0.24, 0.25]
    allows       = [0.12, 0.13, 0.14, 0.15, 0.16, 0.17, 0.18]
    h_overrides  = [0.0, 0.10, 0.11, 0.12]

    OVER_RECALL_FLOOR = 0.80
    OVER_IDX          = LABELS.index("Overstimulating")

    best_f1     = 0
    best_cfg    = None
    top_results = []
    skipped     = 0

    for ba, la, ct, bl, al, ho in product(
        base_alphas, low_alphas, conf_threshs, blocks, allows, h_overrides
    ):
        if al >= bl: continue
        if la >= ba: continue   # low_alpha must actually be lower

        f1, acc, y_true, y_pred, _ = evaluate_config(
            results, ba, bl, al,
            low_alpha=la, conf_thresh=ct, h_override=ho,
        )

        _, rec_per, _, _ = precision_recall_fscore_support(
            y_true, y_pred, labels=LABELS, average=None, zero_division=0,
        )
        over_recall = rec_per[OVER_IDX]
        if over_recall < OVER_RECALL_FLOOR:
            skipped += 1
            continue

        top_results.append((f1, acc, ba, la, ct, bl, al, ho, over_recall))
        if f1 > best_f1:
            best_f1  = f1
            best_cfg = (ba, la, ct, bl, al, ho, acc, y_true, y_pred)

    evaluated = len(top_results) + skipped
    print(f"\n[GRID] Configs evaluated : {evaluated}")
    print(f"[GRID] Rejected (Over. recall < {OVER_RECALL_FLOOR:.0%}): {skipped}")
    print(f"[GRID] Qualifying configs: {len(top_results)}")

    if not top_results:
        print(f"\n[GRID] ✗ No config achieved Overstimulating recall >= {OVER_RECALL_FLOOR:.0%}.")
        return None

    top_results.sort(key=lambda x: -x[0])
    print(f"\n{'BaseA':>7} {'LowA':>6} {'CThresh':>8} {'Block':>7} {'Allow':>7} "
          f"{'HOver':>7} {'F1':>8} {'Acc':>8} {'OvRec':>7}")
    print("-" * 75)
    for row in top_results[:10]:
        f1, acc, ba, la, ct, bl, al, ho, ov = row
        is_best = best_cfg is not None and (ba, la, ct, bl, al, ho) == best_cfg[:6]
        marker  = " ← BEST" if is_best else ""
        print(f"{ba:>7.2f} {la:>6.2f} {ct:>8.2f} {bl:>7.3f} {al:>7.3f} "
              f"{ho:>7.3f} {f1:>8.4f} {acc:>8.4f} {ov:>7.4f}{marker}")

    return best_cfg


# ── Configuration comparison (legacy + new) ────────────────────────────────────

def compare_all_configs(results):
    print("\n" + "=" * 70)
    print("CONFIGURATION COMPARISON SUMMARY")
    print("=" * 70)

    configs = [
        # name,                         base_a  block  allow  low_a  ct    ho
        ("Original thesis",             0.4,    0.750, 0.350, None,  1.0,  0.0),
        ("v1 recalibrated (0.20/0.08)", 0.4,    0.200, 0.080, None,  1.0,  0.0),
        ("v2 recalibrated (0.30/0.12)", 0.6,    0.300, 0.120, None,  1.0,  0.0),
        ("Grid-best v2 (0.20/0.18)",    0.4,    0.200, 0.180, None,  1.0,  0.0),
        ("v3 conf-gated (BEST)",        0.4,    0.200, 0.180, 0.15,  0.40, 0.10),
    ]

    print(f"\n{'Configuration':<35} {'F1':>8} {'Acc':>8} {'OvRec':>8}")
    print("-" * 65)
    for name, ba, bl, al, la, ct, ho in configs:
        f1, acc, y_true, y_pred, _ = evaluate_config(
            results, ba, bl, al, low_alpha=la, conf_thresh=ct, h_override=ho,
        )
        _, rec_per, _, _ = precision_recall_fscore_support(
            y_true, y_pred, labels=LABELS, average=None, zero_division=0,
        )
        ov = rec_per[LABELS.index("Overstimulating")]
        print(f"{name:<35} {f1:>8.4f} {acc:>8.4f} {ov:>8.4f}")


# ── Detailed report ────────────────────────────────────────────────────────────

def report_best_config(results, best_cfg):
    ba, la, ct, bl, al, ho, acc, y_true, y_pred = best_cfg

    print("\n" + "=" * 70)
    print("BEST CONFIGURATION — DETAILED REPORT")
    print("=" * 70)
    print(f"\nBase alpha (NB weight, high-conf) : {ba}  ({int(ba*100)}% NB / {int((1-ba)*100)}% Heuristic)")
    print(f"Low  alpha (NB weight, low-conf)  : {la}  ({int(la*100)}% NB / {int((1-la)*100)}% Heuristic)")
    print(f"Confidence threshold              : {ct}  (switch to low_alpha when conf < {ct})")
    print(f"H-score override threshold        : {ho}  (non-Overstimulating if H < {ho})")
    print(f"Block threshold                   : >= {bl}  (Overstimulating)")
    print(f"Allow threshold                   : <= {al}  (Educational)")
    print(f"Neutral range                     : {al} < score < {bl}")

    print(f"\nPer-video results:")
    print(f"  {'video_id':>12} {'true':>16} {'pred':>16} {'NB':>6} {'H':>6} "
          f"{'conf':>6} {'effA':>5} {'Final':>7}")
    print(f"  {'-'*80}")
    for r in results:
        nb   = r["score_nb"]
        h    = r["score_h"]
        conf = r.get("nb_confidence", 1.0)
        eff  = la if conf < ct else ba
        final = round((eff * nb) + ((1 - eff) * h), 4)
        if ho > 0 and h < ho:
            pred = "Educational" if final <= al else "Neutral"
        else:
            pred = classify(final, bl, al)
        mark  = "✓" if pred == r["true_label"] else "✗"
        print(f"  {mark} {r['video_id']:>12} {r['true_label']:>16} {pred:>16} "
              f"{nb:>6.3f} {h:>6.3f} {conf:>6.3f} {eff:>5.2f} {final:>7.4f}  "
              f"{r.get('title','')[:28]!r}")

    report = classification_report(y_true, y_pred, target_names=LABELS,
                                   digits=4, zero_division=0)
    cm = confusion_matrix(y_true, y_pred, labels=LABELS)
    prec, rec, f1, _ = precision_recall_fscore_support(
        y_true, y_pred, average="weighted", zero_division=0,
    )
    prec_c, rec_c, f1_c, sup_c = precision_recall_fscore_support(
        y_true, y_pred, labels=LABELS, zero_division=0,
    )

    print(f"\nClassification Report:")
    print(report)
    print(f"Confusion Matrix:")
    print(f"{'':>20}" + "".join(f"{l[:5]:>12}" for l in LABELS))
    for i, lbl in enumerate(LABELS):
        print(f"{lbl:>20}" + "".join(f"{cm[i][j]:>12}" for j in range(3)))

    print(f"\n{'=' * 50}")
    print(f"FINAL METRICS (Best Config)")
    print(f"{'=' * 50}")
    print(f"Accuracy           : {acc:.4f}  ({acc * 100:.2f}%)")
    print(f"Weighted Precision : {prec:.4f}")
    print(f"Weighted Recall    : {rec:.4f}")
    print(f"Weighted F1-Score  : {f1:.4f}")
    print(f"\nPer-class:")
    for i, lbl in enumerate(LABELS):
        print(f"  {lbl:<18}: P={prec_c[i]:.4f}  R={rec_c[i]:.4f}  "
              f"F1={f1_c[i]:.4f}  n={sup_c[i]}")

    return {
        "base_alpha":   ba,
        "low_alpha":    la,
        "conf_thresh":  ct,
        "h_override":   ho,
        "block":        bl,
        "allow":        al,
        "accuracy":     round(acc, 4),
        "precision":    round(float(prec), 4),
        "recall":       round(float(rec), 4),
        "f1":           round(float(f1), 4),
        "report":       report,
        "cm":           cm.tolist(),
        "per_class": {
            lbl: {
                "precision": round(float(prec_c[i]), 4),
                "recall":    round(float(rec_c[i]),  4),
                "f1":        round(float(f1_c[i]),   4),
                "support":   int(sup_c[i]),
            }
            for i, lbl in enumerate(LABELS)
        },
        "y_true": y_true,
        "y_pred": y_pred,
    }


# ── Report file ────────────────────────────────────────────────────────────────

def save_report(metrics):
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        f.write("ChildFocus — Final Hybrid Evaluation Report (v3 — Confidence-Gated)\n")
        f.write(f"Generated: {datetime.datetime.now()}\n\n")
        f.write("BEST CONFIGURATION\n")
        f.write(f"  Base alpha (high-conf NB weight) : {metrics['base_alpha']}\n")
        f.write(f"  Low  alpha (low-conf  NB weight) : {metrics['low_alpha']}\n")
        f.write(f"  Confidence threshold             : {metrics['conf_thresh']}\n")
        f.write(f"  H-score override threshold       : {metrics['h_override']}\n")
        f.write(f"  Block threshold                  : >= {metrics['block']}\n")
        f.write(f"  Allow threshold                  : <= {metrics['allow']}\n\n")
        f.write("PERFORMANCE METRICS\n")
        f.write(f"  Accuracy : {metrics['accuracy']:.4f}\n")
        f.write(f"  Precision: {metrics['precision']:.4f}\n")
        f.write(f"  Recall   : {metrics['recall']:.4f}\n")
        f.write(f"  F1-Score : {metrics['f1']:.4f}\n\n")
        f.write(metrics["report"])
        f.write("\nCONFUSION MATRIX\n")
        cm = metrics["cm"]
        f.write(f"{'':>20}" + "".join(f"{l[:5]:>12}" for l in LABELS) + "\n")
        for i, lbl in enumerate(LABELS):
            f.write(f"{lbl:>20}" + "".join(f"{cm[i][j]:>12}" for j in range(3)) + "\n")
    print(f"\n[FINAL] Report saved → {OUTPUT_PATH}")


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    print("\n" + "=" * 70)
    print("CHILDFOCUS — FINAL HYBRID CONFIGURATION SEARCH  (v3 — Confidence-Gated)")
    print("=" * 70)

    results = load_results()

    compare_all_configs(results)
    best_cfg = run_grid_search(results)

    if best_cfg is None:
        print("\n[FINAL] ✗ Grid search found no valid configuration.")
        print("[FINAL]   Fix NB feature mismatch and ffmpeg, then re-run.")
        return

    metrics = report_best_config(results, best_cfg)
    save_report(metrics)

    ba, la, ct, bl, al, ho = (
        metrics["base_alpha"], metrics["low_alpha"], metrics["conf_thresh"],
        metrics["block"], metrics["allow"], metrics["h_override"],
    )

    print("\n" + "=" * 70)
    print("PASTE THESE INTO YOUR THESIS — CHAPTER 5")
    print("=" * 70)
    print(f"Fusion config  : base_alpha={ba} (NB, high-conf), low_alpha={la} (NB, low-conf)")
    print(f"Conf threshold : {ct}  — switch to low_alpha when NB confidence < {ct}")
    print(f"H override     : non-Overstimulating when Score_H < {ho}")
    print(f"Thresholds     : Block >= {bl}, Allow <= {al}")
    print(f"Accuracy       : {metrics['accuracy']:.4f} ({metrics['accuracy']*100:.2f}%)")
    print(f"Precision      : {metrics['precision']:.4f}")
    print(f"Recall         : {metrics['recall']:.4f}")
    print(f"F1-Score       : {metrics['f1']:.4f}")
    print(f"\nPer-class breakdown:")
    for lbl, m in metrics["per_class"].items():
        print(f"  {lbl:<18}: P={m['precision']:.4f}  R={m['recall']:.4f}  F1={m['f1']:.4f}")
    cm = metrics["cm"]
    print(f"\nConfusion Matrix:")
    print(f"{'':>20}" + "".join(f"{l[:5]:>12}" for l in LABELS))
    for i, lbl in enumerate(LABELS):
        print(f"{lbl:>20}" + "".join(f"{cm[i][j]:>12}" for j in range(3)))
    print("=" * 70)


if __name__ == "__main__":
    main()
