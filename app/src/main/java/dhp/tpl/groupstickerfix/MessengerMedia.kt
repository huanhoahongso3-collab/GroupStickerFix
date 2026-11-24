package dhp.tpl.groupstickerfix

import android.app.AndroidAppHelper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Messenger: pick several photos in Google Photos and send them at once, without the edit screen.
 *
 * The composer launches Photos with ACTION_PICK and, for one result Uri, copies it to a file, builds a
 * MediaResource and opens the montage editor. That handler exists twice (a Runnable and a coroutine) and both
 * read only `intent.getData()`. We:
 *  1. ask Photos for several items (EXTRA_ALLOW_MULTIPLE),
 *  2. hook both handlers; when their Intent carries 2+ Uris, run a clone of the handler once per Uri with the
 *     editor launch intercepted so it only hands over the MediaResource,
 *  3. put all resources in the composer and call its send handler.
 */
object MessengerMedia {
    private const val TAG = Resolver.TAG
    private const val MEDIA_RESOURCE = "com.facebook.ui.media.attachments.model.MediaResource"
    private const val COMPOSER_VIEW = "com.facebook.messaging.composer.OneLineComposerView"
    private const val FRAGMENT = "androidx.fragment.app.Fragment"
    private const val EDITOR_TRIGGER = "messenger_montage_swipeable_media_picker"

