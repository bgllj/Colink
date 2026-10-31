# Colink 本地课程表

Colink 是一个离线优先的 Android 课程表应用。课表文件直接在手机上解析和保存，不依赖后端服务或 Web 管理端。

## 当前仓库

本仓库 `main` 分支仅保留 Android 本地课程表软件，工程目录为 `frontend/user/`。后端和 Web 管理端已从仓库移除，历史设计文档仍保留在 `docs/` 中供参考。

主要功能：

- 从设备选择 `.xls` / `.xlsx` 课表文件并在本地解析
- 导入审核时查看解析问题、选择需要导入的行
- 审核时修改开学日期，确认后保存到本地配置
- 首页、课表、我的三个底部选项卡
- 本地保存课程数据，无需登录和网络连接

## 环境要求

- Android Studio
- JDK 17+
- Android SDK（以 `frontend/user/sdk-packages.txt` 为准）

## 构建 APK

用 Android Studio 打开 `frontend/user/`，或在 PowerShell 中执行：

```powershell
cd frontend/user
.\gradlew.bat :app:assembleDebug
```

生成的调试 APK：

```text
frontend/user/app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
adb install -r frontend/user/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.colink.app/.MainActivity
```

## 测试与检查

```powershell
cd frontend/user
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon
```

## 目录说明

| 目录 | 说明 |
|------|------|
| `frontend/user/` | Android 应用源码、Gradle 工程和单元测试 |
| `docs/` | 历史产品与设计文档 |
| `scripts/` | 通用辅助脚本 |

本地样例、构建产物、IDE 配置和密钥文件均不应提交到仓库。详细协作约定见 [`AGENTS.md`](AGENTS.md)。
