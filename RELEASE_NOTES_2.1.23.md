# Toki 字幕版 2.1.23 正式构建

日期：2026-11-08（Asia/Tokyo）。versionCode：143。

## 重要限制

**此版本无法在无 Root 环境下使用。需要 Root 并通过 LSPosed 加载 Toki。**

LSPatch／无 Root 本地模式下的实际模块加载仍未恢复；独立管理端显示框架连接或已激活，不等于 TikTok 内模块与功能已经生效。不要把本版当作无 Root 修复版，也不要因此重新处理、替换 TikTok 或安装另一套管理器。独立 Toki 的更新检查不依赖 Root。

## 新增功能

- 内置正式版本检查：首次打开 Toki 自动检查一次，之后间隔一个月、在下一次打开应用时检查。没有后台定时服务，不会为检查更新唤醒 TikTok。
- 每个新版本自动只提示一次；没有新版本、网络错误或接口不可用时，自动检查不弹窗、不发通知。检查记录和已提示版本独立持久保存，重启或重开应用不重复检查／提醒。
- 可随时点击“手动检查更新”；没有新版或检查失败仅在页面内显示结果，不额外弹窗。手动操作可重新查看同一个新版。
- 新版提示显示该版本的新增功能与 Bug 修复说明，用户主动点击后才打开官方 GitHub Release 页面。不会后台下载 APK、自动安装或请求卸载旧版。
- 首页明确展示本版需要 Root；可手动查看本版更新说明。

## 汇总修复

- Toki 本体进程重建后，主动向现有支持该接口的管理器请求恢复自身框架连接；限制重试次数、避免主线程等待并释放绑定。此项只修管理端连接，**不代表修复无 Root 注入**。
- 配置发布使用明确的事务确认结果，连接状态与配置同步状态分开记录；失败时保留本地设置。
- 原生字幕先送空 EXPANDED 帧、随后异步写入正文时，保留当前字幕所有者的监听，避免该路径断开观察。明确隐藏及换视频时仍释放旧所有者，避免旧字幕复活。
- 评论列表按快照建立父评论、回复及正文身份索引，减少逐行重复扫描。该优化不等同于修复所有评论抓取或网络翻译耗时。

## 升级与数据

- 使用既有正式签名构建；版本、签名、非调试标识、对齐及内外模块入口在产物生成后单独核验。
- 正式签名与 Debug 签名不同。不能直接覆盖 Debug 版；本次不会通过卸载、清数据来强行安装。不要为覆盖安装而删除已有配置、缓存、字幕、评论或媒体。
- 生产构建不重置功能设置，不清理旧数据，不更改网络、TikTok 或 LSPatch。
- 用户已明确授权上传。仅在全部测试与正式签名校验完成后发布 GitHub Release；只上传 Toki 正式 APK、校验文件和本发行说明，不上传用户 APK、日志、签名密钥或其他私有文件。

## 构建与测试结果

- 更新相关37项定向测试全部通过；全量1441项0失败、3项既有环境跳过。lint无未基线错误、148警告；既有389条翻译基线未扩大。
- 内外运行时R8优化与资源裁剪、不可调试、版本143／2.1.23、ZIP CRC、16KiB对齐均核验通过；与2.0.51正式证书一致。
- APK：`Toki-Subtitle-2.1.23-universal-release.apk`；11091101字节。
- SHA256：`d25b927415ea7c55ea3e0b96f64fe101d8c7d2c7efc66b304eab2349a3632a1f`。
- 没有在当前不同签名Debug手机卸载／清数据安装正式版。无Root仍不可用；桌面测试和签名通过不等于所有Root真机功能已逐项验证。

## 验证边界

新增更新策略、解析、网络边界、持久化、界面和完整回归按实际测试结果记录。正式 APK 构建和签名成功不等于所有 TikTok 版本的真机功能均已验证。无 Root 仍不可用，不以绿色状态或单测通过替代实际验收。

---

# Toki Subtitle 2.1.23 — Production build

**This version cannot be used without root. A rooted device with LSPosed is required. Rootless injection remains unavailable.** The standalone update checker does not require root.

New: one automatic check on first opening, then at most once per month when Toki is opened; one automatic notice per newer stable version; silent automatic no-update/failure results; explicit manual checking; new-feature and bug-fix notes; user-initiated official release-page navigation only.

Included fixes: companion reconnection after process recreation, acknowledged configuration publication, caption observation retained after empty expanded frames, and indexed comment reading. These fixes do not restore rootless injection or imply that every runtime feature has been verified.

The APK uses the existing production identity, is optimized and non-debuggable, and is not installed by uninstalling a differently signed Debug build. No host/framework package, network configuration, or existing user data is changed. Publication was explicitly authorized by the user. Only verified Toki release artifacts and notes are uploaded; private device reports, third-party APKs, and signing keys are excluded.
