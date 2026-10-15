package com.couli.app.agent

import com.couli.core.agent.AgentStreamSink

/** 测试替身的便捷入口：一段 SSE / JSON 文本按 UTF-8 编码成字节交给端口的接收端（APP-10b：接收端只认字节，解码只在 Core 的 StreamUtf8Decoder 做一次）。 */
internal fun AgentStreamSink.onData(text: String) = onBytes(text.toByteArray(Charsets.UTF_8))
