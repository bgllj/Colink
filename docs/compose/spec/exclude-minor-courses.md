---
feature: exclude-minor-courses
status: delivered
updated: 2026-10-10
branch: feat/exclude-minor-courses
commits: bced9f9..HEAD
---

# 自动排除辅修课程

## Report

**What was built** — 上传课表时可勾选「忽略辅修课程」（默认勾选）。后端按课程名是否含「辅修」判定，命中行写入预览时 `selected=0` 并附带 info 级 `MINOR_COURSE_EXCLUDED`，仍可在逐行审核中看到并改回。`POST /imports` 新增 Form 字段 `ignore_minor`（默认 `true`）；批次 meta 记录该选项。同时修正确认契约：`confirm` 显式传入的 `row_ids` 视为用户最终勾选（即便入库时 `selected=0`），省略 `row_ids` 时仍只确认 `selected=1` 的 PARSED 行——否则审核页改回辅修行无法入库。管理端上传页增加复选框并随 multipart 提交。

**Verification** —

| 命令 | 结果 |
|------|------|
| `python -m pytest -q`（`backend/`） | PASS 115 passed, 16 skipped（PRE-EXISTING 样例 skip） |
| `ruff check src tests`（`backend/`） | PASS |
| `npm test`（`frontend/admin/`） | PASS 13 passed |
| `npx tsc -b`（`frontend/admin/`） | PASS |
| `npm run build`（`frontend/admin/`） | PASS |

独立审阅：首轮 CRITICAL（显式 row_ids 被 selected=0 静默丢弃）；已修复并复审 PASS。

**Journey log** —

1. 辅修只按课程名含「辅修」判定，不用 FX 号段（学校改编号会漏判）。
2. 排除 = `selected=0` + info issue，不引入 EXCLUDED 状态机，避免 info 把 `row_status` 打成 NEEDS_REVIEW 后无法确认。
3. `confirm_rows` 原先无条件 `and not row.selected`，首次被本功能激活；契约改为显式 `row_ids` 信任用户勾选。
4. 沙箱禁止 `git worktree add`，在当前检出建 `feat/exclude-minor-courses` 分支代替。
5. 样例文件位于 `backend/tests/样例/`（非 gitignore 路径），已补忽略规则防止误提交。

## [S1] Problem

成都文理学院导出课表混有辅修课程（课程名含「辅修」，如 `财务管理Ⅰ（辅修）`）。学生主修课表不应包含辅修课，但目前导入后所有解析行默认 `selected=1`，管理员只能在逐行审核里手动取消，容易漏选并把辅修课写进正式课表。

需要在上传时一键忽略辅修课：勾选后辅修行自动取消勾选、仍可在审核页看到并改回；未勾选则保持现状。

## [S2] Design

### S2.1 辅修识别

```text
is_minor_course(course_name) -> bool
```

- `course_name` 去首尾空白后包含子串 **`辅修`** 即判定为辅修。
- 仅看课程名，不依赖课程号前缀（样例号段 `FX*` 只是巧合，不作为规则）。
- `course_name` 为 `None`/空 → 非辅修。
- 不区分全角/半角括号：`（辅修）`、`(辅修)`、`辅修` 本身都命中。

### S2.2 导入语义（勾选「忽略辅修」）

`POST /imports` 新增 Form 字段：

| 字段 | 类型 | 默认 | 含义 |
|------|------|------|------|
| `ignore_minor` | `bool` | `true` | 是否自动排除辅修课程 |

`ignore_minor=true` 时，在 `_persist_extraction` 写行前：

- 命中 `is_minor_course` 的 occurrence：`selected=0`（其余仍为 `selected=1`）。
- 该行附加 **info** 级 issue：`MINOR_COURSE_EXCLUDED`，message 例：`已按「忽略辅修」自动取消勾选：财务管理Ⅰ（辅修）`。
- 行仍然写入 `import_row`，预览/审核页可见；不改 `row_status`（保持 PARSED/NEEDS_REVIEW/INVALID 原判定），避免 info 误伤确认条件。
- 用户在审核页手动改回勾选后，可正常入库。契约：`confirm` 的 `row_ids` **显式列出的行**按用户最终勾选处理（即便 `selected=0`）；`row_ids` 省略时只确认 `selected=1` 的 PARSED 行。

`ignore_minor=false`：全部行 `selected=1`，无 `MINOR_COURSE_EXCLUDED` issue（与现状一致）。

批次 `meta_json` 记录 `ignore_minor`，预览 meta 可回显。

### S2.3 后端 API 变更

- `POST /imports`：`ignore_minor: bool = True`（Form）。
- `IssueCode` 新增 `MINOR_COURSE_EXCLUDED = "MINOR_COURSE_EXCLUDED"`。
- `ImportIngestResponse` / `ImportPreviewOut` 结构不变；`rows[].issues` 里会出现上述 info。
- 无 DB 迁移（`selected` 列已存在；meta 走 JSON）。

### S2.4 管理端 UI

`ImportUploadPage` 增加复选框：

- 标签：**忽略辅修课程**（说明文案：勾选后课程名含「辅修」的行将自动取消勾选，可在审核页改回）。
- 默认 **勾选**。
- 随 `ingestImport` 提交 `ignore_minor`。

`ingestImport` options 增加 `ignoreMinor?: boolean`，写入 FormData 的 `ignore_minor`（`true`/`false` 字符串）。类型 `ImportIngestResponse` 无需新字段。

审核页 `ImportPreviewPage`：不改交互——`selected` 初值已来自服务端 `row.selected`，辅修行自然显示为未勾选；`MINOR_COURSE_EXCLUDED` 出现在「状态 / 问题」列。

### S2.5 错误与边界

- `ignore_minor` 缺省视为 `true`（Form 省略 = 默认排除）。
- 无法解析的辅修行（INVALID）同样只做 `selected=0`，不吞解析错误。
- 同一单元格多行：逐行独立判定 `course_name`。
- 重复文件（`duplicate_of`）短路在过滤之前，不重新应用选项。

## [S3] Out of Scope

- 不按课程号 `FX` 前缀识别。
- 不删除/隐藏辅修行；不引入 `EXCLUDED` 行状态机。
- 不改确认接口、学生端、课程/班级模型。
- 不做「导入后批量取消辅修」的二次工具。

## Tasks

- [x] T1: 后端识别与过滤 — `is_minor_course` + `IssueCode.MINOR_COURSE_EXCLUDED` + `ingest(ignore_minor)` 将辅修行 `selected=0` 并附 info issue，meta 记录选项 — acceptance: 单测覆盖识别规则与 selected/issue 行为 (covers: S2.1 S2.2 S2.3)
- [x] T2: 后端 API 暴露 — `POST /imports` 接受 `ignore_minor` Form 字段并传入 service — acceptance: API 测试证明字段生效与默认值 (covers: S2.3)
- [x] T3: 管理端上传勾选框 — ImportUploadPage 复选框 + client `ingestImport` 提交 `ignore_minor` — acceptance: 前端测试断言 FormData 含该字段 (covers: S2.4)
- [x] T4: 验证与文档 — 跑 backend/frontend 测试与 lint；提交中文 commit 并推送 GitHub — acceptance: 测试结果记录于 Report；远程分支含变更 (covers: S2.4 S2.5)
