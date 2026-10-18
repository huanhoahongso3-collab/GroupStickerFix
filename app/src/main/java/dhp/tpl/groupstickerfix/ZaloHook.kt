package dhp.tpl.groupstickerfix

import android.content.ClipDescription
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * Zalo receives keyboard commitContent (source 2) and clipboard paste (source 1) in one place
 * (`u7.g0(presenter, payload, ClipDescription, source, continuation)` in 26.10.01). It sends a
 * sticker for gif, or png when `source == 2` and a "sticker-like PNG" test (`b6`: small, transparent
 * edges) passes; everything else is an image.
 *
 * - Clipboard png: run the same sticker-like test by presenting it as source 2 (photos / opaque / big
 *   images fail the test and stay images).
 * - Anything tagged "sticker" (ClipDescription label / extras / mime): force the test to pass.
 * - Untagged keyboard images are left exactly as Zalo handles them.
 *
 * Both methods are found with DexKit fingerprints, never by name.
 */
object ZaloHook {
    const val TARGET = "com.zing.zalo"
    private const val TAG = Resolver.TAG
    private const val CLIP_DESC = "android.content.ClipDescription"

    private const val SOURCE_CLIPBOARD = 1
    private const val SOURCE_KEYBOARD = 2

    private val forced: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val (decide, spriteTest) = Resolver.resolve(lp, modulePath, 2) { bridge ->
            // 1) the decision method: static-ish, 5 params (.., payload, ClipDescription, int, continuation)
            val g0 = bridge.findMethod {
                matcher { usingStrings("image/gif", "image/webp", "image/png") }
            }.firstOrNull { m ->
                m.paramTypeNames.size == 5 &&
                    m.paramTypeNames[2] == CLIP_DESC && m.paramTypeNames[3] == "int" &&
                    m.invokes.any { it.className == CLIP_DESC && it.methodName == "getLabel" }
            } ?: return@resolve null

            // 2) the sticker-like-PNG test: a coroutine class built inside g0 whose invokeSuspend reads pixels
            val test = g0.invokes
                .filter { it.methodName == "<init>" && it.className != g0.className }
                .distinctBy { it.className }
                .firstNotNullOfOrNull { ctor ->
                    bridge.findMethod {
                        matcher { declaredClass(ctor.className); name("invokeSuspend") }
                    }.firstOrNull { s ->
                        s.invokes.any { it.className == "android.graphics.Bitmap" && it.methodName == "getPixel" }
                    }
                } ?: return@resolve null
            listOf(g0, test)
        } ?: run {
            XposedBridge.log("$TAG: [zalo] sticker decision methods not found in this version")
            return
        }

        val payloadType = decide.parameterTypes[1]
        val payloadMime: Field? = payloadType.declaredFields.firstOrNull { it.type == String::class.java }?.also { it.isAccessible = true }
        val testPayload: Field? = spriteTest.declaringClass.declaredFields.firstOrNull { it.type == payloadType }?.also { it.isAccessible = true }
        if (testPayload == null) {
            XposedBridge.log("$TAG: [zalo] payload field not found in ${spriteTest.declaringClass.name}")
            return
        }

        XposedBridge.hookMethod(decide, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val payload = param.args[1] ?: return
                    val desc = param.args[2] as? ClipDescription
                    val source = param.args[3] as? Int ?: return
                    if (source != SOURCE_CLIPBOARD && source != SOURCE_KEYBOARD) return

                    val mime = (payloadMime?.get(payload) as? String).orEmpty()
                    val label = desc?.label?.toString().orEmpty()
                    val mimes = desc?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }.orEmpty()
                    val extras = try { desc?.extras?.keySet()?.joinToString().orEmpty() } catch (_: Throwable) { "" }
                    val tagged = listOf(label, extras, mimes.joinToString()).any { it.contains("sticker", ignoreCase = true) }
                    val png = mime.equals("image/png", ignoreCase = true)

                    var action = "unchanged"
                    if (png && (tagged || source == SOURCE_CLIPBOARD)) {
                        param.args[3] = SOURCE_KEYBOARD // let Zalo's own sticker-like-PNG test run
                        action = "png->test"
                    }
                    if (tagged) {
                        forced[payload] = true
                        action += "+forced"
                    }
                    XposedBridge.log("$TAG: [zalo] source=$source mime=$mime label='$label' mimes=$mimes extras=[$extras] tagged=$tagged -> $action")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [zalo] decide hook error: $t")
                }
            }
        })

        XposedBridge.hookMethod(spriteTest, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val payload = testPayload.get(param.thisObject) ?: return
                    if (forced.containsKey(payload) && param.result == false) param.result = true
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [zalo] test hook error: $t")
                }
            }
        })
        XposedBridge.log("$TAG: [zalo] hooked ${decide.declaringClass.name}.${decide.name} / ${spriteTest.declaringClass.name}")
    }
}
