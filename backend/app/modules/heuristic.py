"""
ChildFocus - Heuristic Analysis Module
backend/app/modules/heuristic.py

FIX: compute_heuristic_score() now accepts the pre-sampled dict from
     frame_sampler.sample_video() instead of calling sample_video() again.
     classify.py already calls sample_video() and passes the result here —
     calling it a second time caused: TypeError: expected string, got dict.
"""

"""
ChildFocus - Heuristic Analysis Module (High-Sensitivity Version)
"""

from app.modules.frame_sampler import (
    compute_fcr,
    compute_csv,
    compute_att,
    compute_thumbnail_intensity,
)

# ── High-Sensitivity Weights (Optimized for Overstimulation) ─────────────────
# Shifting weight toward FCR and ATT to detect "Brain Rot" patterns.
W_FCR   = 0.45  # Fast Cut Rate (Visual Pacing)
W_ATT   = 0.30  # Audio Transients (Auditory Pacing)
W_CSV   = 0.15  # Color Spatial Variance (Flash/Visual Noise)
W_THUMB = 0.10  # Thumbnail Intensity (Initial Impression)

# ── Thresholds ────────────────────────────────────────────────────────────────
THRESHOLD_HIGH = 0.30   # Overstimulating (Aligned with Fusion v2)
THRESHOLD_LOW  = 0.12   # Safe / Educational


def compute_heuristic_score(sample: dict) -> dict:
    """
    Computes the heuristic score using Peak Detection and Chaos Multipliers.
    """
    segments = sample.get("segments", [])
    thumb    = float(sample.get("thumbnail_intensity", 0.0))
    status   = sample.get("status", "success")

    if segments:
        seg_scores = []
        for seg in segments:
            if not seg: continue
            
            fcr = float(seg.get("fcr", 0.0))
            csv = float(seg.get("csv", 0.0))
            att = float(seg.get("att", 0.0))

            # ── Chaos Multiplier ─────────────────────────────────────────────
            # If visual or audio pacing is extremely high, amplify the score 
            # to ensure it breaks the Block threshold.
            multiplier = 1.25 if (fcr > 0.5 or att > 0.5) else 1.0
            
            raw_seg_score = (W_FCR * fcr + W_CSV * csv + W_ATT * att)
            seg_scores.append(round(raw_seg_score * multiplier, 4))

        if seg_scores:
            # ── Peak Detection Strategy ──────────────────────────────────────
            # We prioritize the most intense segment (90%) over the thumbnail (10%).
            max_seg = max(seg_scores)
            score_h = round(0.90 * max_seg + 0.10 * thumb, 4)
        else:
            score_h = round(W_THUMB * thumb, 4)
    else:
        score_h = round(W_THUMB * thumb, 4)

    score_h = round(min(1.0, max(0.0, score_h)), 4)

    return {
        "score_h": score_h,
        "details": {
            "segments": segments,
            "thumbnail_intensity": thumb,
            "status": status,
            "weights": {"fcr": W_FCR, "csv": W_CSV, "att": W_ATT, "thumb": W_THUMB}
        }
    }

def compute_segment_score(fcr: float, csv: float, att: float) -> float:
    """Calculates a single segment score with the Chaos Multiplier."""
    multiplier = 1.25 if (fcr > 0.5 or att > 0.5) else 1.0
    return round((W_FCR * fcr + W_CSV * csv + W_ATT * att) * multiplier, 4)

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
