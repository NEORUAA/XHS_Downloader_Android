# 上游功能同步实施记录

参考：本地 Python `3261312`（2.8）、Android `884ff64`（1.3.5）。

## 已确认的范围

- 优先同步下载核心；暂不移植网页批量采集、桌面 CLI/API/MCP、脚本推送与 ZIP。
- 保留主页框架、存储位置选择、命名模板、实况照片合成及现有 miuix 导航效果。
- 移除视频警告；每个链接独立任务，重复提交默认重新下载，智能跳过默认关闭。
- 前台服务承载后台任务；中断后保留检查点，进程重启后由用户恢复。
- 选择下载先解析与展示缩略图，提交后关闭抽屉，仅下载选中内容。
- 优先使用 miuix Extended 操作图标；三套语言资源同步维护。
- 按模块验证、提交，不 push。

## 提交顺序与验收

1. **解析与数据基础**：结构化笔记模型、目标笔记匹配、RedNote/短链接、完整媒体候选、设置快照、格式识别、严格 Range 校验、归档存储。验收：解析及传输边界单元测试。
2. **持久任务队列**：Room v3 会话与资源记录、前台服务、暂停/继续/重试、资源独立结果、文案导出、实况降级、智能跳过。验收：迁移测试、后台和重启恢复。
3. **miuix 交互**：任务按钮、真正的 Lazy 缩略图选择、历史查询分页、媒体/归档/网络设置、作者备注、会话设置。验收：编译、三语言资源检查、AVD 交互和宽屏检查。
4. **集成验证与交付**：真实笔记、图片/视频/实况、选择下载、错误路径及回归记录。验收结果另行记录，未验证项不视为通过。

## UI 示意

```text
主页 / 任务
  笔记标题 · 状态 · 文件数
  下载进度
  [暂停/继续/选择/重试]      [复制] [删除]

设置
  [媒体与格式 >]
  [归档与记录 >]
  [网络与会话 >]

选择媒体
  已选 n / 共 m                    [全选]
  [缩略图 ✓] [缩略图 □]
  [缩略图 □] [视频封面 ✓]
  关闭可稍后继续；下载按钮提交所选内容。
```

## 设备验证边界

用户已授权使用 Pixel_10_Pro AVD 和 Mac 中已打开的小红书应用复制链接。测试不得清空既有应用数据、输出登录凭据或把源代码检查代替设备验收。

## 实施结果（2026-09-22）

| 模块 | 当前行为 | 主要实现 |
| --- | --- | --- |
| 解析 | 支持分享文本中的多个链接、短链接和 RedNote；只读取目标笔记；保留正文换行、作者、时间、标签与媒体次序 | `data/xhs/XhsUrlParser.kt`、`XhsNoteParser.kt`、`core/model/ResolvedNote.kt` |
| 视频 | 移除旧视频警告；提取原始视频、各编码/规格和备用地址；原始画质默认优先，新增 H.264 兼容策略 | `domain/download/NoteOutput.kt`、`data/settings/DownloadOptions.kt` |
| 图片 | 自动原始格式或 JPEG/PNG/WebP/HEIC/AVIF；优先无水印候选，按文件魔数确定真实 MIME 与扩展名；格式不可用时保留实际格式并提示 | `data/network/MediaFileType.kt`、`domain/download/DownloadQueue.kt` |
| 实况 | 合成、分开保存、仅静态三种模式；合成失败保留原始组件并提示；组件部分失败明确标为部分完成 | `domain/download/DownloadQueue.kt`、既有 `LivePhotoCreator.kt` |
| 传输 | 256 KiB 流式缓冲，单笔记最多四路媒体传输，重试和候选回退；私有文件保存检查点；校验 Range、If-Range、长度、响应类型和可用空间 | `data/network/ResumableTransfer.kt` |
| 队列 | 每个链接独立持久任务；前台服务下载；暂停、继续、取消、重试；重启后暂停；资源级成功记录避免部分失败重下已成功资源 | `domain/download/DownloadQueue.kt`、`DownloadService.kt`、`data/tasks/` |
| 选择下载 | 一次解析后展示真正的 Lazy 缩略图网格；选择前不拉取完整媒体；提交即关闭抽屉；关闭后可从任务继续选择 | `MainActivity.kt`、`viewmodels/MainViewModel.kt` |
| 归档 | 保留默认 MediaStore 和自选存储；作者/笔记目录、作者 ID 对应备注、命名模板、发布时间写入（受存储提供方限制） | `data/storage/AndroidStorageSink.kt`、`domain/download/NoteOutput.kt` |
| 记录 | 默认重复下载；智能跳过需记录匹配且文件仍可访问；TXT/Markdown 笔记信息与 JSON/CSV 下载记录导出 | `data/tasks/RecordExport.kt`、`domain/download/DownloadQueue.kt` |
| 网络/会话 | 超时、重试、HTTP/SOCKS5 代理、媒体代理独立开关；WebView 登录态及主机隔离的手动 Cookie；凭据经 Android Keystore 加密存储 | `data/network/`、`feature/settings/DownloadSettingsRoute.kt` |
| 历史/UI | 数据库搜索标题/作者/链接/正文/笔记 ID，初始 50 条，按需加载；待处理包含排队、解析、下载、待选和暂停；miuix 二级页、固定宽屏顶栏及 Extended 操作图标 | `data/tasks/TaskDao.kt`、`MainActivity.kt`、`ui/AdaptiveUi.kt` |

