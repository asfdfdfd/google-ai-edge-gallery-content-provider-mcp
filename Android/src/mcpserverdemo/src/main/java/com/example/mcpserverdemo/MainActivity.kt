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

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client-side MCP transport used by the self-test: exchanges JSON-RPC messages with a
 * ContentProvider-backed MCP server through synchronous [ContentResolver.call] calls.
 */
class ClientProviderTransport(
  private val contentResolver: ContentResolver,
  private val uri: Uri,
) : AbstractTransport() {

  override suspend fun start() {
    // The connection is stateless: each send() performs its own round trip through the provider.
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    val requestJson = McpJson.encodeToString(JSONRPCMessage.serializer(), message)
    val responseJson =
      withContext(Dispatchers.IO) {
        val result = contentResolver.call(uri, McpContentProvider.METHOD_MCP, requestJson, null)
        result?.getString(McpContentProvider.KEY_RESPONSE)
      }
    if (responseJson == null) {
      // Notifications and other one-way messages produce no response.
      return
    }
    val response = McpJson.decodeFromString(JSONRPCMessage.serializer(), responseJson)
    _onMessage(response)
  }

  override suspend fun close() {
    _onClose()
  }
}

/** Entry point showing how to register the MCP server in AI Edge Gallery. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      McpDemoScreen()
    }
  }
}
