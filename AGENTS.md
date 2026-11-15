# AGENTS.md

## 适用范围与优先级

- 本文件适用于整个仓库；子目录存在更具体的 `AGENTS.md` 时，以距离目标文件最近的规则为准。
- 先理解用户当前请求、相关实现和现有差异，再做最小范围修改；不要顺手重构、改名或扩展未要求的功能。
- 用户当前请求与本文件冲突时，以用户当前请求为准，并在交付说明中指出取舍。

## 工作区与 Git 安全

- 开始修改前先检查 `git status --short` 和相关 `git diff`，识别并保留用户已有的未提交改动。
- 不得使用破坏性的 `git reset`、`git checkout`、`git clean`、强制覆盖或类似操作清理工作区。
- 不修改 `local.properties`；不得在命令、补丁、日志或回复中输出其内容以及其中的 SDK 路径、密钥或其他敏感信息。
- 不覆盖、回退或格式化与当前请求无关的文件。若用户改动与目标文件重叠，应在保留其意图的前提下做增量修改。
- 完成修改后，先向用户报告变更内容与验证结果。除非用户在当前请求中明确授权，否则不执行 `git add`、`git commit` 或 `git push`。
- 即使用户授权 Git 操作，也要先核对精确文件范围；不得把无关的用户改动一并暂存或提交。

## 文案与本地化

