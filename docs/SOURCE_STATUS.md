# Source and artifact status

## 通用无Root框架修复：源码、管理器与补丁器已构建（2026-11-08，兼容测试版）

本轮不再交付专用TikTok。修复已接入LSPatch正常构建任务：框架loader生成时隔离Kotlin依赖，管理器与CLI统一消费该产物；无需按TikTok版本写固定DEX偏移。完整实现和源码锁定保存在`integrations/lspatch-kotlin-isolation/`。

- LSPatch／Vector固定源码构建成功，203项Gradle任务完成；loader／metaloader／管理器／CLI由源码构建，四ABI原生库复用经核对的487／core7306二进制，没有伪称原生部分重编。8项构建工具回归通过，异常输入使打包停止，未隔离原始DEX不进入分发资产。
- 通用测试管理器`Toki-LSPatch-KotlinFix-test.1.apk`与通用补丁器`Toki-LSPatch-KotlinFix-test.1.jar`已产出；管理器签名／16KiB对齐、四ABI与两种交付相同loader校验通过。
- 新管理器使用独立包名`io.github.meiyongai.toki.lspatch`，实际与原管理器并存安装并启动；未卸载、迁移或清除原管理器数据，未自动授予Shizuku。它是debug签名测试版，不冒充上游正式包或原证书更新。
- 两个独立合成应用（旧Kotlin／无Kotlin）经同一通用引擎生成补丁，集成模式与管理器模式共4次Android13实机启动均进入测试模块。管理器模式仅在新测试管理器数据库为两个合成包添加作用域，使用run-as，没有su／原TikTok／旧框架作用域／网络修改。手机系统本身有Root，所以不冒充全新无Root华为验收。
- 干净API29软件模拟器已另行尝试两次，因本机无虚拟机加速未能完成启动；已请求正常关闭测试实例并保留AVD／日志，不把环境准备冒充验证通过。
- **报告华为API29及Toki实际字幕／评论功能仍未端到端验收。** 新测试管理器不会自动接管旧`org.lsposed.lspatch`绑定的宿主；现有用户的安全迁移和正式签名升级路径必须单独设计，不能让用户卸载／清数据。新用户可使用同一通用框架与独立Toki模块正常配置，不再交出私有TikTok逐包二次修改。

## 无 Root 交付方向纠正（2026-11-08）

**Toki面向多人分发。某个用户的已补丁TikTok再次改包，不是Toki的正式兼容方案，也不是完成标准。**

- 正式交付对象仍是独立Toki模块APK；其他用户不应被要求把自己的TikTok发给维护者逐包修改，也不应安装维护者二次打包的专用宿主APK。
- 本地`TikTok-47.4.16-LSPatch487-KotlinFix-cover-test.apk`降为隔离问题的诊断实验，不推荐安装或对外分发，不作为修复完成的依据；原文件和实验记录保留，未删除或撤回任何用户文件。
- 本轮新报告的明确阻断在Vector初始化、Toki入口之前。该层故障不能通过Toki加载后的Hook自救，必须由可复用的LSPatch／Vector加载流程修复，或经验证兼容的框架版本解决，不能伪装成仅更新Toki即可恢复。
- 可复用修复应在框架构建／加载器生成时隔离框架自带Kotlin依赖，供正常补丁流程统一使用；不再以某个TikTok的classes62.dex固定偏移补丁为交付。
- 多人分发的验收需包括：通用构建可复现、签名／升级路径明确、标准无Root安装流程、不同用户／宿主版本／Android版本独立端到端测试，以及Toki字幕／评论等功能实际验证。当前只完成过独立ART机制重放和Toki本地／CI构建；**通用无Root方案仍未完成**。
- 不自动安装框架，不要求卸载／清数据，不发布用户提供的TikTok或由其派生的完整宿主包；不因停止错误交付路线而删除旧证据。

## 47.4.16当前宿主到件：同证书覆盖候选已生成（2026-11-08，未安装）

用户随后提供了当前已补丁TikTok APK。实际配置为 **Manager模式(useManager=true)**，不是旧补丁报告最后一条Local记录；不能将旧记录当当前包的事实。包名com.zhiliaoapp.musically／47.4.16／2024704160，补丁内核487／Vector canary-3106，原APK签名与内置补丁证书一致。

- 新候选不更改管理器模式、不内嵌另一份Toki、不重置作用域。只把现有classes62.dex的启动资源分支改为读取包内隔离loader及原487的四ABI原生库；实际框架仍读取原封不动的useManager=true配置，继续使用RemoteApplicationService和现有管理器模块设置。
- 同包名／同版本／同证书、Android官方apksigner v2签名和zipalign 16KiB对齐通过；原应用Manifest／resources／补丁JSON不变。26628个原条目元数据和偏移保持不变，源包前482402304字节以及内嵌origin.apk原始字节的SHA256一致。
- 原包使用ZIP交叠存储；通用签名工具曾将其重排并破坏原生库对齐，该中间包未交付，保留到work并改为.bin。最终候选保留原布局并用同一公开补丁证书签名，再由独立Android apksigner和zipalign校验，不隐瞒初次失败。
- 原origin.apk条目CRC元数据已不一致，但源APK v2签名验证有效；本次完整保留，不宣称所有条目CRC通过，也未为通过校验擅自重写源内容。
- 本地交付`TikTok-47.4.16-LSPatch487-KotlinFix-cover-test.apk`，487160025字节，SHA256 `5f3a77a78c1918f8962b41115d91007f9a06e528f4bae4fac8934d19a57123dd`。用户APK／派生完整宿主包／日志／数据库均不上传GitHub，不发布为官方框架或正式Toki版本。
- **未安装到任何手机，报告华为API29端到端仍未验收。** 不卸载、不清数据；可先按同证书覆盖测试包在报告手机正常覆盖，若系统要求卸载或报告签名／版本冲突必须停止。旧487的重新补丁／更新loader可能写回原有失败加载器，测试时先不要执行。

## 2.1.20-debug /140 — 字幕／评论修复已构建；无Root加载器候选，真机未验收

- 字幕：给已核验的当前原生renderer增加pre-draw文本观察，捕获渲染回调返回后才发生的TextView写入；单一控制器／选中视频／播放pass／字幕轨屏障保留，不引入两秒过期。新增回归覆盖无第二次回调的换句、清空、暂停8秒、非当前兄弟renderer及释放监听。该路径已在桌面回归通过，原截图卡句的完整真机复现尚未闭环，不宣称所有字幕卡住已修。
- 评论窗口：恢复系统外侧点击取消，仅关闭窗口并释放阅读守卫／订阅，不提交网络任务或清缓存。合成触摸测试首次用了错误的ACTION_DOWN，导致测试未关闭；改为系统实际outside事件后4项Reader回归通过，初次失败证据保留，不隐去失败。
- 速度：139现场120次／108.507秒采样，翻译平均3.93、峰值4个不同视频在途，但新增译文0，失败项增加；原文和媒体都有超时。另观察到队列查询约0.6～1.1秒的锁等待。140新增每视频任务索引／running部分索引，先按公平票号选视频再取一项，避免全积压排序及逐任务重复扫描。仍保留4个不同视频翻译、原文1／媒体1和公平顺序，不承诺网络受阻时吞吐提升。
- 后台评论翻译不再在6秒时提前废弃有效慢响应，独立有界预算20秒；手动界面／实时字幕原6秒策略不变。保留宿主Throwable与数字网络状态，不再把所有错误压成IllegalStateException。不是延长等待就算提速，后续仍需实际新增原文／译文采样。
- 无Root：新报告明确4次Vector初始化Kotlin类冲突，发生在Toki入口前。5项只读报告分类测试和JVM最小机制复现通过；未构建或安装框架修复包。详细边界见docs/NO_ROOT_DIAGNOSTIC_ZH.md，不能用140模块包宣称华为无Root恢复。
- 140本地全量1365项0失败／3项原生MP4环境跳过，云端Actions 42384132741（dc23f4c）1365项0失败／4项环境跳过；本地／云端lint均无未基线错误、146警告，既有389条翻译基线积压未修复。922项源码检查通过，598份生产／238份测试输入哈希无漂移。
- 统一APK `Toki-2.1.20-debug.apk`，89000359字节，SHA256 `e1bca932cf63ded0275b4c33b4edeb070cbf9a7ed9ab3f05d830197408b2614f`。外层／内嵌版本、CRC、签名／同证书及与139签名延续、16KiB对齐／关键类／提供者／权限校验通过。安装包未自动公开发布；手机实际加载证据仍为139，新包未安装／重启／真机验收，未清数据或切网。
- 用户随后提供准确的487管理器APK。已从其真实loader制作Kotlin命名空间隔离候选，8项生成器回归通过；Android13独立ART加载真实Vector类，原loader复现报告相同checkNotNull(Object)缺失，隔离版本成功初始化，宿主未改写。候选不是可安装管理器，也不是华为API29通过；仍需当前已打补丁TikTok APK核对签名和Local／Manager模式，不能要求卸载或清数据。

## 2.1.19-debug /139 — 统一缺省额度并保留旧迁移失败（2026-11-07，本地／CI已验证，真机未验收）

- 未配置时主评论／总回复／每串回复统一为200／1000／15；2500仍是可配置上限，不再兼作默认值。全局数量和回复弹窗有恢复默认入口；单视频仍恢复继承，不擅自覆盖显式333或旧的2500／50设置。
- 迁移只新增来源关联表和版本5元数据，不删除、重建或清空旧表。面板与后台统计按同一来源读取失败，重复复制／导入不重复计数；旧检查点快路径、SQLite分区转本机scope、重启和重新统计不能再抹掉坏页提示。修复缺失页后才清除对应错误，不把历史数据丢失伪装ready。
- 修复前11项新增回归有10项预期断言失败、1项通过，已单独封存。首轮Windows SQLite临时路径错误先修正夹具，不把环境错误冒充产品复现。919项源码检查与修复后49项定向回归全部通过（0失败／0跳过，含全部11项新增回归和私有只读迁移fixture）。本地全量1360项0失败／3项Android原生MP4环境跳过；云端Actions 42376745007（源码0dd1dba）1360项0失败／4环境跳过（额外缺私有fixture）。本地／云端lint均无未基线错误，警告146／146；既有389条MissingTranslation基线积压仍保留，未声称修复。
- 已新增缺省／显式设置往返／350条存量缩小范围并重启／Robolectric数量与回复弹窗恢复默认，以及缺页／两个旧分区／旧检查点／跨scope复制／版本4升级／真实补齐缺页共11项回归。以上回归修复后已通过；桌面Robolectric弹窗／SQLite测试不替代手机验收。
- 统一APK `Toki-2.1.19-debug.apk`，88982207字节，SHA256 `7accf0c88ce22c659145b70bc665e98a77660523865093d1a4f313c15405289b`。外层／内嵌载荷版本、CRC、签名及同证书、16KiB对齐、关键类／入口／提供者／网络状态权限核验通过。598份生产输入、238份测试输入及1688份私有只读fixture文件哈希无漂移。
- 本轮源码已同步GitHub；以上是构建和本地／CI证据，不是139已安装、已由手机加载或已公开发布。公开测试预发布仍为137，历史正式版未改动；真机及报告设备不足继续保留，不以测试通过宣布完整目标完成。
- 当前没有ADB设备；本轮不能代替报告华为无Root端到端、完整字幕seek／loop／换视频、实际断网阅读、指定视频文字与媒体、媒体不闪及长列表滚动真机验收。没有自动发布新包、改动手机设置或清理任何用户缓存。

## 2026-11-07 补完2.1.18 /138本地终态（未公开发布）

- 云端Actions 42373140221成功：1349项、0失败、4环境跳过；本地封存1349项、0失败、3项Android原生MP4环境跳过（提供了私有迁移fixture）。本地lint无未基线错误、146警告；既有389项MissingTranslation积压仍未修复。
- 598份生产输入无漂移；外层及内嵌包版本／CRC／签名／同证书／16KiB对齐／关键类／提供者／网络状态权限均已验证。APK为`Toki-2.1.18-debug.apk`，88980595字节，SHA256 `bc27744a6d566682d18304e3fb5a88a9f8d88085a479f83d1d85dab11905aa43`。
- 这是本地测试包与CI通过，不是公开预发布，也不是138已在手机加载。公开测试预发布仍为137，实际设备、报告华为无Root及完整字幕场景继续待验收。

## 2.1.18-debug /138 — 按当前配额判定完成、保留历史失败（2026-11-07，验证中）

- 新增两项先失败的复现：总回复数已达设置上限，或每串已达上限时，某条旧回复流的incomplete仍让视频被判部分完成。修复只让当前仍需抓取的回复流参与完成判定，旧游标/失败记录保留；扩大配额后旧缺口重新成为有效不足，不清缓存、不伪造评论。
- 后台汇总和评论面板使用相同的当前范围选择。主评论不足、正文截断或媒体描述未核验仍不能ready，不把译文/图片已处理完当原文完整。
- 抓取流增量保存具体终止原因；面板展示主评论实际数/设置数、连续三页无新增、游标未前进、回复接口提前结束等可读说明，旧失败原因未知则如实说明，不显示含糊英文调试码。
- 正在执行本轮全量测试、lint和统一包构建。137源码/CI已通过并已发布真实测试安装包，138尚不冒充构建或真机通过；目前ADB无设备，报告华为无Root和完整字幕场景仍待实际验证。


## 2026-11-07 最终构建核验：2.1.17-debug /137（测试预发布）

GitHub Actions 42369049353已成功，源码提交272c6b1。云端1344项0失败、4跳过；本地1344项0失败、3跳过（额外提供私有迁移fixture）。lint无未基线错误；既有389条英文回退积压和145/146条警告不伪装已修复。914项源码检查、598份生产输入无漂移及外层/内嵌包版本/CRC/签名/同证书/16KiB对齐/提供者/网络权限核验通过。

APK `Toki-2.1.17-debug.apk`，88978047字节；SHA256 `2015debc9fac81b4000b7990115e46b5faa38c2b45313dcdb1d5032c6ed8dc3e`。当前无ADB设备，137真机和报告华为无Root端到端未验证；不能因CI绿灯宣布完整目标完成。详细连续记录见CHANGELOG.md。


## 2.1.17-debug /137 — CI运行时安全修复与可审计的翻译积压（2026-11-07，验证中）

- GitHub全量1343项已无失败，4项跳过分别为私有评论迁移fixture和3项需Android原生MP4解码测试；同版本地提供已有只读fixture后1343项无失败、3项原生MP4测试跳过。不是全量真机通过。
- 后续lint检出缺少ACCESS_NETWORK_STATE声明、API28不支持的Handler.hasCallbacks调用，以及窗口对比度/API30方法未在访问点显式保护。137补 normal网络状态权限、用主线程标志保持500ms合并刷新、为各窗口API调用补版本保护并增加API28窗口恢复回归。不关闭MissingPermission/NewApi检查。
- CapCut精确宿主资源ID属于宿主APK而非模块R，仅对已核验方法局部说明ResourceType例外，保留节点归属检查，不扩展隐藏容器。
- lint另有389条既有扩展文案在其他语言回退英文的积压。只把这389个具体MissingTranslation记录纳入可审计baseline，不把英文复制到其他语种冒充译文、不关闭后续新文案的MissingTranslation，也不基线化权限/API/资源正确性错误。
- 137完整测试、lint、统一包正在执行；136已校验APK仍保留GitHub草稿，未标成可下载稳定版。当前ADB设备列表为空，后续真机验收受此限制。报告华为无Root环境仍未端到端复测。


## 2026-11-07 GitHub同步补记：2.1.16-debug /136（正确版本已构建校验）

此前GitHub停在2.1.1，本次补齐2.1.2～2.1.16的连续变更和失败记录，详见CHANGELOG.md。中间包127～135误标2.2.0～2.2.8，本应为2.1.7～2.1.15；136为2.1.16。遵守满100进1，旧实际包名保留作对照，不重命名伪装重新构建。

