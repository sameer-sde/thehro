package com.threatforge.thehro

import android.content.Context
import android.content.Intent
import kotlin.math.exp
import kotlin.math.ln
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

    // On-device pressure model (logistic regression, trained on synthetic sessions; see ondevice/train.py).
    // Runs entirely on the phone: only the final score is sent to the bank.
    private val W = doubleArrayOf(0.9411, 1.6986, 1.2457, 4.5118, 0.548)
    private const val B = -32.6609
    fun pressureScore(): Double {
        val x = doubleArrayOf(ln(1.0 + avgPauseMs()), accountBursts.toDouble(), appSwitches.toDouble(),
            ln(1.0 + sessionSeconds()), if (accountPasted) 1.0 else 0.0)
        var z = B
        for (i in x.indices) z += W[i] * x[i]
        return 1.0 / (1.0 + exp(-z))
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

// The account number is hashed on the phone; the bank compares hashes to spot mule accounts.
fun sha256(t: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

// "Is this caller real?" – plain-language facts for the agency the scammer claims to be from.
val CALLER_FACTS = mapOf(
    "CBI" to ("CBI never arrests anyone on a call or asks for money. There is no ‘digital arrest’ in Indian law. Hang up and call 1930." to
              "सीबीआई कभी फ़ोन पर गिरफ़्तार नहीं करती, न पैसे माँगती है। ‘डिजिटल अरेस्ट’ जैसा कोई क़ानून नहीं है। फ़ोन काटें और 1930 पर कॉल करें।"),
    "Police" to ("Police never arrest anyone on a video call or make you transfer money to ‘verify’ it. Hang up and call 1930." to
                 "पुलिस कभी वीडियो कॉल पर गिरफ़्तार नहीं करती, न ‘जाँच’ के लिए पैसे ट्रांसफ़र करवाती है। फ़ोन काटें और 1930 पर कॉल करें।"),
    "Customs" to ("Customs never asks for money on a call about a ‘parcel in your name’. Hang up and call 1930." to
                  "कस्टम्स कभी ‘आपके नाम के पार्सल’ के लिए फ़ोन पर पैसे नहीं माँगता। फ़ोन काटें और 1930 पर कॉल करें।"),
    "TRAI" to ("TRAI does not call to cut your number or connect you to the police. Hang up and call 1930." to
               "TRAI आपका नंबर बंद करने या पुलिस से जोड़ने के लिए कॉल नहीं करता। फ़ोन काटें और 1930 पर कॉल करें।"),
    "Bank" to ("Your bank never asks you to move money to a ‘safe account’. Hang up and call the number on your card." to
               "आपका बैंक कभी पैसे ‘सुरक्षित खाते’ में भेजने को नहीं कहता। फ़ोन काटें और कार्ड पर लिखे नंबर पर कॉल करें।"),
    "RBI" to ("RBI never calls customers or holds money for checking. Hang up and call 1930." to
              "RBI कभी ग्राहकों को कॉल नहीं करता, न जाँच के लिए पैसे रखता है। फ़ोन काटें और 1930 पर कॉल करें।"),
)

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

    // Safety Mode: the customer's own rule – hold payments above a limit made during a call.
    var safetyOn by remember { mutableStateOf(false) }
    var safetyLimit by remember { mutableStateOf("20000") }

    var result by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun callNow() = isCallActive(ctx) || simulateCall

    // Language of the warning screen, and spoken alerts (Text-to-Speech, built into Android).
    var hindi by remember { mutableStateOf(false) }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { st -> if (st == TextToSpeech.SUCCESS) tts.value = engine }
        onDispose { engine?.shutdown() }
    }
    fun speak(en: String, hi: String) {
        val t = tts.value ?: return
        val hiOk = hindi && t.setLanguage(Locale.forLanguageTag("hi-IN")) >= 0
        if (!hiOk && t.setLanguage(Locale.forLanguageTag("en-IN")) < 0) t.setLanguage(Locale.US)
        t.speak(if (hiOk) hi else en, TextToSpeech.QUEUE_FLUSH, null, "thehro")
    }

    fun resetForm() {
        payee = ""; account = ""; amount = ""; result = null; status = ""; hindi = false
        tts.value?.stop()
        Signals.reset()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Thehro Bank (demo)", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        val r = result
        if (r == null) {
            // ------------------------- Transfer screen -------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(volunteerId, { volunteerId = it }, label = { Text("Volunteer ID (test only)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Chip("Under pressure", pressuredRun) { pressuredRun = !pressuredRun }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("🛡 Safety Mode", safetyOn, Modifier.weight(1f)) { safetyOn = !safetyOn }
                OutlinedTextField(safetyLimit, { safetyLimit = it.filter { c -> c.isDigit() } },
                    label = { Text("Hold on a call above Rs") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.3f))
            }
            HorizontalDivider()
            OutlinedTextField(payee, { payee = it; Signals.onInput() }, label = { Text("Payee name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })
            OutlinedTextField(account, { Signals.onAccountChange(account, it); account = it },
                label = { Text("Account number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() }; Signals.onInput() },
                label = { Text("Amount (Rs)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) Signals.onFieldFocus() })

            HorizontalDivider()
            Text("Demo controls (simulated bank events) – tap to switch", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("FD broken today", fdBroken, Modifier.weight(1f)) { fdBroken = !fdBroken }
                Chip("New payee", newPayee, Modifier.weight(1f)) { newPayee = !newPayee }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Limit raised today", limitRaised, Modifier.weight(1f)) { limitRaised = !limitRaised }
                Chip("Simulate call", simulateCall, Modifier.weight(1f)) { simulateCall = !simulateCall }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(priorLarge, { priorLarge = it.filter { c -> c.isDigit() } },
                    label = { Text("Large transfers 6h") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server URL") },
                    singleLine = true, modifier = Modifier.weight(1.6f))
            }


        } else {
            // -------------------------- Result screen --------------------------
            val tier = r.optInt("tier", 0)
            val (color, title) = when (tier) {
                0 -> Color(0xFF2E7D32) to (if (hindi) "भुगतान भेजा गया" else "Payment sent")
                1 -> Color(0xFFE09B2D) to (if (hindi) "आगे बढ़ने से पहले जाँच लें" else "Please check before you continue")
                2 -> Color(0xFFB0401A) to (if (hindi) "आपकी सुरक्षा के लिए भुगतान रोका गया" else "Payment on hold for your safety")
                else -> Color(0xFF14213D) to (if (hindi) "बैंक द्वारा जाँच के लिए रोका गया" else "Held for review by your bank")
            }
            val safetyEn = "Police and government agencies never ask for money on a call."
            val safetyHi = "पुलिस या सरकारी एजेंसी कभी फ़ोन पर पैसे नहीं माँगती।"
            val spokenEn = "Stop. This payment is on hold for your safety. $safetyEn Please end the call now."
            val spokenHi = "रुकिए। आपकी सुरक्षा के लिए यह भुगतान रोका गया है। $safetyHi कृपया कॉल अभी काट दें।"
            // Speak the alert once when a payment is held, so the user hears it even while on the call.
            LaunchedEffect(r) { if (tier >= 2) { kotlinx.coroutines.delay(400); speak(spokenEn, spokenHi) } }
            Box(Modifier.fillMaxWidth().background(color).padding(16.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (!r.isNull("on_device_score") && r.has("on_device_score")) Text("On-device pressure score: %.2f".format(r.optDouble("on_device_score")),
                fontSize = 13.sp, color = Color.Gray)
            if (tier >= 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("हिंदी", hindi, Modifier.weight(1f)) { hindi = !hindi }
                Chip(if (hindi) "🔊 सुनें" else "🔊 Read aloud", false, Modifier.weight(1f)) { speak(spokenEn, spokenHi) }
            }
            val reasons = r.optJSONArray(if (hindi && r.has("reasons_hi")) "reasons_hi" else "reasons")
            if (reasons != null && reasons.length() > 0) {
                Text(if (hindi) "क्यों:" else "Why:", fontWeight = FontWeight.SemiBold)
                for (i in 0 until reasons.length()) Text("• ${reasons.getString(i)}")
            }
            if (tier >= 1) Text(if (hindi) safetyHi else safetyEn, fontWeight = FontWeight.SemiBold)

            // "Is this caller real?" – one tap shows (and speaks) the facts about the claimed agency.
            if (tier >= 1) {
                var claimed by remember(r) { mutableStateOf("") }
                Text(if (hindi) "कॉल करने वाला कहता है कि वह यहाँ से है:" else "Is this caller real? They say they are from:",
                    fontWeight = FontWeight.SemiBold)
                val orgs = CALLER_FACTS.keys.toList()
                for (rowOrgs in orgs.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowOrgs.forEach { o ->
                        Chip(o, claimed == o, Modifier.weight(1f)) {
                            claimed = o
                            CALLER_FACTS[o]?.let { (en, hi) -> speak(en, hi) }
                        }
                    }
                }
                CALLER_FACTS[claimed]?.let { (en, hi) ->
                    Text(if (hindi) hi else en, color = Color(0xFFB0401A), fontWeight = FontWeight.Bold)
                }
            }

            if (r.optBoolean("escalated")) Text(
                if (hindi) "यही ‘डिजिटल अरेस्ट’ ठगी का तरीका है। असली पुलिस कभी फ़ोन या वीडियो कॉल पर गिरफ़्तार नहीं करती। बैंक अधिकारी आपको कॉल करेंगे।"
                else "This is how ‘digital arrest’ scams work. Real police never arrest anyone over a phone or video call. " +
                     "Your payment is blocked until a bank officer calls you.",
                color = Color(0xFFB0401A), fontWeight = FontWeight.Bold)

            // Safety check: three yes/no questions. Any "yes" sends the payment to a bank officer.
            if (tier in 1..2 && r.has("case_id")) {
                var answers by remember(r) { mutableStateOf(listOf(false, false, false)) }
                var sent by remember(r) { mutableStateOf(false) }
                val qs = if (hindi) listOf("क्या फ़ोन पर कोई आपको बता रहा है कि क्या करना है?",
                                           "क्या उन्होंने कहा कि आप गिरफ़्तार हैं या जाँच चल रही है?",
                                           "क्या उन्होंने यह बात परिवार से छिपाने को कहा?")
                         else listOf("Is someone on the phone telling you what to do?",
                                     "Did they say you are under arrest or being investigated?",
                                     "Did they ask you to keep this secret from family?")
                HorizontalDivider()
                Text(if (hindi) "सुरक्षा जाँच" else "Quick safety check", fontWeight = FontWeight.SemiBold)
                qs.forEachIndexed { i, q ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(q, Modifier.weight(1f), fontSize = 14.sp)
                        Chip(if (hindi) "हाँ" else "Yes", answers[i]) {
                            answers = answers.toMutableList().also { it[i] = !it[i] }
                        }
                    }
                }
                if (!sent) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        try {
                            val arr = org.json.JSONArray(); answers.forEach { arr.put(it) }
                            val res = postJson("$serverUrl/checkin",
                                JSONObject().put("case_id", r.optString("case_id")).put("answers", arr))
                            sent = true
                            if (res.optBoolean("escalated")) {
                                result = JSONObject(r.toString()).put("tier", 3).put("escalated", true)
                                    .put("reasons", res.optJSONArray("reasons"))
                                    .put("reasons_hi", res.optJSONArray("reasons_hi"))
                            }
                        } catch (e: Exception) { status = "Could not reach server: ${e.message}" }
                    }
                }) { Text(if (hindi) "जवाब भेजें" else "Submit answers") }
                else Text(if (hindi) "धन्यवाद। जवाब बैंक को भेजे गए।" else "Thanks. Your answers were sent to the bank.",
                    color = Color.Gray)
                HorizontalDivider()
            }

            when (tier) {
                2, 3 -> {
                    val hrs = r.optInt("hold_minutes", 240) / 60
                    val guardian = r.optString("guardian", "your guardian")
                    if (tier == 2) Text(if (hindi) "भुगतान $hrs घंटे के लिए रोका गया है। कॉल काटें, फिर दोबारा पुष्टि करें।"
                                        else "Held for $hrs hours. End your call, then confirm again.")
                    else Text(if (hindi) "बैंक अधिकारी जाँच के बाद ही भुगतान जारी करेंगे।"
                              else "A bank officer will review this payment before it is released.")
                    Text(if (hindi) "आपके परिवार को सूचित किया गया: $guardian" else "Guardian alerted: $guardian",
                        color = Color(0xFF1F6F8B), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        // Lets the user (or the app, in production) message the guardian directly.
                        val who = r.optString("customer", "Your family member")
                        val msg = "Thehro safety alert: $who is trying to send Rs ${r.optLong("amount")} " +
                                "while on a phone call. The bank has paused it. Please call $who now. " +
                                "Scammers pose as police and keep victims on the call."
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Message guardian"))
                    }) { Text(if (hindi) "परिवार को संदेश भेजें" else "Message guardian now") }
                    ToggleRow("Simulate active call (emulator only)", simulateCall) { simulateCall = it }
                    Text("Call active now: ${if (callNow()) "yes" else "no"}", color = Color.Gray)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        // Opens the dialer with 1930 filled in; the user presses call. No permission needed.
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
                    }) { Text(if (hindi) "साइबर हेल्पलाइन 1930 पर शिकायत करें" else "Report to cyber helpline 1930") }
                }

            }
        }
    }
    val rr = result
    if (rr != null) {
        // Action buttons stay pinned at the bottom so they never scroll off screen.
        val tier = rr.optInt("tier", 0)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status.isNotEmpty()) Text(status, fontWeight = FontWeight.SemiBold)
            when (tier) {
                0 -> Button(modifier = Modifier.fillMaxWidth(), onClick = { resetForm() }) { Text("New payment") }
                1 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { resetForm() }) {
                        Text(if (hindi) "रद्द करें" else "Cancel payment") }
                    Button(modifier = Modifier.weight(1f), onClick = { result = JSONObject().put("tier", 0) }) {
                        Text(if (hindi) "जारी रखें" else "Continue") }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { resetForm() }) {
                        Text(if (hindi) "रद्द करें" else "Cancel payment") }
                    Button(modifier = Modifier.weight(1.4f), onClick = {
                        scope.launch {
                            try {
                                val res = postJson("$serverUrl/confirm", JSONObject()
                                    .put("case_id", rr.optString("case_id"))
                                    .put("call_active", callNow()))
                                status = if (res.optString("status") == "released") "Released – payment sent"
                                         else res.optString("reason", "Still held")
                            } catch (e: Exception) { status = "Could not reach server: ${e.message}" }
                        }
                    }) { Text(if (tier == 2) "Call ended – confirm" else "Check status") }
                }
            }
        }
    }
    if (result == null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = !busy && amount.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true; status = ""
                        val body = JSONObject()
                            .put("user_id", "demo_user_1")
                            .put("payee_id", payee.ifBlank { "unknown" })
                            .put("payee_account_hash", if (account.isBlank()) "" else sha256(account.trim()))
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
                                .put("app_switches", Signals.appSwitches)
                                .put("on_device_score", Signals.pressureScore()))
                            .put("safety_mode", JSONObject()
                                .put("enabled", safetyOn)
                                .put("limit", safetyLimit.toLongOrNull() ?: 20000L))
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
        }
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

@Composable
fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Box(
        modifier
            .background(if (on) Color(0xFF6750A4) else Color(0xFFE7E0EC), RoundedCornerShape(18.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text((if (on) "✓ " else "") + label, color = if (on) Color.White else Color(0xFF1D1B20), fontSize = 14.sp)
    }
}
