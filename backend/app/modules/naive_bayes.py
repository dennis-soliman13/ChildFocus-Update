"""
ChildFocus - Naïve Bayes Classifier Module
backend/app/modules/naive_bayes.py

Sprint 2 — Metadata-based probabilistic classification.

Loads pre-trained model from ml_training/outputs/:
  - nb_model.pkl    : trained MultinomialNB or ComplementNB classifier
  - vectorizer.pkl  : fitted TF-IDF vectorizer

Pipeline (per manuscript Chapter 2 + Figure 2):
  Input metadata (title, description, tags)
      ↓
  Text cleaning + tokenization
      ↓
  Stop-word removal
      ↓
  TF-IDF feature vectorization
      ↓
  Score_NB = (1/Z) × [log P(C_over) + Σ log P(token | C_over)]
      ↓
  Logistic normalization → Score_NB ∈ [0, 1]

Score_NB is then consumed by hybrid_fusion.py:
  Score_final = α × Score_NB + (1 - α) × Score_H
  where α = 0.4 (metadata weight, per manuscript)
"""

import os
import re
import pickle
import logging
from dataclasses import dataclass
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

# ── Model paths ────────────────────────────────────────────────────────────────
_BASE_DIR      = os.path.dirname(os.path.abspath(__file__))
_OUTPUTS_DIR   = os.path.normpath(
    os.path.join(_BASE_DIR, "..", "..", "..", "ml_training", "outputs")
)
MODEL_PATH      = os.path.join(_OUTPUTS_DIR, "nb_model.pkl")
VECTORIZER_PATH = os.path.join(_OUTPUTS_DIR, "vectorizer.pkl")

# ── Class label → overstimulation index mapping ───────────────────────────────
LABEL_TO_IDX = {
    "Educational":     0,
    "Neutral":         1,
    "Overstimulating": 2,
}

OVERSTIM_CLASS = "Overstimulating"

# ── Stop words (lightweight, no NLTK dependency) ──────────────────────────────
_STOP_WORDS = {
    "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "is", "it", "this", "that", "was", "are",
    "be", "as", "at", "so", "we", "he", "she", "they", "you", "i", "my",
    "your", "his", "her", "its", "our", "their", "what", "which", "who",
    "will", "would", "could", "should", "has", "have", "had", "do", "does",
    "did", "not", "no", "if", "then", "than", "when", "where", "how",
    "all", "each", "more", "also", "just", "can", "up", "out", "about",
    "into", "than", "too", "very", "s", "t", "re", "ve", "ll", "d",
}


# ── Output dataclass ───────────────────────────────────────────────────────────
@dataclass
class NBResult:
    """Result from Naïve Bayes metadata classification."""
    score_nb:        float
    predicted_label: str
    confidence:      float
    probabilities:   dict
    text_used:       str
    model_loaded:    bool = True
    error:           Optional[str] = None


# ── Model loader (singleton) ──────────────────────────────────────────────────
class _ModelCache:
    """
    Loads nb_model.pkl and vectorizer.pkl once and caches them.
    Thread-safe for concurrent Flask requests.
    """
    _model         = None
    _vectorizer    = None
    _classes       = None
    _label_encoder = None   # sklearn LabelEncoder if saved in pkl dict
    _label_names   = None   # ['Educational', 'Neutral', 'Overstimulating']
    _loaded        = False
    _error         = None

    @classmethod
    def load(cls) -> bool:
        if cls._loaded:
            return cls._error is None
        try:
            if not os.path.exists(MODEL_PATH):
                raise FileNotFoundError(f"Model not found: {MODEL_PATH}")
            if not os.path.exists(VECTORIZER_PATH):
                raise FileNotFoundError(f"Vectorizer not found: {VECTORIZER_PATH}")

            with open(MODEL_PATH, "rb") as f:
                raw = pickle.load(f)

            # ── Unwrap if saved as a dict (e.g. {"model": clf, ...}) ──────────
            if isinstance(raw, dict):
                cls._model = (
                    raw.get("model")      or
                    raw.get("classifier") or
                    raw.get("nb")         or
                    next(iter(raw.values()))
                )
                # Pull label encoder and label names if present
                cls._label_encoder = raw.get("label_encoder")
                cls._label_names   = raw.get("label_names")  # e.g. ['Educational','Neutral','Overstimulating']
                print(f"[NB] ✓ Unwrapped model from dict. Keys: {list(raw.keys())}")
                print(f"[NB] ✓ Label names: {cls._label_names}")
            else:
                cls._model = raw

            with open(VECTORIZER_PATH, "rb") as f:
                cls._vectorizer = pickle.load(f)

            # Validate sklearn interface
            if not hasattr(cls._model, "classes_"):
                raise AttributeError(
                    f"Model has no 'classes_'. Type: {type(cls._model)}. "
                    f"Check dict keys in nb_model.pkl."
                )

            cls._classes = list(cls._model.classes_)
            cls._loaded  = True
            cls._error   = None
            logger.info(f"[NB] Model loaded. Classes: {cls._classes}")
            print(f"[NB] ✓ Model loaded from {_OUTPUTS_DIR}")
            print(f"[NB] ✓ Classes: {cls._classes}")
            return True

        except Exception as e:
            cls._error  = str(e)
            cls._loaded = True
            logger.error(f"[NB] Failed to load model: {e}")
            print(f"[NB] ✗ Model load failed: {e}")
            return False

    @classmethod
    def get(cls):
        cls.load()
        return cls._model, cls._vectorizer, cls._classes, cls._error


