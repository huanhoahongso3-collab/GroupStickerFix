package dhp.tpl.groupstickerfix

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

/**
 * Messenger's keyboard-content handler (composer "StickerMakerPlugin", method GXH.A02 in v583)
 * only converts the pasted image into a sticker when `ThreadKey.A1H()` is true (DM thread kinds)
 * or a mobile-config gate is on; otherwise it falls back to a plain image send. We make that
 * first `ThreadKey.A1H()` check return true while the handler runs, so group chats take the
 * same sticker path as DMs.
 *
 * Obfuscated names are never hardcoded: the handler is located with DexKit by its log strings
 * and the check is the first `ThreadKey.()Z` call inside it. Results are cached per APK build.
 */
object StickerHook {
    const val TAG = "GroupStickerFix"
    const val TARGET = "com.facebook.orca"
    private const val THREAD_KEY = "com.facebook.messaging.model.threadkey.ThreadKey"

    // Alternative string sets identifying the handler, tried in order (add more for future versions).
    private val HANDLER_STRINGS = listOf(
        arrayOf("StickerMakerPluginSpec", "Error with requestPermission, commit content uri "),
        arrayOf("StickerMakerPluginSpec"),
    )

    private val armed = ThreadLocal<Boolean>()

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val cl = lp.classLoader
        val (handler, check) = resolve(lp, modulePath) ?: run {
            XposedBridge.log("$TAG: sticker handler not found in this Messenger version")
            return
        }
        XposedBridge.hookMethod(handler, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { armed.set(true) }
            override fun afterHookedMethod(param: MethodHookParam) { armed.set(false) }
        })
        XposedBridge.hookMethod(check, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (armed.get() != true) return
                armed.set(false) // only the first check inside the handler
                if (param.result == false) {
                    param.result = true
                    XposedBridge.log("$TAG: forced sticker path for non-DM thread")
                }
            }
        })
        XposedBridge.log("$TAG: hooked ${handler.declaringClass.name}.${handler.name} / ${check.name} (cl=$cl)")
    }

    private fun resolve(lp: XC_LoadPackage.LoadPackageParam, modulePath: String): Pair<Method, Method>? {
        val cl = lp.classLoader
        val apk = File(lp.appInfo.sourceDir)
        val key = "${apk.length()}-${apk.lastModified()}"
        val cache = File(lp.appInfo.dataDir, "groupstickerfix.cache")

        // 1) cache hit
        try {
            val lines = cache.takeIf { it.exists() }?.readLines()
            if (lines != null && lines.size == 3 && lines[0] == key) {
                val h = DexMethod(lines[1]).getMethodInstance(cl)
                val c = DexMethod(lines[2]).getMethodInstance(cl)
                return h to c
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: cache unusable: $t")
        }

        // 2) DexKit search
        System.load("$modulePath!/lib/${Build.SUPPORTED_ABIS[0]}/libdexkit.so")
        DexKitBridge.create(apk.absolutePath).use { bridge ->
            for (strings in HANDLER_STRINGS) {
                val candidates = bridge.findMethod { matcher { usingStrings(*strings) } }
                for (m in candidates) {
                    val chk = m.invokes.firstOrNull {
                        it.className == THREAD_KEY && it.paramTypeNames.isEmpty() && it.returnTypeName == "boolean"
                    } ?: continue
                    val hm = m.getMethodInstance(cl)
                    val cm = chk.getMethodInstance(cl)
                    try { cache.writeText("$key\n${m.descriptor}\n${chk.descriptor}\n") } catch (_: Throwable) {}
                    return hm to cm
                }
            }
        }
        return null
    }
}
