package dhp.tpl.groupstickerfix

import de.robv.android.xposed.XSharedPreferences

/** Tweak list shared by the settings screen (module process) and the hooks (target process). */
object Prefs {
    const val FILE = "tweaks"
    const val MODULE_PKG = "dhp.tpl.groupstickerfix"

    const val MESSENGER_STICKER = "messenger_sticker"
    const val MESSENGER_ALL_IMAGES = "messenger_all_images"
    const val MESSENGER_GPHOTOS_MULTI = "messenger_gphotos_multi"
    const val ZALO_STICKER = "zalo_sticker"
    const val ZALO_ALL_IMAGES = "zalo_all_images"

    class Tweak(val key: String, val title: String, val summary: String)

    val TWEAKS = listOf(
        Tweak(MESSENGER_STICKER, "Messenger: stickers in groups", "Send keyboard / pasted stickers as stickers in group chats"),
        Tweak(MESSENGER_ALL_IMAGES, "Messenger: show all images", "Gallery lists every image type (webp, heic, ...), not only jpeg / png / gif"),
        Tweak(MESSENGER_GPHOTOS_MULTI, "Messenger: multi-select Google Photos", "Pick several photos in Google Photos and send them at once, without the edit screen"),
        Tweak(ZALO_STICKER, "Zalo: stickers", "Send keyboard, pasted and dragged stickers as stickers"),
        Tweak(ZALO_ALL_IMAGES, "Zalo: show all images", "Gallery lists every image type, including gif and heic"),
    )
}

/** Hook side: reads the module's prefs file from inside the target app, re-reading on every call. */
object HookPrefs {
    private val xprefs by lazy { XSharedPreferences(Prefs.MODULE_PKG, Prefs.FILE) }

    fun enabled(key: String): Boolean = try {
        if (xprefs.hasFileChanged()) xprefs.reload()
        xprefs.getBoolean(key, true)
    } catch (_: Throwable) {
        true
    }
}
