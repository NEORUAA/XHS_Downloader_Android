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

- 所有 UI 组件使用 miuix；其组件内部已用 squircle 渲染圆角，直接用即可
- **自定义形状元素用 squircle modifier**：非 miuix 组件的手搓形状不用 `RoundedCornerShape` 的 clip/background，改用 `top.yukonga.miuix.kmp.squircle.*`（随 miuix-ui 传递；低版本/无 runtime shader 自动回退圆角）。按性能选：
  - **非点击纯色背景**（内容不溢出）→ `squircleBackground(color, radius)`：无 offscreen layer，**不要 clip**
  - **图片 / 必须裁剪的内容** → `squircleClip(radius)`：一个 offscreen layer
  - **可点击元素**（涟漪裁进圆角）→ `squircleSurface(color, radius)` + `.clickable{}`；条件可点击时按 `isSelectable` 退化为 `squircleBackground`
  - **3dp 小徽章保持 `clip(RoundedCornerShape(3.dp))`**：该尺寸肉眼无差异，不值多一个 GPU layer
- 返回按钮使用 MiuixIcons.Back
- 当前项目没有底部导航；未来新增时使用与目的地语义匹配的 `MiuixIcons`，不要复用会与导航展开按钮混淆的图标
- Badge：`clip(RoundedCornerShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **页面骨架**：列表型页面使用 Scaffold + TopAppBar(scrollBehavior) + LazyColumn；媒体详情可按内容使用 LazyVerticalStaggeredGrid，WebView 页面可使用固定操作区 + 可伸缩内容区
  - Lazy 容器必须加 `.nestedScroll(scrollBehavior.nestedScrollConnection)`；若项目已有滚动触底触感或 overscroll modifier 则直接复用，不为满足文档创建空壳 modifier
  - `contentPadding` 仅设 top（`innerPadding.calculateTopPadding()`），不设 bottom
  - 首个 item 是 Card / 表单时加 `item { Spacer(Modifier.height(12.dp)) }`；以 `SmallTitle` 或其他自带上边距的标题 / 提示开头时**不加**
  - 末尾统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`
  - **二级页面签名禁止 `bottomPadding: Dp` 参数**——靠上述末尾 Spacer 自适应
  - 当前项目没有底部导航；若未来页面由带 `bottomBar` 的外层 Scaffold 承载，内容必须接收并消费外层 bottom inset，避免被底栏遮挡
- **顶栏 / 底栏毛玻璃**：当前项目尚无统一毛玻璃栏组件。新增毛玻璃效果时，应先抽取项目内可复用的 bar wrapper 与 backdrop 状态，TopAppBar / NavigationBar 在模糊可用时使用透明背景、不可用时回退 `MiuixTheme.colorScheme.surface`
  - 每个 Activity 的 Scaffold 独立持有 backdrop，内容容器提供对应 texture；不要跨 Activity 或无关页面共享捕获状态
  - 搜索或顶栏状态切换必须保留同一背景策略，避免动画过程中出现不透明色块遮挡毛玻璃
  - 未实现毛玻璃时继续使用现有 miuix bar，不要散落复制未落地的组件名或伪实现
