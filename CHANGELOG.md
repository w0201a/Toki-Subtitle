# Changelog

## 字幕版 / Subtitle edition

原作者 / Original author: [MeiYongAI](https://github.com/MeiYongAI/Toki)。字幕版维护 / Subtitle edition: **154Er · Gi-Ai154M**。

[下载发行版](https://github.com/w0201a/Toki-Subtitle/releases/latest) · [问题与修复记录](docs/ISSUES_AND_FIXES.md) · [提交问题](https://github.com/w0201a/Toki-Subtitle/issues/new/choose)

以下按现存开发记录与实机反馈整理。中间 Debug 版本用于排查，不表示当时问题已修复；较早记录未逐版保留完整时间，因此不补写推测日期。原项目更新历史保留在下方。

### 2.0.23 Debug — 2026-10-20

- 根据用户本地导出的诊断核对多项注册失败，修正子路径失配导致父功能整体撤销的问题；诊断文件不入库。
- 基础快速筛选未匹配的目标继续在手机内全量核验相同契约，只重查缺失目标；保留已有成功结果。旧失败缓存在恢复算法升级后重新核验，避免永久沿用失败结果。
- 字幕渲染优先使用已核验的完整方法描述符，不再因参数类型未独立映射而拒绝已有有效成员；仍核对完整签名。
- 字幕目标语言与排除语言从精确偏好键和 Keva 读取行为定位；评论开关根据实际调用关系选取方法，不再固定旧版方法数量。仍拒绝歧义。
- 视频保存选源不再要求所有备用播放地址方法同时存在，并与保存权限和路径增强独立注册。
- 信息流离线恢复、推荐数据模型和适配器分别注册；页面净化逐组件跳过缺失契约，奖励组件与计时组件独立注册。
- 自动滚动入口解锁与页面行为分别注册；页面路由未匹配时保留已核验入口，但不据此宣称自动滚动行为可用。
- 字幕服务读入口独立使用已有核验结果，一条读入口失配不再强制撤销其他可用读入口。
- 本次尚未确认用户所切换代码集合上的字幕显示／清屏、评论复制及自动滚动页面路由恢复；部分安装和构建通过均不等同于上述画面或行为已修复。

### 2.0.22 Debug — 2026-10-19

- 字幕文件采用两条有界异步下载通道，覆盖已加载推荐列表的后四个真实位置；每条优先准备开头六句，轮流排程，避免较长的前一条独占预翻译。
- 预翻译队列上限 32 句，译文缓存上限 256 条；切换视频优先复用已完成译文，不需要重复请求。
- 当前句与预读分别维护发送时机：预读刚发出时，不再额外扣住当前句 250ms；当前句之间仍保留最小间隔，两条翻译请求通道仍有界。
- 单独启用字幕时也声明推荐列表依赖，避免未开启信息流过滤时无法取得后续视频。
- 不保证尚未加载、无字幕轨或网络未完成的视频提前具备全部译文；本次未完成手机画面与网络耗时验收。

### 2.0.21 Debug — 2026-10-19

- 长文案改为按段显示：成功段立即提交，等待或失败段保留原文；失败段在其他段之后最多重试一次，不再因单段失败让整篇一直没有译文。
- 标题绑定完成后重新记录宿主最终创建的控件，处理绑定期间新建或替换控件导致最终布局没有关联到文案的问题。
- 字幕失败退避仅作用于失败句的重试，新字幕不继承旧句最多 15 秒的等待；保留请求间隔和两条有界通道。
- 这些修改修复已确认的调度与显示缺陷，不代表已经解决所有宿主版本的入口失配。版本切换仍按实际代码身份自动分析。

### 2.0.20 Debug（包含 2.0.19 评论修复）— 2026-10-19

- 使用兼容 Android 9+ 的集合收集 API，修正最终 lint 发现的 API 兼容性问题。
- 修复普通评论翻译仍依赖旧整体指纹的问题：手机从评论自动翻译偏好供应者、惰性值字段、布尔读取与调用关系独立定位入口；只有唯一且完整的关系才启用。
- 保留已核验旧结构作为另一条路径，评论投票仍独立注册，不由普通评论入口失败连带撤销。
- 状态页新增「普通评论翻译入口」，区分普通评论和评论投票。算法修订使旧扩展结果自动重新核验，无需 ADB。
- 这次修复针对评论入口未注册；入口注册不等于已验证所有评论内容的最终翻译显示。

### 2.0.18 Debug — 2026-10-19

- 减少扫描内存：候选仅保留已接受类的独立数据，不再持有整个 DEX 缓冲；按 ZIP 中的实际长度一次性读取 DEX，避免流式拼接的额外整块拷贝。
- 扩展阶段显示实际分析步骤与当前阶段已处理的 DEX 数量；基础功能在基础依赖核验后先行安装，不再等待字幕扩展全部分析完毕。
- 对扫描器内存耗尽发布明确失败结果，避免扫描线程结束而窗口永远停留在字幕扩展。字幕接口表示转换后仍按参数描述符文本核验，保留订阅接口解析。
- 原生快速筛选失败时，自动切换独立的本机 DEX 分析；不把筛选器当作字幕适配的必需服务。
- 功能状态页展示字幕目标语言、字幕轨、显示与清屏、字幕菜单、标题原生翻译、标题译文显示及评论投票的独立注册结果。
- 修正字幕菜单失败未计入视频翻译父级部分失败状态的问题。
- 更新、降级或规则变化仍自动触发手机内分析；重新检测按钮仅用于本机补救，不要求 ADB。

### 2.0.17 Debug — 2026-10-19

- 新增手机端二次分析：按有序业务引用恢复类与成员，不受寄存器分配、跳转偏移及指定插桩调用影响；未知混淆类需要多项独立证据，歧义保留为未适配。
- 为字幕渲染、语言偏好、原生翻译请求、响应订阅和投票生命周期增加专项调用关系规则，适配分析在手机内运行，不依赖 ADB、上传或外部适配服务。
- 字幕语言、显示、菜单、标题及评论投票独立注册；诊断辅助接口或可选偏好失配不再阻止主要翻译通路。模块回调异常撤销当前功能并恢复原调用，宿主异常不重复执行。
- 功能状态页新增「重新检测适配」：保存请求后完全关闭并重开 TikTok，由手机重新扫描，无需清除数据。部分安装失败会显示部分完成，不再只报缓存有效。
- 新增默认关闭的「允许投屏和截图」，仅对 TikTok 主窗口移除禁止截图标记，用于手机显示正常而投屏黑屏的情况；开关独立于字幕扫描。
- 对同一候选类复用方法证据索引，减少重复分析。首次分析仍可能较慢，后续启动复用经过代码身份校验的结果。
- 当前版本的接口恢复、运行注册和最终画面分别验收；大幅重写或无法消歧的未来接口仍可能需要模块更新，不保证所有新版本兼容。

### 2.0.16 Debug — 2026-10-19

- 字幕、长文案、评论投票、字幕设置与预读扩展接入独立的结构适配层，移除仅接受两个旧代码集合的限制。
- 宿主更新后按实际 APK／Split 代码指纹使缓存失效，在后台重新扫描类、方法与字段；唯一匹配后保存并在当前进程自动安装，切换视频刷新已有内容。
- 结构证据包含指令、常量、寄存器和控制流；同签名接口通过独立调用位置消除歧义。具名模型新增无关成员时仍逐项验证已使用的方法、字段类型与序列化注解。公开配置只包含摘要和成员描述，不包含宿主代码。
- 扩展按独立注册事务安装；缺失或歧义时撤销该扩展，避免字幕扩展失配导致基础翻译一起停用。
- 字幕容器通过已核验的渲染器关联识别，字幕设置图标使用系统资源，减少宿主资源编号变化造成的失效。
- 自动适配覆盖结构保持一致的改名或重排；内部实现明显变化仍需维护规则，不承诺所有未来版本自动兼容。首次发现完成后自动应用，无需连接电脑或再次重启。
- 新增调用关系适配：投票桥接绑定、标题视频字段写入、字幕帧到渲染器调用、具体翻译服务实现以及跨类字段使用证据。大类的无关方法变化不会直接废弃全部成员，但被使用的成员仍须独立核验。
- 翻译开关独立安装，原生标题翻译与布局补偿分别注册。内置基础结果按目标规则复用，规则升级只补查变化依赖，避免单个入口变化连带停用其他翻译扩展。
- GitHub 默认验证并构建 Debug；Release 改为手动选择，减少日常调试的重复构建。
- 修正基础扫描已到 100% 时字幕扩展仍在后台查找的误导进度：扩展显示独立阶段，校验保存完成后才显示结果。原生筛选只使用必要类和方法调用证据，字段补充证据仅在候选类中核对，减少无关指令扫描。
- 未知构建扫描结束后，在当前进程自动安装已验证的功能并应用配置；切换视频即可刷新已有内容，普通用户无需连接电脑或再次重启。结果安装完成后才显示扫描完成。
- 增加父类、接口和方法签名的原生预筛选；搜索提示单独存储，单纯调整筛选提示不会使有效匹配缓存失效。匹配算法升级使用独立修订标识重新核验。
- 字幕渲染入口可通过已核验帧、渲染器字段和实际调用识别；翻译目标语言从已核验的翻译请求和语言偏好读取关系识别。原生标题资格入口通过文案类型检查及自动翻译调用关系适配。
- 私有字幕选择器或缓存读入口失配时，继续使用独立核验的字幕服务入口；请求诊断入口失配不再撤销原生标题翻译。补齐进度提示的语言资源和字符串格式检查。

### 文档维护（未发布新 APK）

- 更新中英文 README、最近版本记录、问题状态与链接去向说明。
- 字幕版下载、反馈、CI 和克隆命令指向本仓库；原作者仓库、上游模块目录和原作者赞助链接保留并标注用途。
- 修正 README 的发行证书指纹，说明独立发行签名与 Debug 签名的区别。
- 不公开本机日志、截图、凭据和宿主反编译文件。

### 2.0.15 — 2026-10-19

- 首页明确展示原作者 **MeiYongAI**，提供上游仓库入口，字幕版联合署名单列。
- 发布首个使用字幕版独立发行证书签名的 Release，启用 R8 和资源压缩，附件提供 SHA-256 校验值。
- 修复赞助图片实际为 JPEG、扩展名却为 PNG 导致的 Release 资源编译失败；改名及导出 MIME 与真实格式保持一致，不改变图片字节。
- 将字幕版新增文案单独保存，保留上游语言目录；新增文案支持英文与简体中文，其他语言回退英文。
- 修正原图读取的 lint 类型标记。GitHub Actions 已通过完整单元测试、lint、Debug / Release 构建并上传构建产物。
- 发行签名与 Debug 不同，不能直接互相覆盖；未来发行版沿用本次发行密钥。未自动卸载或清空设备应用。

### 2.0.14 Debug

- 首页新增「赞助字幕版开发」，支持微信支付／支付宝选择和收款码原图保存。
- 软件首页 GitHub 入口指向字幕版仓库；公开源码并保留 MIT 许可证和原作者版权。
- 原图导出 5 项测试通过。JPEG 扩展名与完整语言资源检查问题在 2.0.15 发行前修正。

### 2.0.13 Debug

- 首页版本号下方、代码注释及中英文说明加入 **154Er · Gi-Ai154M** 字幕版联合署名。
- 不替代原项目作者及许可证；2.0.15 进一步在首页显式展示原作者入口。

### 2.0.12 Debug

- 重构视频标题与长文案显示链路，绑定当前视频后分段翻译，并在宿主最终文本布局入口应用译文。
- 修复“请求成功、译文数据已生成，但展开后仍显示外语”的问题；不再依赖多层原生布局缓存刷新。
- 保留展开／收起和查看原文状态，切换视频时隔离旧结果。
- 相关 16 项测试通过；实机截图确认展开后的多段中文正文实际显示。未逐句核验语义准确性。
- 纯文本回退下的话题与链接点击能力仍需回归，见问题记录。

### 2.0.11 Debug

- 继续排查长文案数据与渲染布局不同步。译文请求成功仍未解决最终画面，后续由 2.0.12 重构处理。

### 2.0.10 Debug

- 真机确认分段请求成功并写入显示数据；用户复测仍显示外语，说明数据写入不能代替画面验收。

### 2.0.9 Debug

- 长文案按最多 700 个 UTF-16 字符分段，优先按空白和标点切分，不拆开代理对。
- 全部分段完成后按顺序合并，避免长内容截断；最初向原生状态回填的方案仍未解决显示问题。

### 2.0.8 Debug

- 接入宿主视频说明／图文标题的翻译资格和自动翻译入口。
- 原生翻译请求已触发，但长俄文正文仍可能保留原文；问题后续持续排查至 2.0.12。
- 用户确认 2.0.7 的评论投票翻译正常。

### 2.0.7 Debug

- 修正评论投票绑定方法：以真实 DEX 方法签名匹配，避免使用反编译器合并桥接后显示的方法名。
- 投票题目和选项请求成功，用户确认翻译正常；不修改投票内容模型或票数。

### 2.0.6 Debug

- 初步加入评论投票题目与选项的独立翻译队列、缓存和过期结果隔离。
- 运行时绑定契约匹配失败，尚未形成可用修复；由 2.0.7 修正。

### 2.0.5 Debug

- 按推荐列表当前位置预读紧邻后两条视频的原语言字幕，限制文件与译文缓存，切流取消过期任务。
- 当前句优先，后两条各少量字幕排在其后；无可用字幕地址时不保证预读命中。

### 2.0.4 Debug

- 用户确认清屏字幕正常后，针对翻译响应慢优化当前句与预读调度。
- 当前句和预读使用独立请求通道，复用相同文本的进行中请求，并在排队出队时去重。
- 未作固定视频性能基准对照，不宣称统一加速比例。

### 2.0.3 Debug

- 修复 TikTok 自带清屏时字幕消失：使用仍可见的视频页挂载独立字幕层，接入实际字幕帧。
- 离页和隐藏整张视频卡片时隐藏旧字幕，保留切流生命周期与结果隔离。
- 用户确认清屏字幕问题已解决。

### 2.0.1–2.0.2 及早期 2.0.0 开发版本

- 逐步建立独立字幕显示层、样式设置、播放页字幕设置与拖拽入口。
- 按需求加入拖拽开关和 X / Y 独立锁定，并持续排查自动清屏及原生字幕渲染状态。
- 最初曾尝试第三方翻译和语音入口，后续移除相关路径，当前使用宿主原生翻译服务；无须提供第三方翻译 API Key。
- 早期各构建的变动未逐项完成可追溯映射，按阶段归档，不将开发尝试记为全部可用。

## 上游历史 / Upstream history — MeiYongAI/Toki

以下为原项目记录；包名、签名和升级说明适用于对应上游版本，不代表字幕版 2.x 的安装规则。

## 1.0.4 — 2026-10-08

### English

- Faster first setup: verified TikTok builds use bundled adaptation results; other builds use native method discovery.
- Added Center and Fullscreen video modes. Fullscreen crops suitable portrait videos while keeping landscape videos centered.
- Expanded layout cleanup and added separate control opacity settings, including the top navigation indicator.
- Fixed the progress bar disappearing on profile videos in automatic clean mode.
- Added a Belarus SIM preset and a background audio unlock option; simplified access to the native Captions and translation menu.
- Refined settings and translations across supported languages.

Update directly from **1.0.1–1.0.3**; the signing key is unchanged. Restart TikTok after updating. If method discovery runs, restart again when it finishes. Official TikTok clients only; subtitle availability still depends on TikTok.

### 中文

- 加快首次适配：已验证的 TikTok 构建直接使用内置适配结果，其他构建使用原生方法查找。
- 新增「居中」和「全屏沉浸」视频显示选项，适合的竖屏视频可裁切铺满，横屏视频保持居中。
- 扩展页面布局净化，新增分区域控件透明度设置，覆盖顶部导航指示条。
- 修复作者主页视频在播放清屏时无法保留进度条的问题。
- 新增白俄罗斯 SIM 预设和后台音频解锁选项，简化原生「字幕和翻译」入口解锁。
- 精简设置文案，完善多语言适配。

可直接覆盖升级 **1.0.1–1.0.3**，沿用原签名。更新后重启 TikTok；如触发方法查找，完成后再次重启。目前仅支持官方版 TikTok，字幕是否可用仍由 TikTok 决定。


## 1.0.3 — 2026-10-04

### English

- Added layout cleanup with separate top navigation, bottom navigation and video-page controls, including the stop auto-scroll button.
- Enhanced native auto scroll across video pages, including profile and Following feeds.
- Reuse valid method-discovery results after module updates; only new or changed rules are scanned when TikTok is unchanged.
- Fixed missing seek-time numbers and visual search tags in automatic clean mode.
- Updated translations and kept module settings accessible through LSPosed when the launcher icon is hidden.

Update directly from **1.0.1 / 1.0.2**; the signing key is unchanged. Restart TikTok after updating and again if method discovery runs. Official TikTok clients only.

### 中文

- 新增页面布局净化，顶栏、底栏、视频页面独立设置，支持隐藏停止连播按钮。
- 增强原生自动连播，支持作者主页、关注页等视频页面。
- 优化方法缓存复用：TikTok 未更新时，模块更新仅补查新增或变化的规则。
- 修复自动净屏时拖动时间数字不显示、暂停后“搜同款”标签不出现的问题。
- 完善多语言文案，隐藏桌面图标后仍可从 LSPosed 打开模块设置。

可直接覆盖升级 **1.0.1 / 1.0.2**，沿用原签名。更新后重启 TikTok，如触发方法查找，完成后再次重启。目前仅支持官方版 TikTok。

## 1.0.2 — 2026-10-04

### English

- Added official TikTok **47.1.4** support and improved adaptation across **47.0.3** and **46.8.3**.
- Improved method discovery reliability and speed, including download, filtering, playback speed, comment copying, author region and auto-scroll adaptation.
- Fixed the video description translation unlock interfering with translation availability.
- Improved immersive layout and centered photo posts while preserving their aspect ratio.
- Fixed controls remaining invisible after pausing in automatic clean mode.

Update directly from **1.0.1**; the signing key is unchanged. Restart TikTok after updating and again after method discovery completes. Official TikTok clients only.

### 中文

- 新增官方 TikTok **47.1.4** 适配，完善 **47.0.3**、**46.8.3** 多版本适配。
- 提升方法查找稳定性与速度，完善下载、过滤、倍速、评论复制、作者地区及自动滚动适配。
- 修复视频内容翻译解锁影响翻译可用性的问题。
- 优化全屏沉浸，图文保持比例居中显示。
- 修复自动净屏暂停后部分控件仍不可见的问题。

可直接覆盖升级 **1.0.1**，沿用原签名。更新后重启 TikTok，方法查找完成后再次重启。目前仅支持官方版 TikTok。

## 1.0.1 — 2026-10-03

### 中文

- 修复 TikTok 47.0.3 部分视频无水印下载生成 0 字节 `null.mp4` 的问题：在官方下载选源阶段使用同一视频的有效播放源，保留官方文件名、下载进度和保存流程。
- 状态栏隐藏支持直播观看页，并在返回前台、恢复窗口焦点和配置变化时保持生效。
- 优化方法查找窗口的留白，并修复开始查找时进度条短暂显示满格的问题。
- 按启用功能安装 Hook 和查找所需方法，统一代码指纹匹配，并串行化适配缓存读写、校验提交结果。
- 包名为 `io.github.meiyongai.toki`，版本码为 `2`。
- **本版使用新正式签名，不能直接覆盖安装原签名的 1.0.0。请先导出 Toki 配置，再卸载旧版、安装本版、导入配置，并在 LSPosed 中确认启用及作用域后重启 TikTok。仅卸载 Toki，不需要卸载 TikTok。**
- 无水印下载和直播状态栏隐藏已通过用户实机复测。直播顶部黑色留白保持宿主布局。

### English

- Fixed zero-byte `null.mp4` downloads on TikTok 47.0.3 by resolving a valid playback source during native watermark-free source selection. Native naming, progress and saving remain in use.
- Added status-bar hiding on live viewing pages, including resume, window focus and configuration changes.
- Reduced excess spacing in method discovery and fixed the initial full-progress flash.
- Install hooks and discover methods according to enabled features, use shared code fingerprints, and serialize adaptation-cache access with commit verification.
- Package: `io.github.meiyongai.toki`; version code: `2`.
- **This release uses a new signing certificate and cannot update the original signed 1.0.0 in place. Export Toki settings, uninstall Toki, install this release, import settings, confirm LSPosed activation and scope, then restart TikTok. Do not uninstall TikTok.**
- Download and live status-bar fixes were verified on-device by the user. The live page's top black spacing remains unchanged.

## 1.0.0 — 2026-09-30

### English

- New module identity: `io.github.meiyongai.toki`, version code `1`, with a new release signing certificate. This installs separately from `com.seepd.toki`; disable the 0.x module in LSPosed before enabling 1.0.0. Settings are not migrated automatically.
- Independent rewrite: the entire 1.0.0 source tree is new code and no longer contains the 0.x `com.seepd.toki` sources.
- TikTok 47.0.3 and 46.8.3 adaptation, with method discovery and persistent results keyed to the host code and rules.
- Feed filters for topic and creator recommendation cards, keywords, duration, counts, offline insertion and AI-labeled videos or photos. Ad filtering also covers creator-profile video lists; other content rules remain limited to For You.
- Playback and clean-mode controls, progress-bar handling, translation and original/translated comment copying.
- Material 3 interface with 57 language options, feature status, configuration import/export and donation-image saving.
- Local validation: 252 unit tests passed; lint reported 0 errors and 28 warnings. Release builds use R8 and resource shrinking. The latest profile-ad changes still need device verification.

### 中文

- 新模块包名为 `io.github.meiyongai.toki`，版本号 `1`，使用新的正式签名。与 `com.seepd.toki` 分开安装；请先在 LSPosed 中停用 0.x 模块，再启用 1.0.0，配置不会自动迁移。
- 独立重写：1.0.0 的全部源码均为新代码，不再包含 0.x `com.seepd.toki` 的源文件。
- 适配 TikTok 47.0.3、46.8.3，支持方法查找，并按宿主代码与规则标识持久保存结果。
- 信息流过滤涵盖话题及创作者推荐卡片、关键词、时长、数量范围、离线插入，以及标记为 AI 生成的视频和照片。广告过滤扩展到作者主页的视频列表，其他内容规则仍只作用于推荐页。
- 完善播放清屏、进度条处理、翻译与评论原文／译文复制。
- Material 3 界面提供 57 种语言选项、功能状态查看、配置导入导出及赞助图片保存。
- 本地验证：252 项单元测试通过，Lint 0 错误、28 警告；Release 启用 R8 与资源压缩。最新作者主页广告过滤调整仍待实机验证。


[English](#changelog) | [中文](#中文)

## 0.4.23

- Added a Home dashboard as the default destination with LSPosed service status and the installed TikTok version.
- Moved the Root-based TikTok restart action to Home and added Root-only cache clearing that preserves accounts, settings, drafts, and app data.
- Added manual interface language switching between Follow System, English, and Chinese, plus an action to reset all Toki settings.
- Added direct GitHub, Telegram, and issue-report links and expanded navigation to Home, General, Feed, and Downloads.
- Added an independent page-purification control for game-related entrances.

## 0.4.22

- Added an option to keep the video progress bar visible on standard videos shorter than 30 seconds; the existing hide-progress-bar option takes priority.
- Added optional author-location display with the matching country flag and region code beside the author name.
- Split author-avatar and author-information purification into independent controls.
- Updated TikTok 46.4.3 purification paths for the LIVE entry, search entry, top and bottom navigation, music title, and video feedback surveys.
- Added grouped purification controls for commercial and promotion labels, creative tools and templates, movie and anime entrances, sharing and creator incentives, and activity safety warnings.
- Moved feed payload, label, anchor, survey, and warning gates into a dedicated hook module and removed stale decompiler aliases and obsolete warning paths.

## 0.4.21

- Added an optional page-purification control to hide the system status bar on TikTok's main video pages.
- Applied the status-bar control only to TikTok 46.4.3's `MainActivity` and kept it active across layout updates.
- Used Android's `WindowInsetsController` on Android 11+ with a legacy system-UI fallback for Android 8–10.

## 0.4.20

- Set official TikTok 46.4.3 as the sole implementation, testing, and maintenance target.
- Other TikTok versions are outside the support scope and receive no version-specific compatibility work.
- Removed 46.3.x download-location and comment-translation compatibility code.
- Split hook installation into isolated feature classes while keeping one module APK and entry point.
- Extended TikTok 46.4.3 For You filtering to feed dispatch and cache-backed list insertion paths.
- Preserved the exact text entered in view-count and like-count range fields while keeping numeric filtering unchanged.
- Added an option to disable offline cold-cache use when a network is available.

## 0.4.19

- Added compatibility for the comment-translation control and playback-completion handling on official TikTok 46.4.3.
- Fixed custom video, image, and GIF save locations on TikTok 46.3.2 and 46.4.3 by intercepting TikTok's MediaStore insertion bridge.
- Replaced the Android system directory picker with a built-in relative shared-storage path editor.
- Extended trending-topic and promotional-overlay purification for TikTok 46.4.3.
- Known issue: view-count and like-count filtering remains unreliable on TikTok 46.4.3 and may let some out-of-range videos through.

## 0.4.18

- Reworked the Material 3 settings screen and organized features into General, Feed, and Downloads sections.
- Added page purification with optional controls for author details, descriptions, music, action buttons, search, Tako, translation controls, and navigation bars.
- Added filters for AI-generated content, trending-topic bars, and content-rating prompts.
- Added independent GPS, system-language, and system-time-zone spoofing that follows the selected target region.
- Added an option to skip the startup login guide by dismissing skippable prompts only; it does not bypass login or verification.
- Improved view-count and like-count filters with support for full numbers and `K`/`M`/`B` suffixes.
- Removed the failed anti-burn-in feature; the standard default playback-speed option is now displayed as `1.0x`.
- Clarified download wording to state that all videos prefer watermark-free URLs.

## 0.4.17

- Added support for official TikTok 46.3.2 and 46.3.3.
- Fixed the comment translation button not executing translation on TikTok 46.3.2.
- Adapted the anti-burn-in status Toast entry for TikTok 46.3.2.

## 0.4.16

- Improved anti-burn-in clear-screen state retention and restoration across videos and photo posts.
- Confirmed compatibility with official TikTok 46.3.3.
- Updated the launcher icon with a solid-color background.

## 0.4.15

- Limited support to the official TikTok client and removed compatibility code for modified clients.
- Removed grayscale mode and forced unmute settings that depended on third-party client bridges.
- Improved loop disabling so playback enters TikTok's native paused state and shows the replay frame.
- Fixed the need for two taps to replay a video and the feature becoming inactive after switching videos.
- Stopped forcing progress-bar synchronization to reduce reliance on high-frequency callbacks.

## 0.4.14

- Added default playback speeds: 1.0x, 1.25x, 1.5x, 1.75x, and 2.0x.
- Applied the selected speed to each new video after its first render without overriding manual speed changes.
- Adapted to the official TikTok 46.3.3 player interface.

## 0.4.13

- Switched to libxposed API 102 and fixed the TikTok scope.
- Reworked the Material 3 settings UI, region selection, and media-directory selection.
- Added comment-translation state retention and scrolling-list synchronization.
- Added the two-finger long-press anti-burn-in clear-screen mode.
- Fixed the need for two taps to replay a video after loop disabling.
- Added the Root-based Restart TikTok action.

## 中文

### 0.4.23

- 新增首页并设为默认页面，可查看 LSPosed 服务状态与已安装的 TikTok 版本。
- 将基于 Root 的 TikTok 重启操作移至首页，并新增仅清除缓存的 Root 操作；账号、设置、草稿和应用数据均会保留。
- 新增界面语言手动切换，可选择跟随系统、English 或中文，并可一键重置全部 Toki 配置。
- 新增 GitHub、Telegram 与问题反馈直达入口，导航扩展为首页、常规、信息流和下载。
- 新增独立的游戏相关入口页面净化选项。

### 0.4.22

- 新增“总是显示视频进度条”，移除普通视频短于 30 秒时隐藏进度条的限制；与隐藏进度条同时开启时，以隐藏为准。
- 新增作者位置显示，可在作者昵称旁显示对应国家/地区旗帜和地区代码。
- 将作者头像与作者信息拆分为两个独立的页面净化选项。
- 更新 TikTok 46.4.3 的直播入口、搜索入口、顶部与底部导航栏、音乐标题和视频评价问卷净化路径。
- 新增商业合作与推广、创作工具与模板、影视与动漫、分享与创作者激励、活动伤害警告等分类型净化选项。
- 将 Feed 数据、标识、锚点、问卷和警告 Hook 整理到独立模块，并清除反编译伪包名与失效的旧警告路径。

### 0.4.21

- 新增页面净化选项，可隐藏 TikTok 主视频页面的手机系统状态栏。
- 状态栏功能仅作用于 TikTok 46.4.3 的 `MainActivity`，并在页面布局更新后保持生效。
- Android 11 及以上使用 `WindowInsetsController`，Android 8–10 使用兼容性的系统 UI 标记。

### 0.4.20

- 将官方 TikTok 46.4.3 设为唯一的实现、测试与维护目标。
- 其他 TikTok 版本不在支持范围内，不再提供针对版本的专门适配。
- 移除 46.3.x 保存位置和评论翻译兼容代码。
- 将 Hook 按功能拆分为相互隔离的代码类，同时保持单一模块 APK 和入口。
- 将 TikTok 46.4.3 推荐页过滤扩展到信息流消息分发和缓存列表插入路径。
- 保留播放量与点赞数范围输入的原始文本，过滤计算仍使用解析后的数值。
- 增加联网时禁用离线冷缓存的选项。

### 0.4.19

- 适配官方 TikTok 46.4.3 的评论翻译控件与播放完成处理。
- 通过拦截 TikTok 的 MediaStore 写入桥，修复 TikTok 46.3.2 与 46.4.3 的视频、图片和 GIF 自定义保存位置。
- 移除 Android 系统目录选择器，改用内置的共享存储相对路径编辑框。
- 扩展 TikTok 46.4.3 的热点话题与推广浮层净化兼容。
- 已知问题：TikTok 46.4.3 的播放量与点赞数筛选仍不可靠，少数范围外视频可能漏过过滤。

### 0.4.18

- 重构 Material 3 设置页，按常规、信息流和下载分类组织功能。
- 新增页面净化，可选择隐藏作者信息、文案、音乐、互动按钮、搜索入口、Tako、翻译控件和导航栏。
- 新增屏蔽 AI 生成内容、热点话题条和内容评级提示。
- 新增 GPS、系统语言和系统时区伪装，均可独立开关并跟随目标地区。
- 新增跳过启动登录引导，仅关闭启动时可跳过的登录提示，不绕过登录或验证。
- 优化播放量与点赞量筛选，支持输入完整数字及 `K/M/B` 数量后缀。
- 移除失败的防烧屏功能；默认倍速的标准项统一显示为 `1.0x`。
- 优化下载说明，明确所有视频优先使用无水印地址。

### 0.4.17

- 支持官方 TikTok 46.3.2 与 46.3.3。
- 修复 TikTok 46.3.2 评论页翻译按钮无法执行翻译的问题。
- 适配 TikTok 46.3.2 的防烧屏状态提示 Toast 入口。

### 0.4.16

- 改进防烧屏清屏模式在视频与图集中的状态保持和恢复，增强页面切换后的稳定性。
- 明确适配官方 TikTok 46.3.3。
- 更新启动器图标，使用纯色背景。

### 0.4.15

- 明确仅支持官方 TikTok，移除第三方修改客户端专用兼容代码。
- 移除仅依赖第三方客户端桥接、在官方 TikTok 中无效的灰度模式和强制取消静音设置。
- 优化禁止循环播放：播放结束后进入 TikTok 原生暂停状态，并显示重播首帧。
- 修复播放结束后需要点击两次才能重新播放，以及切换视频后功能失效的问题。
- 不再强制同步播放进度条，减少对 TikTok 高频进度回调的依赖。

### 0.4.14

- 新增默认播放速度：1.0x、1.25x、1.5x、1.75x 和 2.0x。
- 每条新视频首次渲染后自动应用所选速度，不覆盖当前视频内手动选择的倍速。
- 适配官方 TikTok 46.3.3 播放器接口。

### 0.4.13

- 使用 libxposed API 102，并固定 TikTok 作用域。
- 重构 Material 3 设置界面、地区选择和媒体保存目录选择。
- 增加评论翻译状态保持及滚动列表同步。
- 增加双指长按防烧屏清屏模式。
- 修复禁止循环播放后需要点击两次才能重新播放的问题。
- 增加 Root 重启 TikTok 操作。
