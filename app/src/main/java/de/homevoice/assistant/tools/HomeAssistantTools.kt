package de.homevoice.assistant.tools

import de.homevoice.assistant.mcp.HomeAssistantMcpClient
import de.homevoice.assistant.mcp.SchemaConverter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val HA_GROUP = "ha"
private const val HA_PREFIX = "ha_"

/** Loads the Home Assistant MCP tools and exposes them as Gemini tools with an "ha_" prefix. */
suspend fun loadHomeAssistantTools(client: HomeAssistantMcpClient): List<ToolHandler> =
    client.listTools().map { tool ->
        val declaration = SchemaConverter.toFunctionDeclaration(
            name = SchemaConverter.functionName(HA_PREFIX, tool.name),
            description = tool.description,
            inputSchema = tool.inputSchema,
        )
        ToolHandler(declaration) { args ->
            val result = client.callTool(tool.name, args)
            buildJsonObject {
                if (result.isError) put("error", result.text) else put("result", result.text)
            }
        }
    }
