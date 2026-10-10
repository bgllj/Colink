# 课表导入后端

## 简介

本后端用于导入学校导出的课表表格（`.xls` / `.xlsx`），解析出课程与上课安排，校验后生成导入预览，由用户确认后写入数据库。解析与校验不依赖 HTTP，可单独在脚本或任务中复用。

## 环境

- Python 3.12
- 虚拟环境：`backend/.venv`
- 注：本机 msys Python 需通过 pacman 安装 `fastapi` / `pydantic` 等依赖，或改用 `--system-site-packages` 创建虚拟环境后再补装其余依赖。

## 本地启动

```bash
cd backend
# 若尚未创建虚拟环境
python -m venv .venv
.venv/bin/python.exe -m pip install -e ".[dev]"   # 或按环境安装 sqlalchemy/xlrd/openpyxl/fastapi

# 配置数据库（PowerShell 语法；SQLite 本地文件示例）
$env:DATABASE_URL="sqlite:///./class_table.db"

# 配置管理员引导与 JWT（仅管理端需要；见下文「管理员鉴权」）
$env:ADMIN_BOOTSTRAP_USERNAME="admin"
$env:ADMIN_BOOTSTRAP_PASSWORD="change-me"
$env:ADMIN_JWT_SECRET="change-me-too"

# 执行数据库迁移
.venv/bin/python.exe -m alembic upgrade head

# 启动开发服务（推荐：后台启动，不阻塞当前终端 / agent）
.\scripts\dev-server.ps1 start

# 真机 / 局域网访问（绑定 0.0.0.0）
.\scripts\dev-server.ps1 start -HostAddress 0.0.0.0

# 查看 / 停止
.\scripts\dev-server.ps1 status
.\scripts\dev-server.ps1 stop

# 也可以前台启动（终端会一直占用，Ctrl+C 结束；agent 勿用这种方式）
.venv/bin/python.exe -m uvicorn class_table_backend.api.app:create_app --factory --reload --port 8000
.venv/bin/python.exe -m uvicorn class_table_backend.api.app:create_app --factory --reload --host 0.0.0.0 --port 8000
```

### 真机局域网联调

1. 手机与电脑连接同一 Wi-Fi。
2. 用上面 `--host 0.0.0.0` 的命令启动后端。
3. 查看电脑局域网 IP（PowerShell）：`Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }`。
4. 在安卓端「我的」页把服务器地址填为 `http://<电脑局域网IP>:8000`（模拟器才用 `http://10.0.2.2:8000`）。
5. 若连不上，检查 Windows 防火墙是否放行 TCP 8000（或临时关闭防火墙测试）。

## 管理员鉴权

导入与班级管理接口需要管理员 Bearer token；`GET /classes`、`GET /classes/{id}/schedule`、`GET /health`、`POST /auth/login` 保持公开，供学生端使用。

| 环境变量 | 说明 |
|----------|------|
| `ADMIN_BOOTSTRAP_USERNAME` | 首次启动时创建的管理员用户名（可选） |
| `ADMIN_BOOTSTRAP_PASSWORD` | 对应密码（可选；仅在创建时使用，不会覆盖已有用户） |
| `ADMIN_JWT_SECRET` | JWT HMAC 密钥；未设置时使用开发回退值并告警 |
| `ADMIN_JWT_TTL_SECONDS` | token 有效期，默认 3600 |

也可显式创建管理员：

```bash
ADMIN_BOOTSTRAP_USERNAME=admin ADMIN_BOOTSTRAP_PASSWORD=change-me \
  .venv/bin/python.exe -m class_table_backend.auth.bootstrap
```

密码哈希为标准库 PBKDF2-HMAC-SHA256（60 万次迭代），不引入 bcrypt。

## API

产品模型：**一个班级一个课程表**。班级是一等实体，课表数据归属班级。

### 公开

- `POST /auth/login`：管理员登录。请求体 `{"username","password"}`，返回 `{"access_token","token_type":"bearer"}`；失败统一 `401`。
- `GET /health`：健康检查。
- `GET /classes`：班级列表 `[{id, name, grade?, major?, department?}]`，供学生端选班。
- `GET /classes/{class_id}/schedule?week=N`：读取该班已确认课表。返回 `class_id`、`class_name`、`courses` 与 `semester`；每次上课安排含 `weekday`（1=周一）、`period_start` / `period_end`、`week_text`（原始周次文本）、`weeks`（展开后的周次列表）、`room_text` 与来源坐标。可选查询参数 `week=N` 只返回该周有课的安排。班级不存在 → `404`。

### 需要 `Authorization: Bearer <token>`

- `GET /auth/me`：返回当前管理员 `{"id","username"}`。
- `GET /classes` / `POST /classes`：班级列表 / 新建（重名 → `409`）。
- `PATCH /classes/{class_id}`：改名等；`DELETE /classes/{class_id}`：删除班级（级联删除该班课表与导入批次）。
- `PATCH /classes/{class_id}/semester`：修改开学日期。请求体 `{"start_date": "YYYY-MM-DD"}`（显式 `null` 表示清空）。写入该班最近一次已确认导入批次的 `start_date`，返回更新后的 `semester` 元信息。班级不存在 → `404`；尚无已确认课表 → `404`。
- `POST /imports`：上传并解析课表。`multipart` 表单字段 `file`（必填），可选 `max_week`、`class_id`、`class_name`。返回 `import_id`、解析状态、校验问题列表；若文件哈希与已有导入批次相同，会返回 `duplicate_of` 指向原批次（幂等，不重复入库）。
- `GET /imports/{import_id}`：获取导入批次预览（元信息、校验问题、解析出的行）。
- `POST /imports/{import_id}/confirm`：确认导入并写入该班课表。请求体：`row_ids?`（省略表示确认全部可导入行）、`class_id?` / `class_name?`（目标班级；可自动建班）、`replace?`（默认 `false`）。目标班级已有确认课表且未 `replace` → `409`（`SCHEDULE_EXISTS`）；`replace=true` 在同一事务内整表替换。
- `GET /imports/{import_id}/rows?limit=&offset=`：分页读取导入批次的行明细。

无 token / token 无效或过期 → `401`。

## 测试

```bash
cd backend && .venv/bin/python.exe -m pytest
```

## 导入行为说明

- 格式 profile：`chengdu_wenli_v1`（成都文理学院导出格式）。
- 周次表达式中的尾部 `单` / `双`（单/双周）修饰其前面的整段列表。
- 预览阶段不写入课表数据；确认阶段按文件哈希幂等，重复上传同一文件不会产生重复课表记录。
- 样例文件：`samples/excel/25计科9(1).xls`（仅本地手工验证；不入库。依赖该文件的测试在缺失时自动 skip）。
