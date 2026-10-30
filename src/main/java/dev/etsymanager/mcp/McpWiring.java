package dev.etsymanager.mcp;

import dev.etsymanager.tools.ToolDefinitions;
import dev.etsymanager.tools.ToolHandlers;
import dev.etsymanager.tools.ToolResult;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registers the tool layer with the MCP SDK over stdio. */
public final class McpWiring {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpWiring() {}

    public static McpSyncServer start(ToolHandlers handlers) {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JSON);
        SyncToolSpecification[] specs = ToolDefinitions.ALL.stream()
                .map(def -> SyncToolSpecification.builder()
                        .tool(Tool.builder()
                                .name(def.name())
                                .description(def.description())
                                .inputSchema(mapper, def.inputSchema())
                                .build())
                        .callHandler((exchange, request) -> {
                            JsonNode args = JSON.valueToTree(request.arguments());
                            ToolResult result = handlers.call(def.name(), args);
                            return CallToolResult.builder()
                                    .addTextContent(JSON.writeValueAsString(result.body()))
                                    .isError(result.isError())
                                    .build();
                        })
                        .build())
                .toArray(SyncToolSpecification[]::new);
        return McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("etsy-manager-mcp", "0.1.0")
                .instructions("Read and carefully edit the owner's own Etsy listings (title, tags, description, "
                        + "materials). Edits are previewed first; apply them only with confirm: true and a reason.")
                .tools(specs)
                .build();
    }
}