- 禁止硬编码任何面向用户的字符串，包括按钮、标题、提示、Toast、Dialog、通知、无障碍描述和错误文案。
- Composable 中使用 `stringResource(R.string.xxx)`；非 Composable 或持有应用上下文的代码使用 `appContext.getString(R.string.xxx)`。带动态内容时使用字符串资源格式参数，不用字符串拼接绕过本地化。
- 新增或修改字符串时，必须同步维护以下三个文件，并保持 key 集合、占位符数量与类型一致：
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-zh/strings.xml`
  - `app/src/main/res/values-zh-rTW/strings.xml`
- UI 规范中的资源目录同样以这三个实际目录为准；不得照搬其他工程的包名、资源限定符或文件路径。

## 实现约束

- 遵循现有 Kotlin、Compose、ViewModel、数据层和 miuix 组织方式，优先复用仓库已有组件与依赖。
- miuix 当前使用 `0.9.4-d8efe187-SNAPSHOT`，相关模块统一版本。快照从 `settings.gradle` 配置的 GitHub Packages 仓库解析，凭据使用仓库外的 `gpr.user` / `gpr.key` 或环境变量 `GITHUB_ACTOR` / `GITHUB_TOKEN`，不得写入仓库。升级时核对实际发布的快照及 API，并同步维护本节。新版 miuix-nav 的内联 API 使用 JVM 21，Java 与 Kotlin 编译目标保持 21。
- UI 变更必须遵循下方 UI 规范：二级页顶栏适配手机与宽屏，长列表保持 Lazy item 粒度，复杂多行卡片拆成独立的 Lazy item 并拼接为连续卡片。
- 文档中引用实现时，必须先核对当前仓库；已有实现使用 `app/src/main/java/com/neoruaa/xhsdn/` 下的真实路径，不存在的组件改写为行为要求，不得保留其他工程的包名或创建无关空壳组件。
- 下方 UI 规范已按当前项目结构调整；后续若目录或组件迁移，应同步更新引用，但不得擅自放宽行为与性能约束。
- 不无故增加依赖、模块、抽象层或兼容分支。确需新增时，应说明原因并验证对 `minSdk 24` 的兼容性。
- 协程、下载、文件与 MediaStore 操作必须正确处理取消、异常和资源释放；不要吞掉异常或把失败误报为成功。
- Flow 在 Compose 层使用 `collectAsStateWithLifecycle()`；列表必须提供稳定 key，并避免把大型多行内容塞进单个 Lazy item。
- 保持代码标识符、代码注释、日志和建议的 commit message 为英文；面向用户的说明与本文件使用中文。

## 验证与交付

- 根据改动范围执行最小但充分的验证。至少运行 `git diff --check`；Kotlin/Compose 改动优先运行 `./gradlew :app:compileDebugKotlin`，逻辑改动再运行相关测试。
- 涉及交互、布局、权限、文件保存或系统 Intent 的变更，应在可用的模拟器或真机上验证对应流程；无法运行时必须明确说明未验证项，不得推测为通过。
- 最终报告应包含：修改文件、关键行为变化、实际执行的验证命令及结果、尚未验证的风险。未经当前请求授权，不附带执行 Git 写操作。

## UI 规范

- UI 统一采用 miuix OS4 风格，以 miuix demo 的 Glass Component 页为材质和交互标准。通过共享能力判断在 Android 13（API 33）及以上且 `isRenderEffectSupported()` 可用时启用 OS4，低版本或效果不可用时自动使用标准 miuix，保持 `minSdk 24`。
- **共享组件** 复用 `app/src/main/java/com/neoruaa/xhsdn/ui/` 下的 `MiuixComponents.kt`、`MiuixOverlays.kt`、`AdaptiveUi.kt` 和 `MiuixEffects.kt`，集中维护组件作用域、能力判断、顶栏、图标按钮、下拉选择和 backdrop，弹层复用同目录下的 `MiuixDialog`、`MiuixBottomSheet` 与 `MiuixDialogButton`。`isRenderEffectSupported` 使用新版 `top.yukonga.miuix.kmp.material` API。
- **组件选择** OS4 顶栏使用 `GlassTopAppBar`，顶栏操作使用 `GlassIconButton`，选项使用 `GlassDropdownPreference`，图标菜单使用 `GlassIconDropdownMenu`，长按菜单使用 `GlassPopup`。普通卡片、开关、表单与内容按钮继续使用 miuix `Card`、`SwitchPreference`、`TextField` 和 `TextButton`。
- **Glass 弹层** 通过共享封装使用原生 `GlassDialog`、`GlassDialogButton` 及 Glass BottomSheet 材质与交互。`MiuixBottomSheet` 复用 `GlassBottomSheet` 所用的 `OverlayBottomSheet` 与 `glassBottomSheetSurface`，将原生玻璃底层放入正文采样容器，顶栏独立覆盖其上。对话框使用 `MiuixDialog`，弹层底部操作使用 `MiuixDialogButton`。Android 13 以下或效果不可用时，封装自动回退到 `WindowDialog`、`WindowBottomSheet` 与标准 miuix 按钮。
  - 弹层内容容器保持透明，让内容直接显示在 Glass 材质上。内容中的普通卡片和操作按钮保持扁平 miuix 样式。
  - 弹层通过根 Scaffold 的 popup host 展示，页面 backdrop 采样排除弹层，关闭动画完成后执行 `onDismissFinished`，保留删除、清理状态等回调的时序。独立测试组合也使用 `MiuixComponentHost`。
- **胶囊形控件** 搜索框、筛选标签等胶囊轮廓统一使用现有 Capsule 依赖的 `com.kyant.capsule.ContinuousCapsule`，背景、裁剪、边框与阴影使用同一 shape。筛选栏复用 `ui/CapsuleTabRow.kt`，按照 miuix demo Icon 页的连体分段 tabs 组织轨道和滑动指示器，沿用 miuix 配色、交互和 Glass 材质，矩形材质底层统一由 Capsule 裁出连续轮廓。新增胶囊控件遵循相同实现。
- **首页搜索** 顶栏使用搜索图标入口，复用 `ui/HistorySearchOverlay.kt`，输入框、圆形关闭按钮、焦点、IME 和过渡行为按照 miuix demo Icon 页实现。打开与关闭时清空查询，关闭或返回时恢复普通首页。搜索层显示期间清除底层页面的交互语义，搜索结果与普通列表分别持有滚动状态。
- **历史卡片长按菜单** 使用当前触点的窗口坐标作为点状 anchor，由原生菜单处理窗口边缘避让与上下翻转。触点观察保持不消费事件，保留卡片点击、滚动、子项操作和长按触感。无障碍长按使用卡片中心作为 anchor。
- **Sheet 顶栏与滚动** 使用与页面相同的 `AdaptiveTopAppBar` 紧凑模式及 action 封装。`MiuixBottomSheet` 的内容 API 接收 `PaddingValues`，正文容器占满面板可用视口，使用一个自身持有的滚动容器，将测量所得顶栏高度、横向间距及导航栏安全区作为滚动容器内部 padding。顶栏保持固定，滚动内容可经过其下方。
  - 共享 sheet 外壳提供 `visualContentOffset` 接收正文滚动位置，顶栏采样 sheet 自身的内容 backdrop，避免采样自身。嵌入式顶栏消费顶部系统栏 inset，横向 inset 交由 sheet 外壳处理。选择媒体瀑布流直接作为占满视口的滚动容器，移除固定高度限制与外层纵向滚动。
  - 选择媒体标题将页名与所选 / 总数合并为一个本地化标题。左侧为关闭，右侧依次为全选和下载，两个右侧按钮使用 `8.dp` 间距，全选切换全选与清空，下载按当前选择状态启用。
  - 紧凑顶栏标题先按窗口几何中心定位，空间拥挤时再将标题整体钳制到左右操作之间。标题使用自动缩放，左右 padding 与右侧操作按钮间距统一为 `8.dp`，保证标题文字与按钮之间保留相同间距。
  - 渐进模糊与 action 统一采样已经渲染的原生 sheet 玻璃底层及正文，底层使用原生 surface scope 的动画 scrim。顶栏保持在该录制区域外，禁止在正文采样中直接重放外部页面或填入不同的表面色。按钮阴影由 sheet 外轮廓统一裁剪，紧凑顶栏保持无矩形裁剪。
  - OS4 顶栏按钮与面板顶部圆角保持同心，顶部与横向的可见留白统一按 `R - r` 计算，并与正文 `BottomSheetDefaults.insideMargin.width` 对齐。当前留白为 `24.dp`、圆形按钮半径 `r = 22.dp`，面板顶部圆角使用 `R = 24.dp + 22.dp = 46.dp`，两侧按钮中心距对应面板边缘均为 `46.dp`。
  - 按实际测量高度回收原生拖动条与标题区的预留空隙，释放后的区域纳入共享顶栏和内容视口的测量及触摸范围。顶栏、按钮和滚动内容使用一致的布局坐标，保证圆角对齐、指针命中与滚动区域一致。
- **返回与操作按钮** 通过 `AdaptiveUi.kt` 的共享按钮封装接入。OS4 返回图标使用 `MiuixIcons.Glass.ChevronBackward`，标准 miuix 使用 `MiuixIcons.Back`。Glass 顶栏按钮沿用库的圆形尺寸和材质，卡片内及普通内容操作统一使用扁平 `IconButton`，通过 `ActionIconButton` 封装，使用常规图标、`minHeight/minWidth = 35.dp` 与 `secondaryContainer` 背景。禁用操作保留禁用语义并阻止点击。OS4 顶栏按钮禁用时沿用同一 Glass 材质、backdrop、44.dp 按钮和 24.dp 图标尺寸，保持位置与布局一致。
- 当前项目没有底部导航。未来新增时使用与目的地语义匹配的图标，OS4 使用对应 Glass 导航控件，避免与导航展开按钮混淆。
- miuix 组件直接使用其内置圆角与 Glass 形状。自定义形状继续使用 `top.yukonga.miuix.kmp.squircle.*`，依照内容和性能选择 modifier。
  - 非点击纯色背景且内容不溢出时使用 `squircleBackground(color, radius)`，保留无 offscreen layer 的路径，避免额外 clip。
  - 图片或必须裁剪的内容使用 `squircleClip(radius)`。
  - 可点击元素使用 `squircleSurface(color, radius)` + `.clickable {}`，保证涟漪裁进圆角。条件可点击时按 `isSelectable` 退化为 `squircleBackground`。
  - 3dp 小徽章保持 `clip(RoundedCornerShape(3.dp))`，文字使用 9.sp Bold Monospace。
- **页面骨架** 列表页面使用 miuix `Scaffold` + 共享 `AdaptiveTopAppBar(scrollBehavior)` + `LazyColumn`。媒体详情可按内容使用 `LazyVerticalStaggeredGrid`，WebView 页面可使用固定操作区 + 可伸缩内容区。
  - Lazy 容器加 `.nestedScroll(scrollBehavior.nestedScrollConnection)`，触底触感与 overscroll 使用现有 `miuixVerticalScrollEffects()`。
  - `contentPadding` 的纵向只设置 `top = innerPadding.calculateTopPadding()`，横向按统一宽屏与安全区规则处理。
  - 首项为 Card / 表单时添加 `item { Spacer(Modifier.height(12.dp)) }`，以 `SmallTitle` 或自带上边距的标题 / 提示开头时省略。
  - 末尾统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`。
  - 二级页面签名禁止 `bottomPadding: Dp` 参数，使用末尾 Spacer 自适应。
  - 若未来页面由带 `bottomBar` 的外层 Scaffold 承载，内容必须接收并消费外层 bottom inset，避免被底栏遮挡。
