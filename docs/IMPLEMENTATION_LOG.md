# 实现记录

日期：2026-06-28

本文记录 Android MVP 的修改记录、设计决策、验证结果和遗留问题。它是工程决策记录，不是聊天流水，也不包含不可审计的内部推理过程。

## 产品范围

这个 App 是个人使用的微信公众号文章归档器。

MVP 目标刻意收窄：

- 接收用户主动分享或打开的微信公众号文章/专辑链接。
- 记录链接，并按标准化 URL 去重。
- 在 App 内置浏览器中打开链接。
- 默认保存页面。
- 只抓取深度 1 的微信公众号文章/专辑链接并加入队列。
- 专辑页先保存和解析，再由用户手动确认是否开始归档专辑文章。
- 支持首页批量导出和删除。

明确不做：

- 读取微信 App 私有数据。
- 辅助功能自动化、root、Hook、绕过登录或验证码。
- 超过深度 1 的递归抓取。
- 云同步、多设备账号体系。

## 修改记录

### Android 基础

- 创建 Kotlin Android 工程，使用 Jetpack Compose、Material 3、Room、DataStore、WorkManager 和 WebView。
- 增加分享和打开入口：
  - `ACTION_SEND text/plain`
  - `ACTION_VIEW http/https`
- 增加 `mp.weixin.qq.com` 链接标准化逻辑，让重复分享落到同一条记录。
- 增加 Room 数据结构：
  - 链接记录
  - 已保存文章
  - 发现链接
  - 下载任务

### 首页

- 将 App 收敛成一个主页面。
- 次级视图放入左侧抽屉。
- 抽屉筛选项：
  - 全部
  - 已保存
  - 未下载
  - 专辑
  - 失败
- 首页列表展示：
  - 标题
  - 公众号/来源
  - 时间
  - 文章/专辑类型
  - 下载、导出、队列状态
- 移除了每行的“打开”“刷新”“打开保存”按钮。
- 当前行交互：
  - 点击整行：打开已保存本地归档；未保存则打开在线页面并保存。
  - 长按整行：选中该行。
  - 复选框：选中或取消选中。
- 保留专辑行里的“存档专辑”，因为它是单独的人工确认动作，不是普通打开动作。

### 浏览页面

- 在线页面和离线页面复用同一个浏览框架。
- 同一套顶部工具栏用于两个模式：
  - 后退
  - 前进
  - 刷新
  - 保存
  - 复制链接
  - 关闭
- 已保存文章默认打开本地归档。
- 点击“刷新”时，在同一个 WebView 内从本地内容切换到在线源链接。
- 离线模式下禁用“保存”；在线页面加载完成后启用“保存”。
- “复制”始终复制源链接，方便用其他浏览器打开。

### 保存和导出

- 在线页面加载完成后自动保存。
- 手动保存会覆盖本地归档和 Downloads 下的导出目录。
- App 私有归档目录：
  - `files/archive/articles/<link_id>/raw.html`
  - `files/archive/articles/<link_id>/readable.html`
  - `files/archive/articles/<link_id>/text.txt`
  - `files/archive/articles/<link_id>/meta.json`
  - `files/archive/articles/<link_id>/assets/`
- 图片提取来源包括 WeChat HTML 中的：
  - `data-src`
  - `data-original`
  - `src`
- 下载后的图片写入 `assets/`，并把 `readable.html` 里的图片地址改成本地相对路径。
- 批量导出目录：
  - `Downloads/WechatArchive/articles/<link_id>_<title>/`
- 删除已保存文章时，删除文章文件和任务记录，但保留链接去重记录。

### 专辑和深度 1 链接

- 解析器从 `<a href>` 提取支持的链接。
- 解析器也会扫描整页 HTML 文本，补充发现脚本或数据字段里的文章/专辑 URL。
- 专辑页先作为一条页面保存。
- 专辑下文章不会自动下载，必须由用户在首页点击“存档专辑”。
- 深度限制为 1；深度 1 页面由 WorkManager 后台保存，但不会继续递归。

