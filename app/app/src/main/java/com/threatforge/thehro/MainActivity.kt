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
            accountPasted = true
        } else if (new.length > old.length) {
            if (lastAccountCharAt == 0L || now - lastAccountCharAt > 1500) accountBursts++
            lastAccountCharAt = now
        }
        onInput()
    }

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

fun sha256(t: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

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
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1F6F8B), onPrimary = Color.White,
                secondary = Color(0xFF14213D), onSecondary = Color.White)) { ThehroApp() }
        }
    }

    override fun onStop() {
        super.onStop()
        Signals.appSwitches++
    }
}

@Composable
fun ThehroApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("http://localhost:8000") }

    var payee by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }

    var fdBroken by remember { mutableStateOf(false) }
    var newPayee by remember { mutableStateOf(true) }
    var limitRaised by remember { mutableStateOf(false) }
    var priorLarge by remember { mutableStateOf("0") }
    var simulateCall by remember { mutableStateOf(false) }

    var volunteerId by remember { mutableStateOf("") }
    var pressuredRun by remember { mutableStateOf(false) }

    var safetyOn by remember { mutableStateOf(false) }
    var safetyLimit by remember { mutableStateOf("20000") }

    var result by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun callNow() = isCallActive(ctx) || simulateCall

    var realCall by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            realCall = isCallActive(ctx)
            val w = (ctx as? android.app.Activity)?.window
            if (realCall) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            kotlinx.coroutines.delay(1000)
        }
    }

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
    Column(Modifier.fillMaxWidth().background(Color(0xFF14213D)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("🛡 Thehro Bank", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Protected by Thehro · demo", color = Color(0xFFBFC8DA), fontSize = 12.sp)
    }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (result == null) 8.dp else 4.dp)
    ) {
    ProvideTextStyle(if (result != null) LocalTextStyle.current.copy(fontSize = 13.sp) else LocalTextStyle.current) {
        val r = result
        if (r == null) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(volunteerId, { volunteerId = it }, label = { Text("Volunteer ID (test only)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Chip("Under pressure", pressuredRun) { pressuredRun = !pressuredRun }
            }
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
            LaunchedEffect(r) { if (tier >= 2) { kotlinx.coroutines.delay(400); speak(spokenEn, spokenHi) } }
            Box(Modifier.fillMaxWidth().background(color).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (!r.isNull("on_device_score") && r.has("on_device_score")) Text("On-device pressure score: %.2f".format(r.optDouble("on_device_score")),
                fontSize = 13.sp, color = Color.Gray)
            if (tier >= 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("हिंदी", hindi, Modifier.weight(1f)) { hindi = !hindi }
                Chip(if (hindi) "🔊 सुनें" else "🔊 Read aloud", false, Modifier.weight(1f)) { speak(spokenEn, spokenHi) }
            }
            if (r.optBoolean("released")) Text(
                if (hindi) "कॉल ख़त्म होने के बाद भुगतान जारी किया गया।" else "Released after the call ended. Your money is on its way.",
                fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
            if (r.optBoolean("offline")) Text(
                if (hindi) "बैंक की सुरक्षा जाँच उपलब्ध नहीं थी। ऐप ने फ़ोन पर ही एक सरल नियम लगाया।"
                else "Bank safety check was unreachable. The app used a simple on-phone rule instead.",
                fontSize = 13.sp, color = Color.Gray)
            val reasons = r.optJSONArray(if (hindi && r.has("reasons_hi")) "reasons_hi" else "reasons")
            if (reasons != null && reasons.length() > 0) {
                Text(if (hindi) "क्यों:" else "Why:", fontWeight = FontWeight.SemiBold)
                for (i in 0 until reasons.length()) Text("• ${reasons.getString(i)}")
            }
            if (tier >= 1) Text(if (hindi) safetyHi else safetyEn, fontWeight = FontWeight.SemiBold)

            if (tier >= 2) {
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

            if (tier == 2 && r.has("case_id")) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        val who = r.optString("customer", "Your family member")
                        val msg = "Thehro safety alert: $who is trying to send Rs ${r.optLong("amount")} " +
                                "while on a phone call. The bank has paused it. Please call $who now. " +
                                "Scammers pose as police and keep victims on the call."
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Message guardian"))
                    }) { Text(if (hindi) "परिवार को संदेश" else "Message guardian") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
                    }) { Text(if (hindi) "1930 पर शिकायत" else "Report to 1930") }
                    }
                }

            }
        }
    }
    }
    val rr = result
    if (rr != null) {
        val tier = rr.optInt("tier", 0)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status.isNotEmpty()) Text(status, fontWeight = FontWeight.SemiBold)
            if (tier >= 2) Chip(if (callNow()) "📞 Call active – tap to end call" else "Call ended", simulateCall,
                Modifier.fillMaxWidth()) { simulateCall = !simulateCall }
            when (tier) {
                0 -> Button(modifier = Modifier.fillMaxWidth(), onClick = { resetForm() }) { Text("New payment") }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (hindi) "क्या आप फिर भी यह भुगतान करना चाहते हैं?" else "Do you still want to make this payment?",
                        fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                            if (!rr.has("case_id")) resetForm() else scope.launch {
                                try {
                                    val res = postJson("$serverUrl/escalate", JSONObject().put("case_id", rr.optString("case_id")))
                                    result = JSONObject(rr.toString()).put("tier", res.optInt("tier", 2))
                                        .put("reasons", res.optJSONArray("reasons"))
                                        .put("reasons_hi", res.optJSONArray("reasons_hi"))
                                        .put("hold_minutes", res.optInt("hold_minutes", 240))
                                } catch (e: Exception) { status = "Could not reach server: ${e.message}" }
                            }
                        }) { Text(if (hindi) "नहीं, रोकें" else "No, hold it") }
                        Button(modifier = Modifier.weight(1f), onClick = { result = JSONObject().put("tier", 0) }) {
                            Text(if (hindi) "हाँ, भेजें" else "Yes, proceed") }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { resetForm() }) {
                        Text(if (hindi) "← वापस" else "← Back to home") }
                    Button(modifier = Modifier.weight(1.4f), onClick = {
                        scope.launch {
                            try {
                                val res = postJson("$serverUrl/confirm", JSONObject()
                                    .put("case_id", rr.optString("case_id"))
                                    .put("call_active", callNow()))
                                if (res.optString("status") == "released") {
                                    status = ""
                                    result = JSONObject().put("tier", 0).put("released", true)
                                } else status = res.optString("reason", "Still held")
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
                            catch (e: Exception) {
                                val amt = amount.toLongOrNull() ?: 0L
                                val limit = if (safetyOn) (safetyLimit.toLongOrNull() ?: 20000L) else 20000L
                                result = if (callNow() && amt >= limit) JSONObject().put("tier", 1).put("offline", true)
                                    .put("reasons", org.json.JSONArray()
                                        .put("You are on a call while making this payment")
                                        .put("Bank safety check is offline, so the app is being careful"))
                                    .put("reasons_hi", org.json.JSONArray()
                                        .put("भुगतान करते समय आप फ़ोन कॉल पर हैं")
                                        .put("बैंक की सुरक्षा जाँच ऑफ़लाइन है, इसलिए ऐप सावधानी बरत रहा है"))
                                else JSONObject().put("tier", 0).put("offline", true)
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Checking…" else "Send money") }
                if (status.isNotEmpty()) Text(status, color = Color(0xFFB0401A))
                Text("Call active now: ${if (callNow()) "yes" else "no"}", color = Color.Gray)
                if (realCall) Text("🔒 Screen sharing is hidden while you are on a call", color = Color(0xFF1F6F8B), fontSize = 13.sp)
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
            .background(if (on) Color(0xFF1F6F8B) else Color(0xFFE3ECEF), RoundedCornerShape(18.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text((if (on) "✓ " else "") + label, color = if (on) Color.White else Color(0xFF1D1B20), fontSize = 14.sp)
    }
}
