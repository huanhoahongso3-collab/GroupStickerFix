package dhp.tpl.groupstickerfix

import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    private val hookedActivities = HashSet<Class<*>>()

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
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
                try {
                    deliver(activity, uris.toList())
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [zalo] delivering picks failed: ${t.cause ?: t}")
                }
            }
        })
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

    @TargetApi(33)
    private fun deliver(activity: Activity, uris: List<Uri>) {
        val receiver = findReceiver(activity.window.decorView) ?: error("chat input not found")
        for (uri in uris) {
            val type = activity.contentResolver.getType(uri) ?: "image/*"
            val clip = ClipData(ClipDescription("image", arrayOf(type)), ClipData.Item(uri))
            receiver.performReceiveContent(ContentInfo.Builder(clip, ContentInfo.SOURCE_DRAG_AND_DROP).build())
        }
        XposedBridge.log("$TAG: [zalo] handed ${uris.size} item(s) to ${receiver.javaClass.name}#${idName(receiver)}")
    }
}
