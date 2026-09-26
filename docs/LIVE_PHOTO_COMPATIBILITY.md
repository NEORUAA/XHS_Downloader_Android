# 安卓实况照片合成兼容性

本次实现按目标格式输出；小米保留此前可用实现的完整兼容元数据。**格式结构通过测试，不等于各厂商相册已真机验收**；同一品牌的不同相册版本也可能采用不同协议。

## 入口与输出

设置 → 媒体与格式 → 实况保存方式选择「合成动态照片」，再选择「实况合成格式」。设置只作用于新建任务，已有任务使用创建时的设置快照。

| 选项 | 输出结构 | 自动选择 | 厂商相册验证状态 |
| --- | --- | --- | --- |
| 通用 / Google 相册 | JPEG + Google V2 XMP + 原始视频；兼带 V1 偏移 | 其他品牌 | 未验证 Google 相册识别 |
| 小米 / 红米 / POCO | 恢复旧版完整 XMP（含 MiCamera 和原有 Oplus 字段）、零时间戳、SOI 后立即写 XMP、视频 EOF 偏移 | Xiaomi / Redmi / POCO | 恢复旧布局后，用户已确认其小米手机播放正常；其他型号/版本仍待验证 |
| OPPO / 一加 / realme | EXIF UserComment + Oplus XMP + MPF + 原始视频 | OPPO / OnePlus / realme / Oplus | 参考上游及 Issue #36；本项目新实现待真机验证 |
| 三星 | Google V2 + MotionPhoto_Data / MotionPhoto_Version + SEFH/SEFT | Samsung | 待 One UI 相册验证 |
| vivo / iQOO 单文件（实验性） | Google V2 + VCamera + vivo EXIF 标记 + 原始视频 | vivo / iQOO | 参考 X300 格式；不能据品牌推断旧设备支持此格式 |
| vivo / iQOO 双文件（实验性） | JPEG 私有尾标 + MP4 uuid，两端共用 28 位 ID | 手动选择 | 供旧版相册尝试；配对识别待真机验证 |
| 华为 / 荣耀（实验性） | JPEG + MP4 + 60 字节 LIVE_ 尾标 | Huawei / Honor | 不承诺所有 MagicOS / HarmonyOS 版本；非安卓 HarmonyOS 不在应用支持范围 |

导出文件名以 `MP.jpg` 结尾，以满足 Google 的命名建议；MediaStore/旧版文件存储生成重复文件名时把编号插在 `MP` 前，避免破坏这一结尾。SAF 文档提供器仍可能自行改名，外部改名后需要检查文件名与相册识别。vivo 双文件分别登记为图片与视频，默认存入同一 `DCIM/xhsdn/` 目录；自定义目录则沿用用户选择。配对 ID 在同一任务重试时不变。应保留两个文件并通过不压缩的文件方式传输，文件名或目录被其他应用重命名仍可能影响厂商配对。

自动选择只依据制造商与品牌，不会检测相册私有版本或远端接收设备。旧 vivo 请手动尝试双文件；其他品牌相册不识别时可尝试通用格式或分别保存。不能用自动选择的结果作为设备支持证明。

## 已核对的问题

