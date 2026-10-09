# Agent Instructions

## Current Goal

Build a class timetable product with three parts:

1. A Python backend for importing class timetable spreadsheets, validating and previewing the parsed schedule, and persisting confirmed data to a database.
2. An Android student client under `frontend/user/` (Kotlin + Jetpack Compose, "Colink") that is simplified and integrated with the backend schedule API.
3. A Web admin console under `frontend/admin/` (Vite + React + TypeScript) for the import review loop (upload → preview → select rows → confirm) and read-only schedule inspection.

Scope note: as of the user's explicit 2026-10-09 decision, Android work is in scope again. The earlier "backend-only / never restore Android" rule is retired. The Android project lives in `frontend/user/` and is the source of truth for the student client; do not resurrect the deleted root-level `app/` tree from git history unless asked. As of the 2026-10-10 admin-console decision, admin login (username/password + JWT) is in scope for protecting `/imports*`; student-side accounts are not.

## Repository Context

- The backend lives under `backend/`; the Android student client lives under `frontend/user/`; the Web admin console lives under `frontend/admin/`.
- `docs/milestones.md` describes an older Android/product plan. Treat it as historical context where it conflicts with current direction. Do not rewrite it unless asked.
- Root-level Android/Gradle files remain deleted in the worktree. Leave those deletions in place; the live Android project is `frontend/user/`.
- An earlier Excel example was inspected as a legacy `.xls` timetable: a formatted weekday grid with merged cells and multiline course entries. That file is not present in the current checkout. Do not recover deleted files or make tests depend on that original workbook. Use synthetic, anonymized fixtures unless the user supplies a fixture.

## Scope

Allowed work when requested:

- Python backend project setup and configuration.
- `.xls` / `.xlsx` readers and source-layout adapters.
- Timetable extraction, normalization, validation, import preview, and confirmation workflows.
- HTTP API, database models, migrations, repositories, and tests for this import workflow.
- Backend documentation and local development instructions.
- Android student client work under `frontend/user/`: simplification, UI, local data, and HTTP integration with the backend schedule API.
- Web admin console work under `frontend/admin/`: login, import upload/preview/review/confirm UI, read-only schedule view.
- Admin authentication for the import APIs (username/password, JWT, `admin_user` persistence) when requested as part of the admin console.

Out of scope unless the user explicitly asks:

- Quizzes, social features, notifications, or unrelated product epics.
- Cloud deployment, account/authentication design, or external service integrations beyond what is necessary for the requested backend or Android task.
- Destructive changes to user data, user files, Git history, or the existing root-level Android deletions.

## Technical Direction

- Use Python with a `pyproject.toml`-managed project. Prefer FastAPI for HTTP endpoints, Pydantic for API/domain-boundary validation, SQLAlchemy 2 for persistence, Alembic for migrations, and pytest for tests unless the repository or user chooses another stack.
- Keep database configuration external through environment variables such as `DATABASE_URL`; never commit credentials. Make the persistence layer portable between SQLite for local tests and PostgreSQL for deployment where practical.
- Keep dependencies and abstractions proportional to the feature. Do not add microservices, queues, auth, or a plugin framework without a concrete requirement.
- Use typed functions and domain-specific result/error types. Keep workbook I/O, source-layout interpretation, week-expression parsing, validation, API schemas, and persistence in separate modules with clear boundaries.

## Spreadsheet Import Rules

- Support `.xls` with `xlrd` and `.xlsx` with `openpyxl`. Select the reader from the detected file format; do not treat the extension or MIME type alone as proof of file contents.
- Do not use `pandas.read_excel()` as the sole parser for timetable grids. Merged cells, coordinates, and multiline cell contents carry meaning and must be retained.
- Treat each supported school/export format as an explicit source-layout profile. Map weekdays from headers and merged ranges; parse each nonempty course cell and each nonempty line within it. Keep sheet name, cell coordinate, raw cell text, and line index as provenance.
- Do not infer semester start dates, academic year, maximum week, class identity, or room semantics from unlabeled values. Require explicit metadata or user-provided configuration when the workbook is ambiguous.
- Do not execute macros, formulas, embedded content, or workbook links. Enforce upload size limits, use safe temporary-file handling, and remove temporary files after parsing.

## Week-Expression Parsing

