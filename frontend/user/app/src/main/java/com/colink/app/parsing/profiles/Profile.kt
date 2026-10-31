package com.colink.app.parsing.profiles

import com.colink.app.domain.Issue
import com.colink.app.domain.ParsedOccurrence
import com.colink.app.domain.SemesterMeta

data class ExtractionResult(
    var meta: SemesterMeta = SemesterMeta(),
    val occurrences: MutableList<ParsedOccurrence> = mutableListOf(),
    val issues: MutableList<Issue> = mutableListOf(),
)
