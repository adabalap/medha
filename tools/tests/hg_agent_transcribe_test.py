#!/usr/bin/env python3
"""
Tests for samples/hg-agent-transcribe.py.

    python3 -I tools/tests/hg_agent_transcribe_test.py

Real HTTP round trips against a stub server on loopback, not mocks. The
risky part of that sample is the hand-rolled multipart encoder -- the
standard library has none, and `requests` is not worth a dependency under
Termux -- and a multipart bug produces a truncated upload that looks like a
corrupt audio file on the far side. Asserting the bytes parse back with
Python's own email parser is the only way to know it is right.
"""

import json
import struct
import sys
import threading
import wave
from email.parser import BytesParser
from email.policy import default as default_policy
from http.server import BaseHTTPRequestHandler, HTTPServer
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "samples" / "hg-agent-transcribe.py"

# The sample's filename has a hyphen, so it is not importable by name.
mod = SourceFileLoader("hg_agent_transcribe", str(SAMPLE)).load_module()

failures = 0
checks = 0


def check(what, ok):
    global failures, checks
    checks += 1
    if ok:
        print(f"  ok    {what}")
    else:
        print(f"  FAIL  {what}")
        failures += 1


def eq(what, actual, expected):
    global failures, checks
    checks += 1
    if actual == expected:
        print(f"  ok    {what}")
    else:
        print(f"  FAIL  {what}\n          expected: {expected!r}\n          actual:   {actual!r}")
        failures += 1


def wav_bytes(seconds=1.0, rate=16000, channels=1, width=2):
    """A silent WAV, built with the stdlib so the fixture is not self-referential."""
    import io
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(b"\x00" * int(rate * seconds) * channels * width)
    return buf.getvalue()


# --- stub server ---------------------------------------------------------

received = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        body = json.dumps(received.get("health", {
            "status": "ok",
            "capabilities": {"text": True, "vision": True, "audio": True},
            "aar_supports": {"vision": True, "audio": True},
            "multimodal_mode": "media on GPU",
        })).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        received["content_type"] = self.headers.get("Content-Type", "")
        received["auth"] = self.headers.get("Authorization", "")
        received["raw"] = raw

        # Parse it the way a real server would, with the stdlib's own
        # parser rather than by eyeballing the bytes.
        header = f"Content-Type: {received['content_type']}\r\nMIME-Version: 1.0\r\n\r\n".encode()
        msg = BytesParser(policy=default_policy).parsebytes(header + raw)
        parts = {}
        files = {}
        for part in msg.iter_parts():
            disp = part.get_content_disposition()
            name = part.get_param("name", header="content-disposition")
            if disp == "form-data" and part.get_filename():
                files[name] = (part.get_filename(), part.get_payload(decode=True))
            elif disp == "form-data":
                parts[name] = part.get_payload(decode=True).decode()
        received["fields"] = parts
        received["files"] = files

        status, body = received.get("reply", (200, {"text": "నమస్కారం"}))
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main():
    srv = HTTPServer(("127.0.0.1", 0), Handler)
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    tmp = Path(__file__).resolve().parent / "_tmp_clip.wav"
    tmp.write_bytes(wav_bytes(1.0))

    try:
        print("hg-agent-transcribe — capabilities()")
        caps = mod.capabilities(base)
        check("audio true", caps.audio)
        check("vision true", caps.vision)
        eq("mode", caps.mode, "media on GPU")
        check("not blocked by model", not caps.audio_blocked_by_model)

        print("\nhg-agent-transcribe — the model-has-no-encoder case is distinguishable")
        # The distinction a client must act on: the AAR can do audio, this
        # model cannot. Swap the model, not the APK.
        received["health"] = {
            "status": "ok",
            "capabilities": {"text": True, "vision": False, "audio": False},
            "aar_supports": {"vision": True, "audio": True},
            "multimodal_mode": "text-only",
            "multimodal_error": "IllegalStateException: no audio encoder in model",
        }
        c2 = mod.capabilities(base)
        check("audio false", not c2.audio)
        check("but the AAR supports it", c2.aar_audio)
        check("so it is blamed on the model", c2.audio_blocked_by_model)
        check("and the reason is carried", "no audio encoder" in (c2.error or ""))

        received["health"] = {
            "status": "ok",
            "capabilities": {"text": True, "vision": False, "audio": False},
            "aar_supports": {"vision": False, "audio": False},
            "multimodal_mode": "text-only",
        }
        c3 = mod.capabilities(base)
        check("an AAR with no binding is NOT blamed on the model",
              not c3.audio_blocked_by_model)

        print("\nhg-agent-transcribe — the multipart body parses back correctly")
        text = mod.transcribe(tmp, language="te", prompt="names: Spock",
                              base=base, token="tok123")
        eq("returns the transcript", text, "నమస్కారం")
        check("content-type is multipart", received["content_type"].startswith("multipart/form-data"))
        check("boundary is declared", "boundary=" in received["content_type"])
        eq("bearer token sent", received["auth"], "Bearer tok123")
        eq("model field", received["fields"].get("model"), "whisper-1")
        eq("language field", received["fields"].get("language"), "te")
        eq("prompt field", received["fields"].get("prompt"), "names: Spock")
        eq("response_format field", received["fields"].get("response_format"), "json")
        check("file part present", "file" in received["files"])
        eq("filename preserved", received["files"]["file"][0], "_tmp_clip.wav")

        # The bug this test exists for: a multipart encoder that mangles
        # binary content produces a file that is subtly wrong rather than
        # obviously missing.
        sent = received["files"]["file"][1]
        eq("file bytes arrive intact", sent, tmp.read_bytes())
        check("and are a real WAV", sent[:4] == b"RIFF" and sent[8:12] == b"WAVE")

        print("\nhg-agent-transcribe — optional fields are omitted, not sent empty")
        mod.transcribe(tmp, base=base, token="t")
        check("no language key", "language" not in received["fields"])
        check("no prompt key", "prompt" not in received["fields"])

        print("\nhg-agent-transcribe — binary audio cannot collide with the boundary")
        # A fixed boundary string can appear inside binary audio and
        # truncate the upload. The uuid4 boundary is what prevents it; this
        # feeds bytes designed to break a naive encoder.
        nasty = tmp.parent / "_tmp_nasty.wav"
        payload = b"\r\n--" * 200 + b"\x00\xff" * 500
        hdr = b"RIFF" + struct.pack("<I", 36 + len(payload)) + b"WAVE"
        hdr += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
        hdr += b"data" + struct.pack("<I", len(payload))
        nasty.write_bytes(hdr + payload)
        try:
            mod.transcribe(nasty, base=base, token="t")
            eq("boundary-like bytes survive", received["files"]["file"][1], nasty.read_bytes())
        finally:
            nasty.unlink(missing_ok=True)

        print("\nhg-agent-transcribe — errors carry status and code")
        received["reply"] = (501, {"error": "no audio on this build", "code": "audio_unsupported"})
        try:
            mod.transcribe(tmp, base=base, token="t")
            check("raises on 501", False)
        except mod.MedhaError as e:
            eq("status", e.status, 501)
            eq("code", e.code, "audio_unsupported")
            check("tells the caller to fall back", e.should_fall_back)

        received["reply"] = (413, {"error": "too long", "code": "audio_too_long"})
        try:
            mod.transcribe(tmp, base=base, token="t")
            check("raises on 413", False)
        except mod.MedhaError as e:
            eq("code", e.code, "audio_too_long")
            # Chunk and retry, do NOT abandon the endpoint.
            check("is not a fall-back condition", not e.should_fall_back)

        print("\nhg-agent-transcribe — a non-JSON error body still yields the status")
        # A proxy or a crash can return HTML. Losing the status to a
        # JSONDecodeError would tell the caller nothing about what to do.
        class HtmlHandler(Handler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                body = b"<html>502 Bad Gateway</html>"
                self.send_response(502)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        srv2 = HTTPServer(("127.0.0.1", 0), HtmlHandler)
        base2 = f"http://127.0.0.1:{srv2.server_address[1]}"
        threading.Thread(target=srv2.serve_forever, daemon=True).start()
        try:
            mod.transcribe(tmp, base=base2, token="t")
            check("raises on 502", False)
        except mod.MedhaError as e:
            eq("status survives a non-JSON body", e.status, 502)
            check("does not claim fall-back", not e.should_fall_back)
        finally:
            srv2.shutdown()

        print("\nhg-agent-transcribe — chunk_wav splits under the limit")
        long_clip = tmp.parent / "_tmp_long.wav"
        long_clip.write_bytes(wav_bytes(70.0))
        try:
            chunks = mod.chunk_wav(long_clip, seconds=25.0)
            eq("70s at 25s each is three chunks", len(chunks), 3)
            for i, c in enumerate(chunks):
                check(f"chunk {i} is a RIFF/WAVE file",
                      c[:4] == b"RIFF" and c[8:12] == b"WAVE")
                # Each chunk must be independently readable, and under the
                # limit -- a chunk at 30.02s is refused by the real server.
                import io
                with wave.open(io.BytesIO(c), "rb") as w:
                    secs = w.getnframes() / w.getframerate()
                    check(f"chunk {i} is under 30s ({secs:.1f}s)", secs <= 30.0)
                    eq(f"chunk {i} keeps the sample rate", w.getframerate(), 16000)
            total = sum(len(c) - 44 for c in chunks)
            eq("no audio is lost across the split", total, 70 * 32000)
        finally:
            long_clip.unlink(missing_ok=True)

    finally:
        tmp.unlink(missing_ok=True)
        srv.shutdown()

    print("\n--------------------------------------------------")
    if failures == 0:
        print(f"hg_agent_transcribe_test: all {checks} checks passed")
        return 0
    print(f"hg_agent_transcribe_test: {failures} of {checks} checks FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
