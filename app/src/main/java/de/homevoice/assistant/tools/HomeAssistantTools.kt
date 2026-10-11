package de.homevoice.assistant.tools

import de.homevoice.assistant.mcp.HomeAssistantMcpClient
import de.homevoice.assistant.mcp.SchemaConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

const val HA_GROUP = "ha"
private const val HA_PREFIX = "ha_"

/**
 * HA-MCP tools a voice assistant may use: reading states, switching things, calendars, to-dos and
 * history. Admin tools (restart, deleting devices/automations, HACS, backups, …) stay hidden unless
 * explicitly allowed, so a misheard sentence cannot break the installation. Tools of the built-in
 * MCP server (HassTurnOn, GetLiveContext, …) do not start with "ha_" and are always offered.
 */
val SAFE_HA_MCP_TOOLS = setOf(
    "ha_get_state", "ha_search", "ha_call_service", "ha_bulk_control", "ha_get_operation_status",
    "ha_get_history", "ha_get_overview", "ha_list_floors_areas", "ha_get_zone", "ha_eval_template",
    "ha_config_get_calendar_events", "ha_config_set_calendar_event",
    "ha_get_todo", "ha_set_todo_item", "ha_remove_todo_item", "ha_list_services",
)

/** Loads the Home Assistant MCP tools and exposes them as Gemini tools with an "ha_" prefix. */
suspend fun loadHomeAssistantTools(client: HomeAssistantMcpClient, allowAll: Boolean = false): List<ToolHandler> =
    client.listTools().filter { allowAll || !it.name.startsWith("ha_") || it.name in SAFE_HA_MCP_TOOLS }.map { tool ->
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

/** Renders a Jinja template in Home Assistant (needs the HA-MCP tool ha_eval_template). */
suspend fun renderHomeAssistantTemplate(client: HomeAssistantMcpClient, template: String): String {
    val result = client.callTool("ha_eval_template", buildJsonObject { put("template", template) })
    if (result.isError) throw IllegalStateException(result.text.take(300))
    val obj = runCatching { Json.parseToJsonElement(result.text) as? JsonObject }.getOrNull()
        ?: return result.text
    if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == false) {
        throw IllegalStateException(obj["error"]?.toString()?.take(300) ?: "Template-Fehler")
    }
    return (obj["result"] as? JsonPrimitive)?.contentOrNull ?: result.text
}
