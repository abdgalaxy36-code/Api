package com.ruh.flight.live

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: StatsStore
    private lateinit var repoInput: EditText
    private lateinit var branchInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var fcKeyInput: EditText
    private lateinit var checkResult: TextView
    private lateinit var backupStatus: TextView
    private lateinit var creditsBox: LinearLayout
    private lateinit var lastBackup: TextView
    private lateinit var notifPermission: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = StatsStore(this)
        repoInput = findViewById(R.id.repoInput)
        branchInput = findViewById(R.id.branchInput)
        pathInput = findViewById(R.id.pathInput)
        tokenInput = findViewById(R.id.tokenInput)
        fcKeyInput = findViewById(R.id.fcKeyInput)
        checkResult = findViewById(R.id.checkResult)
        backupStatus = findViewById(R.id.backupStatus)
        creditsBox = findViewById(R.id.creditsBox)
        lastBackup = findViewById(R.id.lastBackup)
        notifPermission = findViewById(R.id.notifPermission)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        repoInput.setText(prefs.getString(K_REPO, GithubBackup.DEFAULT_REPO))
        branchInput.setText(prefs.getString(K_BRANCH, GithubBackup.DEFAULT_BRANCH))
        pathInput.setText(prefs.getString(K_PATH, GithubBackup.DEFAULT_PATH))
        tokenInput.setText(prefs.getString(K_TOKEN, ""))
        fcKeyInput.setText(ServiceQuota.firecrawlKey(this))

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        findViewById<Button>(R.id.checkBtn).setOnClickListener { runCheck() }
        findViewById<Button>(R.id.fixBtn).setOnClickListener { runFix() }
        findViewById<Button>(R.id.creditsBtn).setOnClickListener { checkCredits() }
        findViewById<Button>(R.id.fcKeySaveBtn).setOnClickListener {
            ServiceQuota.setFirecrawlKey(this, fcKeyInput.text.toString())
            toast("Firecrawl key saved")
            renderCredits()
        }
        findViewById<Button>(R.id.saveCfgBtn).setOnClickListener {
            saveConfig()
            toast("Settings saved")
        }
        findViewById<Button>(R.id.testCfgBtn).setOnClickListener {
            saveConfig()
            testConnection()
        }
        findViewById<Button>(R.id.backupBtn).setOnClickListener {
            saveConfig()
            runBackup()
        }
        findViewById<Button>(R.id.restoreBtn).setOnClickListener {
            saveConfig()
            runRestore()
        }

        setupRefresh()
        setupNotifications()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        renderCredits()
        renderLastBackup()
        checkPermission()
    }

    // ------------------------------------------------------------- refresh

    private fun setupRefresh() {
        val sw = findViewById<Switch>(R.id.autoRefreshSwitch)
        val sp = findViewById<Spinner>(R.id.intervalSpinner)
        val options = AppPrefs.INTERVALS.map { AppPrefs.label(it) }
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, options
        )
        sw.isChecked = AppPrefs.autoRefresh(this)
        sp.setSelection(AppPrefs.INTERVALS.indexOf(AppPrefs.interval(this)).coerceAtLeast(0))

        sw.setOnCheckedChangeListener { _, on ->
            AppPrefs.setAutoRefresh(this, on)
            Log.i(Fr24Api.TAG, "auto refresh ${if (on) "on" else "off"}")
        }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ms = AppPrefs.INTERVALS.getOrNull(pos) ?: return
                AppPrefs.setInterval(this@SettingsActivity, ms)
                Log.i(Fr24Api.TAG, "refresh interval -> ${AppPrefs.label(ms)}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------------- notifications

    private fun setupNotifications() {
        val best = findViewById<Switch>(R.id.notifBestSwitch)
        val summary = findViewById<Switch>(R.id.notifSummarySwitch)
        val progress = findViewById<Switch>(R.id.notifProgressSwitch)
        val backup = findViewById<Switch>(R.id.notifBackupSwitch)

        best.isChecked = Notifier.enabled(this, Notifier.K_BEST)
        summary.isChecked = Notifier.enabled(this, Notifier.K_SUMMARY)
        progress.isChecked = Notifier.enabled(this, Notifier.K_PROGRESS)
        backup.isChecked = Notifier.enabled(this, Notifier.K_BACKUP)

        val listener = CompoundButton.OnCheckedChangeListener { button, on ->
            val key = when (button.id) {
                R.id.notifBestSwitch -> Notifier.K_BEST
                R.id.notifSummarySwitch -> Notifier.K_SUMMARY
                R.id.notifProgressSwitch -> Notifier.K_PROGRESS
                else -> Notifier.K_BACKUP
            }
            Notifier.setEnabled(this, key, on)
            Notifier.ensureChannels(this)
            Notifier.scheduleAll(this)
            Log.i(Fr24Api.TAG, "notification $key -> $on")
        }
        best.setOnCheckedChangeListener(listener)
        summary.setOnCheckedChangeListener(listener)
        progress.setOnCheckedChangeListener(listener)
        backup.setOnCheckedChangeListener(listener)

        Notifier.ensureChannels(this)
        Notifier.scheduleAll(this)
    }

    private fun checkPermission() {
        val on = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notifPermission.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) {
            notifPermission.text = "Notifications are blocked for this app — tap to open the system setting."
            notifPermission.setOnClickListener {
                try {
                    startActivity(
                        android.content.Intent(
                            "android.settings.APP_NOTIFICATION_SETTINGS"
                        ).putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE, packageName
                        )
                    )
                } catch (e: Exception) {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- credits

    private fun checkCredits() {
        ServiceQuota.probeJina(this)
        ServiceQuota.probeFirecrawl(this)
        Thread {
            Thread.sleep(4_000)
            runOnUiThread { renderCredits() }
        }.start()
    }

    /** Compact, coloured: one row per service. */
    private fun renderCredits() {
        creditsBox.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (q in ServiceQuota.all(this)) {
            val row = inflater.inflate(R.layout.item_credit, creditsBox, false)
            val name = row.findViewById<TextView>(R.id.creditName)
            val value = row.findViewById<TextView>(R.id.creditValue)
            val note = row.findViewById<TextView>(R.id.creditNote)

            name.text = if (q.name == ServiceQuota.JINA) "Jina" else "Firecrawl"

            val remaining = q.remaining.trim().toIntOrNull()
            val limit = q.limit.substringBefore(' ').trim().toIntOrNull()
            value.text = when {
                remaining != null && limit != null -> "$remaining / $limit left"
                remaining != null -> "$remaining left"
                q.lastStatus == 429 -> "no credits"
                q.lastStatus == 0 -> "not used"
                else -> "HTTP ${q.lastStatus}"
            }
            value.setTextColor(
                when {
                    q.lastStatus == 429 -> Color.parseColor("#D32F2F")
                    remaining != null && limit != null && remaining * 2 < limit ->
                        Color.parseColor("#F57C00")
                    remaining != null -> Color.parseColor("#3FB950")
                    else -> Color.parseColor("#8B949E")
                }
            )
            note.text = when {
                q.name == ServiceQuota.JINA -> "main"
                q.lastStatus == 429 -> "backup"
                else -> "backup"
            }
            creditsBox.addView(row)
        }
    }

    // ----------------------------------------------------------- data check

    /** What FR24 says happened on a day whose window it still serves. */
    private class SourceDay(
        val date: String,
        val rows: List<LiveFlight>,
        val stored: Int?,
        val error: String?
    ) {
        val total: Int get() = rows.size
        val needsFix: Boolean get() = error == null && stored != rows.size
    }

    private fun runCheck() {
        checkResult.text = "Checking…"
        Thread {
            val internal = try {
                store.verify()
            } catch (e: Exception) {
                "Check failed: ${e.message}"
            }
            val source = try {
                againstSource()
            } catch (e: Exception) {
                "FR24 comparison failed: ${e.message}"
            }
            val report = "$internal\n\n$source"
            Log.i(Fr24Api.TAG, "data check:\n$report")
            runOnUiThread { checkResult.text = report }
        }.start()
    }

    /**
     * The internal check only proves the rows agree with each other — a day
     * that lost its whole morning still passes it. So the last day or two
     * (all FR24's schedule window still covers) are pulled again and counted
     * here, independently of the app's own numbers.
     */
    private fun loadSource(): List<SourceDay> {
        Fr24Api.quotaContext = applicationContext
        val y = Clock.today().minusDays(1)
        val t = Clock.today()
        val yStart = y.atStartOfDay(Clock.zone).toEpochSecond()
        val tStart = t.atStartOfDay(Clock.zone).toEpochSecond()

        val yDep = Fr24Api.fetch("departures", yStart)
        val yArr = Fr24Api.fetch("arrivals", yStart)
        val yRows = (yDep.flights + yArr.flights)
        val yErr = yDep.error ?: yArr.error

        val tDep = Fr24Api.fetch("departures", tStart)
        val tArr = Fr24Api.fetch("arrivals", tStart)
        val tRows = (tDep.flights + tArr.flights)
        val tErr = tDep.error ?: tArr.error

        val yKey = y.toString()
        val tKey = t.toString()
        return listOf(
            SourceDay(
                yKey,
                yRows.filter { Clock.dayKey(it.realEpoch) == yKey },
                store.get(yKey)?.total,
                yErr
            ),
            // today also owns the flights scheduled yesterday that moved after midnight
            SourceDay(
                tKey,
                (tRows.filter { Clock.dayKey(it.realEpoch) == tKey } +
                    yRows.filter { Clock.dayKey(it.realEpoch) == tKey })
                    .distinctBy { it.dedupKey() },
                store.get(tKey)?.total,
                tErr
            )
        )
    }

    private fun againstSource(): String {
        val sb = StringBuilder("AGAINST FR24 SOURCE\n")
        for (d in loadSource()) {
            sb.append(
                if (d.error != null) "  ${d.date}: FR24 unreachable (${d.error})\n"
                else "  ${d.date}: ${verdict(d.stored, d.total)}\n"
            )
        }
        sb.append("\nOnly the last day or two can be compared: FR24's\n")
        sb.append("schedule window no longer reaches older dates.")
        return sb.toString().trimEnd()
    }

    private fun verdict(stored: Int?, source: Int): String = when {
        stored == null -> "stored: none  FR24=$source"
        stored == source -> "MATCH"
        else -> "MISMATCH  app=$stored  FR24=$source"
    }

    /**
     * Rebuilds, straight from FR24, only the days the check found wrong.
     * Every other day is left untouched — this never overwrites good data.
     */
    private fun runFix() {
        checkResult.text = "Fetching FR24…"
        Thread {
            val report = try {
                val days = loadSource()
                val sb = StringBuilder()
                var fixed = 0
                for (d in days) {
                    when {
                        d.error != null ->
                            sb.append("  ${d.date}: skipped — FR24 unreachable\n")
                        !d.needsFix ->
                            sb.append("  ${d.date}: already correct (${d.total}) — untouched\n")
                        else -> {
                            val before = d.stored ?: 0
                            store.replaceDayFlights(d.date, d.rows)
                            store.recountDay(d.date)
                            val after = store.get(d.date)?.total ?: 0
                            fixed++
                            sb.append("  ${d.date}: $before -> $after  FIXED (FR24=$after)\n")
                            Log.i(Fr24Api.TAG, "data fix ${d.date}: $before -> $after")
                        }
                    }
                }
                val head = if (fixed > 0) "FIXED $fixed day(s) from FR24\n"
                else "NOTHING TO FIX — every day already matches FR24\n"
                head + sb.toString().trimEnd()
            } catch (e: Exception) {
                "Fix failed: ${e.message}"
            }
            Log.i(Fr24Api.TAG, "data fix:\n$report")
            runOnUiThread { checkResult.text = report }
        }.start()
    }

    // -------------------------------------------------------------- backup

    private fun config(): BackupConfig = BackupConfig(
        repo = repoInput.text.toString().trim(),
        branch = branchInput.text.toString().trim(),
        path = pathInput.text.toString().trim(),
        token = tokenInput.text.toString().trim()
    )

    private fun saveConfig() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_REPO, repoInput.text.toString().trim())
            .putString(K_BRANCH, branchInput.text.toString().trim())
            .putString(K_PATH, pathInput.text.toString().trim())
            .putString(K_TOKEN, tokenInput.text.toString().trim())
            .apply()
    }

    private fun testConnection() {
        backupStatus.text = "Testing…"
        Thread {
            try {
                val msg = GithubBackup.testConnection(config())
                Log.i(Fr24Api.TAG, "github test ok")
                runOnUiThread { backupStatus.text = msg }
            } catch (e: Exception) {
                Log.w(Fr24Api.TAG, "github test failed: ${e.message}")
                runOnUiThread { backupStatus.text = "Test failed: ${e.message}" }
            }
        }.start()
    }

    private fun runBackup() {
        val cfg = config()
        if (!cfg.isComplete()) {
            backupStatus.text = "Fill in repository, branch, file and token first."
            return
        }
        backupStatus.text = "Backing up…"
        Thread {
            try {
                val json = GithubBackup.exportJson(store.all(), store.allFlights())
                Log.i(Fr24Api.TAG, "backup payload days=${store.all().size} flights=${store.allFlights().size}")
                val msg = GithubBackup.push(cfg, json)
                BackupState.markBackup(this, true, msg)
                runOnUiThread {
                    backupStatus.text = msg
                    renderLastBackup()
                }
            } catch (e: Exception) {
                Log.w(Fr24Api.TAG, "backup failed: ${e.message}")
                BackupState.markBackup(this, false, e.message ?: "failed")
                runOnUiThread {
                    backupStatus.text = "Backup failed: ${e.message}"
                    renderLastBackup()
                }
            }
        }.start()
    }

    private fun runRestore() {
        val cfg = config()
        if (!cfg.isComplete()) {
            backupStatus.text = "Fill in repository, branch, file and token first."
            return
        }
        backupStatus.text = "Restoring…"
        Thread {
            try {
                val json = GithubBackup.pull(cfg)
                val (stats, flights) = GithubBackup.importJson(json)
                store.insertStats(stats)
                store.insertFlights(flights)
                // Rows that came from a file were not collected here, so the
                // next refresh is allowed to repair them against FR24.
                AppPrefs.clearCatchUp(this)
                Log.i(Fr24Api.TAG, "restore ok days=${stats.size} flights=${flights.size}")
                runOnUiThread {
                    backupStatus.text =
                        "Restored ${stats.size} day(s) and ${flights.size} flight(s).\n" +
                            "Go back and refresh to reload the screen."
                }
            } catch (e: Exception) {
                Log.w(Fr24Api.TAG, "restore failed: ${e.message}")
                runOnUiThread { backupStatus.text = "Restore failed: ${e.message}" }
            }
        }.start()
    }

    private fun renderLastBackup() {
        val days = BackupState.daysSinceLastBackup(this)
        val whenText = BackupState.formatAt(this)
        val state = if (BackupState.lastBackupAt(this) <= 0L) "never"
        else if (BackupState.lastOk(this)) "OK" else "failed"
        lastBackup.text = "Last backup:  $whenText   ($state)"
        if (days >= 0) lastBackup.append("\n${days} day(s) ago")
        val msg = BackupState.lastMessage(this)
        if (msg.isNotBlank()) lastBackup.append("\n$msg")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREFS = "ruh_backup_cfg"
        private const val K_REPO = "repo"
        private const val K_BRANCH = "branch"
        private const val K_PATH = "path"
        private const val K_TOKEN = "token"
    }
}
