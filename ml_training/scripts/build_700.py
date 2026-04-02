"""
build_700.py
════════════════════════════════════════════════════════════════
ONE script. Does everything before preprocess.py.

INPUT  (auto-detected, same folder as this script):
  • handpicked_metadata.csv       ← your 450 gold samples
  • merged_dataset.csv            ← scraped pool (if exists)
  • scraped/*.csv                 ← scraped folder  (if exists)
  Both sources are used if present; script survives if only one exists.

OUTPUT (same folder):
  • final_700_dataset.csv         ← 700 balanced rows  (shuffled)
  • train_490.csv                 ← 70 %  training set
  • test_210.csv                  ← 30 %  test set

TARGET BALANCE:
  Educational     150 + 80  = 230
  Neutral         150 + 85  = 235
  Overstimulating 150 + 85  = 235
                  ─────────────────
  Total           450 + 250 = 700

  Train 70 % → 490   |   Test 30 % → 210   (stratified per class)
════════════════════════════════════════════════════════════════
"""

import re
from pathlib import Path

import pandas as pd
from sklearn.model_selection import train_test_split

# ── paths (always relative to THIS file) ────────────────────────────────────
HERE = Path(__file__).parent.resolve()

HANDPICKED_CSV = HERE / "handpicked_metadata.csv"
MERGED_CSV     = HERE / "data" / "raw" / "merged_dataset.csv"
SCRAPED_DIR    = HERE / "scraped"   # fallback, not needed

OUT_700   = HERE / "final_700_dataset.csv"
OUT_TRAIN = HERE / "train_490.csv"
OUT_TEST  = HERE / "test_210.csv"

# ── per-class targets ────────────────────────────────────────────────────────
TARGET = {
    "Educational":     230,
    "Neutral":         235,
    "Overstimulating": 235,
}
HANDPICKED_BASE = 150                          # confirmed: 150 each in handpicked
NEED_EXTRA = {k: TARGET[k] - HANDPICKED_BASE for k in TARGET}
# → Educational: 80  |  Neutral: 85  |  Overstimulating: 85

# ════════════════════════════════════════════════════════════════════════════
# HELPERS
# ════════════════════════════════════════════════════════════════════════════

def fix_label_col(df: pd.DataFrame) -> pd.DataFrame:
    """Rename whatever label/category column exists → 'label', title-case values."""
    for col in ["label", "Label", "category", "Category", "class", "Class"]:
        if col in df.columns:
            df = df.rename(columns={col: "label"})
            break
    df["label"] = df["label"].astype(str).str.strip().str.title()
    return df


def quality_score(row) -> int:
    """
    Simple heuristic — higher = better metadata quality.
    Used to rank scraped rows; best ones are picked first.
    """
    score = 0
    title = str(row.get("title", "") or "")
    desc  = str(row.get("description", "") or "")
    tags  = str(row.get("tags", "") or "")
    chan  = str(row.get("channel", "") or "")

    if len(desc.strip()) >= 30:    score += 3   # has real description
    if len(desc.strip()) >= 150:   score += 2   # detailed description
    if len(tags.strip()) > 5:      score += 2   # has tags
    if len(title.strip()) >= 20:   score += 1   # reasonable title
    if len(chan.strip()) > 1:       score += 1   # has channel name

    if len(title.strip()) <= 5:    score -= 2   # suspiciously short title
    if re.fullmatch(r"https?://\S+", desc.strip()) or len(desc.strip()) < 5:
        score -= 3                               # description is just a URL / empty

    return score


# ════════════════════════════════════════════════════════════════════════════
# STEP 1 — Load handpicked (your gold 450)
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 1 — Load handpicked (450 gold)")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

if not HANDPICKED_CSV.exists():
    raise FileNotFoundError(f"Cannot find: {HANDPICKED_CSV}")

handpicked = pd.read_csv(HANDPICKED_CSV, low_memory=False)
handpicked = fix_label_col(handpicked)
print(f"  Loaded {len(handpicked)} rows from handpicked_metadata.csv")
print(f"  {handpicked['label'].value_counts().to_dict()}")

# ════════════════════════════════════════════════════════════════════════════
# STEP 2 — Load scraped pool (merged_dataset.csv and/or scraped/*.csv)
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 2 — Load scraped pool")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

parts = []

if MERGED_CSV.exists():
    tmp = pd.read_csv(MERGED_CSV, low_memory=False)
    tmp = fix_label_col(tmp)
    print(f"  merged_dataset.csv  →  {len(tmp)} rows")
    parts.append(tmp)
else:
    print("  merged_dataset.csv  →  not found, skipping")

