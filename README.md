# Kazumi · Kotlin 原生移植版

> 本项目是基于开源项目 [Kazumi](https://github.com/Predidit/Kazumi) 的**非官方 Android 原生移植版**，
> 使用 Kotlin + Jetpack Compose 重写。与原作者无隶属关系，也未获得其背书。
> 原项目及其所有贡献者保留其著作权，详见 [NOTICE](NOTICE)。

## 项目简介

Kazumi 是一个基于自定义规则的番剧采集与在线观看应用：用几条 XPath 选择器就能定义一个视频源，
支持规则导入/分享、番剧时间线、多源搜索、收藏同步与下载。

本仓库是它的 Android 原生重写版：**不含任何 Flutter / Dart 代码**，界面、播放、
下载、规则引擎全部使用 Kotlin 重新实现。

## 功能

| 模块 | 说明 |
|---|---|
| 规则引擎 | XPath 规则解析，支持搜索 / 详情 / 播放线路 / 弹幕源 |
| 视频源解析 | 隐藏 WebView + JS 注入 + 原生请求嗅探（含 `Range: bytes=` 直链识别） |
| 播放 | Media3 / ExoPlayer，HLS 与无扩展名 MP4 直链，弹幕、倍速、手势、截图、同步播放 |
| 番剧时间线 | Bangumi 数据源，每日放送表 |
| 搜索 | 多源聚合搜索、图片搜番（trace.moe） |
| 收藏 | 本地 Room + Bangumi 同步 + WebDAV 同步 |
| 下载 | 多任务下载、后台服务、断点续传 |
| 设置 | DataStore 持久化、代理、播放偏好、主题 |

## 技术栈

- **语言**：100% Kotlin
- **UI**：Jetpack Compose + Material 3 + Navigation Compose
- **架构**：MVVM（ViewModel + Repository + Room）
- **网络**：OkHttp + Gson + Jsoup
- **播放**：AndroidX Media3（ExoPlayer），支持 HLS / Progressive
- **存储**：Room + DataStore
- **构建**：Gradle（Kotlin DSL）+ KSP

## 构建

环境要求：

- JDK 17+
- Android SDK（compileSdk 35、minSdk 24、targetSdk 35）
- Gradle 使用仓库自带的 Wrapper，无需单独安装

```bash
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK（需自行配置签名）
```

产物位于 `app/build/outputs/apk/`。

> `local.properties`（`sdk.dir=...`）需要按本机 SDK 路径自行创建，仓库不包含该文件。

## 项目结构

```
app/src/main/java/com/kazumi/port/
├── KazumiApp.kt          # 全局装配与初始化
├── MainActivity.kt       # Compose 入口
├── data/                 # 模型、仓库、Room、DataStore
├── network/              # OkHttp、Bangumi、弹幕、图片搜索
├── player/               # ExoPlayer 控制器（播放/seek/弹幕/截图/同步）
├── service/              # 下载、WebView 视频源解析、WebDAV、Bangumi 同步
├── ui/                   # Compose 页面、组件、主题、导航
└── util/                 # 工具类与日志
```

- 包名：`com.kazumi.port`
- 版本：1.0.0（versionCode 1）

## 与上游的差异

- 仅支持 Android；上游的 Windows / macOS / Linux / iOS / HarmonyOS 端不在本仓库范围内。
- UI 按 Android / Material 3 重新设计，交互与上游并不逐像素一致。
- 部分平台能力（如 Anime4K 实时超分、MPV 内核特性）未移植，播放基于 ExoPlayer。

## 开源协议

本项目沿用了上游的 **GNU General Public License v3.0**，全文见 [LICENSE](LICENSE)。

这意味着：你可以自由使用、修改、分发本项目，但分发时必须：

1. 保留 `LICENSE`、`NOTICE` 与原作者版权声明；
2. 标注你对代码所做的修改；
3. 以 GPL-3.0 提供完整的对应源代码。

## 致谢

- [Predidit/Kazumi](https://github.com/Predidit/Kazumi) 及所有贡献者：本项目的全部设计、规则格式与功能均来自上游。
- [XpathSelector](https://github.com/simonkimi/xpath_selector)、[media-kit](https://github.com/media-kit/media-kit)、
  [Anime4K](https://github.com/bloc97/Anime4K)、[SyncPlay](https://github.com/Syncplay/syncplay)、
  [trace.moe](https://trace.moe) 等上游所依赖的开源项目。
- AndroidX、Kotlin、OkHttp、Coil、Room 等 Android 生态开源项目。

## 免责声明

本项目仅用于技术学习与交流，不提供、不存储任何视频内容，所有内容均来自第三方网站。
请遵守你所在地区的法律法规，不得用于任何侵犯第三方知识产权的行为。
在法律允许的最大范围内，作者与贡献者不对使用本软件造成的任何直接或间接损失承担责任。