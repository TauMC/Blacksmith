package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Collections;
import java.util.List;

public class SecureJarVerifierTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Collections.singletonList("cpw/mods/jarhandling/impl/SecureJarVerifier");
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for (MethodNode method : data.methods) {
            if (method.name.equals("getJarVerifier")) {
                // Disable SecureJarVerifier as it wastes memory storing signatures
                method.instructions.clear();
                method.localVariables.clear();
                method.tryCatchBlocks.clear();
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                method.maxStack = 1;
                break;
            }
        }
    }
}