- Never hard-code example ranges such as `1-9` or `1-16`. Examples belong in tests and documentation only; parsing must derive numeric boundaries from input.
- Normalize Unicode and punctuation conservatively, tokenize the expression, parse it into a structured representation, validate against the selected semester's configured week bounds, and expand to concrete weeks only when needed.
- Preserve the original week text alongside parsed ranges. A useful canonical representation is a list of `{start, end, parity}` ranges, where parity is `ALL`, `ODD`, or `EVEN`.
- Support only deterministic syntax whose meaning is defined by a source profile. Do not guess the scope of a trailing `单双周` marker, or the meaning of phrases such as `隔周`, `前半学期`, `按通知`, or holiday adjustments. Return a structured warning/review status for ambiguous or unsupported text.
- Make punctuation, aliases, and parity-scope rules configurable per source profile when exports differ. Avoid a single ever-growing regex or an unrestricted “accept anything” heuristic.
- Keep parsing and validation separate. A syntactically parsed expression may still be invalid for the chosen semester.
- Test arbitrary bounds, single weeks, ranges, disjoint ranges, odd/even rules, punctuation variants, reversed/out-of-bound ranges, empty input, and ambiguous/unknown text. Include the actual school-format rules only after they are verified.

## Domain and Persistence

- Model an imported schedule as a semester/class schedule containing logical courses and one or more meeting occurrences.
- A meeting occurrence should capture at least weekday, start/end period, week rules, and source provenance. Support multiple room values without silently dropping or deduplicating source lines.
- Preserve import batch and row-level status so users can inspect parse failures and retry safely. Distinguish parse errors, validation errors, warnings, and user decisions.
- Use an explicit import lifecycle such as `UPLOADED -> PARSED/NEEDS_REVIEW -> CONFIRMED` or `FAILED`. Preview must not write confirmed schedule rows.
- On confirmation, write the selected rows and related records in a database transaction. Define duplicate-file and existing-schedule behavior explicitly; never silently replace a schedule. Prefer an idempotency key or file hash for safe retries.
- Do not use JSON-only week storage as the sole representation if queries need to filter by week. Keep normalized week ranges or a meeting-week relation as appropriate, while retaining the original text for auditability.

## API and Failure Behavior

- Keep parsing independent of HTTP so the parser can be tested and reused from scripts or jobs.
- For an import API, prefer separate upload/parse, preview, and confirm operations. Return stable import IDs, row IDs, structured issue codes, and source coordinates.
- Return actionable errors for unsupported file types, malformed workbooks, unrecognized layouts, invalid rows, and ambiguous week rules. Never silently convert parse failures to zero weeks, empty schedules, or successful imports.
- Apply request/file size limits and safe filename handling. Do not log raw workbook contents or unnecessary student identifiers.
- Access control (settled 2026-10-10): `/imports*` requires a valid admin Bearer token; `GET /schedule`, `GET /health`, and `POST /auth/login` stay public for the student client. Do not add student-side accounts unless asked. Never put credentials or JWT secrets in the repository.

## Verification

- Add unit tests for normalizers, parsers, and validators; fixture tests for workbook layout adapters; and API/database integration tests for preview, confirmation, rollback, duplicate uploads, and retry behavior.
- Generate synthetic `.xls` / `.xlsx` fixtures in tests when the original sample is unavailable. Do not add private timetable contents to the repository without explicit authorization.
- Verify that preview has no persistence side effects, confirmation is atomic, invalid rows remain visible, and retrying a confirmed import does not duplicate data.
- Run the relevant test suite and lint/type checks after changes. Report commands run and any unavailable checks; do not claim success based only on code inspection.
- Preserve unrelated working-tree state. Before edits, inspect `git status`; do not revert or overwrite pre-existing user changes.

## Documentation and Communication

- Update backend setup/API docs when changing backend behavior. Do not rewrite stale product planning docs unless requested.
- Prefer Chinese for user-facing explanations and documentation, matching the user's language. Keep identifiers, API fields, and code symbols in conventional English.
- Before implementing a materially ambiguous import rule, make the uncertainty explicit and choose a reviewable failure state rather than silently guessing.
- In handoff summaries, state the implemented behavior, tests run, unresolved format assumptions, and any database migration impact.
