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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "McpContentProvider"

/**
 * A ContentProvider that exposes an MCP server to other apps on the device.
 *
 * Clients (e.g. AI Edge Gallery) talk MCP JSON-RPC to this provider by calling
 * `ContentResolver.call(uri, "mcp", jsonRequest, null)`. The server URL to register is
 * `content://com.example.mcpserverdemo.mcp/mcp`.
 */
class McpContentProvider : ContentProvider() {
  private lateinit var server: Server
  private lateinit var bridge: ProviderBridgeTransport

  override fun onCreate(): Boolean {
    server =
      Server(
        serverInfo = Implementation(name = "mcp-content-provider-demo", version = "1.0"),
        options =
          ServerOptions(
            capabilities =
              ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
              ),
          ),
        instructions = "Demo MCP server exposed through an Android ContentProvider.",
      ) {
        addTool(
          tool =
            Tool(
              name = "add",
              description =
                "Adds two numbers together and returns their sum. " +
                  "Arguments: a (number), b (number). Returns the sum.",
              inputSchema =
                ToolSchema(
                  properties =
                    buildJsonObject {
                      put("a", JsonPrimitive("number"))
                      put("b", JsonPrimitive("number"))
                    },
                  required = listOf("a", "b"),
                ),
            ),
        ) { request ->
          val params = request.params
          val a = params.arguments?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
          val b = params.arguments?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
          val sum = a + b
          Log.d(TAG, "add($a, $b) = $sum")
          CallToolResult(
            content = listOf(TextContent(text = sum.toString())),
          )
        }
      }
    bridge = ProviderBridgeTransport()
    // Start a session with the bridge. Requests are delivered lazily by handleMcpCall().
    runBlocking { server.createSession(bridge) }
    return true
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    if (method != METHOD_MCP) {
      return null
    }
    val requestJson = arg ?: return null
    val responseJson = runBlocking { bridge.deliver(requestJson) }
    return responseJson?.let {
      Bundle().apply { putString(KEY_RESPONSE, it) }
    }
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  companion object {
    /** ContentProvider.call() method used for MCP JSON-RPC exchanges. */
    const val METHOD_MCP: String = "mcp"

    /** Bundle key carrying the JSON-RPC response string. */
    const val KEY_RESPONSE: String = "response"
  }
}
