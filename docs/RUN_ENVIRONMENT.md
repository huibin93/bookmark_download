# 运行环境梳理（本机实测）

> 本文记录在 **本机（Windows 11, 用户 `260164`）** 上把这个 Android 工程
> 编译、安装到真机并运行的完整环境，以及它与项目根目录 Python 参考实现
> （`wechat_archiver/`）在"运行逻辑"上的对应关系。
>
> 注意：README 里残留的是上一台机器的路径（`D:\01\favorites`、用户 `acang`），
> 以本文为准。

## 1. 工具链（已实测可用）

| 组件 | 实测值 | 位置 |
|---|---|---|
| JDK | OpenJDK 21.0.10（Android Studio 自带 JBR） | `C:\Program Files\Android\Android Studio\jbr` |
| Android SDK | platforms: android-36 / android-36.1；build-tools: 36.0.0 / 36.1.0 / 37.0.0 | `C:\Users\260164\AppData\Local\Android\Sdk` |
| adb | 1.0.41 (37.0.0) | `C:\Users\260164\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Gradle | 9.4.1（由 wrapper 自动下载） | `gradle/wrapper/gradle-wrapper.properties` |
| AGP / Kotlin / KSP | 9.2.1 / 2.3.20 / 2.3.6 | `build.gradle.kts` |

工程目标：`compileSdk 36`、`minSdk 29`、`targetSdk 36`、JDK 21。

`gradle.properties` 已把 `org.gradle.java.home` 固定指向 JBR，因此命令行无需手动
设 `JAVA_HOME` 也能用对的 JDK（仍建议显式设置以防万一）。

## 2. 一次性准备：`local.properties`

工程不提交 `local.properties`（机器相关）。本机已生成，内容：

```properties
sdk.dir=C\:\\Users\\260164\\AppData\\Local\\Android\\Sdk
```

若换机器，改成该机的 Android SDK 路径即可。

## 3. 真机（已连接）

```text
adb devices -l
5d510bb4   device   product:renoir  model:M2101K9C  device:renoir
```

`M2101K9C` = 小米 Redmi（renoir）。已通过 USB 调试连接，可直接 `adb install`。
首次安装时 MIUI 可能弹"确认安装/USB 安装"对话框，需要在手机上手动点允许。

## 4. 构建 / 安装 / 测试命令

PowerShell（项目根目录 = 本文件上两级，即 `bookmark_download\`）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# 编译 Debug APK
.\gradlew.bat :app:assembleDebug

# 直接编译并安装到已连接真机（推荐：一步到位）
.\gradlew.bat :app:installDebug

# 或手动安装产物
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 单元测试
.\gradlew.bat :app:testDebugUnitTest
```

产物：`app\build\outputs\apk\debug\app-debug.apk`
包名 / 启动 Activity：`com.localarchive.wechat` / `.MainActivity`

启动已安装的 App：

```powershell
adb shell monkey -p com.localarchive.wechat -c android.intent.category.LAUNCHER 1
```

## 5. 与 Python 参考实现的运行逻辑对应

根目录的 `wechat_archiver/`（CDP 驱动 Chrome 的桌面端归档器）和本 Android App
是"同一套归档思路的两种载体"。对应关系：

| 归档环节 | Python（`wechat_archiver/`） | Android（本工程） |
|---|---|---|
| 抓取载体 | 桌面 Chrome + CDP（离屏渲染、复用 Cookie） | App 内置 WebView（桌面 UA + 注入 desktop viewport） |
| 链接去重 | `links.py`：`__biz+mid+idx+sn` / 短链 token | `core/url/WechatUrlNormalizer` + `normalized_url` 唯一键 |
| 队列 / 状态 | SQLite `articles` 表（pending/running/done/failed…） | Room `DownloadTask`/`LinkRecord` + `WorkManager` |
| 自动保存网页 | `cdp_runner.py` 抓正文 + 落盘 | `ArchiveBrowser` 页面加载完成自动 `saveCurrentPage` |
| 正文 / 图片 | 读 `img[data-src]`，带 Referer 下载到本地 | `WechatHtmlParser` + `ArchiveFileStore.downloadImages`（带 Referer，改写为本地相对路径） |
| 深度 1 发现 | 文章内发现微信文章入队 `depth=1` | 解析 `<a href>` + 整页扫描，入 `WorkManager` 队列 |
| 文档专辑 | `mp/appmsgalbum`：打开专辑页、滚动到底、点"展开更多"全量入队 | 专辑页先保存解析，首页"存档专辑"人工确认后 `enqueueDiscoveredArticlesForAlbum` 批量入队 |
| 反爬保护 | `blocking.py`：验证墙检测 + 熔断 + 自适应退避 | WebView 后台任务失败 → 标记 `NEEDS_MANUAL_OPEN`，转人工在 WebView 打开 |

差异说明：CDP 能滚动/点击"展开更多"做整页专辑展开；WebView 端只能解析已渲染
HTML 中可见的链接，因此专辑展开依赖页面把目录链接渲染出来，必要时由用户在
WebView 内手动滚动后再保存。这是端能力差异，不是 bug。

## 6. 本地数据落点（设备上）

- Room 库：App 私有 `wechat_archive.db`
- 私有归档：`files/archive/articles/<link_id>/{raw,readable}.html, text.txt, metadata.json, assets/`
- 用户可见导出：`Downloads/WechatArchive/articles/<link_id>_<title>/`
- 默认关闭 Android 自动备份，避免归档被云备份。
