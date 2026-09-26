# 小红书 App 媒体响应研究

研究日期：2026-09-26 至 2026-09-27。目标是下载器独立获取媒体，不依赖已安装的小红书客户端、Hook 或响应转发。

## 当前结论

已使用用户提供的两个 Android APK，分别在本地 unidbg 原型中独立执行其 `libxyass.so`，生成 `shield` 与 `xy-platform-info`。这只证明 native 签名代码可以运行，不证明请求正确或已通过服务端校验。

最初只计算请求签名时，两版请求均得到 HTTP **406**、空 `data`。补上真实响应处理后发现：native 拦截器消费服务端响应、更新 `main_hmac` 并自动重试，随后两版都返回 HTTP **200**、业务码 **-100**、`登录已过期`。继续用 8.42 原型调用设备激活接口，返回 HTTP **461**、业务码 **300014**、`设备异常，请尝试关闭/卸载风险插件或重启试试`，没有下发会话。

独立获取 App 媒体源尚未完成，LivePhoto 视频水印问题仍未解决。既不能因 HTTP 200 就报告成功，也不能因最初 406 响应中的 `success: true` 就报告成功。

本轮没有修改下载器生产代码，也没有将失败的 App 请求接入解析流程。当前代码仍从网页响应解析媒体，见 [XhsContentRepository.kt](../app/src/main/java/com/neoruaa/xhsdn/data/xhs/XhsContentRepository.kt) 和 [XhsNoteParser.kt](../app/src/main/java/com/neoruaa/xhsdn/data/xhs/XhsNoteParser.kt)。此前真实网页样本结论见 [LivePhoto 兼容性记录](LIVE_PHOTO_COMPATIBILITY.md)。

## 输入和本地证据

用户提供的三个文件实际是两个 APK 和一个 IPA，而非三个 Android 版本。

| 输入 | 版本 / build | SHA-256 |
| --- | --- | --- |
| `小红书_官方_9.48.0.apk` | 9.48.0 / 9480809 | `3f7dcf5b75ce2bc2e495eba594335df3bfe08a8ceff1ee6735f5e5c32ede9cfd` |
| `小红书 官方内部版 v8.42.0.5.apk` | 8.42.0.5 / 8420294 | `68dbeac6311ba48ce38ff44a6d4e69791d40ec5804fcf380f3531664dbbf3f78` |
| `小红书_9.48_去水印.ipa` | 9.48 | `b7427d86cbd6aa4d1da1326a49d16999347c540ac9ab31de855d776bc1675448` |

证据保存在被 Git 忽略的 `xhs_workspace/jadx_out/`。下表路径均相对该目录；反编译结果和第三方二进制不进入应用源码或 Git。

| 路径 | 用途 |
| --- | --- |
| `input-manifest.json` | 输入文件大小与摘要 |
| `9.48.0/`、`8.42.0.5/` | Android 资源及 Java 导出；包含 JADX 无法完整还原的方法 |
| `selected-9.48/`、`selected-8.42/` | 媒体模型、详情接口、Shield 的定向导出，优先用于研究 |
| `headers-9.48/`、`params-9.48/` | 公共参数、UA、网络初始化 |
| `bootstrap-9.48/` | 激活接口、激活响应、TinyTokenHelper |
| `tiny-9.48/`、`tiny-dispatch-9.48/` | Tiny 与指纹的 Java / native 边界 |
| `native-probe/` | 本地 Maven / unidbg 签名原型、真实 HTTP 探测结果 |
| `ios-9.48/` | IPA 插件静态分析、字符串初始化解码脚本及输出 |

JADX 使用 `/Applications/jadx-1.5.6-all.jar` 的 `jadx.cli.JadxCLI` 入口；直接 `java -jar` 会启动 GUI。整包 8 GiB 堆导出仍产生明显内存压力，因此保留已有输出，使用 `export_remaining_dex.py` 逐 DEX、单线程、3 GiB 堆继续导出。补充导出使用 SIMPLE 模式，关键类另有默认模式定向导出。`dex-export-results.json` 记录每个 DEX 的进程退出码；退出成功不代表每个方法都还原正确，应结合注解、字段和原始指令核对。

