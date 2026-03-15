"""
ChildFocus - Heuristic Analysis Module
backend/app/modules/heuristic.py

FIX: compute_heuristic_score() now accepts the pre-sampled dict from
     frame_sampler.sample_video() instead of calling sample_video() again.
     classify.py already calls sample_video() and passes the result here —
     calling it a second time caused: TypeError: expected string, got dict.
"""

from app.modules.frame_sampler import (
    compute_fcr,
    compute_csv,
    compute_att,
    compute_thumbnail_intensity,
    extract_frames,
    fetch_video,
)

# ── Heuristic weights (from thesis) ───────────────────────────────────────────
W_FCR   = 0.35
W_CSV   = 0.25
W_ATT   = 0.20
W_THUMB = 0.20

# ── Thresholds (from thesis) ──────────────────────────────────────────────────
THRESHOLD_HIGH = 0.75   # Overstimulating
THRESHOLD_LOW  = 0.35   # Safe / Educational


def compute_heuristic_score(sample: dict) -> dict:
    """
    Compute the final heuristic score from a pre-sampled video dict.

    Accepts the dict already returned by frame_sampler.sample_video().
    Does NOT call sample_video() again — classify.py already did that.

    Args:
        sample: dict returned by sample_video(), containing:
                  - segments: list of dicts with fcr, csv, att, score_h
                  - thumbnail_intensity: float
                  - aggregate_heuristic_score: float
                  - status: "success" | "thumbnail_only" | "unavailable"

    Returns:
        dict with:
            score_h (float):  Aggregate heuristic score [0.0, 1.0]
            details (dict):   Segment breakdown + thumbnail for logging
    """
    segments = sample.get("segments", [])
    thumb    = float(sample.get("thumbnail_intensity", 0.0))
    status   = sample.get("status", "success")

    # Use pre-computed aggregate if available (fastest path)
    if "aggregate_heuristic_score" in sample:
        score_h = float(sample["aggregate_heuristic_score"])

    elif segments:
        seg_scores = []
        for seg in segments:
            if not seg:
                continue
            fcr = float(seg.get("fcr", 0.0))
            csv = float(seg.get("csv", 0.0))
            att = float(seg.get("att", 0.0))
            seg_scores.append(round(W_FCR * fcr + W_CSV * csv + W_ATT * att, 4))

        if seg_scores:
            max_seg = max(seg_scores)
            score_h = round(0.80 * max_seg + 0.20 * thumb, 4)
        else:
            score_h = round(W_THUMB * thumb, 4)

    else:
        score_h = round(W_THUMB * thumb, 4)

    score_h = round(min(1.0, max(0.0, score_h)), 4)

    details = {
        "segments":            segments,
        "thumbnail_intensity": thumb,
        "status":              status,
        "weights": {
            "fcr":   W_FCR,
            "csv":   W_CSV,
            "att":   W_ATT,
            "thumb": W_THUMB,
        }
    }

    return {"score_h": score_h, "details": details}


def compute_segment_score(fcr: float, csv: float, att: float) -> float:
    """
    Compute heuristic score for a single segment.
    Thesis formula: Score_H = (w1*FCR) + (w2*CSV) + (w3*ATT)
    """
    return round(
        (W_FCR * fcr) + (W_CSV * csv) + (W_ATT * att),
        4
    )


def _label_from_score(score: float) -> str:
    """Map a numeric score to an OIR label using thesis thresholds."""
    if score >= THRESHOLD_HIGH:
        return "Overstimulating"
    elif score <= THRESHOLD_LOW:
        return "Safe"
    else:
        return "Uncertain"


def get_feature_weights() -> dict:
    """Return the heuristic feature weights for transparency/logging."""
    return {
        "w_fcr":           W_FCR,
        "w_csv":           W_CSV,
        "w_att":           W_ATT,
        "w_thumb":         W_THUMB,
        "threshold_high":  THRESHOLD_HIGH,
        "threshold_low":   THRESHOLD_LOW,
    }