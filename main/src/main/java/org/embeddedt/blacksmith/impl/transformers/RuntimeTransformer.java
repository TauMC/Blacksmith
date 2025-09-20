package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface RuntimeTransformer {
    String HOOK_CLASS = "org/embeddedt/blacksmith/impl/hooks/Hooks";
    String HOOK17_CLASS = "org/embeddedt/blacksmith/impl/Hooks17";
    List<String> getTransformedClasses();

    @Deprecated
    void transformClass(ClassNode data) throws IllegalClassFormatException;

    default ClassNode replaceClass(ClassNode data) throws IllegalClassFormatException {
        transformClass(data);
        return data;
    }

    default int getWriteFlags() { return 0; }

    static MethodInsnNode redirectToStaticHook(String hookName, String hookDesc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_CLASS, hookName, hookDesc, false);
    }

    static <T extends AbstractInsnNode> T swapInstruction(InsnList list, AbstractInsnNode oldInsn, T newInsn) {
        list.set(oldInsn, newInsn);
        return newInsn;
    }

    static int nextLocalVariableIndex(MethodNode method) {
        int maxIndex = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;

        for (LocalVariableNode var : method.localVariables) {
            int varSize = Type.getType(var.desc).getSize();
            int endIndex = var.index + varSize;
            if (endIndex > maxIndex) {
                maxIndex = endIndex;
            }
        }

        return maxIndex;
    }

    static Stream<AbstractInsnNode> streamInsnList(InsnList list) {
        return StreamSupport.stream(list.spliterator(), false);
    }

    static Optional<AbstractInsnNode> search(AbstractInsnNode current, Function<AbstractInsnNode, AbstractInsnNode> advancer, Predicate<AbstractInsnNode> filter) {
        AbstractInsnNode next = advancer.apply(current);
        while (next != null) {
            if (filter.test(next)) {
                return Optional.of(next);
            }
            next = advancer.apply(next);
        }
        return Optional.empty();
    }

    static Optional<AbstractInsnNode> searchForward(AbstractInsnNode current, Predicate<AbstractInsnNode> filter) {
        return search(current, AbstractInsnNode::getNext, filter);
    }

    static Optional<AbstractInsnNode> searchBackward(AbstractInsnNode current, Predicate<AbstractInsnNode> filter) {
        return search(current, AbstractInsnNode::getPrevious, filter);
    }
}