- 无Root：报告宿主47.4.16/API29出现失败功能重复安装导致宿主崩溃、真实管理器回收/断连和提供者解析问题；已修复重复安装崩溃及旧连接死亡覆盖新连接。195项定向回归全部通过（含API29），0失败/0跳过；正确APK已重新构建，外层/内嵌版本、CRC、签名/证书、16KiB对齐及提供者验证通过；597份生产输入无漂移。报告华为不在当前ADB连接中，无Root端到端未验收，不标“已恢复”。
- 翻译：最多4个不同视频并发，同视频最多1项；真机134观察到四项持久化租约，135进一步改为单条读取/增量提交。原文与媒体各单请求，公平轮转不变。
- 数量：真实样本最终主评论333/333、回复1000/1000、每串15、译文1286/1286、媒体114/114；其他视频覆盖不足仍partial，未宣称所有视频完成。
- 评论/字幕：阅读窗口、真实图片/贴图、模式和回复切换已在连接的47.6.3手机实测；字幕有字暂停及单/双行共同底部锚点有采样证据。报告设备及全部场景仍需分别验收。
- 原APK、视频、评论、字幕及隔离缓存未删除；用户数据/截图/完整设备报告仅保留忽略work，未上传。

下面保留早期记录；旧标题中的版本名为当时误标，最新以本段及CHANGELOG版本对照表为准。


## 2.2.2-debug /129 — 取消本地评论登录绑定，按真机关闭原因修复（2026-11-07）

128真机诊断确认：评论面板四次均在注册成功后约1秒，以accountChanged=true关闭。根因是界面自己的HostOfflineComments实例从未初始化，旧accountMatches把未初始化直接判为账号变化，而后台使用另一个实例；前两轮只测假账号校验未覆盖此问题。

- 用户明确要求本地评论不绑定账号。改为宿主私有目录下稳定本机缓存标识，本地阅读/操作不因登录变化关闭；旧v3分区和旧页逐视频增量迁移，保留所有原始记录/译文/媒体/数量设置，媒体路径引用旧文件，不重新下载。
- 原生离线Keva读取仍用宿主实际存储路径取得本地事实，返回转换为本机标识；联网仍由既有授权、任务租约和宿主原生接口控制，未开放任意网络请求。
- 字幕渲染输入不再因原字幕容器GONE或尺寸为0就丢弃明确EXPANDED帧；只接受已核验renderer，显示层仍要求选中视频页面。补齐安全输入原因诊断。字幕模型暂时缺URL时不再清掉已持久保存的有效轨道。
- 用户本轮授权代理自行覆盖安装/重载并实际操作已连接手机，不要求用户回电脑。未经删除确认仍不卸载、不清缓存、不删除原文件。


## 2.2.1-debug /128 — 评论面板自动退出与字幕闪隐回归修复（2026-11-07）

2.2.0 真机暴露新回归：点击评论后空白并快速关闭，字幕异常。通过现有签名诊断与只读日志确认手机实际运行127且storage=sqlite-v3；不是未启用新版。没有安装、重启、切网络、清缓存或主动触发下载。

- 新评论面板固定阅读打开时的视频，不再以瞬时播放器绑定作为存活条件；同视频重绑/宿主holder退役不自动关闭阅读器，账号变化、Activity退出和明确关闭仍立即释放。补入延迟初始化/绑定失效/账号切换的UI回归。
- 读注册走独立本地线程，仅核对当前视频迁移，不排在全量回填后。只有SQLite该视频为空时才尝试读取宿主已有本地缓存；仍不联网。
- 真机日志出现仓库长锁竞争。移除每次通道结束遍历全部视频、解析全部评论的进度统计，改用事务维护的轻量计数；迁移锁按视频释放。无可执行任务时不再每1.5秒永久轮询，改为任务截止时间和网络回调唤醒。配额/票号/旧缓存保留。
- 字幕原生输入按renderer保存，由最近可见输入持有显示权；另一个隐藏/空renderer不能清空当前句。实际renderer已送帧时跳过重复VM原始帧，避免几毫秒内先显示再隐藏或改写所选轨道文本。当前时间轴不可用/身份不匹配时回到当前原生输入，可靠时间轴空档仍为空句，循环/轨道变更清理旧输入。
- 新核心开关与进程启动冻结策略保持不变；不通过回退旧版冒充新链路修好。新版设备播放、真实评论打开仍待用户加载128后验收。

2.2.1-debug /128 已构建：`Toki-2.2.1-debug.apk`，88954479字节，SHA-256 `7e2194c26383d7daf18d3cfab88f8e97c0d047f623596f50bea8269135e537a1`。最终同一源码的107项定向回归全部通过，0失败、0跳过；899项源码检查、594份构建输入无漂移及git diff --check通过。统一包和内嵌载荷的版本、CRC、签名、同证书、16KiB对齐、关键类和命名空间校验通过。不是手机上的新版验收。


## 2.2.0-debug /127 — 评论事实仓库与字幕单显示层重构（2026-11-07）

这是替换旧双写链路的新测试版，不是对 2.1.6 真机问题的完成承诺。新核心默认关闭：在离线评论缓存设置开启“评论与字幕重构核心（测试）”后，由用户在合适窗口手动重启 TikTok；本进程不会热切换或同时启动两套引擎。

- SQLite v3 分离评论/原文历史、按源摘要和目标语言保存的译文、媒体、主评/回复流、条目任务、租约、轮转票号及迁移检查点。新后台和新面板共用仓库；旧任务实现保留为互斥恢复入口。
- 每通道单请求、五视频一批、持久化严格轮流；同视频主评/回复交替、回复串轮转。条目失败独立退避，不堵住后续正文/媒体。单条翻译授权不放开整个视频联网，暂停为单视频 epoch 屏障。
- 旧页只读导入，保留超配额历史内容、旧文件和有效媒体；已核验页面接续游标，错误页登记，不清缓存、不复用错误原文/语言的译文。
- 原生风格底部面板 72%～90%，默认两条回复预览，连续本地滚动；译文/媒体实际完成数与队列状态集中展示。面板只观察状态和提交命令，切模式/展开/预览/导出均不暗中联网。
- 字幕原生帧与播放时间轴进入同一控制器和唯一页面内显示层；有效时间轴空档是明确空句，不由旧帧抢占；正常长句不因两秒超时失效。保留窗口内容坐标、拖动设置和宿主层级；关闭恢复已核验字幕根，不隐藏播放器。
- 只读手机基线：00:17（日本时间）仍实际运行 2.1.6 /126。截图视频的特定评论仍缺译文；这不能算新版验收。没有安装/重启/改开关/切网络或清缓存。

103 项定向用例分批累计通过（0 失败、0 跳过），包含完整 60 视频设备快照迁移对账、新面板真实布局/反馈、轮转/租约、字幕单层/时间轴/坐标及媒体绑定回归；不是全量测试或新版真机验收。894 项源码检查与 git diff --check 通过。

统一测试包已生成：`Toki-2.2.0-debug.apk`，88930947字节，SHA-256 `5d8a79f2bacc3c9730b2de28a9be0588347f161277c2e077ff105b07322dfefd`。外层及内嵌载荷版本、签名、CRC、16KiB对齐、关键类与适配规则一致性通过，594份生产输入无漂移。

构建和测试证据以本轮 `work/rebuild-core-20261106/` 日志与 `docs/COMMENT_CORE_REBUILD_ZH.md` 为准；不得把旧版测试或全局缓存增长当成本版真机成功。


## 2.1.6-debug /126 — 真机缓存核对、回复字段纠正与字幕统一窗口坐标（2026-11-06）

用户授权ADB直接读取手机后，确认2.1.5仍有缓存问题，并反馈字幕还跳动、要求调整离线评论逻辑。本轮不是把本地通过当作手机恢复：先只读复制模块页／索引、现有任务／配置与受控诊断，再对照原宿主实现修复。

- 已核对60个当前视频档案：1310条主评论、106条内嵌回复，独立回复页为0；所有主评论保存的replyCount均为0。当前打开的视频为27主评论＋1回复，平台返回约1910，7个游标页大量重叠，窗口显示数量确与本地档案一致。56个不完整任务却同时被ready=60统计为就绪，不能称完整。
- 明确代码缺陷：宿主正常评论列表读取`getReplyCommentTotal()`对应reply_comment_total，旧模块只读取`getReplyCount()`对应reply_count。改为真实总数、旧值和内嵌条数的非负最大值，驱动回复任务；不再把没有触发回复请求都归因服务器。
- 旧任务升级schema7并重新核对元数据；每个响应页记已核验回复总数版本，旧页即使媒体字段已知也不能跳过修复。保留已下载视频、已有评论、译文、图片和贴图，沿原生回复游标、独立总／单条配额接续，不伪造回复；是否真实取得回复仍需加载新版后的联网验收。
- 完整ready只统计complete，不完整但译文／媒体准备完毕另列；partial与真实定时重试数分开，不再出现56个不完整任务被误读成全量就绪。
- 字幕真机日志中同句在2293和1879像素高的挂载层交接，位置基准跟着变。改为两者共用Activity窗口内容坐标，字号测量／偏移／拖动使用同基准，挂载仍是视频页overlay，当前视频和离屏保护不放宽；保留贴图稳定绑定等此前改动。

63项定向用例全部通过、884项源码检查、592份构建输入前后一致，统一包和隔离载荷版本／签名／CRC／对齐／命名空间／关键类检查通过。包含真实回复总数非0而旧reply_count=0、旧页重新核验、正确回复任务选择、原文译文保留，以及2293／1879两种高度和偏移交接的屏幕坐标稳定测试。不是新版手机验收。

APK：`Toki-2.1.6-debug.apk`，88592011字节，SHA-256 `9e49b38876a91684c91e2f2c164c25194afb3170c93b804f34371c1f47a9fd9e`。本轮没有安装、重启、修改手机设置、主动触发网络评论／翻译请求、清缓存或日志；设备数据仅留本地。详见[本轮真机调查与修复边界](DEVICE_CACHE_REPLY_FIELD_AND_SUBTITLE_ZH.md)。

## 2.1.5-debug /125 — 评论计数同步与贴图稳定渲染（2026-11-06）

用户实测2.1.4数量仍不一致、贴图渲染闪烁。本轮只读确认手机实际主评论默认200、回复总量1000、每主评论15条回复；另有500／2500等单视频覆盖，不改回50或擅自统一设置。

- 修复同一评论行每次刷新都移除图片View、重新解码并重启动图的问题。以账号／视频／评论／源文本／媒体ID核对复用tile和Drawable；只更新当前回调，不清图、不重复排同一解码。离开／回收停动画，新媒体文件提交后才重试缺失占位，预览保存仍不暗中联网。
- 档案读取先去重内嵌回复和重叠回复页，再计算回复总量／每条上限，避免重复ID提前占满名额。实际存档条数与当前按配额展示数分开，平台返回数量不当作已下载条数；保留独立主评论和回复配额。
- 打开中的评论区订阅已提交档案变动，后台新增评论／译文后在本地worker重新读缓存并保留滚动位置及展开状态，不需要关窗重开、不触发网络。
- 真机样本确认部分服务器返回hasMore=false时仍低于平台reported total，另有不同cursor反复返回同ID。新增连续三页无新增ID的有界退出，保存不完整状态并让剩余回复继续处理；不通过重复请求／伪造条数凑满上限。源覆盖不足的任务即使译文／媒体完成，也保持partial，不冒称全量完成。

59项定向用例累计通过、881项源码检查通过、592份生产输入一致；统一包和隔离载荷版本／证书／CRC／对齐／命名空间／关键类核对通过。含真实两帧GIF解码后反复重绑保持同Drawable、重叠回复去重、盘上／展示计数、档案变动通知、重复页有界退出及partial状态。不是全量回归或新版手机验收。

APK：`Toki-2.1.5-debug.apk`，88591219字节；SHA-256 `41f4cec96619b80e6e553c7c59fd74de8fac4f01e6894720ceac7dcbe519cb4c`。没有安装、重启、改手机开关、主动触发下载／翻译请求、清日志或删缓存；旧APK保留。服务器不可获取条目仍无法保证补满设置上限。详情：[本轮修复与边界](COMMENT_COUNTS_AND_MEDIA_STABILITY_ZH.md)。

## 2.1.4-debug /124 — 连续离线评论、独立回复配额、层级与按钮反馈（2026-11-06）

本轮按用户实测同时修复：评论仅翻译可见项，离线分页／20条、回复缺失及无法分辨、新联网追加缺图片贴图、翻译选项缺失、默认上限不一致和按钮无反馈。用户后来明确回复不应占主评论名额。

- 本地评论为同一列表连续下滑，不再需要点击更多／下一页。HTTP仍按宿主服务实际游标有界请求，20只是单次请求数。仅打开、下滑本地缓存和预览保存不会暗中联网；明确点“联网补齐并连续加载”后才接续主评论与回复，支持停止。
- 主评论上限沿用已有设置／单视频覆盖值，回复改为独立总上限默认1000、每条主评论回复上限默认50，均可0～2500配置（0停止准备回复），主评论最多2500。内存总展示最多5000；持久单页编码仍500条。配置、后台源／译文／媒体、重开及在线接续统一按同一配额，不删除超出新上限的既有缓存。
- 修复显式联网回复页的parent/depth在保存和旧档案重开时的归属；保留主／回复独立游标，增加“查看／收起N条已缓存回复”，回复以小头像、缩进、左侧线与单独“回复@…”标识。列表追加不重置展开状态和阅读位置。
- 新联网内容显式准备图片／贴图文件，缺文件展示带文字占位而非无提示空白；预览／导出仍只读缓存。相同源文本刷新不会用缺字段响应覆盖已有媒体或译文。媒体／翻译与源数据分离，失败保留原内容。
- 每行原文／译文／双语始终提供；缺译文时有明显翻译动作。新原生评论翻译队列按实际返回整批评论与内嵌回复入队，独立于可见控件，不声称已抓取服务器尚未返回的评论；使用原生翻译缓存应用结果，按账号／视频／原文／语言隔离并可取消。
- “操作”入口明确可见，显示已读／已存数量、主评论默认和单视频覆盖、回复独立上限。按钮显示正在执行、成功／失败原因、停止结果；防重复提交，读本地、展开回复、模式切换和调整上限均有反馈。

82项定向测试累计通过，878项源码检查通过，592份生产输入构建前后一致。统一包／隔离载荷版本、证书、CRC、对齐、命名空间和关键新类检查通过。实际Android控件截图为本地合成数据，不是手机验收。本轮仅只读检查手机2.1.3与现有配置，未安装／重启／修改手机开关、主动触发联网、清日志／缓存或重下视频。手机是否全部恢复仍待验收，不能以单元测试替代。2.1.3字幕与CapCut修复保留，HDR仍停止。

APK：`Toki-2.1.4-debug.apk`，88544379字节；SHA-256 `896ac96b42f7790fb0cba3917e066d1534111d98c15b74840f485f763034ab6c`。详情：[本轮修复与验收边界](OFFLINE_COMMENT_STREAM_AND_REPLIES_ZH.md)。

## 2.1.3-debug /123 — 字幕卡住、原生与离线评论、CapCut入口补充修复（2026-11-06）

真机只读核对了2.1.2/122与TikTok47.6.3：跳动有所减轻但字幕仍卡住，原生及离线评论问题同步存在；新增反馈CapCut“智能字幕，双语显示”入口未隐藏。以下为源码与本地验证完成，**新版尚未安装／重启／联网验收**。

- 字幕：支持宿主私有`realtime_captions_cache`内有界WEBVTT读取并在播放中刷新增长文件，不再把本地绝对路径交给HTTPS读取器；禁止越界、符号链接根、超大文件和失效读取。重复送达的相同原生文字不再无限续期，允许当前时间轴接管；新句、隐藏、播放轮次和旧回调边界保留。2.1.2页面锚点修复保留。缺失的本地文件与网络轨超时仍可能影响结果，不能宣称所有卡住原因已排除。
- 原生评论：在精确DEX核验后补充`need_translation`请求策略，绕过与用户翻译开关冲突的原生偏好／播放量门槛和语言排除；关闭开关／禁止联网时保留原宿主结果。旧门禁和投票翻译不移除。
- 离线评论：只读抽样发现两个模型评论数98／214，对应持久页却只有20条、hasMore=false、无回复媒体。旧请求场景为离线首批source33，本版改普通source0，继续按20条分页与真实游标读取。schema4将旧任务重新置为待核对、重取第一页并合并现有页／译文；不删除既有数据、不重下视频。回复、翻译、媒体独立队列和200条当前配置／最高2500条共享预算保留。实际能否补齐仍取决于原生服务响应，需联网验收。
- 缓存译文：较旧辅助偏好不再覆盖当前目标语言下已有的档案译文；手动翻译仍以确切源文本、账号、视频和目标语言绑定。不把历史失败记录显示为全部完成任务的当前失败。
- CapCut：真机开关已开、代码核验成功但旧观察器NO_TARGET。补充类型54／110、专用CapCut区域子控件、标准化渲染器唯一componentKey对应锚点，以及只含一个已核验业务锚点的旧渲染协调器。不能凭类名／文本隐藏整块共享多锚点区，图片轮播根和播放器不处理；关闭后原生可恢复。

