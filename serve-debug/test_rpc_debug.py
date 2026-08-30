import unittest

from rpc_debug import build_module_request


class RpcDebugPayloadTest(unittest.TestCase):
    def test_builds_module_debug_payload_for_normal_rpc(self):
        path, payload = build_module_request(
            {
                "mode": "normal",
                "methodName": "alipay.antforest.forest.h5.queryTaskList",
                "requestData": [{"source": "ANTFOREST"}],
            }
        )

        self.assertEqual(path, "/debugHandler")
        self.assertEqual(payload["methodName"], "alipay.antforest.forest.h5.queryTaskList")
        self.assertEqual(payload["requestData"], [{"source": "ANTFOREST"}])

    def test_builds_module_debug_payload_for_rpc_entity(self):
        path, payload = build_module_request(
            {
                "mode": "entity",
                "operationType": "com.alipay.antieptask.listTaskopengreen",
                "appName": "antieptask",
                "facadeName": "TaskWebRpc",
                "rpcMethodName": "listTask",
                "requestData": [{"sceneCode": "ANTAIFISH"}],
            }
        )

        self.assertEqual(path, "/debugRpcEntity")
        self.assertEqual(payload["operationType"], "com.alipay.antieptask.listTaskopengreen")
        self.assertEqual(payload["appName"], "antieptask")
        self.assertEqual(payload["facadeName"], "TaskWebRpc")
        self.assertEqual(payload["rpcMethodName"], "listTask")
        self.assertEqual(payload["requestData"], [{"sceneCode": "ANTAIFISH"}])


if __name__ == "__main__":
    unittest.main()
