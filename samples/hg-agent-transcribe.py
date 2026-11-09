#!/usr/bin/env python3
"""
Speech-to-text through Medha, for hg_agent.

    MEDHA_TOKEN=... python3 hg-agent-transcribe.py clip.wav --language te

Replaces running whisper.cpp in a proot'd Fedora beside Medha. Standard
library only -- no requests, no openai package, nothing to install under
Termux.

## Why this is not whisper

Nothing is wrong with whisper.cpp as software. The problem is where it was
running and what it cost to keep there:

  - **Memory.** With Gemma resident the device has about 1.7 GB free.
    whisper's peak RSS is ~889 MB for `small` and ~2.14 GB for `medium`.
    `medium` does not fit at all; `small` barely does. So a whisper design
    meant either no concurrent STT and inference, or staying on the small
    models and accepting their modest Telugu accuracy -- permanently.
  - **Speed.** 85s for a 24s clip is ~3.5x realtime. That was never a
    whisper limit: an RK3588, a 2022 SBC weaker than this phone, does
    `small` at RTF 0.47. The likely cause was core parking -- a proot'd
    process is not a foreground Android app, so Android's scheduler puts
    it on the little cores, which is a 3-4x hit by itself on big.LITTLE.
  - **Arbitration.** Two processes competing for the same SoC with no
    shared queue. Medha already owns thermal headroom and an inference
    scheduler; a sidecar is outside both.

The encoder behind /v1/audio/transcriptions is already in memory. No second
model, no container, and transcription queues against generation through
one scheduler instead of fighting it.

## Check before you switch

Medha only advertises audio when the AAR supports it AND the loaded model
actually initialized with an audio backend. Call capabilities() first and
keep whatever fallback you have until it returns True -- a model without an
audio encoder will report False, correctly, and the endpoint answers 501.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_BASE = os.environ.get("MEDHA_BASE", "http://127.0.0.1:8001")
DEFAULT_TOKEN = os.environ.get("MEDHA_TOKEN", "")

# Medha's own ceiling. Longer clips must be chunked; see chunk_wav() below.
MAX_SECONDS = 30.0


class MedhaError(RuntimeError):
    """A request Medha refused, carrying the status and machine-readable code."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.detail = message

    @property
    def should_fall_back(self) -> bool:
        """
        True when this build cannot do audio at all, as opposed to this
        request being bad.

        The distinction matters: on 501 you switch to your other STT path
        and stop retrying. On 413 you chunk and retry. On 415 you convert.
        """
        return self.status == 501 or self.code in ("audio_unsupported",)


@dataclass
class Capabilities:
    text: bool
    vision: bool
    audio: bool
    aar_audio: bool
    mode: str
    error: str | None

    @property
    def audio_blocked_by_model(self) -> bool:
        """
        The AAR can do audio but this engine did not come up with it --
        i.e. the loaded .litertlm has no audio encoder. Swapping the model
        would fix it; a different Medha build would not.
        """
        return self.aar_audio and not self.audio


def capabilities(base: str = DEFAULT_BASE) -> Capabilities:
    """Reads /health. No token needed -- it is the one open endpoint."""
    with urllib.request.urlopen(f"{base}/health", timeout=10) as r:
        h = json.loads(r.read().decode("utf-8"))
    caps = h.get("capabilities", {})
    aar = h.get("aar_supports", {})
    return Capabilities(
        text=bool(caps.get("text")),
        vision=bool(caps.get("vision")),
        audio=bool(caps.get("audio")),
        aar_audio=bool(aar.get("audio")),
        mode=h.get("multimodal_mode", "unknown"),
        error=h.get("multimodal_error"),
    )


