package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
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
public class TransformerDiscovererConstantsTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Arrays.asList("net/neoforged/fml/loading/TransformerDiscovererConstants");
    }

    @Override
    public int getWriteFlags() {
        return ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for (MethodNode method : data.methods) {
            if (method.name.equals("shouldLoadInServiceLayer")) {
                Type desc = Type.getMethodType(method.desc);
                if (desc.getArgumentCount() == 1) {
                    Type firstArg = desc.getArgumentTypes()[0];
                    if (firstArg.getClassName().equals("java.nio.file.Path") || firstArg.getClassName().equals("java.util.Collection")) {
                        // This is our method, we need to capture the JarContents.of call into a local var and restore
                        // it after.
                        Optional<MethodInsnNode> delegateCall = RuntimeTransformer.streamInsnList(method.instructions)
                                .filter(n -> n instanceof MethodInsnNode)
                                .map(MethodInsnNode.class::cast)
                                .filter(m -> m.name.equals("shouldLoadInServiceLayer"))
                                .findFirst();
                        Optional<AbstractInsnNode> returnInstruction = RuntimeTransformer.streamInsnList(method.instructions)
                                .filter(i -> i.getOpcode() == Opcodes.IRETURN).findFirst();
                        if (delegateCall.isPresent() && returnInstruction.isPresent()) {
                            int id = RuntimeTransformer.nextLocalVariableIndex(method);
                            LabelNode startLabel = new LabelNode();
                            LabelNode endLabel = new LabelNode();
                            LocalVariableNode localVar = new LocalVariableNode(
                                    "jarContents",
                                    "Lcpw/mods/jarhandling/JarContents;",
                                    null,
                                    startLabel,
                                    endLabel,
                                    id
                            );
                            method.localVariables.add(localVar);
                            InsnList prologueSave = new InsnList();
                            prologueSave.add(startLabel);
                            prologueSave.add(new InsnNode(Opcodes.DUP));
                            prologueSave.add(new VarInsnNode(Opcodes.ASTORE, id));
                            method.instructions.insertBefore(delegateCall.get(), prologueSave);
                            InsnList epilogueClose = new InsnList();
                            epilogueClose.add(new VarInsnNode(Opcodes.ALOAD, id));
                            epilogueClose.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/io/Closeable", "close", "()V", true));
                            epilogueClose.add(endLabel);
                            method.instructions.insertBefore(returnInstruction.get(), epilogueClose);
                            System.out.println("Patched shouldLoadInServiceLayer" + method.desc + " to close JarContents");
                        }
                    }
                }
            }
        }
    }
}
