package com.example.aichat.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 构造 JSON Schema 的小工具，避免每个工具各写一遍样板代码。
 */
public final class Schemas {
    private Schemas() {
    }

    public static JsonObject stringProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    public static JsonObject enumProperty(String description, String... values) {
        JsonObject property = stringProperty(description);
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        property.add("enum", array);
        return property;
    }

    public static JsonObject booleanProperty(String description, boolean defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        return property;
    }

    public static JsonObject integerProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }

    /**
     * @param properties 属性表
     * @param required   必填字段名，可以为空
     */
    public static JsonObject object(JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required.length > 0) {
            JsonArray array = new JsonArray();
            for (String field : required) {
                array.add(field);
            }
            schema.add("required", array);
        }
        return schema;
    }
}
