package fansirsqi.xposed.sesame.hook.server.handlers

import fansirsqi.xposed.sesame.entity.RpcEntity
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.hook.server.ServerCommon.MIME_JSON
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

class RpcEntityDebugHandler(secretToken: String) : BaseHandler(secretToken) {

    override fun onPost(session: IHTTPSession, body: String?): Response {
        if (body.isNullOrBlank()) {
            return badRequest("Empty body")
        }

        val request: RpcEntityRequest = try {
            mapper.readValue(body, RpcEntityRequest::class.java)
        } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }

        val dataStr = request.getRequestDataString(mapper)
        if (request.operationType.isBlank() || dataStr.isBlank()) {
            return badRequest("Fields cannot be empty")
        }

        return try {
            val rpcEntity = RpcEntity(
                requestMethod = request.operationType,
                requestData = dataStr,
                requestRelation = request.relation,
                appName = request.appName,
                methodName = request.rpcMethodName,
                facadeName = request.facadeName
            )
            val result = RequestManager.requestString(rpcEntity)

            if (result.isBlank()) {
                json(Response.Status.OK, mapOf("status" to "empty"))
            } else {
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, result)
            }
        } catch (e: Exception) {
            badRequest("RPC Entity Error: ${e.message}")
        }
    }
}
