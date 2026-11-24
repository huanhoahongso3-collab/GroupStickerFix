package dhp.tpl.groupstickerfix

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    @SuppressLint("WorldReadableFiles")
    private fun openPrefs(): Pair<SharedPreferences, Boolean> = try {
        @Suppress("DEPRECATION")
        getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE) to true
    } catch (_: SecurityException) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE) to false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Group Sticker Fix"
        val (prefs, shared) = openPrefs()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }

        if (!shared) {
            list.addView(TextView(this).apply {
                text = "Module is not active in LSPosed, so settings cannot be shared with Messenger / Zalo yet. Enable the module and reopen this screen."
                setPadding(0, px(8), 0, px(16))
            })
        }

        for (t in Prefs.TWEAKS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, px(10))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = t.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                text = t.summary
                textSize = 13f
                alpha = 0.7f
            })
            val sw = Switch(this).apply {
                isChecked = prefs.getBoolean(t.key, true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(t.key, on).apply() }
            }
            row.addView(texts)
            row.addView(sw)
            list.addView(row)
        }

        list.addView(TextView(this).apply {
            text = "Changes apply the next time the feature is used. If one does not, force-stop Messenger / Zalo."
            textSize = 12f
            alpha = 0.6f
            setPadding(0, px(16), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(list) })
    }
}
