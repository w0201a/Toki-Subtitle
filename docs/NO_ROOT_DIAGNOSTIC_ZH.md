# 无Root模式：报告定位与验证边界（2026-11-07）

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

## 487原管理器到件后的验证（2026-11-08，未安装）

- 已核对用户提供的原管理器：org.lsposed.lspatch／1.2／487；原APK和报告ZIP保持不变，未覆盖安装／卸载／清数据。
- 实际loader自带1075个Kotlin/kotlinx类，仍被宿主父加载器同名类抢先解析。新增`scripts/prepare-lspatch-kotlin-loader.py`，将框架自带运行库隔离为kotlim/kotlimx，保留DEX所有偏移／大小／排序，重算SHA1和Adler32；8项回归覆盖正确生成、损坏输入、命名空间冲突及排序越界，拒绝覆盖输出。
- 使用准确487的真实loader在Android13独立ART进程重放：旧宿主Intrinsics父加载器下原loader初始化真实VectorInlinedCallers失败，错误精确为报告中的checkNotNull(Object)；隔离loader初始化成功并执行自有方法，宿主类未改写。这不是只有JVM模拟，但仍不是华为API29／完整框架／全部模块端到端验收。
- 原管理器作者签名与内置公开补丁签名不同，未找到可用于原位更新管理器的同证书签名材料；没有生成冒充可覆盖的管理器APK。未向模块硬塞宿主Kotlin方法，未修改宿主类。
- 本地保留加载器候选ZIP、原始/隔离DEX、Android ART证据和复现脚本；不上传用户APK／日志／数据库。候选ZIP是技术修复材料，不是可直接安装的APK。
- **下一必需输入：这台无Root手机当前已打补丁TikTok的完整安装APK。** 需要核对当前Local／Manager模式、实际嵌入模块、loader与签名，再判断能否同证书覆盖修复。原报告最后补丁记录是Local且嵌入0模块，而487的LocalApplicationService实际只枚举assets/lspatch/modules；不能把管理器勾选记录当成Local已嵌入Toki。报告同时含多应用／多次补丁记录，最终结论必须以当前宿主APK为准。

## 新报告补充：框架在模块入口前失败（2.1.20源码回合）

新报告的API29／LSPatch 1.2(487)／Vector canary-3106环境仍不能使用。Shizuku授权和Binder均为true，不能再归因为未授权。

### 本次明确阻断

新的verbose日志有4次独立启动，在Vector自己的`VectorInlinedCallers.<clinit>`中调用`kotlin.jvm.internal.Intrinsics.checkNotNull(Object)`失败；异常指出该类来自TikTok的base.apk。调用链为：

```text
NoSuchMethodError: kotlin.jvm.internal.Intrinsics.checkNotNull(Object)
  VectorInlinedCallers.<clinit>
  VectorDeopter.deoptBootMethods
  VectorStartup.init
  org.matrix.vector.Startup.initXposed
  org.lsposed.lspatch.loader.LSPApplication.onLoad
```

这是框架启动、尚未到Toki模块入口的失败。新日志中存在139 APK文件传输记录，不能据此确认139已被加载；没有读取到139模块入口运行证据。报告还打包了旧framework日志中的模板启动崩溃，旧记录与新阻断分别归档，不能混为139回归。

### 已做的验证，不扩大结论

- 只读ZIP分类器`scripts/analyze-lspatch-report.py`对新旧日志分组、同时间的重复堆栈去重，不输出原始日志、视频／账号／请求头或凭据；5项Python回归通过。
- 本地最小JVM复现：父加载器先提供旧宿主Intrinsics时，框架入口复现相同NoSuchMethodError；父加载器隔离到平台后最小入口成功，宿主类本身不改写。这只是类加载机制复现，不是Android／华为验收。
- 核对JingMatrix/LSPatch主源码的`patch-loader/src/main/jni/src/patch_loader.cpp`，框架加载器的父链仍连接宿主stub加载器。已在本地work目录保存一份“隔离到boot父加载器”的未应用上游候选补丁。
- 没有重建、签名、覆盖安装或验收新的LSPatch管理器／宿主补丁包；当前工作区没有该框架的NDK构建环境，报告设备也不在ADB中。候选补丁不当作已修复安装包。
- 未发现足够证据证明仅换上游canary即可修复此特定Kotlin冲突，因此不宣称“升级492就好”。更换管理器与给TikTok重新注入修正后的loader是两件事。

