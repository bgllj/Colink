---
feature: one-class-one-timetable
status: delivered
updated: 2026-10-10
branch: main
commits: # 新仓尚未提交；交付范围即当前工作区
---

# 一个班级一个课程表（多班级重塑）

## Report

**What was built** — 在全新仓库 `D:\Code\colink` 中把产品重塑为「一个班级一个课程表」：`Class` 成为一等实体，`Course` 经 `class_id` 归属班级；导入双路径建班（管理端先建 / confirm 时按 `class_name` 自动建）；`confirm` 显式 `replace` 才能整表替换，否则 `409 SCHEDULE_EXISTS`。公开 API 为 `GET /classes` 与 `GET /classes/{id}/schedule`（原全局 `GET /schedule` 已删除）。管理端新增班级 CRUD、导入选班、替换勾选、课表按班筛选；Android 端班级列表本地记住并按班拉取。解析层（workbook / profile / 周次节次表达式）原样搬移复用。

**Verification** — `backend/.venv/bin/python.exe -m pytest`：**98 passed, 16 skipped**（样例缺失时跳过）；`ruff check src tests`：**All checks passed**；`frontend/admin`：`npx tsc -b` 通过，`npm test` **9 passed**；`frontend/user`：`gradlew :app:compileDebugKotlin` **BUILD SUCCESSFUL**。

**Journey log** — 1) 旧仓全局课表模型（无班级外键）与「一班一课表」差距明确，选择搬移解析层+重塑模型而非推倒。2) `git worktree` 在沙箱被拦，改为另起 `D:\Code\colink` 新仓。3) mingw Python 装不了 ruff/pydantic-core 轮子：ruff 用独立二进制，fastapi/pydantic 走系统 site-packages（`_msys_system_site.pth`）。4) 班级冲突校验不能把「工作簿 class_name 快照」和「显式 class_id」对撞报错，只在两者都显式传入且不一致时才 `CLASS_CONFLICT`。5) 确认导入必须指定目标班级（`CLASS_REQUIRED`），避免无主课表。

## [S1] 问题

现有产品将课表存成全局一张表：`MeetingOccurrence` 不归属班级，`GET /schedule` 返回全部课表，无法同时服务多个班级。理想产品形态是「一个班级一个课程表」——多班级并存，每班恰好一张课表，学生选班后查看本班，管理端按班导入与维护。

同时，已验证的解析层（工作簿读取、chengdu_wenli_v1 profile、周次/节次表达式、课程行解析）有复用价值，不应推倒重写；需要「搬移 + 重塑」而非完全重写。

## [S2] 设计

### 产品形态

- 多班级：系统内可有 N 个班级；**每个班级恰好一张课程表**（当前学期）。
- Class 为一等实体；课表数据归属 class（1:1）。
- 学生端：浏览公开班级列表 → 选定班级 → 查看该班课表；选择记入本地 profile，无账号。
- 管理端：班级列表/新建/改名/删除；按班级导入审核；按班级查看课表。

### 领域模型

保留现有解析层数据结构（`SemesterMeta`、`ParsedOccurrence`、`WeekRange`、`Issue`/`IssueCode`、各 parse result），仅重塑持久化与归属：

| 实体 | 变化 |
|------|------|
| `Class`（新） | `id`（uuid）、`name`（唯一，如 `2025计算机科学与技术9`）、可选 `grade`/`major`/`department`、`created_at` |
| `ImportBatch` | 新增 `class_id`（确认时解析）；保留 `class_name` 快照（来自工作簿元数据，仅供审计/自动建班） |
| `ImportRow` | 不变（仍挂 batch） |
| `Course` | 新增 `class_id` 外键；唯一约束改为 `(class_id, course_code, name)` |
| `MeetingOccurrence` | 经 `course.class_id` 间接归属班级（不加冗余 `class_id`，除非查询需要） |
| `MeetingWeek` | 不变 |

学期元数据（`academic_year`/`semester_name`/`start_date`/`max_week`）仍来自导入批次快照，挂在该班课表的查询结果上（与现状一致），**不做多学期并存**。

- 删除班级 = 级联删除该班 `Course`/`MeetingOccurrence`/`MeetingWeek` 及未确认的 import 批次（或批次改标 `CLASS_DELETED`，实现取其一并写清）。已确认批次审计记录可保留但不再可被 confirm。
- 确认写入仍为单事务；预览不写课表。

### 班级来源（双路径）

1. **管理端建班**：`POST /classes` 显式创建，再导入到 `class_id`。
2. **导入时识别/创建**：导入可传 `class_name`（或从工作簿元数据读出）；若该名班级不存在则在 **confirm 时**创建（不在 upload/preview 时建，避免预览产生副作用）；若已存在则挂靠。

冲突规则：同名班级唯一。上传时若同时传 `class_id` 与 `class_name` 且指向不同班级 → 结构化错误。

### 导入与替换语义

生命周期不变：`UPLOADED → PARSED | NEEDS_REVIEW → CONFIRMED | FAILED`。

