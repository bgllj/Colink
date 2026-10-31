package com.colink.app.parsing

import java.text.Normalizer

private val PUNCTUATION_MAP: Map<Char, Char> = mapOf(
    '－' to '-', // fullwidth hyphen-minus
    '‐' to '-', // hyphen
    '‑' to '-', // non-breaking hyphen
    '‒' to '-', // figure dash
    '–' to '-', // en dash
    '—' to '-', // em dash
    '―' to '-', // horizontal bar
    '～' to '-', // fullwidth tilde used as range separator
    '~' to '-', // ASCII tilde after NFKC of fullwidth tilde
    '〜' to '-', // wave dash
    '，' to ',', // fullwidth comma
    '、' to ',', // ideographic comma used as list separator
    '；' to ',', // fullwidth semicolon treated as segment separator
    ';' to ',', // semicolon treated as segment separator
)

fun normalizeExpressionText(text: String): String {
    if (text.isEmpty()) return ""
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
    val mapped = buildString(normalized.length) {
        for (ch in normalized) {
            append(PUNCTUATION_MAP[ch] ?: ch)
        }
    }
    return mapped.trim()
}
