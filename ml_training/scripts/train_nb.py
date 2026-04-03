"""
ChildFocus - Naïve Bayes Training Script
ml_training/scripts/train_nb.py

Trains a Complement Naïve Bayes classifier on preprocessed metadata.
ComplementNB handles class imbalance better than MultinomialNB.

Pipeline:
  1. Load data/processed/train_clean.csv  (490 rows — pre-split by build_700.py)
     Load data/processed/test_clean.csv   (210 rows — pre-split by build_700.py)
     ↳ Falls back to metadata_clean.csv + internal 70/30 split if needed
  2. TF-IDF vectorization (unigrams + bigrams, max 5000 features)
     Vectorizer fitted on TRAIN only — never sees test data
  3. Train ComplementNB  (alpha=0.1)
     No oversampling needed — dataset is already balanced (~33% each class)
  4. Evaluate: Precision, Recall, F1-Score, Confusion Matrix
  5. Save nb_model.pkl bundle + vectorizer.pkl → ../outputs/
     Also auto-copies to backend/app/models/

Run AFTER preprocess.py:
    python train_nb.py
"""

import os
import csv
import pickle
import random
import shutil
from pathlib import Path

import numpy as np
from sklearn.naive_bayes import ComplementNB
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder
from sklearn.metrics import (
    classification_report,
    confusion_matrix,
    precision_recall_fscore_support,
)

# ── Paths ──────────────────────────────────────────────────────────────────────
HERE = Path(__file__).parent.resolve()

# Pre-split clean files (produced by preprocess.py from build_700.py outputs)
TRAIN_CLEAN_PATH = HERE / "data" / "processed" / "train_clean.csv"
TEST_CLEAN_PATH  = HERE / "data" / "processed" / "test_clean.csv"
# Fallback: full clean file (preprocess.py will split internally)
FULL_CLEAN_PATH  = HERE / "data" / "processed" / "metadata_clean.csv"

OUTPUTS_DIR     = HERE.parent / "outputs"
MODEL_PATH      = OUTPUTS_DIR / "nb_model.pkl"
VECTORIZER_PATH = OUTPUTS_DIR / "vectorizer.pkl"

BACKEND_MODELS_DIR = HERE.parent.parent / "backend" / "app" / "models"

# ── Reproducibility ────────────────────────────────────────────────────────────
RANDOM_STATE = 42
random.seed(RANDOM_STATE)
np.random.seed(RANDOM_STATE)

LABELS = ["Educational", "Neutral", "Overstimulating"]


def read_clean_csv(path: Path) -> tuple[list, list]:
    texts, labels = [], []
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            text  = row.get("text", "").strip()
            label = row.get("label", "").strip()
            if text and label in LABELS:
                texts.append(text)
                labels.append(label)
    return texts, labels


def print_split_info(name: str, labels: list):
    print(f"\n[TRAIN]   {name} ({len(labels)} rows):")
    for lbl in LABELS:
        cnt = labels.count(lbl)
        print(f"[TRAIN]     {lbl:<20} {cnt:>3}  ({cnt/len(labels)*100:.1f}%)")