def _multipart(fields: dict[str, str], filename: str, data: bytes) -> tuple[bytes, str]:
    """
    Builds a multipart/form-data body.

    Hand-rolled because the standard library has no multipart encoder and
    pulling in `requests` for one POST is not worth it under Termux. The
    boundary is a uuid4, so it cannot collide with binary audio content --
    a fixed boundary string can, and the failure is a truncated upload that
    looks like a corrupt file.
    """
    boundary = f"----medha{uuid.uuid4().hex}"
    out = bytearray()
    for k, v in fields.items():
        if v is None:
            continue
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode()
        out += f"{v}\r\n".encode()

    ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    out += f"--{boundary}\r\n".encode()
    out += (
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
    ).encode()
    out += f"Content-Type: {ctype}\r\n\r\n".encode()
    out += data
    out += f"\r\n--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def transcribe(
    path: str | Path,
    *,
    language: str | None = None,
    prompt: str | None = None,
    response_format: str = "json",
    base: str = DEFAULT_BASE,
    token: str = DEFAULT_TOKEN,
    timeout: float = 180.0,
) -> str:
    """
    Transcribes one audio file and returns the text.

    Accepts WAV and raw 16-bit PCM. Raw PCM is what AudioRecord gives you;
    Medha wraps it in a header on arrival, so there is nothing to do on
    this side.

    Pass `language` whenever you know it. For Telugu it is close to
    load-bearing: left to autodetect, the model sometimes transliterates
    into Latin characters instead of writing Telugu script, which is a
    different and much less useful output.

    :raises MedhaError: with .should_fall_back set when this build has no
        audio capability at all.
    """
    p = Path(path)
    data = p.read_bytes()

    fields: dict[str, str] = {
        # Required by OpenAI's schema; Medha accepts and ignores it,
        # serving whatever model is loaded.
        "model": "whisper-1",
        "response_format": response_format,
    }
    if language:
        fields["language"] = language
    if prompt:
        fields["prompt"] = prompt

    body, content_type = _multipart(fields, p.name, data)
    req = urllib.request.Request(
        f"{base}/v1/audio/transcriptions",
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        payload: dict[str, Any] = {}
        try:
            payload = json.loads(e.read().decode("utf-8"))
        except Exception:
            # A proxy or a crash can produce a body that is not JSON. Do
            # not let that mask the status, which is the part worth acting
            # on -- a bare "JSONDecodeError" tells the caller nothing about
            # whether to fall back, chunk, or convert.
            pass
        raise MedhaError(
            e.code,
            payload.get("code", "http_error"),
            payload.get("error") or payload.get("message") or e.reason or "",
        ) from None

    if response_format == "text":
        return raw
    parsed = json.loads(raw)
    return parsed.get("text", "")


def chunk_wav(path: str | Path, seconds: float = 25.0) -> list[bytes]:
    """
    Splits a WAV into chunks under Medha's 30s limit.

    25s rather than 30 leaves headroom: the limit is checked against the
    real parsed duration, and a chunk that lands at 30.02s is refused.

    Cuts on raw frame boundaries, which means it will cut mid-word. For a
    transcript that matters -- prefer splitting on silence upstream if you
    have VAD. This is the fallback for when you do not.
    """
    import struct
    import wave

    out: list[bytes] = []
    with wave.open(str(path), "rb") as w:
        rate = w.getframerate()
        per_chunk = int(rate * seconds)
        params = w.getparams()
        while True:
            frames = w.readframes(per_chunk)
            if not frames:
                break
            buf = bytearray()
            # Rebuild a minimal WAV around each chunk. struct is used
            # rather than the wave module's writer because that needs a
            # real file object and this stays in memory.
            n = len(frames)
            buf += b"RIFF" + struct.pack("<I", 36 + n) + b"WAVE"
            buf += b"fmt " + struct.pack(
                "<IHHIIHH",
                16, 1, params.nchannels, rate,
                rate * params.nchannels * params.sampwidth,
                params.nchannels * params.sampwidth,
                params.sampwidth * 8,
            )
            buf += b"data" + struct.pack("<I", n) + frames
            out.append(bytes(buf))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Transcribe audio through Medha.")
    ap.add_argument("file", help="WAV or raw 16-bit PCM")
    ap.add_argument("--language", "-l", help="ISO-639 code, e.g. te, en, hi")
    ap.add_argument("--prompt", "-p", help="spelling and name hints")
    ap.add_argument("--format", "-f", default="json",
                    choices=["json", "text", "verbose_json"])
    ap.add_argument("--base", default=DEFAULT_BASE)
    args = ap.parse_args()

    if not DEFAULT_TOKEN:
        print("MEDHA_TOKEN is not set.", file=sys.stderr)
        return 2

    # Check first. A model without an audio encoder answers 501, and you
    # want that as a clear message rather than as a failed transcription.
    try:
        caps = capabilities(args.base)
    except Exception as e:
        print(f"Medha is not reachable at {args.base}: {e}", file=sys.stderr)
        return 1

    if not caps.audio:
        print(f"Medha reports no audio capability (mode: {caps.mode}).", file=sys.stderr)
        if caps.audio_blocked_by_model:
            print(
                "The AAR supports audio but the loaded model did not initialize "
                "with an audio backend -- these weights have no audio encoder. "
                "A different .litertlm would fix this; a different Medha build "
                "would not.",
                file=sys.stderr,
            )
            if caps.error:
                print(f"  engine said: {caps.error}", file=sys.stderr)
        else:
            print(
                "This build of the LiteRT-LM AAR has no audio binding. "
                "See drawer -> Multimodal support for the probe report.",
                file=sys.stderr,
            )
        return 3

    try:
        text = transcribe(
            args.file,
            language=args.language,
            prompt=args.prompt,
            response_format=args.format,
            base=args.base,
        )
    except MedhaError as e:
        print(str(e), file=sys.stderr)
        if e.code == "audio_too_long":
            print(
                f"Split it: chunk_wav('{args.file}') returns pieces under the "
                f"{MAX_SECONDS:.0f}s limit.",
                file=sys.stderr,
            )
        elif e.code == "audio_format_unsupported":
            print(
                "Convert first:  ffmpeg -i in.m4a -ar 16000 -ac 1 -c:a pcm_s16le out.wav",
                file=sys.stderr,
            )
        return 1

    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
