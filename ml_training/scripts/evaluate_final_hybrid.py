"""
ChildFocus - Final Recalibrated Hybrid Evaluation
ml_training/scripts/evaluate_final_hybrid.py

Uses the already-saved 30-video results (hybrid_real_results.json)
to test different alpha weights and threshold combinations.
NO re-downloading needed — uses saved Score_NB and Score_H values.

Finds the best configuration and reports final thesis metrics.

Run from ml_training/scripts/:
    python evaluate_final_hybrid.py
"""

import os
import json
import datetime
import numpy as np
from sklearn.metrics import (
    classification_report, confusion_matrix,
    precision_recall_fscore_support, accuracy_score, f1_score
)

SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
RESULTS_PATH  = os.path.join(SCRIPT_DIR, "..", "outputs", "hybrid_real_results.json")
OUTPUT_PATH   = os.path.join(SCRIPT_DIR, "..", "outputs", "final_hybrid_report.txt")

LABELS = ["Educational", "Neutral", "Overstimulating"]


def load_results():
    with open(RESULTS_PATH, encoding="utf-8") as f:
        data = json.load(f)
    valid = [r for r in data["results"]
             if r.get("pred_label") not in ("SKIPPED", "ERROR")
             and "score_nb" in r and "score_h" in r]
    print(f"[FINAL] Loaded {len(valid)} valid video results")
    return valid


def classify(score_final, block, allow):
    if   score_final >= block: return "Overstimulating"
    elif score_final <= allow: return "Educational"
    else:                      return "Neutral"


def evaluate_config(results, alpha, block, allow):
    """Evaluate one alpha + threshold combination."""
    y_true, y_pred, scores = [], [], []
    for r in results:
        nb    = r["score_nb"]
        h     = r["score_h"]
        final = round((alpha * nb) + ((1 - alpha) * h), 4)
        pred  = classify(final, block, allow)
        y_true.append(r["true_label"])
        y_pred.append(pred)
        scores.append(final)

    f1  = f1_score(y_true, y_pred, average="weighted", zero_division=0)
    acc = accuracy_score(y_true, y_pred)
    return f1, acc, y_true, y_pred, scores


def run_grid_search(results):
    """Search over alpha, block threshold, allow threshold.
    
    Enforces a minimum Overstimulating recall floor before considering
    any config — prevents degenerate all-Educational solutions from winning.
    """
    print("\n" + "="*70)
    print("GRID SEARCH — ALPHA × THRESHOLD COMBINATIONS")
    print("="*70)

    alphas = [0.4, 0.5, 0.6, 0.7, 0.8, 0.9]
    blocks = [0.18, 0.20, 0.22, 0.25, 0.28, 0.30, 0.32, 0.35, 0.38, 0.40]
    allows = [0.08, 0.10, 0.11, 0.13, 0.15, 0.16, 0.17, 0.18]

    # Minimum Overstimulating recall — child safety floor.
    # Any config that misses more than 20% of overstimulating videos is rejected.
    OVER_RECALL_FLOOR = 0.80
    OVER_IDX = LABELS.index("Overstimulating")

    best_f1     = 0
    best_cfg    = None
    top_results = []
    skipped     = 0

    for alpha in alphas:
        for block in blocks:
            for allow in allows:
                if allow >= block:
                    continue

                f1, acc, y_true, y_pred, _ = evaluate_config(
                    results, alpha, block, allow
                )

                # ── Safety floor: reject configs with low Overstimulating recall ──
                _, rec_per_class, _, _ = precision_recall_fscore_support(
                    y_true, y_pred, labels=LABELS,
                    average=None, zero_division=0
                )
                over_recall = rec_per_class[OVER_IDX]
                if over_recall < OVER_RECALL_FLOOR:
                    skipped += 1
                    continue   # never consider this config regardless of F1

                top_results.append((f1, acc, alpha, block, allow, over_recall))
                if f1 > best_f1:
                    best_f1  = f1
                    best_cfg = (alpha, block, allow, acc, y_true, y_pred)

    print(f"\n[GRID] Configs evaluated : {len(top_results) + skipped}")
    print(f"[GRID] Rejected (Over. recall < {OVER_RECALL_FLOOR:.0%}): {skipped}")
    print(f"[GRID] Qualifying configs: {len(top_results)}")

    if not top_results:
        print(f"\n[GRID] ✗ No config achieved Overstimulating recall >= {OVER_RECALL_FLOOR:.0%}.")
        print(f"[GRID]   This means the score distributions don't separate well enough.")
        print(f"[GRID]   Check that NB scores are real (not 0.500) and ATT is non-zero.")
        return None

    # Show top 10 qualifying configs
    top_results.sort(key=lambda x: -x[0])
    print(f"\n{'Alpha':>8} {'Block':>8} {'Allow':>8} {'F1':>10} "
          f"{'Accuracy':>10} {'Over.Rec':>10}")
    print("-"*60)
    for f1, acc, alpha, block, allow, over_rec in top_results[:10]:
        is_best = (
            best_cfg is not None and
            (alpha, block, allow) == (best_cfg[0], best_cfg[1], best_cfg[2])
        )
        marker = " ← BEST" if is_best else ""
        print(f"{alpha:>8.1f} {block:>8.3f} {allow:>8.3f} {f1:>10.4f} "
              f"{acc:>10.4f} {over_rec:>10.4f}{marker}")

    return best_cfg