90项定向测试通过、867项源码检查、590份生产输入一致性核对通过；统一包和隔离载荷均通过版本、签名、CRC、zipalign、命名空间和新增类检查。没有运行全量回归、安装、重启、开关修改、清数据／日志或主动触发手机网络请求。HDR继续停止。证据只留本地忽略目录，不上传设备私有文件。

APK：`Toki-2.1.3-debug.apk`，88339731字节；SHA-256 `5db62b002e6f04f18975c6f2eb3fb00c61ea7ff26965912208cdb0a06579d885`。详情见[本轮调查与修复边界](PLAYBACK_COMMENT_TEMPLATE_FIX_ZH.md)。

## 2.1.2-debug /122 — Real cached-comment GUI and shared subtitle page anchor (2026-11-06)

The cached-comment viewer now uses a dedicated bounded sheet instead of the framework alert title and three-button bar. Its avatar/content columns, compact mode tabs, translated-first bilingual styling and indented replies implement the approved preview using real cached fields only. Cache details and explicit network actions remain available under disclosure. Empty media/reply placeholders do not consume space. Missing cached media never starts a read-time download.

Composed native-caption and timeline bindings now share one page-relative bottom anchor, eliminating the source-bottom versus page-anchor discrepancy during host relayout and handover. Existing x/y offsets, drag/locks and stale video/pass/track guards remain. Pure host-native monolingual drawing is not forcibly replaced. Device-wide flicker/text switching is not claimed solved.

34 focused cases passed across a 33-case successful subset and a corrected reply-fixture rerun, with unchanged production inputs. 857 source checks and 590 frozen production inputs verified; unified and private payload version, certificate, CRC, alignment, namespaces and new UI classes checked. No device install, restart, settings change, data deletion or video re-download occurred. Offline comment count/media/reply/network failures remain unresolved; HDR remains stopped.

Artifact: Toki-2.1.2-debug.apk, 88314303 bytes; SHA-256 4a435f4e871d5f58f8d0fd481baf796c0957db6fac33b402918f94d80c0c3f90. Private evidence: work/comment-style-stability-20261106/.

## 2.1.1-debug /121 — Unified UI and bounded diagnostic storage (2026-11-06)

Standalone and injected settings now share category navigation, stable light/dark colors, typography and shapes; all 58 original dashboard configuration-write expressions were retained. Real activation and host status precede About content in the manager. Only explicit module-owned framework dialogs are themed. Comment row actions are compact; counts distinguish local rows from platform totals.

Root logcat previously bypassed the archive-only byte cap by redirecting stdout directly to a persistent file. New captures own bounded pipes (8MiB /10min), reserve against64MiB aggregate diagnostic storage, and stop on caps or failures. Exports construct fresh snapshots in memory directly in the chosen ZIP; cancelling the file picker follows capture stop. Old files remain untouched. Manual cleanup shows an immutable file plan and rejects escaped/symlink paths; destructive cleanup was not executed in tests. Legacy device processes and actual disk reclamation are unverified.

32 focused tests, 852 source assertions, 589 frozen production inputs and unified/private APK signature/CRC/alignment/namespace/classes verified. No full regression, device install/restart/switch change, actual su command or file deletion was performed. Local Compose screenshots are desktop Android renders. Offline comments restricted to20 with missing replies/media and sustained traffic remain unresolved; HDR remains stopped. Artifact Toki-2.1.1-debug.apk: 88292339 bytes; SHA-256 071646202139e4ae44bee4c787ed89b5067709641b9a766df462236ea5f3ebdb. Private evidence work/unified-ui-20261106/.

## 2.1.0-debug /120 — 2026-11-06

User accepted 2.0.99 offline comments and 2.0.98 cleanup/LIVE gestures. Rich offline comments now add global translated/original/bilingual presentation, account/video-bound original media bytes and cache-only export, local replies and a shared configurable 2500-row budget. Three independent data lanes remain subordinate to existing opt-in background permissions. Old serialized pages remain readable; lowering limits preserves stored data.

Native hidden/empty caption callbacks release their suppression lease immediately, and each current native overlay frame renews ownership before timeline arbitration. Native text remains authoritative even when custom translation is disabled. Cached progress-driven timelines are also allowed under native typography, without enabling custom translation transport. An indexed interval lookup retains a longer active movie cue after a shorter overlapping cue ends. Existing page/pass/stale/pause guards remain. Actual native speed ownership now prevents the offline fallback menu from competing for the same hold.

Evidence: the first 155-test run exposed two caption handoff failures; affected caption suites passed after the ownership fix, with passing unaffected suites retained. Final aggregate: 155 focused tests passed, 0 skipped, 826 source checks, 576 frozen production hashes. Unified/private payload version/signature/CRC/alignment/namespace/classes verified. APK 88023642 bytes, SHA-256 0ab0147c15c615417021b4946f15804480dbf320b6647526bdb756d8000ea4b3. The media storage fixture injects image validation for synthetic bytes; native extraction fixtures prove method shapes, not real server coverage. No new full regression, instrumentation, live API/download, phone install/restart/switch, or destructive clear-all test. No device is connected; rich comments, media export, speed arbitration and the reported offline movie subtitle gap across ordinary and offline playback (most obvious in movies) remain device acceptance pending. Private evidence stays under ignored work/offline-comments-rich-20261106/.

User accepted 2.0.98 cleanup controls and LIVE swipe restriction. Offline comments are now explicitly resumed. Repeated redownloads still yielded only the first few videos with comments; preparation must continue after returning to the home feed and must not depend on re-encountering the same videos. Work in five-video batches; use subtitle translation patterns; expose progress only through an explicitly opened settings dialog.

Source fixes: retain completion identities across code/config readiness; split ingestion, source/reply requests and translation into separate workers; persist source progress without waiting for whole-page translation; retain per-row exact-original translation checkpoints through the existing subtitle transport; merge durable lane fields with epoch/source-revision guards; preserve wakeups and capped retry/backoff after failures. Reuse existing companion storage and opted-in backfill. Do not evict or redownload media. Local reads restore persisted translations and preserve native/saved fallbacks when an archive cannot be read, without overwriting valid pages after a corrupt read.

The retained target DEX identifies the old recurring exception as a native IOException wrapper with HTTP/engine codes. New diagnostics include only stage, cause types and numeric codes, never messages, request logs, URLs or body data. No connected device was available, so current codes and the specific historical transport failure remain unknown. The native request scene and scoped ordinary-comment button are retained; no speculative API replacement or global click hook.

Status is read-only via the existing signature-protected diagnostic receiver, or the local in-process summary. Only opening/refreshing the settings dialog queries it; no playback UI, popups, notifications, host launch or requests are triggered by viewing status. Counts distinguish first-page originals, complete source/replies and complete translations.

Validation: 82 focused tests passed, 0 failures/skips; 799 source checks; 564 production inputs matched. This includes a simulated 60-video persistence/reopen fixture, not a live batch download. Unified/private payload version/signature/CRC/alignment/namespace/new classes, previous font manifests and geographical assets passed. No full regression or instrumentation rerun. No installation, restart, phone switch changes, translation/media requests on a device, redownloads, or destructive clear-all testing.

Artifact: Toki-2.0.99-debug.apk; 87842386 bytes; SHA-256 849f32d4a97ed147fe335cb704b72e839bfd0bcc2795b8cfc2cdc35b8bf87bd0. Native/build evidence remains private under ignored work/offline-comments-batches-20261106/. Actual whole-batch offline originals/translations/replies, ordinary button routing and process-restart behavior remain device acceptance pending.

# Verified build — 2.0.98-debug /118: independent cleanup controls and actual LIVE pager lock (2026-11-06)

User scope is the remaining cleanup settings and LIVE vertical swipe restriction. Add 23 default-off controls: 16 individual components formerly grouped under aggregates, 5 inspected anchor categories and 2 dedicated LIVE gift trays. Preserve existing aggregate scope. Shared roots revalidate category as well as anchor/video/root identity. Unknown types and shared parent containers stay native. Exact inventory: CLEANUP_COMPONENTS_ZH.md.

The resolved LIVE symbol was a listener. Follow its inspected listener -> fragment -> pager fields to bind actual interception, touch and owned listener together. Cancel native drag/velocity state once; latch vertical ownership through UP/CANCEL; track each pointer ID because native interception and touch may adopt different fingers. Drain an already blocked sequence when disabled and restore native handling on the next gesture. Clicks, horizontal gestures, child scrolling and programmatic changes retain native handling.

Target DEX inspection verified both ownership fields, declared pager methods and new gift widgets' inherited lifecycle/content-root contracts. The gift widgets use onLoad(Object[]) / onUnload(), not the earlier follow-guide RecycleSource overload. Native lifecycle proceeds; only inspected content roots receive reversible visibility ownership after code fingerprint success.

Verification: 58 focused tests passed, 0 failures/skips; 790 source checks; 557 production input hashes unchanged across build. Outer/private payload version, signature, CRC, alignment, namespace, new-class, font manifest and geographical asset checks passed. No full regression rerun or instrumentation. No phone operations, installation, restart, settings changes, translation calls, downloads or destructive cleanup. Actual page coverage and LIVE gestures remain device acceptance pending; other independent popups and host variants are not certified.

Artifact: Toki-2.0.98-debug.apk; 87726574 bytes; SHA-256 287c1765624c7a92adda36fa3838f1e26ff997931fe56667e5ac9f7110e6fbab. Native/build evidence remains under ignored work/cleanup-live-20261105/.

Offline comments remain paused. Retain the user's repeated-redownload reproduction: first few videos have comments, later ones do not, usually returning to the home feed after starting downloads. Read-only source inspection confirms media completion is separate from page-wise background comment preparation and three failures pause a job; remaining on the offline page is not an explicit execution condition. This is source evidence, not a live job diagnosis. No comment implementation or switch was changed and no video was redownloaded.

# User acceptance — 2026-11-05: custom-font import and bilingual line-order switching

After delivery of2.0.97, the user reports “完成”. Record both font-file import and original/translated vertical-order switching as accepted in this round's observed use. This supersedes the pending-device wording in the historical build evidence below; it does not claim every font format, device or source-language combination. Offline-comment work remains explicitly paused; HDR remains stopped. This acceptance update changes documentation only: no new implementation, build, tests, installation, phone action or settings change. Other remaining work is retained rather than automatically started.

# Verified build — 2.0.97-debug /117: private custom-font import and live bilingual line order

Latest steering: user confirmsGIF works; offline-comment work is explicitly paused. User expects phone testing later, so no phone commands, installation, restart or settings changes were made for this round. Font-file import and original/translated line-order switching are combined in the same package.

An explicit manager Activity uses OpenDocument and ignores incoming Intent URIs/extras. Read only a user-selected document, bound it to64MiB, and copy it into a fresh private file. Validate sfnt structure and the platform Typeface before registering a SHA-256 asset. Support single-face TTF/OTF, reject collections/web-font formats; failed candidates remain unregistered and are not automatically deleted. Source files are untouched. Selecting a system family clears the active asset preference without deleting copies. Recent selection lists at most128 records, not an eviction policy. A read-onlyProvider validates own/supported-host UIDs, restores Binder identity, validates canonical file location/length/digest and supplies a descriptor; font bytes are never serialized into config. Font family/file handling is shared by preview and playback. A bounded background loader coalesces requests, ignores stale selections and retries temporary failures while preserving a system fallback. File IO is kept off subtitle rendering.

The backward-compatible fourth display argument accepts original_first/translated_first. Default order and single-language/missing-translation/duplicate behavior are preserved. UI, preview, validated config mirrors and playback all use the same key. Bilingual text is composed byToki even when typography follows native appearance, so ordering is not silently ignored in that mode. The integration test switches the same label with no new frame or translation request in both appearance modes; it first establishes a completed pair through the production translation/cache API. Initial synthetic-window eligibility did not dispatch a translation; the display assertions were kept, and the fixture now supplies its required available pair without changing the translator/network code.

74 focused tests pass with0 skipped; no full regression. Structural parsing also passed two real local TTF files, kept private; this does not establish Android glyph rendering. Synthetic storage/provider fixtures replace only the platform-validation callback, explicitly not a renderability assertion. The standard picker, real Android Typeface loading via the cross-process descriptor and actual TikTok display remain device-acceptance pending. Font UID/read-only and deferred-load/stale-result/5-second retry boundaries, schema/mirror roundtrip and existing overlay cases are covered.

785 source checks passed. Toki-2.0.97-debug.apk: 87,662,966 bytes, SHA-256 `d81f1edcddb315e9042859ff501aba1990efcb47e1a2d32455ac50caf41710b6`. Signatures, ZIP CRC/alignment, dual entries/private payload,551 production input hashes and new Activity/Provider declarations in both APK manifests matched. No new broad storage permission was requested. Raw user fonts, private paths/captures and local checks are not GitHub artifacts. Phone testing remains deferred to the user's availability; offline comments and HDR stay paused.

# User acceptance and next investigation — 2026-11-05

User reports the96 round complete and requests continuation. Record the reported directory/subtitle fixes as accepted in the observed use, without claiming full geographic metadata coverage or no future caption stalls. Next item isGIF. The existing animated-GIF encoder/menu path and twelve focused container/source-policy tests pass, including independent GIF decoding, transparency and dictionary resets. The phone runs96, but the current ordered diagnostic reply contains no sticker-export operations and the visible screen is an ordinary video. A target sticker/actual failing step is needed to distinguish missing menu entry from source/decoder/save failure. No speculative production patch, newAPK, install/restart, settings change or gallery write was made. Private screenshots/diagnostics stay local/ignored.

# Verified build — 2.0.96-debug /116: administrative-region directory and stale-caption handoff

User annotations accept all-setting live application, author-region Chinese/profile entry, and speed locking. Animated-sticker work has onlyGIF outstanding; partial purification works but some components have no settings entries. Video modes are unverified, not accepted or failed. Subtitle styling should stay customizable; built-in system families, size/weight/colors/outline/shadow/spacing/background/position already exist, while font-file import does not. Translation is generally working per user, with an intermittent native/custom subtitle stall relieved by switching videos. Earlier accepted image carousel, danmaku entrance, offline menu and95 saving remain accepted. These latest observations supersede older blanket pending-acceptance rows below.

The new administrative-region picker reads a bundled validated GeoNames admin1/cities1000 join:3865 unique codes across228 data-country codes,3782 representative city points and83 without points.2167 Chinese labels use a unique normalized per-country CLDR English match; GeoNames FIPS-style codes are never assumed to beISO codes. Prefer a first-level seat, then national/lower-level seat and population. This is a representative city, not an administrative centroid. Search is country-scoped; keep25 original city presets in a separate tab. Known points use an atomic existing-schema coordinate/timezone selection, with no automatic spoofing switches. Unknown platform zones or missing points are disabled and labeled. Data source hashes/attribution/licenses are shipped. The public importer validates input hashes and reproduces all3 generated files exactly. This is not a service-availability/carrier/language whitelist and does not complete every country-region requirement.

The caption overlay had an inconsistent ownership rule: the progress path allowed timeline fallback after a native-frame lease expired, but an old visible native binding could keep blocking the timeline indefinitely. Allow a current same-video/playback-pass local timeline to take over after native expiry in both custom and native modes; suppress only the inspected caption-renderer source after replacement is mounted. Fresh native frames regain ownership and original alpha/lifecycle cleanup remains paired. No player restart, broad UI unblock, network polling or synthesized wall-clock captions. A new regression exercises lease expiry, old-label suppression, fresh-frame recovery and cleanup in both modes. New takeovers require a recent progress-driven update; wall-clock expiry alone cannot reactivate an old unmounted cue while paused, and an already mounted current cue remains stable on pause. It proves this code defect, not every cause of the user's intermittent stall; missing timelines or stopped progress callbacks remain outside that recovery path.

777 source checks and30 focused tests passed; no full regression. UnifiedAPK Toki-2.0.96-debug.apk: 87,469,834 bytes; SHA-256 `2ac8f80bee0f726608f221fd71cb1e3308bb5cb5186f237e988c7578b8d06a0b`. Signatures, alignment/CRC, dual runtime/private payload,543 production source inputs and the4 geography assets in both APKs matched. No installation, app restart, phone settings change, video download or network translation test was executed. New picker UI and intermittent stall recovery remain device-acceptance pending. Raw geography downloads and private phone evidence remain local/ignored; only derived licensed assets, code/tests and curated status are public.

