"""
merge_datasets.py  (updated)
─────────────────────────────────────────────────────────────────────────────
Pipeline:
  1. Load handpicked_metadata.csv          (450 gold samples)
  2. Load every scraped CSV in ./scraped/  (your auto-collected pool)
  3. Deduplicate by video_id
  4. Score each row for quality
  5. Select best N per class → reach target 700
  6. Save full pool  → merged_dataset.csv       (all unique rows, for reference)
  7. Save final 700  → final_700_dataset.csv    (ready for preprocess.py)

Target balance:
    Educational    → 230
    Neutral        → 235
    Overstimulating→ 235
    ─────────────────────
    Total          → 700

Usage:
    python merge_datasets.py

    # Or point to different folders:
    python merge_datasets.py --handpicked path/to/handpicked_metadata.csv
                             --scraped    path/to/scraped_folder/
                             --out        path/to/output_dir/
"""

import argparse
import re
import sys
from pathlib import Path

import pandas as pd

# ── DEFAULTS — resolved relative to this script's directory ──────────────────
# This means the script works regardless of which folder you cd into first.
_HERE = Path(__file__).parent.resolve()

DEFAULT_HANDPICKED = str(_HERE / "handpicked_metadata.csv")
DEFAULT_SCRAPED    = str(_HERE / "scraped")        # folder containing *.csv files
DEFAULT_OUT_MERGED = str(_HERE / "merged_dataset.csv")
DEFAULT_OUT_FINAL  = str(_HERE / "final_700_dataset.csv")

TARGET = {
    "Educational":     230,
    "Neutral":         235,
    "Overstimulating": 235,
}
HANDPICKED_PER_CLASS = 150

EXTRA_NEEDED = {k: TARGET[k] - HANDPICKED_PER_CLASS for k in TARGET}
EXTRA_SOURCE = "/home/solimandj12/Desktop/CF-2/ml_training/scripts/data/raw/"
# ── QUALITY SCORE ─────────────────────────────────────────────────────────────
def quality_score(row) -> int:
    """
    Heuristic quality score for a single video row.
    Higher = better metadata quality = preferred for training.
    """
    score = 0
    title = str(row.get("title", "") or "")
    desc  = str(row.get("description", "") or "")
    tags  = str(row.get("tags", "") or "")
    chan  = str(row.get("channel", "") or "")

    if len(desc.strip()) >= 30:    score += 3
    if len(tags.strip()) >  5:     score += 2
    if len(desc.strip()) >= 150:   score += 2
    if len(title.strip()) >= 20:   score += 1
    if len(chan.strip())  >  1:     score += 1

    if len(title.strip()) <= 5:    score -= 2
    url_only = re.fullmatch(r"https?://\S+", desc.strip())
    if url_only or len(desc.strip()) < 5:
        score -= 3

    return score


# ── HELPERS ───────────────────────────────────────────────────────────────────
def normalise_labels(df: pd.DataFrame) -> pd.DataFrame:
    """Make label column uniform: Educational / Neutral / Overstimulating."""
    for col in ["label", "category", "class", "Label", "Category"]:
        if col in df.columns:
            if col != "label":
                df = df.rename(columns={col: "label"})
            break
    if "label" not in df.columns:
        raise ValueError(f"No label column found. Columns: {df.columns.tolist()}")
    df["label"] = df["label"].astype(str).str.strip().str.title()
    return df


def load_handpicked(path: str) -> pd.DataFrame:
    df = pd.read_csv(path, low_memory=False)
    df = normalise_labels(df)
    df["source"] = "handpicked"
    print(f"  [handpicked]  {len(df)} rows  ← {path}")
    return df


