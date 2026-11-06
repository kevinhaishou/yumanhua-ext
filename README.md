# 漫画客 (yumanhua.com) → 扩展 APK

为抱走漫画（`com.example.comic`，一个 Mihon/Tachiyomi 扩展宿主）构建的插件。

- 源：漫画客 `http://yumanhua.com`（正文 packer + base64/XOR 解密已在真实页面端点验证）
- 目标运行时：宿主内置的 `eu.kanade.tachiyomi.source.online.ParsedHttpSource` 兼容层
- 编译方式：`libstub` 模块直接编译 keiyoushi/extensions-lib 1.4.2 的回落源码，`compileOnly` 不进 APK

产物在 `out/`（CI 每次构建后自动推回）。安装到抱走漫画：把 APK 放进
`/data/data/com.example.comic/app_flutter/plugins/`，命名 `mihon_eu.kanade.tachiyomi.extension.zh.yumanhua_1.0.0.apk`。