- [#36 OPPO/一加无法识别](https://github.com/NEORUAA/XHS_Downloader_Android/issues/36)：旧代码仅写 Oplus XMP，没有 EXIF 实况识别标记及 MPF。附件补丁提供了很有价值的真机线索，但不能直接套用：它还会移除旧版 MicroVideo 标签，并涉及源 EXIF 复用。当前实现按格式隔离，保留已有封面方向归一化，避免复用旧 Orientation 导致二次旋转。
- [#28 MIUI 14 / OriginOS 6](https://github.com/NEORUAA/XHS_Downloader_Android/issues/28)：这些报告不足以确定具体相册版本、视频编码和输入文件。增加旧版 MicroVideo 兼容及 vivo 两种格式，但该 Issue 仍需要报告者提供原文件并回归。
- [#23 无声](https://github.com/NEORUAA/XHS_Downloader_Android/issues/23)：合成保留输入视频的全部字节与音轨；不会恢复源视频缺失的声音，也不能保证厂商相册默认开启声音。使用带 AAC 音轨的合成素材验证音视频样本哈希不变，不能据此断言该 Issue 的下载源已解决。
- [#27 封面旋转/拉伸](https://github.com/NEORUAA/XHS_Downloader_Android/issues/27)：保留当前 EXIF 八方向归一化处理，并对带 90° EXIF 的测试封面检查最终宽高。不会重编码视频以修正源视频显示矩阵；该具体帖子仍需原始素材回归。
- [#19 相册索引](https://github.com/NEORUAA/XHS_Downloader_Android/issues/19)：下载走既有 MediaStore 发布流程，旧 API 仍有媒体扫描入口；SAF 目录是否被厂商图库索引取决于目录和系统。

## 实现位置

- `app/src/main/java/com/neoruaa/xhsdn/LivePhotoCreator.kt`：图片方向归一化、厂商 EXIF、Android 媒体轨道检查、临时文件及取消处理。
- `app/src/main/java/com/neoruaa/xhsdn/MotionPhotoContainer.kt`：XMP、MPF、SEF、LIVE_、vivo 配对尾标与 uuid 的字节布局；使用 Long 计算偏移。
- `app/src/main/java/com/neoruaa/xhsdn/data/settings/DownloadOptions.kt` 与 `feature/settings/DownloadSettingsRoute.kt`（位于同一 `com/neoruaa/xhsdn/` 目录下）：持久化目标格式、自动映射、格式选项及三语说明。
- `app/src/main/java/com/neoruaa/xhsdn/domain/download/DownloadQueue.kt`：任务快照、目标格式合成、失败保留原始组件、vivo 配对保存；`NoteOutput.kt` 将格式纳入下载记录标识。
- `app/src/main/java/com/neoruaa/xhsdn/data/storage/AndroidStorageSink.kt`：vivo 双文件保存到同一 DCIM 目录，继续使用真实 MIME 和对应 MediaStore 集合。
- `app/src/main/java/com/neoruaa/xhsdn/feature/detail/DetailMediaCards.kt`：双文件下载进度和结果均可见。

封面继续按原有策略转为 SDR JPEG（质量 95），不保留源 HEIC/AVIF 容器、HDR gain map 或全部原始 EXIF。视频不转码，MP4/MOV 的 MIME 按实际文件类型填写；OPPO、华为和 vivo 双文件要求 MP4，否则回退保存原始组件。视频编码兼容问题可尝试现有「视频优先级 → 兼容优先」，该选项优先选择服务端 AVC 候选，不进行本地转码。

小红书解析结果没有可靠的封面帧时间。小米保留已验收旧实现的 `0`；OPPO 与新版 vivo 对齐各自开源写入器的默认 `0`；通用和三星写 `-1`（未知）。华为尾标按新合成文件写入封面帧号 `0` 和从视频轨道统计的总帧数，不能将毫秒时长误当帧数。精准封面定位未验证。

## 研究来源与致谢

没有新增运行时依赖，未把桌面项目或其外部二进制打包进应用。下面的项目用于核对协议结构，保留各自原始许可证；不能将它们宣称的设备兼容性直接转为本项目验收结果。

1. [Android Motion Photo 1.0 规范](https://developer.android.com/media/platform/motion-photo-format)：容器目录、视频长度、未知时间戳及 `MP` 文件名要求。
2. [Young-Spark/oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)，MIT，研究版本 `3d020598789e8eebb0fa042b993ea03e4d21c2f9`：OPPO 的 EXIF、MPF 与 Oplus XMP。作者明确列出的已验证设备是 Find X7 Ultra / ColorOS 14，不等于覆盖所有一加/realme。
3. [flashlab/motion-live-photo](https://github.com/flashlab/motion-live-photo)：对照 Google、小米、OPPO 元数据实例，以及本项目 Issue #36 的来源。
4. [PetrVys/MotionPhoto2](https://github.com/PetrVys/MotionPhoto2)，研究版本 `ff46699215f1071ada579140d73994a4be29a399`；[doodspav/motionphoto](https://github.com/doodspav/motionphoto)：三星的独立 SEF 字段、反向索引与 Google XMP 偏移关系。
5. [LengxiQwQ/live-photo-box](https://github.com/LengxiQwQ/live-photo-box)，GPL-3.0，研究版本 `66b619249c32a6d6d4080063d64df9471589f003`：华为 LIVE_、vivo 新旧两种格式的逆向结构与标记值。vivo 格式仍有实验性质；本项目也将相应选项标为实验性。
6. [aiglance/flutter_live_motion](https://github.com/aiglance/flutter_live_motion)：可参考 Android 实现，但其三星兼容说明依赖 Google 相册，不能代替三星原生 SEF 支持。
7. [gaoyilun/LivePhotoTools](https://github.com/gaoyilun/LivePhotoTools)：检索到的相关 Android 工具；当前 README 明确为专有/闭源分发，不作为开源实现或代码来源。

## 验证与待验收范围

- JVM 测试：XML 按命名空间解析、各格式视频字节及 EOF 偏移、三星独立反向索引、OPPO MPF 图像长度、vivo uuid 长度/配对 ID、超过 Int 的长度、无效视频拒绝、取消传播、旧设置默认值、去重键和双文件详情槽位。
- Android 测试：`app/src/androidTest/java/com/neoruaa/xhsdn/LivePhotoCreatorTest.kt`，使用本地生成的 AVC/AAC 样本，覆盖最终 JPEG 解码与方向、视频帧解码、所有音视频样本 SHA-256 一致性、MediaStore 写入后完整字节保留、重复保存仍以 `MP.jpg` 结尾、配对目录、失败与取消。
- 输入样本 `app/src/androidTest/assets/live_photo_av.mp4` 由 FFmpeg testsrc2 + sine 生成，不含下载内容。生成命令见该目录 README。
- 真机验收仍需：HyperOS/MIUI、ColorOS/OxygenOS/realme UI、One UI、OriginOS/Funtouch OS、EMUI/MagicOS。每个系统应核对原生相册实况标记、完整播放、声音、封面方向、重启相册后的识别和原文件跨设备传输。Android 模拟器的媒体解码通过不能替代上述结果。

### 本次实际运行（2026-09-26）

- `./gradlew :app:compileDebugKotlin --console=plain`：通过。
- `./gradlew :app:testDebugUnitTest --tests com.neoruaa.xhsdn.MotionPhotoContainerTest --tests com.neoruaa.xhsdn.domain.download.NoteOutputTest --tests com.neoruaa.xhsdn.feature.detail.DetailMediaCardsTest`：22 项通过（包含小米旧输出的完整字节回归）。
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.neoruaa.xhsdn.LivePhotoCreatorTest --console=plain`：Android 37 / Pixel 10 Pro AVD，3 项通过。未运行厂商原生相册或 Android 24 真机。
- 模拟器交互：打开媒体与格式页面及八选项格式弹窗，检查文字与按钮完整显示；选择三星后重启应用，选项仍为三星；检查结束恢复自动。
- 三种语言的实况字符串 key 一致，无重复 key；`git diff --check` 通过。

回归过程中修正了 AAC 预热样本可带负时间戳的情况：不能把 `sampleTime < 0` 一概视为空音轨，应以是否存在有效样本轨道判断。测试素材确实覆盖了该情况。

### 小米播放回归修复

用户确认使用「自动」，输出以 `_live_MP.jpg` 结尾但无法播放。首次多格式改动同时移除了旧 Xiaomi XMP 的兼容字段、将时间戳改为未知值并移动了 APP1 位置，缺少小米相册验收。当前将小米分支恢复到旧实现的完整 XMP 与 `SOI → XMP → JPEG 剩余内容 → 视频` 顺序；不能据此判断究竟哪个字段导致拒播。

固定基线 `app/src/test/resources/livephoto/xiaomi-before-multiformat.xmp` 从提交 `15c5f5701b3479c2a970f3acf953625725f0d59c` 的 `LivePhotoCreator.kt` 提取。测试独立组装旧格式，分别检查显式小米和自动映射小米输出的完整字节一致性。模拟器解码和 MediaStore 测试不代表小米相册播放验收；修复不会重写此前已经保存的文件，应关闭「跳过已下载」后新建任务重新合成验证。

## 二次逐项审计（2026-09-26）

用户已确认恢复后的小米输出能正常播放。以下参考锁定到具体提交；“有源码依据”与“本项目已在该品牌相册播放”分别记录，不推导跨机型兼容。

| 分支 | 固定源码与关键位置 | 本次审计结论 |
| --- | --- | --- |
| 通用 / Google | [Android 规范](https://developer.android.com/media/platform/motion-photo-format)；[MotionPhoto2/constants.py](https://github.com/PetrVys/MotionPhoto2/blob/ff46699215f1071ada579140d73994a4be29a399/constants.py) 与 [Muxer.py](https://github.com/PetrVys/MotionPhoto2/blob/ff46699215f1071ada579140d73994a4be29a399/Muxer.py) | JPEG APP1 的 XMP、Primary/MotionPhoto、实际视频长度、未知时间戳及 MP 文件名；兼带旧版 MicroVideo 偏移。纯 JPEG，无 gain map，不声明 HDR。 |
| 小米 | [flashlab/extractutil.ts](https://github.com/flashlab/motion-live-photo/blob/48b884713c496ed4e4ad5ba688e84e6eb82ca059/src/lib/extractutil.ts)；本项目 `15c5f570` 原实现固定样本 | 保留 MiCamera 和原有 Oplus 兼容字段及 SOI 后的写入位置；不继续精简已能播放的布局。完整字节基线覆盖自动/手动选择。用户的小米实测通过。 |
| OPPO / 一加 / realme | [oppo-live-photo-maker/muxer.py](https://github.com/Young-Spark/oppo-live-photo-maker/blob/3d020598789e8eebb0fa042b993ea03e4d21c2f9/src/oppo_live_photo/muxer.py)，`write_oppo_motionphoto` / `_build_mpf_segment`（MIT） | 修正 EXIF 大小写为 **`Oplus_8388608`**，Google 和 OpCamera 时间戳均为 `0`，显式 MotionPhoto Padding=0；核对 owner、version、feature flag、VideoLength 和 MPF 主图长度。MPF 基线直接调用上游生成。来源要求 MP4，因此 MOV 回退原组件；不声称覆盖所有 ColorOS / OxygenOS。 |
| 三星 | [MotionPhoto2/SamsungTags.py](https://github.com/PetrVys/MotionPhoto2/blob/ff46699215f1071ada579140d73994a4be29a399/SamsungTags.py)，`video_footer` / `get_image_padding`（MIT） | JPEG 分支的 24 字节 MotionPhoto_Data 前缀、mpv3 版本字段、SEFH 107、双反向索引及 SEFT 尾完全对照上游输出，字节基线直接执行上游类生成。XMP 视频项目长度含视频后方 SEF 数据；不套用 HEIC 的 mpvd 布局。 |
| vivo 单文件 | [VivoLivePhotoProtocol.cs](https://github.com/LengxiQwQ/live-photo-box/blob/66b619249c32a6d6d4080063d64df9471589f003/LivePhotoBox.Core/Services/Protocols/VivoLivePhotoProtocol.cs)，`RdfTemplateNoGainMap` / `UserCommentTemplate`（GPL-3.0） | 时间戳恢复上游默认 `0`；Primary 不写 Length/Padding，MotionPhoto 明确 Padding=0；三个 VCamera 字段与完整 UserComment 模板逐字段比较。当前封面已转 SDR，故采用无 GainMap 模板。源码针对 X300 系列，不能把自动品牌映射理解为所有 vivo 支持。 |
| vivo 双文件 | [VivoDualFileMetadataWriter.cs](https://github.com/LengxiQwQ/live-photo-box/blob/66b619249c32a6d6d4080063d64df9471589f003/LivePhotoBox.Core/Services/Protocols/VivoDualFileMetadataWriter.cs)，`BuildTail` / `AppendVideoUuidBoxAsync`（GPL-3.0） | 核对两份完整 JSON、28 位共享 ID、两处大端长度、cameralbum!、固定签名及 16 字节 uuid user type。基线由上游字符串常量与独立封装脚本生成；未声称执行了 C# 或已完成相册配对。 |
| 华为 / 荣耀 | [HuaweiMovingPhotoProtocol.cs](https://github.com/LengxiQwQ/live-photo-box/blob/66b619249c32a6d6d4080063d64df9471589f003/LivePhotoBox.Core/Services/Protocols/HuaweiMovingPhotoProtocol.cs)，`BuildTail`；[native huawei.cpp](https://github.com/LengxiQwQ/live-photo-box/blob/66b619249c32a6d6d4080063d64df9471589f003/LivePhotoBox.Native/src/protocols/huawei.cpp)，`lpb_huawei_build_tail`（GPL-3.0） | **修复把 durationMs 写入总帧数字段的错误**；新文件采用 `v6_f0`、`0:总帧数`、`LIVE_视频大小加20`，60 字节空格补齐。只做 JPEG 简化格式；没有移植上游 HEIC tmap、MP4 ftyp/©too 仿原机修饰，也不写没有来源的 covertime，仍属实验性。 |

注意：华为上游尾标另有“保留原文件历史时间”的重载，该重载可以接收毫秒值；本项目拿到的是单独图片和视频，没有原始华为尾标，不应混用这两个接口。总帧数在合成前和嵌入后均从视频轨道检查；媒体样本逐字节保留。

### 实况视频水印取源

当前合成器只拼接下载视频，不渲染水印。解析端过去直接枚举 `imageList[].stream`，没有处理该组件明确提供的 `consumer.originVideoKey`，也没有保留流类型的水印信息；画质排序可能优先选择更大的水印版本。

参考 [media-parser/xiaohongshu_parser.py](https://github.com/ucmao/media-parser/blob/bf961fb30bbd13ba9d04d16466eb8a851932297b/src/parsers/xiaohongshu_parser.py) 的 `get_real_video_url`（原始 Key 与 streamType=259/309 的处理），本项目现在：

- 仅当组件实际提供原始 Key 时构造原始 CDN 地址；不把图片 traceId、videoId、masterUrl 文件名猜成原始视频 Key。
- 支持顶层 stream 和嵌套 livePhoto 的媒体结构；保留主地址及备份地址。
- 在分辨率/码率/大小/兼容排序之前，将已知水印流排到最后；原始 Key 保持原片优先策略，兼容优先仍优先 AVC。
- 未知流不标成“已验证无水印”，已知水印流仍可在其他候选失败时回退。上游的实况提取自身也只取 masterUrl，不提供“所有实况都能找到原片”的保证。

二次审计当时用户已确认每段视频右下角是小红书文字 / Logo，尚未提供帖子；下文“真实帖子复现”补充了随后提供链接的实测结果。本次修正了可定位的候选选择缺陷，**尚不能认定该水印已消失**，也不能认定每段实况均返回了原始 CDN Key。需用一个实际案例核对 streamType、下载到的候选和视频帧，不能仅检查域名。

### 可复现的来源基线

`app/src/test/resources/livephoto/README.md` 记录基线来源与生成方式；`scripts/generate_livephoto_reference_fixtures.py` 强制检查上游提交号，生成三星/OPPO 二进制基线和 vivo/华为参考数据。三星/OPPO 调用原项目函数；vivo/华为的 C# 模板提取与布局转写明确标明，不能称为原程序端到端测试。基线不依赖当前 Kotlin 合成器生成，也不在测试时联网。

### 二次审计实际验证

- `python3 scripts/generate_livephoto_reference_fixtures.py /tmp/xhs-livephoto-research`：校验三个参考项目提交与工作区状态，生成 7 个参考文件；重复生成成功。
- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.neoruaa.xhsdn.MotionPhotoContainerTest --tests com.neoruaa.xhsdn.data.xhs.XhsNoteParserTest --tests com.neoruaa.xhsdn.domain.download.NoteOutputTest --tests com.neoruaa.xhsdn.feature.detail.DetailMediaCardsTest`：编译成功，31 项 JVM 测试通过。
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.neoruaa.xhsdn.LivePhotoCreatorTest --console=plain`：Pixel 10 Pro / Android 37 模拟器，4 项通过。包括 12 帧尾标、EXIF 标记、全部音视频样本哈希、封面解码、MediaStore 字节保留、MOV 限定和取消。
- `git diff --check`：通过。未运行其他品牌原生相册，未用用户实际帖子进行无水印验收。没有暂存、提交或推送。

## 真实帖子复现：网页实况流不是原视频（2026-09-26）

用户提供 [分享链接](https://xhslink.cn/o/AONqPejRJtI)，对应笔记 `6a253dce0000000015024a0f`。本次请求无账号 Cookie，不保存签名 URL 或用户信息到仓库；原始响应与媒体只保留在本机临时诊断目录。

- 移动网页与 iOS UA 响应均只有 `imageList[0].stream.h264[0]`：`streamType=19`、`streamDesc=WEB_LIVEPHOTO_19`，H.265/H.266/AV1 列表为空。
- 该组件没有 `consumer.originVideoKey` 或其他已核对的原始视频 Key。普通视频的 259/309 排序修正不能解决该帖。
- 实际下载文件 787061 字节，H.264，1080×1440，3.000 秒，90 帧，无音轨；抽取第 1 秒画面后，确认右下角小红书 Logo 已编码在像素中。
- 视频 SHA-256：`6577f011583d3d4b0e30462157f352be39d0635721cf9c0262cfc60140df4c2d`。静态原图是独立 HEIC（1060407 字节），没有附加 MP4。
- 以网页视频文件名推测原始 Key、切换路径或旧版流类型的有限探测均返回 404；这些猜测**没有加入生产代码**。

[fmz200/wool_scripts 的实况保存实现](https://github.com/fmz200/wool_scripts/blob/2dfe1fa129085015a9abed46b4474d34d4426269/Scripts/xiaohongshu/xiaohongshu.js#L125) 提供了不同数据源的真实案例：它读取原生 App feed 响应缓存，再使用 `images_list[].live_photo.media.stream.h265[0].master_url` 替换保存 URL。该实现不是从本项目拿到的网页 H.264 地址还原原片。H.265 本身也不是无水印保证，仍须检查真实文件；不得把 App 播放流直接称为作者原始上传文件。

当前环境直接读取 `/api/sns/v2/note/feed` 返回 `success=false, code=-7`，提示版本过低；模拟器没有小红书 App。因此未取得该帖的 App 媒体响应，**原片下载仍未打通，不宣称此问题已解决**。后续需要可验证的 App 媒体数据入口，并按图片身份匹配，核对实际下载画面的水印、音轨、时长与方向后再接入，不能用猜测 URL 或 AI 擦除替代原片。

本次代码仅补齐 `WEB_LIVEPHOTO_19` 的已知水印标识，原有回退下载仍保留。`app/src/test/resources/xhs/live-photo-web-preview.json` 是该响应的脱敏结构；测试确认所有候选均不是原始视频，不会因为切换视频偏好凭空产生原片。`XhsNoteParser.kt`、`XhsNoteParserTest.kt`、上述夹具及此文档为本轮修改范围。

2026-09-27 补充：[App 媒体响应研究](XHS_APP_MEDIA_RESEARCH.md) 核对了用户提供的两个 Android APK 和一个 IPA。两版 Android native 签名及响应中的 HMAC 更新已能独立执行，媒体接口随后返回登录过期；8.42 独立设备激活又被服务端以设备异常拒绝，未获得会话。尚未接入下载器，也尚未解决此样本的视频水印。

验证：`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.neoruaa.xhsdn.data.xhs.XhsNoteParserTest --tests com.neoruaa.xhsdn.domain.download.NoteOutputTest --console=plain` 通过，13 项测试零失败；`git diff --check` 通过。没有改动已能播放的小米容器，也没有暂存、提交或推送。
