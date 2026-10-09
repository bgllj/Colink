---
feature: admin-console
status: in-progress
updated: 2026-10-10
branch: feat/admin-console
commits: 27e2b50..  # leave empty while in progress; fill at delivery
---

# Admin Console & Frontend Split

## Report

## [S1] Problem

管理员无法通过界面完成「上传课�?Excel �?解析预览 �?逐行审核 �?确认入库」这条链路，只能借助 curl 或脚本直接打后端 API，易错且无法给非开发角色使用�?
同时 `frontend/` 目前是一个单一 Android 工程（`app` 模块 + Gradle 根），没有区分学生端与管理端。本次要求把 `frontend/` 整理�?`user/` �?`admin/` 两个并列的项目目录�?
另外，现有导�?API（上�?预览/确认）完全无鉴权，属于写操作；一�?API 暴露到可信局域网之外即可被任意人调用。本次要求补上管理员登录�?
## [S2] Design

### S2.1 目录布局

```text
frontend/
  README.md                 # 说明两个子项目与本地启动方式
  user/                     # 既有 Android 工程整体迁入（结构不动）
    settings.gradle.kts
    build.gradle.kts
    gradlew / gradlew.bat
    gradle/
    app/                    # �?:app 模块，包�?namespace 保持 com.colink.app
    local.properties        # 不入�?  admin/                    # 新建 Web 管理�?    package.json
    vite.config.ts
    index.html
    src/
```

- `frontend/user/` 是独�?Gradle 工程根（自带 wrapper），不是 `frontend/` 下的子模块�?- `frontend/admin/` 是独�?Vite 工程根，�?Android 工程无构建耦合�?- `frontend/` 本身不再�?Gradle 根；删除其下�?`settings.gradle.kts` / `build.gradle.kts` / `gradlew*`（它们会出现�?`frontend/user/` 中）�?- �?`README.md` 目录表与 `AGENTS.md` 中的路径表述同步改为 `frontend/user/`、`frontend/admin/`�?
### S2.2 管理�?Web（`frontend/admin`�?
技术栈：Vite + React 18 + TypeScript，无 UI 组件库（保持依赖与复杂度成正比）；开发期�?Vite proxy �?`/api` 转发到后端�?
页面与流程（首版只做导入审核闭环 + 只读课表）：

1. **登录�?* `#/login`：用户名 + 密码 �?`POST /api/auth/login`，成功后保存 token 并跳转导入页�?2. **导入上传�?* `#/import`：选择 `.xls`/`.xlsx` 文件，可选填 `max_week`，提�?`POST /api/imports`。展示返回的 `import_id`、`status`、`duplicate_of`、批次级 `issues`；成功后进入预览页�?3. **导入预览/审核�?* `#/import/:importId`�?   - 顶部展示批次 meta（文件名、profile、学年、学期、开学日期、班级、max_week）与批次�?issues�?   - 行表格：`course_code` / `course_name` / `weekday` / `period_start` / `period_end` / `week_text` / `week_ranges` / `room_text` / 溯源（`sheet`+`coordinate`+`line_index`+`raw_line`�? 行状�?/ �?issues�?   - 复选框多选行；默认全部选中；可单行/全�?全不选�?   - �?issues 以可展开列表展示（code + message + severity + 溯源）�?   - 「确认入库」按钮：`POST /api/imports/{id}/confirm`，body �?`{ row_ids: [...] }`（仅勾选行）�?   - 确认成功后展�?`rows_confirmed` 与结�?issues，并跳转课表页�?4. **课表�?* `#/schedule`：只读展�?`GET /api/schedule` 的已确认课程（按周次过滤可选），用于确认结果核对�?
路由守卫：未登录访问 `#/import*`、`#/schedule` 时重定向�?`#/login`；token �?`sessionStorage`�?01 时清�?token 并跳转登录�?
Vite 开发代理：`/api` �?`VITE_API_BASE_URL`（默�?`http://127.0.0.1:8000`），前端所有请求走 `/api/...`，生产构建为静�?`dist/`�?
### S2.3 后端鉴权（仅管理员）

新表 `admin_user`�?
| �?| 类型 | 约束 |
|----|------|------|
| id | String(36) | PK |
| username | String(64) | unique, index |
| password_hash | String(255) | 非空 |
| created_at | DateTime | UTC |

密码哈希使用标准�?`hashlib.pbkdf2_hmac('sha256', ...)`（salt 16 字节随机，迭�?600_000），存储格式 `pbkdf2_sha256$<iterations>$<salt_hex>$<hash_hex>`。不引入 bcrypt/argon2 二进制依赖。校验使�?`hmac.compare_digest`�?
管理员引导：`create_app` 启动时若设置�?`ADMIN_BOOTSTRAP_USERNAME` �?`ADMIN_BOOTSTRAP_PASSWORD`，则在库中不存在该用户名时创建之；已有同名用户不改密码。另提供模块入口 `python -m class_table_backend.auth.bootstrap`，便于在测试和运维中显式创建�?*不提供注册接口�?*

