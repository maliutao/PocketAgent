package com.pocketagent.app.tools

import com.pocketagent.app.llm.ToolDefinition

/** 工具注册表:汇聚所有工具,导出为 LLM 的工具定义列表。 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun toDefinitions(): List<ToolDefinition> = tools.values.map {
        ToolDefinition(name = it.name, description = it.description, parameters = it.parametersSchema)
    }
}