- **Glass 材质与 backdrop** 使用 `MiuixComponentHost` 提供根 popup host 和组件作用域。根 popup host 位于页面与滚动内容的采样层外，防止弹层采样自身。
  - 页面 backdrop 用于下拉菜单和 popup，内容 backdrop 用于顶栏与标签栏，分别维护采样状态。独立 Activity 使用独立 host，无关页面之间避免共享采样。
  - 内容采样容器保持固定视口，将 overscroll 放在其内部。页面采样覆盖页面本体并排除根 popup host。行内 Glass 菜单的 `menuBackdrop` 录制区域位于菜单节点之外，避免自采样。
  - 手机上使用原生 `GlassTopAppBar(progressiveBlur = true)`，渐进模糊范围随包含 tabs 的 `bottomContent` 一起适配。宽屏固定小顶栏按实际前景高度测量模糊区域，覆盖顶栏与 tabs。顶栏按钮沿用对应 Glass 材质与采样，避免重复叠加 underlay。
  - 搜索和顶栏状态切换保持同一背景策略。材质不可用时使用 `MiuixTheme.colorScheme.surface` 作为背景。
  - 选择项优先复用 `MiuixSpinnerPreference`，保持所选值、回调与标准 miuix 回退行为一致。菜单通过库的 anchor API 与触发控件对齐。
