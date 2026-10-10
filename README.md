# Colink

课堂课表产品：**一个班级一个课程表**。Python 后端（课表导入/校验/持久化）+ Android 学生端 + Web 管理端。

## 目录结构

| 目录 | 说明 |
|------|------|
| `backend/` | Python 后端：工作簿解析、班级管理、导入预览/确认 API、持久化、管理员鉴权 |
| `frontend/user/` | Android 学生端（Colink，Kotlin / Jetpack Compose）：选班 + 查看本班课表 |
| `frontend/admin/` | Web 管理端（Vite + React + TS）：班级管理、导入审核、按班查看课表 |
| `docs/` | 里程碑、产品计划、设计/计划文档 |
| `scripts/` | 辅助脚本（如 PDF 生成） |
| `samples/` | 本地样例数据（不入库） |

## 快速开始

### 后端

```bash
cd backend
uv sync
uv run pytest
# 引导管理员账号（首次）
export ADMIN_BOOTSTRAP_USERNAME=admin
export ADMIN_BOOTSTRAP_PASSWORD=change-me
export ADMIN_JWT_SECRET=change-me-too
uv run uvicorn class_table_backend.api.app:create_app --factory --reload
```

### Android 学生端

用 Android Studio 打开 `frontend/user/`，或：

```bash
cd frontend/user
./gradlew :app:assembleDebug
```

### Web 管理端

```bash
cd frontend/admin
npm install
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`，使用上一步引导的管理员账号登录。

## 说明

- 产品模型：一个班级一个课程表；管理端按班级导入，学生端选班后查看本班课表。
- 真实课表样例仅存于本地 `samples/`，已被 gitignore，不会推送。
- 学生端只读 `GET /classes` 与 `GET /classes/{id}/schedule`；管理端登录后调用 `/classes*`（写）与 `/imports*`。
- 详细开发约定见 `AGENTS.md`。