# User acceptance update — 2026-11-05: offline video saving works

The user confirmed “可以了” after2.0.95. The reported offline-save path is accepted and removed from the current fault backlog. This supersedes the pending menu-save wording in the historical build sections below; it does not claim every device/format or additional unreported behavior. Photo display/carousel, danmaku entrance hiding, offline long-press and current subtitles also have prior user acceptance. Offline comment prefetch remains unresolved. Download-time subtitle readiness/lifecycle and other previously authorized incomplete implementation/coverage items are listed at the top of FEATURE_STATUS_ZH.md. HDR remains explicitly stopped. This update changes documentation only: no new build, test, device action, settings change or deletion.

# Verified build — 2.0.95-debug /115: fix AAC priming rejection; native container copy verified on phone

Read-only diagnostics from the installed94 package showed five failed exports of the same-size native file. All passed code gates, native DB lookup and retained-header restoration. Android is13; the old combined Android10/model/cache message did not identify the cause. A focused native extractor probe found HE-AACv2 audio with encoder-delay3553 at22050Hz: four leading samples have negative presentation times, starting at-161133us. The module incorrectly rejected every negative timestamp. Both media tracks are readable:2060 AAC packets and2291 HEVC packets, with no PSSH/encrypted/partial flags. These are metadata counts, not user/account identifiers.

Accept negative AAC priming only within its declared encoder-delay/sample-rate bound, with malformed/excessive delays or unexplained negative timestamps still rejected. Complete muxed native MDL files now restore/copy their original container instead of remuxing, preserving edit lists, priming and B-frame timestamps. Separate tracks and fragmented sources retain the prior remux path. Full sample checks and independent readback remain: copied output keeps original timestamps and delay metadata, matches encoded packet fingerprints/counts and source length, and publishes only after verification. A separate native remux experiment did change the tested video timestamps; it was rejected as evidence of valid output and is not the selected copy path.

The exact compiled production OfflineMdlFile/LocalVideoFile/LocalVideoTracks/LocalVideoRemuxer methods ran on the user's Android13 phone via a temporary diagnostic runner. They inspected the failing file, copied it to a fresh temporary MP4, and passed production output readback for all4351 packets. Output bytes exactly match the independently reconstructed original; both original media and sidecar hashes were unchanged. This tests the real Android reader/writer path, not the menu/MediaStore publication path. No app install/restart, setting change, gallery publication, network video download, source edit, deletion or cache clear occurred. Diagnostic temporary files are retained, not removed without consent.

Platform-version, host-code and timestamp failures now have separate messages; generic unsupported no longer claims every user needs Android10/model/cache setup. Feature help uses user-facing save behavior. Subtitles remain user-confirmed working and unchanged; HDR remains stopped; the comments-cache report remains unresolved.

772 source checks, whitespace checks and37 focused tests passed (no skipped cases); full regression was not repeated. The existing native MDL instrumentation case also now requires byte-identical container output, but the instrumentation target was not executed. UnifiedAPK Toki-2.0.95-debug.apk: 87,104,692 bytes; SHA-256 `a83771877d302826d1f5f3825b032c4759abd9def45df7dc9619af3884c4e3c7`. Both runtime entries, private payload, signatures, CRC/alignment, new classes and536 production input hashes passed. No new APK was installed. The remaining acceptance is user-controlled installation/restart and one actual offline-menu save followed by gallery playback; exact production primitive success does not mark the full menu save as accepted. All raw account/media/native diagnostic captures remain local/ignored.

# Verified build — 2.0.94-debug /114: native offline file export, phone acceptance pending

Latest user request is to replace the failed video-save logic. Subtitles are user-confirmed working and are unchanged; HDR remains stopped. The user still reports missing/unusable cached comments. Some translated archived pages exist, but this does not establish the selected video's comment-cache acceptance. No comment feature is marked fixed by this export change.

New export path uses the native current-account offline directory and a parameterized read-only native DB lookup for the selected video and has_cached=1. It runs before the ordinary playback-cache index; that old index is now a fallback only when there is no native record. Existing original-code hashes gate all reflected native contracts. Video records were refreshed locally: the current60 native rows map to60 exact media/sidecar pairs. Older480-row captures are retained, not treated as the live inventory. No video was re-downloaded or full APK re-scanned.

Two inspected complete native MDL files have zeroed first128 bytes and the retained original header in their sidecars. The new reader strictly accepts only the observed v1 complete-range metadata profile with matching key, lengths, structure and a zeroed media prefix. This is a structural profile check, not a claim to implement every native checksum/version. It reconstructs a virtual read-only MP4 with MediaDataSource, without patching/renaming/removing source files or creating a temporary converted cache. Both sample validation and the actual mux reader use this adapter. Source/account/cancellation guards, encrypted/partial sample rejection, encoded-track fingerprints, final output readback and pending publication remain in force. Unsupported/partial/conflicting input is refused rather than guessed or re-downloaded.

769 source checks, whitespace checks and34 focused tests passed (0 skipped): MDL12, local-file6, track5, mux-guard2 and operation-journal9. Full regression was not repeated. Two existing media samples were reconstructed and fully decoded on desktop as HEVC+AAC; desktop remux preserved all encoded packets. This is not phone MediaExtractor/MediaMuxer or gallery acceptance. One new retained-header native integration test is added alongside the three existing Android cases; these tests were not run on a device.

Unified APK Toki-2.0.94-debug.apk: 87,097,712 bytes; SHA-256 `5fea89972c41bdc111e5105275f8e81dde83436f5a91ee4c3b1422c3d18b5769`. Version, signatures, matching signer, ZIP CRC, alignment, dual-entry/private payload, new-class namespaces and536 production input hashes passed. No APK installation, TikTok restart, setting change, saved-video write on phone, deletion or cache clear was performed. Next acceptance is one long-press save and playback of an already-downloaded video after user-controlled installation/restart. Raw DB/account/media data and device captures stay local/ignored.

# Verified build — 2.0.93-debug /113: download-time comments/translations, runtime pending

The user requires comments and their translations to be prepared during offline download, not first fetched/translated after opening the viewer. Existing downloads need configurable active/passive backfill; comments persist with offline-video identity until manual clear-all. Caption originals/translations require the same timing later, but current scope is comments only. Video export and HDR are not modified. User reports92 long-press menu now works; on-device reading confirmed20 cached original comments with no translation, not a wholly inaccessible dialog.

A dedicated default-off network-consent setting hooks inspected X.0rF7.LIZ (onVideoCacheSucceeded), with active backfill using inspected stored-model enumeration and optional passive repair for old downloads. Translation uses explicit adaptation dependencies even when other translation features are off. Requests are separate from playback-revision/local-only cancellation, authenticated account and validated network remain required, and the UI explicitly authorizes this separate online work. No new switch is changed automatically.

Durable per-account/video jobs and page files retain original+translated text, reply ownership and next cursors. Jobs advance after translated pages commit; row checkpoints survive restart. Atomic replacement includes readback verification. A local viewer window reads beyond the single500-row snapshot without the old100-video/2000-translation global limits. Page/text safety bounds, serial requests and bounded retry remain; truncation, missing translation, nonprogressing cursors or insufficient server coverage do not claim completion. Automatic expiry/LRU/host per-video invalidation does not delete companions. Only inspected native manual-clear-all events may clear module-owned same-account files with a stale-writer epoch barrier. No deletion/clear-all test or device cleanup was executed. Uninstall/app-data clearing remains outside persistence guarantees.

757 source checks and68 focused tests passed: archive6, progress3, merge6, feature-plan25, status28. Initial overwrite tests exposed stale readback; checked atomic replacement fixed the issue without relaxing assertions. Feature routing was corrected to explicitly wait for native translation adaptation; the final focused run passed. Full regression was not repeated. Original callback/model-list/serializer/manual-clear signatures were checked against the full original47.6.3 APK. The unified APK and relocated payload passed signature, ZIP CRC, alignment, entry/namespace/new-class and533 production input checks. APK: Toki-2.0.93-debug.apk; 87,055,800 bytes; SHA-256 `1d570b5b87d56230525dd337a1c014695db8046867c855fcacd9973fcd54160b`. It is not installed and no network-consent switch has been changed automatically. Device download/translation, offline reopen, account changes and configured backfill still require runtime acceptance; caption download-stage implementation and video export remain open. Private host sources and account captures are local/ignored.

Downloaded-video inventory is retained locally for subsequent export work: original offline index DB plus WAL/SHM, schema and480 row metadata records, all native has_cached=1, including account/video identity, cache key, cache times and native model protobuf bytes. quick_check returned ok. This is a sequential live copy, not an atomic snapshot or independent proof every media file is complete. No videos were re-downloaded. Reuse this inventory and the existing inspected cache/export contracts rather than another full-APK scan; raw DBs, media URLs and account identifiers are never public artifacts.

# Verified build — 2.0.92-debug /112: offline native hold; real menu/export pending

The user stopped HDR work and explicitly requested offline video. No HDR production code or phone switch is changed. A read-only device check confirmed module91 and both offline switches enabled. The user navigated to the real offline page; a single center hold still displayed the native unavailable-operation message, and no Toki early-hit counter appeared.

Original47.6.3 identifies the gesture surface X.05p5 as a plain View; the existing conservative sibling hit check does not permit it. The new fallback observes its matured X.05pB.run only after original classes4/classes5 code hashes match. It requires current selected offline holder identity, the same live player, visible attached descendants of the selected card, the same focused activity/window and a fresh model reread. The native callback is consumed only after the local menu shows, and the trailing finger-up is drained rather than becoming a click. Online/stale cards, other windows, unsupported code and unsuccessful menu display retain native behavior. The old early-hit guard is not loosened and no generic toast is suppressed.

746 source checks and12 focused tests passed. Exact original Runnable owner, root/player getter signatures and both code hashes were separately checked against the complete original APK. The unified APK and relocated payload passed signatures, ZIP CRC, alignment, namespaces/new-class and526 production input checks. APK: Toki-2.0.92-debug.apk; 86,905,760 bytes; SHA-256 `6ea0a108e279b17b7d03d1fdc173c0feb1694007445fe96e5923293df9aa7896`. No new package is installed, no TikTok restart or saved-video export is performed. Requested device installation/restart/export approval is pending. Menu acceptance and playable saved-media acceptance are separate gates; existing export readback checks and source-cache preservation remain unchanged. No full regression was repeated. Raw host code and device captures remain local/ignored.

# Device capability follow-up — 2026-11-05

The reconnected device completed explicit HEVC Main10 / HLG configure-only queries requesting SDR transfer 3. OMX.qcom.video.decoder.hevc returned an input format without color-transfer-request; c2.android.hevc.decoder returned color-transfer-request=0. Neither result affirmatively accepted the request. No media frames were submitted, so these observations do not establish all-decoder impossibility or successful SDR frame output.

No production hook, phone setting, source color label, decoder routing or brightness was changed. No new module was built. This does not justify another speculative package using the system request alone. Actual player tone mapping or genuine SDR-source evidence remains required, and HDR-off is still unaccepted. User-confirmed photo and danmaku fixes are unchanged. Raw local query output and temporary capability helpers remain ignored.

# Follow-up — 2026-11-05: danmaku accepted; HDR decoder capability pending

The user confirmed the danmaku entrance is fixed and authorized continuing. HDR-off is still unaccepted: later observations contain an active HLG layer despite native selection-policy calls. Original classes4.dex analysis identifies com.ss.ttm.player.AJMediaCodec.configure as a real call site of system MediaCodec.configure; no existing color-transfer-request was found in that bridge. Local SDK metadata exposes KEY_COLOR_TRANSFER_REQUEST from API31. A disposable HEVC configuration query is compiled locally, not deployed or executed; online API documentation could not be fetched. No hardware acceptance or true frame tone mapping has been proven.

ADB currently reports no device. Next action requires device reconnection to query the actual decoder, before introducing a guarded HDR-to-SDR request into production. A configuration acknowledgment must not be confused with verified SDR frames. No module build, production hook modification, app restart or phone switch change occurred during this evidence-only follow-up. Private source excerpts and helper outputs stay ignored locally.

# Verified build — 2.0.91-debug /111: danmaku entrance added, real hide effect pending

The user identified the center screenshot control as the danmaku entrance. The prior cleanup catalog and hook had no such option. This batch adds a dedicated layout_hide_danmaku_entrance key under side-control cleanup with independent direct-host installation and the shared manager/injected dashboard. It does not require navigation adaptation.

Original 47.6.3 classes33.dex confirms the create-entry LLLF/LLLFF and switch-entry LLLFFI/LLLI view fields. Only actual descendants of the current lifecycle root are bound; callback roots and unrelated/detached controls are rejected. Native video/photo containers, touch handling and danmaku-content preferences remain unchanged. Visibility ownership preserves native hidden values on restore and suppresses native reshow while requested. Existing pages can require one re-entry after first live installation.

742 source checks and 31 focused tests passed (three entrance tests and 28 status-model tests). No full regression was repeated. The unified APK passed signature, ZIP CRC, alignment, payload/namespace/new-class and 525 production input checks. APK: Toki-2.0.91-debug.apk; 86,884,596 bytes; SHA-256 `cc15e801e4ebba53ddf13d97881fe79b4fe2992b70f3083ac30798fc437da6e3`. The user subsequently confirmed the reported danmaku entrance issue is resolved. This acceptance does not cover all cleanup variants. No phone switches were changed. HDR production code is unchanged: subsequent sample evidence again showed HLG/HDR, so the paused-frame off observation is not accepted as HDR-off success. HDR investigation is deferred for the user's current entrance request. Raw host sources, captures and logs stay local/ignored.

# Verified build — 2.0.90-debug /110: getter bypass corrected; real SDR output pending

The user reports 89 still outputs HDR. A read-only in-process diagnostic snapshot confirms HdrPlaybackHook was enabled and committed, but PlayerConfigImpl.isForceHdrOff had zero calls. Configuration delivery and registration were never evidence of real SDR output. Native code has an outer experiment gate and cold/local early returns; ART may also inline the tiny getter. The evidence does not isolate which bypass occurred yet.

The 90 patch verifies original classes2/classes4 hashes before admitting the obfuscated selector/gate, scopes the outer gate to the enabled synchronous selection only, and deoptimizes the exact policy/caller so inline execution cannot bypass the hooks. Nested/thread-local scopes restore on exceptions. Selection diagnostics record policy/gate reads and SDR/HDR candidate counts, not media contents. A return or changed list still does not prove physical output. Unsupported original code fails closed. Cache-only/HDR-only paths remain explicit limitations.

738 source checks and 10 focused tests passed; the exact native members and code digests were additionally verified against the full original APK. The unified APK and relocated payload passed signature, ZIP CRC, alignment, namespace/new-class and 521 production input checks. APK: Toki-2.0.90-debug.apk; 86,861,876 bytes; SHA-256 `c966d5f8527578bc1f5349778e921e592c0de45bebc8a28c6376349b4d0e62f3`. No full regression was repeated. The user must update the module and fully restart TikTok to load the new code; the existing enabled HDR-off toggle does not need repeated switching. No phone switches, host preferences, brightness, system HDR capabilities or media colorspace flags were changed. No automatic install/restart was performed. Real SDR output remains unverified and offline work stays deferred.

# Verified build — 2.0.89-debug / 109: disable HDR playback; SDR output pending

The user clarified that the requirement is to remove HDR playback, not enable it. Previous physical HLG/HBM observations prove the baseline HDR state only and do not satisfy this requirement. Offline work is deferred until the HDR-off path is addressed.

The new opt-in hdr_playback_disabled setting requests PlayerConfigImpl.isForceHdrOff through the inspected ISimPlayerConfig contract. Original 47.6.3 caller instructions confirm that the native true branch selects bitrate entries with getHdrType()==0 when such entries exist. Toggle-off preserves the native off preference (nativeOff || disabled), not forced HDR-on. The module does not relabel decoded frames, change display capabilities, modify brightness or convert downloads. UI/config mirroring and normal late installation share the existing standalone/injected paths.

This is a next-playback selection policy: existing/preloaded decoders can remain HDR until reselected, and HDR-only content is not guaranteed to have SDR fallback. 732 source checks and 56 focused tests passed; no full regression was repeated. The unified APK and relocated payload passed signature, ZIP CRC, alignment, namespace and 519 production input checks. APK: Toki-2.0.89-debug.apk; 86,839,416 bytes; SHA-256 `3fce68feffb71bbd3daec5290c8cd2967a29b366c1cbb69e48494d783272f13a`. Installation and real HDR-to-SDR output acceptance remain pending. The user must install the new module, enable Disable HDR playback, and reload the same HDR video before read-only output verification. The new switch is default-off and no phone setting is changed automatically.

