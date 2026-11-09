# Voice server

Speech to text for WeiAssist, run on your own Apple Silicon Mac with MLX Whisper.

Needs [uv](https://docs.astral.sh/uv/) and ffmpeg (`brew install uv ffmpeg`).

```sh
./server.py     # the first run downloads the model, about 1.6 GB
```

It listens on every network interface, port 8765, **with no authentication**: anyone on the same
network can use it. Use it on a network you trust. Audio is deleted after each request and
transcripts are not logged.

To run it at login (after a first run has downloaded the model; this mode stays offline):

```sh
./install-launchd.sh               # log: ~/Library/Logs/weiassist-voice.log
./install-launchd.sh --uninstall
```

| Variable | Default |
|---|---|
| `VOICE_HOST` | `0.0.0.0` |
| `VOICE_PORT` | `8765` |
| `VOICE_MODEL` | `mlx-community/whisper-large-v3-turbo` |

## API

`GET /health` answers 200 when the model is loaded and 503 until then.

`POST /v1/audio/transcriptions`, multipart, follows OpenAI's API:

| Field | |
|---|---|
| `file` | Audio in anything ffmpeg reads, up to 25 MB |
| `language` | `zh`, `en`, … or `auto` (the default) |
| `prompt` | Optional hint for the model |
| `response_format` | `json` (the default, `{"text": "…"}`) or `text` |

```sh
curl -F file=@clip.m4a -F language=zh http://<mac>:8765/v1/audio/transcriptions
```

TODO, optional: text to speech at `POST /v1/audio/speech`.
