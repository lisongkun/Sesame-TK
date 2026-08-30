package fansirsqi.xposed.sesame.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.util.AssetUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


object HookUtil {
    private const val TAG = "HookUtil"
    private const val MAX_DISCOVERED_PROXIES = 200
    private const val NATIVE_RPC_HANDLER = "com.alipay.mobile.common.rpc.RpcInvocationHandler"

    private val nativeRpcTargetInterfaces = setOf(
        "com.alipay.mobilerelation.fpc.rpc.ContactPbRpc",
        "com.alipay.mobilerelation.rpc.MobileRelationManagePBService",
        "com.alipay.mobilerelation.rpc.ScocialInfoQueryRpc"
    )

    val rpcHookMap = ConcurrentHashMap<Any, Array<Any?>>()

    private val discoveredProxyHandlers = ConcurrentHashMap.newKeySet<String>()
    private val proxyDiscoveryLogging = ThreadLocal.withInitial { false }

    @Volatile
    private var proxyDiscoveryInstalled = false

    @Volatile
    private var nativeRpcInvocationInstalled = false

    private val nativeRpcCallSequence = AtomicLong(0)
    private val nativeRpcCalls = ConcurrentHashMap<Any, NativeRpcCall>()

    private var lastToastTime = 0L

    private var microContextCache: Any? = null

    private data class NativeRpcCall(
        val id: Long,
        val interfaceName: String,
        val methodName: String
    )

    /**
     * Discover concrete InvocationHandler implementations used by Alipay.
     * This is diagnostic-only and never changes proxy creation or invocation results.
     */
    @Synchronized
    fun hookProxyDiscovery() {
        if (proxyDiscoveryInstalled) return

        try {
            val classArrayClass = java.lang.reflect.Array
                .newInstance(Class::class.java, 0)
                .javaClass

            XposedHelpers.findAndHookMethod(
                Proxy::class.java,
                "newProxyInstance",
                ClassLoader::class.java,
                classArrayClass,
                InvocationHandler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (proxyDiscoveryLogging.get()) return

                        val interfaces = param.args.getOrNull(1) as? Array<*> ?: return
                        val handler = param.args.getOrNull(2) as? InvocationHandler ?: return
                        if (handler.javaClass.classLoader == null) return

                        val interfaceNames = interfaces
                            .filterIsInstance<Class<*>>()
                            .joinToString { it.name }
                        val handlerName = handler.javaClass.name
                        val discoveryKey = "$handlerName|$interfaceNames"

                        if (discoveredProxyHandlers.size >= MAX_DISCOVERED_PROXIES ||
                            !discoveredProxyHandlers.add(discoveryKey)
                        ) {
                            return
                        }

                        val callStack = Throwable().stackTrace
                            .drop(2)
                            .filterNot { it.className.startsWith("de.robv.android.xposed") }
                            .take(8)
                            .joinToString("\n") { "  at $it" }

                        proxyDiscoveryLogging.set(true)
                        try {
                            Log.capture(
                                """
                                [ProxyDiscovery]
                                Handler: $handlerName
                                Interfaces: $interfaceNames
                                Thread: ${Thread.currentThread().name}
                                Stack:
                                $callStack
                                """.trimIndent()
                            )
                        } finally {
                            proxyDiscoveryLogging.set(false)
                        }
                    }
                }
            )