# ── Text preprocessing ────────────────────────────────────────────────────────
def preprocess_text(title: str = "", description: str = "", tags: list = None) -> str:
    """
    Clean and combine metadata fields into a single text string.

    Pipeline:
      1. Combine title (weighted 3x) + tags (weighted 2x) + description
      2. Lowercase
      3. Remove URLs, special characters, numbers
      4. Remove stop words
      5. Collapse whitespace
    """
    tags = tags or []

    title_text = f"{title} " * 3
    tags_text  = f"{' '.join(tags)} " * 2
    desc_text  = description[:300] if description else ""

    raw  = f"{title_text}{tags_text}{desc_text}"
    text = raw.lower()
    text = re.sub(r"https?://\S+|www\.\S+", " ", text)
    text = re.sub(r"[^a-z\s]", " ", text)

    tokens = [t for t in text.split() if t not in _STOP_WORDS and len(t) > 1]
    return " ".join(tokens)


# ── Logistic normalization ────────────────────────────────────────────────────
def _logistic(x: float) -> float:
    return float(1.0 / (1.0 + np.exp(-x)))


def _normalize_score(proba_overstim: float) -> float:
    """
    Normalize raw overstimulation probability to Score_NB ∈ [0, 1].
    Applies logistic stretch to sharpen separation between classes.
    """
    stretched = (proba_overstim - 0.5) * 6.0
    return round(float(np.clip(_logistic(stretched), 0.0, 1.0)), 4)


# ── Main inference function ───────────────────────────────────────────────────
def score_metadata(
    title:       str  = "",
    description: str  = "",
    tags:        list = None,
) -> NBResult:
    """
    Classify video metadata and return Score_NB ∈ [0, 1].

    Score_NB represents the overstimulation likelihood from metadata alone.
    Higher = more likely to be overstimulating based on title/tags/description.
    """
    tags = tags or []

    model, vectorizer, classes, load_error = _ModelCache.get()

    if load_error or model is None:
        print(f"[NB] ⚠ Model unavailable, returning neutral score. Error: {load_error}")
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.0,
            probabilities   = {},
            text_used       = "",
            model_loaded    = False,
            error           = load_error,
        )

    cleaned_text = preprocess_text(title, description, tags)

    if not cleaned_text.strip():
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.33,
            probabilities   = {c: 0.33 for c in classes},
            text_used       = cleaned_text,
            error           = "Empty metadata after preprocessing",
        )

    try:
        X     = vectorizer.transform([cleaned_text])
        proba = model.predict_proba(X)[0]   # shape: (n_classes,)
        pred  = model.predict(X)[0]         # integer index (0, 1, 2)

        # ── Resolve human-readable label names ────────────────────────────────
        # Model was trained with integer-encoded labels (0, 1, 2).
        # Use label_names from the pkl dict to map back to strings.
        label_names = _ModelCache._label_names or ["Educational", "Neutral", "Overstimulating"]

        # Map integer prediction → string label
        pred_int        = int(pred)
        predicted_label = label_names[pred_int] if pred_int < len(label_names) else str(pred)

        # Build probability dict with string keys {label: probability}
        prob_dict = {
            label_names[i] if i < len(label_names) else str(c): round(float(p), 4)
            for i, (c, p) in enumerate(zip(classes, proba))
        }

        # Get overstimulation probability using resolved label names
        overstim_idx   = label_names.index(OVERSTIM_CLASS) if OVERSTIM_CLASS in label_names else -1
        proba_overstim = float(proba[overstim_idx]) if overstim_idx >= 0 else 0.5

        score_nb = _normalize_score(proba_overstim)

        print(f"[NB] '{title[:40]}' → {predicted_label} | Score_NB={score_nb} | P(over)={proba_overstim:.3f}")

        return NBResult(
            score_nb        = score_nb,
            predicted_label = predicted_label,
            confidence      = round(float(np.max(proba)), 4),
            probabilities   = prob_dict,
            text_used       = cleaned_text[:200],
        )

    except Exception as e:
        logger.error(f"[NB] Inference error: {e}")
        print(f"[NB] ✗ Inference error: {e}")
        return NBResult(
            score_nb        = 0.5,
            predicted_label = "Neutral",
            confidence      = 0.0,
            probabilities   = {},
            text_used       = cleaned_text,
            model_loaded    = True,
            error           = str(e),
        )


# ── Convenience wrapper ───────────────────────────────────────────────────────
def score_from_metadata_dict(metadata: dict) -> NBResult:
    """
    Convenience wrapper accepting a dict from youtube_api.get_video_metadata().

    Usage:
        meta   = youtube_api.get_video_metadata(video_id)
        result = naive_bayes.score_from_metadata_dict(meta)
        score  = result.score_nb
    """
    return score_metadata(
        title       = metadata.get("title",       ""),
        description = metadata.get("description", ""),
        tags        = metadata.get("tags",        []),
    )


# ── Model status check ────────────────────────────────────────────────────────
def model_status() -> dict:
    """Returns model loading status. Used by /health endpoint."""
    model, _, classes, error = _ModelCache.get()
    return {
        "loaded":      model is not None,
        "model_path":  MODEL_PATH,
        "classes":     _ModelCache._label_names or classes or [],
        "error":       error,
    }
