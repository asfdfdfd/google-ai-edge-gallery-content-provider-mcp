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

package com.google.ai.edge.gallery.mcp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGContentProviderTransport"

/** Number of send attempts for transient provider lookup failures. */
private const val MAX_ATTEMPTS = 2

/** Delay between retries of a provider lookup failure. */
private const val RETRY_DELAY_MS = 1_000L

/**
 * An MCP [io.modelcontextprotocol.kotlin.sdk.shared.Transport] that exchanges JSON-RPC messages
 * with a remote MCP server exposed through an Android [ContentProvider].
 *
 * The MCP server URL must be a `content://` URI (e.g. `content://com.example.mcpserver/mcp`).
 * Each message is sent as a synchronous request through [ContentResolver.call] with the method
 * `mcp` and the JSON-RPC payload as the argument. Requests receive a JSON-RPC response back in the
 * returned bundle; notifications receive no response.
 */
class ContentProviderTransport(
  private val context: Context,
  private val uri: Uri,
  private val json: Json = McpJson,
) : AbstractTransport() {

  private val contentResolver: ContentResolver = context.contentResolver

  override suspend fun start() {
    // The connection is stateless: each send() performs its own round trip through the provider.
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    val requestJson = json.encodeToString(JSONRPCMessage.serializer(), message)
    for (attempt in 0 until MAX_ATTEMPTS) {
      try {
        val responseJson =
          withContext(Dispatchers.IO) {
            val result = contentResolver.call(uri, METHOD_MCP, requestJson, null)
            result?.getString(KEY_RESPONSE)
          }
        if (responseJson == null) {
          // Notifications and other one-way messages produce no response.
          return
        }
        val response = json.decodeFromString(JSONRPCMessage.serializer(), responseJson)
        _onMessage(response)
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Error calling MCP provider $uri (attempt ${attempt + 1}/$MAX_ATTEMPTS)", e)
        // Transient failures happen when the provider app is being installed, updated or its
        // process has just started. Retry once before surfacing the error.
        if (attempt < MAX_ATTEMPTS - 1 && isProviderLookupFailure(e)) {
          delay(RETRY_DELAY_MS)
          continue
        }
        throw describeError(e)
      }
    }
  }

  override suspend fun close() {
    _onClose()
  }

  /**
   * Provider lookup failures (e.g. "Unknown authority") are thrown by the framework when the
   * authority cannot be resolved; any other error comes from inside the provider itself.
   */
  private fun isProviderLookupFailure(e: Exception): Boolean {
    val message = e.message.orEmpty()
    return e is IllegalArgumentException && message.contains("Unknown authority") ||
      message.contains("Failed to find provider")
  }

  /** Wraps the raw exception with a diagnostic message explaining why the provider is unreachable. */
  private fun describeError(e: Exception): Exception {
    val authority = uri.authority.orEmpty()
    val registered =
      try {
        context.packageManager.queryContentProviders(null, 0, 0).any { info ->
          info.authority == authority || info.authority.contains("$authority.")
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to query content providers", t)
        null
      }
    val hint =
      when (registered) {
        true ->
          "MCP provider '$authority' is installed but did not respond " +
            "(provider app crashed or is not exported?)"
        false ->
          "MCP provider '$authority' is not registered on this device. " +
            "Make sure the provider app is installed and the authority matches its manifest."
        null ->
          "Could not verify whether MCP provider '$authority' is installed. " +
            "Make sure the provider app is installed and the authority matches its manifest."
      }
    return IllegalStateException("Unable to reach MCP provider '$authority': ${e.message}. $hint", e)
  }

  companion object {
    /** ContentProvider.call() method used for MCP JSON-RPC exchanges. */
    const val METHOD_MCP: String = "mcp"

    /** Bundle key carrying the JSON-RPC response string. */
    const val KEY_RESPONSE: String = "response"
  }
}