    private val captured = ThreadLocal<ArrayList<Any>?>()

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        try {
            installGooglePhotos(lp, modulePath)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [messenger] gphotos hook failed: $t")
        }
    }

    private fun installGooglePhotos(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val cl = lp.classLoader

        val entries = Resolver.resolve(lp, modulePath, 1, "gp-entries") { bridge ->
            bridge.findMethod {
                matcher { usingStrings("com.google.android.apps.photos", "Google Photos") }
            }.filter { it.returnTypeName == "java.util.List" && it.paramTypeNames.isEmpty() }.take(1).ifEmpty { null }
        }?.single()

        val handlers = Resolver.resolve(lp, modulePath, Resolver.ANY, "gp-handlers") { bridge ->
            bridge.findMethod {
                matcher { usingStrings("GooglePhotos", EDITOR_TRIGGER) }
            }.filter { it.methodName == "run" || it.methodName == "invokeSuspend" }.ifEmpty { null }
        }

        val editor = Resolver.resolve(lp, modulePath, 1, "gp-editor") { bridge ->
            val handler = bridge.findMethod {
                matcher { usingStrings("GooglePhotos", EDITOR_TRIGGER) }
            }.firstOrNull() ?: return@resolve null
            handler.invokes.filter {
                Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramTypeNames.size == 4 &&
                    it.paramTypeNames[1].endsWith(".MontageComposerFragmentParams") && it.paramTypeNames[3] == "int"
            }.take(1).ifEmpty { null }
        }?.single()

        val sends = Resolver.resolve(lp, modulePath, Resolver.ANY, "gp-send") { bridge ->
            bridge.findMethod {
                matcher { usingStrings("ComposeFragment.onSendClicked") }
            }.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.size == 1 }.ifEmpty { null }
        }

        if (entries == null || handlers == null || editor == null || sends == null) {
            XposedBridge.log("$TAG: [messenger] google photos code not found in this version " +
                "(entries=${entries != null} handlers=${handlers != null} editor=${editor != null} send=${sends != null})")
            return
        }

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
                    // ACTION_PICK is single-choice in Photos; GET_CONTENT is the multi-select form of the same picker
                    i.action = Intent.ACTION_GET_CONTENT
                    i.setDataAndType(null, i.type)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        })

        // 2) the composer drops a result without getData(); a multi-pick may carry only ClipData
        XposedHelpers.findAndHookMethod(
            "com.facebook.base.activity.FbFragmentActivity", cl, "onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val data = param.args[2] as? Intent ?: return
                    val clip = data.clipData ?: return
                    val first = clip.getItemAt(0)?.uri ?: return
                    if (first.authority?.contains("photos", true) != true) return
                    XposedBridge.log("$TAG: [messenger] photos result code=${param.args[1]} data=${data.data} clip=${clip.itemCount} type=${data.type}")
                    if (param.args[1] != -1 || data.data != null) return
                    if (!HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                    data.setDataAndType(first, data.type)
                    XposedBridge.log("$TAG: [messenger] photos result had ${clip.itemCount} items and no data; using the first")
                }
            },
        )

        // 3) while we drive a handler clone, take its MediaResource instead of opening the editor
        XposedBridge.hookMethod(editor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val list = captured.get() ?: return
                param.result = null
                val params = param.args[1] ?: return
                val f = params.javaClass.declaredFields.firstOrNull { it.type == mediaResource } ?: return
                f.isAccessible = true
                f.get(params)?.let(list::add)
            }
        })

        // 4) handlers: several Uris -> one clone per Uri, then send all together
        val handlerHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (captured.get() != null) return
                val self = param.thisObject ?: return
                try {
                    val intentFields = intentFieldsOf(self)
                    val intent = intentFields.firstNotNullOfOrNull { it.get(self) as? Intent } ?: return
                    val uris = urisOf(intent)
                    if (uris.size < 2 || !HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                    XposedBridge.log("$TAG: [messenger] google photos returned ${uris.size} items via ${self.javaClass.name}")

                    val method = param.method as Method
                    val type = intent.type ?: AndroidAppHelper.currentApplication()?.contentResolver?.getType(uris[0])
                    val resources = ArrayList<Any>()
                    var last: Any? = null
                    captured.set(resources)
                    try {
                        for (uri in uris) {
                            try {
                                val one = Intent(intent).apply { clipData = null; setDataAndType(uri, type) }
                                last = XposedBridge.invokeOriginalMethod(method, cloneWith(self, intentFields, one), param.args)
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG: [messenger] google photos item failed: ${t.cause ?: t}")
                            }
                        }
                    } finally {
                        captured.remove()
                    }
                    XposedBridge.log("$TAG: [messenger] google photos ready ${resources.size}/${uris.size}")
                    if (resources.isEmpty()) return
                    param.result = last
                    Handler(Looper.getMainLooper()).post {
                        try {
                            sendNow(self, resources, fragmentCls, composerCls, mediaResource, sends)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: [messenger] google photos send failed: $t")
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [messenger] google photos handler hook error: $t")
                }
            }
        }
        handlers.forEach { XposedBridge.hookMethod(it, handlerHook) }
        XposedBridge.log("$TAG: [messenger] hooked google photos multi-select (${handlers.size} handlers)")
    }

    private fun urisOf(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.data?.let(out::add)
        intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(out::add) }
        return out.toList()
    }

    private fun instanceFields(c: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) if (!Modifier.isStatic(f.modifiers) && !f.type.isPrimitive) out += f.also { it.isAccessible = true }
            k = k.superclass
        }
        return out
    }

    private fun intentFieldsOf(o: Any): List<Field> = instanceFields(o.javaClass).filter { it.get(o) is Intent }

    private val unsafe: Any? by lazy {
        try {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        } catch (_: Throwable) { null }
    }

    /** Shallow copy of [o] (including coroutine state) with the Intent fields replaced by [intent]. */
    private fun cloneWith(o: Any, intentFields: List<Field>, intent: Intent): Any {
        val u = unsafe ?: error("no Unsafe")
        val copy = u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, o.javaClass)!!
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                f.set(copy, f.get(o))
            }
            k = k.superclass
        }
        for (f in intentFields) f.set(copy, intent)
        return copy
    }

    private fun isPlatform(name: String) =
        name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.") || name.startsWith("dalvik.")

    /** The compose fragment (a Fragment that holds the composer view) reachable from a handler object. */
    private fun findComposeFragment(root: Any, fragmentCls: Class<*>, composerCls: Class<*>): Pair<Any, Any>? {
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue += root to 0
        seen[root] = true
        while (queue.isNotEmpty() && seen.size < 4000) {
            val (o, depth) = queue.removeFirst()
            val fields = instanceFields(o.javaClass)
            if (fragmentCls.isInstance(o)) {
                val composer = fields.firstNotNullOfOrNull { f -> f.get(o)?.takeIf { composerCls.isInstance(it) } }
                if (composer != null) return o to composer
            }
            if (depth >= 4) continue
            for (f in fields) {
                val v = f.get(o) ?: continue
                if (isPlatform(v.javaClass.name) || seen.put(v, true) != null) continue
                queue += v to depth + 1
            }
        }
        return null
    }

    private fun sendNow(
        handler: Any, resources: List<Any>, fragmentCls: Class<*>, composerCls: Class<*>,
        mediaResource: Class<*>, sends: List<Method>,
    ) {
        val (fragment, composer) = findComposeFragment(handler, fragmentCls, composerCls) ?: error("compose fragment not found")
        val send = sends.firstOrNull { it.parameterTypes[0].isInstance(fragment) } ?: error("send handler not found for ${fragment.javaClass.name}")
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
        XposedBridge.log("$TAG: [messenger] google photos sent ${resources.size} item(s)")
    }
}
