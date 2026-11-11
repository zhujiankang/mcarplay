package com.mcarplay.app.carlink

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.service.CarlinkReceiverService

class CarlinkActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var choices: RadioGroup
    private val handler = Handler(Looper.getMainLooper())
    private val update =
        object : Runnable {
            override fun run() {
                val state = CarlinkRuntime.status()
                status.text =
                    getString(
                        R.string.carlink_receiver_status,
                        state.stage,
                        state.videoFrames,
                        state.audioFrames,
                        state.width,
                        state.height,
                        CarlinkRuntime.pin(),
                    )
                val enabled = canChangeInput()
                for (index in 0 until choices.childCount) choices.getChildAt(index).isEnabled = enabled
                handler.postDelayed(this, 750)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 32, 24, 24)
            }

        fun text(resource: Int) =
            TextView(this).apply {
                setText(resource)
                textSize = 16f
                setPadding(0, 12, 0, 12)
                layout.addView(this)
            }

        fun button(
            resource: Int,
            action: () -> Unit,
        ) {
            layout.addView(
                Button(this).apply {
                    setText(resource)
                    setOnClickListener { action() }
                },
            )
        }
        text(R.string.carlink_experiment_title).textSize = 23f
        text(R.string.carlink_experiment_description)
        choices = RadioGroup(this)
        val carlife =
            RadioButton(this).apply {
                id = R.id.carlink_choice_carlife
                setText(R.string.carlink_source_carlife)
            }
        val carlink =
            RadioButton(this).apply {
                id = R.id.carlink_choice_carlink
                setText(R.string.carlink_source_carlink)
            }
        choices.addView(carlife)
        choices.addView(carlink)
        choices.check(if (ProjectionInputPrefs.isCarlink(this)) R.id.carlink_choice_carlink else R.id.carlink_choice_carlife)
        choices.setOnCheckedChangeListener { _, id ->
            if (canChangeInput()) {
                ProjectionInputPrefs.set(
                    this,
                    if (id ==
                        R.id.carlink_choice_carlink
                    ) {
                        ProjectionInputPrefs.Input.CARLINK
                    } else {
                        ProjectionInputPrefs.Input.CARLIFE
                    },
                )
                if (id == R.id.carlink_choice_carlife) stopService(Intent(this, CarlinkReceiverService::class.java))
            } else {
                choices.check(if (ProjectionInputPrefs.isCarlink(this)) R.id.carlink_choice_carlink else R.id.carlink_choice_carlife)
            }
        }
        layout.addView(choices)
        text(R.string.carlink_switch_when_idle)
        status = text(R.string.carlink_status_idle)
        button(R.string.carlink_start_receiver) { startForegroundService(Intent(this, CarlinkReceiverService::class.java)) }
        button(R.string.carlink_stop_receiver) { stopService(Intent(this, CarlinkReceiverService::class.java)) }
        button(R.string.carlink_open_xiaomi_settings) {
            runCatching {
                startActivity(
                    Intent().setComponent(
                        ComponentName("com.miui.carlink", "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity"),
                    ),
                )
            }.onFailure { SessionController.appendLog("CARLINK: SOURCE_SETTINGS_UNAVAILABLE") }
        }
        button(R.string.carlink_share_status) {
            val s = CarlinkRuntime.status()
            val report =
                "mcarplay Carlink experiment\nBOOTSTRAP=NOT_IMPLEMENTED\nCERTIFICATE=NOT_IMPLEMENTED\n" +
                    "PEER_APP=UNVERIFIED\nINPUT=${ProjectionInputPrefs.get(this)}\nSTAGE=${s.stage}\n" +
                    "VIDEO=${s.videoFrames}\nAAC=${s.audioFrames}\nSIZE=${s.width}x${s.height}\nREADY=${s.ready}"
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                    getString(R.string.carlink_share_status),
                ),
            )
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(update)
    }

    private fun canChangeInput(): Boolean =
        !SessionController.isSessionRunning() && SessionController.currentClient == null &&
            SessionController.runningDiagnosticCarLifeSession == null

    override fun onPause() {
        handler.removeCallbacks(update)
        super.onPause()
    }
}
