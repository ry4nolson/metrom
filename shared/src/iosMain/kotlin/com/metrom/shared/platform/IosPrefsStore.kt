package com.metrom.shared.platform

import platform.Foundation.NSUserDefaults

class IosPrefsStore : PrefsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getInt(key: String, default: Int): Int {
        if (defaults.objectForKey(key) == null) return default
        return defaults.integerForKey(key).toInt()
    }

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    override fun getFloat(key: String, default: Float): Float {
        if (defaults.objectForKey(key) == null) return default
        return defaults.floatForKey(key)
    }

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean {
        if (defaults.objectForKey(key) == null) return default
        return defaults.boolForKey(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null
}
