package dhp.tpl.groupstickerfix

import de.robv.android.xposed.XSharedPreferences

/** Tweak list shared by the settings screen (module process) and the hooks (target process). */
object Prefs {
    const val FILE = "tweaks"
    const val MODULE_PKG = "dhp.tpl.groupstickerfix"

    const val MESSENGER_STICKER = "messenger_sticker"
    const val MESSENGER_GPHOTOS_MULTI = "messenger_gphotos_multi"
    const val MESSENGER_NATIVE_PICKER = "messenger_native_picker"
    const val ZALO_STICKER = "zalo_sticker"
    const val ZALO_NATIVE_PICKER = "zalo_native_picker"

    class Tweak(val key: String, val title: String, val summary: String)

    val TWEAKS = listOf(
        Tweak(MESSENGER_STICKER, "Messenger: stickers in groups", "Send keyboard / pasted stickers as stickers in group chats"),
        Tweak(MESSENGER_GPHOTOS_MULTI, "Messenger: multi-select Google Photos", "Pick several photos in Google Photos and send them at once, without the edit screen"),
        Tweak(MESSENGER_NATIVE_PICKER, "Messenger: Android photo picker", "The image button opens Android's own photo picker (single or multiple) and sends the picks directly"),
        Tweak(ZALO_STICKER, "Zalo: stickers", "Send keyboard, pasted and dragged stickers as stickers"),
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
