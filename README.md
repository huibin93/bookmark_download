# 微信公众号文章个人归档器 Android MVP

这是根据 `ANDROID_AUTO_CRAWLER_DESIGN.md` 实现的 Android MVP。第一版只处理用户主动分享或打开的微信公众号文章链接，数据默认保存在本机 Room 数据库和 App 私有文件目录；用户可以从首页多选文章导出到 Downloads。

开发记录、设计决策、验证结果和遗留问题见 [docs/IMPLEMENTATION_LOG.md](docs/IMPLEMENTATION_LOG.md)。

## 已实现

- 接收系统分享 `ACTION_SEND text/plain`。
- 接收网页打开 `ACTION_VIEW http/https`，第一版只处理 `mp.weixin.qq.com`。
- 标准化微信公众号文章 URL，并按 `normalized_url` 去重。
- Room 保存链接收件箱、文章元数据、发现链接和下载任务。
- DataStore 保存最近打开链接。
- Compose + Material 3 单页首页，左侧抽屉提供全部、已保存、未下载、专辑、失败等过滤视图。
- WebView 使用桌面浏览器 User-Agent 和宽视口打开在线页面，页面加载完成后默认自动保存。
- 保存当前页面 HTML、标题、公众号名称、正文文本和发现链接。
- 保存时会提取正文图片地址，下载到本地 `assets/` 目录，并把离线 HTML 的图片地址改成本地相对路径。
- 页面上的“保存”按钮会重新保存当前页面，并覆盖 Downloads 中同一文章目录下的导出文件。
- 已保存文章默认在同一个浏览器框架中打开本地归档内容；点击“刷新”才在同一页面切换到在线模式并重新保存。
- 页面支持复制原始链接，便于在其他浏览器中打开。
- 从深度 0 页面提取深度 1 公众号文章链接并加入 WorkManager 队列。
- WorkManager 后台保存深度 1 页面，但不继续递归。
- 文章列表支持按标题、公众号和链接搜索。
- 支持多选文章导出到 `Downloads/WechatArchive/articles/`。
- 支持多选删除已保存文章；删除时保留链接去重记录，再次分享会重新下载。
- 专辑链接会保存并提取专辑下文章链接；解析器会从 `<a href>` 和整页 HTML 中补充扫描公众号文章/专辑 URL，首页可人工点击“存档专辑”开始入队。

## 未实现

- root、Hook、辅助功能服务、读取微信内部数据、绕过登录或验证码：按设计明确不实现。
- 专辑展开、书签导入、图片缓存、Markdown 导出、全文搜索：设计文档中属于后续阶段，不在本 MVP 范围。
- 需要登录或验证码的页面后台下载可能失败，会标记为需要手动打开。

## 构建

环境要求：

- Android Studio 或 Android SDK，当前工程使用 `compileSdk 36`、`minSdk 29`。
- JDK 21。当前机器可使用 Android Studio 自带 JBR：`C:\Program Files\Android\Android Studio\jbr`。

命令行构建：

```powershell
cd D:\01\favorites\wechat-archive-android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

如果 `local.properties` 不存在，创建并指向 Android SDK：

```properties
sdk.dir=C\:\\Users\\acang\\AppData\\Local\\Android\\Sdk
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

运行单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## 使用

1. 在微信文章页使用系统分享，把文本分享到“公众号归档”。
2. 或在系统“打开方式”中选择“公众号归档”打开 `mp.weixin.qq.com` 链接。
3. App 会记录链接，并用内置 WebView 打开文章。
4. 页面加载完成后会自动保存；页面上的“保存”可用于手动覆盖保存。
5. App 保存当前页面，提取页面中的下一层公众号文章链接并加入队列。
6. 回到首页查看文章列表；已保存文章默认打开本地归档，点击“刷新”才重新联网。
7. 勾选文章后可以批量导出到 Downloads，也可以删除本地归档。

## 本地数据

- Room 数据库：App 私有数据库 `wechat_archive.db`。
- 归档文件：App 私有目录 `files/archive/articles/<link_id>/`，图片位于其 `assets/` 子目录。
- 用户可见导出目录：`Downloads/WechatArchive/articles/<link_id>_<title>/`。
- App 启动时会创建 `Downloads/WechatArchive/README.txt`，用于确保主文件夹可见。
- 默认关闭 Android backup，避免归档数据自动云备份。

Android 10+ 使用 MediaStore 写入 Downloads，不需要申请“管理全部文件”权限。每次导出或在 WebView 页面点击“保存”都会覆盖同一文章目录下的同名导出文件。
