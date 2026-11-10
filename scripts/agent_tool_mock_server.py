from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("HTTP", self.address_string(), fmt % args, flush=True)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        has_tool_result = "function_call_output" in body or '"role":"tool"' in body
        print("REQUEST", self.path, "tool_result=", has_tool_result, "bytes=", len(body), flush=True)
        print(body[:5000], flush=True)

        if has_tool_result:
            events = [
                {
                    "type": "response.output_text.delta",
                    "item_id": "msg_done",
                    "output_index": 0,
                    "content_index": 0,
                    "delta": "工具调用成功，已经读取到当前上下文。",
                },
                {
                    "type": "response.completed",
                    "response": {
                        "status": "completed",
                        "output": [
                            {
                                "type": "message",
                                "id": "msg_done",
                                "role": "assistant",
                                "content": [
                                    {
                                        "type": "output_text",
                                        "text": "工具调用成功，已经读取到当前上下文。",
                                        "annotations": [],
                                    }
                                ],
                            }
                        ],
                        "usage": {"input_tokens": 21, "output_tokens": 9, "total_tokens": 30},
                    },
                },
            ]
        else:
            call = {
                "id": "fc_context",
                "type": "function_call",
                "call_id": "call_context",
                "name": "get_current_context",
                "arguments": "{}",
            }
            events = [
                {
                    "type": "response.output_item.added",
                    "output_index": 0,
                    "item": {
                        "id": "fc_context",
                        "type": "function_call",
                        "call_id": "call_context",
                        "name": "get_current_context",
                    },
                },
                {
                    "type": "response.function_call_arguments.delta",
                    "item_id": "fc_context",
                    "delta": "{}",
                },
                {
                    "type": "response.completed",
                    "response": {
                        "status": "completed",
                        "output": [call],
                        "usage": {"input_tokens": 14, "output_tokens": 4, "total_tokens": 18},
                    },
                },
            ]

        payload = "".join(
            "event: %s\ndata: %s\n\n" % (event["type"], json.dumps(event, ensure_ascii=False))
            for event in events
        ) + "data: [DONE]\n\n"
        data = payload.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        self.wfile.flush()


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
