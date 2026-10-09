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

# 执行数据库迁移
.venv/bin/python.exe -m alembic upgrade head

# 启动开发服务（本机调试）
.venv/bin/python.exe -m uvicorn class_table_backend.api.app:create_app --factory --reload --port 8000

# 启动开发服务（真机 / 局域网访问，必须绑定 0.0.0.0）
.venv/bin/python.exe -m uvicorn class_table_backend.api.app:create_app --factory --reload --host 0.0.0.0 --port 8000
```

### 真机局域网联调

1. 手机与电脑连接同一 Wi-Fi。
2. 用上面 `--host 0.0.0.0` 的命令启动后端。
3. 查看电脑局域网 IP（PowerShell）：`Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }`。
4. 在安卓端「我的」页把服务器地址填为 `http://<电脑局域网IP>:8000`（模拟器才用 `http://10.0.2.2:8000`）。
5. 若连不上，检查 Windows 防火墙是否放行 TCP 8000（或临时关闭防火墙测试）。

## API

- `POST /imports`：上传并解析课表。`multipart` 表单字段 `file`（必填），可选表单字段 `max_week`。返回 `import_id`、解析状态、校验问题列表；若文件哈希与已有导入批次相同，会返回 `duplicate_of` 指向原批次（幂等，不重复入库）。
- `GET /imports/{import_id}`：获取导入批次预览（元信息、校验问题、解析出的行）。
- `POST /imports/{import_id}/confirm`：确认导入并写入课表。请求体可选 `{"row_ids": [...]}`（省略表示确认全部可导入行）。
- `GET /imports/{import_id}/rows?limit=&offset=`：分页读取导入批次的行明细。
- `GET /schedule`：读取已确认课表。返回 `courses` 列表，每个课程含 `course_id`、`course_code`、`name` 与 `meetings`；每次上课安排含 `weekday`（1=周一）、`period_start` / `period_end`、`week_text`（原始周次文本）、`weeks`（展开后的周次列表）、`room_text` 与来源坐标。可选查询参数 `week=N` 只返回该周有课的安排。
- `GET /health`：健康检查。

## 测试

```bash
cd backend && .venv/bin/python.exe -m pytest
```

## 导入行为说明

- 格式 profile：`chengdu_wenli_v1`（成都文理学院导出格式）。
- 周次表达式中的尾部 `单` / `双`（单/双周）修饰其前面的整段列表。
- 预览阶段不写入课表数据；确认阶段按文件哈希幂等，重复上传同一文件不会产生重复课表记录。
- 样例文件：`excel样例/25计科9(1).xls`（仅作手工验证参考，测试使用合成样例，不依赖该文件）。
