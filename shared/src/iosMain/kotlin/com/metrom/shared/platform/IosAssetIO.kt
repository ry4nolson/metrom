package com.metrom.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
class IosAssetIO : AssetIO {
    override fun open(path: String): ByteArray? {
        val fileName = path.substringAfterLast('/')
        val name = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && !it.endsWith(fileName) }
        val urlPath = NSBundle.mainBundle.pathForResource(
            name = name,
            ofType = ext.ifEmpty { null },
            inDirectory = dir,
        ) ?: return null
        val data = NSData.dataWithContentsOfFile(urlPath) ?: return null
        val len = data.length.toInt()
        if (len <= 0) return null
        val out = ByteArray(len)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return out
    }

    override fun exists(path: String): Boolean = open(path) != null
}
