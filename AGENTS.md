# 项目规则

## LSPosed / 支付宝 Receiver 刷新

当新增手动任务、广播 action、枚举值或 hook receiver 行为后，模块 UI 可能已更新，但注入到支付宝进程中的代码仍使用旧 dex。如果支付宝日志报错 `No enum constant ... CustomTask.X`，按以下流程刷新 receiver：

1. 安装新构建的 APK：

   ```bash
   adb install -r -d app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk
   ```

2. 强制停止模块应用：

   ```bash
   adb shell am force-stop fansirsqi.xposed.sesame
   ```

3. 强制停止支付宝：

   ```bash
   adb shell am force-stop com.eg.android.AlipayGphone
   ```

4. 使用 root 权限杀死残留的支付宝进程：

   ```bash
   adb shell su -c 'pkill -f com.eg.android.AlipayGphone'
   ```

5. 重新启动支付宝，使 LSPosed 注入更新后的模块代码：

   ```bash
   adb shell monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1
   ```

6. 重新触发任务。以 AI 摸鱼手动任务为例，验证广播为：

   ```bash
   adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.manual_task --es task OCEAN_AI_FISH
   ```

成功时日志如下，receiver 接受任务并进入手动任务流程：

```text
[ApplicationHook]: 🚀 收到手动庄园任务指令
[ManualTask]: ⏳ 正在执行: AI摸鱼...
[AntOcean]: 执行AI摸鱼任务
```

如果日志仍显示 `No enum constant`，说明 receiver 仍在加载旧代码。重新安装 APK，确认 LSPosed 的作用域包含支付宝，然后重复强制停止和重启流程。如果仍然失败，重启设备以刷新 Zygisk/LSPosed 注入链。

## 本地 Web UI / RPC 调试工作流

使用 `serve-debug` 目录下的本地调试工具，避免每次调试 RPC 参数都重新安装 APK。PC 浏览器不会直接调用支付宝，而是将请求发送到运行在被 hook 的支付宝进程中的模块 HTTP 服务器。

### Web UI 调试器

调试模块前端 UI 时使用 `serve-debug/webui.py`。

1. 将模块 `web` 目录复制到 `serve-debug`。
2. 将模块 `config.json` 复制到 `serve-debug`。
3. 将一个已配置的 `friend.json` 复制到 `serve-debug`。
4. 启动本地 UI 服务器：

   ```bash
   cd serve-debug
   uv run webui.py
   ```

5. 打开本地 Web UI：

   ```text
   http://127.0.0.1:8000
   ```

可通过路径打开自定义 UI 页面，例如：

```text
http://127.0.0.1:8080/semi_index.html
http://127.0.0.1:8080/varlet_index.html
```

### RPC 调试面板

从 PC 调试 RPC 请求载荷时使用 `serve-debug/rpc_debug.py`。请求流程：

```text
浏览器 -> serve-debug/rpc_debug.py -> adb forward tcp:8080 -> 支付宝模块 HTTP 服务器 -> RequestManager
```

使用面板前，确保当前安装的 APK 包含要测试的 RPC handler。如果新增了路由或 handler 代码，先执行上面的 LSPosed / 支付宝 Receiver 刷新流程。

1. 转发模块 HTTP 服务器端口：

   ```bash
   adb forward tcp:8080 tcp:8080
   ```

2. 启动 PC 调试面板：

   ```bash
   cd serve-debug
   uv run python rpc_debug.py
   ```

3. 打开浏览器：

   ```text
   http://127.0.0.1:9528
   ```

4. 保持默认的模块 URL（除非使用了不同的转发端口）：

   ```text
   http://127.0.0.1:8080
   ```

5. 使用模块调试 Token：

   ```text
   ET3vB^#td87sQqKaY*eMUJXP
   ```

面板支持两种请求模式：

- **Normal RPC**：发送到 `/debugHandler`，使用 `methodName + requestData`。
- **RpcEntity / XRiver**：发送到 `/debugRpcEntity`，使用 `operationType`、`appName`、`facadeName`、`rpcMethodName`、`requestData`。

对于捕获的 `opengreen` 类型请求（如 AI 摸鱼任务列表），使用 **RpcEntity / XRiver** 模式：

```json
{
  "operationType": "com.alipay.antieptask.listTaskopengreen",
  "appName": "antieptask",
  "facadeName": "TaskWebRpc",
  "rpcMethodName": "listTask",
  "requestData": [
    {
      "extend": {
        "appMode": "normal"
      },
      "requestType": "RPC",
      "sceneCode": "ANTAIFISH",
      "source": "ANTAIFISH",
      "uniqueId": "test"
    }
  ]
}
```

### 快速端到端启动

当设备已连接且调试 APK 已构建时，完整的启动流程如下：

```bash
adb install -r -d app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk
adb shell am force-stop fansirsqi.xposed.sesame
adb shell am force-stop com.eg.android.AlipayGphone
adb shell su -c 'pkill -f com.eg.android.AlipayGphone'
adb shell monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1
adb forward tcp:8080 tcp:8080
cd serve-debug
uv run python rpc_debug.py
```

然后打开 `http://127.0.0.1:9528` 并发送模板请求。向 `/debugRpcEntity` 发送一个空 JSON body 的探测请求，如果返回 `Fields cannot be empty`，说明新的调试路由已加载且可达。
