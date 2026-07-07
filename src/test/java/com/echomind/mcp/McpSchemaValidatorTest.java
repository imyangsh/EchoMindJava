package com.echomind.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpSchemaValidator validator = new McpSchemaValidator(objectMapper);

    @Test
    void validatesRequiredTypesEnumAndAdditionalProperties() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type": "object",
                  "required": ["order_id", "mode"],
                  "additionalProperties": false,
                  "properties": {
                    "order_id": {"type": "string"},
                    "count": {"type": "integer"},
                    "mode": {"type": "string", "enum": ["brief", "full"]}
                  }
                }
                """);

        assertThat(validator.validate(schema, Map.of("order_id", "123", "count", 2, "mode", "brief")).valid()).isTrue();

        McpSchemaValidator.ValidationResult result = validator.validate(schema, Map.of(
                "order_id", 123,
                "mode", "verbose",
                "extra", true
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("$.order_id must be string"));
        assertThat(result.errors()).anyMatch(error -> error.contains("$.mode must be one of"));
        assertThat(result.errors()).anyMatch(error -> error.contains("$.extra is not allowed"));
    }
}