# Current verified build checkpoint — 2.0.88-debug

Updated: 2026-11-04. Current source and verified local APK: **2.0.88-debug / 108**.

The photo-indicator cleanup previously treated PhotoSwipeHintAssem's lifecycle root as an indicator view, although the inspected native class configures the photo pager/scroller/touch listeners. The behavior-only class and abstract slide-indicator class are no longer root-hide mappings. Concrete number/dot cleanup now resolves inspected indicator child fields, rejects callback-root aliases and detached/external reused views, and retains the native carousel. No display-mode or user switch change is required by this patch. Native swipe-hint behavior is retained rather than hiding a guessed visual root.

727 source checks and eight targeted tests passed (three new photo-target tests plus five existing selector tests). No full regression was rerun. The unified APK and embedded relocated payload passed namespace/new-selector-presence checks, matching signatures, ZIP CRC and alignment; all 514 production input hashes matched. APK: Toki-2.0.88-debug.apk; 86,829,748 bytes; SHA-256 `d559659a335d493a29e7446a89c37d54a6e5f9a314547056225246cc4c1fd561`.

Device follow-up: the user installed the APK; read-only ADB confirms 108 / 2.0.88-debug. The user separately confirmed photo display and horizontal multi-photo swiping recovered on the reported page. This does not imply all runtime features are accepted. The user authorized continuing HDR, then offline video. After the user turned the physical screen on, read-only SurfaceFlinger evidence showed the HLG video layer in the enabled physical device composition path with forceClientComposition=false, and display service reported mHbmMode=hdr / mIsHdrLayerPresent=true. HDR output is verified for this sample, not all videos/formats; the scrcpy virtual display declares no HDR support. No forced-HDR code is justified by this sample. Continue with offline video acceptance. No app switch is changed or forced-HDR patch introduced. Raw diagnostics and original host code remain local/ignored. The first build wrapper invocation incorrectly bound --offline as its AAR argument and stopped before compilation; the corrected explicit cached-AAR invocation completed successfully. No test assertion was removed or full suite repeated.

# Current verified build checkpoint — 2.0.87-debug

Updated: 2026-11-04. Source and verified local APK at that historical checkpoint: **2.0.87-debug / 107**.

The existing desktop report records 1,067 tests: 1,064 passed, zero failed and three native-media tests skipped. The 722 source checks and APK signature, ZIP CRC, alignment, embedded payload and 513 production input checks passed. The resumed chat rechecked all 513 input hashes and the APK hash without repeating the full suite. APK: Toki-2.0.87-debug.apk; 86,808,860 bytes; SHA-256 `35a271e3933ed637e9a612ab96edac822d31e6577847392b2298125b4c321b94`.

ADB confirms the installed manager is 2.0.87-debug / 107. This confirms installation, not acceptance of every runtime hook. This build contains inspected share adapter resolution, sticker/text menu routing, offline gesture targeting, live touch rule, bounded same-author region evidence, absent-only historical host configuration reconciliation and 25 curated city/state location representatives. Earlier Root font-size mirroring evidence does not establish no-root or all-field correctness.

The latest independent user message raises blank photo posts (download thumbnails remain visible) to priority 100, ahead of HDR and offline video. The user will operate requested switches; automated phone navigation/switch changes are stopped. The user clarified that the whole multi-photo carousel is absent, not merely a fit-mode problem. The temporary immersive toggle comparison was withdrawn; the investigation targets photo container/lifecycle/binding and visibility code directly. End-to-end HDR stream/decoder/output evidence and offline device acceptance remain open. No HDR fix or all-feature completion is claimed. Original host code, account content, device captures, logs and configuration remain local and ignored.

# Verified Debug checkpoint — 2.0.86-debug

Updated: 2026-11-04. Source and local APK at that historical checkpoint: **2.0.86-debug / 106**.

713项源码／接线／词法检查、差异检查、223份XML解析、92项定向回归通过；完整桌面1054项为1051通过、0失败、3项原生媒体能力跳过。统一模块APK的版本／双入口／私有载荷／profiles／签名／ZIP CRC／对齐／507源输入一致性检查通过。

Home now exposes installed-host manual adaptation. Package-scoped nonces bypass cached and bundled mappings, request all known core/extension rules, and are consumed only with saved scan results. Delivery is read back from the actual framework publication or host-private overrides; local-only standalone saves are not reported as delivered. Injected settings expose the same flow without a manager service. Rooted and no-root host settings now also mirror through an authenticated local provider with a durable immutable outbox and transactional replay receipts; manager UI listens to the resulting config publication. Settings mirroring needs no restart. A manual code rescan still requires a full user-controlled host restart, never app-data clearing.

APK: Toki-2.0.86-debug.apk / 82.72 MiB / SHA-256 35bea85600faaef9c8908a10585a8d95d213a2cd9474ff349e981b3bbd583c21. Earlier artifacts and pre-existing worktree changes were retained. The registration/window guards carried from the unpublished 84 worktree are covered by the full desktop regression. Real TikTok, injector behavior and the native-media device tests were not executed. Manual rescan does not prove changed native contracts are fixed, nor that the original full requirements are complete.

# Verified Debug checkpoint — 2.0.83-debug

Updated: 2026-11-04. Current source and checked local APK: **2.0.83-debug / 103**. Earlier APKs are retained.

This batch adds an inspected-code immersive live capability, early read-only observations, immutable screen copies with identity-based cache ownership, paired window/background restoration and native panel/cell recalculation. The installed session is promoted only after the classes4/classes5 gate and hook registration succeed; unmatched hosts retain their startup contract. Unknown decor-fits values are not guessed. Deferred hidden-panel restoration survives until the same current panel returns.

663 source checks, whitespace, 217 XML parses, 83 focused cases and the full desktop suite (988 total: 985 passed, 0 failed, 3 native-media capability skips) passed. The Android test target compiled but did not run on a device. The unified APK passed version, both entries, payload/profile/namespace, signatures, ZIP/alignment and 494 source-input checks. APK: Toki-2.0.83-debug.apk / 82.52 MiB / SHA-256 e64dbd2a8546c28a001a24f8e0ef986acba700897403090d839ab8de04b36a8a.

The first 62-case run failed six mutable-color assumptions under target-35 enforcement. Restoration assertions were retained, mutable and enforced windows were tested separately, then the expanded focused/full runs passed. UI calls and simulated state transitions are not real TikTok or injector/pixel acceptance. Unknown players, cached menus, unmatched hosts, restricted hidden getters, unobserved roots, unhooked/native-direct writes and the other original feature requests remain open. No install, uninstall, clearing or deletion occurred.

# Verified Debug checkpoint — 2.0.82-debug

Updated: 2026-11-04. Current source: **2.0.82-debug / 102**; verified local APK: **2.0.82-debug / 102**. Prior APKs are retained.

This batch fixes missing-current-caption timeline retries, independent bounded current/future download lanes, rejected-job marker recovery, and stable-identity memory reuse after signed URL refresh. Parsing/normalization remain on the worker before UI delivery. Selection/track changes and cancellation reject stale callbacks. Cache-first hybrid policy is unchanged; local-only remains optional.

649 source/wiring/lexical checks, whitespace checks, 217 XML parses and the 52-case focused suite passed after the new test fixture initialized the runtime reporter. The first fixture run failed (9/52); assertions were retained, initialization repaired, and the suite rerun. Full regression passed: 961 total, 958 passed, 0 failed, 3 native-media capability skips. The Android test target compiled; it did not run on a device. Unified packaging, both entries, payload/profile/namespace, version, signatures, ZIP/alignment and 489 source inputs passed verification. APK: Toki-2.0.82-debug.apk / 82.41 MiB / SHA-256 2ff6aca2f18e2b2f3a1f073640b481bb1f077ee65ca664d10ec3c02dbb24dbaf.

Read-only region investigation found the optional LIVE mock list falls back to an asset absent from this original APK. No external assignments establishing a complete supported-country list were found by the targeted member-reference scan. This is not evidence that TikTok has no such server list, and it is not a basis for inventing country/subdivision support from ISO/payment data. The full requirement remains open.

No phone installation, real TikTok interaction or native-media device test ran for this checkpoint. Prior checkpoints and remaining requirements follow.

# Verified Debug checkpoint — 2.0.81-debug

Updated: 2026-11-04. Current source: **2.0.81-debug / 101**; last verified local APK: **2.0.81-debug / 101**. Latest published production release referenced by this repository remains **2.0.51**.

**Desktop verification passed before APK packaging.** 644 source/wiring/lexical checks and whitespace checks passed. Main/test compilation, 58 focused cases and the full desktop run (951 total: 948 passed, 0 failed, 3 native-media capability skips) passed. The unified 2.0.81 module APK built successfully and package/entry/payload/profile/signature/ZIP/source checks passed. Those three platform-media cases still require actual Android execution, and no real TikTok/device acceptance or full-goal completion is claimed.

**For a concise Chinese source/status/limitation matrix, start with [FEATURE_STATUS_ZH.md](FEATURE_STATUS_ZH.md).** The diagnostic ZIP has a separate runtime-evidence overview; the two are not interchangeable.

## 2.0.81 author-route source and verified build

- Current source version: 2.0.81-debug / 101, based on 3e8198726f960e6e569cebdd4c70d421ba8bf3ce. The verified distributable is now 2.0.81; the prior 2.0.80 checkpoint remains below as history.
- Fresh local inspection confirmed the target Manifest 47.6.3 / 2024706030 and classes5.dex SHA-256 60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff. Native method/field inventories and caller inspection show direct primary-label setText branches as well as Mq. Existing Mq-only interception misses the former.
- New observer covers inspected view/item lifecycle and both nickname callbacks; it only binds the exact primary TextView, current cellProxy.LL item and matching native name. Homepage collaboration follows the native profile-mode gate/selected User, not an assumed original author. Rich badges require an actual raw binding witness, module prefixes have owned spans, and synchronous native rebinds are rechecked after module writes.
- Known-host observation can prepare disabled views for later toggles; code-identity verification precedes native helper invocation. Weak observation and bounded name caching avoid retaining native span callbacks or formatting every frame. Missing data, mismatch and absent region remain explicit, never filled from viewer/SIM settings.
- Display-only ISO3/UK aliases reuse Chinese country names and flags. No new server availability or subdivision claim. 644 source/lexical/wiring checks passed; main/test compilation and 58 focused JVM/Robolectric cases passed; full regression passed (951 total, 948 passed, 0 failed, 3 native-media skips); the unified APK built and passed packaging/signature/source checks, not device acceptance.

## Verified 2.0.81 Debug artifact

- Local module: `Toki-2.0.81-debug.apk`, 86411922 bytes (82.41 MiB), versionCode 101. SHA-256 `9a13f5ebc3fb7d31e205df7ea318693bf192c0568d7833a9bca46bcc6a135607`.
- aapt confirmed the same package and 2.0.81-debug / 101 for both outer and payload; valid matching v2 debug certificates, inner/outer ZIP CRC, zipalign and exact payload-byte checks passed. Modern entry, legacy bootstrap, private API namespace and equal profiles were verified.
- 489 non-signing source/resource/build input files matched the frozen pre-build manifest. No phone installation, actual TikTok profile-route test or native-media execution occurred. Existing 2.0.80 APK was preserved.

## Verified 2.0.80 Debug artifact

- Local module APK: `Toki-2.0.80-debug.apk`, 86346478 bytes (82.35 MiB), package `io.github.meiyongai.toki.subtitle`, versionCode 100, minSdk 28, targetSdk 35.
- SHA-256: `eaa4f60f740124f8786211cdd8af65d27f7db1b58c233e38f25947cacb52f7b0`.
- Signature: Android Debug certificate, valid APK v2; inner and outer certificates match. ZIP CRC and zipalign checks passed.
- Both modern and legacy-bootstrap discovery entries, private legacy API namespace, exact embedded payload and equal runtime profiles were checked. The embedded payload itself is version 2.0.80-debug / 100.
- All 485 recorded non-signing module source/resource/build-script input files still matched after the build. This is source consistency evidence, not a claim of bit-for-bit reproducibility of all third-party dependencies.
- Order: 628 source checks + whitespace + 215 XML syntax checks; full desktop tests (931 total / 928 passed / 0 failed / 3 native-media skips); Android instrumentation target compile; legacy payload build and verification; unified outer build and final file checks.
- No APK was installed on a phone, no real TikTok interaction or real native-media test ran, and no GitHub release or original host APK was uploaded. This is a checked Debug checkpoint, not completion of the full original requirements.

## Latest authorization — 2026-11-04

The user now permits the other original objectives immediately, without waiting for four-item acceptance or another start message. Finish the changes, pass basic checks, then compile/test and build a Debug APK; fix failed checks rather than bypassing them. The full original requirements stay active. Previous source-only/no-build and deferred-scope paragraphs below describe historical batches, not the latest authorization. No destructive cleanup or private host-source uploads are authorized.

## Verified desktop gate and earlier failure discovery

The first full desktop run reached 586 cases before a proven infinite automatic-vsync fixture loop was stopped; 8 failures were recorded, not counted as success. Confirmed planner errors now keep restart flags for rejected scopes, allow path-only late download installation without enabling source repair, and avoid associating a selected audio track with a different fallback video. Android-dependent caption tests now use Robolectric; the subtitle window/frame fixture now passes all nine cases with an explicit matching screen, manual vsync and bounded dispatch; expected transparent backgrounds are checked on the current rounded drawable rather than an obsolete drawable subtype.

Robolectric 4.17's MediaExtractor shadow provides zero tracks for real MP4 bytes. Three native demux/mux/readback assertions are preserved unchanged in a real Android instrumentation target, with byte-identical independently generated AVC/AAC fixtures; desktop capability skips are explicitly reported, not treated as native integration passes. Two portable cancellation/source-change guards were added and exercised in the focused repair run. Real Android media/device acceptance remains required, even after a desktop build succeeds.

## Baseline UI resource repair

Seven previously added cleanup labels/hints were missing from the other baseline locale catalogs. They now have localized entries in all 57 language variants, with canonical Android quoting and a 352-entry baseline strings.xml catalog. Both source completeness/placeholder checks and compiled-context locale tests passed. This is not a claim that every separate feature resource is fully translated or that all new wording has been professionally reviewed.

## Added in 2.0.80 — execution evidence and readable ID-less comment fallback

- Typed enum/numeric-only execution records distinguish player command returns, same-instance native setter observations, restore failures, unchanged/deferred/raced work, menu source reads, inspected target-root counts and audio getter returns. Six recent semantic changes per feature are retained, repeated identical samples update a saturated counter, and logging exceptions cannot fail business hooks. No raw comments, media/account IDs or source URLs enter these records.
- The overview first shows the four priority areas, retained-window media-operation outcomes and per-scope limits. Latest unavailable/running work is not replaced by older success; eviction and absence are explicit. Operation brief/detail capture is atomic under the journal lock. Unknown/future/wrong-feature/wrong-revision evidence cannot prove current success; shutdown without a receipt stays unconfirmed.
- Explicit per-dialog comment translation accepts readable text without requiring a persisted comment ID, preserves existing length/blank constraints and the native menu on unreadable text. It does not broaden translation networking or persist anonymous comments.
- 628 basic source checks and whitespace checks passed before Gradle. Main and test sources compiled; all 931 desktop cases completed with 928 passes, no failures and 3 explicitly native-media skips. APK and instrumentation compilation succeeded; package checks passed, but device acceptance remains pending.

## Earlier priority ordering (superseded by latest authorization)

**Updated user priority (clarified 2026-11-04; readable logging additionally requested):** advance only offline-video export, animated sticker conversion, all-setting live application and the pictured template/effect hiding. Offline-video export means saving already-downloaded TikTok offline videos into user-accessible playable files, not adding a cache-format conversion catalogue; track assembly/container handling is implementation work only where the actual export needs it. Other feature expansions are recorded in [USER_WORK_ORDER.md](USER_WORK_ORDER.md) and wait for an explicit user “start”. Existing functionality is retained. None of the four selected items is certified complete. This supersedes the previous ordering for the selected export task; the rest of offline integration is deferred.

### Earlier offline ordering and policy context

