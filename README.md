# Colink

课堂课表产品：**一个班级一个课程表**。

管理员在 Web 控制台导入学校导出的课表 Excel（`.xls` / `.xlsx`），逐行审核后入库；学生在 Android 客户端选择班级，查看本班课表。学生端只读、无需登录。

## 组成

| 目录 | 形态 | 职责 |
|------|------|------|
| `backend/` | Python 3.12 + FastAPI | 解析课表工作簿、校验、导入预览/确认、持久化、管理员 JWT 鉴权 |
| `frontend/admin/` | Vite + React + TS | 管理端：登录、上传 Excel、审核勾选、确认入库、只读查看课表 |
| `frontend/user/` | Kotlin + Jetpack Compose | 学生端：选班、查看本班课表 |
| `docs/` | 文档 | 里程碑、产品与设计说明 |
| `scripts/` | 脚本 | 辅助工具（如 PDF 生成） |
| `samples/` | 本地样例 | 真实课表样例，已 gitignore，不入库 |

## 环境要求

- **后端**：Python 3.12（使用 `backend/.venv`，不依赖 uv）
- **管理端**：Node.js 20+
- **学生端**：Android Studio / JDK 17+，或直接出 APK

## 快速开始

### 1. 后端（端口 8000）

```powershell
cd backend

# 首次：创建虚拟环境并安装依赖
python -m venv .venv
.venv\bin\python.exe -m pip install -e ".[dev]"

# 环境变量（PowerShell；部署前请换成真实密钥）
$env:ADMIN_BOOTSTRAP_USERNAME = "admin"
$env:ADMIN_BOOTSTRAP_PASSWORD = "change-me"
$env:ADMIN_JWT_SECRET = "change-me-too-please-use-32bytes-in-prod"   # ≥32 字节

# 数据库迁移（默认 SQLite：./class_table.db）
.venv\bin\python.exe -m alembic upgrade head

# 后台启动（推荐，不阻塞终端）
.\scripts\dev-server.ps1 start
# 真机 / 局域网联调
.\scripts\dev-server.ps1 start -HostAddress 0.0.0.0

# 查看 / 停止
.\scripts\dev-server.ps1 status
.\scripts\dev-server.ps1 stop
```

也可前台启动：

```powershell
.venv\bin\python.exe -m uvicorn class_table_backend.api.app:create_app --factory --host 127.0.0.1 --port 8000
```

健康检查：`http://127.0.0.1:8000/health`

首次启动时若设置了 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD`，会自动创建管理员（已存在则不会覆盖密码）。也可手动引导：

```powershell
$env:ADMIN_BOOTSTRAP_USERNAME = "admin"
$env:ADMIN_BOOTSTRAP_PASSWORD = "change-me"
.venv\bin\python.exe -m class_table_backend.auth.bootstrap
```

本地开发默认账号（仅本地，勿用于生产）：

| 用户名 | 密码 |
|--------|------|
| `admin` | `change-me` |

### 2. Web 管理端（端口 5173）

```powershell
cd frontend/admin
npm install
npm run dev
```

浏览器打开 **http://127.0.0.1:5173**，用上面的管理员账号登录。

- 开发服务器把 `/api` 代理到 `VITE_API_BASE_URL`（默认 `http://127.0.0.1:8000`）。
- 构建静态站：`npm run build`（产物 `dist/`）。
- 测试：`npm test`。

管理端流程：登录 → 上传课表 Excel → 预览解析行与问题 → 勾选待入库行 → 确认入库 → 按班查看课表。

### 3. Android 学生端

用 Android Studio 打开 `frontend/user/`，或：

```powershell
cd frontend/user
.\gradlew.bat :app:assembleDebug
# APK：app/build/outputs/apk/debug/app-debug.apk
```

安装到设备/模拟器后：

| 环境 | 后端地址 |
|------|----------|
| 模拟器 | `http://10.0.2.2:8000` |
| 真机（同一 Wi-Fi） | `http://<电脑局域网IP>:8000`（后端需 `-HostAddress 0.0.0.0` 启动） |

学生端只读公开接口，无需登录：`GET /classes`、`GET /classes/{id}/schedule`。

## 接口概览

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/health` | 公开 | 健康检查 |
| POST | `/auth/login` | 公开 | 管理员登录，返回 JWT |
| GET | `/auth/me` | 管理员 | 当前管理员信息 |
| GET | `/classes` | 公开 | 班级列表 |
| GET | `/classes/{id}/schedule` | 公开 | 某班课表 |
| POST/PATCH/DELETE | `/classes*` | 管理员 | 班级写操作 |
| * | `/imports*` | 管理员 | 上传 → 预览 → 确认 导入闭环 |

导入三步：`上传/解析 → 预览（无落库副作用）→ 确认（勾选行，单事务写入）`。

## 常用开发命令

```powershell
# 后端测试与检查
cd backend
.venv\bin\python.exe -m pytest
.venv\bin\ruff.exe check src tests

# 管理端测试 / 类型检查
cd frontend/admin
npm test
npx tsc -b

# 学生端打包
cd frontend/user
.\gradlew.bat :app:assembleDebug
```

## 说明

- **产品模型**：一个班级一个课程表；管理端按班级导入，学生端选班后查看本班课表。
- **导入安全**：不静默覆盖已有课表（需显式 `replace=true`）；解析失败不会被当成空课表成功；有歧义的周次规则会进入待审核而不是猜测。
- **数据与密钥**：`samples/`、`*.db`、`.venv`、`node_modules`、构建产物均已 gitignore。`ADMIN_JWT_SECRET` 与生产密码勿提交到仓库。
- **端口**：后端 `8000`，管理端 `5173`。
- 详细开发约定见 [`AGENTS.md`](AGENTS.md)；后端细节见 [`backend/README.md`](backend/README.md)；前端细节见 [`frontend/README.md`](frontend/README.md)。
