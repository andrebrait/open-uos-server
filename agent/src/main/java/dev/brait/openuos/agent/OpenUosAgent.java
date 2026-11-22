package dev.brait.openuos.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Runtime instrumentation entrypoint for UniFi Network Application (ace.jar).
 *
 * Attach via:
 *   JAVA_TOOL_OPTIONS=-javaagent:/var/lib/unifi/open-uos/open-uos-agent.jar
 *
 * Optional custom models file:
 *   /var/lib/unifi/open-uos/custom-models.json (or -Dopenuos.models.file=/path/to/custom-models.json)
 */
public final class OpenUosAgent {
    public static final String DEFAULT_MODELS_PATH = "/var/lib/unifi/open-uos/custom-models.json";

    private OpenUosAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[open-uos-agent] Loaded (args=" + (agentArgs == null ? "" : agentArgs) + ")");

        Path modelsPath = resolveModelsPath(agentArgs);
        try {
            List<CustomModelConfig> models = CustomModelConfig.loadFromFile(modelsPath);
            CustomModelRuntime.setConfigs(models);
            if (!models.isEmpty()) {
                System.out.println("[open-uos-agent] Loaded " + models.size() + " custom model(s) from " + modelsPath + ": "
                    + models.stream().map(m -> m.model() + "(0x" + m.sysIdHex() + ")").toList());
                inst.addTransformer(new CustomModelEnumTransformer(models), false);
            } else {
                System.out.println("[open-uos-agent] No custom models file at " + modelsPath + " (skipping custom model injection)");
            }
        } catch (Exception e) {
            System.err.println("[open-uos-agent] Failed to load custom models from " + modelsPath + ": " + e);
        }

        inst.addTransformer(new SwitchAndUidbTransformer(), false);

        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(ElementMatchers.named("com.ubnt.ace.BootLauncher"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.method(ElementMatchers.named("main"))
                    .intercept(Advice.to(BootLauncherAdvice.class))
            )
            .installOn(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    private static Path resolveModelsPath(String agentArgs) {
        String sysProp = System.getProperty("openuos.models.file");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp.trim());
        }
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String part : agentArgs.split(",")) {
                if (part.startsWith("models=")) {
                    String val = part.substring("models=".length()).trim();
                    if (!val.isEmpty()) {
                        return Path.of(val);
                    }
                }
            }
        }
        return Path.of(DEFAULT_MODELS_PATH);
    }

    public static final class BootLauncherAdvice {
        @Advice.OnMethodEnter
        public static void onEnter() {
            System.out.println("[open-uos-agent] Intercepted com.ubnt.ace.BootLauncher.main()");
        }
    }
}
