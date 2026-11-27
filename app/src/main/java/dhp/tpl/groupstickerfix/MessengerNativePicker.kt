package dhp.tpl.groupstickerfix

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Messenger: the chat's image button asks the composer to open its in-app gallery by posting an
 * ActivateGalleryEvent on the composer event bus. We swallow that event and open Android's photo picker instead,
 * using the request code of the Google Photos flow, so the existing result handler turns the picks into a direct
 * send (see [MessengerMedia]).
 */
object MessengerNativePicker {
    private const val TAG = Resolver.TAG
    private const val REQUEST_PICK = 0x458
    private const val ACTIVATE_GALLERY = "com.facebook.xapp.messaging.composer.gallery.event.ActivateGalleryEvent"
    private const val COMPOSER_EVENT = "com.facebook.xapp.messaging.composer.event.ComposerEvent"
    private const val THREAD_MARKER = "Send read receipt not supported for thread type:"

    private val threadFragments = ArrayList<WeakReference<Any>>()

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val methods = Resolver.resolve(lp, modulePath, 2, "native-bus") { bridge ->
                val seed = bridge.findMethod { matcher { usingStrings(THREAD_MARKER) } }.firstOrNull() ?: return@resolve null
                val dispatch = bridge.findMethod { matcher { declaredClass(seed.className) } }.firstOrNull {
                    it.paramTypeNames == listOf(COMPOSER_EVENT) && it.returnTypeName == "void" && !Modifier.isStatic(it.modifiers)
                } ?: return@resolve null
                val queue = dispatch.invokes.firstOrNull {
                    it.paramTypeNames == listOf(COMPOSER_EVENT) && it.returnTypeName == "void"
                } ?: return@resolve null
                listOf(queue, dispatch)
            } ?: run {
                XposedBridge.log("$TAG: [messenger] composer event bus not found in this version")
                return
            }
            val (queue, dispatch) = methods

            XposedBridge.hookAllConstructors(dispatch.declaringClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    synchronized(threadFragments) {
                        threadFragments.removeAll { it.get() == null }
                        threadFragments += WeakReference(param.thisObject)
                    }
                }
            })

            XposedBridge.hookMethod(queue, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) ?: return
                    if (event.javaClass.name != ACTIVATE_GALLERY) return
                    if (!HookPrefs.enabled(Prefs.MESSENGER_NATIVE_PICKER)) return
                    val fragment = currentThread() ?: return
                    val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                        putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                    }
                    param.result = null
                    val launch = Runnable {
                        try {
                            MessengerMedia.nativePickAt = SystemClock.elapsedRealtime()
                            fragment.javaClass.getMethod("startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType)
                                .invoke(fragment, intent, REQUEST_PICK)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: [messenger] native picker launch failed: ${t.cause ?: t}")
                        }
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) launch.run() else Handler(Looper.getMainLooper()).post(launch)
                }
            })
            XposedBridge.log("$TAG: [messenger] hooked native image picker (${dispatch.declaringClass.name})")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [messenger] native picker hook failed: $t")
        }
    }

    private fun call(o: Any, name: String): Any? = try { o.javaClass.getMethod(name).invoke(o) } catch (_: Throwable) { null }

    private fun currentThread(): Any? {
        val live = synchronized(threadFragments) { threadFragments.mapNotNull { it.get() } }.asReversed()
        val added = live.filter { call(it, "isAdded") == true }
        return added.firstOrNull { call(it, "isResumed") == true } ?: added.firstOrNull()
    }
}
