package dhp.tpl.groupstickerfix

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Diagnostics: logs MediaStore queries so the filter that hides images can be seen in the module log. */
object QueryLog {
    private val seen = HashSet<String>()

    fun install(tag: String) {
        XposedHelpers.findAndHookMethod(
            ContentResolver::class.java, "query",
            Uri::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uri = param.args[0] as? Uri ?: return
                        if (uri.authority != "media") return
                        val b = param.args[2] as? Bundle
                        val sel = b?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                        val args = b?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.joinToString("|")
                        val extra = b?.keySet()?.filter { !it.startsWith("android:query-arg-sql-selection") }?.joinToString { it + "=" + b.get(it) }
                        val line = "$tag query $uri sel=$sel args=$args extra=[$extra]"
                        if (synchronized(seen) { seen.add(line) }) XposedBridge.log("${Resolver.TAG}: $line")
                    } catch (_: Throwable) {}
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val uri = param.args[0] as? Uri ?: return
                        if (uri.authority != "media") return
                        val c = param.result as? Cursor ?: return
                        if (c.count > 5000) { XposedBridge.log("${Resolver.TAG}: $tag result $uri rows=${c.count} (not scanned)"); return }
                        val col = c.getColumnIndex("_data").takeIf { it >= 0 } ?: c.getColumnIndex("_display_name")
                        if (col < 0) return
                        val pos = c.position
                        var rows = 0; var shots = 0
                        c.moveToPosition(-1)
                        while (c.moveToNext()) { rows++; if (c.getString(col)?.contains("Screenshot", true) == true) shots++ }
                        c.moveToPosition(pos)
                        val line = "$tag result $uri rows=$rows screenshots=$shots"
                        if (synchronized(seen) { seen.add(line) }) XposedBridge.log("${Resolver.TAG}: $line")
                    } catch (_: Throwable) {}
                }
            },
        )
    }
}
