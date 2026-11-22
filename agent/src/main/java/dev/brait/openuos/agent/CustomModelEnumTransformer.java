package dev.brait.openuos.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Injects external custom device models directly into UniFi's DeviceModel enum bytecode
 * before {@code <clinit>} runs, so {@code values()}, {@code valueOf()}, {@code EnumSet},
 * {@code EnumMap}, {@code sysId} maps, and {@code productInfoTableGenerator} all recognize
 * custom models natively without obfuscated-name hardcoding.
 */
final class CustomModelEnumTransformer implements ClassFileTransformer {
    private static final int ASM_API = Opcodes.ASM9;
    private static final String RUNTIME_OWNER = Type.getInternalName(CustomModelRuntime.class);
    private static final Pattern SET_GENERIC_PATTERN = Pattern.compile("Ljava/util/Set<L([^;]+);>;");

    private final String packagePrefix;
    private final List<CustomModelConfig> models;

    CustomModelEnumTransformer(List<CustomModelConfig> models) {
        this("com/ubnt/k/g/a/", models);
    }

    CustomModelEnumTransformer(String packagePrefix, List<CustomModelConfig> models) {
        this.packagePrefix = packagePrefix;
        this.models = List.copyOf(models);
    }

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        if (className == null || models.isEmpty() || !className.startsWith(packagePrefix)) {
            return null;
        }
        return transformBytes(className, classfileBuffer);
    }

    byte[] transformBytes(String className, byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ModelEnumAnalysis info = analyze(cr);
        if (!info.isDeviceModelEnum()) {
            return null;
        }

        List<CustomModelConfig> toInject = new ArrayList<>();
        for (CustomModelConfig cfg : models) {
            if (!info.existingEnumFields.contains(cfg.model())) {
                toInject.add(cfg);
            }
        }
        if (toInject.isEmpty()) {
            return null;
        }

        System.out.println(
            "[open-uos-agent] Injecting " + toInject.size() + " custom model(s) into "
                + className.replace('/', '.') + " (existing=" + info.existingEnumFields.size() + ")"
        );

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public void visitEnd() {
                String selfDesc = "L" + className + ";";
                for (CustomModelConfig cfg : toInject) {
                    FieldVisitor fv = super.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                        cfg.model(),
                        selfDesc,
                        null,
                        null
                    );
                    if (fv != null) {
                        fv.visitEnd();
                    }
                }
                super.visitEnd();
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<clinit>".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(ASM_API, mv) {
                    private boolean injected;

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        if (!injected && opcode == Opcodes.PUTSTATIC && owner.equals(className) && "$VALUES".equals(fname)) {
                            injected = true;
                            emitCustomEnumInit(mv, className, info, toInject);
                        }
                        super.visitFieldInsn(opcode, owner, fname, fdesc);
                    }
                };
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static void emitCustomEnumInit(
        MethodVisitor mv,
        String className,
        ModelEnumAnalysis info,
        List<CustomModelConfig> toInject
    ) {
        String selfDesc = "L" + className + ";";
        String arrDesc = "[" + selfDesc;

        // Top of stack: original DeviceModel[] from $values()
        pushInt(mv, toInject.size());
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RUNTIME_OWNER,
            "growValuesArray",
            "([Ljava/lang/Object;I)[Ljava/lang/Object;",
            false
        );
        mv.visitTypeInsn(Opcodes.CHECKCAST, arrDesc);

        int baseOrdinal = info.existingEnumFields.size();
        for (int i = 0; i < toInject.size(); i++) {
            CustomModelConfig cfg = toInject.get(i);
            int ordinal = baseOrdinal + i;

            mv.visitInsn(Opcodes.DUP); // newValuesArr
            pushInt(mv, ordinal);

            mv.visitTypeInsn(Opcodes.NEW, className);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(cfg.model());
            pushInt(mv, ordinal);

            // EHquh type
            mv.visitLdcInsn(Type.getObjectType(info.deviceTypeClass));
            mv.visitLdcInsn(cfg.type());
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_OWNER,
                "resolveEnum",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;",
                false
            );
            mv.visitTypeInsn(Opcodes.CHECKCAST, info.deviceTypeClass);

            // Set<Feature>
            mv.visitLdcInsn(Type.getObjectType(info.featureClass));
            mv.visitLdcInsn(cfg.featuresCsv());
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_OWNER,
                "buildFeatureSet",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/util/Set;",
                false
            );

            // Chipset
            mv.visitLdcInsn(Type.getObjectType(info.chipsetClass));
            mv.visitLdcInsn(cfg.chipset());
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_OWNER,
                "resolveEnum",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;",
                false
            );
            mv.visitTypeInsn(Opcodes.CHECKCAST, info.chipsetClass);

            // sysId, portNum, supportsAnalyticsToggle=true
            pushInt(mv, cfg.sysId());
            pushInt(mv, cfg.portNum());
            mv.visitInsn(Opcodes.ICONST_1);

            // EthernetMaxSpeed
            mv.visitLdcInsn(Type.getObjectType(info.speedClass));
            mv.visitLdcInsn(cfg.maxSpeedEnumName());
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_OWNER,
                "resolveEnum",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;",
                false
            );
            mv.visitTypeInsn(Opcodes.CHECKCAST, info.speedClass);

            // Collections.emptyMap() for radios
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyMap",
                "()Ljava/util/Map;",
                false
            );

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "<init>", info.ctorDescriptor, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, className, cfg.model(), selfDesc);
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }

    private static ModelEnumAnalysis analyze(ClassReader cr) {
        ModelEnumAnalysis a = new ModelEnumAnalysis();
        cr.accept(new ClassVisitor(ASM_API) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                a.isEnum = (access & Opcodes.ACC_ENUM) != 0;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if ((access & Opcodes.ACC_ENUM) != 0) {
                    a.existingEnumFields.add(name);
                }
                if (a.featureClass == null && "Ljava/util/Set;".equals(descriptor) && signature != null) {
                    Matcher m = SET_GENERIC_PATTERN.matcher(signature);
                    if (m.find()) {
                        String candidate = m.group(1);
                        if (!candidate.equals(cr.getClassName())) {
                            a.featureClass = candidate;
                        }
                    }
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ("<init>".equals(name)) {
                    Type[] args = Type.getArgumentTypes(descriptor);
                    // (String, int, DeviceType, Set, Chipset, int, int, boolean, Speed, Map)
                    if (args.length == 10
                        && args[0].getDescriptor().equals("Ljava/lang/String;")
                        && args[1].getSort() == Type.INT
                        && args[2].getSort() == Type.OBJECT
                        && args[3].getDescriptor().equals("Ljava/util/Set;")
                        && args[4].getSort() == Type.OBJECT
                        && args[5].getSort() == Type.INT
                        && args[6].getSort() == Type.INT
                        && args[7].getSort() == Type.BOOLEAN
                        && args[8].getSort() == Type.OBJECT
                        && args[9].getDescriptor().equals("Ljava/util/Map;")) {
                        a.ctorDescriptor = descriptor;
                        a.deviceTypeClass = args[2].getInternalName();
                        a.chipsetClass = args[4].getInternalName();
                        a.speedClass = args[8].getInternalName();
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return a;
    }

    private static final class ModelEnumAnalysis {
        boolean isEnum;
        final Set<String> existingEnumFields = new HashSet<>();
        String ctorDescriptor;
        String deviceTypeClass;
        String featureClass;
        String chipsetClass;
        String speedClass;

        boolean isDeviceModelEnum() {
            return isEnum
                && existingEnumFields.contains("US24P250")
                && existingEnumFields.contains("UNKNOWN")
                && ctorDescriptor != null
                && deviceTypeClass != null
                && featureClass != null
                && chipsetClass != null
                && speedClass != null;
        }
    }
}
