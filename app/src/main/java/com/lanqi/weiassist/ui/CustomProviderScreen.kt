package com.lanqi.weiassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.CustomModel
import com.lanqi.weiassist.agent.CustomProvider
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Model lists survive rotation and process death as JSON. */
private val ModelListSaver = Saver<List<CustomModel>, String>(
    save = { JSONArray(it.map(CustomModel::toJson)).toString() },
    restore = { s -> JSONArray(s).let { a -> (0 until a.length()).map { CustomModel.fromJson(a.getJSONObject(it), true) } } },
)

/**
 * Add or edit one of the user's own OpenAI-compatible providers: name, base URL, API key and models
 * (picked from the server's list, or typed). Each model's context window and image support come from the
 * server's list, else Pi's catalog, else defaults, and can be corrected. [editing] null = a new one.
 * Saving with a key makes it the provider in use (see AgentController.saveCustomProvider).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomProviderScreen(vm: AppViewModel, editing: CustomProvider?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(editing?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(editing?.baseUrl ?: "") }
    var key by rememberSaveable { mutableStateOf("") }
    // The chosen models, in order; and what the server listed (not saved).
    var selected by rememberSaveable(stateSaver = ModelListSaver) { mutableStateOf(editing?.models.orEmpty()) }
    var listed by rememberSaveable(stateSaver = ModelListSaver) { mutableStateOf(emptyList<CustomModel>()) }
    var typed by rememberSaveable { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val state by vm.state.collectAsStateWithLifecycle()
    val hasKey = editing != null && state.providers.any { it.id == editing.id && it.credential != null }
    val trimmedUrl = url.trim()
    val urlOk = trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://")
    val canSave = name.isNotBlank() && urlOk && selected.isNotEmpty() && (key.isNotBlank() || hasKey)
    val ollama = Regex(""":11434(/|$)""").containsMatchIn(trimmedUrl) || "ollama" in trimmedUrl.lowercase()

    // Models saved with nothing known (e.g. by an older version): ask Pi's catalog once, keeping what the user set.
    LaunchedEffect(editing?.id) {
        val unknown = selected.filter { it.source == null }.map { it.id }
        if (unknown.isEmpty()) return@LaunchedEffect
        val found = vm.describeCustomModels(unknown).filter { it.source != null }.associateBy { it.id }
        if (found.isNotEmpty()) selected = selected.map { m -> if (m.source == null) found[m.id] ?: m else m }
    }

    fun toggle(m: CustomModel) {
        selected = if (selected.any { it.id == m.id }) selected.filter { it.id != m.id } else selected + m
    }
    fun update(m: CustomModel) { selected = selected.map { if (it.id == m.id) m else it } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (editing == null) R.string.custom_add_title else R.string.custom_edit_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.custom_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.custom_name)) }, placeholder = { Text(stringResource(R.string.custom_name_hint)) })
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.custom_url)) }, placeholder = { Text("https://example.com/v1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text(stringResource(if (trimmedUrl.startsWith("http://")) R.string.custom_url_http else R.string.custom_url_hint)) },
            isError = url.isNotBlank() && !urlOk)
        OutlinedTextField(key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.custom_key)) },
            placeholder = { Text(stringResource(if (hasKey) R.string.custom_key_keep else R.string.custom_key_hint)) })

        // Models: fetch the server's list, or type ids.
        Text(stringResource(R.string.custom_models), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = urlOk && !fetching,
                onClick = {
                    fetching = true; fetchError = null
                    scope.launch {
                        runCatching { vm.fetchCustomModels(url, key, editing?.id) }
                            .onSuccess { (base, ms) ->
                                url = base
                                listed = ms
                                if (ms.isEmpty()) fetchError = "—"
                                // One model and nothing chosen yet: choose it.
                                if (ms.size == 1 && selected.isEmpty()) selected = ms
                            }
                            .onFailure { fetchError = it.message ?: it.javaClass.simpleName }
                        fetching = false
                    }
                },
            ) { Text(stringResource(R.string.custom_fetch)) }
            if (fetching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        fetchError?.let { Text(stringResource(R.string.custom_fetch_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (listed.isNotEmpty()) {
            Text(stringResource(R.string.custom_models_pick), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listed.forEach { m -> FilterChip(selected = selected.any { it.id == m.id }, onClick = { toggle(m) }, label = { Text(m.id) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.custom_model_id)) })
            TextButton(enabled = typed.isNotBlank(), onClick = {
                val ids = typed.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() && selected.none { s -> s.id == it } }.distinct()
                typed = ""
                // Typed ids get what Pi's catalog knows of them.
                scope.launch { selected = selected + vm.describeCustomModels(ids) }
            }) { Text(stringResource(R.string.custom_model_add)) }
        }

        // The chosen models' settings.
        if (selected.isNotEmpty()) {
            Text(stringResource(R.string.custom_chosen), style = MaterialTheme.typography.titleSmall)
            selected.forEach { m -> ModelSettings(m, onChange = ::update, onRemove = { toggle(m) }) }
        }
        if (ollama) Text(stringResource(R.string.custom_ollama_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

        saveError?.let { Text(stringResource(R.string.custom_save_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = canSave && !saving, onClick = {
                val p = CustomProvider(editing?.id ?: CustomProvider.newId(), name.trim(), CustomProvider.normalizeBaseUrl(url), selected)
                saving = true; saveError = null
                // The form closes only once the provider and its key are really saved.
                scope.launch {
                    val problem = vm.saveCustomProvider(p, key)
                    saving = false
                    if (problem == null) onClose() else saveError = problem
                }
            }) { Text(stringResource(R.string.custom_save)) }
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
            if (editing != null) TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.custom_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete && editing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.custom_delete_title, editing.name)) },
        text = { Text(stringResource(R.string.custom_delete_text)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; vm.deleteCustomProvider(editing.id); onClose() }) {
                Text(stringResource(R.string.custom_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One chosen model: its context window (tokens), whether it reads images and thinks, and where those came from. */