def train():
    OUTPUTS_DIR.mkdir(parents=True, exist_ok=True)

    # ── Load train / test ──────────────────────────────────────────────────────
    print("\n[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] LOADING DATA")
    print("[TRAIN] ══════════════════════════════════════")

    if TRAIN_CLEAN_PATH.exists() and TEST_CLEAN_PATH.exists():
        # ✅ IDEAL PATH: pre-split files from build_700.py → preprocess.py
        print(f"[TRAIN] ✅ Using pre-split files from build_700.py")
        X_train, y_train = read_clean_csv(TRAIN_CLEAN_PATH)
        X_test,  y_test  = read_clean_csv(TEST_CLEAN_PATH)
        print(f"[TRAIN]    train_clean.csv  →  {len(X_train)} rows")
        print(f"[TRAIN]    test_clean.csv   →  {len(X_test)} rows")
        print_split_info("Train", y_train)
        print_split_info("Test",  y_test)

    elif FULL_CLEAN_PATH.exists():
        # ⚠ FALLBACK: split the full clean file
        print(f"[TRAIN] ⚠  Pre-split files not found — splitting metadata_clean.csv")
        print(f"[TRAIN]    For best results, run build_700.py then preprocess.py.")
        X_all, y_all = read_clean_csv(FULL_CLEAN_PATH)
        print(f"[TRAIN]    Loaded {len(X_all)} rows — applying 70/30 stratified split")
        X_train, X_test, y_train, y_test = train_test_split(
            X_all, y_all,
            test_size=0.30,
            stratify=y_all,
            random_state=RANDOM_STATE,
        )
        print_split_info("Train", y_train)
        print_split_info("Test",  y_test)

    else:
        print("[TRAIN] ✗ No processed data found.")
        print("[TRAIN]   Run: python build_700.py → python preprocess.py → python train_nb.py")
        return

    if len(X_train) < 10:
        print("[TRAIN] ✗ Not enough training data.")
        return

    # ── TF-IDF Vectorization ───────────────────────────────────────────────────
    # Fit ONLY on train — test is transformed, never seen during fitting
    print("\n[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] VECTORIZING")
    print("[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] Fitting TF-IDF on train set only (unigrams + bigrams, max 5000)...")

    vectorizer = TfidfVectorizer(
        ngram_range=(1, 2),
        max_features=5000,
        sublinear_tf=True,      # log(1 + tf) — dampens very frequent terms
        min_df=2,               # ignore terms appearing in only 1 document
        strip_accents="unicode",
    )
    X_train_vec = vectorizer.fit_transform(X_train)
    X_test_vec  = vectorizer.transform(X_test)   # transform only — no fit
    print(f"[TRAIN] Vocabulary size: {len(vectorizer.vocabulary_)}")

    # ── Train ComplementNB ─────────────────────────────────────────────────────
    # No oversampling needed — dataset is already balanced (230 / 235 / 235)
    print("\n[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] TRAINING  (ComplementNB, alpha=0.1)")
    print("[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] Dataset is pre-balanced → no oversampling needed")

    model = ComplementNB(alpha=0.1)
    model.fit(X_train_vec, y_train)
    print("[TRAIN] ✓ Model trained")

    # ── Label encoder (for bundle format expected by naive_bayes.py) ──────────
    label_encoder = LabelEncoder()
    label_encoder.fit(LABELS)

    # ── Evaluate ───────────────────────────────────────────────────────────────
    print("\n[TRAIN] ══════════════════════════════════════")
    print("[TRAIN] EVALUATION RESULTS")
    print("[TRAIN] ══════════════════════════════════════")

    y_pred = model.predict(X_test_vec)

    print("\n[TRAIN] Classification Report:")
    print(classification_report(y_test, y_pred, target_names=LABELS, digits=4))

    cm = confusion_matrix(y_test, y_pred, labels=LABELS)
    print("[TRAIN] Confusion Matrix (rows=actual, cols=predicted):")
    header = "".join(f"{l[:5]:>8}" for l in LABELS)
    print(f"{'Actual / Pred':>20}  {header}")
    for i, row_label in enumerate(LABELS):
        row_str = "".join(f"{cm[i][j]:>8}" for j in range(len(LABELS)))
        print(f"  {row_label:>20}  {row_str}")

    prec, rec, f1, _ = precision_recall_fscore_support(
        y_test, y_pred, average="weighted"
    )
    accuracy = float(np.mean(np.array(y_pred) == np.array(y_test)))

    print(f"\n[TRAIN] Overall Accuracy  : {accuracy:.4f}  ({accuracy*100:.2f}%)")
    print(f"[TRAIN] Weighted Precision: {prec:.4f}")
    print(f"[TRAIN] Weighted Recall   : {rec:.4f}")
    print(f"[TRAIN] Weighted F1-Score : {f1:.4f}")

    _, class_rec, class_f1, _ = precision_recall_fscore_support(
        y_test, y_pred, labels=LABELS, average=None
    )
    print(f"\n[TRAIN] Per-class recall (critical for child safety):")
    for lbl, r, f in zip(LABELS, class_rec, class_f1):
        flag = "  ← ⚠ CHECK" if lbl == "Overstimulating" and r < 0.70 else ""
        print(f"[TRAIN]   {lbl:>20}: recall={r:.4f}  F1={f:.4f}{flag}")

    print("\n[TRAIN] ══════════════════════════════════════")
    if f1 < 0.70:
        print(f"[TRAIN] ⚠  Weighted F1 ({f1:.4f}) is below thesis target of 0.70")
        print(f"[TRAIN]    Consider: checking label quality or adjusting alpha")
    else:
        print(f"[TRAIN] ✓  F1 ({f1:.4f}) meets thesis target ≥ 0.70")
    print("[TRAIN] ══════════════════════════════════════\n")

    # ── Build metrics dict ─────────────────────────────────────────────────────
    metrics = {
        "accuracy":           round(accuracy, 4),
        "weighted_precision": round(float(prec), 4),
        "weighted_recall":    round(float(rec), 4),
        "weighted_f1":        round(float(f1), 4),
        "train_size":         len(X_train),
        "test_size":          len(X_test),
        "vocabulary_size":    len(vectorizer.vocabulary_),
        "classes":            LABELS,
    }

    # ── Save model bundle ──────────────────────────────────────────────────────
    bundle = {
        "model":         model,
        "label_encoder": label_encoder,
        "label_names":   LABELS,
        "metrics":       metrics,
    }

    with open(MODEL_PATH, "wb") as f:
        pickle.dump(bundle, f)
    with open(VECTORIZER_PATH, "wb") as f:
        pickle.dump(vectorizer, f)

    print(f"[TRAIN] ✓ Model bundle → {MODEL_PATH}")
    print(f"[TRAIN] ✓ Vectorizer   → {VECTORIZER_PATH}")

    # ── Auto-copy to backend/app/models/ ──────────────────────────────────────
    try:
        BACKEND_MODELS_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MODEL_PATH,      BACKEND_MODELS_DIR / "nb_model.pkl")
        shutil.copy2(VECTORIZER_PATH, BACKEND_MODELS_DIR / "vectorizer.pkl")
        print(f"[TRAIN] ✓ Copied to {BACKEND_MODELS_DIR}/")
    except Exception as e:
        print(f"[TRAIN] ⚠  Could not auto-copy to backend: {e}")
        print(f"[TRAIN]    Manually copy {MODEL_PATH.name} and {VECTORIZER_PATH.name}")
        print(f"[TRAIN]    to backend/app/models/")

    print(f"\n[TRAIN] Classes: {list(model.classes_)}")
    print(f"[TRAIN] Training complete. Restart Flask to load new model.\n")


if __name__ == "__main__":
    train()
