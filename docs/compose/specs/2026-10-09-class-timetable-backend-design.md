# 课表导入后端设计（端到端垂直切片）

日期：2026-10-09  
状态：已获用户分段确认  
范围：Python 后端，导入 `.xls`/`.xlsx` 课表 → 校验/预览 → 确认入库；不含 Android/前端/认证。

## [S1] 问题

需要一个可独立运行的 Python 后端：读取教务导出的课表工作簿，解析为结构化排课数据，在预览中暴露问题，确认后持久化。当前仓库仅有 `docs/`、`AGENTS.md`、`excel样例/25计科9(1).xls`，Android 工程已删除且不得恢复。

## [S2] 解决方案概览

在项目根下新建独立 `backend/` 目录，实现 FastAPI + SQLAlchemy 2 + Alembic + pytest 的导入流水线。只做一种已核实 source-layout profile（`chengdu_wenli_v1`），模块边界按可扩展 profile 切开，但不做插件框架。第一期打通垂直切片：上传解析 → 预览 → 确认入库 → HTTP API。

## [S3] 仓库与模块布局

项目根 `D:\Code\my classTable` 保持为总目录；后端全部放在 `backend/`：

```
backend/
  pyproject.toml
  alembic.ini
  src/class_table_backend/
    api/            # FastAPI 路由与请求/响应 schema
    parsing/        # 工作簿 I/O、layout profile、周次表达式
    domain/         # 领域模型与校验结果类型
    persistence/    # SQLAlchemy 模型、会话、仓储
    import_flow/    # 上传→预览→确认 生命周期编排
  tests/
```

依赖方向：`api` → `import_flow` → (`parsing`, `domain`, `persistence`)。`parsing` 不依赖 HTTP/DB。可被脚本/任务复用。

## [S4] 工作簿解析与 chengdu_wenli_v1

- `.xls` 用 `xlrd`，`.xlsx` 用 `openpyxl`；按文件实际格式/可读性选择 reader，不单靠扩展名或 MIME。
- 不用 `pandas.read_excel()` 作为网格解析器；保留合并区域、单元格坐标、原文与行号。
- 从样例 `excel样例/25计科9(1).xls` 归纳的 profile：
  - 标题解析学年学期（如 `成都文理学院2026-2027学年第一学期课表`）。
  - 角落无标签日期（如 `2026-08-31`）解析为学期开学日；解析失败则标记元数据缺失，不瞎猜。
  - 元数据行：院系、年级、专业、班级。
  - 表头 `星期一`…`星期日` 映射列；节次行 `一`…`六` 映射上午/下午/晚上时段。
  - 课程单元格多行文本：每行独立 occurrence，保留 sheet、单元格坐标、行内序号。
  - 行格式：`[课程号]课程名 [周次][节次] 地点`（地点可省；去掉前导空白；方括号兼容中英文形态）。
- `max_week` 默认取已解析周次上界，可被导入配置覆盖。
- 无法识别的单元格/行 → 结构化 issue（含坐标与原文）→ 进入审查态，不静默丢弃。
- 不执行宏、公式或外链；限制上传体积；安全临时文件并在解析后删除。

## [S5] 周次与节次

**周次表达式**

- 保守规范化 Unicode/标点后分词；支持数字、`-`、`,`、`周`、`单周`/`双周`（及 `单`/`双` 别名）。
- 规范形：`[{start, end, parity}]`，`parity ∈ {ALL, ODD, EVEN}`；同时保留原始周次文本。
- **已核实规则：尾部 `单周`/`双周` 修饰整段列表**，不是只修饰最后一段。  
  例：`2-4,8-16双周` → 周 `2,4,8,10,12,14,16`。
- 解析与学期边界校验分离：语法可解析的表达式仍可能对当前 semester 非法。
- 反转区间、空段、未知词（`隔周`、`前半学期`、`按通知`、节假日调整等）→ 结构化 warning/error，不猜测语义。
- 实现不得写死示例区间（如 `1-9`/`1-16`）；数值边界一律来自输入。

**节次**

- `[1-2节]` → `start=1,end=2`；支持任意正整数区间（如 `5-8`、`10-11`）。
- 网格行时段与文本节次交叉校验：不一致记 warning，以文本节次为准。

## [S6] 领域模型与持久化

实体：

- `ImportBatch`：文件哈希、原始文件名、状态、学期元数据快照、创建时间。
- `ImportRow`：批次内逐 occurrence 状态、原文、坐标、issue 列表、是否被选用确认。
- `Course`：课程号、课程名。
- `MeetingOccurrence`：星期、起止节次、周次原文 + 规范化 ranges、教室原文、来源坐标。
- `MeetingWeek`（或等价规范化关联表）：支持按周过滤查询，不把 JSON 当作唯一存储。

生命周期：`UPLOADED → PARSED | NEEDS_REVIEW → CONFIRMED`，失败进入 `FAILED`。

规则：

- 预览不得写入已确认课表（`Course`/`MeetingOccurrence`）；仅写 import 批次与行状态，便于重试与审计。
- 确认在单事务内写入选中行及关联记录。
- 文件哈希作幂等键：重复确认/重复上传不静默覆盖已确认课表，返回既有 batch 或显式冲突。
- 多教室/多行全部保留，不静默丢弃或去重。
- `DATABASE_URL` 外部配置；测试用 SQLite，部署可换 PostgreSQL。

## [S7] API 与失败行为

- `POST /imports`：multipart 上传 + 可选 semester 覆盖（如 `max_week`）→ 创建 batch 并解析；返回 `import_id` 与结构化 issues。
- `GET /imports/{id}`：预览摘要 + 行列表（含 NEEDS_REVIEW/无效行）。
- `POST /imports/{id}/confirm`：body 可选 `row_ids`（缺省为批次内全部「可确认」行）；只写入被选中且无阻断性 issue 的行；幂等；单事务。含阻断性 issue 的行不得写入确认表。
- `GET /imports/{id}/rows`：分页查看行级问题。

错误约定：

- 不支持的文件类型、损坏工作簿、未识别布局、无效行、歧义周次 → 稳定 `issue_code` + 来源坐标 + 可读中文消息。
- 禁止把解析失败转换成 0 周、空课表或假成功导入。
- 上传大小限制、安全文件名处理；不记录工作簿原文与不必要敏感信息。
- 解析与 HTTP 解耦，可在脚本或任务中直接调用。

## [S8] 测试与验收

- 单测：文本规范化、周次解析（任意边界、单双周整段修饰、歧义词、反转/越界/空输入）、节次解析。
- Profile 测试：使用用户提供的 `excel样例/25计科9(1).xls` 作为真实 fixture。
- 集成/API 测试：预览无确认表副作用、确认原子、无效行仍可见、重复确认/重复上传不双写。
- 完成前运行 `pytest` 与类型/静态检查；向 `docs/` 更新本地启动与 API 说明。
- 不恢复 Android 删除文件；不改写历史产品文档，除非另行要求。

## [S9] 明确不做的内容

- Android/移动端/前端 UI、测验、社交、通知等产品史诗。
- 认证授权、云部署、外部服务集成（超出本次导入所必需者）。
- 多学校通用插件框架（仅保留 profile 接口形状）。
- 对歧义周次或未标注元数据做静默猜测。
