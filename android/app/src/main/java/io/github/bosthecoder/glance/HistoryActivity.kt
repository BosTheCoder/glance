package io.github.bosthecoder.glance

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Every move, skip (and older snooze) and revert, newest first, with Revert on the ones that can still be undone. */
class HistoryActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(48), dp(20), dp(48)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E0E10.toInt()); addView(list) })
        build()
    }

    private fun describe(e: JSONObject): String {
        val t = "“${e.optString("title")}”"
        fun at(ms: Long) = if (day(ms) == day(e.getLong("at"))) hm(ms) else fmt(ms, "EEE HH:mm")
        val s = when (e.getString("kind")) {
            "snooze" -> "Snoozed $t ${dur(e.getInt("minutes") * MIN)}: ${at(e.getLong("begin"))} → ${hm(e.getLong("begin") + e.getInt("minutes") * MIN)}"
            "move" -> "Moved the start of $t: ${at(e.getLong("begin"))} → ${hm(e.getLong("newBegin"))}"
            "extend" -> "Extended $t: until ${hm(e.getLong("end"))} → ${hm(e.getLong("newEnd"))}"
            "wontdo" -> "Skipped $t (${at(e.getLong("begin"))})"
            "revert" -> "Reverted $t"
            else -> e.toString()
        }
        return s + (e.optString("error").takeIf { it.isNotEmpty() }?.let { "\nFailed: $it" } ?: "") +
            (if (e.optBoolean("reverted")) "\nReverted" else "")
    }

    private fun build() {
        list.removeAllViews()
        list.addView(text("Action history", 26f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(12)) })
        val all = Actions.history(this).reversed()
        if (all.isEmpty()) list.addView(text("Nothing yet. Skip on the start screen shows up here.", 14f, 0x99FFFFFF.toInt()).apply { maxLines = 3 })
        for (e in all) list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(fmt(e.getLong("at"), "EEE d MMM HH:mm"), 12f, 0x80FFFFFF.toInt()))
                addView(text(describe(e), 15f, if (e.has("error")) 0xFFFF8A80.toInt() else Color.WHITE).apply { maxLines = 5 })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (e.getString("kind") != "revert" && !e.has("error") && !e.optBoolean("reverted")) addView(Button(context).apply {
                text = "Revert"; isAllCaps = false; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF22305A.toInt()) }
                setOnClickListener {
                    isEnabled = false
                    thread {
                        val r = Actions.revert(this@HistoryActivity, e.getLong("at"))
                        runOnUiThread {
                            Toast.makeText(this@HistoryActivity, r.optString("error").ifEmpty { "Reverted" }, Toast.LENGTH_LONG).show()
                            build()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}
