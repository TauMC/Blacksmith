package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class BootstrapLauncherTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Arrays.asList("cpw/mods/bootstraplauncher/BootstrapLauncher");
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for (MethodNode method : data.methods) {
            if (method.name.equals("main") && (method.access & Opcodes.ACC_STATIC) != 0) {
                Optional<MethodInsnNode> nameCaptureInLoop = RuntimeTransformer.streamInsnList(method.instructions)
                        .filter(n -> n.getOpcode() == Opcodes.INVOKEINTERFACE)
                        .map(MethodInsnNode.class::cast)
                        .filter(m -> m.name.equals("name") && m.owner.equals("cpw/mods/jarhandling/SecureJar"))
                        .findFirst();
                nameCaptureInLoop.ifPresent(methodInsnNode -> RuntimeTransformer.searchBackward(methodInsnNode, insn -> insn.getOpcode() == Opcodes.ALOAD).ifPresent(aLoad -> {
                    RuntimeTransformer.searchForward(methodInsnNode, insn -> {
                        if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                            MethodInsnNode node = (MethodInsnNode) insn;
                            return node.owner.equals("java/util/ArrayList") && node.name.equals("add");
                        } else {
                            return false;
                        }
                    }).ifPresent(listAdd -> {
                        InsnList epilogueClose = new InsnList();
                        epilogueClose.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) aLoad).var));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "cpw/mods/jarhandling/SecureJar", "getRootPath", "()Ljava/nio/file/Path;", true));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "getFileSystem", "()Ljava/nio/file/FileSystem;", true));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/io/Closeable", "close", "()V", true));
                        method.instructions.insert(listAdd, epilogueClose);
                    });
                }));
            }
        }
    }
}
