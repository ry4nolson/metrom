package com.metrom.shared

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString
