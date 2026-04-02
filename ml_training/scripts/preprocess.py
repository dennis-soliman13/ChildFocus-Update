"""
ChildFocus - Data Preprocessing Script
ml_training/scripts/preprocess.py

Steps:
  1. Load final_700_dataset.csv  (700 balanced rows from build_700.py)
     ↳ Falls back to train_490.csv + test_210.csv if present
     ↳ Falls back to metadata_raw.csv as last resort
  2. Clean and combine text fields (title + tags + description)
  3. Remove stop words, URLs, special characters
  4. Save cleaned CSVs:
       data/processed/train_clean.csv   (490 rows – from train_490.csv)
       data/processed/test_clean.csv    (210 rows – from test_210.csv)
       data/processed/metadata_clean.csv (700 rows – full, for reference)

Run AFTER build_700.py:
    python preprocess.py
"""

import os
import re
import csv
from pathlib import Path

# ── Paths (relative to this script's location) ────────────────────────────────
HERE = Path(__file__).parent.resolve()

# Input priority:
#   1. final_700_dataset.csv  ← what build_700.py produces (BEST)
#   2. train_490 + test_210   ← pre-split files from build_700.py
#   3. metadata_raw.csv       ← last resort fallback
FINAL_700_PATH = HERE / "final_700_dataset.csv"
TRAIN_RAW_PATH = HERE / "train_490.csv"
TEST_RAW_PATH  = HERE / "test_210.csv"
RAW_PATH       = HERE / "data" / "raw" / "metadata_raw.csv"

PROCESSED_DIR       = HERE / "data" / "processed"
TRAIN_CLEAN_PATH    = PROCESSED_DIR / "train_clean.csv"
TEST_CLEAN_PATH     = PROCESSED_DIR / "test_clean.csv"
FULL_CLEAN_PATH     = PROCESSED_DIR / "metadata_clean.csv"   # compat alias

VALID_LABELS = {"Educational", "Neutral", "Overstimulating"}

# ── Query → Label fallback map ─────────────────────────────────────────────────
# Only used when a row has NO explicit label (scraped rows without a label col).
QUERY_LABEL_MAP = {
    "kids fast cartoon compilation":   "Overstimulating",
    "surprise eggs unboxing kids":     "Overstimulating",
    "kids prank videos compilation":   "Overstimulating",
    "kids slime videos satisfying":    "Overstimulating",
    "kids toy unboxing fast":          "Overstimulating",
    "baby shark challenge kids":       "Overstimulating",
    "kids gaming loud reaction":       "Overstimulating",
    "kids educational videos":         "Educational",
    "kids science experiments":        "Educational",
    "kids yoga and exercise":          "Educational",
    "preschool learning abc":          "Educational",
    "kids counting numbers learning":  "Educational",
    "children learning colors shapes": "Educational",
    "phonics for kids learning":       "Educational",
    "children cartoon episodes":       "Neutral",
    "nursery rhymes for toddlers":     "Neutral",
    "animated stories for kids":       "Neutral",
    "kids bedtime stories":            "Neutral",
    "children fairy tales":            "Neutral",
    "kids cooking simple recipes":     "Neutral",
    "children drawing tutorial":       "Neutral",
    "children's music videos":         "Neutral",
    "baby sensory videos":             "Neutral",
}

# ── Stop words ─────────────────────────────────────────────────────────────────
STOP_WORDS = {
    "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "is", "it", "this", "that", "was", "are",
    "be", "as", "so", "we", "he", "she", "they", "you", "i", "my", "your",
    "his", "her", "its", "our", "their", "what", "which", "who", "will",
    "would", "could", "should", "has", "have", "had", "do", "does", "did",
    "not", "no", "if", "then", "than", "when", "where", "how", "all",
    "each", "more", "also", "just", "can", "up", "out", "about", "into",
    "too", "very", "s", "t", "re", "ve", "ll", "d",
}


def assign_label(query: str, existing_label: str) -> str:
    if existing_label and existing_label.strip() in VALID_LABELS:
        return existing_label.strip()
    return QUERY_LABEL_MAP.get(query.strip().lower(), "Neutral")


def clean_text(title: str, tags: str, description: str) -> str:
    """
    Combine and clean title + tags + description for TF-IDF.
    Title  ×3  (strongest signal)
    Tags   ×2  (curated keywords)
    Description trimmed to 300 chars (often noisy/spammy)
    """
    title_part = f"{title} " * 3
    tags_part  = f"{tags} " * 2
    desc_part  = (description or "")[:300]
    raw = f"{title_part}{tags_part}{desc_part}".lower()
    raw = re.sub(r"https?://\S+|www\.\S+", " ", raw)   # remove URLs
    raw = re.sub(r"[^a-z\s]", " ", raw)                # keep only letters
    tokens = [t for t in raw.split() if t not in STOP_WORDS and len(t) > 1]
    return " ".join(tokens)