### 需要修复的层与安全边界

修复点是LSPatch／Vector的框架ClassLoader隔离，或框架自带Kotlin运行库的命名空间重定位；不是在Toki设置中加一个开关。不要向TikTok的Kotlin类硬塞缺失方法、不要在模块中吞掉框架错误，也不要卸载／清数据来掩盖失败。

下一步需要精确匹配正在使用的487管理器／加载器APK或其对应源码版本，再构建并验证loader修复以及报告手机上的注入与模块入口。仅给Toki 140不能保证这个入口前阻断消失。

用户报告“模块一会显示可用一会不行”，并提供LSPatch导出报告。

## 不应混为一谈的三个状态

1. LSPatch补丁已生成/安装：报告有成功记录，Shizuku授权有效。
2. 模块管理端绑定框架：Binder真实死亡后必须显示断开，不能永远绿色。
3. TikTok中的功能实际运行：仍需宿主启动及功能路径验证；仅管理端有Binder不能证明功能正常。

## 报告明确证据

- 环境为Android10/API29、LSPatch1.2(487)、Vector API102。
- 模板功能因宿主方法不匹配被撤销后再次安装，抛出IllegalStateException，报告中7次在Application创建期间导致宿主主线程崩溃。
- 华为iAware低内存清理及强制停止LSPatch真实存在，模块进程死亡还触发了提供者依赖导致的管理器退出。模块内不能通过伪造存活修复系统回收。
- 有XposedService提供者解析失败记录。当前构建清单包含正确导出提供者；是否为报告设备当时的安装/进程/包管理状态异常，需要现场确认，不能直接断言是清单缺失。
- libxposed service102的实际字节码中，每个死亡回调携带原服务对象，旧回调可晚于新绑定；原模块未区分两者。

## 2.1.16-debug /136 的模块侧修复（本地回归及构建已验证，报告设备未端到端复测）

- 失败的功能不重新启用；重复安装请求保留错误并跳过，不能再把可恢复的方法不匹配升级为宿主启动崩溃。
- 后续安装计划排除已拒绝功能。
- 只有当前服务对象的死亡可以清除当前框架/配置状态；旧Binder死亡忽略，真实断连仍立即如实显示。
- 远程元数据异常受控，传统加载提示使用实际版本号，去掉2.0.37硬编码。

当前ADB连接是另一台Android13设备，不是报告中的API29华为。API29本地回归、APK边界检查、连接设备验证与华为LSPatch端到端复测必须分别报告。

补充版本证据：报告宿主EmergencyConfigFetcher/Forest原生日志包含47.4.16，当前ADB宿主为47.6.3。模板等直接方法契约不能把两版本视为相同；修复启动容错不等于所有功能已适配47.4.16。

## 已完成的本地回归

本次同源码195项定向回归：0失败、0错误、0跳过。HookRuntimeOwnership含API29和API35两套运行，报告中的注册失败后重复安装路径已在API29重放；旧Binder死亡、当前Binder死亡、新绑定及对象身份区别均有回归。911项源码检查通过。最终APK构建和实际设备加载结果另记，不能由本段推定。

报告的重补丁请求Mode=Local，Modules to embed=0；管理器生命周期和模块进程绑定都需要独立检查。没有在报告设备上擅自清数据、重新补丁或调整电池/后台权限。

版本纠正：先前将重构测试包提前标为2.2.x，不符合满100进1。136修复包应为2.1.16-debug；旧误标APK保留，绝不仅改文件名冒充重打包。

最终正确包：`Toki-2.1.16-debug.apk`，88978031字节，SHA256 `2455762fa0dd7edab59c7a992891409987b982a723b516fc67966d0f6830db74`。外层及内嵌版本/CRC/签名/同证书/16KiB对齐、关键类和XposedService提供者验证通过；597份生产输入无漂移。195项功能回归在版本名纠正前完成，功能源码未更改；不把这些结果等同华为无Root实际运行。
