# Colink

一个轻量、离线优先的 Android 课程表应用。Colink 使用 Kotlin、Jetpack Compose 和 Material 3 构建，专注于让学生快速查看当天课程，并保留简洁的本地个人信息管理能力。

> 当前版本：`v0.1.0` · 初始原型

## 功能

- **今日课程**：首页默认跟随手机系统日期，只显示当天课程。
- **任意日期查看**：点击顶部日期打开日期选择器，可查看历史或未来日期。
- **周次计算**：开学时间可在“我的”页设置，当前周次根据开学日期和系统日期自动计算；学期外日期显示“学期外”。
- **系统时间校准**：页面定时读取系统时间，跨天或系统时间变化后自动刷新。
- **课程详情**：点击课程卡片查看课程代码、地点、节次和开课周次。
- **本地个人资料**：姓名、学号、院系、专业、年级和班级保存在设备本地，不依赖账号或网络。
- **清晰的课程数据**：支持单双周、离散周和同一时段多地点课程；当前种子数据已移除辅修课程。

## 界面预览

应用采用暖纸色背景与墨绿色主色，课程以纵向时间线呈现，减少传统周课表在手机上的横向滚动和信息拥挤。

后续可在此处补充真机截图：

```text
docs/screenshots/today.png
docs/screenshots/profile.png
```

## 技术栈

- Kotlin `2.0.21`
- Jetpack Compose + Material 3
- Android Gradle Plugin `8.5.2`
- compileSdk `35`
- minSdk `29`（Android 10）
- SharedPreferences（个人资料与开学时间）
- Java Time API（日期与周次计算）

## 项目结构

```text
app/src/main/java/com/colink/app/
├── MainActivity.kt             # 应用入口
├── CourseModels.kt              # 课程模型、种子数据、周次计算
├── data/Contracts.kt            # 可替换的数据源/导入接口
└── ui/
    ├── ColinkApp.kt             # 主题、底部导航和页面容器
    ├── schedule/                # 今日课程、日期选择和课程详情
    ├── profile/                 # 个人资料与开学时间设置
    └── theme/                   # Colink Material 3 主题
```

## 开始使用

### 环境要求

- Android Studio（建议使用当前稳定版）
- JDK 17 或更高版本
- Android SDK 35

### Android Studio

1. 克隆仓库并使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 连接 Android 设备或启动模拟器。
4. 运行 `app` 配置。

### 命令行构建

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 数据与隐私

当前版本不联网、不上传课程或个人资料。课程数据以内置种子数据提供；个人资料和开学时间仅保存在应用自己的 SharedPreferences 中。卸载应用会清除这些本地数据。

## 已知限制

- 暂不支持从 XLS、CSV 或教务系统自动导入课程。
- 暂不支持云同步、账号体系和多学期管理。
- 课程节次暂未映射到具体钟点，当前以“第 N–M 节”展示。
- 当前仅提供浅色主题。

## 规划

- [ ] XLS/CSV 课程导入与预览
- [ ] 多学期与课程表备份
- [ ] 可选的课程提醒
- [ ] Room 数据库与可替换仓库实现
- [ ] 深色模式与更完整的无障碍支持

## 贡献

欢迎提交 Issue 或 Pull Request。建议在提交前运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 许可证

许可证尚未确定。正式公开发布前，请在此处补充许可证（例如 Apache-2.0 或 MIT）。
