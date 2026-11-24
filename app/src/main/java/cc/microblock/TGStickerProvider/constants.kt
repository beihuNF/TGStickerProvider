package cc.microblock.TGStickerProvider

import android.os.Environment

// 动态获取外部存储根目录，避免硬编码 /storage/self/primary
private val externalStoragePath: String
    get() = runCatching { Environment.getExternalStorageDirectory().absolutePath }
        .getOrDefault("/storage/self/primary")

val exposedPath: String
    get() = "$externalStoragePath/Documents/TGStickersExported"
val realDataPath: String
    get() = "$externalStoragePath/Android/media/com.tencent.mobileqq/TGStickersExported/v1"
val destDataPath: String
    get() = "$exposedPath/cache/"
val tgspDataPath: String
    get() = "$exposedPath/mb_TGSPData"
val stickerDataPath: String
    get() = "$tgspDataPath/sticker/"
val syncFlagsPath: String
    get() = "$tgspDataPath/stickerSyncFlags/"
val nomediaPath: String
    get() = "$exposedPath/.nomedia"
val nomediaPath2: String
    get() = "$realDataPath/.nomedia"
