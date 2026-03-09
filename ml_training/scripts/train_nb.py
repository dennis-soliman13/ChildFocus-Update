"""
ChildFocus — Naïve Bayes Training Script
ml_training/scripts/train_nb.py

What this does:
  1. Reads metadata_labeled.csv from data/processed/
  2. Vectorizes text using the fitted vectorizer.pkl
  3. Trains a ComplementNB classifier (best for imbalanced text classes)
  4. Evaluates on 30% hold-out test set (Accuracy, F1, Confusion Matrix)
  5. Saves nb_model.pkl to ../../backend/app/models/

Target: F1 ≥ 0.70

Run from: ml_training/scripts/
Command:   py train_nb.py
"""

import os
import pickle
import sys

print("[TRAIN_NB] ══════════════════════════════════════")
print("[TRAIN_NB] ChildFocus — Naïve Bayes Training")
print("[TRAIN_NB] ══════════════════════════════════════")

# ── Try importing required packages ──────────────────────────────────────────
try:
    import pandas as pd
    print("[TRAIN_NB] ✓ pandas loaded")
except ImportError:
    print("[TRAIN_NB] ✗ pandas not found. Run: pip install pandas")
    sys.exit(1)

try:
    import numpy as np
    from sklearn.naive_bayes     import ComplementNB
    from sklearn.preprocessing   import LabelEncoder
    from sklearn.model_selection import train_test_split
    from sklearn.metrics         import (
        accuracy_score, f1_score,
        classification_report, confusion_matrix
    )
    print("[TRAIN_NB] ✓ scikit-learn loaded")
except ImportError:
    print("[TRAIN_NB] ✗ scikit-learn not found. Run: pip install scikit-learn")
    sys.exit(1)

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
LABELED_CSV   = os.path.join(SCRIPT_DIR, "data", "processed", "metadata_labeled.csv")
MODELS_DIR    = os.path.join(SCRIPT_DIR, "..", "..", "backend", "app", "models")
VEC_PATH      = os.path.join(MODELS_DIR, "vectorizer.pkl")
MODEL_PATH    = os.path.join(MODELS_DIR, "nb_model.pkl")
OUTPUTS_DIR   = os.path.join(SCRIPT_DIR, "..", "outputs")

print(f"\n[TRAIN_NB] Input  → {LABELED_CSV}")
print(f"[TRAIN_NB] Model  → {MODEL_PATH}")

# ── Check prerequisite files ──────────────────────────────────────────────────
if not os.path.exists(LABELED_CSV):
    print(f"\n[TRAIN_NB] ✗ ERROR: Labeled CSV not found.")
    print(f"             Run preprocess.py first: py preprocess.py")
    sys.exit(1)

if not os.path.exists(VEC_PATH):
    print(f"\n[TRAIN_NB] ✗ ERROR: vectorizer.pkl not found.")
    print(f"             Run preprocess.py first: py preprocess.py")
    sys.exit(1)

# ── Load data ─────────────────────────────────────────────────────────────────
print(f"\n[TRAIN_NB] Loading labeled dataset...")
df = pd.read_csv(LABELED_CSV, encoding="utf-8")
print(f"[TRAIN_NB] ✓ Loaded {len(df)} rows")

required_cols = ["text_combined", "label"]
for col in required_cols:
    if col not in df.columns:
        print(f"[TRAIN_NB] ✗ ERROR: Column '{col}' missing from CSV.")
        print(f"             Found: {list(df.columns)}")
        sys.exit(1)

# Drop rows with empty text or label
df = df.dropna(subset=["text_combined", "label"])
df = df[df["text_combined"].str.strip().str.len() > 5]
df = df[df["label"].str.strip().str.len() > 0]
print(f"[TRAIN_NB] ✓ {len(df)} valid rows after cleaning")

label_counts = df["label"].value_counts()
print(f"\n[TRAIN_NB] Label distribution:")
for label, count in label_counts.items():
    print(f"           {label:20s} → {count} rows ({count/len(df)*100:.1f}%)")

# ── Load vectorizer ───────────────────────────────────────────────────────────
print(f"\n[TRAIN_NB] Loading vectorizer...")
with open(VEC_PATH, "rb") as f:
    vectorizer = pickle.load(f)
print(f"[TRAIN_NB] ✓ Vectorizer loaded ({len(vectorizer.vocabulary_)} features)")

