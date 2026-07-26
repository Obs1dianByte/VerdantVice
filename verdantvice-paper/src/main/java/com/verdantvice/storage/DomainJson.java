package com.verdantvice.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.verdantvice.genetics.GeneticClass;
import com.verdantvice.genetics.GeneticProfile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DomainJson {
    private DomainJson() {
    }

    public static String genetics(GeneticProfile profile) {
        JsonObject json = new JsonObject();
        json.addProperty("yieldModifier", profile.yieldModifier());
        json.addProperty("growthSpeedModifier", profile.growthSpeedModifier());
        json.addProperty("resistanceModifier", profile.resistanceModifier());
        json.addProperty("qualityModifier", profile.qualityModifier());
        json.addProperty("smellModifier", profile.smellModifier());
        json.addProperty("stabilityModifier", profile.stabilityModifier());
        json.addProperty("negativeEffectModifier", profile.negativeEffectModifier());
        return json.toString();
    }

    public static GeneticProfile genetics(
            UUID id,
            String geneticClass,
            String json,
            Instant createdAt
    ) {
        JsonObject value = JsonParser.parseString(json).getAsJsonObject();
        return new GeneticProfile(
                id,
                GeneticClass.valueOf(geneticClass),
                number(value, "yieldModifier"),
                number(value, "growthSpeedModifier"),
                number(value, "resistanceModifier"),
                number(value, "qualityModifier"),
                number(value, "smellModifier"),
                number(value, "stabilityModifier"),
                number(value, "negativeEffectModifier"),
                createdAt
        );
    }

    public static String composition(Map<String, Double> composition) {
        JsonObject json = new JsonObject();
        composition.forEach(json::addProperty);
        return json.toString();
    }

    public static Map<String, Double> composition(String source) {
        JsonObject json = JsonParser.parseString(source).getAsJsonObject();
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsDouble());
        }
        return Map.copyOf(result);
    }

    public static String stringSet(Set<String> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.stream().sorted().forEach(array::add);
        return array.toString();
    }

    public static Set<String> stringSet(String source) {
        if (source == null || source.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonElement element : JsonParser.parseString(source).getAsJsonArray()) {
            result.add(element.getAsString());
        }
        return Set.copyOf(result);
    }

    public static String uuidSet(Set<UUID> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.stream().map(UUID::toString).sorted().forEach(array::add);
        return array.toString();
    }

    public static Set<UUID> uuidSet(String source) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (JsonElement element : JsonParser.parseString(source).getAsJsonArray()) {
            result.add(UUID.fromString(element.getAsString()));
        }
        return Set.copyOf(result);
    }

    public static String stringMap(Map<String, ?> values) {
        JsonObject json = new JsonObject();
        values.forEach((key, value) -> {
            if (value == null) {
                json.add(key, com.google.gson.JsonNull.INSTANCE);
            } else if (value instanceof Number number) {
                json.addProperty(key, number);
            } else if (value instanceof Boolean bool) {
                json.addProperty(key, bool);
            } else {
                json.addProperty(key, value.toString());
            }
        });
        return json.toString();
    }

    private static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing numeric JSON field: " + key);
        }
        return value.getAsDouble();
    }
}
