package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * @author embeddedt (credit to Uncandango for finding the bug in FML: https://github.com/neoforged/FancyModLoader/issues/289)
 */
public class ModDirTransformerDiscovererTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Arrays.asList("net/minecraftforge/fml/loading/ModDirTransformerDiscoverer");
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for (MethodNode method : data.methods) {
            if (method.name.equals("visitFile")) {
                Optional<LocalVariableNode> secureJar = method.localVariables.stream().filter(l -> l.desc.equals("Lcpw/mods/jarhandling/SecureJar;")).findFirst();
                if (secureJar.isPresent()) {
                    int lvIndex = method.instructions.indexOf(secureJar.get().start);
                    Optional<AbstractInsnNode> returnInsn = RuntimeTransformer.streamInsnList(method.instructions).filter(i -> i.getOpcode() == Opcodes.RETURN && method.instructions.indexOf(i) > lvIndex).findFirst();
                    if (returnInsn.isPresent()) {
                        InsnList epilogueClose = new InsnList();
                        epilogueClose.add(new VarInsnNode(Opcodes.ALOAD, secureJar.get().index));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "cpw/mods/jarhandling/SecureJar", "getRootPath", "()Ljava/nio/file/Path;", true));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "getFileSystem", "()Ljava/nio/file/FileSystem;", true));
                        epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/io/Closeable", "close", "()V", true));
                        method.instructions.insertBefore(returnInsn.get(), epilogueClose);
                    }
                }
            }
        }
    }
}
