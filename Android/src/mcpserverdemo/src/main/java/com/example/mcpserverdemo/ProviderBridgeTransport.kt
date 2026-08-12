/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.mcpserverdemo

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server-side transport bridging the MCP [ServerSession] with synchronous
 * [android.content.ContentProvider] calls.
 *
 * The ContentProvider receives a JSON-RPC request, feeds it into the session through [deliver],
 * and blocks until the session produces a response through [send].
 */
class ProviderBridgeTransport : AbstractTransport() {
  private val mutex = Mutex()
  private var pendingResponse: CompletableDeferred<JSONRPCMessage>? = null

  override suspend fun start() {
    // The session has no background connection: every message is delivered by deliver().
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    // The Protocol dispatches the response for the in-flight request here.
    pendingResponse?.complete(message)
  }

  override suspend fun close() {
    pendingResponse?.completeExceptionally(IllegalStateException("Connection closed"))
    _onClose()
  }

  /**
   * Delivers an incoming JSON-RPC message to the connected session and returns the JSON-RPC
   * response string, or null when the message was a notification and no response is expected.
   */
  suspend fun deliver(requestJson: String): String? = mutex.withLock {
    val message = McpJson.decodeFromString(JSONRPCMessage.serializer(), requestJson)
    val responseDeferred = CompletableDeferred<JSONRPCMessage>()
    pendingResponse = responseDeferred
    try {
      _onMessage(message)
      if (message is JSONRPCRequest) {
        val response = responseDeferred.await()
        return@withLock McpJson.encodeToString(JSONRPCMessage.serializer(), response)
      }
      return@withLock null
    } finally {
      pendingResponse = null
    }
  }
}
