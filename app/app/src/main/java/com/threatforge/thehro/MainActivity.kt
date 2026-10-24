package com.threatforge.thehro

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// Phone signals. Only counts and timings are kept; no text, audio or contacts.
// ---------------------------------------------------------------------------
object Signals {
    var sessionStart = SystemClock.elapsedRealtime()
    var appSwitches = 0
    private var lastInputAt = 0L
    private val fieldPauses = mutableListOf<Long>()
    var accountBursts = 0
    var accountPasted = false
    private var lastAccountCharAt = 0L

    fun reset() {
        sessionStart = SystemClock.elapsedRealtime()
        appSwitches = 0; lastInputAt = 0L; fieldPauses.clear()
        accountBursts = 0; accountPasted = false; lastAccountCharAt = 0L
    }

    fun onFieldFocus() {
        val now = SystemClock.elapsedRealtime()
        if (lastInputAt > 0) fieldPauses.add(now - lastInputAt)
    }

    fun onInput() { lastInputAt = SystemClock.elapsedRealtime() }

    fun onAccountChange(old: String, new: String) {
        val now = SystemClock.elapsedRealtime()
        if (new.length - old.length >= 4) {
            accountPasted = true                       // several digits arrived at once
        } else if (new.length > old.length) {
            if (lastAccountCharAt == 0L || now - lastAccountCharAt > 1500) accountBursts++
            lastAccountCharAt = now                    // a pause >1.5 s starts a new burst
        }
        onInput()
    }

    fun avgPauseMs(): Long = if (fieldPauses.isEmpty()) 0 else fieldPauses.average().toLong()
    fun sessionSeconds(): Long = (SystemClock.elapsedRealtime() - sessionStart) / 1000
}

// Yes/no only: is any phone or VoIP call (WhatsApp etc.) using the audio right now.
fun isCallActive(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
}

suspend fun postJson(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    JSONObject(stream.bufferedReader().use { it.readText() })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ThehroApp() } }
    }

    override fun onStop() {           // user left the app (e.g. went back to WhatsApp)
        super.onStop()
        Signals.appSwitches++
    }
}