def load_scraped(folder: str) -> pd.DataFrame:
    folder_path = Path(folder)
    csvs = list(folder_path.glob("*.csv"))
    if not csvs:
        print(f"  [WARNING] No CSV files found in '{folder}'. "
               "Proceeding with handpicked only.")
        return pd.DataFrame()

    parts = []
    for f in sorted(csvs):
        try:
            tmp = pd.read_csv(f, low_memory=False)
            tmp = normalise_labels(tmp)
            tmp["source"] = f.stem
            parts.append(tmp)
            print(f"  [scraped]     {len(tmp):>5} rows  ← {f.name}")
        except Exception as e:
            print(f"  [SKIP] {f.name}: {e}")

    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def select_best(pool: pd.DataFrame, handpicked: pd.DataFrame) -> pd.DataFrame:
    """
    From pool (scraped, deduped vs handpicked),
    pick the highest-quality rows to reach EXTRA_NEEDED per class.
    """
    pool = pool.copy()
    pool["_score"] = pool.apply(quality_score, axis=1)

    print("\n  ┌─────────────────────┬──────────┬──────────┬──────────┐")
    print("  │ Class               │ Need     │ In pool  │ Selected │")
    print("  ├─────────────────────┼──────────┼──────────┼──────────┤")

    parts = []
    for label, need in EXTRA_NEEDED.items():
        subset = (
            pool[pool["label"] == label]
            .sort_values(by=["_score", "video_id"], ascending=[False, True])
        )
        available = len(subset)
        chosen    = subset.head(need)

        if available < need:
            print(f"  │ {label:<19} │ {need:<8} │ {available:<8} │ {len(chosen):<8} │"
                  f"  ⚠ short by {need - available}")
        else:
            print(f"  │ {label:<19} │ {need:<8} │ {available:<8} │ {len(chosen):<8} │")

        parts.append(chosen)

    print("  └─────────────────────┴──────────┴──────────┴──────────┘")
    return pd.concat(parts, ignore_index=True).drop(columns=["_score"], errors="ignore")


# ── MAIN ──────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Merge & select 700 balanced dataset")
    parser.add_argument("--handpicked", default=DEFAULT_HANDPICKED)
    parser.add_argument("--scraped",    default=DEFAULT_SCRAPED)
    parser.add_argument("--out-merged", default=DEFAULT_OUT_MERGED)
    parser.add_argument("--out-final",  default=DEFAULT_OUT_FINAL)
    args = parser.parse_args()

    print("\n═══════════════════════════════════════════════════════════")
    print("  merge_datasets.py  –  Building final 700 balanced dataset")
    print("═══════════════════════════════════════════════════════════\n")

    # ── Load ──────────────────────────────────────────────────────────────────
    handpicked = load_handpicked(args.handpicked)
    scraped    = load_scraped(args.scraped)

    if scraped.empty:
        full_pool = handpicked.copy()
    else:
        full_pool = pd.concat([handpicked, scraped], ignore_index=True)

    # ── Deduplicate by video_id ────────────────────────────────────────────────
    before_dedup = len(full_pool)
    # Keep handpicked version if duplicate (it has trust_level=handpicked)
    full_pool = full_pool.drop_duplicates(subset="video_id", keep="first")
    print(f"\n  Duplicates removed:  {before_dedup - len(full_pool)}")
    print(f"  Unique pool size:    {len(full_pool)}")

    # ── Save full merged (reference) ──────────────────────────────────────────
    full_pool.to_csv(args.out_merged, index=False)
    print(f"\n  💾 Full pool saved  → {args.out_merged}  ({len(full_pool)} rows)")

    # ── Select best 250 from scraped pool ────────────────────────────────────
    hp_ids = set(handpicked["video_id"].astype(str))
    extra_pool = full_pool[~full_pool["video_id"].astype(str).isin(hp_ids)].copy()
    print(f"\n  Extra pool (scraped only):  {len(extra_pool)} rows")

    selected_extra = select_best(extra_pool, handpicked)

    # ── Combine handpicked + selected extra ──────────────────────────────────
    final = pd.concat(
        [handpicked.drop(columns=["_score"], errors="ignore"), selected_extra],
        ignore_index=True,
    )
    final = final.sample(frac=1, random_state=42).reset_index(drop=True)

    # ── Final report ─────────────────────────────────────────────────────────
    print(f"\n  ✅ Final dataset:  {len(final)} rows")
    print("\n  Class distribution:")
    vc = final["label"].value_counts()
    for lbl in ["Educational", "Neutral", "Overstimulating"]:
        cnt = vc.get(lbl, 0)
        bar = "█" * (cnt // 5)
        pct = cnt / len(final) * 100
        print(f"    {lbl:<20}  {cnt:>3}  ({pct:4.1f}%)  {bar}")

    # ── Save final 700 ────────────────────────────────────────────────────────
    final.to_csv(args.out_final, index=False)
    print(f"\n  💾 Final 700 saved  → {args.out_final}\n")

    print("  Next step:  python preprocess.py --input", args.out_final)
    print()


if __name__ == "__main__":
    main()