最终两版全部 DEX 导出进程均已结束：9.48 共 22 个 DEX、157907 个 Java 文件；8.42 共 15 个 DEX、106052 个 Java 文件。逐 DEX 日志分别有 243 / 152 条 JADX ERROR，存在无法还原的方法，不能称为无错误的完整源码。统计保存在 `export-summary.json`。

IPA 是 Mach-O，不适用 JADX；本轮分析其 `XHS_X.dylib` 去水印插件，没有宣称完整反编译 iOS 主程序。

## Android 媒体入口

两版 `selected-*/sources/com/xingin/notebase/notedetail/service/NoteDetailService.java` 都声明：

```text
GET /api/sns/v1/note/imagefeed
```

9.48 的 `queryNoteDetailFeedData3Up` 参数包括 `note_id`、`page`、`has_ads_tag`、`num`、`fetch_mode`、`source`、`source_scene`、`ads_track_id`、`ads_track_url`、`from_rec_local`、`extra_params`、`biz_ext_json`。本地探测仅构造部分参数，尚未证明与客户端实际请求完全一致。

9.48 同一接口类还声明：

```text
POST /api/sns/v1/note/live_photo/save
form: note_id, is_hdr
method: getLivePhotoMarkVideoData
```

方法名明确指向带标记的保存视频；没有证据支持将这个保存接口作为无水印播放源入口。

两版媒体模型提供的证据：

- `com/xingin/entities/ImageBean.java`：`live_photo` 映射到 `VideoInfoV2`，另有 `live_photo_file_id`；9.48 还有 `live_photo_url`。
- `com/xingin/entities/video/VideoInfoV2.java`：包含 `media`、`consumer` 等字段。
- `Media.java` / `MediaStream.java`：包含 `stream`、`video_id`，以及 H.264 / H.265 / H.266 流。
- `VideoStream.java`：`master_url`、`backup_urls`、`stream_type`、`stream_desc`、编码、尺寸、时长等字段。

应从 App 响应中读取 `images_list[*].live_photo.media.stream` 的真实候选。当前网页解析器使用 camelCase 网页结构；取得有效 App 响应之后才有依据接入 snake_case 模型和真实回归夹具。检查到的 `Consumer` 模型没有 `origin_video_key` 字段，不能假定 App 响应一定提供作者上传的原文件。播放流与上传原片必须区分。

## 独立签名已验证的范围

8.42 的 `com/xingin/shield/http/XhsHttpInterceptor.java` 使用静态 `initializeNative()`，以及实例 native 方法 `initialize("main")`、`intercept(chain, ptr)`。

9.48 的 `com/xingin/shield/http/Native.java` 改用静态 native 方法，并新增 `updateCachedSsk`。`ContextHolder` 提供 context、device ID、app ID；初始化代码加载 `libxyass`。两个输入 APK 提供的相关库均为 arm64。

隔离原型根据这些实际 JNI 声明适配 ARM64 和调用方式。9.48 APK 证书字节的 Java `Arrays.hashCode` 已独立核对，与原型使用值一致。原型使用自建测试 device ID 和空 `main_hmac`，没有复用开源示例中的第三方登录态。

| 实验 | native 执行 | 实际 imagefeed 响应 |
| --- | --- | --- |
| 仅 UA / 部分公共参数 | 未调用 | HTTP 406，空 `data` |
| 公开 Python Shield 算法 + 构造参数 | Python 算法完成 | HTTP 406，空 `data` |
| 提供的 9.48 native 库 + JNI 模拟 | 生成 98 字符 `shield` | HTTP 406，空 `data` |
| 提供的 8.42 native 库 + JNI 模拟 | 生成 134 字符 `shield` | HTTP 406，空 `data` |
| 9.48 / 8.42 native 拦截器完整处理真实 HTTP 响应 | 首次 406 后写入 `main_hmac` 并自动重试；同一实例再请求一次 | 两版均 HTTP 200、`success: false`、`code: -100`、登录已过期，无笔记 |
| 8.42 原型调用设备激活接口 | POST 表单，处理 HMAC 初始化响应 | HTTP 461、`code: 300014`、设备异常，没有 session |