The user supplied the full offline acceptance requirements on 2026-11-03. Offline-video work remains the final integrated feature group, but is **no longer awaiting clarification**. The online-comment batch and local-only preparation are committed. The user has since confirmed hybrid networking by default: online translation/data are permitted when connected; saved caches are used when disconnected; explicit local-only remains optional. Selected-cell offline classification, a local menu and complete-MP4 export have now been wired in source; a saved-comment viewer with optional explicit online translation and persistent translated results is now wired as well; explicit first/next comment-page loading and private snapshots are now wired; top-level rows now also have per-root reply pagination/cursors and native coroutine cancellation in source; the inspected ordinary native comment button now has an independent opt-in cache-viewer route; hidden/disabled buttons, other native entry/layout variants, other-host/engine/player integration remain pending. Existing code is retained. No APK build is authorized.

## Offline acceptance contract (received and networking policy amended 2026-11-03; full behavior NOT verified)

**Latest user confirmation supersedes the earlier blanket prohibition on remote requests:** when connected, online translation or additional data are allowed; when disconnected, use saved local data and report missing capabilities. Explicit local-data-only mode remains available. Native offline playback/menus/comments and true disconnected operation are still required; hiding errors is not a substitute. The incomplete-video-cache export rule remains separate and unchanged in this batch.

| Area | Required offline behavior | Current gap |
| --- | --- | --- |
| Video menu | Long press while playing or paused opens locally executable actions; no connectivity-wide disable. | Experimental selected VideoViewCell local menu is wired; alternative layouts, paused gestures and conflicts remain unverified. |
| Save/export | Export only complete, validated local video bytes to user-accessible storage. Never fetch missing bytes; report incomplete/missing/unsupported caches. Offline cache and user export remain distinct. | Read-only indexed MP4 cache discovery, sample validation, actual local remux and destination readback are wired for Android 10+. Complete initialized single-file fragments and unambiguously paired video/AAC MP4 tracks can reach this path. Device behavior is unverified; API 28, encrypted/special caches, dispersed init/media files and unresolved model-only entries remain unsupported. |
| Comments | Open cached comments with author information/text and scroll cached pages; distinguish empty, not cached and partially cached pages. | A dedicated menu reads current-account native/Toki snapshots, author/text/replies, local pages and explicit first/next online pages with private commits. Per-root reply pages/cursors are now wired too. The inspected ordinary native button has an opt-in cache-viewer route. Hidden/disabled buttons, other entry/layout/host variants and real service/device validation remain unimplemented or unverified. |
| Comment translation | Single-comment original/translation/bilingual toggle in the comment area. Cached/local results work disconnected; online translation may be used when connected unless explicit local-only is enabled. | Per-row original/translation/bilingual, optional explicit online translation and account/source/target-bound private persistence are wired. No bundled local model; native-cell coverage and device validation remain incomplete. |
| Captions | Read original cues, synchronize to playback/seek/replay, show/hide and switch original/translation/bilingual. | Cached tracks alone do not provide offline page/player integration. |
| Caption translation | Read saved translations offline; online translation/caption data may be requested when connected unless explicit local-only is enabled. Local translation/ASR applies only if actually available. | Hybrid eligibility and visible-frame reconnect handling are in source. No bundled local engine, and full offline player integration is missing. |
| Persistence | Reopening the app retains complete local data/translations and per-video identity without applying stale results to another video. | Caption archives and private account/video-bound comment/translation snapshots exist in source. Cold reopen, native offline player association and all-device persistence are still unverified. |
| Networking policy | Default hybrid mode allows online work when connected. The optional explicit local-only switch prohibits module translation/caption fallback; disconnected paths must report missing data or engines. | Offline content classification no longer forces local-only. Identity-bound operations, cached reads and visible-frame reconnect checks are wired; all offline-page/comment paths and device behavior still need integration/verification. |

Required later runtime checks: airplane-mode cold reopen, no available network, network loss/restoration in hybrid mode and strict explicit-local-only mode, empty/partial caches, missing models, seek/replay/switching videos, interrupted export, comment pagination/toggles, and packet inspection distinguishing allowed hybrid requests from explicit-local-only behavior. No such runtime test has been run. Ordinary TikTok background traffic cannot be treated as proof of this module's behavior; the isolated call path must also be checked.

## Changes present in source

- Bounded versioned subtitle translation archives, copied track ownership on restoration, coalesced background writes, and cached source-timeline reads before network requests. Previously cached content can follow the offline path; there is no bundled offline machine-translation model.
- Country presets expanded to 163 countries/territories, with ISO Chinese display-name fallback and locale-independent code normalization. This is **not** an official TikTok availability list or a complete list of states/provinces.
- Cleanup-option search and settings-change subscriptions, plus the template toggle connected to an existing template-button binding. Specific 47.6.3 components remain unverified.
- Dependency preparation and late admission for 15 reversible feature installation units. Requested settings remain masked until successful installation. Startup-sensitive settings retain their restart contract; existing views may require re-entry or another binding callback.
- Shared subtitle preview/playback renderer: outline, shadow, line/letter spacing, corner radius, padding, alignment, line limits and original/translated/bilingual modes. Defaults preserve unrestricted lines and no outline/shadow.
- Per-key manager change clocks and durable suppression of superseded injected overrides. Unrelated changes preserve local choices; an explicit later edit of the same manager setting wins. Stored override values are retained for recovery. Host changes never write into framework read-only preferences. This is **not automatic two-way synchronization**.

## Added in 2.0.79 — independent live fixed-rate/menu and conditional restoration

- Fixed rate and extended menu have independent feature keys, symbol scopes, runtime installation, configuration listeners, disposal and applied-state reports. The current import revision reaches playback memory; changing fixed-rate settings does not replace menu payloads. Returned menu lists are defensive copies.
- Successful concrete player setter requests supply the only restoration baseline for that same weakly identified instance. Capacity is reserved before overriding a request; full/untrackable cases pass through. Module-generated writes and native user selections have independent thread-local nesting/finally cleanup. Failed native selections do not pre-update active rate or memory. Fixed-rate configuration loss releases known overrides even while the application refresh waits for ready configuration.
- Conditional static-field leases restore observed original values only while their last module write still owns the field. Unchanged module-owned fields are not adopted merely because a native method returned. Module-generated central-manager changes do not become external baselines. Per-field failures are aggregated, other fields and known players are still attempted, and failed fields retain retry evidence.
- Background audio is late-installable with the missing BACKGROUND_AUDIO dependency supplied. It changes future experiment getter reads only; it does not invalidate host-cached groups, start/stop playback or replace media sessions. Immersive-window and download-repair restrictions remain explicit. Menu-only status requires confirmed registration/application and confirmed fixed-rate disable; unknown/pending reports stay pending.
- 589 source-only assertions/lexical checks and whitespace checks passed. Twenty-four newly added regression cases exist as source only; no compilation, JUnit/Robolectric, device or APK build verification. No offline export, sticker or template behavior was newly extended in this batch.
- Not complete: a current player without a successful observed native setter is not forcibly changed/restored; it may wait for native speed/binding callbacks. Already-open menus caching their lists may need reopening. Concurrent native/module setter completion is detected/reported, not proof of an atomic physical-player state; a later refresh can retry known baselines. All-setting no-restart and all four user-selected acceptance goals remain unverified/incomplete.

## Added in 2.0.78 — selected template/effect business roots with independent live control

- Original template setting now belongs to a standalone direct/live installation unit, not navigation-layout symbol discovery. Existing layout UI and the specific legacy creation-button policy remain; no unrequested new cleanup category was added. Shared standardized-template class-only hiding remains explicitly rejected.
- Standard path reads IStandardModelProvider.providerDisplayAnchor/providerAweme, the selected AnchorCommonStruct.getType and the concrete X.0B4S.rootView. Thirteen inspected template/effect enum names and numeric codes are validated. Other/unknown businesses, ordinary CAPCUT links, donation stickers and shopping/location anchors are not hidden by this policy. No model/list/text mutation or whole multi-tag-container hide is used.
- Dedicated VideoTemplateButtonAssem uses creation/data-bind callbacks, requires the actual button inside its own content root, and releases on unbind/destroy. It does not globally observe the shared getContentView getter. Standard renderer root callbacks are recursion-suppressed while native lifecycle and getters continue unchanged.
- Main-process host preparation installs/adopts the observer even with cleanup off, without enabling visibility changes. It records weak owner identities during asynchronous code checking, then re-reads known owners only after all four DEX matches. Configuration changes revalidate exact anchor/video/root identity and update observed roots; reuse/unload/destroy/close release ownership, detach releases visibility and reattach revalidates current semantics. Other visibility owners/latest host visibility remain respected.
- Four expected DEX fingerprints, selected-provider/root signatures and 13 enum codes were independently matched to original local evidence. At most 256 weak owners/roots; unready, overflow, unsupported or unreadable instances retain native presentation and diagnostics record only technical counts/type/readiness.
- 554 source-only/whitespace checks passed. Twelve new regression sources cover concrete contracts, non-target/type identity, root replacement/reuse/capacity, attach/detach/current toggles, other owners and standalone/eager admission; NOT executed. No compilation, JUnit/Robolectric, device/layout or APK build verification was performed.
- Full goal not achieved: uninspected legacy renderers, ambiguous generic CapCut business links, roots existing before observation without further callbacks and startup verification flashes remain limitations; full screenshot-route/UI behavior and complete template/effect coverage are unverified. All-setting no-restart and other selected tasks also remain incomplete.

## Added in 2.0.77 — live platform environment reads with reversible payloads

- Existing SIM/locale/timezone/GPS overrides join live-install eligibility and no longer freeze their parameter keys at startup. Platform-only installation/admission occurs before host classloader, main-process-only and unrelated scan prerequisites; a failed feature remains masked and healthy siblings remain eligible. No new country or deferred feature was added.
- Each hook resolves one immutable effective configuration snapshot and atomically publishes a nullable payload. Off, missing-preset and disposal paths remove the override; actual payload application records its revision/enabled receipt. Diagnostics distinguish a registered hook from a pending/current payload and do not report requested settings as already applied.
- Timezone no longer writes global process defaults; Java/ICU results are copies and ZoneId derives from the resolved timezone, including short aliases. Locale still avoids global default/resource mutation and retains native font-locale protection. GPS copies the downstream last-known Location instead of overwriting it; coordinate pairs share a snapshot and are not logged.
- Scope: subsequent intercepted platform reads, not retroactive replacement of host caches, already-created objects, account/server region, sent requests or current page resources. Real hook interception, multi-process delivery and render/permission behavior remain unverified. This is not all-setting live restoration: speed, immersive-window and audio-experiment restrictions remain for later work.
- 530 source-only/whitespace checks passed. Fifteen new test-source cases cover platform admission/dependencies/current revisions, resolver data/absence, production payload helpers/copies/disable, actual receipts and pending diagnostics. Existing freezing tests were updated, not falsely marked executed. No Kotlin/Gradle compilation, JUnit/Robolectric, device verification or APK build was performed.

## Added in 2.0.76 — review correction of uncommitted shared-renderer mappings

- Reviewed the nine modified tracked files left in the local worktree. Retained the source version increment, documentation structure and inheritance test; preserved a local pre-review patch rather than resetting or discarding unrelated work.
- Inspected original DEX metadata and targeted decompilation: FcpStandardTemplateAssem selects generic UI schemas (plain button/tag/bottom tag/avatar tag/tip card); FcpStandardButtonAssem and FcpStandardTagAssem are abstract shared renderers. The schema namespace is not sufficient proof of video-template/effect business identity. One method in FcpStandardTemplateAssem failed decompilation; no claim of full-body inspection is made.
- Removed the draft's three unconditional VIDEO_TEMPLATE mappings. Existing inspected VideoTemplateButtonAssem mapping remains. A pre-registration policy now rejects class-only VIDEO_TEMPLATE ownership for the shared standardized-template namespace, including verification variants. Future support still needs an explicit per-instance business/anchor/root contract; this policy is not that implementation.
- Retained/relabelled the inheritance test as mechanism-only evidence, added negative registration-policy test sources and strengthened source checks. Corrected the duplicated changelog heading and inaccurate coverage claims. 501 source-only and whitespace checks passed; one inherited-selector regression source was retained and two policy cases added, NOT executed. No compilation, test execution, device verification or APK build was performed. The four selected tasks remain incomplete.

## Added in 2.0.75 — readable evidence-first diagnostics and operation logging


- Added a curated Chinese at-a-glance file distinguishing implementation, explicit missing support and verification level for all original requirements. Latest authorization remains the four selected tasks plus readable logging; deferred functionality was not expanded.
- Runtime ZIP overview lists every ConfigSchema feature: requested/applied/restart/registration/config-revision differences, errors/detail/counters and internal-path summaries. Missing host/feature reports, stale snapshots, version mismatch, unobserved operations and ready hooks are not promoted to business success.
- Bounded process log uses UTC time, monotonic elapsed milliseconds and sequence numbers. State/config transitions are deduplicated and timestamped. Host snapshot adds module/host version, Android SDK and operation summaries without dumping configuration content.
- Video export and sticker conversion now have generated per-process operation IDs, recent phase history, elapsed time, numeric metadata, candidate failure counts/types, first-wins terminal outcomes and next-step advice. Code checks/candidate-cache-sample checks/mux/readback/publish and source/animation parse-decode-encode are distinguished. No per-frame/sample event flood; operation tracing is not implemented for every other feature.
- Queue: 600 lines/96Ki characters. Journal: 64 operations, 16 recent stages each, 48Ki exported summary with explicit truncation. These are process-memory records, not persistent post-crash/restart logs. Reproduce and export while host remains alive.
- Extra metadata redaction/line escaping covers common URLs, credentials and private paths, but is not a guarantee for arbitrary content. Full logcat remains explicitly optional and may contain system/other-app information. New archives select the current snapshot and explicitly selected capture only, not all historical directories; no historical data is deleted. Per-file 8MiB and aggregate raw 32MiB bounds have a manifest.
- 490 source-only/whitespace checks passed. Sixteen new regression test cases cover journal phases/terminal/candidate/capacity/error metadata, readable states/revisions/privacy, log bounds and archive history exclusion; NOT executed. No compilation, JUnit/Robolectric, device validation or APK build was performed. Real ZIP/Binder/root/no-root behavior remains unverified; the four selected tasks remain incomplete.

## Added in 2.0.74 — offline-video export with local remux and readback

- Implements the clarified task: exporting downloaded offline video to one accessible MP4, not adding a format-selection catalogue. No deferred comment/subtitle/country/page functionality was expanded.
- Replaces unconditional model-based rejection with inspected raw-field candidate discovery. Avoids computed video-model getters and network/model expansion. Exact selected video/audio pairs take precedence; only an unambiguous sole audio alternative can be used as fallback. Missing/ambiguous required audio cannot silently become a successful silent export. Audio cache lookup follows inspected native fileHash semantics.
- Complete single-file initialized MP4 fragments can now reach actual extractor validation. Source index/physical size, allowed storage, box boundaries, init/media presence and source identity remain checked. This does not assemble separate init/media segment files or repair missing data.
- Every chosen local media sample is inspected from a descriptor, with video/audio track count, supported muxable codec, DRM/encrypted/partial samples, resource budgets, timeline coverage and initial sync checks. Complete muxed inputs or a supported video plus uniquely paired AAC audio are written through MediaMuxer. No byte concatenation, filename-only conversion, transcoding or remote repair is used.
- One timestamp origin preserves relative offsets and per-track decode order; rotation is applied. Per-track sample-boundary/payload SHA-256/count/bytes/time summaries are verified while writing and independently read back from the completed destination. Mux finalization, descriptor sync and readback must pass before gallery publication. Configuration disable, playback revision changes and source mutation cancel work. Unfinished pending gallery rows are not deleted automatically.
- Seventeen raw-field declarations and four added model DEX digests were matched to local original evidence, in addition to existing cache-index gates. Tests use independently generated gray-frame AVC/silent AAC, not host videos or original APK resources.
- 458 source/whitespace checks passed. Fifteen new regression case sources (candidates, track policy/digests, fragment envelope, future platform mux/readback/cancellation) were NOT executed. An independent PyAV reference remux joined 5 video and 9 audio packets and read back both counts; this was NOT Kotlin/Android execution. No compilation, JUnit/Robolectric, device verification or APK build was performed.
- Still incomplete: Android 9 export, encrypted/unsupported codecs, separate init/media files, parsed-model-only entries without raw addresses, multi-language audio selection UI, actual host cache semantics on device, real gallery playback/A/V sync/rotation/cancellation. The four selected tasks remain uncompleted for acceptance.

## Added in 2.0.73 — selected work order and real animated sticker conversion

