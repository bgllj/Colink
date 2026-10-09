# 仓库整理与公开发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将仓库整理为 frontend/backend/docs/samples/scripts 可分类 monorepo，统一忽略规则与中文文档命名，并创建公开 GitHub 仓库 `colink` 完成推送。

**Architecture:** 先提交整理前已有 backend 变更，再做纯文件迁移与路径修正，最后校验测试与目录、创建公开仓库并推送。不改写 git 历史，不恢复已删除的根级 Android 文件。

**Tech Stack:** Git、PowerShell、Python pytest（backend）、Gradle（frontend 路径完整性）、GitHub CLI (`gh`)

## Global Constraints

- **所有 git commit 描述使用中文。**
- 顶层目录名使用英文：`frontend/`、`backend/`、`docs/`、`samples/`、`scripts/`。
- 不上传真实课表样例：`samples/` 必须被 `.gitignore` 排除。
- 不提交：`*.db`、`uvicorn.log`、`uvicorn.err`、`local.properties`、`.venv*`、构建缓存、`.mimocode/`、`output/`。
- 不执行 `git push --force`、不改写历史、不恢复已删除的根级 `app/`、`gradle*` 文件。
- `backend/src/**` 代码结构不重构；仅允许为路径/测试可用性做最小修正。
- 样例路径修正后，依赖真实 xls 的测试在样例缺失时必须 **skip**，不得让公开仓库测试失败。
- 推送前必须用 `git status` / `git ls-files` 确认无样例课表与本机敏感文件。

---

### Task 1: 提交整理前已有的 backend 变更

**Covers:** [S6]（提交策略：先落盘已有 backend 变更）

**Files:**
- Modify: `backend/README.md`
- Modify: `backend/src/class_table_backend/api/app.py`
- Modify: `backend/src/class_table_backend/api/schemas.py`
- Modify: `backend/src/class_table_backend/persistence/repositories.py`
- Modify: `backend/tests/test_api.py`
- Create: `backend/src/class_table_backend/api/routes_schedule.py`
- Create: `backend/tests/test_schedule_api.py`

**Interfaces:**
- Consumes: 无（整理前已有工作区改动）
- Produces: 干净的 `git status`（仅剩未跟踪的整理材料），供 Task 2+ 在干净基线上迁移

- [ ] **Step 1: 查看待提交差异**

Run:
```powershell
git status --short
git diff --stat
git log --oneline -5
```
Expected: 看到上述 backend 文件的 `M` / `??` 变更；根级删除项仍保持删除。

- [ ] **Step 2: 运行 backend 测试确认基线**

Run:
```powershell
cd backend
uv run pytest -q
```
Expected: 全部通过（若环境无 `uv`，使用 `python -m pytest -q`，需已安装开发依赖）。

- [ ] **Step 3: 仅暂存 backend 变更并提交（中文）**

Run:
```powershell
git add backend/README.md backend/src/class_table_backend/api/app.py backend/src/class_table_backend/api/schemas.py backend/src/class_table_backend/persistence/repositories.py backend/tests/test_api.py backend/src/class_table_backend/api/routes_schedule.py backend/tests/test_schedule_api.py
git commit -m "feat(backend): 增加课表查询 API 与相关测试"
```
Expected: commit 成功；`git status` 不再显示这些文件为待提交。

- [ ] **Step 4: 确认未误提交本地产物**

Run:
```powershell
git status --short
```
Expected: 仍可见 `excel样例/`、`离线单板应用-beck/`、`AGENTS.md`、`backend/class_table.db`、`backend/uvicorn.*`、`.venv-tmp/` 等未跟踪项；**不得**出现已提交的 `*.db` / 日志。

---

### Task 2: 根级 .gitignore 与 samples 归位

**Covers:** [S3], [S4], [S8]

**Files:**
- Create: `.gitignore`
- Move: `excel样例/25计科9(1).xls` → `samples/excel/25计科9(1).xls`
- Modify: `backend/tests/test_api.py:14`
- Modify: `backend/tests/test_workbook_reader.py:15`
- Modify: `backend/tests/test_chengdu_wenli_v1_profile.py:8`
- Modify: `backend/tests/test_import_service.py:18`
- Modify: `backend/README.md`（样例路径说明）

