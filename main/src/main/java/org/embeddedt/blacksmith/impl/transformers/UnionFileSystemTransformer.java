package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Arrays;
import java.util.List;

public class UnionFileSystemTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Arrays.asList("cpw/mods/niofs/union/UnionFileSystem");
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for(MethodNode m : data.methods) {
            if(m.name.equals("openFileSystem")) {
                for(AbstractInsnNode i : m.instructions) {
                    if(i.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode invokeNode = (MethodInsnNode)i;
                        if(invokeNode.name.equals("newFileSystem")) {
                            System.out.println("Interning zip file systems");
                            invokeNode.owner = "org/embeddedt/blacksmith/impl/sjh/ZipfsInterner";
                            invokeNode.name = "internFilesystem";
                        }
                    }
                }
            }
        }
    }
}
