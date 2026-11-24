package dhp.tpl.groupstickerfix

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import android.view.ContentInfo
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Zalo: the chat's image button opens Android's photo picker instead of Zalo's gallery pane.
 *
 * Nothing here depends on obfuscated names: the button is found by its resource id name, the picker is started
 * from the hosting Activity, and the picks are handed to the chat input as content drops
 * (View.performReceiveContent), which is the entry Zalo already uses for keyboard / clipboard / drag-and-drop
 * images (see [ZaloHook]).
 */
object ZaloNativePicker {
    private const val TAG = Resolver.TAG
    private const val GALLERY_BUTTON = "new_chat_input_btn_show_gallery"
    private const val CHAT_INPUT = "chatinput_text"
    private const val REQUEST_PICK = 0x5A10

    private const val COMPOSE_PANEL = "com.zing.zalo.ui.widget.ChatComposePanelNew"
    private const val MEDIA_ITEM = "com.zing.zalo.data.mediapicker.model.MediaItem"

    private val hookedActivities = HashSet<Class<*>>()
    private var sendPhotos: Method? = null

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        resolveSend(lp, modulePath)
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (!isGalleryButton(v) || !HookPrefs.enabled(Prefs.ZALO_NATIVE_PICKER)) return
                    val desc = v.contentDescription?.toString().orEmpty()
                    if (desc.contains("close", true) || desc.contains("đóng", true)) return
                    val activity = activityOf(v.context) ?: return
                    try {
                        ensureResultHook(activity.javaClass)
                        launch(activity)
                        param.result = true
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: [zalo] native picker launch failed: ${t.cause ?: t}")
                    }
                }
            })
            XposedBridge.log("$TAG: [zalo] hooked native image picker (button $GALLERY_BUTTON)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [zalo] native picker hook failed: $t")
        }
    }

    /**
     * Zalo's gallery Send button ends in ChatComposePanelNew.g(selectedItems, Z, Z, Z), which queues the upload and
     * send of a list of MediaItems. It is found by its signature inside the (readable) panel class.
     */
    private fun resolveSend(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        try {
            val m = Resolver.resolve(lp, modulePath, 1, "send-photos") { bridge ->
                bridge.findMethod { matcher { declaredClass(COMPOSE_PANEL) } }.firstOrNull { md ->
                    md.paramTypeNames == listOf("java.util.List", "boolean", "boolean", "boolean") && md.returnTypeName == "void"
                }?.let { listOf(it) }
            }?.firstOrNull()
            if (m == null) {
                XposedBridge.log("$TAG: [zalo] send-photos method not found in this version")
                return
            }
            sendPhotos = m
            XposedBridge.log("$TAG: [zalo] send-photos method hooked up (${m.declaringClass.name}.${m.name})")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [zalo] send-photos lookup failed: $t")
        }
    }

    private fun isGalleryButton(v: View): Boolean = try {
        v.id != View.NO_ID && v.resources.getResourceEntryName(v.id) == GALLERY_BUTTON
    } catch (_: Throwable) { false }

    private tailrec fun activityOf(c: Context?): Activity? = when (c) {
        is Activity -> c
        is ContextWrapper -> activityOf(c.baseContext)
        else -> null
    }

    @TargetApi(33)
    private fun launch(activity: Activity) {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        }
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    /** Hook the most-derived onActivityResult of the host activity (once per class) to catch our picker's result. */
    private fun ensureResultHook(activityCls: Class<*>) {
        synchronized(hookedActivities) { if (!hookedActivities.add(activityCls)) return }
        var k: Class<*>? = activityCls
        var target: Method? = null
        while (k != null && target == null) {
            target = try {
                k.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            } catch (_: NoSuchMethodException) { null }
            k = k.superclass
        }
        target ?: error("no onActivityResult in ${activityCls.name}")
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] != REQUEST_PICK) return
                param.result = null
                val data = param.args[2] as? Intent
                if (param.args[1] != Activity.RESULT_OK || data == null) return
                val activity = param.thisObject as? Activity ?: return
                val uris = LinkedHashSet<Uri>()
                data.data?.let(uris::add)
                data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(uris::add) }
                XposedBridge.log("$TAG: [zalo] picker returned ${uris.size} item(s)")
                deliverWhenResumed(activity, uris.toList())
            }
        })
    }

    /**
     * Zalo's chat only accepts dropped content while its screen is resumed, and onActivityResult runs before that,
     * so wait for the activity to resume (with a fallback timer) before handing the picks over.
     */
    private fun deliverWhenResumed(activity: Activity, uris: List<Uri>) {
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable {
            if (done) return@Runnable
            done = true
            try {
                deliver(activity, uris)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: [zalo] delivering picks failed: ${t.cause ?: t}")
            }
        }
        val app = activity.application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (a !== activity) return
                app.unregisterActivityLifecycleCallbacks(this)
                handler.postDelayed(run, 400)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        handler.postDelayed({ app.unregisterActivityLifecycleCallbacks(callbacks); run.run() }, 3000)
    }

    private fun idName(v: View): String = try {
        if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "-"
    } catch (_: Throwable) { "?" }

    /** Views reachable from [v], depth first. */
    private fun allViews(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) allViews(v.getChildAt(i), out)
        return out
    }

    @TargetApi(33)
    private fun findReceiver(root: View): View? {
        val views = allViews(root)
        XposedBridge.log("$TAG: [zalo] content receivers: " + views.filter { it.receiveContentMimeTypes != null }
            .joinToString { "${it.javaClass.name}#${idName(it)}" })
        return views.firstOrNull { idName(it) == CHAT_INPUT && it.isShown }
            ?: views.firstOrNull { it.receiveContentMimeTypes != null && it.isShown && it is android.widget.EditText }
            ?: views.firstOrNull { it.receiveContentMimeTypes != null && it.isShown }
    }

    private fun extensionFor(type: String) = when {
        type.endsWith("png") -> "png"
        type.endsWith("webp") -> "webp"
        type.endsWith("gif") -> "gif"
        type.contains("heic") || type.contains("heif") -> "heic"
        else -> "jpg"
    }

    /** Copy a picked content Uri into the app's cache and wrap it as a Zalo MediaItem (built from a local path). */
    private fun toMediaItem(activity: Activity, uri: Uri, index: Int, itemCls: Class<*>): Any {
        val type = activity.contentResolver.getType(uri) ?: "image/jpeg"
        val dir = File(activity.cacheDir, "groupstickerfix").also { it.mkdirs() }
        val file = File(dir, "pick_${System.currentTimeMillis()}_$index.${extensionFor(type)}")
        activity.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return itemCls.getConstructor(String::class.java).newInstance(file.absolutePath)
    }

    @TargetApi(33)
    private fun deliver(activity: Activity, uris: List<Uri>) {
        val send = sendPhotos
        val panel = allViews(activity.window.decorView).firstOrNull { it.javaClass.name == COMPOSE_PANEL }
        if (send != null && panel != null) {
            try {
                val itemCls = Class.forName(MEDIA_ITEM, false, activity.classLoader)
                val items = ArrayList<Any>(uris.mapIndexed { i, u -> toMediaItem(activity, u, i, itemCls) })
                send.isAccessible = true
                send.invoke(panel, items, false, false, false)
                XposedBridge.log("$TAG: [zalo] sent ${items.size} photo(s) through the chat composer")
                return
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: [zalo] sending photos failed, falling back to drops: ${t.cause ?: t}")
            }
        } else {
            XposedBridge.log("$TAG: [zalo] send method=${send != null} panel=${panel != null}; falling back to drops")
        }
        deliverAsDrops(activity, uris)
    }

    @TargetApi(33)
    private fun deliverAsDrops(activity: Activity, uris: List<Uri>) {
        val receiver = findReceiver(activity.window.decorView) ?: error("chat input not found")
        for (uri in uris) {
            val type = activity.contentResolver.getType(uri) ?: "image/*"
            val clip = ClipData(ClipDescription("image", arrayOf(type)), ClipData.Item(uri))
            val rest = receiver.performReceiveContent(ContentInfo.Builder(clip, ContentInfo.SOURCE_DRAG_AND_DROP).build())
            XposedBridge.log("$TAG: [zalo] drop type=$type uri=$uri -> remaining=${rest?.clip?.itemCount}")
        }
        XposedBridge.log("$TAG: [zalo] handed ${uris.size} item(s) to ${receiver.javaClass.name}#${idName(receiver)}")
    }
}