**Interfaces:**
- Consumes: 无
- Produces: 统一根 `.gitignore`；`SAMPLE_PATH` 指向 `samples/excel/25计科9(1).xls`；样例缺失时仅样例用例 `@requires_sample` skip

- [ ] **Step 1: 创建根 `.gitignore`**

写入文件 `.gitignore`：

```gitignore
# Python
.venv/
.venv-ss/
.venv-tmp/
__pycache__/
*.py[cod]
.pytest_cache/
.ruff_cache/
*.egg-info/
dist/
build/

# Local data & logs
*.db
uvicorn.log
uvicorn.err

# Android / Gradle / IDE
.gradle/
.idea/
.kotlin/
**/build/
local.properties
*.iml
*.ipr
*.iws
captures/
.externalNativeBuild/
.cxx/

# Generated output
output/

# Project tooling
.mimocode/

# Local sample data (do not publish)
samples/

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 2: 移动样例到 samples/excel/**

Run:
```powershell
New-Item -ItemType Directory -Force samples/excel | Out-Null
Move-Item "excel样例/25计科9(1).xls" "samples/excel/25计科9(1).xls"
Remove-Item "excel样例" -Recurse -Force
```
Expected: `samples/excel/25计科9(1).xls` 存在；根下无 `excel样例/`。

- [ ] **Step 3: 修正测试中的 SAMPLE_PATH，并对样例用例增加缺失 skip**

统一将路径改为：

```python
SAMPLE_PATH = Path(__file__).resolve().parents[2] / "samples" / "excel" / "25计科9(1).xls"

