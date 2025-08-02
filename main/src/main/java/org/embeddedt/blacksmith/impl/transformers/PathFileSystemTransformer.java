package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Arrays;
import java.util.List;

public class PathFileSystemTransformer implements RuntimeTransformer {
    @Override
    public List<String> getTransformedClasses() {
        return Arrays.asList("net/minecraftforge/jarjar/nio/pathfs/PathFileSystem");
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        for(MethodNode m : data.methods) {
            if (m.name.equals("lambda$new$0")) {
                for(AbstractInsnNode i : m.instructions) {
                    if(i.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode invokeNode = (MethodInsnNode)i;
                        if(invokeNode.name.equals("newFileSystem")) {
                            System.out.println("Interning zip file systems (pathfs)");
                            invokeNode.owner = "org/embeddedt/blacksmith/impl/sjh/ZipfsInterner";
                            invokeNode.name = "internFilesystem";
                        }
                    }
                }
            } else if (m.name.equals("close")) {
                for(AbstractInsnNode i : m.instructions) {
                    if(i.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode invokeNode = (MethodInsnNode)i;
                        if(invokeNode.name.equals("ifPresent")) {
                            System.out.println("Deleting invoke call");
                            InsnList l = new InsnList();
                            l.add(new InsnNode(Opcodes.POP));
                            l.add(new InsnNode(Opcodes.POP));
                            m.instructions.insert(invokeNode, l);
                            m.instructions.remove(invokeNode);
                            break;
                        }
                    }
                }
            }
        }
    }
}