长度只是本次实验记录，不是格式校验规则。最初 `NativeProbe` / `LegacyNativeProbe` 的 `Chain.proceed` 是用于捕获生成请求头的桩，实际网络请求由 Python 单独发送。后续 `RoundTripProbe` / `LegacyRoundTripProbe` 则在 `Chain.proceed` 中发送真实 HTTP 请求，再将真实响应交回 native 拦截器，验证其 HMAC 更新与重试。不能把前一组原型的桩响应误当成服务器成功。

研究原型的构建与执行入口：

```sh
mvn -q -f xhs_workspace/jadx_out/native-probe/pom.xml package
```

`NativeProbe.java` / `LegacyNativeProbe.java` 的 main 参数依次为 native 库、APK、生成请求头 JSON 的本地路径。classpath 为 `native-probe/target/classes` 加 `native-probe/classpath.txt` 中的依赖。`send_generated_request.py` 和 `send_legacy_generated_request.py` 使用生成文件探测同一帖子；其输出只展示状态、结构和头名称，不输出签名或会话值。

响应记录：`native-probe/native-imagefeed-result.json`（9.48）和 `native-probe/native-imagefeed-result-8.42.json`（8.42）保留初次单向签名实验；`roundtrip-9.48-result.json`、`roundtrip-8.42-result.json` 及对应日志记录完整往返结果；`bootstrap-8.42-result.json` 记录激活失败。`RoundTripProbe` / `LegacyRoundTripProbe` / `BootstrapProbe` 的 main 参数与前述原型相同，第三个参数用于写入脱敏结果。原型运行于开发机，不是已验证可集成 Android / minSdk 24 的实现。

## 尚未复现的设备与会话链路

9.48 的 `bootstrap-9.48/sources/com/xingin/account/net/api/ILoginService.java` 声明 `POST /api/sns/v1/user/activate`，参数除设备、安装、来源信息外还包括 `client_public_key_base64`。`ActivateResponse` / JSON adapter 包含 `session`、`secure_session`、`ssk`、`user_token`、`userid`。

`selected-9.48/sources/com/xingin/shield/ssk/AccountSskClient.java` 生成 X25519 密钥对，接收服务端返回的加密 SSK 和 SID，解密后要求 SSK 为 32 字节，并以 `_sid_ssk_v3` 后缀管理状态。`shield/http/c.java` 最终将缓存状态交给 `Native.updateCachedSsk`。

`params-9.48/sources/jed/o1.java` 还初始化 Tiny 与 FingerPrintJni。`tiny-dispatch-9.48/sources/com/xingin/tiny/internal/t.java` 声明 native 分派入口；仅执行 Shield 库没有复现这条链路。`TinyTokenHelper` 的网络缓存 token 路径还检查登录状态，不能直接当作匿名初始化捷径。

真实往返实验确认 `Xy-Ter-Str` 响应参与 HMAC 状态更新；只实现请求签名会漏掉这一步。原型没有把 Cookie、HMAC 或会话值打印到结果中。更新 HMAC 后，两版 imagefeed 均返回明确的登录过期错误。

`BootstrapProbe` 按 8.42 `com/xingin/net/gen/api/GrowthApi.java` 的激活声明构造设备、安装信息及表单，使用自建测试标识，不触发短信或账号登录。服务端以 300014 拒绝，没有获得可用于后续 imagefeed 的 SID。它不是成功的设备初始化实现，构造的设备环境与真实客户端也尚未对齐。

当前没有完成：设备状态初始化、服务端会话 / SSK 交换、Tiny 请求参数生成、客户端实际 query 与全部公共参数的对应关系。HTTP 406 本身不能确定缺失项；后续业务错误也不能证明 JNI 环境、所有请求参数和签名在其它接口上都正确。8.42 同样缺少有效会话，不能把问题单独归因于 9.48 的 SSK。

