# TikTok 内全部 Toki 注入界面的统一样式预览

2026-11-06。用户明确范围为 TikTok 内的所有 Toki 注入界面，不限于评论区。当前交付为可交互的设计预览；没有改动 Android 生产代码、构建 APK、安装、启动 TikTok 或修改手机开关。

预览文件：`docs/ui/toki-injected-preview.html`。支持浅色／深色、设置类别切换、评论原文／译文／双语切换、回复展开收起，以及保存格式弹窗。所有内容为演示数据；页面没有网络请求，不访问手机或真实缓存。

## 覆盖范围

| 界面 | 统一方式 | 现有入口 |
|---|---|---|
| 主设置与二级设置 | 分组列表、统一图标、标题与辅助说明、触控尺寸 | HostSettingsDialog、DashboardScreen |
| 字幕与翻译 | 字体、颜色、位置、原文译文顺序和翻译弹窗 | SubtitleDialog、ManualCommentDialog |
| 界面净化、播放控制 | 共用开关与分组行；保留每个选项的语义 | 现有功能设置组件 |
| 下载与离线 | 设置列表与数据状态分开；明确实际可读数量 | OfflineVideoSection、OfflineCommentStatusSection |
| 离线评论 | 全局显示模式、紧凑头像行、回复层级和媒体区 | OfflineCommentsDialog、OfflineCommentListAdapter |
| 保存／转换／选择弹窗 | 统一圆角、动作列表、确认按钮和深浅色 | CommentStickerDownloadHook、OfflineCommentMediaViews、OfflineCommentLimitDialog |
| 日志、适配与诊断 | 共用弹窗与操作层级 | HostScanDialog、ManualHostAdaptationDialog |

下载参考保存在忽略目录 `work/ui-templates-20261106/`：

- Jetchat：Google Android Compose Samples 的会话布局源码、官方截图、代码及素材许可。参考头像、正文、回复层级。
- Reply：同仓库的列表项源码、官方截图、代码及素材许可。参考设置分组、列表间距与 Material 配色。
- Material Bottom Sheet：Material Components Android 的底部弹窗示例源码及 Apache 2.0 许可。参考保存、转换与选择器容器。
- 另外下载了 Flowbite Typography 的小型入口及 MIT 许可，但它不是评论模板，未选作本方案。

原始下载链接、文件大小与 SHA-256 在本地 `download-manifest.json` 中；第三方原始源码和截图没有复制进生产代码或上传仓库。当前预览为自行编写的 HTML/CSS，未直接嵌入第三方源码或截图。

## 验证与未完成

已使用无窗口的本地浏览器渲染浅色、深色及保存弹窗，并检查主设置类别切换、原文切换、回复收起和窄屏布局；渲染期间阻止外部网络，结束后关闭专用浏览器。

真实 Android 界面尚未应用这套样式，预览中的开关不会修改实际设置。现有 2.1.0 的“后台持续下载但只有20条、数量不符、没有回复／图片贴图”仍是未解决问题，不能用设计预览宣称修复。设备没有通过 ADB 连接，本轮未读取到真实任务、流量或缓存证据；后续先核对下载、落盘和列表读取是否对应同一个视频及分页游标，并补充无进度停止／请求限额，避免盲目重复下载。