- User-approved work order/deferred backlog is published separately. This batch changes animation export and its required UI/resource/validation paths only; it does not expand deferred country/author/comment/subtitle/cleanup features.
- New animated GIF/WebP intents are separate from original-byte preservation and static first-frame formats. They select only animation-source URLs. No static-preview fallback is used to claim successful animation conversion.
- Strict bounded container readers inspect source dimensions, frame rectangles, timing, loops, blend/disposal and complete boundaries. Inspected byte-array Fresco decoders provide each actual image patch. Full canvas composition implements blend, background clear and restore-previous. Native concrete frame geometry is compared to the container; handles/bitmaps are released on exit.
- Related classes10/classes13 DEX fingerprints are checked before animation downloads/decoding. Local original-APK metadata and JNI-registration strings were inspected; this does NOT establish actual device library availability or successful JNI calls.
- GIF encoding writes actual indexed/LZW frames with transparent index, loop extension and cumulative centisecond rounding. It quantizes to 216 colors and binary alpha. WebP muxing uses platform-encoded image bitstreams in full replacement ANMF frames with source millisecond timing. Android 11+ uses lossless encoding; Android 9–10 uses high-quality lossy WebP. Output is reparsed to check frame count, loop, dimensions and timing before save.
- Explicit limits: 300 frames, 2048 per edge, 1,048,576 canvas pixels, 64M cumulative work pixels, 120 seconds, 20 MiB encoded output. Inputs without real animation or unsupported rendering semantics are rejected, not flattened silently. Decode/memory/native availability and all actual pixel/timing behavior remain unverified.
- 429 source-only/whitespace checks passed. Eleven new regression cases with independent tiny fixtures and future ImageIO GIF decode assertions are source-only and NOT executed. A separate Python reference-layout experiment was decoded with Pillow for GIF/WebP frames/alpha/timing/loops; it was NOT the Kotlin implementation or JNI pipeline. No compilation, JUnit/Robolectric, device verification or APK build was performed.
- Additional cache formats, full live-setting restoration and complete template/effect-anchor coverage remain active selected work, not completed by this animation batch. APNG/animated AVIF and other unsupported codecs are not implemented here.

## Added in 2.0.72 — country directory and confirmed dashboard snapshots

- Country selection includes the runtime platform ISO directory plus the existing 163 carrier/environment presets. Search supports Chinese/English/localized names, codes and accent/locale-independent case normalization. Country metadata, complete operational presets and server/account availability are distinct; this is NOT a verified TikTok service whitelist or a complete state/province catalogue.
- Missing-carrier entries select a separately persisted country-only mode. It changes only country returns while retaining native operator/SIM/phone paths, and preserves old manual carrier fields. Explicit carrier mode remains validated; blank inputs no longer become JP/00000/invented carrier defaults. The mode does not independently install SIM hooks and remains startup-frozen with its owner.
- Absent environment presets return absence instead of en-US/UTC/Tokyo. Follow-region locale/timezone/GPS hooks remain native when required metadata is absent; manual environment settings remain available. First enabled state is published after payload resolution. Diagnostics report missing-preset status and do not report that unavailable environment as applied.
- Dashboard subscribes to changes, reloads all 58 persisted top-level controls from one immutable REQUESTED configuration snapshot and commits the state group through one Compose snapshot. It does not rewrite dialog drafts or reset confirmed controls on disconnected notifications. Waiting-for-restart settings keep their saved intent visible rather than silently showing startup-frozen effective values.
- Confirmed writes reload actual values and surface write/readback errors. Country/carrier, latitude/longitude, range bounds and video/image download paths use one related-field transaction. Failed saves do not close those dialogs or announce success; optimistic controls are reloaded. These source changes do not certify bidirectional sync or no-restart behavior on a device.
- Local host resource inspection found distinct support metadata in SettingRegion and a bundled payment-country table without service availability or subdivision fields. No unsupported inference that payment coverage equals TikTok availability was made; host data/decompiled content stays local.
- 401 source-only/whitespace checks passed. Eleven new regression case sources cover directory coverage/query/selection/custom validation, no fabricated environment/carriers, startup ownership, missing-data diagnostics and confirmed-write failure/readback/retry. They were NOT executed. No compilation, JUnit/Robolectric, device verification or APK build was performed.

## Added in 2.0.71 — independent search/inbox/LIVE component presentation

- Six independent default-off controls grouped in settings: search voice floating entrance, Tako/mixed floating entrance, inbox shop-migration banner, LIVE bottom/pinned follow-guide cards and GiftGuideWidget content. The mixed entrance can include other actions; the gift control does not cover separate Lynx popups/windows. No message/data deletion or lifecycle/business suppression is introduced.
- Direct-host and late-install wiring does not depend on top/bottom navigation discovery. Settings subscribe to shared state and report save failures. Closed controls restore observed host visibility while respecting other visibility owners. First activation/code preparation and unregistered/recycled views may still require page re-entry; this is not an all-feature no-restart claim.
- Search roots come from actual inflated fields, inbox roots from returned/rebound holder itemView, and LIVE roots only from matching actual contentView/getView. The inspected Widget.getView fallback to a container is refused. Detached holder creation is tracked until attachment; subsequent detach releases ownership/listeners and requires a fresh native bind/create/show callback. Bounded weak-identity records avoid equal-owner collision, stale root reuse and accidental retention.
- Six expected DEX hashes are checked off-thread per component; missing/unsupported code keeps native UI. Settings diagnostics distinguish checking, requested-but-unavailable components and ready installed controls. All six code digests were independently re-matched against the local original 47.6.3 APK.
- 371 source checks and whitespace checks passed. Twelve additional regression case sources cover configuration/code requirements, field/widget/holder readers, pending attachment, independent toggles, latest host visibility, shared owners, identity/capacity/reuse/disposal, and diagnostics. They were NOT executed. No Gradle/Kotlin compilation, JUnit/Robolectric, device/UI/pixel verification or APK build was performed.
- This supplies concrete page-internal targets, not complete search/inbox/LIVE purification. Other page variants, overlays, runtime behavior, complete supported subdivision coverage and the final offline integration remain outstanding.

## Added in 2.0.70 — six video geometry modes and live mode reapplication

- Six shared persisted keys: original/center, smart, fill, contain, fit_width and fit_height. New modes have real aspect-preserving geometry through the existing host result contract. Containment stays inside the viewport; width/height matching may crop the other axis and the UI states that. Legacy smart/fill algorithms and old keys retain their meaning; invalid/unmeasured/overflow inputs keep native results.
- Only the video-mode key was removed from startup freezing. Inside an already-installed immersive session, mode/fill zoom/auto-border settings can replay a current measured layout. The immersive window/master switch still requires restart because its existing system-bar/reserved-area edits do not yet have fully paired restoration. No all-feature hot-restart claim is made.
- A separately owned child geometry adapter is prepared even when starting in center mode. It tracks at most 24 weak component/view bindings, the latest host original and eight weak identities of recent module outputs. Configuration/viewport notifications coalesce to a UI-thread reapply for attached measured actually visible roots. Center returns the original object; cached echoes of recent module outputs resolve to that original instead of compounding scale.
- Rebind invalidates the previous video's capture; detach/release/destroy stop stale updates and release listeners. Leaving fill or changing its geometry restores old black-border transforms before reapplication. No live/photo modes or arbitrary stretch/rotation are introduced.
- Settings subscribe to config changes, describe each crop/letterbox tradeoff, preserve in-progress zoom drags and show write failures. Labels distinguish smart fit from the immersive master toggle. Unrecorded/unmeasured/unsupported current pages may still wait for the next native layout; reentry/rotation/surface variants remain unverified.
- 342 source-only/whitespace checks passed; five original-APK layout/lifecycle signatures and result types were checked. New geometry/config/original-object/replay/echo/rebind/visibility regression sources are NOT executed. No compilation, JUnit/Robolectric, rendered pixel/device checks or APK build was performed.

## Added in 2.0.69 — subtitle replay/cancel/shared-result source fixes

- Observed playback ownership now tracks the selected holder/video and backward progress beyond a 250ms jitter tolerance. Normal resume for the same holder does not repeatedly reset the pass; neighbour progress is excluded. Selection and rewind classification happen before native callbacks may emit frames, with selection rechecked afterward.
- A rewind/new selected holder invalidates old foreground delivery and native-frame presentation ownership, not completed translations or loaded cue timelines. A prior-pass Toki native binding cannot suppress the current timeline fallback; a real current frame can take ownership again. This does not rewrite or claim to fix TikTok's own raw native-frame content.
- Cancellation increments a request revision observed by the display layer, so an unchanged visible cue no longer remains permanently requested after its work was cancelled. Result validity captures the video/pass in addition to source/track generation. Cache hits remain available under the current hybrid/explicit-local-only policy.
- A completed translation increments a cache revision. Other visible bindings waiting on the shared request can read the exact completed video/track/text result once per revision without another network request or repeated per-frame lookup. Replayed completed cues remain reusable from cache.
- Added pure playback-clock and Robolectric source regressions for jitter/holder changes, same-cue cancellation recovery, shared-request displays, stale native-pass suppression, old callbacks and completed-cue replay. 315 source-only/whitespace checks passed; original APK playback methods were inspected. No compilation, JUnit/Robolectric execution, latency benchmark, device playback or APK build was run.
- Bounds remain: only observed backward discontinuities and holder changes are detected; forward seeks, paused/no-redraw wakeup, every track/language/host/offline-player variant and actual latency remain unverified. These fixes do not complete the whole subtitle or original project objective.

## Added in 2.0.68 — opt-in ordinary native comment-button entry

- Independent default-off **Offline video comment button opens cached comments** option shares current playback tracking but does not enable long press or online translation. Re-enter after first enabling; disabling returns subsequent clicks to native. The original ordinary button listener was traced into VideoCommentAssem.vq(View,String); the VM/item/video access and inheritance were checked from exact original DEX metadata.
- Only that component method is hooked, not global View clicks, resource-label guesses or a replacement listener. Interception additionally requires inspected DEX readiness, matching currently selected video ID, both selected/component offline facts, same Activity/page root and attached visible button/container. Ordinary online videos, stale/hidden/mismatched components, disabled/unprepared controls and unsupported code retain the original call. The click is consumed only after opening/reusing the current owned cache window.
- Code fingerprinting runs on the existing background worker. An optional child installation transaction is owned by the offline feature and rolls back independently; parent resource disposal is registered before later hook installations. Long-press/button share one viewer and existing local/comment/reply/translation paths. The entry itself does not submit network requests; page/translation actions remain explicit.
- Button-only enablement does not start long-press export. Disabling long press cancels its pending gestures and prevents its export from retaining eligibility just because the button route is enabled. Existing hidden/disabled comments are not forcibly restored. Other programmatic/native panel/layout variants are outside this narrow route and remain incomplete.
- 303 source-only/whitespace checks passed. Five original-APK method signatures, the event field, three VM inheritance edges and the ordinary click-listener call were checked. Added eligibility/default/live-admission/signature regression sources were NOT executed. No compilation, JUnit/Robolectric, device/UI or APK build was performed.

## Added in 2.0.67 — per-root replies and native coroutine ownership

- Top-level cached comment rows now offer explicit first/next/refresh reply actions. Each action requests one 20-item page for that root; scrolling does not submit network calls. Existing cached replies remain readable disconnected and retain the same original/translation/bilingual and optional online-translation features.
- The original reply endpoint is suspend-based, unlike the top-level Rx list. A host-ClassLoader Continuation/Function2 bridge preserves the native coroutine context and handles immediate/suspended completion once. An isolated native IO Job scope is attached to the operation before launch, and actual scope cancellation is called on timeout/close/config/account/page invalidation. No assumption that the module's Kotlin classes match host classes is made.
- Exact request/coroutine/helper contracts are checked behind the existing inspected-code gates. Non-personalized mode is read from the actual native privacy provider, not from an unrelated translation gate. Account/video/root identity and an optional returned root ID are checked; server restrictions are not overridden.
- Each root has its own next/hasMore state, distinct from video-list pagination and other roots. Merge rejects non-progressing/mismatched cursors, root-as-reply/cross-parent collisions and invalid parent graphs. Top-level refresh and local-cache combination preserve reply cursors; older unobserved replies remain explicitly flagged unvalidated rather than silently removed.
- Snapshot v2 stores root-specific reply cursors and continues reading v1 without deleting/rebuilding old archives. Capacity and private commit checks remain. Added source tests cover cursor isolation, parent preservation, overflow refusal, recycled buttons and v1/v2 reads.
- 290 source-only/whitespace checks passed; nine original-DEX reply/scope/continuation/privacy methods and three fields were independently matched. No compilation, JUnit/Robolectric, device/service/cancellation/airplane-mode validation or APK build was performed. Real HTTP cancellation, reply sorting/cursor semantics and cold reopening are still unverified.
- Reply entry is currently limited to top-level rows. Full native comment-button/layout replacement, more host variants, local translation/ASR and the rest of the original objective remain incomplete.

## Added in 2.0.66 — explicit online comment pages and private snapshots

- Separate **Load first page online / Load next page online** actions call the native authenticated comment-list builder for a 20-item page. Local reload and scrolling do not submit requests. Original APK request/scene/Rx contracts were checked from exact DEX metadata; the adapter is fingerprint-gated to its two inspected DEX files. It does not invoke native offline predownload/cache-clear managers, construct raw HTTP or inspect credentials.
- Network eligibility, selected video and account are checked before dispatch/acceptance. One request at a time, 15-second timeout and window/reload/account/config cancellation reject obsolete work. Cancellation before subscribe returns still releases the resource. Already accepted response copying/saving is local work and does not refetch merely because connectivity drops afterward.
- Responses are copied and validated off-thread, merged only for matching identity/cursor and an advancing next-page cursor. Refresh retains old unobserved rows and explicitly marks them not revalidated online; overflow refuses the merge instead of evicting saved rows. Explicit parent IDs survive copying, serialization and parent-aware ordering, with cycle/depth rejection.
- Private versioned binary snapshots persist account/video, author/text/saved translation, reply parent, timestamps and next cursor. Local reload combines native and Toki archives, then restores the separate translation cache. Host objects/storage remain untouched. Confirmed commits, full/unavailable persistence, missing/corrupt data and cancellation have distinct messages.
- Limits: 500 rows and 4 MiB per snapshot, at most 100 stored account/video keys and about 12M stored characters. Existing archives are not deleted for capacity. Pre-commit cancellation/account checks and bounded strict decoding reject malformed text, duplicate records, unsupported versions and trailing bytes.
- 273 source-only/whitespace checks passed; five original-DEX request/Rx method contracts plus the enum field were separately checked. New merge/cursor/parent/cancellation/codec/store regression sources are NOT executed. No compilation, JUnit/Robolectric, network service/device/airplane-mode/cold-reopen tests or APK build were performed.
- Remaining: separate reply-thread network pagination, complete native comment-button/layout integration, a bundled local translation/ASR engine, other host-code layouts and full offline subtitle/player timing. This is not verification of all native/offline-comment behavior.

## Added in 2.0.65 — explicit online cached-comment translation and persistence

- Independent default-off **Online translation for cached comments** option prepares the existing host translation extension, with live admission, core-only deferral, status reporting and service rollback. Cached reading remains independent. No user text is submitted just by enabling or opening the viewer; a row action is required, validated connectivity is checked and explicit local-only prevents submission.
- Per-window single-comment segmented translation reuses timeout, duplicate/stale guards and retry-failed-segments behavior. Progress/partial results appear in that row; original/translation/bilingual stays selectable. Unchanged service output is not saved as a new translation. Actual host target language is read and checked, not assumed Chinese. No bundled local model is added.
- Complete successful results are written on a bounded background worker to private Toki preferences and restored on a read worker when reopening comments, including disconnected use. Cache identity hashes length-prefixed account/video/comment/exact source/normalized target. Existing host snapshots/models remain read-only; missing-comment downloading is still not implemented.
- At most 2000 saved results and about 2M characters; full/failed/busy persistence is reported without deleting or evicting earlier user caches. Only a confirmed commit matching the same displayed source/target/output marks that result saved. Partial or stale results never claim complete durable success.
- Close/reload/pause/account-page changes, feature disable and explicit local-only cancel active translation. The original/translated text is not written to public diagnostics. The host translation service, initial adaptation, real latency/language behavior and cold-reopen restoration still need actual device verification.
- 252 source-only and whitespace checks passed. Session/retry/cancellation, source-account-target keying, private restore/capacity and recycled-row precise-save regression sources are added but NOT executed. No compilation, JUnit/Robolectric, device verification or APK build was performed.

## Added in 2.0.64 — saved-comment viewer

