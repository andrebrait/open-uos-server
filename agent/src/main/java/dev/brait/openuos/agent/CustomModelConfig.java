package dev.brait.openuos.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * External custom device/switch model specification loaded from custom-models.json.
 */
public record CustomModelConfig(
    String model,
    String name,
    String abbrev,
    String sku,
    String type,
    String chipset,
    int sysId,
    int portNum,
    int standardPorts,
    List<Integer> sfpPorts,
    int maxSpeedMbps,
    List<String> features,
    String iconModel
) {
    private static final Pattern VALID_ENUM_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    public String sysIdHex() {
        return Integer.toHexString(sysId);
    }

    public String maxSpeedEnumName() {
        return switch (maxSpeedMbps) {
            case 100 -> "FAST_ETHERNET";
            case 1000 -> "GIGABIT_ETHERNET";
            case 2500 -> "TWO_AND_HALF_GIGABIT_ETHERNET";
            case 5000 -> "FIVE_GIGABIT_ETHERNET";
            case 10000 -> "TEN_GIGABIT_ETHERNET";
            case 25000 -> "TWENTY_FIVE_GIGABIT_ETHERNET";
            case 100000 -> "ONE_HUNDRED_GIGABIT_ETHERNET";
            default -> "GIGABIT_ETHERNET";
        };
    }

    public String featuresCsv() {
        return String.join(",", features);
    }

    public static List<CustomModelConfig> loadFromFile(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        return parseJson(raw);
    }

    @SuppressWarnings("unchecked")
    public static List<CustomModelConfig> parseJson(String raw) {
        Object parsed = MiniJson.parse(raw);
        List<Object> items;
        if (parsed instanceof Map<?, ?> root && root.get("models") instanceof List<?> list) {
            items = (List<Object>) list;
        } else if (parsed instanceof List<?> list) {
            items = (List<Object>) list;
        } else {
            throw new IllegalArgumentException("Expected {\"models\": [...]} or [...] in custom-models.json");
        }

        List<CustomModelConfig> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("Each custom model entry must be a JSON object");
            }
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String model = reqString(m, "model").toUpperCase(Locale.ROOT);
            if (!VALID_ENUM_NAME.matcher(model).matches()) {
                throw new IllegalArgumentException("Invalid model identifier '" + model + "' (use A-Z, 0-9, _)");
            }
            String name = optString(m, "name", model);
            String abbrev = optString(m, "abbrev", name);
            String sku = optString(m, "sku", model);
            String type = optString(m, "type", "USW").toUpperCase(Locale.ROOT);
            String chipset = optString(m, "chipset", "REALTEK").toUpperCase(Locale.ROOT);
            int sysId = parseSysId(m.get("sysid"));
            int portNum = optInt(m, "port_num", 8);
            List<Integer> sfpPorts = optIntList(m, "sfp_ports");
            int standardPorts = optInt(m, "standard_ports", Math.max(0, portNum - sfpPorts.size()));
            int maxSpeedMbps = optInt(m, "max_speed_mbps", 1000);
            List<String> features = optStringList(m, "features");
            if (features.isEmpty()) {
                features = defaultFeaturesForType(type);
            }
            String iconModel = optString(m, "icon_model", null);

            out.add(new CustomModelConfig(
                model, name, abbrev, sku, type, chipset, sysId,
                portNum, standardPorts, List.copyOf(sfpPorts),
                maxSpeedMbps, List.copyOf(features), iconModel
            ));
        }
        return List.copyOf(out);
    }

    private static List<String> defaultFeaturesForType(String type) {
        return switch (type) {
            case "USW" -> List.of("SWITCH", "HAS_CLIENTS");
            case "UGW" -> List.of("GATEWAY", "HAS_CLIENTS");
            case "UXG" -> List.of("GATEWAY", "APPLICATION_GATEWAY", "HAS_CLIENTS");
            case "UAP" -> List.of("ACCESS_POINT", "VPORT", "HAS_CLIENTS");
            default -> List.of("HAS_CLIENTS");
        };
    }

    private static int parseSysId(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            String clean = s.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("0x")) {
                clean = clean.substring(2);
            }
            return Integer.parseInt(clean, 16);
        }
        throw new IllegalArgumentException("Missing or invalid 'sysid' (expected hex string like \"f901\" or int)");
    }

    private static String reqString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        throw new IllegalArgumentException("Missing required string field '" + key + "'");
    }

    private static String optString(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return (v instanceof String s && !s.isBlank()) ? s.trim() : fallback;
    }

    private static int optInt(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static List<Integer> optIntList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> res = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) {
                res.add(n.intValue());
            }
        }
        return res;
    }

    private static List<String> optStringList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> res = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                res.add(s.trim().toUpperCase(Locale.ROOT));
            }
        }
        return res;
    }
}
