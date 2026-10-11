package de.homevoice.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.homevoice.assistant.service.AssistantStatus
import de.homevoice.assistant.settings.CUSTOM_WAKE_MODEL
import de.homevoice.assistant.settings.DEFAULT_SYSTEM_PROMPT
import de.homevoice.assistant.settings.Settings
import de.homevoice.assistant.wakeword.WakeWordDetector
import de.homevoice.assistant.wakeword.WakewordFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    initial: Settings,
    status: AssistantStatus,
    photoCount: Int,
    setupHint: String,
    onSave: (Settings) -> Unit,
    onTestHomeAssistant: suspend () -> String,
    onClose: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onChooseLauncher: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var haResult by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            draft = draft.copy(photoTreeUri = uri.toString())
        }
    }
    val pickWakeModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = importFile(context, uri)
            importMessage = path?.let { "Modell importiert: ${File(it).name}" } ?: "Datei muss auf .onnx enden"
            if (path != null) draft = draft.copy(wakeModel = CUSTOM_WAKE_MODEL, customWakeModelPath = path)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Einstellungen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Abbrechen") }
                    Button(onClick = { onSave(draft); onClose() }) { Text("Speichern") }
                }

                Status(setupHint)

                Section("Gemini")
                TextSetting("API-Key (Google AI Studio)", draft.geminiApiKey, secret = true) { draft = draft.copy(geminiApiKey = it) }
                TextSetting("Modell", draft.model) { draft = draft.copy(model = it) }
                TextSetting("Stimme (z.B. Kore, Puck, Charon, Aoede, Fenrir)", draft.voice) { draft = draft.copy(voice = it) }
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    label = { Text("System-Prompt") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { draft = draft.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) }) { Text("Standard-Prompt wiederherstellen") }
                SliderSetting("Nachlaufzeit nach Antwort", draft.followUpSeconds, 3..30, "s") { draft = draft.copy(followUpSeconds = it) }
                SwitchSetting("Echo-Unterdrückung (Kommunikationsmodus)", draft.communicationAudioMode) {
                    draft = draft.copy(communicationAudioMode = it)
                }
                SwitchSetting("Halbduplex (Mikro stumm während Gemini spricht, unterbrechen per Hotword)", draft.halfDuplex) { draft = draft.copy(halfDuplex = it) }

                Section("Hotword (openWakeWord, lokal)")
                Status(status.hotword?.let { "Aktiv: \"$it\"" } ?: status.hotwordError ?: "inaktiv")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = WakeWordDetector.BUILTIN_MODELS.toList() +
                        listOfNotNull(draft.customWakeModelPath.takeIf { it.isNotBlank() }
                            ?.let { CUSTOM_WAKE_MODEL to WakewordFiles.displayName(it) })
                    for ((id, name) in options) {
                        if (draft.wakeModel == id) Button(onClick = {}) { Text(name) }
                        else OutlinedButton(onClick = { draft = draft.copy(wakeModel = id) }) { Text(name) }
                    }
                }
                OutlinedButton(onClick = { pickWakeModel.launch(arrayOf("*/*")) }) { Text("Eigenes Modell (.onnx) importieren") }
                importMessage?.let { Status(it) }
                SliderSetting("Schwelle (niedriger = empfindlicher)", (draft.wakeThreshold * 100).roundToInt(), 10..95, "%") {
                    draft = draft.copy(wakeThreshold = it / 100f)
                }

                Section("Home Assistant (MCP)")
                Status(status.homeAssistant)
                TextSetting("URL (z.B. http://homeassistant.local:8123)", draft.haUrl, keyboard = KeyboardType.Uri) {
                    draft = draft.copy(haUrl = it)
                }
                Status("Basis-URL (eingebauter MCP-Server, mit Token) oder vollständige Webhook-URL (z.B. HA-MCP, ohne Token)")
                TextSetting("Access Token (optional)", draft.haToken, secret = true) { draft = draft.copy(haToken = it) }
                OutlinedButton(onClick = {
                    onSave(draft)
                    haResult = "Teste…"
                    scope.launch { haResult = onTestHomeAssistant() }
                }) { Text("Speichern & Verbindung testen") }
                haResult?.let { Status(it) }

                Section("Diashow & Anzeige")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Fotoordner wählen") }
                    Status(
                        if (draft.photoTreeUri.isBlank()) "kein Ordner gewählt"
                        else Uri.parse(draft.photoTreeUri).lastPathSegment?.substringAfter(':')?.ifBlank { "Hauptordner" }
                            .orEmpty() + if (draft.photoTreeUri == initial.photoTreeUri) " · $photoCount Fotos" else ""
                    )
                }
                SliderSetting("Bildwechsel alle", draft.slideIntervalSeconds, 5..300, "s") { draft = draft.copy(slideIntervalSeconds = it) }
                SwitchSetting("Sekunden anzeigen", draft.showSeconds) { draft = draft.copy(showSeconds = it) }
                SwitchSetting("Nachtmodus (nur gedimmte Uhr)", draft.nightModeEnabled) { draft = draft.copy(nightModeEnabled = it) }
                if (draft.nightModeEnabled) {
                    SliderSetting("Nachtmodus ab", draft.nightStartHour, 0..23, " Uhr") { draft = draft.copy(nightStartHour = it) }
                    SliderSetting("Nachtmodus bis", draft.nightEndHour, 0..23, " Uhr") { draft = draft.copy(nightEndHour = it) }
                }

                Section("System")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onOpenSystemSettings) { Text("Android-Einstellungen") }
                    OutlinedButton(onClick = onChooseLauncher) { Text("Standard-Launcher ändern") }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

/** Copies a picked wake word model into app storage. */
private suspend fun importFile(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: return@withContext null
    context.contentResolver.openInputStream(uri)?.use { WakewordFiles.save(context, name, it) }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider()
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) {
            { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Verbergen" else "Zeigen") } }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value$unit")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