requires_sample = pytest.mark.skipif(
    not SAMPLE_PATH.is_file(),
    reason="本地样例课表未提供（samples/excel 不入库）",
)
```

**不要**使用模块级 `pytestmark`（会误伤同文件中不依赖样例的用例）。仅给调用 `SAMPLE_PATH.read_bytes()` / `_sample_bytes()` / 默认样例 `_upload(client)` 的用例加 `@requires_sample`：

| 文件 | 需加 `@requires_sample` | 不要加 |
|------|-------------------------|--------|
| `backend/tests/test_api.py` | `test_upload_sample_returns_import_id`、`test_preview_returns_rows`、`test_list_rows_paginates`、`test_confirm_twice_keeps_meeting_count_stable`、`test_schedule_readable_after_confirm` | `test_unknown_import_returns_404`、`test_confirm_rejects_failed_batch` |
| `backend/tests/test_workbook_reader.py` | `test_detect_xls_magic_on_sample`、`test_read_sample_has_sheet1_and_title_at_row0` | `test_detect_xlsx_and_unknown_magic`、`test_a1_coordinate`、`test_read_workbook_rejects_unsupported_bytes`、`test_read_workbook_rejects_empty_bytes`、`test_read_synthetic_xlsx_round_trip` |
| `backend/tests/test_chengdu_wenli_v1_profile.py` | 该文件中全部使用 `SAMPLE_PATH` 的用例 | — |
| `backend/tests/test_import_service.py` | 使用 `_sample_bytes()` 的用例 | 若有纯合成/空数据用例则不要加 |

若文件顶部尚未 `import pytest`，补上。`test_api.py`、`test_import_service.py`、`test_workbook_reader.py` 已有 `import pytest` 时不要重复导入。

- [ ] **Step 4: 更新 backend/README 样例路径**

将 `backend/README.md` 中：

```text
- 样例文件：`excel样例/25计科9(1).xls`（仅作手工验证参考，测试使用合成样例，不依赖该文件）。
```

改为：

```text
- 样例文件：`samples/excel/25计科9(1).xls`（仅本地手工验证；不入库。依赖该文件的测试在缺失时自动 skip）。
```

- [ ] **Step 5: 验证测试在样例存在时通过**

Run:
```powershell
cd backend
uv run pytest -q
```
Expected: 全部通过（样例存在）。

- [ ] **Step 6: 验证样例缺失时 skip 而非失败**

Run:
```powershell
cd ..
Rename-Item samples samples_off
cd backend
uv run pytest -q
cd ..
Rename-Item samples_off samples
```
Expected: 依赖样例的测试显示 skipped，其余仍通过；重命名后目录恢复。

- [ ] **Step 7: 提交（中文）**

Run:
```powershell
git add .gitignore samples 2>$null
git add backend/tests/test_api.py backend/tests/test_workbook_reader.py backend/tests/test_chengdu_wenli_v1_profile.py backend/tests/test_import_service.py backend/README.md
# 确认 samples 未被加入：
git status --short
git commit -m "chore: 统一忽略规则并整理样例目录（样例不入库）"
```
Expected: commit 不包含 `samples/` 内任何文件；`.gitignore` 已跟踪。

> 注意：`git add samples` 在被忽略时应无效果；若误加入，用 `git reset samples` 撤出后再提交。

---

### Task 3: 文档英文化与脚本归位

**Covers:** [S4], [S5]

**Files:**
- Move: `docs/里程碑任务清单.md` → `docs/milestones.md`
- Move: `离线单板应用-beck/Colink项目计划书.md` → `docs/project-proposal.md`
- Move: `离线单板应用-beck/build_colink_pdf.py` → `scripts/build_colink_pdf.py`
- Modify: `scripts/build_colink_pdf.py`（输出路径与数据根）
- Modify: `docs/milestones.md`（文内指向计划书的文件名）
- Modify: `docs/project-proposal.md`（如有路径引用则更新；无则不动）

**Interfaces:**
- Consumes: Task 2 完成后的树
- Produces: `docs/milestones.md`、`docs/project-proposal.md`、`scripts/build_colink_pdf.py`；PDF 输出到 `output/pdf/`（已忽略）

- [ ] **Step 1: 创建 scripts 并移动文件**

Run:
```powershell
New-Item -ItemType Directory -Force scripts | Out-Null
Move-Item "docs/里程碑任务清单.md" "docs/milestones.md"
Move-Item "离线单板应用-beck/Colink项目计划书.md" "docs/project-proposal.md"
Move-Item "离线单板应用-beck/build_colink_pdf.py" "scripts/build_colink_pdf.py"
```
Expected: 三者到达目标路径；原路径不存在。

- [ ] **Step 2: 修正 build_colink_pdf.py 的路径常量**

将：

```python
ROOT = Path(__file__).resolve().parent
OUT = ROOT / "output" / "pdf" / "Colink项目计划书.pdf"
```

替换为：

```python
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output" / "pdf" / "project-proposal.pdf"
```

说明：脚本移动到 `scripts/` 后，仓库根为 `parents[1]`；PDF 文件名与文档英文化对齐。若脚本内另有读取同目录 Markdown 的逻辑，改为读取 `ROOT / "docs" / "project-proposal.md"`（先 Grep `Colink项目计划书|Path\(__file__\)` 确认）。

- [ ] **Step 3: 修正 docs/milestones.md 内的文档引用**

将正文中的：

```text
`Colink项目计划书.md`
```

替换为：

```text
`docs/project-proposal.md`
```

Run:
```powershell
# 检查是否仍有旧引用
Select-String -Path docs/milestones.md,docs/project-proposal.md,scripts/build_colink_pdf.py -Pattern "Colink项目计划书.md|里程碑任务清单.md|excel样例|离线单板应用"
```
Expected: 无输出，或仅历史描述且不构成路径依赖。

- [ ] **Step 4: 提交（中文）**

Run:
```powershell
git add docs/milestones.md docs/project-proposal.md scripts/build_colink_pdf.py
git commit -m "docs: 中文文档与工具脚本英文化归位"
```
Expected: 新路径入库；旧中文路径不再出现在 `git status` 的新增列表。

---

### Task 4: Android 工程迁入 frontend/ 并清理产物

**Covers:** [S2], [S3], [S4], [S8]

**Files:**
- Move: `离线单板应用-beck/` → `frontend/`
- Delete: `frontend/excel样例/`（与根样例重复）
- Delete: `frontend/output/`（生成 PDF）
- Keep on disk but ignored: `frontend/.gradle/`, `frontend/.idea/`, `frontend/.kotlin/`, `frontend/build/`, `frontend/app/build/`, `frontend/local.properties`, `frontend/.mimocode/`

**Interfaces:**
- Consumes: Task 3 已移走计划书与 PDF 脚本后的目录
- Produces: `frontend/` 为完整 Gradle Android 工程；`settings.gradle.kts` 仍 `include(":app")`

- [ ] **Step 1: 重命名目录**

Run:
```powershell
Rename-Item "离线单板应用-beck" "frontend"
```
Expected: `frontend/` 存在且含 `settings.gradle.kts`、`app/`、`gradlew`。

- [ ] **Step 2: 删除重复样例与生成产物**

Run:
```powershell
if (Test-Path "frontend/excel样例") { Remove-Item "frontend/excel样例" -Recurse -Force }
if (Test-Path "frontend/output") { Remove-Item "frontend/output" -Recurse -Force }
```
Expected: 上述路径不存在；`samples/excel/25计科9(1).xls` 仍存在。

- [ ] **Step 3: 校验 Gradle 工程相对路径**

Run:
```powershell
Get-Content frontend/settings.gradle.kts
Test-Path frontend/app/build.gradle.kts
Test-Path frontend/app/src/main/AndroidManifest.xml
Test-Path frontend/gradlew.bat
```
Expected: `settings.gradle.kts` 含 `include(":app")`；其余路径均为 True。

- [ ] **Step 4: 提交 frontend 源码（中文）**

Run:
```powershell
git add frontend
git status --short
git commit -m "chore: 将 Android 客户端整理到 frontend 目录"
```
Expected: `git ls-files frontend` 含 `frontend/app/src/...` 与 Gradle 脚本；**不含** `local.properties`、`.gradle/`、`build/`、`samples`。

> 若 `git add frontend` 误加入缓存/产物，用 `git rm -r --cached <path>` 撤出后再提交，并检查根 `.gitignore`。

---

### Task 5: 同步 AGENTS.md 与根 README

**Covers:** [S5], [S8]

**Files:**
- Modify: `AGENTS.md`
- Create: `README.md`

**Interfaces:**
- Consumes: Task 4 后的最终目录名
- Produces: 文档与真实树一致；`frontend/` 为 Android 唯一真源

- [ ] **Step 1: 更新 AGENTS.md 路径与命名**

在 `AGENTS.md` 中做替换：

| 旧 | 新 |
|----|----|
| `离线单板应用-front/` | `frontend/` |
| `docs/里程碑任务清单.md` | `docs/milestones.md` |

并核对下列句子与现实一致：
- “The Android project lives in `frontend/`”
- “Root-level Android/Gradle files remain deleted… the live Android project is `frontend/`”
- Repository Context 中的 backend/frontend 分工说明

Run:
```powershell
Select-String -Path AGENTS.md -Pattern "离线单板应用|里程碑任务清单"
```
Expected: 无输出。

- [ ] **Step 2: 新建根 README.md**

写入 `README.md`：

```markdown
# Colink

