package com.metrom.app.platform

import android.content.Context
import com.metrom.shared.platform.AssetIO

class AndroidAssetIO(context: Context) : AssetIO {
    private val assets = context.applicationContext.assets

    override fun open(path: String): ByteArray? =
        runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()

    override fun exists(path: String): Boolean =
        runCatching { assets.open(path).use { true } }.getOrDefault(false)
}
