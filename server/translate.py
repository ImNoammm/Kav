"""Hebrew to English for Kav's line alerts (alerts.py's TRANSLATE_URL): Helsinki-NLP's opus-mt-tc-big-he-en (CC-BY 4.0,
Tiedemann et al., OPUS-MT) run by CTranslate2 on the CPU, answering LibreTranslate's POST /translate, so any server that
speaks it can stand in. On 9 Nov 2026 it read the notices far better than LibreTranslate's own Argos model (which made
"חולון", Holon, "The sand"), 13 lines in under two seconds on four threads. Street names can still come out translated
word by word; alerts.py keeps a line's Hebrew when its numbers change."""

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import ctranslate2
import sentencepiece

MODEL = os.environ.get("MODEL", "/model")
PORT = int(os.environ.get("PORT", "5000"))
THREADS = int(os.environ.get("THREADS", "4"))
MOST = 64  # texts in one request
LONGEST = 4000  # characters of one text

source = sentencepiece.SentencePieceProcessor(model_file=os.path.join(MODEL, "source.spm"))
target = sentencepiece.SentencePieceProcessor(model_file=os.path.join(MODEL, "target.spm"))
translator = ctranslate2.Translator(MODEL, device="cpu", compute_type="int8", inter_threads=1, intra_threads=THREADS)
one_at_a_time = threading.Lock()


def english(texts):
    """Each text in English, line by line (the model reads a sentence or two at a time); empty lines stay empty."""
    lines = [t.split("\n") for t in texts]
    asked = [line for group in lines for line in group if line.strip()]
    with one_at_a_time:
        found = translator.translate_batch([source.encode(line, out_type=str) + ["</s>"] for line in asked],
                                           beam_size=4, max_decoding_length=512) if asked else []
    out = iter(target.decode(r.hypotheses[0]) for r in found)
    return ["\n".join(next(out) if line.strip() else line for line in group) for group in lines]


class Handler(BaseHTTPRequestHandler):
    server_version = "kav-translate"
    sys_version = ""

    def answer(self, code, body):
        raw = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/languages":
            return self.answer(200, [{"code": "he", "name": "Hebrew", "targets": ["en"]}])
        self.answer(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/translate":
            return self.answer(404, {"error": "not found"})
        try:
            asked = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            q = asked["q"]
            texts = q if isinstance(q, list) else [q]
            if asked.get("source", "he") not in ("he", "auto") or asked.get("target", "en") != "en":
                return self.answer(400, {"error": "only Hebrew to English"})
            if len(texts) > MOST or not all(isinstance(t, str) and len(t) <= LONGEST for t in texts):
                return self.answer(400, {"error": "at most %d texts of %d characters" % (MOST, LONGEST)})
        except (ValueError, KeyError, TypeError):
            return self.answer(400, {"error": "a JSON body with q"})
        out = english(texts)
        self.answer(200, {"translatedText": out if isinstance(q, list) else out[0]})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("kav-translate: listening on", PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