## IPA 去水印插件实际上做了什么

提供的 IPA 含 `XHS_X.dylib`，其 SHA-256 为 `784de387f854a9b5575901b57c1c47f59abcc836bd90c573af5e81bb140c610e`。插件注册的 `extractLivePhotoData` 实现地址为 `0xd96d0`，读取 `largeImageURL` 和 `livePhotoMediaJson`，解析已有的媒体 JSON，优先取 H.265、再回退 H.264 候选中的 URL。

`ios-9.48/decode_live_photo_keys.py` 校验二进制摘要，只模拟该方法开头的本地字符串初始化，停在外部调用之前；恢复并校验的字符串为：`stream`、`h265`、`h264`、`url`、`master_url`、`sns-v3`、`sns-v1`。

插件包含 `sns-v3` 到 `sns-v1` 的替换，但读取 App 播放响应在先。这不是把网页 `WEB_LIVEPHOTO_19` 的 URL 推导为原片的证据，更不能据此对 `sns-video-v3` 全局替换。插件需要宿主 App，不能直接满足本项目独立获取的目标。

## 公开实现的可用性

| 项目与固定版本 | 可参考内容 | 限制 |
| --- | --- | --- |
| [Rnote-dev/Xiaohongshu-Shield-Algorithm](https://github.com/Rnote-dev/Xiaohongshu-Shield-Algorithm/tree/9960f326a7ba44d025d14b7a7d79323dcd9083eb) | Python Shield 算法；仓库含 Apache-2.0 许可 | 示例对应 9.19.2；不包含本项目所需的完整设备、会话、Tiny 初始化。套用到本样本未取得媒体 |
| [zero199901/xhs-unidbg-public](https://github.com/zero199901/xhs-unidbg-public/tree/e955fd39a0036711123df2ab1d900b0c154984f3) | native Shield JNI 模拟，可用于验证调用边界 | 参考版本 9.33 / ARM32；本轮本地适配为所提供 APK 的 ARM64。该快照未发现 LICENSE，未复制进生产代码。其示例接口成功不等于本帖媒体接口成功 |
| [fmz200/wool_scripts 的小红书脚本](https://github.com/fmz200/wool_scripts/blob/2dfe1fa129085015a9abed46b4474d34d4426269/Scripts/xiaohongshu/xiaohongshu.js) | 从 native feed 缓存 `images_list`，再读取 `live_photo.media.stream.h265[*].master_url` | 依赖截获 App 响应；可佐证字段用途，不能替代独立请求实现 |

## 接入下载器的验收条件

独立原型必须先对用户提供的帖子返回非空、可对应 note ID 的 App 媒体数据；下载对应视频后确认画面无小红书水印，并记录编码、音轨、分辨率和来源字段。取得播放流也不得直接标注为上传原片。

随后才能接入现有 repository / parser，使用脱敏真实响应测试解析、候选排序、无有效流时的回退，以及会话过期和取消行为。还需确定实现及依赖的分发许可、支持 ABI、minSdk 24 兼容性。当前没有跨过上述验收门槛，不能把这份研究或本地签名原型描述为下载器已支持 App 原始媒体。

## 本轮验证

- `mvn -q -f xhs_workspace/jadx_out/native-probe/pom.xml package`：通过；仅构建隔离研究原型。
- 两版 native 签名、真实响应处理与重复请求：实际执行；结果为登录过期，无媒体。
- 8.42 独立设备激活：实际执行；设备异常，无会话。
- `research-venv/bin/python ios-9.48/decode_live_photo_keys.py`（在 `jadx_out` 下）：通过摘要和恢复字符串断言。
- Python 脚本语法检查、HTTP 结果结构断言、文档本地链接检查：通过。
- `git diff --check`：通过；新增研究文档另行检查了行尾空白。

本轮生产文件变化仅为本文和兼容性文档中的研究链接；研究代码、反编译输出均留在被忽略的工作目录。没有 Kotlin / UI 变更，因此未运行 Android 编译或设备验收；没有执行 Git 暂存、提交或推送。