@Composable
private fun ModelSettings(m: CustomModel, onChange: (CustomModel) -> Unit, onRemove: () -> Unit) {
    // The field's text, kept while typing (an empty field = unknown = 128K); refilled when the value comes
    // from elsewhere (Pi's catalog filling in a saved model after the form opened).
    var ctx by rememberSaveable(m.id, m.source) { mutableStateOf(if (m.contextWindow > 0) m.contextWindow.toString() else "") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_cancel, stringResource(R.string.custom_model_remove), Modifier.size(20.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    ctx,
                    { t ->
                        ctx = t.filter(Char::isDigit).take(9)
                        onChange(m.copy(contextWindow = ctx.toIntOrNull() ?: 0, source = CustomModel.SOURCE_USER))
                    },
                    singleLine = true, modifier = Modifier.width(170.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.custom_context)) },
                    placeholder = { Text(CustomModel.DEFAULT_CONTEXT.toString()) },
                )
                Checkbox(m.images, { onChange(m.copy(images = it)) })
                Text(stringResource(R.string.custom_model_images), style = MaterialTheme.typography.bodyMedium)
            }
            // Thinks: it takes a reasoning effort, so the model picker offers thinking levels for it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(m.reasoning, { onChange(m.copy(reasoning = it)) })
                Text(stringResource(R.string.custom_model_reasoning), style = MaterialTheme.typography.bodyMedium)
            }
            val source = m.source
            Text(
                when {
                    source == CustomModel.SOURCE_SERVER -> stringResource(R.string.custom_src_server)
                    source == CustomModel.SOURCE_USER -> stringResource(R.string.custom_src_user)
                    source?.startsWith("pi:") == true -> stringResource(R.string.custom_src_pi, source.removePrefix("pi:"))
                    else -> stringResource(R.string.custom_src_none)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