            proxyDiscoveryInstalled = true
            Log.record(TAG, "Proxy.newProxyInstance 发现探针安装成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Proxy.newProxyInstance 发现探针安装失败", t)
        }
    }

    /**
     * Capture calls made through the native mPaaS RPC proxy for friend operations.
     */
    @Synchronized
    fun hookNativeRpcInvocation(classLoader: ClassLoader) {
        if (nativeRpcInvocationInstalled) return

        try {
            val handlerClass = XposedHelpers.findClassIfExists(NATIVE_RPC_HANDLER, classLoader)
            if (handlerClass == null) {
                Log.record(TAG, "原生 RPC Handler 不存在: $NATIVE_RPC_HANDLER")
                return
            }

            val unhooks = XposedBridge.hookAllMethods(
                handlerClass,
                "invoke",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val rpcMethod = param.args.getOrNull(1) as? Method ?: return
                            val interfaceName = rpcMethod.declaringClass.name
                            if (interfaceName !in nativeRpcTargetInterfaces) return

                            val callId = nativeRpcCallSequence.incrementAndGet()
                            nativeRpcCalls[param] = NativeRpcCall(callId, interfaceName, rpcMethod.name)

                            val rpcArgs = param.args.getOrNull(2) as? Array<*>
                            val annotations = rpcMethod.declaredAnnotations
                                .joinToString { safeDescribeRpcValue(it) }

                            Log.capture(
                                """
                                [NativeRpcRequest #$callId]
                                Interface: $interfaceName
                                Method: ${rpcMethod.name}
                                Annotations: $annotations
                                Args: ${safeDescribeRpcValue(rpcArgs)}
                                Thread: ${Thread.currentThread().name}
                                """.trimIndent()
                            )
                        } catch (t: Throwable) {
                            Log.printStackTrace(TAG, "记录原生 RPC 请求失败", t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val call = nativeRpcCalls.remove(param) ?: return

                        try {
                            if (param.hasThrowable()) {
                                val throwable = param.throwable
                                Log.capture(
                                    """
                                    [NativeRpcError #${call.id}]
                                    Interface: ${call.interfaceName}
                                    Method: ${call.methodName}
                                    Error: ${throwable.javaClass.name}: ${throwable.message}
                                    """.trimIndent()
                                )
                            } else {
                                Log.capture(
                                    """
                                    [NativeRpcResponse #${call.id}]
                                    Interface: ${call.interfaceName}
                                    Method: ${call.methodName}
                                    Result: ${safeDescribeRpcValue(param.result)}
                                    """.trimIndent()
                                )
                            }
                        } catch (t: Throwable) {
                            Log.printStackTrace(TAG, "记录原生 RPC 响应失败", t)
                        }
                    }
                }
            )

            if (unhooks.isEmpty()) {
                Log.record(TAG, "原生 RPC Handler 未找到 invoke 方法: $NATIVE_RPC_HANDLER")
                return
            }

            nativeRpcInvocationInstalled = true
            Log.record(TAG, "原生好友 RPC 调用探针安装成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "原生好友 RPC 调用探针安装失败", t)
        }
    }

    private fun safeDescribeRpcValue(value: Any?): String = try {
        when (value) {
            null -> "null"
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") {
                safeDescribeRpcValue(it)
            }
            is ByteArray -> value.contentToString()
            is ShortArray -> value.contentToString()
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is DoubleArray -> value.contentToString()
            is CharArray -> value.concatToString()
            is BooleanArray -> value.contentToString()
            else -> value.toString()
        }
    } catch (t: Throwable) {
        "<${value?.javaClass?.name ?: "unknown"} toString failed: ${t.javaClass.simpleName}>"
    }

    /**
     * Hook RpcBridgeExtension.rpc 方法，记录请求信息
     */
    fun hookRpcBridgeExtension(classLoader: ClassLoader, isdebug: Boolean, debugUrl: String) {
        try {
            val className = "com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension"
            val jsonClassName = General.JSON_OBJECT_NAME // 替换为你项目中的实际 JSON 类名

            val jsonClass = Class.forName(jsonClassName, false, classLoader)
            val appClass = XposedHelpers.findClass("com.alibaba.ariver.app.api.App", classLoader)
            val pageClass = XposedHelpers.findClass("com.alibaba.ariver.app.api.Page", classLoader)
            val apiContextClass = XposedHelpers.findClass("com.alibaba.ariver.engine.api.bridge.model.ApiContext", classLoader)
            val bridgeCallbackClass = XposedHelpers.findClass("com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback", classLoader)

            XposedHelpers.findAndHookMethod(
                className,
                classLoader,
                "rpc",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                jsonClass,
                String::class.java,
                jsonClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                appClass,
                pageClass,
                apiContextClass,
                bridgeCallbackClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        if (args.size > 15) {// 参数校验
                            // 1. 获取方法名
                            val methodName = args[0] as? String ?: return
                            // 2. 获取参数 (这是一个反射得到的 com.alibaba.fastjson.JSONObject 对象)
                            val rawParams = args[4]

                            // 3. 这里的 rawParams 是阿里内部的 JSON 对象，不是 org.json.JSONObject
                            // 需要转换一下。最稳妥的方法是 toString() 然后再转 org.json.JSONObject
                            if (rawParams != null) {
                                val jsonString = rawParams.toString()
                                val jsonObject = JSONObject(jsonString)
                                // ✅✅✅ 关键：把拦截到的数据扔给 VIPHook 进行分发
                                TokenHooker.handleRpc(methodName, jsonObject)
                            }

                            val callback = args[15]
                            val recordArray = arrayOfNulls<Any>(4).apply {
                                this[0] = System.currentTimeMillis()
                                this[1] = args[0] ?: "null" // method name
                                this[2] = args[4] ?: "null" // params
                            }
                            rpcHookMap[callback] = recordArray
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        if (args.size > 15) {
                            val callback = args[15]
                            val recordArray = rpcHookMap.remove(callback)
                            recordArray?.let {
                                try {
                                    val time = it[0]
                                    val method = it.getOrNull(1)
                                    val params = it.getOrNull(2)
                                    val data = it.getOrNull(3)

                                    val dataIsNullValue: Boolean = data == null
                                    if (!dataIsNullValue) {

                                        val res = JSONObject().apply {
                                            put("TimeStamp", time)
                                            put("Method", method)
                                            put("Params", params)
                                            put("Data", data)
                                        }

                                        val prettyRecord = """
{
"TimeStamp": $time,
"Method": "$method",
"Params": $params,
"Data": $data
}
""".trimIndent()

                                        if (isdebug) {
                                            HookSender.sendHookData(res, debugUrl)
                                        }
                                        Log.capture(prettyRecord)
                                    }
                                } catch (e: Exception) {
                                    Log.record(TAG, "JSON 构建失败: ${e.message}")
                                }
                            }
                        }
                    }
                })
            Log.record(TAG, "Hook RpcBridgeExtension#rpc 成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Hook RpcBridgeExtension#rpc 失败", t)
        }
    }

    fun hookOtherService(classLoader: ClassLoader) {
        try {
            //hook 服务不在后台
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.common.fgbg.FgBgMonitorImpl",
                classLoader, "isInBackground",
                XC_MethodReplacement.returnConstant(false))
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.common.fgbg.FgBgMonitorImpl",
                classLoader,
                "isInBackground",
                Boolean::class.javaPrimitiveType,
                XC_MethodReplacement.returnConstant(false)
            )
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.common.fgbg.FgBgMonitorImpl",
                classLoader, "isInBackgroundV2",
                XC_MethodReplacement.returnConstant(false))
            //hook 服务在前台
            XposedHelpers.findAndHookMethod(
                "com.alipay.mobile.common.transport.utils.MiscUtils",
                classLoader,
                "isAtFrontDesk",
                classLoader.loadClass("android.content.Context"),
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "hookOtherService 失败", e)
        }
    }

    /**
     * Hook DefaultBridgeCallback.sendJSONResponse 方法，记录响应内容
     */
    fun hookDefaultBridgeCallback(classLoader: ClassLoader) {
        try {
            val className = "com.alibaba.ariver.engine.common.bridge.internal.DefaultBridgeCallback"
            val jsonClassName = General.JSON_OBJECT_NAME
            val jsonClass = Class.forName(jsonClassName, false, classLoader)
            XposedHelpers.findAndHookMethod(className, classLoader, "sendJSONResponse", jsonClass, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.thisObject
                    val recordArray = rpcHookMap[callback]
                    if (recordArray != null && param.args.isNotEmpty()) {
                        recordArray[3] = param.args[0].toString()
                    }
                }
            })
            Log.record(TAG, "Hook DefaultBridgeCallback#sendJSONResponse 成功")
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "Hook DefaultBridgeCallback#sendJSONResponse 失败", t)
        }
    }

    /**
     * 突破目标应用最大可登录账号数量限制
     * @param classLoader 类加载器
     */
    fun fuckAccounLimit(classLoader: ClassLoader) {
        Log.record(TAG, "Hook AccountManagerListAdapter#getCount")
        XposedHelpers.findAndHookMethod(
            "com.alipay.mobile.security.accountmanager.data.AccountManagerListAdapter",  // target class
            classLoader, "getCount",  // method name
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 获取真实账号列表大小
                    try {
                        val list = XposedHelpers.getObjectField(param.thisObject, "queryAccountList") as? List<*>
                        if (list != null) {
                            param.result = list.size  // 设置返回值为真实数量
                            val now = System.currentTimeMillis()
                            if (now - lastToastTime > 1000 * 60) { // 每N秒最多显示一次
                                Toast.show("🎉 TK已尝试为您突破限制")
                                lastToastTime = now
                            }
                        }
                        return
//                        Log.runtime(TAG, "Hook AccountManagerListAdapter#getCount but return is null")
                    } catch (e: Throwable) {
                        // 错误日志处理（你可以替换为自己的日志方法）
                        e.printStackTrace()
                        Log.error(TAG, "Hook AccountManagerListAdapter#getCount failed: ${e.message}")
                    }
                }
            })
        Log.record(TAG, "Hook AccountManagerListAdapter#getCount END")
    }


    fun getMicroApplicationContext(classLoader: ClassLoader): Any? {
        if (microContextCache != null) return microContextCache
        return runCatching {
            val appClass = XposedHelpers.findClass(
                "com.alipay.mobile.framework.AlipayApplication", classLoader
            )
            val appInstance = XposedHelpers.callStaticMethod(appClass, "getInstance")
            XposedHelpers.callMethod(appInstance, "getMicroApplicationContext")
                .also { microContextCache = it }
        }.onFailure {
            Log.printStackTrace(TAG, it)
        }.getOrNull()
    }

    fun getServiceObject(classLoader: ClassLoader, serviceName: String): Any? = runCatching {
        val microContext = getMicroApplicationContext(classLoader)
        XposedHelpers.callMethod(microContext, "findServiceByInterface", serviceName)
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun getUserObject(classLoader: ClassLoader): Any? = runCatching {
        val serviceClassName = "com.alipay.mobile.personalbase.service.SocialSdkContactService"
        val serviceClass = XposedHelpers.findClass(serviceClassName, classLoader)
        val serviceObject = getServiceObject(classLoader, serviceClass.name)
        XposedHelpers.callMethod(serviceObject, "getMyAccountInfoModelByLocal")
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun getUserId(classLoader: ClassLoader): String? = runCatching {
        val userObject = getUserObject(classLoader)
        XposedHelpers.getObjectField(userObject, "userId") as? String
    }.onFailure {
        Log.printStackTrace(TAG, it)
    }.getOrNull()

    fun hookUser(classLoader: ClassLoader) {
        runCatching {
            UserMap.unload()
            val selfId = getUserId(classLoader)
            UserMap.setCurrentUserId(selfId) //有些地方要用到 要set一下
            val clsUserIndependentCache = classLoader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.UserIndependentCache")
            val clsAliAccountDaoOp = classLoader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.contact.data.AliAccountDaoOp")
            val aliAccountDaoOp = XposedHelpers.callStaticMethod(clsUserIndependentCache, "getCacheObj", clsAliAccountDaoOp)
            val allFriends = XposedHelpers.callMethod(aliAccountDaoOp, "getAllFriends") as? List<*> ?: emptyList<Any>()
            if (allFriends.isEmpty()) return
            val friendClass = allFriends.firstOrNull()?.javaClass ?: return
            val userIdField = XposedHelpers.findField(friendClass, "userId")
            val accountField = XposedHelpers.findField(friendClass, "account")
            val nameField = XposedHelpers.findField(friendClass, "name")
            val nickNameField = XposedHelpers.findField(friendClass, "nickName")
            val remarkNameField = XposedHelpers.findField(friendClass, "remarkName")
            val friendStatusField = XposedHelpers.findField(friendClass, "friendStatus")
            var selfEntity: UserEntity? = null
            allFriends.forEach { userObject ->
                runCatching {
                    val userId = userIdField.get(userObject) as? String
                    val account = accountField.get(userObject) as? String
                    val name = nameField.get(userObject) as? String
                    val nickName = nickNameField.get(userObject) as? String
                    val remarkName = remarkNameField.get(userObject) as? String
                    val friendStatus = friendStatusField.get(userObject) as? Int
                    val userEntity = UserEntity(userId, account, friendStatus, name, nickName, remarkName)
                    if (userId == selfId) selfEntity = userEntity
                    UserMap.add(userEntity)
                }.onFailure {
                    Log.record(TAG, "addUserObject err:")
                    Log.printStackTrace(it)
                }
            }

            UserMap.saveSelf(selfEntity)
            UserMap.save(selfId)
            Log.record(TAG, "userCache load scuess !")
        }.onFailure {
            Log.printStackTrace(TAG, "hookUser 失败", it)
        }
    }
    fun hookAssetManagerForModel(loader: ClassLoader) {
        try {
            val assetManagerClass = android.content.res.AssetManager::class.java

            fun tryRedirectModel(param: XC_MethodHook.MethodHookParam) {
                val fileName = param.args.getOrNull(0) as? String ?: return
                if (fileName != AssetUtil.SLIDER_MODEL) return

                val modelFile = AssetUtil.modelPrivateFile
                if (modelFile != null && modelFile.exists()) {
                    val pfd = android.os.ParcelFileDescriptor.open(
                        modelFile,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val afd = android.content.res.AssetFileDescriptor(pfd, 0, modelFile.length())
                    param.result = afd
                    Log.record(TAG, "成功拦截 Asset 加载：重定向 ${AssetUtil.SLIDER_MODEL} 到 ${modelFile.absolutePath}")
                } else {
                    Log.error(TAG, "拦截失败：私有模型文件不存在")
                }
            }

            XposedHelpers.findAndHookMethod(
                assetManagerClass,
                "openFd",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        tryRedirectModel(param)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                assetManagerClass,
                "openAssetFd",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        tryRedirectModel(param)
                    }
                }
            )

            Log.record(TAG, "Hook AssetManager(openFd/openAssetFd) 成功")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "Hook AssetManager 失败", e)
        }
    }
}
