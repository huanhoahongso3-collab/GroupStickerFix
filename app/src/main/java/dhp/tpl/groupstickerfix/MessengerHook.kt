package dhp.tpl.groupstickerfix

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Messenger's keyboard-content handler (composer "StickerMakerPlugin") only converts the pasted image
 * into a sticker when `ThreadKey.A1H()` is true (DM thread kinds) or a mobile-config gate is on;
 * otherwise it falls back to a plain image send. We make that first `ThreadKey.A1H()` check return
 * true while the handler runs, so group chats take the same sticker path as DMs.
 *
 * The handler is located with DexKit by its log strings; the check is the first `ThreadKey.()Z`
 * call inside it.
 */
object MessengerHook {
    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG
    private const val THREAD_KEY = "com.facebook.messaging.model.threadkey.ThreadKey"

    private val HANDLER_STRINGS = listOf(
        arrayOf("StickerMakerPluginSpec", "Error with requestPermission, commit content uri "),
        arrayOf("StickerMakerPluginSpec"),
    )

    private val armed = ThreadLocal<Boolean>()

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val (handler, check) = Resolver.resolve(lp, modulePath, 2) { bridge ->
            for (strings in HANDLER_STRINGS) {
                for (m in bridge.findMethod { matcher { usingStrings(*strings) } }) {
                    val chk = m.invokes.firstOrNull {
                        it.className == THREAD_KEY && it.paramTypeNames.isEmpty() && it.returnTypeName == "boolean"
                    } ?: continue
                    return@resolve listOf(m, chk)
                }
            }
            null
        } ?: run {
            XposedBridge.log("$TAG: [messenger] sticker handler not found in this version")
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
                    XposedBridge.log("$TAG: [messenger] forced sticker path for non-DM thread")
                }
            }
        })
        XposedBridge.log("$TAG: [messenger] hooked ${handler.declaringClass.name}.${handler.name} / ${check.name}")
    }
}