if SCRAPED_DIR.is_dir():
    csv_files = sorted(SCRAPED_DIR.glob("*.csv"))
    for f in csv_files:
        try:
            tmp = pd.read_csv(f, low_memory=False)
            tmp = fix_label_col(tmp)
            print(f"  scraped/{f.name:<35} →  {len(tmp)} rows")
            parts.append(tmp)
        except Exception as e:
            print(f"  [SKIP] {f.name}: {e}")
else:
    print("  scraped/ folder  →  not found, skipping")

if not parts:
    raise RuntimeError(
        "No scraped data found!\n"
        f"  Expected: {MERGED_CSV}  OR  {SCRAPED_DIR}/*.csv\n"
        "  Make sure at least one exists next to this script."
    )

pool_raw = pd.concat(parts, ignore_index=True)
print(f"\n  Combined raw pool:  {len(pool_raw)} rows")

# ════════════════════════════════════════════════════════════════════════════
# STEP 3 — Deduplicate & remove handpicked IDs from pool
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 3 — Deduplicate")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

pool_raw = pool_raw.drop_duplicates(subset="video_id", keep="first")
hp_ids   = set(handpicked["video_id"].astype(str))
pool     = pool_raw[~pool_raw["video_id"].astype(str).isin(hp_ids)].copy()

print(f"  After dedup + removing handpicked IDs:  {len(pool)} unique scraped rows")
print(f"  Label distribution in pool:")
for lbl, cnt in pool["label"].value_counts().items():
    print(f"    {lbl:<20} {cnt}")

# ════════════════════════════════════════════════════════════════════════════
# STEP 4 — Score & select best 250 from pool
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 4 — Select best 250 from pool")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

pool["_score"] = pool.apply(quality_score, axis=1)

print(f"\n  {'Class':<20} {'Need':>6} {'Available':>10} {'Selected':>9}")
print(f"  {'─'*20} {'─'*6} {'─'*10} {'─'*9}")

selected_parts = []
for label, need in NEED_EXTRA.items():
    subset    = pool[pool["label"] == label].sort_values("_score", ascending=False)
    available = len(subset)
    chosen    = subset.head(need)

    flag = "  ⚠ SHORT" if available < need else ""
    print(f"  {label:<20} {need:>6} {available:>10} {len(chosen):>9}{flag}")
    selected_parts.append(chosen)

extra_250 = pd.concat(selected_parts, ignore_index=True).drop(columns=["_score"])

# ════════════════════════════════════════════════════════════════════════════
# STEP 5 — Combine → 700, shuffle
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 5 — Build final 700")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

final_700 = pd.concat(
    [handpicked, extra_250], ignore_index=True
).sample(frac=1, random_state=42).reset_index(drop=True)

print(f"\n  Total rows : {len(final_700)}")
print(f"  {'Class':<20} {'Count':>6} {'%':>6}")
print(f"  {'─'*20} {'─'*6} {'─'*6}")
for lbl in ["Educational", "Neutral", "Overstimulating"]:
    cnt = (final_700["label"] == lbl).sum()
    print(f"  {lbl:<20} {cnt:>6} {cnt/len(final_700)*100:>5.1f}%")

# ════════════════════════════════════════════════════════════════════════════
# STEP 6 — Stratified 70 / 30 split
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 6 — 70 / 30 stratified split")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

train_df, test_df = train_test_split(
    final_700,
    test_size=0.30,
    stratify=final_700["label"],
    random_state=42,
)

print(f"\n  Train : {len(train_df)} rows")
for lbl in ["Educational", "Neutral", "Overstimulating"]:
    cnt = (train_df["label"] == lbl).sum()
    print(f"    {lbl:<20} {cnt}")

print(f"\n  Test  : {len(test_df)} rows")
for lbl in ["Educational", "Neutral", "Overstimulating"]:
    cnt = (test_df["label"] == lbl).sum()
    print(f"    {lbl:<20} {cnt}")

# ════════════════════════════════════════════════════════════════════════════
# STEP 7 — Save all outputs
# ════════════════════════════════════════════════════════════════════════════
print("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
print("  STEP 7 — Save files")
print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

final_700.to_csv(OUT_700,   index=False)
train_df.to_csv(OUT_TRAIN, index=False)
test_df.to_csv(OUT_TEST,  index=False)

print(f"\n  ✅ final_700_dataset.csv  →  {len(final_700)} rows")
print(f"  ✅ train_490.csv          →  {len(train_df)} rows")
print(f"  ✅ test_210.csv           →  {len(test_df)} rows")
print(f"\n  Next → python3 preprocess.py\n")
