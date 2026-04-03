import pandas as pd
import numpy as np
from collections import Counter

# =========================
# CONFIG (EDIT PATHS HERE)
# =========================
HANDPICKED_PATH = "/home/solimandj12/Desktop/CF-2/ml_training/scripts/data/processed/metadata_labeled.csv"
SCRAPED_PATH    = "/home/solimandj12/Desktop/CF-2/ml_training/scripts/data/processed/metadata_clean.csv"
OUTPUT_PATH     = "/home/solimandj12/Desktop/CF-2/ml_training/scripts/data/processed/final_dataset_700.csv"

# Target distribution
TARGET_COUNTS = {
    "educational": 230,
    "neutral": 235,
    "overstimulating": 235
}

RANDOM_SEED = 42
np.random.seed(RANDOM_SEED)


# =========================
# LOAD DATA
# =========================
print("Loading datasets...")

df_hand = pd.read_csv(HANDPICKED_PATH)
df_scraped = pd.read_csv(SCRAPED_PATH)

print(f"Handpicked: {len(df_hand)}")
print(f"Scraped: {len(df_scraped)}")


# =========================
# STANDARDIZE LABELS
# =========================
def normalize_label(label):
    label = str(label).lower().strip()
    if "educ" in label:
        return "educational"
    elif "neutral" in label:
        return "neutral"
    elif "over" in label:
        return "overstimulating"
    return None


df_hand["label"] = df_hand["label"].apply(normalize_label)

# IMPORTANT:
# scraped dataset may NOT have labels yet
# if it has, normalize them too
if "label" in df_scraped.columns:
    df_scraped["label"] = df_scraped["label"].apply(normalize_label)


# =========================
# REMOVE DUPLICATES
# =========================
print("\nRemoving duplicates...")

# Prefer video_id if exists
if "video_id" in df_hand.columns and "video_id" in df_scraped.columns:
    df_scraped = df_scraped[~df_scraped["video_id"].isin(df_hand["video_id"])]
else:
    # fallback: use title similarity
    df_scraped = df_scraped[~df_scraped["title"].isin(df_hand["title"])]

print(f"Scraped after dedup: {len(df_scraped)}")


# =========================
# CURRENT DISTRIBUTION
# =========================
print("\nCurrent class distribution (handpicked):")
current_counts = df_hand["label"].value_counts()
print(current_counts)

needed = {}
for cls in TARGET_COUNTS:
    current = current_counts.get(cls, 0)
    needed[cls] = TARGET_COUNTS[cls] - current

print("\nSamples needed per class:")
print(needed)


# =========================
# AUTO-LABEL SCRAPED (IF NEEDED)
# =========================
def simple_heuristic_label(row):
    text = f"{row.get('title','')} {row.get('description','')} {row.get('tags','')}".lower()

    # heuristic rules (aligned with your thesis)
    if any(k in text for k in ["learn", "education", "alphabet", "numbers", "science", "math"]):
        return "educational"

    if any(k in text for k in ["kids playing", "toy review", "vlog", "daily life"]):
        return "neutral"

    if any(k in text for k in ["fast", "colorful", "surprise", "challenge", "loud", "crazy"]):
        return "overstimulating"

    return "neutral"  # fallback


if "label" not in df_scraped.columns:
    print("\nApplying heuristic labeling to scraped data...")
    df_scraped["label"] = df_scraped.apply(simple_heuristic_label, axis=1)


# =========================
# SAMPLE ADDITIONAL DATA
# =========================
print("\nSelecting additional samples...")

additional_samples = []

for cls, n_needed in needed.items():
    if n_needed <= 0:
        continue

    subset = df_scraped[df_scraped["label"] == cls]

    if len(subset) < n_needed:
        print(f"⚠ Warning: Not enough {cls} samples. Taking all available.")
        sampled = subset
    else:
        sampled = subset.sample(n=n_needed, random_state=RANDOM_SEED)

    additional_samples.append(sampled)

df_additional = pd.concat(additional_samples, ignore_index=True)

print(f"Added samples: {len(df_additional)}")


# =========================
# MERGE FINAL DATASET
# =========================
df_final = pd.concat([df_hand, df_additional], ignore_index=True)

print("\nFinal dataset size:", len(df_final))
print("\nFinal distribution:")
print(df_final["label"].value_counts())


# =========================
# SHUFFLE
# =========================
df_final = df_final.sample(frac=1, random_state=RANDOM_SEED).reset_index(drop=True)


# =========================
# SAVE
# =========================
df_final.to_csv(OUTPUT_PATH, index=False)

print(f"\n✅ Final dataset saved to:\n{OUTPUT_PATH}")
