"""
ChildFocus - Handpicked Playlist Metadata Collector
ml_training/scripts/collect_handpicked.py

Fetches YouTube metadata for 450 manually curated videos organized in
3 playlists (150 Educational / 150 Neutral / 150 Overstimulating).

These are the highest-trust labels in the ChildFocus pipeline — they
override any auto-labeled or previously labeled entries for the same
video ID during merging.

Setup:
  1. Create 3 YouTube playlists (one per class) and add your 150 videos each.
  2. Set PLAYLIST_IDS below to your actual playlist IDs.
     (Get ID from the URL: youtube.com/playlist?list=<THIS_PART>)
  3. Make sure YOUTUBE_API_KEY is set in backend/.env
  4. Run: python collect_handpicked.py

Output:
  data/raw/handpicked_metadata.csv  ← trust_level="handpicked" column added
"""

import csv
import os
import time

import requests
from dotenv import load_dotenv

load_dotenv("../../backend/.env")
API_KEY = os.getenv("YOUTUBE_API_KEY")

# ══════════════════════════════════════════════════════════════════════════════
# !! CONFIGURE THESE — paste your 3 playlist IDs here !!
# ══════════════════════════════════════════════════════════════════════════════
PLAYLIST_IDS = {
    "Educational":     "PLuPWFbwOhVRs-_cRrplrItrN76AdkQOFr",   # ← replace
    "Neutral":         "PLuPWFbwOhVRuh0A9ifFtxbAvruUFUhh__",   # ← replace
    "Overstimulating": "PLuPWFbwOhVRtLyRZxwl372xBg7511_B1X",   # ← replace
}
# ══════════════════════════════════════════════════════════════════════════════

OUTPUT_PATH = "data/raw/handpicked_metadata.csv"
TARGET_PER_CLASS = 150


def get_playlist_video_ids(playlist_id: str) -> list[str]:
    """
    Fetches all video IDs from a YouTube playlist (handles pagination).
    Returns up to 200 IDs (more than enough for 150 per playlist).
    """
    if not API_KEY:
        print("[ERROR] YOUTUBE_API_KEY not set in backend/.env")
        return []

    video_ids = []
    next_page_token = None

    while True:
        params = {
            "part":       "contentDetails",
            "playlistId": playlist_id,
            "maxResults": 50,
            "key":        API_KEY,
        }
        if next_page_token:
            params["pageToken"] = next_page_token

        try:
            resp = requests.get(
                "https://www.googleapis.com/youtube/v3/playlistItems",
                params=params, timeout=10
            )
            resp.raise_for_status()
            data = resp.json()
        except requests.exceptions.RequestException as e:
            print(f"  [ERROR] Playlist fetch failed: {e}")
            break

        for item in data.get("items", []):
            vid = item["contentDetails"].get("videoId", "")
            if vid:
                video_ids.append(vid)

        next_page_token = data.get("nextPageToken")
        if not next_page_token:
            break
        time.sleep(0.5)

    return video_ids


def get_video_metadata_batch(video_ids: list[str]) -> list[dict]:
    """
    Fetches snippet + contentDetails for up to 50 video IDs per API call.
    Batching saves quota — 1 unit per call instead of 50.
    """
    if not video_ids:
        return []

    results = []
    # YouTube API accepts up to 50 IDs per videos.list call
    for i in range(0, len(video_ids), 50):
        batch = video_ids[i:i + 50]
        params = {
            "part": "snippet,contentDetails",
            "id":   ",".join(batch),
            "key":  API_KEY,
        }
        try:
            resp = requests.get(
                "https://www.googleapis.com/youtube/v3/videos",
                params=params, timeout=10
            )
            resp.raise_for_status()
            items = resp.json().get("items", [])
        except requests.exceptions.RequestException as e:
            print(f"  [ERROR] Video metadata fetch failed: {e}")
            continue

        for item in items:
            snippet = item.get("snippet", {})
            tags    = snippet.get("tags", []) or []
            results.append({
                "video_id":    item["id"],
                "title":       snippet.get("title", ""),
                "description": snippet.get("description", "")[:500],
                "tags":        " ".join(tags),
                "channel":     snippet.get("channelTitle", ""),
            })

        time.sleep(0.3)   # stay well within rate limits

    return results


def collect_handpicked():
    os.makedirs("data/raw", exist_ok=True)

    # ── Validate playlist IDs are set ─────────────────────────────────────────
    placeholder = "PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    unconfigured = [cls for cls, pid in PLAYLIST_IDS.items() if pid == placeholder]
    if unconfigured:
        print("[ERROR] You haven't set playlist IDs for these classes:")
        for cls in unconfigured:
            print(f"         {cls}")
        print("\nHow to get your playlist ID:")
        print("  1. Open your YouTube playlist in a browser")
        print("  2. Copy the ID from the URL: youtube.com/playlist?list=<THIS_PART>")
        print("  3. Paste it into PLAYLIST_IDS at the top of this script")
        return

    all_rows    = []
    label_counts = {}

    for label, playlist_id in PLAYLIST_IDS.items():
        print(f"\n[COLLECT] ── {label} playlist: {playlist_id}")

        # Step 1: get video IDs from playlist
        print(f"[COLLECT]   Fetching video IDs...")
        video_ids = get_playlist_video_ids(playlist_id)
        print(f"[COLLECT]   Found {len(video_ids)} videos in playlist")

        if not video_ids:
            print(f"[COLLECT]   ⚠ No videos found — check playlist ID and visibility")
            continue

        # Warn if playlist doesn't have the target count
        if len(video_ids) < TARGET_PER_CLASS:
            print(f"[COLLECT]   ⚠ Only {len(video_ids)} videos (target: {TARGET_PER_CLASS})")
        elif len(video_ids) > TARGET_PER_CLASS:
            print(f"[COLLECT]   Using first {TARGET_PER_CLASS} of {len(video_ids)} videos")
            video_ids = video_ids[:TARGET_PER_CLASS]

        # Step 2: fetch metadata in batches of 50
        print(f"[COLLECT]   Fetching metadata for {len(video_ids)} videos...")
        metadata_list = get_video_metadata_batch(video_ids)
        print(f"[COLLECT]   ✓ Retrieved metadata for {len(metadata_list)} videos")

        # Step 3: build rows with trust metadata
        for meta in metadata_list:
            all_rows.append({
                "video_id":    meta["video_id"],
                "title":       meta["title"],
                "description": meta["description"],
                "tags":        meta["tags"],
                "channel":     meta["channel"],
                "query_used":  f"handpicked_{label.lower()}",
                "label":       label,
                "trust_level": "handpicked",   # ← marks highest-trust source
            })

        label_counts[label] = len(metadata_list)

    if not all_rows:
        print("\n[COLLECT] ✗ No data collected. Check your playlist IDs and API key.")
        return

    # ── Write output CSV ───────────────────────────────────────────────────────
    fieldnames = ["video_id", "title", "description", "tags", "channel",
                  "query_used", "label", "trust_level"]
    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(all_rows)

    print(f"\n[COLLECT] ══════════════════════════════════════")
    print(f"[COLLECT] ✓ {len(all_rows)} handpicked videos saved → {OUTPUT_PATH}")
    print(f"[COLLECT] Label distribution:")
    for label, count in label_counts.items():
        print(f"          {label:>20}: {count:>4}")
    print(f"[COLLECT] ══════════════════════════════════════")
    print(f"[COLLECT] Next step: python merge_datasets.py")


if __name__ == "__main__":
    collect_handpicked()