## 决策链路

### 只处理用户主动分享/打开的链接

这是稳定性和边界控制的选择。App 不依赖微信内部数据结构，也不需要 root、辅助功能服务或 Hook。用户看到有价值的文章后主动分享，App 负责归档这个明确输入。

### 单页首页加左侧抽屉

核心任务是查看、搜索、选择和导出文章。多页面或底部 Tab 会增加导航成本。单页列表配合抽屉筛选更接近文件管理器/阅读列表，也更适合 MVP。

### 默认打开即保存

用户把链接交给这个 App 的默认意图就是保存。在线页面加载完成后自动保存，减少“打开了但忘记保存”的失败路径。

### 已保存内容优先打开本地文件

归档 App 的价值是离线可读和可复现。已保存文章默认打开本地内容；只有用户点击“刷新”才重新联网并覆盖保存。

### 删除不删除去重记录

删除是清理本地归档，不应该抹掉“这个链接见过”的事实。保留链接记录可以继续去重，也允许用户之后重新打开并下载。

### 在线/离线复用同一个浏览框架

早期在线页和离线页分开后，刷新像是跳到了另一个页面，顶部按钮也重复。现在把它们作为同一浏览组件的两种模式，行为更连续。

### 桌面显示不能只靠 User-Agent

开源浏览器的常见做法是同时设置：

- desktop User-Agent
- `useWideViewPort`
- `loadWithOverviewMode`
- 缩放控件
- DOM storage

参考过的开源实现：

- Lightning Browser `WebViewFactory`
- Lightning Browser User-Agent 选择逻辑
- Lightning Browser desktop User-Agent 常量

但微信公众号页面本身会使用移动 viewport 和响应式逻辑，所以 App 额外注入了 desktop viewport，把在线页面强制为宽页面并缩放显示。

## 当前遗留问题

### 本地归档仍可能是手机样式

在线 WebView 已经可以强制成更接近桌面显示的宽页面。但本地归档不是完整浏览器快照，而是由保存内容生成的 `readable.html`。

当前行为：

- 新打开的在线页面会使用 desktop UA 和注入的 desktop viewport。
- 刷新文章会覆盖保存文件。
- 再次打开已保存文章时，读取本地 `readable.html`。
- 如果本地 `readable.html` 是早期版本保存的，它仍会保持旧的手机样式，直到刷新覆盖。
- 即使刷新后，本地页也是生成的阅读版 HTML，不是在线 WebView 的逐像素快照。

建议的下一步修复：

- 给本地 `readable.html` 增加桌面宽度模板。
- 在生成的本地 HTML 中加入 viewport meta 和 desktop-width wrapper。
- 另存一份注入 viewport 后的 DOM，比如 `snapshot.html`。
- 离线打开时可以优先使用 `snapshot.html` 保持视觉一致；需要清爽阅读时再使用 `readable.html`。

## 验证记录

使用过的命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

已用 adb 在设备上验证：

- 首页是单列表布局。
- 左侧抽屉宽度收窄，只保留筛选入口。
- 每行不再显示“打开”“刷新”按钮。
- 点击整行可以打开文章。
- 长按整行可以进入选择状态。
- 复选框选择可用。
- 离线文章在统一浏览框架中打开。
- 刷新会在同一框架内切换到在线页面。
- 在线页面保存后会显示发现链接数量。
- 在线微信公众号文章页在注入 desktop viewport 后显示为缩放宽页面。
- 刷新覆盖保存后，本地归档能显示已下载图片。

## Git 记录

初始仓库：

- 分支：`main`
- 首个提交：`858ee92 Initial Android WeChat archive app`
- 远端：`ssh://git@ssh.github.com:443/huibin93/bookmark_download.git`

当前环境里 GitHub SSH 22 端口不可用，所以远端使用 GitHub SSH 443 端口。
