package com.lanqi.weiassist.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanqi.weiassist.AppViewModel
import com.lanqi.weiassist.R
import com.lanqi.weiassist.agent.VoiceServer
import com.lanqi.weiassist.agent.VoiceServer.Transcript
import com.lanqi.weiassist.agent.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** The text box speech goes into. */
enum class DictationTarget { NewSession, Reply, Supervisor }

/**
 * Speech to text through the user's voice server: records, sends, and hands the text to [deliver], which puts it in the
 * box to be read and sent by hand (a misheard word must not become an action on the phone). One clip at a time; a clip
 * that couldn't be transcribed stays on disk until retried or discarded. Main thread only.
 */
class Dictation(
    private val context: Context,
    private val settings: VoiceSettings,
    private val scope: CoroutineScope,
    private val deliver: (DictationTarget, String) -> Unit,
) {
    enum class Problem(val canRetry: Boolean = false) { Permission, Microphone, TooShort, NoSpeech, Unreachable(true), Loading(true), Rejected(true) }

    sealed interface State {
        val target: DictationTarget
        data class Recording(override val target: DictationTarget, val seconds: Int) : State
        data class Transcribing(override val target: DictationTarget) : State
        data class Failed(override val target: DictationTarget, val problem: Problem, val detail: String? = null) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val clip = File(File(context.cacheDir, "voice").apply { mkdirs() }, "clip.m4a")
    private var recorder: MediaRecorder? = null
    private var job: Job? = null
    private var startedAt = 0L

    fun start(target: DictationTarget) {
        if (_state.value is State.Recording || _state.value is State.Transcribing) return
        clip.delete()
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_MILLIS)
            r.setOutputFile(clip.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
            r.prepare()
            r.start()
        } catch (_: Exception) {
            r.release()
            clip.delete()
            _state.value = State.Failed(target, Problem.Microphone)
            return
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        _state.value = State.Recording(target, 0)
        job = scope.launch {
            while (isActive) {
                // Square root: speech sits low on this scale, and the meter would hardly move.
                _level.value = sqrt(r.maxAmplitude / 32767f)
                _state.value = State.Recording(target, ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
                delay(100)
            }
        }
    }

    /** Finish recording and transcribe what was said. */
    fun stop() {
        val recording = _state.value as? State.Recording ?: return
        val millis = SystemClock.elapsedRealtime() - startedAt
        if (finishRecorder() && millis >= MIN_MILLIS) {
            send(recording.target)
        } else {
            clip.delete()
            _state.value = State.Failed(recording.target, Problem.TooShort)
        }
    }

    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        if (failed.problem.canRetry && clip.exists()) send(failed.target)
    }

    /** Stop whatever is happening and forget the clip. */
    fun cancel() {
        if (_state.value is State.Recording) finishRecorder()
        job?.cancel()
        clip.delete()
        _state.value = null
    }

    fun permissionDenied(target: DictationTarget) {
        _state.value = State.Failed(target, Problem.Permission)
    }

    private fun send(target: DictationTarget) {
        _state.value = State.Transcribing(target)
        job = scope.launch {
            val url = settings.url.value
            val result = if (url == null) Transcript.Unreachable else VoiceServer.transcribe(url, clip, settings.language.value)
            _state.value = when (result) {
                is Transcript.Text -> {
                    clip.delete()
                    if (result.text.isBlank()) State.Failed(target, Problem.NoSpeech)
                    else {
                        deliver(target, result.text)
                        null
                    }
                }
                Transcript.Unreachable -> State.Failed(target, Problem.Unreachable)
                Transcript.Loading -> State.Failed(target, Problem.Loading)
                is Transcript.Rejected -> State.Failed(target, Problem.Rejected, result.message)
            }
        }
    }

    /** Whether a playable clip was written: stop() throws if nothing was recorded yet. */
    private fun finishRecorder(): Boolean {
        job?.cancel()
        _level.value = 0f
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            r.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder() = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val MAX_MILLIS = 120_000
        const val MIN_MILLIS = 500L
    }
}

/** The mic for a text box's end, or null while no voice server is set (Settings → Voice input). */
@Composable
fun dictationMic(vm: AppViewModel, target: DictationTarget): (@Composable () -> Unit)? {
    val url by vm.voice.url.collectAsStateWithLifecycle()
    if (url == null) return null
    return { DictationMicButton(vm.dictation, target) }
}

@Composable
private fun DictationMicButton(dictation: Dictation, target: DictationTarget) {
    val ctx = LocalContext.current
    val state by dictation.state.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start(target) else dictation.permissionDenied(target)
    }
    IconButton(
        enabled = state == null || state is Dictation.State.Failed,
        onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictation.start(target)
            else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
    ) { AppIcon(R.drawable.ic_mic, stringResource(R.string.voice_input)) }
}

/** Above a text box while speech for it is being recorded or transcribed, or has failed. */
@Composable
fun DictationBar(dictation: Dictation, target: DictationTarget, modifier: Modifier = Modifier) {
    val state by dictation.state.collectAsStateWithLifecycle()
    val s = state?.takeIf { it.target == target } ?: return
    // Android silences the mic of an app in the background: keep what was said so far. Same when the box goes away.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dictation.stop() }
    DisposableEffect(Unit) { onDispose { dictation.stop() } }
    Surface(modifier.fillMaxWidth().padding(bottom = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                is Dictation.State.Recording -> {
                    val level by dictation.level.collectAsStateWithLifecycle()
                    AppIcon(R.drawable.ic_mic, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Text("%d:%02d".format(s.seconds / 60, s.seconds % 60), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator({ level }, Modifier.weight(1f))
                    TextButton(onClick = dictation::cancel) { Text(stringResource(R.string.cancel)) }
                    FilledTonalButton(onClick = dictation::stop) { Text(stringResource(R.string.voice_done)) }
                }
                is Dictation.State.Transcribing -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.voice_transcribing), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = dictation::cancel) { Text(stringResource(R.string.cancel)) }
                }
                is Dictation.State.Failed -> {
                    Column(Modifier.weight(1f)) {
                        Text(problemText(s), Modifier.padding(top = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = dictation::cancel) { Text(stringResource(if (s.problem.canRetry) R.string.voice_discard else R.string.voice_dismiss)) }
                            if (s.problem.canRetry) FilledTonalButton(onClick = dictation::retry) { Text(stringResource(R.string.voice_retry)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun problemText(s: Dictation.State.Failed): String = when (s.problem) {
    Dictation.Problem.Permission -> stringResource(R.string.voice_problem_permission)
    Dictation.Problem.Microphone -> stringResource(R.string.voice_problem_microphone)
    Dictation.Problem.TooShort -> stringResource(R.string.voice_problem_short)
    Dictation.Problem.NoSpeech -> stringResource(R.string.voice_problem_no_speech)
    Dictation.Problem.Unreachable -> stringResource(R.string.voice_unreachable)
    Dictation.Problem.Loading -> stringResource(R.string.voice_loading)
    Dictation.Problem.Rejected -> stringResource(R.string.voice_problem_rejected, s.detail.orEmpty())
}
