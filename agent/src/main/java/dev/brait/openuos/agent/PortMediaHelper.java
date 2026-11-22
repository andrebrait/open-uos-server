package dev.brait.openuos.agent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-cached copper vs SFP/fiber port classification helper.
 * Relies solely on unobfuscated Device methods and literal media strings ("SFP", "QSFP", "fiber")
 * so it remains immune to Ubiquiti's per-build obfuscator rotation.
 */
public final class PortMediaHelper {
    private static final Map<Class<?>, DeviceAccessors> ACCESSORS = new ConcurrentHashMap<>();

    private PortMediaHelper() {}

    public static boolean isSfpPort(Object device, int portIdx) {
        if (device == null) {
            return false;
        }
        DeviceAccessors acc = ACCESSORS.computeIfAbsent(device.getClass(), DeviceAccessors::resolve);

        // Primary: Device.getPortMediaTypeOnIdx(int) -> Optional<PortMediaType>
        if (acc.getPortMediaTypeOnIdx != null) {
            try {
                Object opt = acc.getPortMediaTypeOnIdx.invoke(device, portIdx);
                if (opt instanceof Optional<?> o && o.isPresent()) {
                    Object mediaType = o.get();
                    if (isFiberMediaName(mediaType.toString())) {
                        return true;
                    }
                    // Also check cablingType enum if available (toString() == "fiber")
                    if (isFiberCablingEnum(mediaType)) {
                        return true;
                    }
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }

        // Fallback: inspect port_table entries directly via getPortTableOrEmptyList()
        if (acc.getPortTableOrEmptyList != null) {
            try {
                Object listObj = acc.getPortTableOrEmptyList.invoke(device);
                if (listObj instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry == null) {
                            continue;
                        }
                        int idx = readIntField(entry, "port_idx", -1);
                        if (idx == portIdx) {
                            String media = readStringField(entry, "media");
                            if (media != null && isFiberMediaName(media)) {
                                return true;
                            }
                            if (readBooleanField(entry, "is_sfp", false)) {
                                return true;
                            }
                            return false;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    /**
     * Used by Device.hasPortEeeCapability(int) instrumentation: SFP ports never report EEE capability.
     */
    public static boolean filterEeeCapability(boolean originalResult, Object device, int portIdx) {
        if (!originalResult) {
            return false;
        }
        return !isSfpPort(device, portIdx);
    }

    /**
     * Used by switch system.cfg flowctrl emitters: SFP ports never receive flowctrl=enabled.
     */
    public static boolean allowFlowControl(boolean requested, Object device, int portIdx) {
        if (!requested) {
            return false;
        }
        return !isSfpPort(device, portIdx);
    }

    static boolean isFiberMediaName(String media) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        String u = media.toUpperCase(Locale.ROOT);
        return u.contains("SFP") || u.contains("FIBER") || u.contains("FIBRE") || u.contains("OPTIC");
    }

    private static boolean isFiberCablingEnum(Object mediaType) {
        for (Method m : mediaType.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().isEnum() && m.getReturnType() != mediaType.getClass()) {
                try {
                    Object cabling = m.invoke(mediaType);
                    if (cabling != null && "fiber".equalsIgnoreCase(cabling.toString())) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static int readIntField(Object obj, String key, int fallback) {
        try {
            Method m = obj.getClass().getMethod("getInt", String.class, int.class);
            Object v = m.invoke(obj, key, fallback);
            return v instanceof Number n ? n.intValue() : fallback;
        } catch (Throwable ignored) {
            if (obj instanceof Map<?, ?> map && map.get(key) instanceof Number n) {
                return n.intValue();
            }
            return fallback;
        }
    }

    private static String readStringField(Object obj, String key) {
        try {
            Method m = obj.getClass().getMethod("getString", String.class);
            Object v = m.invoke(obj, key);
            return v instanceof String s ? s : null;
        } catch (Throwable ignored) {
            if (obj instanceof Map<?, ?> map && map.get(key) instanceof String s) {
                return s;
            }
            return null;
        }
    }

    private static boolean readBooleanField(Object obj, String key, boolean fallback) {
        try {
            Method m = obj.getClass().getMethod("is", String.class, boolean.class);
            Object v = m.invoke(obj, key, fallback);
            return v instanceof Boolean b ? b : fallback;
        } catch (Throwable ignored) {
            if (obj instanceof Map<?, ?> map && map.get(key) instanceof Boolean b) {
                return b;
            }
            return fallback;
        }
    }

    private record DeviceAccessors(Method getPortMediaTypeOnIdx, Method getPortTableOrEmptyList) {
        static DeviceAccessors resolve(Class<?> cls) {
            Method media = null;
            Method ports = null;
            try {
                media = cls.getMethod("getPortMediaTypeOnIdx", int.class);
                media.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                ports = cls.getMethod("getPortTableOrEmptyList");
                ports.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
            return new DeviceAccessors(media, ports);
        }
    }
}
