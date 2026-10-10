"""Local fault endpoint: a valid ML body with an invalid HTTP success status."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/api/v1/ml-inference":
            self.send_error(404)
            return
        try:
            payload = self._read_payload()
        except (ValueError, OverflowError):
            self.send_error(400)
            return
        request = json.loads(payload)
        response = {
            key: request[key]
            for key in (
                "transactionId",
                "evaluationCutoffAt",
                "featureVersion",
                "scoringPolicyVersion",
                "modelVersion",
                "modelSha256",
            )
        }
        response.update(probabilityBasisPoints=7500, reasonCode="ML_RISK_SIGNAL")
        body = json.dumps(response, separators=(",", ":")).encode("ascii")
        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print("ML_HTTP_CONTRACT_FAULT_RECEIVED", flush=True)

    def _read_payload(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = bytearray()
            while True:
                line = self.rfile.readline(64)
                size = int(line.split(b";", 1)[0].strip(), 16)
                if size == 0:
                    self.rfile.readline(2)
                    break
                if size > 262144 - len(chunks):
                    raise OverflowError
                chunks.extend(self.rfile.read(size))
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError
            if not chunks:
                raise ValueError
            return bytes(chunks)
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > 262144:
            raise ValueError
        return self.rfile.read(length)

    def log_message(self, _format, *args):
        pass


HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