**替换（硬规则：永不静默替换）**：

- `POST /imports/{id}/confirm` 新增可选 `replace: bool = false`。
- 目标班级已有确认课表且 `replace != true` → `409`，响应含 `existing_schedule` 摘要（课程数/行数）与提示信息。
- `replace = true` → 在同一事务内删除该班现有 `Course`/`MeetingOccurrence`/`MeetingWeek` 后写入新行。
- 文件哈希幂等保留：同一文件重复 confirm 返回既有结果，不重复写入。

### API（公开）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/classes` | 公开班级列表：`[{id, name}]`（分页可选） |
| GET | `/classes/{id}/schedule?week=N` | 该班课表；含 semester 元数据。取代原全局 `GET /schedule` |
| POST | `/auth/login` | 管理员登录（不变） |
| GET | `/health` | 健康检查（不变） |

原 `GET /schedule` **删除或重定向**到需 `class_id` 的形态；不留「默认返回全部课表」的行为。

### API（管理端，Bearer）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST | `/classes` | 列表 / 新建 |
| PATCH/DELETE | `/classes/{id}` | 改名等 / 删除（级联） |
| POST | `/imports` | 上传解析；multipart 字段：`file`、可选 `class_id`、`class_name`、`max_week` |
| GET | `/imports/{id}` | 预览摘要 + 行 |
| GET | `/imports/{id}/rows` | 行级问题分页 |
| POST | `/imports/{id}/confirm` | body：`row_ids?`、`replace?` |
| GET | `/classes/{id}/schedule` | 同公开课表（管理端查看） |

错误行为沿用 AGENTS.md：稳定 `issue_code` + 坐标 + 中文消息；禁止解析失败变空成功。

### 管理端 SPA（frontend/admin）

- 新增班级页：列表、新建、改名、删除（删除二次确认，说明级联范围）。
- 导入流程：上传前选/建目标班级（或沿用工作簿元数据自动识别提示）；预览展示目标班级；确认对话框在检测到已有课表时要求显式勾选「替换」。
- 课表页：按班级筛选查看。
- 路由沿用 HashRouter：`#/login`、`#/classes`、`#/import`、`#/import/:importId`、`#/schedule`。

### 学生端（frontend/user）

- 首次进入：班级选择列表（`GET /classes`），选定后写入本地 profile（DataStore/SharedPreferences）。
- 课表屏：`GET /classes/{id}/schedule`；可切换班级（回列表或下拉）。
- 保留今日课表、日期选择、周次计算、BackendConfig。

### 搬移边界（不完全重写）

**原样搬移（已验证）**：`parsing/*`（workbook、normalize、course_line、period_expr、week_expr、profiles）、`domain/issues.py`、`domain/models.py`、`domain/validation.py`、`auth/*`（passwords、tokens、bootstrap）、周次/节次/解析测试。

**重塑**：`persistence/tables.py`（+Class、+class_id）、`repositories.py`、`import_flow/service.py`（目标班级解析、replace）、`api/*`（classes 路由、schedule 按班、confirm replace）、Alembic 初始迁移（新库新迁移，不从旧库升级）、API/导入测试、两前端。

**不做**：多学期并存、学生账号、课表在线编辑、导入历史页、班级合并/转移。

## [S3] Out of Scope

- 多学期/多张课表并存（每班仅当前一张）
- 学生注册登录、选课、个人叠加课表
- 课表行在线编辑/手工录入
- 班级合并、课表跨班转移
- 云部署、推送、题库/组队等 Colink 大盘功能

## Tasks

- [x] T1: 持久化重塑 — Class 表 + Course.class_id + 新初始迁移 + 仓储读写按班 — acceptance: 仓储测试通过，同 code 课程可属不同班，删班级联课表 (covers: S2)
- [x] T2: 导入流程目标班级与 replace — upload 支持 class_id/class_name；confirm 解析/创建班级、409 与 replace=true 整表替换、单事务 — acceptance: 导入服务测试覆盖双路径建班、冲突 409、替换与幂等 (covers: S2; depends: T1)
- [x] T3: API 重塑 — GET /classes、GET /classes/{id}/schedule、管理端班级 CRUD、confirm 替换参数；移除全局 GET /schedule — acceptance: API 测试通过，公开接口无鉴权，imports* 需 Bearer (covers: S2; depends: T2)
- [x] T4: 管理端 SPA 班级维度 — 班级页 CRUD、导入选班、确认替换勾选、课表按班筛选 — acceptance: npm test / tsc 通过，关键流程可走通 (covers: S2; depends: T3)
- [x] T5: Android 班级选择 — 班级列表 + 本地记住 + 按班拉课表 + 切换班级 — acceptance: 单元测试或手动验收路径记录，GET /classes/{id}/schedule 被调用 (covers: S2; depends: T3)
- [x] T6: 文档与仓库对齐 — 根 README/AGENTS 反映一班一课表与新 API，backend README 更新 — acceptance: 文档与实现一致，无旧全局 schedule 描述 (covers: S2; depends: T3)
