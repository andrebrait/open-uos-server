package dev.brait.openuos.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SwitchAndUidbTransformerTest {

    public enum MockCabling {
        COPPER("copper"),
        FIBER("fiber");
        final String label;
        MockCabling(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum MockMedia {
        GE("GE", MockCabling.COPPER),
        TWO_P_FIVE_GE("2.5GE", MockCabling.COPPER),
        SFP("SFP", MockCabling.FIBER),
        SFP_PLUS("SFP+", MockCabling.FIBER);
        final String name;
        final MockCabling cabling;
        MockMedia(String name, MockCabling cabling) { this.name = name; this.cabling = cabling; }
        public MockCabling getCabling() { return cabling; }
        @Override public String toString() { return name; }
    }

    public enum MockModel { US24P250, RTL_LG510S }

    public static class MockDbObj extends HashMap<String, Object> {
        public boolean is(String key, boolean defaultVal) {
            Object v = get(key);
            return v instanceof Boolean b ? b : defaultVal;
        }
        public Optional<Boolean> getOptionalBoolean(String key) {
            Object v = get(key);
            return v instanceof Boolean b ? Optional.of(b) : Optional.empty();
        }
    }

    /** Mirrors com.ubnt.data.cgtQt (Device) methods patched by SwitchAndUidbTransformer. */
    public static class MockDevice extends MockDbObj {
        private final MockModel model;
        private final Map<Integer, MockMedia> portMedia;
        public boolean upgradable;
        public boolean upgradableOnAdoption;

        public MockDevice(MockModel model, Map<Integer, MockMedia> portMedia) {
            this.model = model;
            this.portMedia = portMedia;
        }

        public MockModel getModel() { return model; }

        public Optional<MockMedia> getPortMediaTypeOnIdx(int portIdx) {
            return Optional.ofNullable(portMedia.get(portIdx));
        }

        public boolean hasPortEeeCapability(int portIdx) {
            return portMedia.containsKey(portIdx);
        }

        public boolean isFlowControlEnabled() {
            return getOptionalBoolean("flowctrl_enabled").orElse(false);
        }

        public MockDevice setUpgradable(boolean upgradable) {
            this.upgradable = upgradable;
            return this;
        }

        public MockDevice setUpgradableOnAdoption(boolean upgradable) {
            this.upgradableOnAdoption = upgradable;
            return this;
        }
    }

    /** Mirrors com.ubnt.service.config.rywXKdLGZvAktNSA (USW switch config generator). */
    public static class MockUswConfigGen {
        private static void appendProp(StringBuilder sb, String prefix, String key, String val) {
            sb.append(prefix).append('.').append(key).append('=').append(val).append('\n');
        }

        public void emitFlowControl(StringBuilder sb, int idx, MockDbObj port, Object originalDevice, Object stackingCtx) {
            if (port.is("flow_control_enabled", true)) {
                appendProp(sb, "switch.port." + idx, "flowctrl", "enabled");
            }
        }

        public void emitEee(StringBuilder sb, int idx, MockDbObj port, Object device) throws Exception {
            boolean cap = (boolean) device.getClass().getMethod("hasPortEeeCapability", int.class).invoke(device, idx);
            if (cap) {
                boolean on = port.is("eee_enabled", false);
                appendProp(sb, "switch.port." + idx, "eee", on ? "enabled" : "disabled");
            }
        }
    }

    /** Mirrors com.ubnt.service.config.MJmYip / mvDN (legacy switch flowctrl emitter). */
    public static class MockLegacyFlowGen {
        private static void appendProp(StringBuilder sb, String prefix, String key, String val) {
            sb.append(prefix).append('.').append(key).append('=').append(val).append('\n');
        }

        public void emitFlowControl(StringBuilder sb, int idx, MockDbObj device) {
            if (device.is("flowctrl_enabled", false)) {
                appendProp(sb, "switch.port." + idx, "flowctrl", "enabled");
            }
        }
    }

    @Test
    void copperDefaultsEeeAndPauseOnWhileSfpNeverGetsEitherAndCustomModelNeverUpgrades() throws Exception {
        CustomModelRuntime.setConfigs(CustomModelConfig.parseJson("""
            {"models": [{"model": "RTL_LG510S", "sysid": "f901", "port_num": 10}]}
            """));

        SwitchAndUidbTransformer transformer = new SwitchAndUidbTransformer();

        // Transform MockDevice
        byte[] devBytes = transformer.transformBytes("com/ubnt/data/MockDevice", readClassBytes(MockDevice.class));
        assertNotNull(devBytes);
        Class<?> devCls = loadIsolated(MockDevice.class.getName(), devBytes);

        Map<Integer, MockMedia> ports = Map.of(
            1, MockMedia.GE,
            2, MockMedia.TWO_P_FIVE_GE,
            9, MockMedia.SFP,
            10, MockMedia.SFP_PLUS
        );
        Object customDev = devCls.getConstructor(MockModel.class, Map.class).newInstance(MockModel.RTL_LG510S, ports);
        Object unifiDev = devCls.getConstructor(MockModel.class, Map.class).newInstance(MockModel.US24P250, ports);

        // 1. Device.isFlowControlEnabled() defaults to TRUE when unset, respects explicit false
        assertTrue((boolean) devCls.getMethod("isFlowControlEnabled").invoke(customDev));
        ((Map<String, Object>) customDev).put("flowctrl_enabled", false);
        assertFalse((boolean) devCls.getMethod("isFlowControlEnabled").invoke(customDev));
        ((Map<String, Object>) customDev).remove("flowctrl_enabled");

        // 2. Device.hasPortEeeCapability(): TRUE for copper (1, 2), FALSE for SFP/SFP+ (9, 10)
        assertTrue((boolean) devCls.getMethod("hasPortEeeCapability", int.class).invoke(customDev, 1));
        assertTrue((boolean) devCls.getMethod("hasPortEeeCapability", int.class).invoke(customDev, 2));
        assertFalse((boolean) devCls.getMethod("hasPortEeeCapability", int.class).invoke(customDev, 9));
        assertFalse((boolean) devCls.getMethod("hasPortEeeCapability", int.class).invoke(customDev, 10));

        // 3. Firmware upgrades suppressed for custom model, preserved for standard UniFi model
        devCls.getMethod("setUpgradable", boolean.class).invoke(customDev, true);
        devCls.getMethod("setUpgradableOnAdoption", boolean.class).invoke(customDev, true);
        assertFalse(devCls.getField("upgradable").getBoolean(customDev));
        assertFalse(devCls.getField("upgradableOnAdoption").getBoolean(customDev));

        devCls.getMethod("setUpgradable", boolean.class).invoke(unifiDev, true);
        assertTrue(devCls.getField("upgradable").getBoolean(unifiDev));

        // 4. USW config generator: copper defaults EEE=enabled + flowctrl=enabled; SFP never emits either
        byte[] uswBytes = transformer.transformBytes("com/ubnt/service/config/MockUswConfigGen", readClassBytes(MockUswConfigGen.class));
        assertNotNull(uswBytes);
        Class<?> uswCls = loadIsolated(MockUswConfigGen.class.getName(), uswBytes);
        Object uswGen = uswCls.getConstructor().newInstance();

        StringBuilder sb = new StringBuilder();
        MockDbObj emptyPort = new MockDbObj();
        MockDbObj explicitOffPort = new MockDbObj();
        explicitOffPort.put("eee_enabled", false);
        explicitOffPort.put("flow_control_enabled", false);
        MockDbObj explicitOnPort = new MockDbObj();
        explicitOnPort.put("eee_enabled", true);
        explicitOnPort.put("flow_control_enabled", true);

        // Port 1 (copper, default unset) -> BOTH flowctrl=enabled AND eee=enabled
        uswCls.getMethod("emitFlowControl", StringBuilder.class, int.class, MockDbObj.class, Object.class, Object.class)
            .invoke(uswGen, sb, 1, emptyPort, customDev, new Object());
        uswCls.getMethod("emitEee", StringBuilder.class, int.class, MockDbObj.class, Object.class)
            .invoke(uswGen, sb, 1, emptyPort, customDev);

        // Port 2 (copper, explicitly disabled) -> NO flowctrl, eee=disabled
        uswCls.getMethod("emitFlowControl", StringBuilder.class, int.class, MockDbObj.class, Object.class, Object.class)
            .invoke(uswGen, sb, 2, explicitOffPort, customDev, new Object());
        uswCls.getMethod("emitEee", StringBuilder.class, int.class, MockDbObj.class, Object.class)
            .invoke(uswGen, sb, 2, explicitOffPort, customDev);

        // Port 9 & 10 (SFP / SFP+, even with explicit ON!) -> NEITHER flowctrl NOR eee emitted!
        for (int sfpPort : List.of(9, 10)) {
            uswCls.getMethod("emitFlowControl", StringBuilder.class, int.class, MockDbObj.class, Object.class, Object.class)
                .invoke(uswGen, sb, sfpPort, explicitOnPort, customDev, new Object());
            uswCls.getMethod("emitEee", StringBuilder.class, int.class, MockDbObj.class, Object.class)
                .invoke(uswGen, sb, sfpPort, explicitOnPort, customDev);
        }

        String cfg = sb.toString();
        assertTrue(cfg.contains("switch.port.1.flowctrl=enabled\n"), cfg);
        assertTrue(cfg.contains("switch.port.1.eee=enabled\n"), cfg);
        assertFalse(cfg.contains("switch.port.2.flowctrl=enabled"), cfg);
        assertTrue(cfg.contains("switch.port.2.eee=disabled\n"), cfg);
        assertFalse(cfg.contains("switch.port.9."), cfg);
        assertFalse(cfg.contains("switch.port.10."), cfg);

        // 5. Legacy flowctrl generator: copper defaults ON, SFP blocked
        byte[] legacyBytes = transformer.transformBytes("com/ubnt/service/config/MockLegacyFlowGen", readClassBytes(MockLegacyFlowGen.class));
        assertNotNull(legacyBytes);
        Class<?> legacyCls = loadIsolated(MockLegacyFlowGen.class.getName(), legacyBytes);
        Object legacyGen = legacyCls.getConstructor().newInstance();

        StringBuilder lsb = new StringBuilder();
        legacyCls.getMethod("emitFlowControl", StringBuilder.class, int.class, MockDbObj.class).invoke(legacyGen, lsb, 1, customDev);
        legacyCls.getMethod("emitFlowControl", StringBuilder.class, int.class, MockDbObj.class).invoke(legacyGen, lsb, 9, customDev);
        assertEquals("switch.port.1.flowctrl=enabled\n", lsb.toString());
    }

    private static byte[] readClassBytes(Class<?> cls) throws IOException {
        String res = cls.getName().replace('.', '/') + ".class";
        try (InputStream is = cls.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(is);
            return is.readAllBytes();
        }
    }

    private static Class<?> loadIsolated(String name, byte[] bytecode) throws ClassNotFoundException {
        ClassLoader cl = new ClassLoader(SwitchAndUidbTransformerTest.class.getClassLoader()) {
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