def process_rows(rows: list) -> tuple[list, dict]:
    """Clean a list of raw CSV row dicts. Returns (processed_rows, label_counts)."""
    processed    = []
    label_counts = {lbl: 0 for lbl in VALID_LABELS}
    skipped      = 0

    for row in rows:
        label = assign_label(row.get("query_used", ""), row.get("label", ""))
        text  = clean_text(
            row.get("title", ""),
            row.get("tags", ""),
            row.get("description", ""),
        )
        if not text.strip():
            skipped += 1
            continue
        label_counts[label] = label_counts.get(label, 0) + 1
        processed.append({
            "video_id":    row.get("video_id", ""),
            "text":        text,
            "label":       label,
            "title":       row.get("title", ""),
            "trust_level": row.get("trust_level", "auto_labeled"),
        })

    return processed, label_counts, skipped


def write_csv(path: Path, rows: list):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f, fieldnames=["video_id", "text", "label", "title", "trust_level"]
        )
        writer.writeheader()
        writer.writerows(rows)


def read_csv(path: Path) -> list:
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def print_distribution(label_counts: dict, total: int, title: str):
    print(f"\n[PREPROCESS] {title}")
    for lbl in ["Educational", "Neutral", "Overstimulating"]:
        count = label_counts.get(lbl, 0)
        pct   = count / total * 100 if total else 0
        bar   = "█" * int(pct / 2)
        print(f"  {lbl:>20} : {count:>3}  ({pct:5.1f}%)  {bar}")


# ══════════════════════════════════════════════════════════════════════════════
def preprocess():
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)

    # ── MODE 1: final_700 + pre-split train/test files exist (IDEAL) ──────────
    if FINAL_700_PATH.exists() and TRAIN_RAW_PATH.exists() and TEST_RAW_PATH.exists():
        print(f"[PREPROCESS] ✅ Found build_700.py outputs — using balanced 700 dataset")
        print(f"[PREPROCESS]    Train source : train_490.csv")
        print(f"[PREPROCESS]    Test  source : test_210.csv")

        train_rows = read_csv(TRAIN_RAW_PATH)
        test_rows  = read_csv(TEST_RAW_PATH)
        full_rows  = read_csv(FINAL_700_PATH)

        print(f"[PREPROCESS] Loaded {len(train_rows)} train rows, "
              f"{len(test_rows)} test rows, {len(full_rows)} total")

        train_proc, train_counts, train_skip = process_rows(train_rows)
        test_proc,  test_counts,  test_skip  = process_rows(test_rows)
        full_proc,  full_counts,  full_skip  = process_rows(full_rows)

        write_csv(TRAIN_CLEAN_PATH, train_proc)
        write_csv(TEST_CLEAN_PATH,  test_proc)
        write_csv(FULL_CLEAN_PATH,  full_proc)   # alias for train_nb.py compat

        print(f"\n[PREPROCESS] ✓ {len(train_proc)} rows → {TRAIN_CLEAN_PATH.name}  "
              f"(skipped {train_skip})")
        print(f"[PREPROCESS] ✓ {len(test_proc)} rows  → {TEST_CLEAN_PATH.name}  "
              f"(skipped {test_skip})")
        print(f"[PREPROCESS] ✓ {len(full_proc)} rows  → {FULL_CLEAN_PATH.name}")

        print_distribution(train_counts, len(train_proc), "Train label distribution:")
        print_distribution(test_counts,  len(test_proc),  "Test  label distribution:")

    # ── MODE 2: only final_700 exists (no pre-split files) ────────────────────
    elif FINAL_700_PATH.exists():
        print(f"[PREPROCESS] ✅ Found final_700_dataset.csv — processing 700 rows")
        print(f"[PREPROCESS] ⚠  train_490.csv / test_210.csv not found.")
        print(f"[PREPROCESS]    Run build_700.py to get pre-split files.")

        full_rows = read_csv(FINAL_700_PATH)
        full_proc, full_counts, full_skip = process_rows(full_rows)
        write_csv(FULL_CLEAN_PATH, full_proc)

        print(f"\n[PREPROCESS] ✓ {len(full_proc)} rows → {FULL_CLEAN_PATH.name}  "
              f"(skipped {full_skip})")
        print_distribution(full_counts, len(full_proc),
                           "Label distribution (full 700):")
        print(f"\n[PREPROCESS] ℹ  train_nb.py will do its own 70/30 split.")

    # ── MODE 3: fallback — raw data only ──────────────────────────────────────
    else:
        print(f"[PREPROCESS] ⚠  final_700_dataset.csv not found!")
        print(f"[PREPROCESS]    Run build_700.py first for a balanced dataset.")
        print(f"[PREPROCESS]    Falling back to raw metadata...")

        if not RAW_PATH.exists():
            raise FileNotFoundError(
                f"No input data found.\n"
                f"  Expected: {FINAL_700_PATH}\n"
                f"  Fallback: {RAW_PATH}\n"
                f"  → Run build_700.py first."
            )

        raw_rows = read_csv(RAW_PATH)
        print(f"[PREPROCESS] Loaded {len(raw_rows)} rows from {RAW_PATH.name}")
        full_proc, full_counts, full_skip = process_rows(raw_rows)
        write_csv(FULL_CLEAN_PATH, full_proc)

        total = len(full_proc)
        print(f"\n[PREPROCESS] ✓ {total} rows → {FULL_CLEAN_PATH.name}  "
              f"(skipped {full_skip})")
        print_distribution(full_counts, total, "Label distribution (raw fallback):")

    print(f"\n[PREPROCESS] Next step: python train_nb.py\n")


if __name__ == "__main__":
    preprocess()
