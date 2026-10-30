package dev.etsymanager.tools;

import tools.jackson.databind.JsonNode;

public record ToolResult(JsonNode body, boolean isError) {}
