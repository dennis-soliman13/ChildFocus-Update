"""
ChildFocus — Preprocessing Script
ml_training/scripts/preprocess.py

What this does:
  1. Reads metadata_raw.csv (500 rows, no labels)
  2. Auto-labels each row based on the query_used column
  3. Cleans and combines title + description + tags into one text field
  4. Saves metadata_labeled.csv to data/processed/
  5. Saves vectorizer.pkl to ../../backend/app/models/

Run from: ml_training/scripts/
Command:   py preprocess.py
"""

import os
import re
import csv
import pickle

print("[PREPROCESS] ══════════════════════════════════════")
print("[PREPROCESS] ChildFocus — Data Preprocessing")
print("[PREPROCESS] ══════════════════════════════════════")

# ── Try importing required packages ──────────────────────────────────────────
try:
    import pandas as pd
    print("[PREPROCESS] ✓ pandas loaded")
except ImportError:
    print("[PREPROCESS] ✗ pandas not found. Run: pip install pandas")
    exit(1)

try:
    from sklearn.feature_extraction.text import TfidfVectorizer
    print("[PREPROCESS] ✓ scikit-learn loaded")
except ImportError:
    print("[PREPROCESS] ✗ scikit-learn not found. Run: pip install scikit-learn")
    exit(1)

# ── Paths (relative to ml_training/scripts/) ─────────────────────────────────
SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
RAW_CSV      = os.path.join(SCRIPT_DIR, "data", "raw", "metadata_raw.csv")
PROCESSED_DIR = os.path.join(SCRIPT_DIR, "data", "processed")
LABELED_CSV  = os.path.join(PROCESSED_DIR, "metadata_labeled.csv")
MODELS_DIR   = os.path.join(SCRIPT_DIR, "..", "..", "backend", "app", "models")
VEC_PATH     = os.path.join(MODELS_DIR, "vectorizer.pkl")

print(f"\n[PREPROCESS] Input  → {RAW_CSV}")
print(f"[PREPROCESS] Output → {LABELED_CSV}")
print(f"[PREPROCESS] Model  → {VEC_PATH}")

# ── Check input file exists ───────────────────────────────────────────────────
if not os.path.exists(RAW_CSV):
    print(f"\n[PREPROCESS] ✗ ERROR: Raw CSV not found at:")
    print(f"             {RAW_CSV}")
    print(f"\n  Expected folder: ml_training/scripts/data/raw/metadata_raw.csv")
    print(f"  Check your file is in the right place and try again.")
    exit(1)

# ── Label mapping from query_used → OIR label ────────────────────────────────
# Based on thesis: educational queries → Educational,
#                  neutral queries     → Neutral,
#                  overstimulating queries → Overstimulating

LABEL_MAP = {
    "kids educational videos":         "Educational",
    "kids science experiments":        "Educational",
    "kids yoga and exercise":          "Educational",
    "children cartoon episodes":       "Educational",
    "nursery rhymes for toddlers":     "Educational",
    "children's music videos":         "Neutral",
    "animated stories for kids":       "Neutral",
    "baby sensory videos":             "Neutral",
    "kids fast cartoon compilation":   "Overstimulating",
    "surprise eggs unboxing kids":     "Overstimulating",
}

def auto_label(query: str) -> str:
    """Map query_used to OIR label. Defaults to Neutral if unknown."""
    q = str(query).strip().lower()
    for key, label in LABEL_MAP.items():
        if key.lower() in q or q in key.lower():
            return label
    # Fallback: try keyword matching
    if any(w in q for w in ["educational", "science", "learn", "abc", "phonics", "yoga"]):
        return "Educational"
    if any(w in q for w in ["fast", "unboxing", "surprise", "compilation", "sensory"]):
        return "Overstimulating"
    return "Neutral"

def clean_text(text: str) -> str:
    """Normalize text for TF-IDF. Must match naive_bayes.py exactly."""
    text = str(text).lower()
    text = re.sub(r"http\S+|www\S+", " ", text)      # remove URLs
    text = re.sub(r"[^a-z0-9\s]", " ", text)          # keep letters/numbers
    text = re.sub(r"\s+", " ", text).strip()           # collapse whitespace
    return text

# ── Load CSV ──────────────────────────────────────────────────────────────────
print(f"\n[PREPROCESS] Loading CSV...")
df = pd.read_csv(RAW_CSV, encoding="utf-8")
print(f"[PREPROCESS] ✓ Loaded {len(df)} rows, columns: {list(df.columns)}")

# ── Auto-label ────────────────────────────────────────────────────────────────
print(f"\n[PREPROCESS] Auto-labeling by query_used...")

if "query_used" not in df.columns:
    print("[PREPROCESS] ✗ ERROR: 'query_used' column not found in CSV.")
    print(f"             Found columns: {list(df.columns)}")
    exit(1)

df["label"] = df["query_used"].apply(auto_label)

label_counts = df["label"].value_counts()
print(f"[PREPROCESS] Label distribution:")
for label, count in label_counts.items():
    print(f"             {label:20s} → {count} rows")

# ── Build combined text column ────────────────────────────────────────────────
print(f"\n[PREPROCESS] Building combined text (title + description)...")

df["title"]       = df["title"].fillna("").astype(str)
df["description"] = df["description"].fillna("").astype(str)

# Combine title + description (tags not in CSV from collect_metadata.py)
df["text_combined"] = (df["title"] + " " + df["description"]).apply(clean_text)

# Drop rows with empty text
before = len(df)
df = df[df["text_combined"].str.strip().str.len() > 5].reset_index(drop=True)
after = len(df)
if before != after:
    print(f"[PREPROCESS] ⚠ Dropped {before - after} rows with empty text")
print(f"[PREPROCESS] ✓ {after} rows ready for training")

# ── Save labeled CSV ──────────────────────────────────────────────────────────
os.makedirs(PROCESSED_DIR, exist_ok=True)
df.to_csv(LABELED_CSV, index=False, encoding="utf-8")
print(f"\n[PREPROCESS] ✓ Labeled CSV saved → {LABELED_CSV}")

# ── Fit TF-IDF Vectorizer ─────────────────────────────────────────────────────
print(f"\n[PREPROCESS] Fitting TF-IDF vectorizer...")

vectorizer = TfidfVectorizer(
    max_features  = 5000,
    ngram_range   = (1, 2),    # unigrams + bigrams
    min_df        = 2,         # ignore terms appearing in < 2 docs
    sublinear_tf  = True,      # apply log normalization
    strip_accents = "unicode",
)

X = vectorizer.fit_transform(df["text_combined"])
print(f"[PREPROCESS] ✓ Vectorizer fitted")
print(f"             Feature matrix: {X.shape[0]} samples × {X.shape[1]} features")
print(f"             Vocabulary size: {len(vectorizer.vocabulary_)} terms")

# ── Save vectorizer ───────────────────────────────────────────────────────────
os.makedirs(MODELS_DIR, exist_ok=True)
with open(VEC_PATH, "wb") as f:
    pickle.dump(vectorizer, f)
print(f"[PREPROCESS] ✓ vectorizer.pkl saved → {VEC_PATH}")

print(f"\n[PREPROCESS] ══════════════════════════════════════")
print(f"[PREPROCESS] DONE. Now run: py train_nb.py")
print(f"[PREPROCESS] ══════════════════════════════════════\n")
