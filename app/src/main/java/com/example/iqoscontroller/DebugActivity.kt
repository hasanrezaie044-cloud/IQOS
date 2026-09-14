package com.example.iqoscontroller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.iqoscontroller.databinding.ActivityDebugBinding

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    companion object {
        val diagnosticMap = LinkedHashMap<String, String>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_debug)

        binding.btnClearLogs.setOnClickListener {
            AppLogger.clear()
            renderLogs()
        }

        binding.btnForensicsRefresh.setOnClickListener { renderForensics() }
        binding.btnForensicsClear.setOnClickListener {
            FrameForensics.clear()
            renderForensics()
        }
        binding.btnForensicsExport.setOnClickListener {
            val path = FrameForensics.exportToFile(this)
            if (path != null) {
                android.widget.Toast.makeText(this, "Saved: $path", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(this, "Export failed - see logs", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        renderDiagnostics()
        renderLogs()
        renderForensics()

        AppLogger.onLogUpdated = {
            runOnUiThread {
                renderLogs()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderDiagnostics()
        renderLogs()
        renderForensics()
    }

    private fun renderForensics() {
        binding.tvForensicsSummary.text = FrameForensics.summaryText()
    }

    private fun renderDiagnostics() {
        val sb = StringBuilder()
        if (diagnosticMap.isEmpty()) {
            sb.append("Bluetooth: Ready\nScan: Idle\nDevice: None\nTransport: Disconnected\nLast Error: None")
        } else {
            diagnosticMap.forEach { (k, v) ->
                sb.append("$k: $v\n")
            }
        }
        binding.tvDiagnosticsText.text = sb.toString()
    }

    private fun renderLogs() {
        val logs = AppLogger.getLogs()
        binding.tvLiveLogs.text = if (logs.isEmpty()) {
            "[LOG] No events recorded yet."
        } else {
            logs.joinToString("\n")
        }
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.onLogUpdated = null
    }
}
