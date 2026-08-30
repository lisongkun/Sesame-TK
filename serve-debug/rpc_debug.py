import json
from typing import Any, Dict

import requests
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel


DEFAULT_MODULE_URL = "http://127.0.0.1:8080"
DEFAULT_TOKEN = "ET3vB^#td87sQqKaY*eMUJXP"

app = FastAPI(title="Sesame-TK RPC Debug")


class RpcDebugRequest(BaseModel):
    mode: str = "normal"
    targetUrl: str = DEFAULT_MODULE_URL
    token: str = DEFAULT_TOKEN
    methodName: str = ""
    operationType: str = ""
    appName: str = ""
    facadeName: str = ""
    rpcMethodName: str = ""
    requestData: Any


def build_module_request(data: Dict[str, Any]) -> tuple[str, Dict[str, Any]]:
    mode = str(data.get("mode", "normal")).strip().lower()
    if mode == "entity":
        operation_type = str(data.get("operationType", "")).strip()
        if not operation_type:
            raise ValueError("operationType cannot be empty")

        return "/debugRpcEntity", {
            "operationType": operation_type,
            "appName": str(data.get("appName", "")).strip(),
            "facadeName": str(data.get("facadeName", "")).strip(),
            "rpcMethodName": str(data.get("rpcMethodName", "")).strip(),
            "requestData": data.get("requestData"),
        }

    method_name = str(data.get("methodName", "")).strip()
    if not method_name:
        raise ValueError("methodName cannot be empty")

    return "/debugHandler", {
        "methodName": method_name,
        "requestData": data.get("requestData"),
    }


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    return HTML


@app.post("/api/rpc")
def proxy_rpc(request: RpcDebugRequest) -> JSONResponse:
    path, module_payload = build_module_request(request.model_dump())
    target_url = request.targetUrl.rstrip("/") + path

    try:
        response = requests.post(
            target_url,
            headers={
                "Authorization": f"Bearer {request.token}",
                "Content-Type": "application/json",
            },
            json=module_payload,
            timeout=45,
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"Cannot reach module server: {exc}") from exc

    try:
        body = response.json()
    except ValueError:
        body = response.text

    return JSONResponse(
        status_code=response.status_code,
        content={
            "statusCode": response.status_code,
            "moduleUrl": target_url,
            "request": module_payload,
            "response": body,
        },
    )


HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Sesame-TK RPC Debug</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f6f7f9;
      --panel: #ffffff;
      --text: #20242a;
      --muted: #667085;
      --line: #d8dde6;
      --accent: #1463ff;
      --accent-weak: #e9f0ff;
      --danger: #b42318;
      --ok: #067647;
      --shadow: 0 1px 2px rgba(16, 24, 40, 0.08);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font: 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      height: 56px;
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 0 18px;
      background: var(--panel);
      border-bottom: 1px solid var(--line);
      position: sticky;
      top: 0;
      z-index: 2;
    }
    h1 {
      font-size: 17px;
      margin: 0;
      font-weight: 650;
    }
    main {
      display: grid;
      grid-template-columns: minmax(360px, 520px) minmax(420px, 1fr);
      gap: 14px;
      padding: 14px;
      min-height: calc(100vh - 56px);
    }
    section {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      box-shadow: var(--shadow);
      min-width: 0;
    }
    .left, .right {
      display: flex;
      flex-direction: column;
      min-height: 0;
    }
    .section-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 14px;
      border-bottom: 1px solid var(--line);
      gap: 10px;
    }
    h2 {
      margin: 0;
      font-size: 14px;
      font-weight: 650;
    }
    .content {
      padding: 14px;
      display: grid;
      gap: 12px;
    }
    label {
      display: grid;
      gap: 6px;
      color: var(--muted);
      font-size: 12px;
      font-weight: 600;
    }
    input, textarea, select {
      width: 100%;
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 9px 10px;
      font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      background: #fff;
      color: var(--text);
    }
    textarea {
      min-height: 220px;
      resize: vertical;
    }
    .row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    button {
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #fff;
      color: var(--text);
      padding: 8px 11px;
      font-weight: 650;
      cursor: pointer;
    }
    button.primary {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
    }
    button.soft {
      background: var(--accent-weak);
      color: var(--accent);
      border-color: #c8d8ff;
    }
    button:disabled {
      opacity: .55;
      cursor: wait;
    }
    pre {
      margin: 0;
      overflow: auto;
      padding: 12px;
      background: #0f172a;
      color: #e5e7eb;
      border-radius: 6px;
      min-height: 260px;
      white-space: pre-wrap;
      word-break: break-word;
      font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }
    .status {
      color: var(--muted);
      font-size: 12px;
      font-weight: 600;
    }
    .status.ok { color: var(--ok); }
    .status.error { color: var(--danger); }
    .hint {
      color: var(--muted);
      font-size: 12px;
    }
    @media (max-width: 960px) {
      main { grid-template-columns: 1fr; }
      .row { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <header>
    <h1>Sesame-TK RPC Debug</h1>
    <span class="status" id="status">Ready</span>
  </header>
  <main>
    <section class="left">
      <div class="section-head">
        <h2>Request</h2>
        <div class="toolbar">
          <button class="soft" id="formatBtn">Format</button>
          <button class="primary" id="sendBtn">Send</button>
        </div>
      </div>
      <div class="content">
        <div class="row">
          <label>Module URL
            <input id="targetUrl" value="http://127.0.0.1:8080" />
          </label>
          <label>Token
            <input id="token" value="ET3vB^#td87sQqKaY*eMUJXP" />
          </label>
        </div>
        <label>Template
          <select id="template"></select>
        </label>
        <label>Mode
          <select id="mode">
            <option value="normal">Normal RPC</option>
            <option value="entity">RpcEntity / XRiver</option>
          </select>
        </label>
        <label>Method
          <input id="methodName" placeholder="alipay.xxx or com.alipay.xxx" />
        </label>
        <div id="entityFields" class="row">
          <label>App Name
            <input id="appName" placeholder="antieptask" />
          </label>
          <label>Facade
            <input id="facadeName" placeholder="TaskWebRpc" />
          </label>
          <label>RPC Method
            <input id="rpcMethodName" placeholder="listTask" />
          </label>
          <label>Operation Type
            <input id="operationType" placeholder="com.alipay.antieptask.listTaskopengreen" />
          </label>
        </div>
        <label>Request Data
          <textarea id="requestData" spellcheck="false"></textarea>
        </label>
        <div class="hint">Use `adb forward tcp:8080 tcp:8080` before sending.</div>
      </div>
    </section>
    <section class="right">
      <div class="section-head">
        <h2>Response</h2>
        <div class="toolbar">
          <button id="copyCurlBtn">Copy curl</button>
          <button id="copyResponseBtn">Copy response</button>
        </div>
      </div>
      <div class="content">
        <pre id="responseBox">{}</pre>
      </div>
    </section>
  </main>
  <script>
    const templates = [
      {
        name: "森林 queryTaskList",
        mode: "normal",
        methodName: "alipay.antforest.forest.h5.queryTaskList",
        requestData: [
          {
            extend: {},
            fromAct: "home_task_list",
            source: "chInfo_ch_appcenter__chsub_9patch",
            version: "20241203"
          }
        ]
      },
      {
        name: "海洋 queryTaskList",
        mode: "normal",
        methodName: "alipay.antocean.ocean.h5.queryTaskList",
        requestData: [
          {
            extend: { appMode: "normal" },
            source: "ANT_OCEAN"
          }
        ]
      },
      {
        name: "AI摸鱼 touchFish",
        mode: "normal",
        methodName: "alipay.antaifish.h5.touchfish",
        requestData: [
          {
            source: "ANT_OCEAN",
            uniqueId: String(Date.now())
          }
        ]
      },
      {
        name: "AI摸鱼 listTask RpcEntity",
        mode: "entity",
        operationType: "com.alipay.antieptask.listTaskopengreen",
        appName: "antieptask",
        facadeName: "TaskWebRpc",
        rpcMethodName: "listTask",
        requestData: [
          {
            extend: { appMode: "normal" },
            requestType: "RPC",
            sceneCode: "ANTAIFISH",
            source: "ANTAIFISH",
            uniqueId: String(Date.now())
          }
        ]
      }
    ];

    const els = {
      status: document.getElementById("status"),
      targetUrl: document.getElementById("targetUrl"),
      token: document.getElementById("token"),
      template: document.getElementById("template"),
      mode: document.getElementById("mode"),
      methodName: document.getElementById("methodName"),
      entityFields: document.getElementById("entityFields"),
      operationType: document.getElementById("operationType"),
      appName: document.getElementById("appName"),
      facadeName: document.getElementById("facadeName"),
      rpcMethodName: document.getElementById("rpcMethodName"),
      requestData: document.getElementById("requestData"),
      responseBox: document.getElementById("responseBox"),
      sendBtn: document.getElementById("sendBtn"),
      formatBtn: document.getElementById("formatBtn"),
      copyCurlBtn: document.getElementById("copyCurlBtn"),
      copyResponseBtn: document.getElementById("copyResponseBtn")
    };

    function setStatus(text, kind = "") {
      els.status.textContent = text;
      els.status.className = "status " + kind;
    }

    function pretty(value) {
      return JSON.stringify(value, null, 2);
    }

    function parseRequestData() {
      return JSON.parse(els.requestData.value);
    }

    function currentPayload() {
      return {
        mode: els.mode.value,
        targetUrl: els.targetUrl.value.trim(),
        token: els.token.value,
        methodName: els.methodName.value.trim(),
        operationType: els.operationType.value.trim(),
        appName: els.appName.value.trim(),
        facadeName: els.facadeName.value.trim(),
        rpcMethodName: els.rpcMethodName.value.trim(),
        requestData: parseRequestData()
      };
    }

    function loadTemplate(index) {
      const item = templates[index];
      els.mode.value = item.mode || "normal";
      els.methodName.value = item.methodName || item.operationType || "";
      els.operationType.value = item.operationType || "";
      els.appName.value = item.appName || "";
      els.facadeName.value = item.facadeName || "";
      els.rpcMethodName.value = item.rpcMethodName || "";
      els.requestData.value = pretty(item.requestData);
      updateMode();
      setStatus("Template loaded");
    }

    function updateMode() {
      const isEntity = els.mode.value === "entity";
      els.entityFields.style.display = isEntity ? "grid" : "none";
      els.methodName.parentElement.style.display = isEntity ? "none" : "grid";
    }

    function buildCurl() {
      const payload = currentPayload();
      const moduleBody = payload.mode === "entity"
        ? {
            operationType: payload.operationType,
            appName: payload.appName,
            facadeName: payload.facadeName,
            rpcMethodName: payload.rpcMethodName,
            requestData: payload.requestData
          }
        : {
            methodName: payload.methodName,
            requestData: payload.requestData
          };
      const path = payload.mode === "entity" ? "/debugRpcEntity" : "/debugHandler";
      const safeJson = JSON.stringify(moduleBody);
      return [
        "curl -X POST '" + payload.targetUrl.replace(/\/$/, "") + path + "'",
        "  -H 'Authorization: Bearer " + payload.token.replace(/'/g, "'\\''") + "'",
        "  -H 'Content-Type: application/json'",
        "  -d '" + safeJson.replace(/'/g, "'\\''") + "'"
      ].join(" \\\n");
    }

    async function sendRpc() {
      let payload;
      try {
        payload = currentPayload();
      } catch (error) {
        setStatus("Invalid JSON", "error");
        els.responseBox.textContent = String(error);
        return;
      }

      els.sendBtn.disabled = true;
      setStatus("Sending...");
      const started = performance.now();
      try {
        const response = await fetch("/api/rpc", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        const body = await response.json();
        els.responseBox.textContent = pretty(body);
        const ms = Math.round(performance.now() - started);
        setStatus(response.ok ? "OK " + ms + "ms" : "HTTP " + response.status, response.ok ? "ok" : "error");
      } catch (error) {
        setStatus("Request failed", "error");
        els.responseBox.textContent = String(error);
      } finally {
        els.sendBtn.disabled = false;
      }
    }

    templates.forEach((item, index) => {
      const option = document.createElement("option");
      option.value = String(index);
      option.textContent = item.name;
      els.template.appendChild(option);
    });

    els.template.addEventListener("change", () => loadTemplate(Number(els.template.value)));
    els.mode.addEventListener("change", updateMode);
    els.sendBtn.addEventListener("click", sendRpc);
    els.formatBtn.addEventListener("click", () => {
      try {
        els.requestData.value = pretty(parseRequestData());
        setStatus("Formatted", "ok");
      } catch (error) {
        setStatus("Invalid JSON", "error");
      }
    });
    els.copyCurlBtn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(buildCurl());
        setStatus("curl copied", "ok");
      } catch (error) {
        setStatus("Copy failed", "error");
      }
    });
    els.copyResponseBtn.addEventListener("click", async () => {
      await navigator.clipboard.writeText(els.responseBox.textContent);
      setStatus("response copied", "ok");
    });

    loadTemplate(0);
  </script>
</body>
</html>
"""


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("rpc_debug:app", host="127.0.0.1", port=9528, reload=True)