上述路径均相对于 `app/src/main/java/com/neoruaa/xhsdn/`。所有新增用户文案维护英文、简体、繁体三套资源。

## 数据与默认行为

- Room 从旧版迁移到 v3，保留原下载记录和文件引用；保存会话时不通过 REPLACE 删除子记录。
- 每个任务保存提交时的设置；改变设置只影响新任务。
- 智能跳过默认关闭，不改变重复提交行为；网络超时/代理或跳过开关本身不改变媒体身份。
- 默认保留原始视频优先策略；兼容模式尝试 H.264 候选，不执行本地转码，仍保留其他候选作为回退。
- 原图格式由服务器实际返回内容决定；“指定格式”不等于保证上游存在该格式。
- 本文记录的测试未清空用户数据、修改存储位置、提交凭据或上传任何文件；迁移测试改为独立上下文和文件目录。

## 自动验证

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb -s emulator-5554 shell am instrument -w com.neoruaa.xhsdn.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- 单元测试涵盖结构化解析、目标匹配、URL 边界、命名、格式识别、CSV 转义、资源身份及视频候选排序。
- Range 回归涵盖确认后续传、服务器忽略 Range 返回 200、206 验证值变化、416 长度/验证值确认，以及拒绝伪装为媒体的 HTML。
- 49 项单元测试、12 项设备测试通过；三套语言共 332 个字符串键及格式占位符一致。
- Pixel_10_Pro 上设备测试覆盖设置与数据库迁移、真实 MediaStore 写入/读取/预览/删除、选择前不下载完整媒体、暂停续传、默认重复下载、智能跳过、部分失败重试、实况组件失败保留已保存图片、TXT/Markdown 导出；外部重复分享和 Activity 重建幂等。分享入口使用 singleTop，并消费已处理输入，保留 MIME 类型。
- lint 无错误；仍有既有 API、未使用旧资源、英文复数、宽度 API 等警告，本次未借机清理无关实现。

## 实际设备验收

| 场景 | 结果 |
| --- | --- |
| 真实图文，4 张，提交后回到系统桌面 | 4/4 成功，2 JPEG + 2 HEIC，真实扩展名与魔数一致 |
| 原始大视频下载中强制停止应用 | 保留约 21 MB 检查点；重开显示暂停；继续后部分文件增长；确认源视频超过 7 GB 后主动取消该测试，私有缓存已清理 |
| 真实实况，7 张中只选 1 张 | 仅保存 1 个 `_live.jpg`，8,354,081 字节；XMP 声明与尾部 MP4 长度吻合，内嵌 H.264 1080×1440 / 2.906667 秒 |
| 真实短视频和封面，后台下载 | 2/2 成功；源 MOV 为 HEVC 1080×1920 + AAC，9.309002 秒；FFmpeg 完整解码无错误 |
| 源 MOV 的系统播放器打开 | Intent 和文件授权成功；系统 Codec2 / BufferPool 出现权限错误，HEVC 与 H.264 均无法播放；文件可在主机完整解码，不把下载成功表述为设备播放通过 |
| H.264 兼容模式 | 同一真实笔记保存 3,080,856 字节 MP4，H.264 720×1280 + HE-AAC，9.309751 秒；原始画质策略仍保留，测试后恢复原设置 |
| 系统记录导出 | JSON 经系统文件选择器保存并回读，版本 1，4 条成功资源记录，无 Cookie、媒体签名 URL 或本地路径 |
| 手机/宽屏设置 | 核对媒体、归档、网络页和格式弹窗；宽屏为固定小顶栏；临时分辨率已恢复 |

## 未覆盖与后续边界

- 当前 AVD 的外部图库视频播放受系统 Codec2 / BufferPool 错误阻塞；未改变 SELinux 或设备安全设置。文件头、长度、媒体轨道及主机完整解码均已验证。
- Android 7–9、真实厂商后台策略、不同图库对 Motion Photo 的识别，仍需对应设备测试；AVD 的编解码能力不代表真实手机。
- 没有可用代理服务器时只验证配置与网络策略代码；未在用户账号中写入测试 Cookie；WebView 登录和风控回退未做账号交互验收。
- CDN 和平台登录/风控可能随时变化；不存在的格式或失效资源通过候选回退、错误提示和 WebView 重新解析处理。
- 网页批量采集、桌面 CLI/API/MCP、脚本推送和 ZIP 不属于本轮 Android 下载核心同步范围。

## 本地模块提交

1. `727ad41` — `feat(core): add structured note resolution and resumable media transfers`
2. `bdf9276` — `feat(download): persist background queue and selective media sessions`
3. `c92bd8e` — `feat(settings): add media archive and session preferences with record exports`
4. `1017ebb` — `fix(download): harden media compatibility and repeated share recovery`

本记录作为独立文档提交。所有提交仅在本地，未执行 push。
