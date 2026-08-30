# Sesame-TK 开发规范

## 项目概述

Sesame-TK 是一个 Android Xposed/LSPosed 模块，用于自动化支付宝小程序任务（蚂蚁森林、蚂蚁庄园、蚂蚁海洋、蚂蚁运动等）。使用 Kotlin + Java 混合开发，Jetpack Compose 构建 UI。

## 技术栈

- **语言**: Kotlin + Java（Java 类使用 Lombok 注解生成 getter/setter）
- **构建**: Gradle (Kotlin DSL)，单模块 `:app`
- **UI**: Jetpack Compose + Material 3
- **日志**: Logback (SLF4J)，通过 `Log` 工具类统一调用
- **Hook 框架**: Xposed/LSPosed，入口在 `hook/lsp100/` 和 `hook/xp82/`
- **RPC**: 通过 `RequestManager` 发送支付宝内部 RPC 请求

## 代码规范

### 命名与结构

- 任务类继承 `ModelTask`（Kotlin）或直接继承 `ModelTask`（Java），实现 `getName()`、`getFields()`、`runJava()`
- RPC 调用统一放在 `*RpcCall.java` 类中，使用 `RequestManager.requestString()` 发送
- 日志使用 `Log.record()`（记录）、`Log.forest()`（森林相关）、`Log.error()`（错误）、`Log.printStackTrace()`（异常堆栈）
- 模型字段使用 `BooleanModelField`、`ChoiceModelField`、`SelectModelField` 等，在 `getFields()` 中注册

### 日志规范

- `capture` Logger 的 `isAdditive` 为 `false`，不要修改此设置
- `Log.capture()` 内部自行管理 Logcat 分块输出，不要在其他地方重复输出 capture 日志
- 业务日志使用对应的 Logger 名称：`runtime`、`system`、`record`、`debug`、`forest`、`farm`、`other`、`error`、`capture`、`captcha`

### 任务开发

- 新增任务类放在对应的 `task/` 子包下
- 手动任务需要在 `CustomTask.kt` 中添加枚举值，在 `ManualTask.kt` 中添加 handler，在 `ManualTaskModel.kt` 中添加 UI 字段
- 在 `ApplicationHook.kt` 的手动任务分发 switch 中添加对应的 case
- 在 `ModelOrder.kt` 中注册新的 Model 类以在 UI 中显示

### RPC 调用

- 普通 RPC 使用 `RequestManager.requestString("methodName", "[{params}]")` 格式
- RpcEntity 类型的 RPC（如 `opengreen` 请求）使用 `new RpcEntity(operationType, requestData, relation, appName, facadeName, rpcMethodName)` 构造
- RPC 方法统一放在 `*RpcCall.java` 类中，不要在业务类中直接调用 `RequestManager`

## 构建与调试

### 构建 APK

```bash
./gradlew assembleDebug
```

输出路径: `app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk`

### LSPosed/支付宝 Receiver 刷新

当新增手动任务、广播 action、枚举值或 hook receiver 行为后，需要刷新 receiver：

```bash
# 1. 安装 APK
adb install -r -d app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk

# 2. 强制停止模块
adb shell am force-stop fansirsqi.xposed.sesame

# 3. 强制停止支付宝
adb shell am force-stop com.eg.android.AlipayGphone

# 4. 杀死残留进程（需要 root）
adb shell su -c 'pkill -f com.eg.android.AlipayGphone'

# 5. 重新启动支付宝
adb shell monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1
```

### 手动任务触发

```bash
adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.manual_task --es task <TASK_ENUM>
```

示例:
```bash
adb shell am broadcast -a com.eg.android.AlipayGphone.sesame.manual_task --es task OCEAN_AI_FISH
```

成功日志:
```
[ApplicationHook]: 🚀 收到手动庄园任务指令
[ManualTask]: ⏳ 正在执行: AI摸鱼...
[AntOcean]: 执行AI摸鱼任务
```

### RPC 调试面板

使用 `serve-debug/rpc_debug.py` 在 PC 上调试 RPC 请求：

```bash
# 1. 转发端口
adb forward tcp:8080 tcp:8080

# 2. 启动调试面板
cd serve-debug
uv run python rpc_debug.py

# 3. 打开浏览器
# http://127.0.0.1:9528
```

调试 Token: `ET3vB^#td87sQqKaY*eMUJXP`

支持两种请求模式:
- **Normal RPC**: 发送到 `/debugHandler`，使用 `methodName + requestData`
- **RpcEntity / XRiver**: 发送到 `/debugRpcEntity`，使用 `operationType`、`appName`、`facadeName`、`rpcMethodName`、`requestData`

### Web UI 调试

使用 `serve-debug/webui.py` 调试模块前端 UI：

```bash
cd serve-debug
uv run webui.py
# 打开 http://127.0.0.1:8000
```

## 常见问题

### No enum constant 错误

如果支付宝日志报 `No enum constant ... CustomTask.X`，说明注入的代码是旧版本。执行完整的 Receiver 刷新流程（安装 → 停止模块 → 停止支付宝 → 杀进程 → 重启支付宝）。如果仍然失败，重启设备刷新 Zygisk/LSPosed 注入链。

### Lombok getter/setter 报错

Android Studio 需要:
1. 安装 Lombok 插件（Settings → Plugins → Marketplace → Lombok）
2. 启用注解处理（Settings → Build → Compiler → Annotation Processors → Enable）
3. Rebuild Project
