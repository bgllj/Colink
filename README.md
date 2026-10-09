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
uv run uvicorn class_table_backend.api.app:create_app --factory --reload
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
