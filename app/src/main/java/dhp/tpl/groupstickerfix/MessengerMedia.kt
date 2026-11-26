package dhp.tpl.groupstickerfix

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** Set by [MessengerNativePicker] when it launches the photo picker; makes even a single pick send directly. */
    @Volatile var nativePickAt = 0L

    private fun nativePickActive() = nativePickAt != 0L && SystemClock.elapsedRealtime() - nativePickAt < 10 * 60_000

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
            bridge.findMethod {
                matcher { usingStrings("GooglePhotos", EDITOR_TRIGGER) }
            }.flatMap { it.invokes }.filter {
                it.returnTypeName == "void" && it.paramTypeNames.size == 4 &&
                    it.paramTypeNames[1].endsWith(".MontageComposerFragmentParams") && it.paramTypeNames[3] == "int"
            }.distinctBy { it.descriptor }.take(1).ifEmpty { null }
        }?.single()

        // only the fallback path needs these; the chat's event bus sends without them
        val sends: List<Method> = try {
            Resolver.resolve(lp, modulePath, Resolver.ANY, "gp-send") { bridge ->
                bridge.findMethod {
                    matcher { usingStrings("ComposeFragment.onSendClicked") }
                }.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.size == 1 }.ifEmpty { null }
            }.orEmpty()
        } catch (t: Throwable) { emptyList() }

        if (entries == null || handlers == null || editor == null) {
            XposedBridge.log("$TAG: [messenger] google photos code not found in this version " +
                "(entries=${entries != null} handlers=${handlers != null} editor=${editor != null})")
            return
        }

        val mediaResource = Class.forName(MEDIA_RESOURCE, false, cl)
        val composerCls: Class<*>? = try { Class.forName(COMPOSER_VIEW, false, cl) } catch (_: Throwable) { null }
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
        try { XposedHelpers.findAndHookMethod(
            "com.facebook.base.activity.FbFragmentActivity", cl, "onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == Activity.RESULT_CANCELED && nativePickAt != 0L) nativePickAt = 0L
                    val data = param.args[2] as? Intent ?: return
                    val clip = data.clipData ?: return
                    val first = clip.getItemAt(0)?.uri ?: return
                    val authority = first.authority ?: return
                    if (!authority.contains("photos", true) && authority != "media") return
                    XposedBridge.log("$TAG: [messenger] picker result code=${param.args[1]} data=${data.data} clip=${clip.itemCount} type=${data.type}")
                    if (param.args[1] != -1 || data.data != null) return
                    if (!HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI) && !nativePickActive()) return
                    data.setDataAndType(first, data.type)
                    XposedBridge.log("$TAG: [messenger] picker result had ${clip.itemCount} items and no data; using the first")
                }
            },
        ) } catch (t: Throwable) { XposedBridge.log("$TAG: [messenger] activity result hook failed: $t") }

        // 3) while we drive a handler clone, take its MediaResource instead of opening the editor
        XposedBridge.hookMethod(editor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val list = captured.get() ?: return
                param.result = null
                val params = param.args.firstOrNull { it?.javaClass?.name?.endsWith(".MontageComposerFragmentParams") == true } ?: return
                val f = params.javaClass.declaredFields.firstOrNull { it.type == mediaResource } ?: return
                f.isAccessible = true
                f.get(params)?.let(list::add)
            }
        })

        // remember compose fragment instances for the send step
        sends.map { it.parameterTypes[0] }.distinct().forEach { cls ->
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    synchronized(composeFragments) {
                        composeFragments.removeAll { it.get() == null }
                        composeFragments += java.lang.ref.WeakReference(param.thisObject)
                    }
                }
            })
        }

        // 4) handlers: several Uris -> one clone per Uri, then send all together
        val handlerHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (captured.get() != null) return
                val self = param.thisObject ?: return
                try {
                    val intentFields = intentFieldsOf(self)
                    val intent = intentFields.firstNotNullOfOrNull { it.get(self) as? Intent } ?: return
                    val uris = urisOf(intent)
                    val native = nativePickActive()
                    if (uris.isEmpty() || (uris.size < 2 && !native)) return
                    if (!native && !HookPrefs.enabled(Prefs.MESSENGER_GPHOTOS_MULTI)) return
                    nativePickAt = 0L
                    XposedBridge.log("$TAG: [messenger] picker returned ${uris.size} item(s) via ${self.javaClass.name} (native=$native)")

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

    private val composeFragments = ArrayList<java.lang.ref.WeakReference<Any>>()

    private fun call(o: Any, name: String): Any? = try { o.javaClass.getMethod(name).invoke(o) } catch (_: Throwable) { null }

    private fun composerOf(fragment: Any, composerCls: Class<*>): Any? =
        instanceFields(fragment.javaClass).firstNotNullOfOrNull { f -> f.get(fragment)?.takeIf { composerCls.isInstance(it) } }

    /** Fragments reachable from the fields of a handler object (breadth-first, platform classes skipped). */
    private fun collectFragments(root: Any, fragmentCls: Class<*>): List<Any> {
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        val fragments = ArrayList<Any>()
        queue += root to 0
        seen[root] = true
        while (queue.isNotEmpty() && seen.size < 4000) {
            val (o, depth) = queue.removeFirst()
            if (fragmentCls.isInstance(o)) fragments += o
            if (depth >= 4) continue
            for (f in instanceFields(o.javaClass)) {
                val v = f.get(o) ?: continue
                if (isPlatform(v.javaClass.name) || seen.put(v, true) != null) continue
                queue += v to depth + 1
            }
        }
        return fragments
    }

    /**
     * The compose fragment is the one the send handler takes as its argument. Look for it from the handler's own
     * fields and up the parent-fragment chain (the handler may only hold a child such as the media tray), then fall
     * back to the compose fragments created in this process.
     */
    private fun findComposeFragment(
        root: Any, fragmentCls: Class<*>, composerCls: Class<*>, sendTypes: List<Class<*>>,
    ): Pair<Any, Any>? {
        fun accept(f: Any): Pair<Any, Any>? =
            if (sendTypes.any { it.isInstance(f) }) composerOf(f, composerCls)?.let { f to it } else null

        val fragments = collectFragments(root, fragmentCls)
        val tree = fragmentTree(fragments, fragmentCls)
        val candidates = tree.mapNotNull { accept(it) }
        if (candidates.isNotEmpty()) {
            return candidates.firstOrNull { call(it.first, "isResumed") == true } ?: candidates.first()
        }
        val live = synchronized(composeFragments) { composeFragments.mapNotNull { it.get() } }.asReversed()
        tree.filter { f -> sendTypes.any { it.isInstance(f) } }.forEach { f ->
            XposedBridge.log("$TAG: [messenger] compose fragment ${f.javaClass.name} has no OneLineComposerView; composer-like fields=" +
                instanceFields(f.javaClass).mapNotNull { fl -> fl.get(f)?.javaClass?.name }.filter { it.contains("composer", true) })
        }
        XposedBridge.log("$TAG: [messenger] compose fragment not found; handler fragments=${fragments.map { it.javaClass.name }} " +
            "tree=${tree.map { it.javaClass.name }} live=${live.map { it.javaClass.name + "(added=" + call(it, "isAdded") + ")" }}")
        val ready = live.filter { call(it, "isAdded") == true }
        return (ready.firstOrNull { call(it, "isResumed") == true } ?: ready.firstOrNull())?.let(::accept)
    }

    /** Child fragments of [fragment] (its child FragmentManager's list; the manager's method names are obfuscated). */
    private fun childrenOf(fragment: Any, fragmentCls: Class<*>): List<Any> = fragmentsIn(call(fragment, "getChildFragmentManager"), fragmentCls)

    private fun fragmentsIn(manager: Any?, fragmentCls: Class<*>): List<Any> {
        manager ?: return emptyList()
        val out = ArrayList<Any>()
        var k: Class<*>? = manager.javaClass
        while (k != null && k != Any::class.java) {
            for (m in k.declaredMethods) {
                if (!Modifier.isPublic(m.modifiers) || Modifier.isStatic(m.modifiers) || m.parameterCount != 0 || m.returnType != List::class.java) continue
                try {
                    m.isAccessible = true
                    (m.invoke(manager) as? List<*>)?.forEach { if (it != null && fragmentCls.isInstance(it)) out += it }
                } catch (_: Throwable) {}
            }
            k = k.superclass
        }
        return out
    }

    /** Every fragment under the top-most ancestor of each start fragment, plus the activity's own fragments. */
    private fun fragmentTree(starts: List<Any>, fragmentCls: Class<*>): List<Any> {
        val out = ArrayList<Any>()
        val seen = IdentityHashMap<Any, Boolean>()
        fun visit(f: Any, depth: Int) {
            if (depth > 8 || seen.put(f, true) != null) return
            out += f
            childrenOf(f, fragmentCls).forEach { visit(it, depth + 1) }
        }
        val fmType = fragmentCls.getMethod("getChildFragmentManager").returnType
        for (start in starts) {
            var top: Any = start
            var hops = 0
            while (hops++ < 10) top = call(top, "getParentFragment") ?: break
            visit(top, 0)
            val activity = call(start, "getActivity") ?: continue
            for (m in activity.javaClass.methods) {
                if (m.parameterCount != 0 || m.returnType != fmType) continue
                try { fragmentsIn(m.invoke(activity), fragmentCls).forEach { visit(it, 1) } } catch (_: Throwable) {}
            }
        }
        return out
    }

    private const val SEND_EVENT = "com.facebook.xapp.messaging.composer.gallery.event.SendGalleryItemsEvent"
    private const val GALLERY_ITEM = "com.facebook.xapp.messaging.gallery.model.GalleryMediaItem"
    private const val COMPOSER_EVENT = "com.facebook.xapp.messaging.composer.event.ComposerEvent"
    private const val SEND_SOURCE = "com.facebook.ui.media.attachments.source.MediaResourceSendSource"
    private const val GUAVA_LIST = "com.google.common.collect.ImmutableList"

    private fun allocate(cls: Class<*>): Any {
        val u = unsafe ?: error("no Unsafe")
        return u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, cls)!!
    }

    private fun copyField(src: Any, srcName: String, dst: Any, dstName: String) {
        try {
            val s = src.javaClass.getDeclaredField(srcName).also { it.isAccessible = true }
            val d = dst.javaClass.getDeclaredField(dstName).also { it.isAccessible = true }
            if (s.type == d.type) d.set(dst, s.get(src))
        } catch (_: Throwable) {}
    }

    /** Same conversion the chat's gallery tray applies in reverse: a MediaResource becomes a GalleryMediaItem. */
    private fun toGalleryItem(mr: Any, itemCls: Class<*>, index: Int): Any {
        val ctor = itemCls.declaredConstructors.first { it.parameterTypes.size == 1 && it.parameterTypes[0] != itemCls }
        val builderCls = ctor.parameterTypes[0]
        val b = builderCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        val uri = instanceFields(mr.javaClass).mapNotNull { it.get(mr) as? Uri }.let { l -> l.firstOrNull { it.scheme == "file" } ?: l.first() }
        val path = uri.path
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path != null) android.graphics.BitmapFactory.decodeFile(path, opts)
        builderCls.declaredMethods.first { it.parameterTypes.contentEquals(arrayOf(Uri::class.java)) }.invoke(b, uri)
        builderCls.declaredMethods.first { it.parameterTypes.contentEquals(arrayOf(String::class.java)) }.invoke(b, opts.outMimeType ?: "image/jpeg")
        // MediaResource and the builder share field letters (A0f -> MediaResource); the item takes them from the gallery columns
        for ((from, to) in listOf("A05" to "A03", "A06" to "A04", "A07" to "A06", "A08" to "A05", "A00" to "A00", "A04" to "A02")) copyField(mr, from, b, to)
        try {
            val f = builderCls.getDeclaredField("A04").also { it.isAccessible = true }
            if (f.getLong(b) == 0L) f.setLong(b, System.currentTimeMillis() + index)
        } catch (_: Throwable) {}
        ctor.isAccessible = true
        return ctor.newInstance(b)
    }

    /** Post a SendGalleryItemsEvent on the thread screen's composer event bus, like the chat's own gallery Send button. */
    private fun sendViaComposerBus(fragments: List<Any>, resources: List<Any>, mediaResource: Class<*>): Boolean {
        return try {
            val cl = mediaResource.classLoader
            val composerEvent = Class.forName(COMPOSER_EVENT, false, cl)
            val dispatch = fragments.firstNotNullOfOrNull { f ->
                f.javaClass.declaredMethods.firstOrNull {
                    Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(composerEvent))
                }?.let { f to it }
            } ?: return false
            val eventCls = Class.forName(SEND_EVENT, false, cl)
            val itemCls = Class.forName(GALLERY_ITEM, false, cl)
            val items = resources.mapIndexed { i, mr -> toGalleryItem(mr, itemCls, i) }
            val list = Class.forName(GUAVA_LIST, false, cl).getMethod("copyOf", java.util.Collection::class.java).invoke(null, items)
            val source = mediaResource.declaredFields.firstOrNull { it.type.name == SEND_SOURCE }?.also { it.isAccessible = true }?.get(resources[0])
            val event = allocate(eventCls)
            for (f in eventCls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                when {
                    f.type.name == SEND_SOURCE -> f.set(event, source)
                    f.type.isEnum -> f.set(event, f.type.enumConstants.first { (it as Enum<*>).name == "NONE" })
                    f.type.name == GUAVA_LIST -> f.set(event, list)
                    f.type == String::class.java -> f.set(event, if (f.name == "A04") "SendGalleryItemsEvent" else "unspecified")
                    f.type == java.lang.Boolean.TYPE -> f.setBoolean(event, false)
                }
            }
            dispatch.second.isAccessible = true
            dispatch.second.invoke(dispatch.first, event)
            XposedBridge.log("$TAG: [messenger] google photos sent ${resources.size} item(s) via ${dispatch.first.javaClass.name}.${dispatch.second.name}")
            true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [messenger] composer event bus send failed: ${t.cause ?: t}")
            false
        }
    }

    private fun sendNow(
        handler: Any, resources: List<Any>, fragmentCls: Class<*>, composerCls: Class<*>?,
        mediaResource: Class<*>, sends: List<Method>,
    ) {
        if (sendViaComposerBus(collectFragments(handler, fragmentCls), resources, mediaResource)) return
        if (composerCls == null || sends.isEmpty()) error("no composer event bus and no classic composer in this version")
        XposedBridge.log("$TAG: [messenger] composer event bus not available; trying the classic composer")
        val (fragment, composer) = findComposeFragment(handler, fragmentCls, composerCls, sends.map { it.parameterTypes[0] })
            ?: error("compose fragment not found")
        val send = sends.first { it.parameterTypes[0].isInstance(fragment) }
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
