# Colink 前端

`frontend/` 下包含两个互相独立的项目：

| 目录 | 形态 | 说明 |
|------|------|------|
| `user/` | Android（Kotlin + Jetpack Compose） | 学生端 Colink，查看个人课表 |
| `admin/` | Web（Vite + React + TypeScript） | 管理端，导入课表 Excel 并审核入库 |

两个项目没有构建期依赖，可分别开发与打包。

## user/ — Android 学生端

用 Android Studio 打开 `frontend/user/`，或：

```powershell
cd frontend/user
./gradlew :app:assembleDebug
```

功能与对接说明见 `user/` 内文档与根 `README.md`。学生端只读调用 `GET /schedule`，无需登录。

## admin/ — Web 管理端

### 环境要求

- Node.js 20+（开发验证使用 24.x）
- 后端已启动（默认 `http://127.0.0.1:8000`）

### 本地开发

```powershell
cd frontend/admin
npm install
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`。开发服务器把 `/api` 代理到 `VITE_API_BASE_URL`（默认 `http://127.0.0.1:8000`）。

```powershell
# 如需指向其它后端
$env:VITE_API_BASE_URL="http://192.168.1.10:8000"
npm run dev
```

### 构建

```powershell
npm run build   # 产物在 dist/
```

### 测试

```powershell
npm test
```

### 管理员账号

管理端使用管理员账号登录（无注册入口）。在后端启动前设置引导环境变量：

```powershell
$env:ADMIN_BOOTSTRAP_USERNAME="admin"
$env:ADMIN_BOOTSTRAP_PASSWORD="change-me"
$env:ADMIN_JWT_SECRET="change-me-too"
```

后端启动时若库中不存在该用户名会自动创建；已存在则不会覆盖密码。

### 功能范围（首版）

1. 登录
2. 上传课表 Excel（`.xls` / `.xlsx`）并解析
3. 逐行审核：查看解析结果、问题坐标、勾选待入库行
4. 确认入库（只写入勾选行）
5. 只读查看已入库课表

不在首版范围：课表行级修改、导入历史、多学期运维、学生端账号。
