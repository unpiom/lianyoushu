# 练有数

练有数是一款免费的离线 Android 健身与体重记录应用。适用于 Android 8.0 及以上设备。

[下载最新版 APK](https://github.com/unpiom/lianyoushu/releases/latest/download/lianyoushu.apk) · [下载内置示例计划 JSON](https://github.com/unpiom/lianyoushu/releases/latest/download/example-plan.json) · [版本记录](https://github.com/unpiom/lianyoushu/releases) · [正式发布行动记录](PLAN/)

新安装时可在「训练」直接开练。已有用户可在「训练 → 新建计划」点「重置计划」，确认后用 4 个内置计划替换当前每周计划。通过「训练 → 每周计划 → 导入计划」也可单独添加示例计划。

觉得好用，欢迎给项目点个 Star。

## 功能

- **体重**支持当天记录、历史补记、7 天均重和 30 天曲线。当天记录需要二次确认，确认后锁定。
- **达标预测**设置目标后，按近 30 天的体重趋势估算达标日期和截止日前的达标概率。估算需要至少 6 条记录、跨度至少 5 天，最近一次称重距今最多 7 天。跨度为 5～6 天、7～13 天、14 天及以上时，截止日分别最远可选到 7、14、56 天后。动态水位圆显示概率或缺失条件。
- **训练**可按星期安排每周重复的训练计划，并设置时长、动作和组数。开练后可逐组记录重量与次数，自动开始组间倒计时，并查看同一动作的上次记录。结束训练、快速打卡和补打卡时，可选择撤销或确认。确认后记录锁定，并显示打卡成功提示。撤销结束训练的打卡会返回训练页，可继续记录。
- **计划文件**在「训练 → 每周计划」可通过 JSON 文件导入或导出计划。导入会新增计划并跳过内容完全相同的条目。
- **复盘**可用月历查看训练记录和逐组数据。从首次安装当天起，有训练计划的日期按确认打卡的完成情况着色。周报汇总实际训练时长，并比较相邻两段 7 天均重。腰围可单独记录和查看趋势。
- **更新**在「今天」页底部点「检查更新」，可比较当前版本和 GitHub 最新正式版。有新版时可下载并通过 Android 安装界面覆盖升级。覆盖升级后首次打开时会弹出该版本的更新公告。

## 数据与权限

体重、腰围、训练计划和打卡保存在手机的应用私有存储中，卸载应用会清除这些数据。应用可离线记录，检查更新需要联网。

从 1.0 覆盖升级后，已有体重记录视为已确认。新版对当天体重进行确认锁定，历史日期新补录的条目可以修正。

覆盖升级至 1.2.2 后，首次打开应用会清除旧版在练有数内保存的步数及目标，已有训练打卡视为已确认。

## 本地构建

Windows 可使用系统自带的 Windows PowerShell 5.1，也支持 PowerShell 7。构建需要 JDK 17 或更新版本、Gradle 8.11.1、Android SDK Platform 35 和 Build Tools 35.0.1。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后，在仓库目录运行下列命令。Gradle 可通过 `PATH` 或 `-Gradle` 参数指定。

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\build.ps1
```

构建产物默认为仓库目录下的 `练有数.apk`。依赖已缓存时可加 `-Offline` 离线构建，也可用 `-OutputApk` 指定输出路径。构建脚本在 `.build/signing` 保存本地签名材料。使用相同签名的 APK 即可覆盖更新，保留应用数据。

应用内更新读取 `unpiom/lianyoushu` 的 GitHub Releases。正式发布时，标签应与 APK 版本对应（例如 `v1.4.1`），APK 附件名为 `lianyoushu.apk`，并使用原签名和递增的 `versionCode`。同时附上随包的 [example-plan.json](app/src/main/assets/example-plan.json)，供 README 的示例计划入口下载。发布前更新 `strings.xml` 中的 `update_notes`，覆盖升级后可离线查看公告。每个正式版在 [PLAN](PLAN/) 中记录改动、验证和发布结果。
