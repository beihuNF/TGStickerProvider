![header](./header.png)

## Telegram Stickers Provider
A Xposed/LSPosed plugin to dump stickers from Telegram. Almost all 3rd-party telegram clients are supported.

![screenshot](./screenshot.jpg)

### Release
You can download the latest release [here](https://t.me/microblock_pub/159).

### 兼容性（Compatibility）

支持以下官方及主流基于 Telegram Android（MTProto / `cache4.db`）的第三方客户端：

✅ Telegram（Google Play / 官网直装 Web / Beta）  
✅ Nekogram / Nekogram Beta  
✅ NekoX  
✅ exteraGram / exteraGram Beta  
✅ AyuGram  
✅ Cherrygram / Cherrygram Beta  
✅ Nagram / NagramX / NagramX Turbo / Nnngram  
✅ OctoGram  
✅ Nullgram  
✅ MDGram  
✅ Forkgram / Forkgram Beta  
✅ Momogram  

❎ Telegram X（基于 TDLib，及其所有衍生客户端均**不支持**）

### 使用限制（Limitations）
- 同时安装并勾选多个 Telegram 客户端时，可在应用内选择其中一个活跃客户端作为首选下载宿主。
- **不支持 Telegram X 及任何基于 TDLib 的客户端**，因为其本地缓存与数据库架构与官方 Telegram Android 客户端完全不同。

### Credits

This project used [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) and is greatly dependent on the source code of [Telegram Android](https://github.com/DrKLO/Telegram). Thanks!