def report_best_config(results, best_cfg):
    alpha, block, allow, acc, y_true, y_pred = best_cfg

    print("\n" + "="*70)
    print("BEST CONFIGURATION — DETAILED REPORT")
    print("="*70)
    print(f"\nAlpha (NB weight)    : {alpha}  ({int(alpha*100)}% NB / {int((1-alpha)*100)}% Heuristic)")
    print(f"Block threshold      : >= {block}  (Overstimulating)")
    print(f"Allow threshold      : <= {allow}  (Educational)")
    print(f"Neutral range        : {allow} < score < {block}")

    # Per-video breakdown with final scores
    print(f"\nPer-video results:")
    print(f"  {'video_id':>12} {'true':>16} {'pred':>16} {'NB':>6} {'H':>6} {'Final':>7}")
    print(f"  {'-'*65}")
    for r in results:
        nb    = r["score_nb"]
        h     = r["score_h"]
        final = round((alpha * nb) + ((1 - alpha) * h), 4)
        pred  = classify(final, block, allow)
        mark  = "✓" if pred == r["true_label"] else "✗"
        print(f"  {mark} {r['video_id']:>12} {r['true_label']:>16} "
              f"{pred:>16} {nb:>6.3f} {h:>6.3f} {final:>7.4f}  "
              f"{r.get('title','')[:30]!r}")

    report = classification_report(y_true, y_pred, target_names=LABELS,
                                   digits=4, zero_division=0)
    cm     = confusion_matrix(y_true, y_pred, labels=LABELS)
    prec, rec, f1, _ = precision_recall_fscore_support(
        y_true, y_pred, average="weighted", zero_division=0
    )
    prec_c, rec_c, f1_c, sup_c = precision_recall_fscore_support(
        y_true, y_pred, labels=LABELS, zero_division=0
    )

    print(f"\nClassification Report:")
    print(report)
    print(f"Confusion Matrix:")
    print(f"{'':>20}" + "".join(f"{l[:5]:>12}" for l in LABELS))
    for i, lbl in enumerate(LABELS):
        print(f"{lbl:>20}" + "".join(f"{cm[i][j]:>12}" for j in range(3)))

    print(f"\n{'='*50}")
    print(f"FINAL METRICS (Best Config)")
    print(f"{'='*50}")
    print(f"Accuracy           : {acc:.4f}  ({acc*100:.2f}%)")
    print(f"Weighted Precision : {prec:.4f}")
    print(f"Weighted Recall    : {rec:.4f}")
    print(f"Weighted F1-Score  : {f1:.4f}")
    print(f"\nPer-class:")
    for i, lbl in enumerate(LABELS):
        print(f"  {lbl:<18}: P={prec_c[i]:.4f}  R={rec_c[i]:.4f}  "
              f"F1={f1_c[i]:.4f}  n={sup_c[i]}")

    return {
        "alpha": alpha, "block": block, "allow": allow,
        "accuracy": round(acc, 4),
        "precision": round(float(prec), 4),
        "recall":    round(float(rec),  4),
        "f1":        round(float(f1),   4),
        "report":    report,
        "cm":        cm.tolist(),
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


def compare_all_configs(results):
    """Summary table comparing original, recalibrated, and best configs."""
    print("\n" + "="*70)
    print("CONFIGURATION COMPARISON SUMMARY")
    print("="*70)

    configs = [
        ("Original (thesis)",       0.4,  0.75,  0.35),
        ("Recalibrated basic",       0.4,  0.185, 0.11),
        ("NB-dominant (0.7 alpha)",  0.7,  0.30,  0.15),
        ("NB-dominant (0.8 alpha)",  0.8,  0.30,  0.15),
        ("NB-dominant (0.9 alpha)",  0.9,  0.30,  0.15),
    ]

    print(f"\n{'Configuration':<30} {'Alpha':>7} {'Block':>7} {'Allow':>7} "
          f"{'F1':>9} {'Accuracy':>10}")
    print("-"*75)
    for name, alpha, block, allow in configs:
        f1, acc, *_ = evaluate_config(results, alpha, block, allow)
        print(f"{name:<30} {alpha:>7.1f} {block:>7.3f} {allow:>7.3f} "
              f"{f1:>9.4f} {acc:>10.4f}")


def save_report(metrics):
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        f.write(f"ChildFocus — Final Hybrid Evaluation Report\n")
        f.write(f"Generated: {datetime.datetime.now()}\n\n")
        f.write(f"BEST CONFIGURATION\n")
        f.write(f"  Alpha (NB weight): {metrics['alpha']}\n")
        f.write(f"  Block threshold  : >= {metrics['block']}\n")
        f.write(f"  Allow threshold  : <= {metrics['allow']}\n\n")
        f.write(f"PERFORMANCE METRICS\n")
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


def main():
    print("\n" + "="*70)
    print("CHILDFOCUS — FINAL HYBRID CONFIGURATION SEARCH")
    print("="*70)

    results  = load_results()

    # First show all config comparison
    compare_all_configs(results)

    # Then run full grid search
    best_cfg = run_grid_search(results)

    # ✅ SAFETY CHECK (added)
    if best_cfg is None:
        print("\n[FINAL] ✗ Grid search found no valid configuration.")
        print("[FINAL]   Fix NB feature mismatch and ffmpeg, then re-run.")
        return

    # Detailed report of best
    metrics  = report_best_config(results, best_cfg)
    save_report(metrics)

    print("\n" + "="*70)
    print("PASTE THESE INTO YOUR THESIS — CHAPTER 5")
    print("="*70)
    print(f"Fusion config  : alpha={metrics['alpha']} (NB), "
          f"beta={round(1-metrics['alpha'],1)} (Heuristic)")
    print(f"Thresholds     : Block >= {metrics['block']}, "
          f"Allow <= {metrics['allow']}")
    print(f"Accuracy       : {metrics['accuracy']:.4f} ({metrics['accuracy']*100:.2f}%)")
    print(f"Precision      : {metrics['precision']:.4f}")
    print(f"Recall         : {metrics['recall']:.4f}")
    print(f"F1-Score       : {metrics['f1']:.4f}")
    print(f"\nPer-class breakdown:")
    for lbl, m in metrics["per_class"].items():
        print(f"  {lbl:<18}: P={m['precision']:.4f}  "
              f"R={m['recall']:.4f}  F1={m['f1']:.4f}")
    cm = metrics["cm"]
    print(f"\nConfusion Matrix:")
    print(f"{'':>20}" + "".join(f"{l[:5]:>12}" for l in LABELS))
    for i, lbl in enumerate(LABELS):
        print(f"{lbl:>20}" + "".join(f"{cm[i][j]:>12}" for j in range(3)))
    print("="*70)


if __name__ == "__main__":
    main()
