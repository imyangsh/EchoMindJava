package com.echomind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class FakeStdioMcpServer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static int toolsListCalls = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--json-lines".equals(args[0])) {
            runJsonLines();
        } else {
            runContentLength();
        }
    }

    private static void runJsonLines() throws Exception {
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode request = OBJECT_MAPPER.readTree(line);
                if (!request.has("id")) {
                    continue;
                }
                ObjectNode response = OBJECT_MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                response.set("result", resultFor(request));
                writer.write(OBJECT_MAPPER.writeValueAsString(response));
                writer.newLine();
                writer.flush();
            }
        }
    }

    private static void runContentLength() throws Exception {
        InputStream input = System.in;
        OutputStream output = System.out;
        while (true) {
            byte[] headerBytes = readHeaderBytes(input);
            if (headerBytes.length == 0) {
                return;
            }
            int contentLength = parseContentLength(headerBytes);
            if (contentLength <= 0) {
                continue;
            }
            byte[] body = input.readNBytes(contentLength);
            if (body.length < contentLength) {
                return;
            }
            JsonNode request = OBJECT_MAPPER.readTree(body);
            if (!request.has("id")) {
                continue;
            }
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", resultFor(request));
            writeFrame(output, response);
        }
    }

    private static void writeFrame(OutputStream output, JsonNode response) throws Exception {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(response);
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static byte[] readHeaderBytes(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            buffer.write(value);
            byte[] bytes = buffer.toByteArray();
            if (endsWith(bytes, new byte[]{'\r', '\n', '\r', '\n'})) {
                return Arrays.copyOf(bytes, bytes.length - 4);
            }
            if (endsWith(bytes, new byte[]{'\n', '\n'})) {
                return Arrays.copyOf(bytes, bytes.length - 2);
            }
        }
        return buffer.toByteArray();
    }

    private static int parseContentLength(byte[] headerBytes) {
        String headers = new String(headerBytes, StandardCharsets.US_ASCII);
        for (String header : headers.split("\\r?\\n")) {
            int separator = header.indexOf(':');
            if (separator <= 0 || !"content-length".equalsIgnoreCase(header.substring(0, separator).trim())) {
                continue;
            }
            return Integer.parseInt(header.substring(separator + 1).trim());
        }
        return -1;
    }

    private static boolean endsWith(byte[] bytes, byte[] suffix) {
        if (bytes.length < suffix.length) {
            return false;
        }
        for (int i = 0; i < suffix.length; i++) {
            if (bytes[bytes.length - suffix.length + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode resultFor(JsonNode request) {
        String method = request.path("method").asText("");
        return switch (method) {
            case "initialize" -> initializeResult();
            case "tools/list" -> toolsListResult();
            case "tools/call" -> toolsCallResult(request.path("params"));
            default -> OBJECT_MAPPER.createObjectNode();
        };
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        result.set("capabilities", OBJECT_MAPPER.createObjectNode().set("tools", OBJECT_MAPPER.createObjectNode()));
        ObjectNode serverInfo = OBJECT_MAPPER.createObjectNode();
        serverInfo.put("name", "fake-stdio-mcp");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private static ObjectNode toolsListResult() {
        toolsListCalls++;
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("echo_order", "Echo an order id", true));
        tools.add(tool("delete_order", "Dangerous delete simulation", false));
        return result;
    }

    private static ObjectNode tool(String name, String description, boolean includeMode) {
        ObjectNode tool = OBJECT_MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ArrayNode required = schema.putArray("required");
        required.add("order_id");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("order_id").put("type", "string");
        if (includeMode) {
            ObjectNode mode = properties.putObject("mode");
            mode.put("type", "string");
            mode.putArray("enum").add("brief").add("full");
        }
        return tool;
    }

    private static ObjectNode toolsCallResult(JsonNode params) {
        String toolName = params.path("name").asText("");
        String orderId = params.path("arguments").path("order_id").asText("");
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("isError", false);
        result.put("toolsListCalls", toolsListCalls);
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", toolName + ":" + orderId);
        return result;
    }
}
