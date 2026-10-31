# Colink Android 客户端

`frontend/user/` 是 Colink 的独立 Android 工程，使用 Kotlin 和 Jetpack Compose 构建本地课程表应用。

## 开发与构建

用 Android Studio 打开当前目录，或在 PowerShell 中执行：

```powershell
cd frontend/user
.\gradlew.bat :app:assembleDebug
```

运行单元测试和 lint：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon
```

## 功能范围

- 选择本地 `.xls` / `.xlsx` 课表文件
- 在导入审核页查看问题并选择导入行
- 审核时修改开学日期
- 本地保存课程表和学期配置
- 首页、课表、我的三个底部选项卡

应用不依赖后端接口，也不包含 Web 管理端。详细说明见仓库根目录 [`README.md`](../README.md)。
