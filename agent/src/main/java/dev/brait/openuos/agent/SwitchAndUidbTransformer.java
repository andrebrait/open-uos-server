package dev.brait.openuos.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Obfuscation-resilient ASM transformer implementing:
 *  1. Copper-port defaults for EEE and Flow Control (Pause), while never enabling either on SFP ports.
 *  2. Custom model UIDB record injection and firmware-upgrade suppression.
 *
 * All targets are identified by unobfuscated method names, parameter shapes, and literal string constants,
 * with zero branch insertion (COMPUTE_MAXS safe).
 */
final class SwitchAndUidbTransformer implements ClassFileTransformer {
    private static final int ASM_API = Opcodes.ASM9;
    private static final String PORT_HELPER = Type.getInternalName(PortMediaHelper.class);
    private static final String MODEL_RUNTIME = Type.getInternalName(CustomModelRuntime.class);

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        if (className == null || !className.startsWith("com/ubnt/")) {
            return null;
        }
        return transformBytes(className, classfileBuffer, loader);
    }

    byte[] transformBytes(String className, byte[] classfileBuffer) {
        return transformBytes(className, classfileBuffer, getClass().getClassLoader());
    }

    byte[] transformBytes(String className, byte[] classfileBuffer, ClassLoader loader) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassScan scan = scanClass(className, cr);

        if (scan.isDeviceClass()) {
            return patchDeviceClass(className, cr);
        }
        if (scan.isGlobalSwitchSettingService()) {
            return patchGlobalSwitchSettingService(className, cr);
        }
        if (scan.isGlobalSwitchDefaultBuilder()) {
            return patchGlobalSwitchDefaultBuilder(className, cr);
        }
        if (scan.isUswSwitchConfigGenerator()) {
            return patchUswSwitchConfigGenerator(className, cr, loader);
        }
        if (scan.isLegacySwitchFlowCtrlEmitter()) {
            return patchLegacySwitchFlowCtrlEmitter(className, cr, loader);
        }
        if (scan.isPortConfigDiffDetector()) {
            return patchPortConfigDiffDetector(className, cr);
        }
        if (scan.isUiDbUtils()) {
            return patchUiDbUtils(className, cr);
        }
        return null;
    }

    private static byte[] patchDeviceClass(String className, ClassReader cr) {
        System.out.println("[open-uos-agent] Patching Device class: " + className.replace('/', '.'));
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if ("hasPortEeeCapability".equals(name) && "(I)Z".equals(desc)) {
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitVarInsn(Opcodes.ILOAD, 1);
                                mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    PORT_HELPER,
                                    "filterEeeCapability",
                                    "(ZLjava/lang/Object;I)Z",
                                    false
                                );
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if ("isFlowControlEnabled".equals(name) && "()Z".equals(desc)) {
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ICONST_0) {
                                super.visitInsn(Opcodes.ICONST_1);
                                return;
                            }
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                            if (opcode == Opcodes.GETSTATIC && "java/lang/Boolean".equals(owner) && "FALSE".equals(fname)) {
                                super.visitFieldInsn(opcode, owner, "TRUE", fdesc);
                                return;
                            }
                            super.visitFieldInsn(opcode, owner, fname, fdesc);
                        }
                    };
                }
                if (("setUpgradable".equals(name) || "setUpgradableOnAdoption".equals(name)) && desc.startsWith("(Z)")) {
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ILOAD, 1);
                            mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                MODEL_RUNTIME,
                                "filterUpgradable",
                                "(Ljava/lang/Object;Z)Z",
                                false
                            );
                            mv.visitVarInsn(Opcodes.ISTORE, 1);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    private static byte[] patchGlobalSwitchSettingService(String className, ClassReader cr) {
        System.out.println("[open-uos-agent] Patching GlobalSwitchSettingService (flowctrl default=true): " + className.replace('/', '.'));
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"(Ljava/lang/String;)Z".equals(desc)) {
                    return mv;
                }
                return new MethodVisitor(ASM_API, mv) {
                    private boolean sawFlowCtrlKey;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if ("flowctrl_enabled".equals(value)) {
                            sawFlowCtrlKey = true;
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (sawFlowCtrlKey && opcode == Opcodes.ICONST_0) {
                            sawFlowCtrlKey = false;
                            super.visitInsn(Opcodes.ICONST_1);
                            return;
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        if (sawFlowCtrlKey && opcode == Opcodes.GETSTATIC && "java/lang/Boolean".equals(owner) && "FALSE".equals(fname)) {
                            sawFlowCtrlKey = false;
                            super.visitFieldInsn(opcode, owner, "TRUE", fdesc);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, fname, fdesc);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static byte[] patchGlobalSwitchDefaultBuilder(String className, ClassReader cr) {
        System.out.println("[open-uos-agent] Patching GlobalSwitchDefaultBuilder (flowctrl default=true): " + className.replace('/', '.'));
        return flipBooleanLiteralAfterStringKey(cr, "flowctrl_enabled");
    }

    private static byte[] patchUswSwitchConfigGenerator(String className, ClassReader cr, ClassLoader loader) {
        System.out.println("[open-uos-agent] Patching USW switch config generator (copper EEE+Pause default, never SFP): " + className.replace('/', '.'));
        ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                Type[] args = Type.getArgumentTypes(desc);
                Type ret = Type.getReturnType(desc);

                // Flow control emitter: (StringBuilder, int idx, DbObj port, Device originalDevice, StackingCtx)V
                if (ret == Type.VOID_TYPE && args.length == 5
                    && "Ljava/lang/StringBuilder;".equals(args[0].getDescriptor())
                    && args[1] == Type.INT_TYPE) {
                    return new MethodVisitor(ASM_API, mv) {
                        private boolean sawFlowCtrlPortKey;

                        @Override
                        public void visitLdcInsn(Object value) {
                            if ("flow_control_enabled".equals(value)) {
                                sawFlowCtrlPortKey = true;
                            }
                            super.visitLdcInsn(value);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean isInterface) {
                            super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                            if (sawFlowCtrlPortKey && "is".equals(mname) && "(Ljava/lang/String;Z)Z".equals(mdesc)) {
                                sawFlowCtrlPortKey = false;
                                mv.visitVarInsn(Opcodes.ALOAD, 4);
                                mv.visitVarInsn(Opcodes.ILOAD, 2);
                                mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    PORT_HELPER,
                                    "allowFlowControl",
                                    "(ZLjava/lang/Object;I)Z",
                                    false
                                );
                            }
                        }
                    };
                }

                // EEE emitter: (StringBuilder, int idx, DbObj port, Device device)V
                if (ret == Type.VOID_TYPE && args.length == 4
                    && "Ljava/lang/StringBuilder;".equals(args[0].getDescriptor())
                    && args[1] == Type.INT_TYPE) {
                    return new MethodVisitor(ASM_API, mv) {
                        private boolean afterEeeKey;

                        @Override
                        public void visitLdcInsn(Object value) {
                            afterEeeKey = "eee_enabled".equals(value);
                            super.visitLdcInsn(value);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (afterEeeKey && opcode == Opcodes.ICONST_0) {
                                afterEeeKey = false;
                                super.visitInsn(Opcodes.ICONST_1);
                                return;
                            }
                            afterEeeKey = false;
                            super.visitInsn(opcode);
                        }
                    };
                }

                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    private static byte[] patchLegacySwitchFlowCtrlEmitter(String className, ClassReader cr, ClassLoader loader) {
        System.out.println("[open-uos-agent] Patching legacy switch flowctrl emitter (copper default, never SFP): " + className.replace('/', '.'));
        ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                Type[] args = Type.getArgumentTypes(desc);
                if (Type.getReturnType(desc) != Type.VOID_TYPE || args.length != 3
                    || !"Ljava/lang/StringBuilder;".equals(args[0].getDescriptor())
                    || args[1] != Type.INT_TYPE) {
                    return mv;
                }
                return new MethodVisitor(ASM_API, mv) {
                    private boolean afterFlowKey;
                    private boolean inFlowCheck;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if ("flowctrl_enabled".equals(value)) {
                            afterFlowKey = true;
                            inFlowCheck = true;
                        } else {
                            afterFlowKey = false;
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (afterFlowKey && opcode == Opcodes.ICONST_0) {
                            afterFlowKey = false;
                            super.visitInsn(Opcodes.ICONST_1);
                            return;
                        }
                        afterFlowKey = false;
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                        if (inFlowCheck && "is".equals(mname) && "(Ljava/lang/String;Z)Z".equals(mdesc)) {
                            inFlowCheck = false;
                            mv.visitVarInsn(Opcodes.ALOAD, 3);
                            mv.visitVarInsn(Opcodes.ILOAD, 2);
                            mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                PORT_HELPER,
                                "allowFlowControl",
                                "(ZLjava/lang/Object;I)Z",
                                false
                            );
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    private static byte[] patchPortConfigDiffDetector(String className, ClassReader cr) {
        System.out.println("[open-uos-agent] Patching port config diff detector (eee_enabled default=true): " + className.replace('/', '.'));
        return flipBooleanLiteralAfterStringKey(cr, "eee_enabled");
    }

    private static byte[] patchUiDbUtils(String className, ClassReader cr) {
        System.out.println("[open-uos-agent] Patching UiDbUtils (custom models UIDB merge): " + className.replace('/', '.'));
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                Type[] args = Type.getArgumentTypes(desc);
                Type ret = Type.getReturnType(desc);
                // Filter method: (Lcom/ubnt/data/h/UiDbSnapshot;)Lcom/ubnt/data/h/UiDbSnapshot;
                if (args.length == 1 && ret.getSort() == Type.OBJECT
                    && args[0].equals(ret)
                    && ret.getInternalName().startsWith("com/ubnt/data/h/")) {
                    String snapInternal = ret.getInternalName();
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MODEL_RUNTIME,
                                    "mergeCustomUidbRecords",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                                );
                                mv.visitTypeInsn(Opcodes.CHECKCAST, snapInternal);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    private static byte[] flipBooleanLiteralAfterStringKey(ClassReader cr, String key) {
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(ASM_API, mv) {
                    private boolean afterKey;

                    @Override
                    public void visitLdcInsn(Object value) {
                        afterKey = key.equals(value);
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (afterKey && opcode == Opcodes.ICONST_0) {
                            afterKey = false;
                            super.visitInsn(Opcodes.ICONST_1);
                            return;
                        }
                        afterKey = false;
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static ClassScan scanClass(String className, ClassReader cr) {
        ClassScan scan = new ClassScan(className);
        cr.accept(new ClassVisitor(ASM_API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                scan.methodNames.add(name);
                Type[] args = Type.getArgumentTypes(desc);
                Type ret = Type.getReturnType(desc);
                boolean isLegacyFlowSig = ret == Type.VOID_TYPE && args.length == 3
                    && "Ljava/lang/StringBuilder;".equals(args[0].getDescriptor())
                    && args[1] == Type.INT_TYPE;
                boolean isPortDiffSig = ret == Type.BOOLEAN_TYPE && args.length == 2
                    && args[0].equals(args[1]) && args[0].getSort() == Type.OBJECT;
                boolean isUiDbFilterSig = args.length == 1 && ret.getSort() == Type.OBJECT
                    && args[0].equals(ret) && ret.getInternalName().startsWith("com/ubnt/data/h/");
                if (isUiDbFilterSig) {
                    scan.hasUiDbSnapshotFilterMethod = true;
                }

                return new MethodVisitor(ASM_API) {
                    private boolean sawLegacyKey;
                    private boolean sawLegacyValue;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String s) {
                            scan.strings.add(s);
                            if (isLegacyFlowSig) {
                                if ("flowctrl_enabled".equals(s)) {
                                    sawLegacyKey = true;
                                } else if ("flowctrl".equals(s)) {
                                    sawLegacyValue = true;
                                }
                                if (sawLegacyKey && sawLegacyValue) {
                                    scan.hasLegacyFlowCtrlMethod = true;
                                }
                            }
                            if (isPortDiffSig && "eee_enabled".equals(s)) {
                                scan.hasPortEeeDiffMethod = true;
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static final class ClassScan {
        final String className;
        final Set<String> methodNames = new HashSet<>();
        final Set<String> strings = new HashSet<>();
        boolean hasLegacyFlowCtrlMethod;
        boolean hasPortEeeDiffMethod;
        boolean hasUiDbSnapshotFilterMethod;

        ClassScan(String className) {
            this.className = className;
        }

        boolean isDeviceClass() {
            return methodNames.contains("hasPortEeeCapability")
                && methodNames.contains("isFlowControlEnabled")
                && methodNames.contains("setUpgradable");
        }

        boolean isGlobalSwitchSettingService() {
            return className.startsWith("com/ubnt/service/")
                && !className.startsWith("com/ubnt/service/devmgr/")
                && !className.startsWith("com/ubnt/service/aw/")
                && !className.startsWith("com/ubnt/service/ba/")
                && strings.contains("flowctrl_enabled")
                && strings.contains("jumboframe_enabled")
                && strings.contains("dot1x_portctrl_enabled")
                && strings.contains("dot1x_fallback_networkconf_id");
        }

        boolean isGlobalSwitchDefaultBuilder() {
            return className.startsWith("com/ubnt/service/aw/")
                && strings.contains("flowctrl_enabled")
                && strings.contains("jumboframe_enabled")
                && strings.contains("dhcp_snoop")
                && strings.contains("stp_version")
                && strings.contains("acl_l3_isolation");
        }

        boolean isUswSwitchConfigGenerator() {
            return className.startsWith("com/ubnt/service/config/")
                && strings.contains("flow_control_enabled")
                && strings.contains("eee_enabled")
                && strings.contains("flowctrl")
                && strings.contains("eee");
        }

        boolean isLegacySwitchFlowCtrlEmitter() {
            return className.startsWith("com/ubnt/service/config/")
                && hasLegacyFlowCtrlMethod
                && !isUswSwitchConfigGenerator();
        }

        boolean isPortConfigDiffDetector() {
            return className.startsWith("com/ubnt/service/devmgr/")
                && hasPortEeeDiffMethod
                && strings.contains("flow_control_enabled")
                && strings.contains("eee_enabled");
        }

        boolean isUiDbUtils() {
            return className.startsWith("com/ubnt/service/")
                && hasUiDbSnapshotFilterMethod
                && strings.contains("[UIDB] skip fetch content of url [{}] due to same eTag [{}]");
        }
    }

    /**
     * ClassWriter that uses the target ClassLoader for frame computation without crashing on
     * not-yet-loaded obfuscated classes (falls back cleanly to java/lang/Object).
     */
    private static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(ClassReader cr, int flags, ClassLoader loader) {
            super(cr, flags);
            this.loader = loader;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader != null ? loader : super.getClassLoader();
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable ignored) {
                return "java/lang/Object";
            }
        }
    }
}