- **宽屏适配**：窗口宽 ≥ 600dp 时，通过当前 Android Compose 可用的窗口尺寸 API 或单一项目内工具判断宽屏；同一页面只能有一个宽屏判断来源，避免界面缩放或 density 变化导致外壳与内容策略不一致
  - **导航**：当前项目使用 Activity 页面跳转，没有底部 NavigationBar。若未来增加多目的地主导航，手机与宽屏应共用同一内容 lambda，宽屏可切换 NavigationRail，并正确消费 `systemBars ∪ displayCutout` 的横向 inset
  - **顶栏**：[MainActivity.kt](app/src/main/java/com/neoruaa/xhsdn/MainActivity.kt)、[SettingsActivity.kt](app/src/main/java/com/neoruaa/xhsdn/SettingsActivity.kt)、[DetailActivity.kt](app/src/main/java/com/neoruaa/xhsdn/DetailActivity.kt) 与 [WebViewActivity.kt](app/src/main/java/com/neoruaa/xhsdn/WebViewActivity.kt) 中的 TopAppBar 都应适配宽度——宽屏使用固定不折叠的 SmallTopAppBar，手机保留可折叠的大标题 TopAppBar；重复实现达到两处时抽取到 `app/src/main/java/com/neoruaa/xhsdn/ui/`，但不要预先创建未使用的空壳组件
  - **内容居中**：主页面和列表型二级页的 Lazy 容器保持全宽，宽屏仅通过 `contentPadding` 的 start/end 将内容限制到建议最大宽度 800dp；不要压缩 Lazy 节点本身的宽度，以免两侧形成滚动死区
  - **横屏屏幕缺口**：miuix Scaffold 不会自动为内容补齐所有横向 inset。二级页根内容容器必须消费 `displayCutout ∪ navigationBars` 的 start/end inset；顶栏继续由 TopAppBar 自身 inset 处理，避免横屏刘海或手势条压住内容
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不使用 `Arrangement.spacedBy`
- **多组件卡片拆为独立 lazy item（滚动性能）**：`LazyColumn` 里禁止 `item { Card { 多行 } }`——整卡一次性组合，行多时会导致滚动或条件展开卡顿。应把每行拆成带稳定 key 的独立 item，并以首段 / 中段 / 末段的圆角和背景拼回视觉连续的 miuix 卡片；同类实现达到两处时再抽取通用分组卡片 helper 到 `app/src/main/java/com/neoruaa/xhsdn/ui/`
  - **分角背景**：有圆角的首/末段 `squircleSurface`（fill+clip，**必须 clip**——否则段内 clickable 的方角涟漪溢出圆角）；中间段纯 `background`（无 offscreen layer 最省）。语义对齐 miuix `Card`（surfaceContainer + onSurfaceContainer + 16.dp 圆角；preference 自带内边距故段 `insidePadding=0`）
  - 分组末项的 bottom padding 按所替换 Card 的间距传递（6/12/0）；条件行先构造稳定的行描述列表，或直接使用带稳定 key 的条件 item
  - 分组 helper 本身不默认添加 item 动画；需要动画时在对应 item 内使用 `Modifier.animateItem(...)`，**placement spec 不能设 null**，否则下方分组会硬跳
  - 条件展开的行若是顶层 Lazy item，展开状态应提升到页面级可保存状态，不能放在可能被回收的 item 内；动态增删用 item animation，不要用一次性组合整组的 `AnimatedVisibility`
  - **不适用**：短小、纯静态的提示或说明卡可以保持单个 `item { Card }`；内容可能超过一屏时使用 `heightIn(min = 视口高)` 等可滚动布局，不要用 `fillParentMaxHeight()` 把内容钉死为一屏
- **TextField 表单**：不包 Card，直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`
- **Edit Dialog 按钮顺序**：`not_modified | cancel | confirm`（三按钮 weight(1f) + `spacedBy(8.dp)`），confirm 用 `ButtonDefaults.textButtonColorsPrimary()`
- **长内容 Dialog 滚动 + 按钮固定底部**：miuix `WindowDialog` 手机上不限 content 高度，过长会把底部按钮顶出屏。包 `Column(Modifier.heightIn(max = 500.dp))` 限高，滚动区 `Modifier.weight(1f, fill = false).verticalScroll(...)`（短内容自然收缩），按钮作为非加权子项固定底部；当前项目尚无长内容 Dialog 的标准实现，首次实现后再补充真实示例链接
- **用户反馈**：复用当前 Activity 的 `showToast` 或 Android `Toast` 显示轻量操作结果，消息必须来自字符串资源
- **i18n**：所有用户字符串走 `stringResource(R.string.xxx)`（Composable）/ `appContext.getString(R.string.xxx)`（非 Composable），禁止硬编码；新增同时维护 `app/src/main/res/values`、`app/src/main/res/values-zh`、`app/src/main/res/values-zh-rTW`。key 命名 `{页面}_{描述}`，通用按钮 `common_` 前缀。日志消息与代码注释使用英文
- **语义色 token**：状态、进度、按钮和错误色优先使用 `MiuixTheme.colorScheme.*`；同一语义色重复出现时再集中抽取到项目主题 token。**禁止屏幕里散落 `Color(0xFF...)`**
- **Flow 收集**：所有屏幕用 `collectAsStateWithLifecycle()`（`lifecycle-runtime-compose`），不用 compose runtime 的 `collectAsState`——后台时上游不再驱动重组
- **强跳过友好的状态形状**：UiState 保持不可变；大集合更新时创建新实例并避免在 Composable 中反复复制或做全表结构性比较。只有项目实际引入 immutable collections 后才使用 `ImmutableList` / `ImmutableMap`，不要仅为满足文档新增依赖
- **可复用组件 API**：`app/src/main/java/com/neoruaa/xhsdn/ui/` 下的可复用 composable 第一可选参必须是 `modifier: Modifier = Modifier` 并应用到 root-most 节点；wrapper 透传到底层 miuix 组件
