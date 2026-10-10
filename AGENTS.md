# Agent Instructions

Class timetable product ("Colink"): **one class, one timetable**. Spreadsheet import backend + Android student client + Web admin console.

## Hard Rules

These override convenience. Violating them is always wrong.

- Do not resurrect the deleted root-level Android tree (`app/`, root Gradle files). Live Android project is `frontend/user/` only.
- Do not commit credentials, JWT secrets, `*.db`, venvs, build caches, `local.properties`, uvicorn/dev-server logs, or anything under `samples/`.
- Do not publish or commit the real sample workbook. Tests that need it must skip when missing.
- Never run a long-lived server (uvicorn, Vite, Gradle daemon, emulator) in a blocking foreground shell command. Always manage such processes with `better-bash` tools (`bash_start`/`bash_status`/`bash_kill`/`bash_list`) or `backend/scripts/dev-server.ps1` — see [Long-running Services](#long-running-services).
- Never silently guess ambiguous import / week rules. Surface a structured warning or review state instead.
- Never silently replace an existing schedule, convert parse failures into empty success, or invent missing semester metadata.
- Do not rewrite `docs/milestones.md` or other historical planning docs unless asked.
- Preserve unrelated working-tree state. Check `git status` before edits; do not revert user changes.
- Git commit messages are Chinese: `type(scope): 中文描述`. Do not commit unless asked.

## Goal

1. **Backend** (`backend/`): import class-timetable spreadsheets (`.xls`/`.xlsx`), validate + preview parsed rows, persist confirmed schedule, expose HTTP APIs.
2. **Android student client** (`frontend/user/`, Kotlin + Jetpack Compose, "Colink"): simplified read-only schedule UI — pick a class from `GET /classes`, then load that class's timetable via `GET /classes/{id}/schedule`.
3. **Web admin console** (`frontend/admin/`, Vite + React + TS): import review loop (upload → preview → select rows → confirm) and read-only schedule inspection.

Admin login (username/password + JWT) protects `/imports*` and class-write endpoints. Student-side accounts are **not** in scope.

## Scope

**In scope (when requested):** backend setup; workbook readers and source-layout profiles; extraction / normalization / validation / import lifecycle; HTTP API, DB models, migrations, tests; backend docs; Android work under `frontend/user/`; admin console work under `frontend/admin/`; admin auth for import APIs.

**Out of scope (unless explicitly asked):** quizzes, social, notifications, other product epics; cloud deployment; student accounts; external service integrations beyond the requested task; destructive changes to user data, files, git history, or the root-level Android deletions.

## Repository Layout

| Path | Role |
|------|------|
| `backend/` | Python package `class_table_backend` (src layout), pytest, Alembic |
| `frontend/user/` | Android student app (independent Gradle root, module `app/`, namespace `com.colink.app`) |
| `frontend/admin/` | Vite + React 18 + TS admin SPA (HashRouter; static `dist/`) |
| `docs/` | Milestones / proposal / compose specs & plans (historical where they conflict with current direction) |
| `samples/` | Local-only real workbooks; gitignored |
| `scripts/` | Helper scripts (e.g. PDF build) |

Backend modules under `backend/src/class_table_backend/`:

- `parsing/` — workbook I/O, normalize, course-line / period / week expressions, `profiles/`
- `domain/` — models, issues, validation
- `import_flow/` — import lifecycle service
- `persistence/` — SQLAlchemy tables, repositories, Alembic migrations
- `api/` — FastAPI app, routes, schemas, deps
- `auth/` — admin users, password hash, JWT, bootstrap

Dependency direction: `api → import_flow → (parsing | domain | persistence)`. Parsing is independent of HTTP and DB.

## Technical Direction

- Python 3.12 + `pyproject.toml`. FastAPI, Pydantic, SQLAlchemy 2, Alembic, pytest, ruff. Add deps only for a concrete need.
- `DATABASE_URL` via environment; never commit credentials. Keep persistence portable between SQLite (local tests) and PostgreSQL (deploy).
- One school/export format = one explicit source-layout profile. Current profile: `chengdu_wenli_v1`. No plugin framework in v1.
- Keep workbook I/O, layout interpretation, week parsing, validation, API schemas, and persistence in separate modules.
- Typed functions and domain-specific result/error types (`domain.issues.Issue` / `IssueCode`).
- Admin auth: `admin_user` table; password = stdlib `pbkdf2_hmac('sha256')` (format `pbkdf2_sha256$<iter>$<salt_hex>$<hash_hex>`, no bcrypt/passlib); JWT HS256 via PyJWT. Bootstrap from `ADMIN_BOOTSTRAP_USERNAME`/`ADMIN_BOOTSTRAP_PASSWORD` (create-if-missing, never overwrite). No registration endpoint.
- Admin SPA: HashRouter (`#/login`, `#/import`, `#/import/:importId`, `#/schedule`) so static `dist/` deep-links work. 401 must clear both `sessionStorage` and React auth state.

## Spreadsheet Import

- Support `.xls` via `xlrd` and `.xlsx` via `openpyxl`. Choose the reader from **detected** file format, not extension/MIME alone.
- Do not use `pandas.read_excel()` as the sole timetable parser. Merged cells, coordinates, and multiline cell contents carry meaning.
- Map weekdays from headers and merged ranges; parse every nonempty course cell and every nonempty line in it. Keep provenance: sheet name, cell coordinate, raw cell text, line index.
- Do not infer semester start date, academic year, max week, class identity, or room semantics from unlabeled values. Require explicit metadata or user configuration; otherwise return a structured error.
- `max_week` defaults to the maximum upper bound of parsed week ranges; allow import-time override. Never hard-code 16/17/18.
- Do not execute macros, formulas, embedded content, or workbook links. Enforce upload size limits; use safe temp files and delete them after parsing.

## Week Expressions

- Never hard-code example ranges (`1-9`, `1-16`, …). Examples live only in tests and docs; parsing derives bounds from input.
- Normalize Unicode/punctuation conservatively → tokenize → parse to structured ranges → validate against the selected semester's week bounds → expand only when needed.
- Canonical representation: list of `{start, end, parity}` with parity `ALL` | `ODD` | `EVEN`, plus the **original week text** preserved for audit.
- **Verified rule:** a trailing `单` / `双` (as in `…周单` / `…周双`, e.g. `2-4,8-16周双`) applies to the **entire** preceding week list, not just the last item.
- Ambiguous or unsupported text (`单双周` alone, `隔周`, `前半学期`, `按通知`, holiday adjustments, …) → structured warning / `NEEDS_REVIEW`. Do not guess scope.
- Make punctuation, aliases, and parity-scope rules configurable per source profile. Avoid one ever-growing regex or an "accept anything" heuristic.
- Parsing ≠ validation: a syntactically parsed expression may still be invalid for the chosen semester. Empty expansions must not silently succeed.
- Tests must cover: arbitrary bounds, single week, range, disjoint ranges, odd/even, punctuation variants, reversed/out-of-range, empty input, ambiguous text.

## Domain and Persistence

- Schedule model: **one class owns exactly one timetable**. Class is a first-class entity; courses/meetings belong to a class (`course.class_id`). Import targets a class (`class_id` or `class_name`, auto-create on confirm). Never silently replace an existing class schedule — `confirm` requires explicit `replace=true` (else `409 SCHEDULE_EXISTS`).
- A meeting captures weekday, start/end period, week rules, and source provenance. Keep multiple room lines; do not silently drop or dedupe source lines.
- Preserve import batch and row-level status (parse errors, validation errors, warnings, user decisions) so failed rows stay visible and retry is safe.
- Import lifecycle: `UPLOADED → PARSED/NEEDS_REVIEW → CONFIRMED` or `FAILED`. **Preview must not write confirmed schedule rows.**
- Confirm writes selected rows in **one transaction**. Define duplicate-file and existing-schedule behavior explicitly; never silently replace a schedule. Prefer file hash / idempotency key so retries do not duplicate data.
- Do not store weeks as JSON-only if queries filter by week. Keep normalized week ranges (or a meeting-week relation); retain original text.

## API and Access Control

- Keep parsing independent of HTTP so it can be reused from scripts/jobs.
- Import API is three steps: upload/parse → preview → confirm. Return stable import IDs, row IDs, structured issue codes, and source coordinates.
- Confirm accepts client-supplied `row_ids` (`null`/omitted = all selectable rows).
- Return actionable errors for unsupported types, malformed workbooks, unknown layouts, invalid rows, ambiguous week rules. Never map parse failure to zero weeks / empty schedule / success.
- Apply request/file size limits and safe filename handling. Do not log raw workbook contents or unnecessary student identifiers.
- Access control (settled):
  - `POST /auth/login`, `GET /health`, `GET /classes`, `GET /classes/{id}/schedule` — public (student client).
  - `/imports*`, `/classes` write (`POST`/`PATCH`/`DELETE`), and `GET /auth/me` — require admin `Authorization: Bearer <token>`.
  - Do not add student-side accounts unless asked. Never put credentials or JWT secrets in the repo.

## Local Commands

```bash
# Backend tests + lint
cd backend
.venv/bin/python.exe -m pytest
.venv/bin/ruff.exe check src tests

# Alembic
.venv/bin/python.exe -m alembic upgrade head

# Admin bootstrap (first run)
$env:ADMIN_BOOTSTRAP_USERNAME="admin"
$env:ADMIN_BOOTSTRAP_PASSWORD="change-me"
$env:ADMIN_JWT_SECRET="change-me-too"   # ≥32 bytes in real use
```

Admin console: `cd frontend/admin && npm install && npm run dev` (or `npm test`, `npx tsc -b`, `npm run build`).  
Android: open `frontend/user/` in Android Studio, or `cd frontend/user && ./gradlew :app:assembleDebug`.

Environment notes: `uv` is not used here; run pytest via the backend venv python. Real device LAN access needs the backend bound to `0.0.0.0` and the PC's LAN IP (emulator uses `http://10.0.2.2:8000`).

## Long-running Services

Never start uvicorn / Vite / Gradle daemon / emulator in a blocking foreground shell command — the tool call hangs and the agent cannot continue.

**Mandatory:** manage every long-lived process with the `better-bash` tools (`bash_start` / `bash_status` / `bash_kill` / `bash_list`). Do not use blocking `bash` / `Bash` for servers, watchers, or any command that does not exit on its own.

- Start a service with `bash_start` (e.g. `bash_start("python -m uvicorn ...")`). The call returns immediately with an `id`/`pid`.
- Poll readiness/output with `bash_status` (optional long-poll `wait`). Do other work while it runs.
- Stop with `bash_kill` when the task that needed the live service is done. Also kill before starting another copy on the same port.
- Inventory leftovers with `bash_list`; kill anything still listening that the current task does not need.
- Preferred backend helper (still non-blocking, equivalent to `bash_start`): `.\backend\scripts\dev-server.ps1 start` / `stop` / `status`. Use it for the class-table backend when available; otherwise `bash_start` the uvicorn command directly.
- One-off HTTP checks: `curl`/`Invoke-WebRequest` after start, then `bash_kill` (or `dev-server.ps1 stop`). Do not attach to the server process or wait on it in the foreground shell.
- Temporary logs / pid files (`.dev-server.pid`, `.dev-server.out.log`, `.dev-server.err.log`) are gitignored local artifacts — do not commit them.

## Verification

- Unit tests for normalizers / parsers / validators; fixture tests for layout adapters; API + DB integration tests for preview, confirm, rollback, duplicate upload, retry.
- Generate synthetic `.xls`/`.xlsx` fixtures in tests. Prefer them over the real sample. Sample-dependent tests use per-test skip when the file is missing — not a module-level skip.
- Assert: preview has no persistence side effects; confirm is atomic; invalid rows stay visible; retrying a confirmed import does not duplicate data.
- Run the relevant tests and lint/type checks after changes. Report commands and results; do not claim success from code inspection alone. Note any check that could not run.

## Documentation and Communication

- Update backend setup / API docs when backend behavior changes. Do not rewrite stale product planning docs unless asked.
- User-facing explanations and docs in Chinese (match the user). Identifiers, API fields, and code symbols in conventional English.
- Before implementing a materially ambiguous import rule, state the uncertainty and choose a reviewable failure state rather than guessing.
- Handoff summaries: implemented behavior, tests run, unresolved format assumptions, migration impact.