@Composable
fun ThehroApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Server address: emulator -> 10.0.2.2 reaches this laptop. Real phone -> laptop's Wi-Fi IP.
    var serverUrl by remember { mutableStateOf("http://localhost:8000") }

    var payee by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    // Demo controls: simulated bank-side events (no real bank connection).
    var fdBroken by remember { mutableStateOf(false) }
    var newPayee by remember { mutableStateOf(true) }
    var limitRaised by remember { mutableStateOf(false) }
    var priorLarge by remember { mutableStateOf("0") }
    var simulateCall by remember { mutableStateOf(false) }   // emulator cannot place calls

    // Volunteer test: leave Volunteer ID empty for normal demo use.
    var volunteerId by remember { mutableStateOf("") }
    var pressuredRun by remember { mutableStateOf(false) }

    var result by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun callNow() = isCallActive(ctx) || simulateCall

    fun resetForm() {
        payee = ""; account = ""; amount = ""; result = null; status = ""
        Signals.reset()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Thehro Bank (demo)", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        val r = result
        if (r == null) {
            // ------------------------- Transfer screen -------------------------
            Text("Send money", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(payee, { payee = it; Signals.onInput() }, label = { Text("Payee name") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })
            OutlinedTextField(account, { Signals.onAccountChange(account, it); account = it },
                label = { Text("Account number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() }; Signals.onInput() },
                label = { Text("Amount (Rs)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })

            HorizontalDivider()
            Text("Demo controls (simulated bank events)", fontWeight = FontWeight.SemiBold)
            ToggleRow("Fixed deposit broken today", fdBroken) { fdBroken = it }
            ToggleRow("Payee is new (added minutes ago)", newPayee) { newPayee = it }
            ToggleRow("Transfer limit raised today", limitRaised) { limitRaised = it }
            ToggleRow("Simulate active call (emulator only)", simulateCall) { simulateCall = it }
            OutlinedTextField(priorLarge, { priorLarge = it.filter { c -> c.isDigit() } },
                label = { Text("Large transfers in last 6 hours") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth())


            Button(
                enabled = !busy && amount.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; status = ""
                    val body = JSONObject()
                        .put("user_id", "demo_user_1")
                        .put("payee_id", payee.ifBlank { "unknown" })
                        .put("amount", amount.toLongOrNull() ?: 0L)
                        .put("account", JSONObject()
                            .put("payee_added_minutes_ago", if (newPayee) 12 else 100000)
                            .put("fd_broken_24h", fdBroken)
                            .put("limit_raised_24h", limitRaised)
                            .put("prior_large_transfers_6h", priorLarge.toIntOrNull() ?: 0))
                        .put("phone", JSONObject()
                            .put("call_active", callNow())
                            .put("session_seconds", Signals.sessionSeconds())
                            .put("avg_field_pause_ms", Signals.avgPauseMs())
                            .put("account_number_pasted", Signals.accountPasted)
                            .put("account_number_bursts", Signals.accountBursts)
                            .put("app_switches", Signals.appSwitches))
                        .put("study", JSONObject()
                            .put("volunteer", volunteerId.trim())
                            .put("condition", if (pressuredRun) "pressured" else "normal"))
                    scope.launch {
                        try { result = postJson("$serverUrl/score", body) }
                        catch (e: Exception) { status = "Could not reach server: ${e.message}" }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Checking…" else "Send money") }
            if (status.isNotEmpty()) Text(status, color = Color(0xFFB0401A))
            Text("Call active now: ${if (callNow()) "yes" else "no"}", color = Color.Gray)
            HorizontalDivider()
            Text("Volunteer test (leave empty for normal use)", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(volunteerId, { volunteerId = it }, label = { Text("Volunteer ID, e.g. V1") },
                modifier = Modifier.fillMaxWidth())
            ToggleRow("This run is under pressure (scripted call)", pressuredRun) { pressuredRun = it }
        } else {
            // -------------------------- Result screen --------------------------
            val tier = r.optInt("tier", 0)
            val (color, title) = when (tier) {
                0 -> Color(0xFF2E7D32) to "Payment sent"
                1 -> Color(0xFFE09B2D) to "Please check before you continue"
                2 -> Color(0xFFB0401A) to "Payment on hold for your safety"
                else -> Color(0xFF14213D) to "Held for review by your bank"
            }
            Box(Modifier.fillMaxWidth().background(color).padding(16.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            val reasons = r.optJSONArray("reasons")
            if (reasons != null && reasons.length() > 0) {
                Text("Why:", fontWeight = FontWeight.SemiBold)
                for (i in 0 until reasons.length()) Text("• ${reasons.getString(i)}")
            }
            if (tier >= 1) Text("Police and government agencies never ask for money on a call.",
                fontWeight = FontWeight.SemiBold)

            when (tier) {
                1 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { resetForm() }) { Text("Cancel payment") }
                    Button(onClick = { result = JSONObject().put("tier", 0) }) { Text("Continue") }
                }
                2, 3 -> {
                    if (tier == 2) Text("Held for ${r.optInt("hold_minutes", 240) / 60} hours. " +
                            "End your call, then confirm again. Your guardian has been notified.")
                    else Text("A bank officer will review this payment before it is released.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { resetForm() }) { Text("Cancel payment") }
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val res = postJson("$serverUrl/confirm", JSONObject()
                                        .put("case_id", r.optString("case_id"))
                                        .put("call_active", callNow()))
                                    status = if (res.optString("status") == "released") "Released – payment sent"
                                             else res.optString("reason", "Still held")
                                } catch (e: Exception) { status = "Could not reach server: ${e.message}" }
                            }
                        }) { Text(if (tier == 2) "I have ended the call – confirm" else "Check status") }
                    }
                }
                else -> Button(onClick = { resetForm() }) { Text("New payment") }
            }
            if (status.isNotEmpty()) Text(status, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
