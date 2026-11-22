package dev.brait.openuos.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runtime helpers invoked from ASM-instrumented bytecode inside ace.jar.
 * Lives on the AppClassLoader so Spring Boot's LaunchedClassLoader can call it directly.
 */
public final class CustomModelRuntime {
    private static volatile List<CustomModelConfig> configs = List.of();
    private static volatile Set<String> customModelNames = Set.of();
    private static final Map<Class<?>, Method> GET_MODEL_CACHE = new ConcurrentHashMap<>();

    private CustomModelRuntime() {}

    public static void setConfigs(List<CustomModelConfig> loaded) {
        configs = List.copyOf(loaded);
        customModelNames = loaded.stream()
            .map(CustomModelConfig::model)
            .collect(Collectors.toUnmodifiableSet());
    }

    public static List<CustomModelConfig> getConfigs() {
        return configs;
    }

    public static boolean isCustomModelName(String modelName) {
        return modelName != null && customModelNames.contains(modelName.toUpperCase(Locale.ROOT));
    }

    public static boolean isCustomDevice(Object device) {
        if (device == null || customModelNames.isEmpty()) {
            return false;
        }
        try {
            Method m = GET_MODEL_CACHE.computeIfAbsent(device.getClass(), cls -> {
                try {
                    Method gm = cls.getMethod("getModel");
                    gm.setAccessible(true);
                    return gm;
                } catch (NoSuchMethodException e) {
                    return null;
                }
            });
            if (m != null) {
                Object model = m.invoke(device);
                if (model instanceof Enum<?> e && customModelNames.contains(e.name())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Suppresses firmware upgradability for custom models while preserving upstream behavior for UniFi devices.
     */
    public static boolean filterUpgradable(Object device, boolean requested) {
        if (requested && isCustomDevice(device)) {
            return false;
        }
        return requested;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Enum<?> resolveEnum(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, name);
        } catch (IllegalArgumentException e) {
            // Fall back to UNKNOWN if present, else first constant
            try {
                return Enum.valueOf((Class<? extends Enum>) enumClass, "UNKNOWN");
            } catch (IllegalArgumentException ignored) {
                Object[] constants = enumClass.getEnumConstants();
                if (constants != null && constants.length > 0) {
                    return (Enum<?>) constants[0];
                }
                throw e;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Set<?> buildFeatureSet(Class<?> featureEnumClass, String featuresCsv) {
        EnumSet set = EnumSet.noneOf((Class<? extends Enum>) featureEnumClass);
        if (featuresCsv != null && !featuresCsv.isBlank()) {
            for (String raw : featuresCsv.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    set.add(Enum.valueOf((Class<? extends Enum>) featureEnumClass, token));
                } catch (IllegalArgumentException ex) {
                    System.err.println("[open-uos-agent] Ignoring unknown feature '" + token + "' for " + featureEnumClass.getName());
                }
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public static Object[] growValuesArray(Object[] original, int extraCount) {
        Object[] grown = (Object[]) Array.newInstance(original.getClass().getComponentType(), original.length + extraCount);
        System.arraycopy(original, 0, grown, 0, original.length);
        return grown;
    }

    /**
     * Merges synthetic UIDB device entries for custom models into a loaded UiDbSnapshot record.
     * Uses the UiDbUtils instance's own Jackson ObjectMapper so no obfuscated field names are needed.
     */
    @SuppressWarnings("unchecked")
    public static Object mergeCustomUidbRecords(Object uiDbUtilsInstance, Object snapshot) {
        if (snapshot == null || configs.isEmpty()) {
            return snapshot;
        }
        try {
            Class<?> snapClass = snapshot.getClass();
            if (!snapClass.isRecord()) {
                return snapshot;
            }
            RecordComponent[] comps = snapClass.getRecordComponents();
            if (comps.length != 3) {
                return snapshot;
            }
            // UiDbSnapshot record: (String version, List<UiDbRecord> devices, int uiDbRecordVersion)
            String version = (String) comps[0].getAccessor().invoke(snapshot);
            List<Object> devices = (List<Object>) comps[1].getAccessor().invoke(snapshot);
            int recordVersion = (Integer) comps[2].getAccessor().invoke(snapshot);

            Class<?> recordClass = extractListElementClass(comps[1].getGenericType());
            if (recordClass == null) {
                return snapshot;
            }
            Object objectMapper = findObjectMapper(uiDbUtilsInstance);
            if (objectMapper == null) {
                return snapshot;
            }
            Method readValue = objectMapper.getClass().getMethod("readValue", String.class, Class.class);
            Method writeValueAsString = objectMapper.getClass().getMethod("writeValueAsString", Object.class);

            // Index configured custom sysIds so stale entries cached in local uidb.json are refreshed
            Set<String> customSysIds = configs.stream()
                .map(c -> c.sysIdHex().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
            List<Object> merged = new ArrayList<>(devices.size() + configs.size());

            Method sysidsAccessor = recordClass.getMethod("sysids");
            Method shortnamesAccessor = recordClass.getMethod("shortnames");
            for (Object rec : devices) {
                List<String> sids = (List<String>) sysidsAccessor.invoke(rec);
                boolean isStaleCustom = sids != null && sids.stream()
                    .anyMatch(s -> s != null && customSysIds.contains(s.toLowerCase(Locale.ROOT)));
                if (!isStaleCustom) {
                    merged.add(rec);
                }
            }

            int added = 0;
            for (CustomModelConfig cfg : configs) {
                String donorId = null;
                String iconJson = "null";
                String imagesJson = "null";
                if (cfg.iconModel() != null) {
                    Object donor = findDonorRecord(merged, shortnamesAccessor, cfg.iconModel());
                    if (donor != null) {
                        Object idObj = recordClass.getMethod("id").invoke(donor);
                        if (idObj instanceof String s && !s.isBlank()) {
                            donorId = s;
                        }
                        Object iconObj = recordClass.getMethod("icon").invoke(donor);
                        Object imagesObj = recordClass.getMethod("images").invoke(donor);
                        if (iconObj != null) {
                            iconJson = (String) writeValueAsString.invoke(objectMapper, iconObj);
                        }
                        if (imagesObj != null) {
                            imagesJson = (String) writeValueAsString.invoke(objectMapper, imagesObj);
                        }
                    }
                }
                String recJson = buildUidbRecordJson(cfg, donorId, iconJson, imagesJson);
                Object synthetic = readValue.invoke(objectMapper, recJson, recordClass);
                merged.add(synthetic);
                added++;
            }

            if (added == 0) {
                return snapshot;
            }

            Constructor<?> ctor = snapClass.getDeclaredConstructor(String.class, List.class, int.class);
            ctor.setAccessible(true);
            Object updated = ctor.newInstance(version, Collections.unmodifiableList(merged), recordVersion);
            System.out.println("[open-uos-agent] Merged " + added + " custom model(s) into UIDB snapshot (" + version + ")");
            return updated;
        } catch (Throwable t) {
            System.err.println("[open-uos-agent] Failed to merge custom models into UIDB snapshot: " + t);
            return snapshot;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object findDonorRecord(List<Object> devices, Method shortnamesAccessor, String iconModel) {
        String target = iconModel.toUpperCase(Locale.ROOT);
        for (Object rec : devices) {
            try {
                List<String> names = (List<String>) shortnamesAccessor.invoke(rec);
                if (names != null) {
                    for (String n : names) {
                        if (n != null && n.toUpperCase(Locale.ROOT).equals(target)) {
                            return rec;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String buildUidbRecordJson(CustomModelConfig cfg, String donorId, String iconJson, String imagesJson) {
        String id = (donorId != null && !donorId.isBlank())
            ? donorId
            : UUID.nameUUIDFromBytes(("open-uos-model:" + cfg.model()).getBytes()).toString();
        String sfpJson = cfg.sfpPorts().stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(MiniJson.quote(id)).append(',');
        sb.append("\"sysids\":[").append(MiniJson.quote(cfg.sysIdHex().toLowerCase(Locale.ROOT))).append("],");
        sb.append("\"guids\":[],");
        sb.append("\"sku\":").append(MiniJson.quote(cfg.sku())).append(',');
        sb.append("\"product\":{")
            .append("\"name\":").append(MiniJson.quote(cfg.name())).append(',')
            .append("\"abbrev\":").append(MiniJson.quote(cfg.abbrev()))
            .append("},");
        if (!"null".equals(iconJson)) {
            sb.append("\"icon\":").append(iconJson).append(',');
        }
        if (!"null".equals(imagesJson)) {
            sb.append("\"images\":").append(imagesJson).append(',');
        }
        sb.append("\"shortnames\":[")
            .append(MiniJson.quote(cfg.model())).append(',')
            .append(MiniJson.quote(cfg.sku())).append(',')
            .append(MiniJson.quote(cfg.abbrev()))
            .append("],");
        sb.append("\"unifi\":{\"adoptability\":\"adoptable\",\"network\":{")
            .append("\"model\":").append(MiniJson.quote(cfg.model())).append(',')
            .append("\"numberOfPorts\":").append(cfg.portNum()).append(',')
            .append("\"ethernetMaxSpeedMegabitsPerSecond\":").append(cfg.maxSpeedMbps()).append(',')
            .append("\"systemIdHexadecimal\":").append(MiniJson.quote(cfg.sysIdHex().toLowerCase(Locale.ROOT))).append(',')
            .append("\"ports\":{\"standard\":").append(cfg.standardPorts()).append(",\"sfp\":").append(sfpJson).append("}")
            .append("}}");
        sb.append('}');
        return sb.toString();
    }

    private static Class<?> extractListElementClass(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
    }

    private static Object findObjectMapper(Object target) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals("com.fasterxml.jackson.databind.ObjectMapper")) {
                    try {
                        f.setAccessible(true);
                        return f.get(target);
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
        return null;
    }
}
