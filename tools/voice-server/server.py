#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = [
#     "fastapi==0.115.*",
#     "uvicorn==0.32.*",
#     "python-multipart==0.0.*",
#     "mlx-whisper==0.4.3",
#     "webrtcvad-wheels==2.0.*",
# ]
# ///
"""Speech to text for WeiAssist, on an Apple Silicon Mac. See README.md here.

The API follows OpenAI's, so the app can use any Whisper server of that shape.
Audio lives only in a temp folder for the length of a request; transcripts are never logged.
"""
import asyncio
import logging
import os
import shutil
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path

import mlx_whisper
import numpy as np
import uvicorn
import webrtcvad
from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
from mlx_whisper.audio import SAMPLE_RATE, load_audio
from mlx_whisper.tokenizer import LANGUAGES, TO_LANGUAGE_CODE

HOST = os.environ.get("VOICE_HOST", "0.0.0.0")
PORT = int(os.environ.get("VOICE_PORT", "8765"))
MODEL = os.environ.get("VOICE_MODEL", "mlx-community/whisper-large-v3-turbo")
MAX_BYTES = 25 * 1024 * 1024
LOAD_RETRY_SECONDS = 10
# Whisper invents text on silence and noise, and this model's no_speech_prob stays 0 there, so
# audio goes to it only if a voice detector hears speech. Silence and soft noise measured 0.09 s.
VAD_FRAME_MS = 30
MIN_SPEECH_MS = 150
# Without a nudge, Chinese sometimes comes back in Traditional characters.
CHINESE_PROMPT = "以下是普通话的句子，使用简体中文。"

log = logging.getLogger("voice-server")

# MLX is not safe to share between threads: loading and every transcription stay on this one,
# which is also what queues requests that arrive together.
worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="whisper")
stop = threading.Event()
ready = False
load_error: str | None = None


def load_model() -> None:
    """Keeps trying, because at login the disk holding the model may not be mounted yet."""
    global ready, load_error
    while not stop.is_set():
        try:
            if shutil.which("ffmpeg") is None:
                raise RuntimeError("ffmpeg is not on PATH")
            started = time.monotonic()
            mlx_whisper.transcribe(np.zeros(SAMPLE_RATE, np.float32), path_or_hf_repo=MODEL, language="en", verbose=None)
            load_error = None
            ready = True
            log.info("model ready in %.1fs: %s", time.monotonic() - started, MODEL)
            return
        except Exception as e:
            load_error = str(e)
            log.warning("model not loaded, will retry: %s", e)
            stop.wait(LOAD_RETRY_SECONDS)


class BadAudio(Exception):
    pass


def has_speech(audio) -> bool:
    pcm = (np.array(audio) * 32767).astype(np.int16).tobytes()
    vad = webrtcvad.Vad(3)
    size = VAD_FRAME_MS * SAMPLE_RATE // 1000 * 2
    frames = sum(vad.is_speech(pcm[i : i + size], SAMPLE_RATE) for i in range(0, len(pcm) - size + 1, size))
    return frames * VAD_FRAME_MS >= MIN_SPEECH_MS


def transcribe(path: Path, language: str | None, prompt: str | None) -> tuple[str, float]:
    try:
        audio = load_audio(str(path))
    except RuntimeError as e:
        raise BadAudio("the file could not be decoded as audio") from e
    if audio.size == 0:
        raise BadAudio("the file has no audio in it")
    seconds = audio.size / SAMPLE_RATE
    if not has_speech(audio):
        return "", seconds
    if prompt is None and language == "zh":
        prompt = CHINESE_PROMPT
    result = mlx_whisper.transcribe(
        audio,
        path_or_hf_repo=MODEL,
        language=language,
        initial_prompt=prompt,
        condition_on_previous_text=False,
        verbose=None,
    )
    return result["text"].strip(), seconds


def error(status: int, message: str) -> JSONResponse:
    return JSONResponse({"error": {"message": message}}, status_code=status)


def language_code(value: str | None) -> tuple[str | None, bool]:
    """(Whisper's code or None for auto-detect, whether the value was understood)."""
    name = (value or "auto").strip().lower()
    if name == "auto":
        return None, True
    code = TO_LANGUAGE_CODE.get(name, name)
    return code, code in LANGUAGES


@asynccontextmanager
async def lifespan(_: FastAPI):
    worker.submit(load_model)
    yield
    stop.set()
    worker.shutdown(wait=True, cancel_futures=True)


app = FastAPI(title="WeiAssist voice server", lifespan=lifespan)


@app.middleware("http")
async def limit_upload(request: Request, call_next):
    if request.method == "POST":
        length = request.headers.get("content-length")
        if length is None or not length.isdigit():
            return error(411, "Content-Length is required")
        if int(length) > MAX_BYTES:
            return error(413, f"the file is over {MAX_BYTES // (1024 * 1024)} MB")
    return await call_next(request)


@app.get("/health")
async def health():
    body = {"ready": ready, "model": MODEL, "error": load_error}
    return JSONResponse(body, status_code=200 if ready else 503)


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: UploadFile = File(...),
    # Sent by OpenAI clients; this server has one model.
    model: str | None = Form(None),
    language: str | None = Form(None),
    prompt: str | None = Form(None),
    response_format: str = Form("json"),
):
    if response_format not in ("json", "text"):
        return error(400, "response_format must be json or text")
    code, known = language_code(language)
    if not known:
        return error(400, f"unknown language: {language}")
    if not ready:
        return error(503, "the model is not loaded yet")

    with tempfile.TemporaryDirectory(prefix="weiassist-voice-") as folder:
        # A fixed name: ffmpeg goes by the contents, and the client's name never reaches a path.
        path = Path(folder) / "audio"
        with path.open("wb") as out:
            await asyncio.to_thread(shutil.copyfileobj, file.file, out)
        started = time.monotonic()
        try:
            text, seconds = await asyncio.wrap_future(worker.submit(transcribe, path, code, prompt or None))
        except BadAudio as e:
            return error(400, str(e))
        except Exception:
            log.exception("transcription failed")
            return error(500, "transcription failed")

    log.info("transcribed %.1fs of audio in %.2fs", seconds, time.monotonic() - started)
    return PlainTextResponse(text) if response_format == "text" else {"text": text}


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    uvicorn.run(app, host=HOST, port=PORT)
