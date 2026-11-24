package dhp.tpl.groupstickerfix

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

/**
 * Messenger gallery tweaks.
 *
 * All images: the composer gallery queries MediaStore with `mime_type in (jpeg, png, gif)`. The query is
 * rewritten to `image/%` so webp, heic, avif, ... show up too.
 *
 * Google Photos multi-select: the composer launches Photos with ACTION_PICK and handles one Uri by copying it
 * to a file and opening the montage editor. We ask Photos for several, and for 2+ results run that same
 * per-Uri code (editor launch intercepted to just capture the MediaResource), put all resources in the composer
 * and press its send handler.
 */
object MessengerMedia {
    private const val TAG = Resolver.TAG
    private const val REQUEST_PICK = 0x458
    private const val MEDIA_RESOURCE = "com.facebook.ui.media.attachments.model.MediaResource"
    private const val COMPOSER_VIEW = "com.facebook.messaging.composer.OneLineComposerView"
    private const val FRAGMENT = "androidx.fragment.app.Fragment"

    private val captured = ThreadLocal<ArrayList<Any>?>()

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        try { installAllImages() } catch (t: Throwable) { XposedBridge.log("$TAG: [messenger] all-images hook failed: $t") }
        try { logIncomingShares() } catch (t: Throwable) { XposedBridge.log("$TAG: [messenger] launch log failed: $t") }
        try { installGooglePhotos(lp, modulePath) } catch (t: Throwable) { XposedBridge.log("$TAG: [messenger] gphotos hook failed: $t") }
    }

    private fun installAllImages() {
        val strCls = String::class.java
        XposedHelpers.findAndHookMethod(
            MediaStore.Images.Media::class.java, "query",
            ContentResolver::class.java, Uri::class.java, Array<String>::class.java, strCls, Array<String>::class.java, strCls,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val where = param.args[3] as? String ?: return
                    if (!where.startsWith(MIME_FILTER)) return
                    if (!HookPrefs.enabled(Prefs.MESSENGER_ALL_IMAGES)) return
                    param.args[3] = "(mime_type LIKE 'image/%')" + where.removePrefix(MIME_FILTER)
                    @Suppress("UNCHECKED_CAST")
                    val old = param.args[4] as? Array<String>
                    param.args[4] = old?.drop(MIME_ARGS)?.takeIf { it.isNotEmpty() }?.toTypedArray()
                }
            },
        )
        XposedBridge.log("$TAG: [messenger] hooked gallery MediaStore query")
    }

    private fun logIncomingShares() {
        XposedHelpers.findAndHookMethod(
            android.app.Instrumentation::class.java, "callActivityOnCreate",
            android.app.Activity::class.java, android.os.Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val a = param.args[0] as android.app.Activity
                    val i = a.intent ?: return
                    val act = i.action ?: return
                    if (act == Intent.ACTION_MAIN) return
                    XposedBridge.log("$TAG: [messenger] activity ${a.javaClass.name} action=$act type=${i.type} data=${i.data} clip=${i.clipData?.itemCount}")
                }
            },
        )
    }

    private const val MIME_FILTER = "(mime_type in (?, ?, ?))"
    private const val MIME_ARGS = 3

    private fun installGooglePhotos(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val cl = lp.classLoader
        val methods = Resolver.resolve(lp, modulePath, 4, "gphotos") { bridge ->
            val entries = bridge.findMethod {
                matcher { usingStrings("com.google.android.apps.photos", "Google Photos") }
            }.firstOrNull { it.returnTypeName == "java.util.List" && it.paramTypeNames.isEmpty() } ?: return@resolve null

            val runMd = bridge.findMethod {
                matcher { usingStrings("GooglePhotos", "messenger_montage_swipeable_media_picker"); name("run") }
            }.firstOrNull() ?: return@resolve null

            val editor = runMd.invokes.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramTypeNames.size == 4 &&
                    it.paramTypeNames[1].endsWith(".MontageComposerFragmentParams") && it.paramTypeNames[3] == "int"
            } ?: return@resolve null

            val send = bridge.findMethod {
                matcher { usingStrings("ComposeFragment.onSendClicked") }
            }.firstOrNull { Modifier.isStatic(it.modifiers) && it.paramTypeNames.size == 1 } ?: return@resolve null

            listOf(entries, runMd, editor, send)
        } ?: run {
            XposedBridge.log("$TAG: [messenger] google photos picker code not found in this version")
            return
        }
        val (entries, runMethod, editor, send) = methods
        val mediaResource = Class.forName(MEDIA_RESOURCE, false, cl)
        val composerCls = Class.forName(COMPOSER_VIEW, false, cl)
        val fragmentCls = Class.forName(FRAGMENT, false, cl)

        // 1) ask Photos for several items
        XposedBridge.hookMethod(entries, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                for (e in param.result as? List<*> ?: return) {
                    e ?: continue
                    val f = e.javaClass.declaredFields.firstOrNull { it.type == Intent::class.java } ?: continue
                    f.isAccessible = true
                    val i = f.get(e) as? Intent ?: continue
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    XposedBridge.log("$TAG: [messenger] photos entry intent=$i multi=true")
                }
            }
        })

        // 2) Fragment results come back through the activity-result registry with its own request codes, so match on
        //    shape instead: several items in ClipData and no getData() (the composer ignores results without getData()).
        XposedHelpers.findAndHookMethod(
            android.app.Activity::class.java, "dispatchActivityResult",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val data = param.args[3] as? Intent ?: return
                    val clip = data.clipData
                    XposedBridge.log("$TAG: [messenger] activity result code=${param.args[1]} result=${param.args[2]} data=${data.data} clip=${clip?.itemCount} type=${data.type}")
                    if (param.args[2] != -1 || data.data != null || clip == null || clip.itemCount < 1) return
                    if (!HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                    data.setDataAndType(clip.getItemAt(0).uri, data.type)
                    XposedBridge.log("$TAG: [messenger] result had ${clip.itemCount} items and no data; set data to first")
                }
            },
        )

        // 3) capture the MediaResource instead of opening the editor (only while we drive the runnable)
        XposedBridge.hookMethod(editor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val list = captured.get()
                if (list == null) {
                    XposedBridge.log("$TAG: [messenger] editor opened from: " + Throwable().stackTrace.take(20).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName })
                    return
                }
                param.result = null
                val params = param.args[1] ?: return
                val f = params.javaClass.declaredFields.firstOrNull { it.type == mediaResource } ?: return
                f.isAccessible = true
                f.get(params)?.let(list::add)
            }
        })

        // 4) several Uris: build each resource with the original code, then send them in one go
        XposedBridge.hookMethod(runMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (captured.get() != null || !HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                try {
                    val self = param.thisObject
                    val intent = field(self, Intent::class.java) as? Intent ?: return
                    val uris = urisOf(intent)
                    XposedBridge.log("$TAG: [messenger] VYV.run uris=${uris.size}")
                    if (uris.size < 2) return
                    param.result = null
                    val lifecycle = self.javaClass.declaredFields.firstOrNull { it.type != Intent::class.java && !Modifier.isStatic(it.modifiers) }
                        ?.also { it.isAccessible = true }?.get(self) ?: return
                    val ctor = self.javaClass.declaredConstructors.first().also { it.isAccessible = true }
                    val resources = ArrayList<Any>()
                    captured.set(resources)
                    try {
                        for (uri in uris) {
                            try {
                                val one = Intent(intent).apply { clipData = null; setDataAndType(uri, intent.type) }
                                val vyv = ctor.newInstance(*ctor.parameterTypes.map { if (it == Intent::class.java) one else lifecycle }.toTypedArray())
                                XposedBridge.invokeOriginalMethod(runMethod, vyv, emptyArray())
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG: [messenger] gphotos item failed: $t")
                            }
                        }
                    } finally {
                        captured.remove()
                    }
                    XposedBridge.log("$TAG: [messenger] gphotos picked=${uris.size} ready=${resources.size}")
                    if (resources.isEmpty()) return
                    Handler(Looper.getMainLooper()).post {
                        try { sendNow(lifecycle, resources, fragmentCls, composerCls, mediaResource, send) }
                        catch (t: Throwable) { XposedBridge.log("$TAG: [messenger] gphotos send failed: $t") }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [messenger] gphotos run hook error: $t")
                }
            }
        })
        XposedBridge.log("$TAG: [messenger] hooked google photos multi-select")
    }

    private fun urisOf(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.data?.let(out::add)
        intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(out::add) }
        return out.toList()
    }

    private fun field(o: Any, type: Class<*>): Any? =
        o.javaClass.declaredFields.firstOrNull { it.type == type }?.also { it.isAccessible = true }?.get(o)

    private fun sendNow(
        lifecycle: Any, resources: List<Any>, fragmentCls: Class<*>, composerCls: Class<*>,
        mediaResource: Class<*>, send: java.lang.reflect.Method,
    ) {
        val fragment = findInHolders(lifecycle) { fragmentCls.isInstance(it) } ?: error("compose fragment not found")
        val composer = findInFields(fragment) { composerCls.isInstance(it) } ?: error("composer view not found")
        val setAttachments = composerCls.declaredMethods.single {
            Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(List::class.java))
        }
        val existing = composerCls.declaredFields.filter { it.type == List::class.java }.mapNotNull { f ->
            f.isAccessible = true
            (f.get(composer) as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { mediaResource.isInstance(it) } }
        }.firstOrNull().orEmpty()
        setAttachments.invoke(composer, ArrayList<Any?>(existing).apply { addAll(resources) })
        send.isAccessible = true
        send.invoke(null, fragment)
    }

    private fun findInFields(o: Any, pred: (Any) -> Boolean): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type.isPrimitive || Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(o) ?: continue
                if (pred(v)) return v
            }
            c = c.superclass
        }
        return null
    }

    private fun findInHolders(o: Any, pred: (Any) -> Boolean): Any? {
        findInFields(o, pred)?.let { return it }
        for (f in o.javaClass.declaredFields) {
            if (f.type.isPrimitive || Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val holder = f.get(o) ?: continue
            findInFields(holder, pred)?.let { return it }
        }
        return null
    }
}