课堂课表产品：Python 后端（课表导入/校验/持久化）+ Android 客户端（Kotlin / Jetpack Compose）。

## 目录结构

| 目录 | 说明 |
|------|------|
| `backend/` | Python 后端：工作簿解析、导入预览/确认 API、持久化 |
| `frontend/` | Android 客户端（Colink） |
| `docs/` | 里程碑、产品计划、设计/计划文档 |
| `scripts/` | 辅助脚本（如 PDF 生成） |
| `samples/` | 本地样例数据（不入库） |

## 快速开始

### 后端

```bash
cd backend
uv sync
uv run pytest
uv run uvicorn class_table_backend.api.app:app --reload
```

### Android

用 Android Studio 打开 `frontend/`，或：

```bash
cd frontend
./gradlew :app:assembleDebug
```

## 说明

- 真实课表样例仅存于本地 `samples/`，已被 gitignore，不会推送。
- 详细开发约定见 `AGENTS.md`。
```

- [ ] **Step 3: 提交（中文）**

Run:
```powershell
git add AGENTS.md README.md
git commit -m "docs: 根 README 与 AGENTS 目录说明对齐新结构"
```
Expected: commit 成功。

---

### Task 6: 整体校验

**Covers:** [S3], [S8]

**Files:**
- 无新文件（只读校验 + 必要回修）

**Interfaces:**
- Consumes: Task 1–5 的树与提交
- Produces: 可公开发布的干净索引与测试结论

- [ ] **Step 1: 树与命名校验**

Run:
```powershell
Get-ChildItem -Force | Select-Object Name
Get-ChildItem -Force docs, samples, scripts | Select-Object FullName
Select-String -Path AGENTS.md,README.md,backend/README.md,docs/milestones.md -Pattern "离线单板应用|excel样例" 
```
Expected: 顶层含 `frontend` `backend` `docs` `samples` `scripts` `AGENTS.md` `README.md` `.gitignore`；无中文顶层目录；Select-String 无路径性旧名。

- [ ] **Step 2: git 索引卫生校验**

Run:
```powershell
git ls-files | Select-String -Pattern "local\.properties|\.db$|uvicorn|\.venv|samples/|\.gradle/|node_modules|\.mimocode|output/"
git status --short
```
Expected: 第一条无输出；`git status` 无“应忽略却被跟踪”的文件。

- [ ] **Step 3: backend 测试**

Run:
```powershell
cd backend
uv run pytest -q
```
Expected: 通过或合理 skip；无 failure。

- [ ] **Step 4: frontend 路径完整性（无 Android SDK 时不强制编译）**

Run:
```powershell
cd ../frontend
if (Test-Path .\gradlew.bat) { .\gradlew.bat :app:tasks --all | Select-Object -First 20 }
```
Expected: 若本机无 Android SDK/网络，允许 Gradle 配置失败但 **源码与 settings 路径必须完整**；记录未能编译的原因。若有 SDK，`:app:tasks` 可列出任务。

- [ ] **Step 5: 如有回修则中文提交**

Run:
```powershell
git status --short
# 若有回修：
git add -A
git commit -m "fix: 整理后残留路径与忽略规则修正"
```
Expected: 工作区仅剩允许未跟踪且被忽略的本地产物。

---

### Task 7: 创建公开 GitHub 仓库并推送

**Covers:** [S6], [S7], [S8]

**Files:**
- 无本地新文件（远端操作）

**Interfaces:**
- Consumes: Task 6 全部通过
- Produces: 公开仓库 `colink`，`origin` 指向该仓库，`main` 已推送

- [ ] **Step 1: 检查 gh 登录与仓库名占用**

Run:
```powershell
gh auth status
gh repo view colink
```
Expected: `gh auth status` 显示已登录。`gh repo view colink` 若已存在则 **停止** 并询问用户（不得删除远端仓库）；404 表示可用。

- [ ] **Step 2: 创建公开仓库并推送**

Run:
```powershell
git remote -v
gh repo create colink --public --source=. --remote=origin --push
```
Expected: 创建成功并推送 `main`。若已有 `origin` 且指向错误地址，先 `git remote set-url origin <新地址>` 或 `git remote remove origin` 后再 create（仅动 remote 配置，不 force）。

- [ ] **Step 3: 远端内容抽查**

Run:
```powershell
gh api repos/:owner/colink/contents --jq '.[].name'
git ls-remote origin
```
Expected: 远端根目录含 `frontend` `backend` `docs` `scripts` `README.md` `AGENTS.md`；**不含** `samples`、`excel样例`、`*.db`。

- [ ] **Step 4: 失败与回退约定**

- 未登录：报告用户执行 `gh auth login`，不继续猜测 token。
- 仓库名被占用：报告并询问是否改名，不删除/覆盖远端。
- push 被拒绝：展示完整错误；禁止 `--force`。
- 若发现敏感文件已推送：立即停止并告知用户（是否撤销/转私有由用户决定）。

---

## Self-Review 记录

1. **Spec coverage:** S1 问题陈述（无独立任务，由 S2–S8 承载）；S2→Task 4；S3→Task 2/4/6；S4→Task 2/3/4；S5→Task 3/5；S6→Task 1/7；S7→Task 7；S8→Task 2/5/6/7。全覆盖。
2. **Placeholder scan:** 步骤均为具体路径/命令/预期；无 TBD。
3. **Type consistency:** 测试统一 `SAMPLE_PATH` + `requires_sample`（用例级 skipif，不用模块级 pytestmark）；`build_colink_pdf.py` 统一 `ROOT = parents[1]`。
