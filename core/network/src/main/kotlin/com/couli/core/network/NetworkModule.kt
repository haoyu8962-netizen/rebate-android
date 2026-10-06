package com.couli.core.network

/**
 * 网络核心（03 §3.1、§4.2；APP-03a）：[ApiClient] 请求管线、[RequestHeaderPolicy] 公共头与本地放行、[RequestSigner] 签名、
 * [RefreshCoordinator] 单飞刷新、[ErrorActionMapper]、[PendingKeyStore] 未决幂等键、[SessionTokens] 令牌存储。
 * TODO(APP-03b): 设备注册（DeviceCredentials 的实现）、ConfigStore + LKG、Tracker。
 */
object NetworkModule