- Offline local menu action **Browse cached comments** opens an account-isolated read-only native snapshot viewer. The inspected store DEX is fingerprinted; native current-account identity selects offline_comment_<uid> and snap_<videoId>. The same host Gson/snapshot type parses persisted data, so obfuscated JSON keys are not guessed. Native predownload/network jobs are never called by this viewer.
- Snapshot video identity, native status/error codes and account stability are checked. Missing, empty, partial, corrupt, account-changed and unsupported states are distinct. Cached author names/handles, source text and saved replies are shown in a scrollable recycled list with local pages. At most 500 rows/1000 visited nodes/3 reply levels; raw JSON size/depth and Unicode-safe text lengths are bounded. End of cached rows never claims all online comments were loaded.
- Saved translations support per-row original/translation/bilingual modes with selectable body text. Missing translations retain original text and disable unavailable modes, explicitly stating no bundled local engine. No host models, snapshots, accounts or files are written/deleted. Native snapshot persistence supplies cold-reopen data only when TikTok has actually saved it.
- A bounded read worker and generation guards reject obsolete reload/closed/left-page results; account rechecks clear old window data after account switches. Existing offline feature ownership closes the comment window on page/menu-owner disposal. No avatars/images/profile links are fetched by this viewer.
- Remaining: missing-comment online fill/reply network pagination, online per-row translation with persistent results, native comment-button integration, bundled local translation and all real-device compatibility/airplane-mode tests. This batch implements a dedicated local viewer, not full native-comment replacement. Hybrid networking remains the current policy; the viewer simply has no new online loader yet.
- 233 source-only checks plus whitespace checks passed. Added data/pagination/Unicode/bounds/account and recycled-cell regression sources were NOT executed. No compilation, JUnit/Robolectric, device verification or APK build was run.

## Added in 2.0.63 — current networking policy

- The user explicitly withdrew the blanket no-network restriction and confirmed **online translation/additional data when connected, local caches when disconnected**. Offline-item classification now only chooses context/menu identity, not remote eligibility. An explicit, default-off local-data-only switch remains available. Historical .61/.62 forced-local descriptions below are superseded on this point.
- Existing translation/caption caches remain first. The module network decision checks configuration readiness, the explicit switch and validated connectivity, but not the offline-item flag. Per-operation denial remains latched; a reconnect starts a new eligible operation instead of reviving a denied old subscription. Playback identity still rejects results from another selected video.
- Visible subtitle rendering and current-timeline updates sample access at most once per second. An observed denied-to-allowed transition can retry missing translations or the missing current subtitle file; existing translated text and loaded cue tracks are retained. This is not an unconditional per-frame retry loop. Paused/no-redraw recovery still needs device validation.
- This batch does **not** add an online comment-data loader or bundled local engine. It restores eligibility for existing module translation/caption transports. Complete-local-video export still never auto-downloads missing bytes; that separate rule was not silently changed.
- 212 source-only checks and whitespace checks passed; policy/reconnect regression sources are added but NOT executed. No compilation, JUnit/Robolectric, device, reconnect/packet validation or APK build was run.

## Added in 2.0.62 (historical; forced-local default superseded above)

- Independent default-off **Offline video: local controls (experimental)** unit, live-installable without unrelated scan dependencies. Re-enter the video after enabling. Inspected named VideoViewCell/Aweme/OfflineVideoInfo contracts classify the selected holder before native selection callbacks; exact offline_mode_page is also recognized. Background/adjacent progress cannot replace the owner. Known offline denial survives unbinding until a known next selection/page exit; translation tickets bind the playback revision as well as network eligibility.
- Playing or paused offline video surfaces can open a dedicated local hold menu with complete-cache save and subtitle-settings actions. Ordinary taps/scrolling remain native; movement, multitouch, cancellation, stale holders, page exit and settings disable cancel pending holds. Interactive overlays/inputs are excluded. Native touch cancellation precedes showing the local dialog. No native share/download menu is called.
- Save reads only exact media-key MDL cache-index metadata, with a 100ms lock bound and known DEX SHA-256 gates for the two obfuscated cache-reader files. It does not invoke preload, URL resolution, download, native online export or cache deletion. Native index byte counts, actual file length, canonical storage roots, non-fragmented MP4 box structure and descriptor-based local video-track inspection are checked before copying.
- Android 10+ exports use MediaStore pending rows; publication follows successful local copy. Source length/mtime and operation eligibility are checked while copying. Source caches remain untouched; failed pending outputs are retained rather than silently deleted. Default video export directory or an eligible configured public media directory is used.
- Partial scope: unsupported code variants, API 28 export, model-based/DASH split media, encryption, fragmented/special MDL formats and alternative offline pages are NOT solved by this batch. Missing/incomplete/unsupported caches report their state without network fallback. Offline comments, persistent comment snapshots, in-cell translation toggles, local translation/ASR models and full offline subtitle/player synchronization remain incomplete.
- New policy/admission/hold/file-integrity/hit-target regression sources are unexecuted. Static checks do NOT prove native cache format compatibility, complete playback of a saved file, gesture correctness or zero remote traffic on a device. No compilation, test execution, build, device or packet inspection was performed.

## Added in 2.0.61

- A default-off **Toki translations and subtitle files: local data only** control persists through the existing configuration channel. Cached caption files and translations remain readable; misses do not gain remote fallback. Unknown/unready configuration, missing connectivity permissions/capabilities and disconnected/unvalidated networks deny module-owned remote translation/caption requests. No remote connectivity probe is used.
- Operation tickets are captured before asynchronous caption work, manual-comment sessions and subtitle queues. A ticket begun local-only or subsequently observed denied cannot become remote when Wi-Fi returns. Foreground subtitle tasks and prefetch batches guard dispatch; caption HTTP guards opening, redirects and reads; the shared translation transport guards host-service invocation.
- Revoked subtitle results are not displayed, cached or retried. Mode changes cancel subtitle subscriptions and HTTP reads. Missing local translations/engines or subtitle files get foreground notices, with source text retained. Re-entering the video is still advised after changing mode.
- Scope is deliberately narrow: **not a TikTok firewall or complete offline feature**. TikTok-native translation/background traffic, stickers/native downloads, offline video classification while connected, long-press video menus, validated local export, persistent offline comments and a local translation/ASR engine are NOT implemented by this batch. Enable the explicit local-only control when using an offline entry while the device still has a validated network.
- Added policy and subtitle cache-only/revocation regression test sources; existing subtitle fixtures explicitly opt into fake online transports. Source-only checks are not runtime privacy/compatibility certification. No compilation, JUnit/Robolectric, APK build, device or packet inspection was run.

## Added in 2.0.60

- Default-off **Manual comment translation** offers a long-press fallback viewer with an explicit route to native comment actions. It reads Comment.getText() directly, not a rendered translation, and never changes the host comment model or list.
- Original/translation/bilingual display, selectable text, segmented progress and retry-only-failed controls are wired. Requests are explicit per comment, use the existing host translation channel, and have no new disk/offline cache. A 16000-character source cap, <=700-character Unicode-safe chunks, six-second request timeouts and generation/duplicate guards bound the work. Pure numeric/emoji text is not silently excluded; unchanged service results keep the original.
- Closing, pause or disabling cancels work; reentrant rendering callbacks cannot dispatch or requeue work after close/restart; successful segments are retained only within the dialog. Manual and sticker menus share native-return scope to avoid recursive choosers. Menu and translation dependency preparation is ordered behind extension readiness; this is the 19th reversible installation unit.
- Getter signatures were inspected offline and regression sources added, not executed. This is an opt-in fallback window, not a claim that every native comment cell, bulk-translation path, language or offline page is fixed.

## Added in 2.0.59

- New default-off **Hold-to-speed gesture → Slide down to lock / unlock** option uses the inspected 47.6.3 native edge-speed gesture/state machine. It preserves native eligibility, left/right geometry, lock hints and release decisions; it does not replace whole-page touch dispatch or directly overwrite the speed manager.
- Downward-mode and legacy-mode gate overrides are synchronous/thread-local, confined to owned native calls. New sessions begin only on a native single-pointer down event. Cancel/multi-touch, root recreation, data rebind, detach, pause, disable and disposal withdraw pending state and use native noncommitting finish, with delayed-runnable/listener cleanup. Already committed user-selected speed is not reset merely by disabling the extension.
- Disabled experiment groups may return a zero threshold. Valid native values are retained; a local 72dp-or-touch-slop fallback is used only in the owned gesture, without editing experiment storage. This is the 18th reversible installation unit. First activation may need video re-entry, and only locations where TikTok already supports edge hold-speed are eligible. Fixed-speed interaction, layouts and actual gestures remain untested.
- DEX method/field/service descriptors and native branch logic were inspected offline; scope/session/threshold/admission regression sources were added but not executed. No compilation or APK build was run.

## Added in 2.0.58

- Added a primary-author-label binding path inspected directly in the 47.6.3 DEX. It decorates only the component's designated author TextView using current video/author identity. Original nickname spans are retained; owned prefix spans support disable, country changes and repeated binding without stripping similar native names. Text replaced by TikTok is not overwritten during refresh. Detached labels restore and reapply on attachment using current settings; tracking is weak and bounded to 256 labels.
- Author labels and quick-comment cleanup now prepare directly without unrelated navigation/formatter scans. Quick cleanup has one configuration owner and targets the three existing feed variants plus the inspected video-detail bottom-input component and its attach callback. It retains the old quick-comment setting key and UI location, and does not hide the comment button/sheet. Visibility ownership restores/reapplies across detach/attach, with 384 weakly tracked views.
- Exact method/field descriptors were inspected offline. Regression test sources were added, not executed. Actual profile/detail route coverage and Hook behavior remain unverified; first activation can still require re-entering the video page. Missing region data is not invented. The reversible-feature catalogue now has 17 installation units.

## Added in 2.0.57

- Read-only inventory of the original TikTok 47.6.3 APK found the real sharing and requested feed implementations absent from the partial exported source tree. Targeted CLI decompilation identified separate share action/channel adapters and their item key methods. The raw APK/DEX/decompiled output remains private and is not included in the repository.
- New **Share panel cleanup** settings provide 39 independently selectable named options, including the recommended-recipient row, repost/copy, social channels, report/dislike/save/promote/group, captions/speed/clear-screen and others. The 42 catalogue business keys were observed in constant-return key methods in the local APK. Advanced extra keys are explicit, bounded exact matches, not button text or wildcards.
- Concrete setters are hooked only within two inspected share adapter hierarchies. Filtering does not modify host lists in place or replace callbacks; tracked adapters can hot-hide and restore items from the latest native list. Panel view destruction releases tracked adapter references; the recipient row uses shared visibility ownership. First activation requires reopening the share panel once. This adds a 16th reversible installation unit; actual Hook, UI, adapter variants and target-version behavior remain untested.

## Added in 2.0.56

- A dedicated **Layout cleanup → Bottom → Bottom-of-video search suggestions** option now owns the three existing bottom search-bar targets. It does not hide top search. The legacy Search suggestion option still hides those bars; the settings hint explains how to restore them. Actual TikTok 47.6.3 implementation availability and detail/profile variants remain unverified.
- Sticker export now separates animated and static source categories, retains original bytes, and offers actual static PNG/JPEG/WebP conversion. Explicit source choices do not silently cross into the other category. Converted animation is labelled as a single static frame; original export retains the file bytes.
- Software bitmap decoding is bounded to a 2048-pixel longest edge; source dimensions and encoded byte growth are limited. JPEG conversion explicitly places white behind transparent pixels. Native-image regression sources were added but not executed. API signatures were inspected read-only in the installed Android SDK, not compiled or device-tested.

## Requirement tracking

| Requested area | Current evidence / remaining work |
|---|---|
| All supported countries, territories, states | Country picker now includes the platform ISO directory plus 163 operational presets, with explicit country-only mode and no fabricated missing environment values. Actual TikTok server/account coverage and the full state/province model remain incomplete; generic ISO/payment metadata is not proof of service support. |
| Quick-comment hiding on profile/detail and other pages | Independently installed feed variants plus real video-detail bottom-input creation/attachment targets, with reversible view ownership. Runtime, layouts and alternate variants remain unverified. |
| Author country in videos opened from a profile | New primary-author TextView binding with span preservation, current identity checks and restoration is connected from actual 47.6.3 contracts. Profile/detail variants and runtime behavior remain unverified. |
| Templates/effects | Independent direct control now observes the dedicated video-template button and selected standard-renderer roots for 13 inspected business types, with live root revalidation and restoration. Shared UI schemas are not treated as businesses. Other legacy/ambiguous links, pre-observation views without callbacks, complete screenshot routes and actual pixels/device coverage remain incomplete or unverified. |
| Individually selectable share actions | 39 named controls, exact custom keys and reversible action/channel adapter filtering now wired to locally inspected 47.6.3 contracts. Runtime, alternative panels and some unnamed actions still need verification; not a whole-panel hide substitute. |
| Settings synchronization and no-restart use | Late registration/per-key conflicts, current video-mode replay, 58-control requested-snapshot updates, confirmed saves and now live SIM/locale/timezone/GPS installation/payload application receipts are in source. Subsequent platform reads update without module startup freezing; actual bidirectional/cross-process behavior, host caches, all-page rebinding and speed/immersive/audio restoration remain incomplete and unverified. |
| Faster/reliable subtitle translation and replay | Cache/track/write paths, observed-rewind ownership, cancelled-request recovery and shared-result retrieval are wired in source. Compilation, real replay/seek/paused/language behavior and measured latency remain unverified. |
| Offline subtitles and translation persistence | Persistent caches, optional local-only and default hybrid online eligibility/reconnect handling are in source. No local engine; host offline player/time identity and full local UI still need integration. |
| Offline long-press, export/download and comments | Selected-cell local menu, indexed descriptor-based MP4 export/remux/readback and account-isolated cached-comment viewer are in source. Export can inspect complete initialized fragments and uniquely paired video/AAC tracks; real playback/cache/device validation, dispersed init/media and unresolved model-only/API-28 export remain incomplete. Other offline comment/layout integration stays deferred by latest user order. |
| Sticker format/source selection | Original bytes, static PNG/JPEG/WebP and new multi-frame GIF/WebP decode/compose/re-encode paths are wired in source with explicit palette/alpha/timing and resource limits. Actual host JNI, pixels, frame disposal, output playback and API 28–29 coverage remain unverified; APNG/animated AVIF and other codecs are not implemented. |
| Hold-to-speed, downward-slide lock/unlock | Owned native downward-mode path, default-off setting and cancellation lifecycle wired from 47.6.3 contracts. Real gestures, fixed-speed interaction, layout coverage and native-eligibility limitations remain unverified. |
| More video display modes | Six aspect-preserving modes and current-layout reapply inside an installed immersive session are wired. Window master restart, unsupported surfaces/layouts, native lifecycle recursion and actual pixels/device behavior remain unverified. |
| Search/inbox/live internal cleanup | Six independent inspected page-internal component controls are wired in source, separate from existing bottom-video search suggestions. Other variants/overlays, complete page coverage and runtime verification remain incomplete; standalone LIVE Lynx popups/windows are not covered. |
| All comments translatable and translation switching | Ordinary-comment fallback plus cached-comment per-row modes, optional explicit online translation and private persistent results are in source. Native bulk/cell coverage, special comments, deeper native interaction, local engines and all device/service behavior remain incomplete/unverified. |
| More subtitle styles | New renderer/settings/test sources present; compilation, pixel checks, device behavior and full localization unverified. |

## Verification performed

Run the source-only checker with:

    python scripts/verify-caption-source.py

The checker parses default/Simplified-Chinese XML, checks string references and preset uniqueness, inspects selected wiring, and checks lexical delimiter balance in selected Kotlin files. It **does not** compile or execute Kotlin, prove API signatures, run tests, measure translation speed, validate rendered pixels or certify Hook contracts.

Whitespace was checked with `git diff --check`. Regression cases now ran in the full 2.0.80 desktop suite: 928 passed, none failed, 3 real-media capabilities require Android and were explicitly skipped on desktop. The same three native integration assertions were compiled into the Android test target, not executed on a device.

## Publication boundaries

Source, regression sources and curated documentation are published. The user now authorizes a locally built Debug module APK after basic checks; it can be delivered locally but is not committed to Git. Signing material, credentials, original host APK/DEX/decompiled code, screenshots, raw private logs and handoff files remain excluded. No GitHub tag/release/APK upload is implied. Source commits retain `[skip ci]`; the explicitly authorized local checked build is separate from CI.
