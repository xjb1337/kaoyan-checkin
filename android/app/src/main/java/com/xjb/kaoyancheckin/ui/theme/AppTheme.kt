package com.xjb.kaoyancheckin.ui.theme

/** Names are persisted on this device; their order is not part of the storage format. */
enum class AppTheme(val label: String) {
    WEB_BLUE("晨读蓝"),
    INK_TEAL("砚青"),
    WARM_APRICOT("暖杏"),
    NIGHT_VIOLET("夜读紫"),
    BAMBOO_GREEN("竹青");

    companion object {
        val DEFAULT = WEB_BLUE
        fun from(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
