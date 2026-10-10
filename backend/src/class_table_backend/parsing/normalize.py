from __future__ import annotations

import unicodedata

_PUNCTUATION_MAP = str.maketrans(
    {
        "－": "-",  # fullwidth hyphen-minus
        "‐": "-",  # hyphen
        "‑": "-",  # non-breaking hyphen
        "‒": "-",  # figure dash
        "–": "-",  # en dash
        "—": "-",  # em dash
        "―": "-",  # horizontal bar
        "～": "-",  # fullwidth tilde used as range separator
        "~": "-",  # ASCII tilde after NFKC of fullwidth tilde
        "〜": "-",  # wave dash
        "，": ",",  # fullwidth comma
        "、": ",",  # ideographic comma used as list separator
        "；": ",",  # fullwidth semicolon treated as segment separator
        ";": ",",  # semicolon treated as segment separator
    }
)


def normalize_expression_text(text: str) -> str:
    """Normalize week/period expression text for parsing.

    Applies NFKC, maps fullwidth and dash/comma punctuation to ASCII,
    and strips surrounding whitespace. Chinese week modifiers such as
    单周/双周 are preserved.
    """
    if not text:
        return ""
    normalized = unicodedata.normalize("NFKC", text)
    normalized = normalized.translate(_PUNCTUATION_MAP)
    return normalized.strip()