- **导航与宽屏** 页面路由集中于 `MainActivity.kt` 的 miuix-nav 与 `ui/navigation/AppRoute.kt`，保留的独立 Activity 入口应使用相同 UI 封装和行为。
  - 宽屏判断统一使用 `rememberWindowLayoutInfo()`，窗口宽 ≥ 600dp 时采用宽屏布局。同一页面保持一个判断来源，避免界面缩放或 density 变化引发不一致。
  - 顶栏统一使用 `AdaptiveTopAppBar`，手机保留可折叠大标题，宽屏保持固定小顶栏。OS4 使用 Glass 实现，标准 miuix 使用对应配色与 `TopAppBar`，固定小顶栏以库的 `SmallTopAppBar` 布局为标准。
  - 当前快照的小顶栏遗漏 action 的放置，共享封装在 `AdaptiveUi.kt` 中以四槽布局补齐，沿用库的高度、inset 和标题宽度规则。升级快照时核对该问题及本地适配。
  - 固定小顶栏切回手机大标题时，按原生实际测量的折叠范围恢复状态。内容已滚动时恢复折叠，内容位于顶部时展开，覆盖初始宽屏与保存状态恢复。
  - 主页面与列表型二级页的 Lazy 容器保持全宽，宽屏通过 `contentPadding` 的 start/end 将内容限制到建议最大宽度 800dp，保留两侧滚动响应。
  - 二级页根内容容器消费 `displayCutout ∪ navigationBars` 的 start/end inset。顶栏由自身处理 inset，避免横屏刘海或手势条覆盖内容。
  - 若未来增加多目的地主导航，手机与宽屏共用同一内容 lambda，宽屏可切换相应导航栏，并正确消费 `systemBars ∪ displayCutout` 的横向 inset。
