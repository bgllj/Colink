---
feature: update-semester-start-date
status: delivered
updated: 2026-10-10
branch: main
commits: def8225..3d711a6
---

# 修改开学日期

## Report

**What was built** — 管理员可在管理端课表页直接修改某班的开学日期。后端新增 `PATCH /classes/{class_id}/semester`（Bearer），把 `start_date` 写入该班最近一次已确认导入批次；班级不存在或尚无已确认课表返回 `404`，非法日期/缺字段 `422`。管理端 SchedulePage 的「开学日期」提供修改/保存/取消，保存成功后页内回写 `semester`，失败展示错误并保持编辑态；保存期间禁用班级与周次控件，并用 `classIdRef` 丢弃切换班级后的过期响应。

**Verification** — 命令与结果：

| 命令 | 结果 |
|------|------|
| `python -m pytest -q`（`backend/`） | PASS 106 passed, 16 skipped（PRE-EXISTING 样例 skip） |
| `ruff check src tests`（`backend/`） | PASS |
| `npm test`（`frontend/admin/`） | PASS 12 passed |
| `npx tsc -b`（`frontend/admin/`） | PASS |
| `npm run build`（`frontend/admin/`） | PASS |

独立审阅：首轮发现 CRITICAL 竞态（保存中切班导致学期元信息串班）；已修复并复审 PASS。

**Journey log** —

1. 开学日期落在 `import_batch.start_date`（最近 CONFIRMED 批次），不是 `class` 表；接口只改该字段，不动 max_week/学年。
2. UI 首版不做「清空」；API 仍接受 `null` 以备脚本使用。
3. 管理端保存竞态：`onStartSave` 必须捕获 `targetClassId`，响应只在 `classIdRef` 未变时回写；并禁用班级/周次控件。
4. 后端仓库层只 `flush()`，事务由 `get_session` 在请求结束时提交。

## [S1] Problem

课表导入后，开学日期可能来自表格中的错误/过期信息，或需要按校历调整。目前后端只能读 `semester.start_date`，管理端课表页只展示不可改；管理员只能重新导入或直接改库。

## [S2] Design

### S2.1 后端契约

`PATCH /classes/{class_id}/semester`（需 `Authorization: Bearer <token>`）

请求体：

```json
{ "start_date": "YYYY-MM-DD" }   // 显式 null 表示清空；字段必填
```

行为：

- 写入该班**最近一次已确认导入批次**（`status=CONFIRMED`）的 `start_date`。
- 成功 `200`，返回 `ScheduleSemesterOut`：`{ start_date, max_week, academic_year, semester_name }`。
- 班级不存在 → `404`「班级不存在」。
- 班级存在但无已确认课表 → `404`「尚无已确认课表，无法修改开学日期」。
- 非法日期 / 缺 `start_date` 字段 → `422`。
- 无/无效 token → `401`。

### S2.2 管理端 UI（课表页）

在 `frontend/admin` 的课表页（SchedulePage）学期元信息区，「开学日期」可编辑：

- 默认只读展示 `semester.start_date`（无值显示 `—`）。
- 「修改」进入编辑：`<input type="date">` + 「保存」/「取消」。
- 保存调用 `PATCH /classes/{id}/semester`，成功后用响应更新页内 `schedule.semester`（无需整页刷新课表）。
- 失败（404/422/网络）在页内展示错误信息，保留编辑态便于重试。
- 保存期间禁用班级选择与周次过滤；过期响应（班级已切换）不写入视图。
- 仅登录管理员可见编辑入口（该页本身在管理端鉴权之后）。
- 不提供「清空」按钮；如需清空由 API 传 `null`（UI 首版只支持设为具体日期）。

### S2.3 API client

`frontend/admin/src/api/client.ts` 增加：

```ts
updateClassSemester(classId: string, body: { start_date: string | null }): Promise<ScheduleSemesterOut>
```

`types.ts` 无需新增响应类型（复用 `ScheduleSemesterOut`）。

## [S3] Out of Scope

- Android 学生端（只读；本地改开学时间的既有能力不动）。
- 修改 `max_week` / 学年 / 学期名。
- UI 清空开学日期。
- 导入预览页改开学日期。
- 后端 schema/迁移变更（无表结构变更）。

## Tasks

- [x] T1: 管理端 API client 增加 `updateClassSemester` — acceptance: 类型正确，PATCH `/classes/{id}/semester`，单测或 tsc 通过 (covers: S2.3)
- [x] T2: SchedulePage 开学日期可编辑（修改/保存/取消、错误展示、成功回写） — acceptance: 页面可改日期并看到新值；无已确认课表时展示后端错误 (covers: S2.2; depends: T1)
- [x] T3: 管理端测试与检查 — acceptance: `npm test`、`npx tsc -b` 通过 (covers: S2.2, S2.3; depends: T1, T2)
