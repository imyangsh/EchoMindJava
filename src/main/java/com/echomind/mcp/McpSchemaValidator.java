package com.echomind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class McpSchemaValidator {

    private final ObjectMapper objectMapper;

    public McpSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(JsonNode schema, Map<String, Object> arguments) {
        if (schema == null || schema.isMissingNode() || schema.isNull()
                || (schema.isObject() && !schema.fieldNames().hasNext())) {
            return ValidationResult.ok();
        }
        JsonNode value = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
        List<String> errors = new ArrayList<>();
        validateNode("$", schema, value, errors);
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private void validateNode(String path, JsonNode schema, JsonNode value, List<String> errors) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matched = false;
            for (JsonNode enumValue : enumValues) {
                if (enumValue.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " must be one of " + enumValues);
                return;
            }
        }
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !typeMatches(type, value)) {
            errors.add(path + " must be " + type);
            return;
        }
        if ("object".equals(type) || schema.has("properties")) {
            validateObject(path, schema, value, errors);
        }
        if ("array".equals(type) && value.isArray() && schema.has("items")) {
            int index = 0;
            for (JsonNode item : value) {
                validateNode(path + "[" + index + "]", schema.get("items"), item, errors);
                index++;
            }
        }
    }

    private void validateObject(String path, JsonNode schema, JsonNode value, List<String> errors) {
        if (!value.isObject()) {
            errors.add(path + " must be object");
            return;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asText("");
                if (!name.isBlank() && !value.has(name)) {
                    errors.add(path + "." + name + " is required");
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (value.has(entry.getKey())) {
                    validateNode(path + "." + entry.getKey(), entry.getValue(), value.get(entry.getKey()), errors);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean() && properties != null && properties.isObject()) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!properties.has(name)) {
                    errors.add(path + "." + name + " is not allowed");
                }
            }
        }
    }

    private boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }
    }
}
