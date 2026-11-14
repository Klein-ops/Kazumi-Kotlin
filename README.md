# Kazumi · Kotlin 原生移植版

> 本项目是基于开源项目 [Kazumi](https://github.com/Predidit/Kazumi) 的**非官方 Android 原生移植版**，
> 使用 Kotlin + Jetpack Compose 重写。与原作者无隶属关系，也未获得其背书。
> 原项目及其所有贡献者保留其著作权，详见 [NOTICE](NOTICE)。

## 这是什么

**Kazumi 是一款番剧聚合播放器**：把分散在几十个视频站点的番剧，统一收进一个应用里搜索、追番、在线观看、下载。

它不自己存任何视频——所有内容都来自第三方网站。它的核心能力是**规则引擎**：用几条 XPath 选择器就能定义一个视频源，把"某个网站怎么搜、详情页长啥样、播放接口在哪"描述清楚。装上规则，Kazumi 就能从这个网站采集番剧。

本仓库是 Kazumi 的 **Android 原生重写版**：不含任何 Flutter / Dart 代码，界面、播放、下载、规则引擎全部用 Kotlin 重新实现，专为安卓设备优化。

## 它能做什么

| 能力 | 说明 |
|---|---|
| 🎬 **在线观看** | 多线路聚合播放：同一部番剧，自动汇总所有源站线路，一个源挂了自动换下一个。HLS、无扩展名 MP4 直链都能播 |
| 🔍 **聚合搜索** | 一次输入，同时搜遍所有已装的视频源；还支持**以图搜番**（上传/粘贴截图，trace.moe 识别是哪部番） |
| 📅 **番剧时间线** | Bangumi 每日放送表，今天更新什么一目了然 |
| 💬 **弹幕** | 弹弹 play 弹幕源，看番不孤单；支持弹幕屏蔽、倍速、手势、截图、同步播放 |
| ⭐ **追番收藏** | 本地 Room 收藏 + Bangumi 账号同步 + WebDAV 同步，换设备不丢追番进度 |
| 📥 **下载** | 多任务下载、后台服务、断点续传，离线也能看 |
| 🧩 **规则生态** | XPath 规则定义视频源，支持规则导入/分享；社区有大量现成规则可用 |
| 🚀 **图片加速** | 封面图走 ECH（加密 SNI）直连加速，绕过污染；磁盘缓存，二次打开秒出 |

## 快速开始

**安装正式版**：去 [Releases](https://github.com/Klein-ops/Kazumi-Kotlin/releases) 下载最新的 APK 安装即可（Android 7.0+）。

**第一次使用**：

1. 打开 App，先进「设置 → 规则仓库」，导入视频源规则（社区规则见上游 Kazumi 的规则仓库）
2. 回首页，「番剧时间线」看今天更新；「搜索」试搜一部想看的番
3. 点进详情页，选一个源站线路，开始观看

## 仓库内容速览

```
app/src/main/java/com/kazumi/port/
├── KazumiApp.kt          # 全局装配与初始化
├── MainActivity.kt       # 应用入口
├── data/                 # 数据层：模型、仓库、Room、DataStore
├── network/              # 网络层：OkHttp、Bangumi、弹幕、图片加速（ECH）
├── player/               # 播放层：ExoPlayer 控制器（播放/seek/弹幕/截图/同步）
├── service/              # 服务层：下载、WebView 视频源解析、WebDAV、Bangumi 同步
├── ui/                   # 界面层：Compose 页面、组件、主题、导航
└── util/                 # 工具类与日志
```

## 自己构建

环境要求：

- JDK 17+
- Android SDK（compileSdk 35、minSdk 24、targetSdk 35）
- Gradle 使用仓库自带的 Wrapper，无需单独安装

```bash
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK（需自行配置签名）
```

产物位于 `app/build/outputs/apk/`。`local.properties`（`sdk.dir=...`）需按本机 SDK 路径自行创建。

## 与上游的差异

- 仅支持 Android；上游的 Windows / macOS / Linux / iOS / HarmonyOS 端不在本仓库范围内
- UI 按 Android / Material 3 重新设计，交互与上游并不逐像素一致
- 部分平台能力（如 Anime4K 实时超分、MPV 内核特性）未移植，播放基于 ExoPlayer

## 开源协议

本项目沿用了上游的 **GNU General Public License v3.0**，全文见 [LICENSE](LICENSE)。

这意味着：你可以自由使用、修改、分发本项目，但分发时必须：

1. 保留 `LICENSE`、`NOTICE` 与原作者版权声明；
2. 标注你对代码所做的修改；
3. 以 GPL-3.0 提供完整的对应源代码。

## 致谢

- [Predidit/Kazumi](https://github.com/Predidit/Kazumi) 及所有贡献者：本项目的全部设计、规则格式与功能均来自上游
- [XpathSelector](https://github.com/simonkimi/xpath_selector)、[media-kit](https://github.com/media-kit/media-kit)、
  [Anime4K](https://github.com/bloc97/Anime4K)、[SyncPlay](https://github.com/Syncplay/syncplay)、
  [trace.moe](https://trace.moe) 等上游所依赖的开源项目
- AndroidX、Kotlin、OkHttp、Coil、Room 等 Android 生态开源项目

## 免责声明

本项目仅用于技术学习与交流，不提供、不存储任何视频内容，所有内容均来自第三方网站。
请遵守你所在地区的法律法规，不得用于任何侵犯第三方知识产权的行为。
在法律允许的最大范围内，作者与贡献者不对使用本软件造成的任何直接或间接损失承担责任。