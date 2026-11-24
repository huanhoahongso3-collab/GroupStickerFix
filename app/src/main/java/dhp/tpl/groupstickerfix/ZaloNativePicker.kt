package dhp.tpl.groupstickerfix

import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

/** Zalo: native photo picker for the chat image button (work in progress: currently only logs what the button does). */
object ZaloNativePicker {
    private const val TAG = Resolver.TAG
    private const val QUICK_PICKER = "com.zing.zalo.ui.picker.QuickPickerView"

    private val seen = HashSet<String>()
    private var budget = 300

    private fun log(line: String) {
        synchronized(seen) {
            if (budget <= 0 || !seen.add(line)) return
            budget--
        }
        XposedBridge.log("$TAG: [zalo] $line")
    }

    fun install(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as View
                    val name = try { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "-" } catch (_: Throwable) { "?" }
                    log("click ${v.javaClass.name} id=$name desc=${v.contentDescription}")
                }
            })

            val cls = XposedHelpers.findClass(QUICK_PICKER, lp.classLoader)
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) || m.parameterCount > 2) continue
                if (m.parameterTypes.any { it == MotionEvent::class.java || View::class.java.isAssignableFrom(it) }) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("qp.${m.name}(${param.args.joinToString { it?.toString()?.take(40) ?: "null" }})")
                    }
                })
            }
            XposedBridge.log("$TAG: [zalo] native picker diagnostics installed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [zalo] native picker diagnostics failed: $t")
        }
    }
}
