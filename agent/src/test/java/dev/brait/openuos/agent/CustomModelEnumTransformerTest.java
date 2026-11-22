package dev.brait.openuos.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomModelEnumTransformerTest {

    public enum MockDeviceType { USW, UGW, UAP, UNKNOWN }
    public enum MockFeature { SWITCH, GATEWAY, HAS_CLIENTS, HAS_TEMPERATURE_SENSOR }
    public enum MockChipset { REALTEK, BROADCOM, INTEL, UNKNOWN }
    public enum MockSpeed {
        FAST_ETHERNET(100),
        GIGABIT_ETHERNET(1000),
        TWO_AND_HALF_GIGABIT_ETHERNET(2500),
        TEN_GIGABIT_ETHERNET(10000);
        final int mbps;
        MockSpeed(int mbps) { this.mbps = mbps; }
    }

    /** Mirrors the exact constructor & static-init structure of UniFi's DeviceModel enum. */
    public enum MockDeviceModel {
        US24P250(
            MockDeviceType.USW,
            Set.of(MockFeature.SWITCH, MockFeature.HAS_CLIENTS),
            MockChipset.BROADCOM,
            60209,
            26,
            true,
            MockSpeed.GIGABIT_ETHERNET,
            Collections.emptyMap()
        ),
        UNKNOWN(
            MockDeviceType.UNKNOWN,
            Set.of(),
            MockChipset.UNKNOWN,
            0,
            1,
            true,
            MockSpeed.GIGABIT_ETHERNET,
            Collections.emptyMap()
        );

        private static final Map<Integer, MockDeviceModel> BY_SYSID = buildSysIdMap();

        final MockDeviceType type;
        final Set<MockFeature> features;
        final MockChipset chipset;
        final int sysId;
        final int portNum;
        final boolean analytics;
        final MockSpeed maxSpeed;
        final Map<?, ?> radios;

        MockDeviceModel(
            MockDeviceType type,
            Set<MockFeature> features,
            MockChipset chipset,
            int sysId,
            int portNum,
            boolean analytics,
            MockSpeed maxSpeed,
            Map<?, ?> radios
        ) {
            this.type = type;
            this.features = features;
            this.chipset = chipset;
            this.sysId = sysId;
            this.portNum = portNum;
            this.analytics = analytics;
            this.maxSpeed = maxSpeed;
            this.radios = radios;
        }

        private static Map<Integer, MockDeviceModel> buildSysIdMap() {
            Map<Integer, MockDeviceModel> map = new HashMap<>();
            for (MockDeviceModel m : values()) {
                map.put(m.sysId, m);
            }
            return map;
        }

        public static MockDeviceModel bySysId(int id) {
            return BY_SYSID.getOrDefault(id, UNKNOWN);
        }

        public int getSysId() { return sysId; }
        public int getPortNum() { return portNum; }
        public MockDeviceType getType() { return type; }
        public Set<MockFeature> getFeatures() { return features; }
        public MockSpeed getMaxSpeed() { return maxSpeed; }
    }

    @Test
    void parsesCustomModelsJson() {
        String json = """
            {
              "models": [
                {
                  "model": "RTL_LG510S",
                  "name": "LG510S Switch",
                  "abbrev": "LG510S",
                  "sku": "RTL-LG510S",
                  "type": "USW",
                  "chipset": "REALTEK",
                  "sysid": "f901",
                  "port_num": 10,
                  "standard_ports": 8,
                  "sfp_ports": [9, 10],
                  "max_speed_mbps": 2500,
                  "features": ["SWITCH", "HAS_CLIENTS", "HAS_TEMPERATURE_SENSOR"],
                  "icon_model": "USL8LP"
                }
              ]
            }
            """;
        List<CustomModelConfig> list = CustomModelConfig.parseJson(json);
        assertEquals(1, list.size());
        CustomModelConfig cfg = list.get(0);
        assertEquals("RTL_LG510S", cfg.model());
        assertEquals(0xf901, cfg.sysId());
        assertEquals("f901", cfg.sysIdHex());
        assertEquals("TWO_AND_HALF_GIGABIT_ETHERNET", cfg.maxSpeedEnumName());
        assertEquals(List.of(9, 10), cfg.sfpPorts());
        assertEquals(8, cfg.standardPorts());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void injectsCustomModelsIntoDeviceModelEnumBytecode() throws Exception {
        List<CustomModelConfig> configs = CustomModelConfig.parseJson("""
            {
              "models": [
                {
                  "model": "RTL_LG510S",
                  "name": "LG510S Switch",
                  "type": "USW",
                  "chipset": "REALTEK",
                  "sysid": "f901",
                  "port_num": 10,
                  "max_speed_mbps": 2500,
                  "features": ["SWITCH", "HAS_CLIENTS", "HAS_TEMPERATURE_SENSOR"]
                },
                {
                  "model": "PFSENSE_GW",
                  "name": "pfSense Firewall",
                  "type": "UGW",
                  "chipset": "INTEL",
                  "sysid": "0xf902",
                  "port_num": 4,
                  "max_speed_mbps": 10000,
                  "features": ["GATEWAY", "HAS_CLIENTS"]
                }
              ]
            }
            """);

        String internalName = MockDeviceModel.class.getName().replace('.', '/');
        byte[] origBytes = readClassBytes(MockDeviceModel.class);
        CustomModelEnumTransformer transformer = new CustomModelEnumTransformer("dev/brait/openuos/agent/", configs);
        byte[] transformed = transformer.transformBytes(internalName, origBytes);
        assertNotNull(transformed, "Transformer must rewrite MockDeviceModel enum");

        Class<? extends Enum> enumCls = (Class<? extends Enum>) loadIsolated(MockDeviceModel.class.getName(), transformed);

        Enum<?>[] values = enumCls.getEnumConstants();
        assertEquals(4, values.length);
        assertEquals("US24P250", values[0].name());
        assertEquals("UNKNOWN", values[1].name());
        assertEquals("RTL_LG510S", values[2].name());
        assertEquals("PFSENSE_GW", values[3].name());

        // Enum.valueOf, EnumSet.allOf, EnumMap all work natively!
        Enum<?> lg510s = Enum.valueOf(enumCls, "RTL_LG510S");
        assertEquals(2, lg510s.ordinal());
        assertEquals(0xf901, enumCls.getMethod("getSysId").invoke(lg510s));
        assertEquals(10, enumCls.getMethod("getPortNum").invoke(lg510s));
        assertEquals(MockDeviceType.USW, enumCls.getMethod("getType").invoke(lg510s));
        assertEquals(MockSpeed.TWO_AND_HALF_GIGABIT_ETHERNET, enumCls.getMethod("getMaxSpeed").invoke(lg510s));
        assertTrue(((Set<?>) enumCls.getMethod("getFeatures").invoke(lg510s)).contains(MockFeature.HAS_TEMPERATURE_SENSOR));

        // Static sysId map initialized AFTER $VALUES automatically includes injected models!
        Method bySysId = enumCls.getMethod("bySysId", int.class);
        assertEquals(lg510s, bySysId.invoke(null, 0xf901));
        assertEquals(Enum.valueOf(enumCls, "PFSENSE_GW"), bySysId.invoke(null, 0xf902));

        EnumSet<?> all = EnumSet.allOf(enumCls);
        assertEquals(4, all.size());
        assertTrue(all.contains(lg510s));

        EnumMap map = new EnumMap(enumCls);
        map.put(lg510s, "ok");
        assertEquals("ok", map.get(lg510s));

        // CustomModelRuntime.isCustomModelName check
        CustomModelRuntime.setConfigs(configs);
        assertTrue(CustomModelRuntime.isCustomModelName("RTL_LG510S"));
        assertFalse(CustomModelRuntime.isCustomModelName("US24P250"));
    }

    private static byte[] readClassBytes(Class<?> cls) throws IOException {
        String res = cls.getName().replace('.', '/') + ".class";
        try (InputStream is = cls.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(is, "Missing class resource " + res);
            return is.readAllBytes();
        }
    }

    private static Class<?> loadIsolated(String name, byte[] bytecode) throws ClassNotFoundException {
        ClassLoader cl = new ClassLoader(CustomModelEnumTransformerTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.equals(name)) {
                    Class<?> c = defineClass(n, bytecode, 0, bytecode.length);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
                return super.loadClass(n, resolve);
            }
        };
        return cl.loadClass(name);
    }
}
