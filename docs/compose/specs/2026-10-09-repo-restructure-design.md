# 仓库整理与公开发布设计

## [S1] Problem

仓库当前混杂了多种材料与临时产物，缺少统一分类与根级卫生规则：

- Android 工程目录名为 `离线单板应用-beck/`，与 `AGENTS.md` 中的 `离线单板应用-front/` 不一致，且中英文命名混用。
- 样例课表、历史文档、PDF 生成脚本、生成产物、虚拟环境、日志与数据库散落在根目录和各子目录。
- 根级 `.gitignore` / `README.md` 处于已删除状态，构建缓存与本地密钥类文件（如 `local.properties`）缺少统一忽略。
- 需要创建公开 GitHub 仓库 `colink` 并安全上传，且不暴露真实课表样例。

## [S2] Solution overview

采用前后端对称 monorepo 布局，完成目录迁移、命名英文化、忽略规则统一、文档同步，并以 1–2 个整理 commit 创建公开仓库 `colink` 后推送。

顶层结构：

```
colink/
├── frontend/                 # Android 客户端
├── backend/                  # Python 后端（源码结构保持不动）
├── docs/                     # 里程碑、计划书、compose specs/plans
├── samples/excel/            # 本地样例，不入库
├── scripts/                  # 工具脚本
├── AGENTS.md
├── README.md
└── .gitignore
```

## [S3] Target tree and ignore policy

### 目标树

- `frontend/`：原 `离线单板应用-beck/` 重命名后的 Android 工程（Gradle 工程文件 + `app/src`）。
- `backend/`：保持现有 `src/class_table_backend/{api,domain,import_flow,parsing,persistence}` 与 `tests/`、`pyproject.toml`、`alembic.ini`。
- `docs/`：
  - `docs/milestones.md`（原 `docs/里程碑任务清单.md`）
  - `docs/project-proposal.md`（原 `离线单板应用-beck/Colink项目计划书.md`）
  - `docs/compose/specs/`、`docs/compose/plans/`
- `samples/excel/`：本地样例课表，仅本地保留。
- `scripts/build_colink_pdf.py`：PDF 生成脚本。
- 根级：`AGENTS.md`、新建 `README.md`、新建 `.gitignore`。

### 必须忽略且不得入库

- Python：`.venv/`、`.venv-ss/`、`.venv-tmp/`、`__pycache__/`、`*.py[cod]`、`.pytest_cache/`、`.ruff_cache/`、`*.egg-info/`、`dist/`、`build/`
- 本地数据与日志：`*.db`、`uvicorn.log`、`uvicorn.err`
- Android/IDE：`.gradle/`、`.idea/`、`.kotlin/`、`**/build/`、`local.properties`、`output/`
- 项目工具：`.mimocode/`
- 样例：`samples/`

## [S4] Migration mapping

| 现状 | 目标 | 操作 |
|------|------|------|
| `离线单板应用-beck/` | `frontend/` | 整目录重命名；剔除缓存/产物/本地配置后入 git |
| `excel样例/25计科9(1).xls` | `samples/excel/` | 移动，gitignore |
| `离线单板应用-beck/excel样例/` | 删除 | 与根样例重复 |
| `离线单板应用-beck/Colink项目计划书.md` | `docs/project-proposal.md` | 移动并英文化文件名 |
| `离线单板应用-beck/build_colink_pdf.py` | `scripts/build_colink_pdf.py` | 移动；若内部路径写死旧目录则更新 |
| `离线单板应用-beck/output/` | 删除本地生成物并忽略 | 生成 PDF 产物，不入库 |
| `docs/里程碑任务清单.md` | `docs/milestones.md` | 移动并英文化文件名 |
| 根级已删除的 `app/`、`gradle*`、`README.md`、`.gitignore` | 保持删除 | 仅新建根 `.gitignore` 与 `README.md` |

约束：

- 不恢复、不重新提交历史中已删除的根级 Android 文件。
- 不改写 git 历史。
- `backend/src/**` 代码结构、测试与迁移文件不做重构式改动。

## [S5] Documentation sync

- `AGENTS.md`：将 `离线单板应用-front/` 修正为 `frontend/`；目录说明与本设计一致；保留既有导入规则与范围约束。
- 根 `README.md`：项目简介、目录分类说明、backend/frontend 本地启动指引、测试命令。
- `frontend/README.md`、`backend/README.md`：仅在路径或启动方式因迁移改变时做必要修正。

## [S6] GitHub publish

1. 创建公开仓库 `colink`（`gh repo create colink --public`），remote 指向该仓库。
2. 纳入版本库：`frontend/` 源码与 Gradle 脚本、`backend/` 源码/测试/配置、`docs/`、`scripts/`、`AGENTS.md`、`README.md`、根 `.gitignore`。
3. 绝不推送：`samples/`、`*.db`、`uvicorn.*`、`local.properties`、虚拟环境、构建缓存、`.mimocode/`。
4. 提交策略：默认 2 个 commit——(1) 先提交整理前已有的 backend 变更（schedule API 等），(2) 承载目录迁移与文档/忽略规则的整理提交；若用户要求可合并为 1 个。**commit 描述一律使用中文。**
5. 失败行为：若 `gh` 未登录或仓库名已存在，停止并报告，不猜测、不删除远端内容。

## [S7] Error handling and safety

- 迁移前记录 `git status`，不覆盖、不回滚用户已有未提交改动（backend schedule API 修改一并纳入整理提交范围，若用户要求拆分则拆分）。
- 仅使用可逆的移动/重命名/写忽略文件；删除仅限明确的重复样例、构建产物与生成 PDF。
- 推送前用 `.gitignore` 与 `git status` 复核，确认无样例课表、无密钥/本机路径文件。
- 不执行 `git push --force`、不改写历史、不删除远端仓库。

## [S8] Success criteria

- 顶层目录可一眼分类：`frontend` / `backend` / `docs` / `samples` / `scripts`。
- 中文顶层目录名清零（文档文件名也英文化）。
- `git status` 无缓存、日志、db、local.properties、samples 被追踪。
- backend 测试可运行且与整理前同结论；前端 Gradle 工程路径有效（至少 `settings.gradle.kts` 与 `app/` 相对路径正确）。
- 公开仓库 `colink` 创建成功并完成推送；远端不含 `samples` 与本地产物。
- 所有 git commit 描述为中文。