- **Card 间距** 水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`，卡片之间沿用 item padding，不使用 `Arrangement.spacedBy`。
- **多行卡片与懒加载** `LazyColumn` 中禁止 `item { Card { 多行 } }`，多行卡片必须将每行拆为带稳定 key 的独立 Lazy item，再拼接为视觉连续的 miuix 卡片。复用 `ui/GroupedCardItems.kt` 的 `groupedCardItems`。
  - 首段和末段使用 `squircleSurface` 填充并裁剪，中间段使用纯 `background`。语义对齐 miuix `Card` 的 `surfaceContainer`、`onSurfaceContainer` 和 16.dp 圆角，preference 保持自身内边距。
  - 分组末项 bottom padding 按所替换 Card 的间距传递（6/12/0）。条件行先构造稳定的行描述列表，或使用带稳定 key 的条件 item。
  - 分组 helper 本身保持无默认 item 动画。需要动画时在对应 item 内使用 `Modifier.animateItem(...)`，placement spec 保持非 null，避免下方分组跳变。
  - 顶层条件 item 的展开状态提升到页面级可保存状态，动态增删使用 item animation，避免通过 `AnimatedVisibility` 一次性组合整组。
  - 短小、纯静态的提示或说明卡可以保持单个 `item { Card }`。内容可能超过一屏时使用 `heightIn(min = 视口高)` 等可滚动布局，避免 `fillParentMaxHeight()` 将内容钉死为一屏。
- **TextField 表单** 直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`，保持独立表单布局。
- **Edit Dialog 按钮顺序** `not_modified | cancel | confirm`，三按钮使用 `weight(1f)` + `spacedBy(8.dp)`，confirm 使用 `MiuixDialogButton(primary = true)`。
- **Dialog 按钮文案与排版** 使用适合操作按钮的独立简短本地化标签，保存信息与下载分别使用 `common_save_info`、`common_download`，菜单和说明保留各自完整文案。共享按钮保持 `maxLines = 1`，字体从主题原生按钮字号向下自动缩放至 `14.sp`，标准 miuix 与 OS4 使用相同排版规则。无障碍文本保留完整按钮标签。
- **长内容 Dialog** 使用 `Column(Modifier.heightIn(max = 500.dp))` 限高，滚动区使用 `Modifier.weight(1f, fill = false).verticalScroll(...)`，按钮作为非加权子项固定底部。参考 `SettingsActivity.kt` 中的 `TimeFormatDialog`。
- **用户反馈** 复用当前 Activity 的 `showToast` 或 Android `Toast` 显示轻量操作结果，消息来自字符串资源。
- **本地化** Composable 使用 `stringResource(R.string.xxx)`，非 Composable 使用 `appContext.getString(R.string.xxx)`。新增字符串同步维护 `values`、`values-zh` 和 `values-zh-rTW`，key 使用 `{页面}_{描述}`，通用按钮使用 `common_` 前缀。代码注释与日志使用英文。
- **语义色** 状态、进度、按钮与错误色优先使用 `MiuixTheme.colorScheme.*`，同一语义色重复出现时再抽取到项目主题 token，屏幕中禁止散落 `Color(0xFF...)`。Glass 深浅色材质使用库的主题 style API。底部下载主按钮使用随 `primary` 着色的玻璃材质，其采样源排除按钮自身。
- **Flow 收集** 屏幕使用 `collectAsStateWithLifecycle()`，后台时停止上游驱动重组。
- **状态形状** UiState 保持不可变，大集合更新时创建新实例，避免在 Composable 中反复复制或进行全表结构性比较。仅在项目实际引入 immutable collections 后使用 `ImmutableList` / `ImmutableMap`。
- **可复用组件 API** `app/src/main/java/com/neoruaa/xhsdn/ui/` 下的可复用 composable 第一可选参必须为 `modifier: Modifier = Modifier`，并应用到 root-most 节点，wrapper 透传到底层 miuix 组件。