# ── Encode labels ─────────────────────────────────────────────────────────────
label_encoder = LabelEncoder()
label_encoder.fit(["Educational", "Neutral", "Overstimulating"])
y = label_encoder.transform(df["label"])
classes = list(label_encoder.classes_)
print(f"\n[TRAIN_NB] Label encoding: {dict(zip(classes, label_encoder.transform(classes)))}")

# ── Vectorize text ────────────────────────────────────────────────────────────
print(f"\n[TRAIN_NB] Vectorizing text...")
X = vectorizer.transform(df["text_combined"])
print(f"[TRAIN_NB] ✓ Feature matrix: {X.shape[0]} samples × {X.shape[1]} features")

# ── Train / Test split (70/30) ────────────────────────────────────────────────
print(f"\n[TRAIN_NB] Splitting dataset (70% train / 30% test)...")
X_train, X_test, y_train, y_test = train_test_split(
    X, y,
    test_size    = 0.30,
    random_state = 42,
    stratify     = y,          # preserve class proportions
)
print(f"[TRAIN_NB] ✓ Train: {X_train.shape[0]} samples | Test: {X_test.shape[0]} samples")

# ── Train ComplementNB ────────────────────────────────────────────────────────
print(f"\n[TRAIN_NB] Training ComplementNB classifier...")
model = ComplementNB(alpha=0.5)   # alpha=0.5 is smoother than default 1.0
model.fit(X_train, y_train)
print(f"[TRAIN_NB] ✓ Training complete")

# ── Evaluate ──────────────────────────────────────────────────────────────────
print(f"\n[TRAIN_NB] ── Evaluation Results ──────────────────")
y_pred = model.predict(X_test)

accuracy = accuracy_score(y_test, y_pred)
f1_macro = f1_score(y_test, y_pred, average="macro")
f1_weighted = f1_score(y_test, y_pred, average="weighted")

print(f"[TRAIN_NB] Accuracy    : {accuracy:.4f} ({accuracy*100:.1f}%)")
print(f"[TRAIN_NB] F1 (macro)  : {f1_macro:.4f}")
print(f"[TRAIN_NB] F1 (weighted): {f1_weighted:.4f}")

print(f"\n[TRAIN_NB] Classification Report:")
print(classification_report(y_test, y_pred, target_names=classes))

print(f"[TRAIN_NB] Confusion Matrix (rows=actual, cols=predicted):")
cm = confusion_matrix(y_test, y_pred)
print(f"           Labels: {classes}")
for i, row in enumerate(cm):
    print(f"           {classes[i]:20s}: {row}")

# ── F1 threshold check ────────────────────────────────────────────────────────
TARGET_F1 = 0.70
if f1_macro >= TARGET_F1:
    print(f"\n[TRAIN_NB] ✓ F1 {f1_macro:.4f} ≥ {TARGET_F1} — TARGET MET ✅")
else:
    print(f"\n[TRAIN_NB] ⚠ F1 {f1_macro:.4f} < {TARGET_F1} — below target")
    print(f"           This is acceptable given 500 rows. Will improve in Sprint 3.")

# ── Save model ────────────────────────────────────────────────────────────────
os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs(OUTPUTS_DIR, exist_ok=True)

model_bundle = {
    "model":         model,
    "label_encoder": label_encoder,
    "label_names":   classes,
    "metrics": {
        "accuracy":    round(float(accuracy), 4),
        "f1_macro":    round(float(f1_macro), 4),
        "f1_weighted": round(float(f1_weighted), 4),
        "train_size":  X_train.shape[0],
        "test_size":   X_test.shape[0],
        "features":    X.shape[1],
    }
}

with open(MODEL_PATH, "wb") as f:
    pickle.dump(model_bundle, f)
print(f"\n[TRAIN_NB] ✓ nb_model.pkl saved  → {MODEL_PATH}")

# Also save a copy to ml_training/outputs/
outputs_model = os.path.join(OUTPUTS_DIR, "nb_model.pkl")
outputs_vec   = os.path.join(OUTPUTS_DIR, "vectorizer.pkl")
with open(outputs_model, "wb") as f:
    pickle.dump(model_bundle, f)
with open(outputs_vec, "wb") as f:
    pickle.dump(vectorizer, f)
print(f"[TRAIN_NB] ✓ Backup copy saved   → {outputs_model}")

print(f"\n[TRAIN_NB] ══════════════════════════════════════")
print(f"[TRAIN_NB] DONE. Both .pkl files are ready.")
print(f"[TRAIN_NB] Next step: cd ../../backend && py -m pytest tests/ -v")
print(f"[TRAIN_NB] ══════════════════════════════════════\n")