JWT�?
- 算法 HS256，库 PyJWT（纯 Python）�?- 密钥来自环境变量 `ADMIN_JWT_SECRET`；未设置时开发模式回退到固定值并在日志警告，测试内显式传入�?- 过期秒数 `ADMIN_JWT_TTL_SECONDS`，默�?`3600`�?- Claims：`sub` = admin_user.id，`username`，`exp`，`iat`�?
新端点：

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/auth/login` | 公开 | body `{username,password}` �?`200 {access_token,token_type:"bearer"}`；失�?`401` |
| GET | `/auth/me` | Bearer | �?`{id,username}` |

鉴权边界�?
- **公开**：`GET /health`、`POST /auth/login`、`GET /schedule`（学生端 Android 只读依赖它）�?- **需 Bearer**：`/imports` 全部子路径（上传/预览/行分�?确认）�?- 未带 token �?token 无效/过期 �?`401`，body `{"detail": "..."}`�?- �?FastAPI dependency（`require_admin`）挂�?imports 路由上；测试通过 `dependency_overrides` 或真实登录获�?token�?
### S2.4 错误与边�?
- 登录失败统一 `401 {"detail": "用户名或密码错误"}`，不区分用户名不存在与密码错误�?- 过期 token �?`401`；格式错�?token �?`401`�?- 上传仍沿用现�?`MAX_UPLOAD_BYTES` 限制�?issue 语义；鉴权失败优先于文件校验（先 401 再处理文件）�?- 确认接口�?`row_ids` 语义不变：`null` 表示确认全部当前可选行，显式列表则只确认列表内行�?- 管理端对已有 `duplicate_of` 的上传不做拦截，按现状展示提示，由管理员决定是否继续�?
### S2.5 测试边界

后端 pytest�?
- 登录成功/密码错误/用户名不存在�?- `/imports*` �?token �?401；带 token �?200�?- `/schedule`、`/health` �?token 可访问�?- token 过期 �?401�?- bootstrap 创建管理员：首次创建、不覆盖已有密码�?- 现有导入/课表测试改为�?override 下跑（保持无鉴权注入）或统一登录�?token，保证不回归�?
管理端（Vitest）：

- api client：带 token 请求头�?01 清理�?- 预览页行勾选状态变更逻辑（全�?反�?单行）�?- 不做端到端浏览器测试�?
### S2.6 依赖增量

后端：`pyjwt`。其余沿用现有依赖；不引�?passlib/bcrypt�?
管理端：`react`、`react-dom`、`react-router-dom`、`typescript`、`vite`、`@vitejs/plugin-react`、`vitest`（dev）�?
## [S3] Out of Scope

- 学生端账�?注册/登录；Android 端任何改动（只做目录迁移，不改代码逻辑）�?- 已入库课表的行级修正、删除、覆盖重导入�?- 导入批次历史列表、失败重试面板、多学期切换运维视图�?- 社区、题库、组队、通知等产品规划中的其�?epics�?- 云部署、HTTPS、密码找回、多管理员角�?RBAC�?- 深色主题、i18n、无障碍完善、移动端适配（管理端按桌面浏览器优先）�?- 对既有解析规则（profile、周表达式）的任何行为改动�?
## Tasks

- [ ] T1: 将现�?Android 工程整体迁入 `frontend/user/`，并新建 `frontend/admin` Vite+React+TS 脚手架（�?`/api` 代理与空页面路由骨架）�?acceptance: `frontend/user` �?`gradlew :app:assembleDebug` 路径成立�?Gradle 根在 `frontend/user`；`frontend/admin` �?`npm install && npm run dev` 启动空壳；`frontend/` 下不再有 Gradle 根文�?(covers: S2.1, S2.2)
- [ ] T2: 增加 `admin_user` 表、Alembic 迁移、PBKDF2 密码哈希�?bootstrap 创建逻辑 �?acceptance: 迁移可升级；bootstrap 首次创建用户、二次运行不改密码；哈希校验对错密码行为正确 (covers: S2.3)
- [ ] T3: 实现 `POST /auth/login`、`GET /auth/me` �?JWT 签发/校验依赖 �?acceptance: 正确凭证返回 Bearer token；错误凭�?401；`/auth/me` 需合法 token (covers: S2.3)
- [ ] T4: �?`require_admin` 保护 `/imports` 全部路由，`/schedule` �?`/health` 保持公开 �?acceptance: �?token �?`/imports` �?401；带 token 可上�?预览/确认；`/schedule` �?token �?200 (covers: S2.3, S2.4)
- [ ] T5: 管理端登录页、token 存储、路由守卫与 401 处理 �?acceptance: 未登录访问受保护路由跳登录；登录成功进入导入页；401 自动登出 (covers: S2.2)
- [ ] T6: 管理端导入上�?+ 预览审核页（行多选、issue 展示、确认入库）�?acceptance: 能上传文件看到批�?issues；能全�?单选行并提�?`row_ids` 确认；确认结果可�?(covers: S2.2, S2.4)
- [ ] T7: 管理端只读课表页 �?acceptance: 展示 `GET /api/schedule` 的课程与学期信息 (covers: S2.2)
- [ ] T8: 后端鉴权/导入回归测试 + 管理�?api/勾选逻辑测试 �?acceptance: `pytest` 全绿（含新鉴权用例，既有用例不回归）；`npm test` 全绿 (covers: S2.5)
- [ ] T9: 更新�?`README.md`、`AGENTS.md`、`backend/README.md`、`frontend/README.md` 中的目录与管理端启动说明 �?acceptance: 文档中的路径与实际布局一致，含管理员 bootstrap 与登录方�?(covers: S2.1, S2.3)
