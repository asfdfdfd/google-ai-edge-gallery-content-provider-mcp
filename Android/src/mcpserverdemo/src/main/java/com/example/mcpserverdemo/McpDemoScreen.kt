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

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The URI to register in AI Edge Gallery as the MCP server URL. */
private const val MCP_SERVER_URI = "content://com.example.mcpserverdemo.mcp/mcp"

/** Demo screen: shows the MCP server URI and runs a self-test against the local provider. */
@Composable
fun McpDemoScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf("Idle") }
  var toolsText by remember { mutableStateOf("") }
  var resultText by remember { mutableStateOf("") }

  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "MCP ContentProvider demo",
        style = MaterialTheme.typography.headlineSmall,
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Register this server in AI Edge Gallery (Agent Chat -> MCP servers -> Add from URL):",
            style = MaterialTheme.typography.bodyMedium,
          )
          OutlinedTextField(
            value = MCP_SERVER_URI,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
          )
          Text(
            text = "The server exposes the \"add\" tool which adds two numbers.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            status = "Running..."
            toolsText = ""
            resultText = ""
            scope.launch {
              try {
                val tools = runSelfTestListTools(context)
                toolsText = tools.joinToString("\n")
                status = "Connected. Calling add(2, 3)..."
                val result = runSelfTestCallTool(context)
                resultText = result
                status = "Self-test passed."
              } catch (e: Exception) {
                status = "Self-test failed: ${e.message ?: e.javaClass.simpleName}"
              }
            }
          },
        ) {
          Text(text = "Run self-test")
        }
      }
      Text(text = "Status: $status", style = MaterialTheme.typography.bodyMedium)
      if (toolsText.isNotEmpty()) {
        Text(
          text = "Tools exposed:\n$toolsText",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (resultText.isNotEmpty()) {
        Text(
          text = "add(2, 3) = $resultText",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

private suspend fun runSelfTestListTools(context: Context): List<String> {
  val client = Client(clientInfo = Implementation(name = "mcp-content-provider-demo", version = "1.0"))
  val transport =
    ClientProviderTransport(
      contentResolver = context.contentResolver,
      uri = Uri.parse(MCP_SERVER_URI),
    )
  try {
    client.connect(transport)
    return client.listTools()?.tools.orEmpty().map { it.name }
  } finally {
    client.close()
  }
}

private suspend fun runSelfTestCallTool(context: Context): String {
  val client = Client(clientInfo = Implementation(name = "mcp-content-provider-demo", version = "1.0"))
  val transport =
    ClientProviderTransport(
      contentResolver = context.contentResolver,
      uri = Uri.parse(MCP_SERVER_URI),
    )
  try {
    client.connect(transport)
    val result =
      client.callTool(
        request =
          CallToolRequest(
            params =
              CallToolRequestParams(
                name = "add",
                arguments =
                  buildJsonObject {
                    put("a", JsonPrimitive(2))
                    put("b", JsonPrimitive(3))
                  },
              ),
          ),
      )
    return result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
  } finally {
    client.close()
  }
}
