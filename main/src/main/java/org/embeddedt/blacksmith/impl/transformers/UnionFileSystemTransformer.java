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
                    } else if(i.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode invokeNode = (MethodInsnNode)i;
                        // Match ZIPFS_CH.invoke(zfs) specifically — the polymorphic descriptor
                        // takes a FileSystem argument, distinguishing it from FCI_UNINTERUPTIBLE.invoke(fci)
                        if(invokeNode.owner.equals("java/lang/invoke/MethodHandle")
                                && invokeNode.name.equals("invoke")
                                && invokeNode.desc.contains("FileSystem")) {
                            System.out.println("Replacing ZIPFS_CH.invoke with safe hook");
                            invokeNode.setOpcode(Opcodes.INVOKESTATIC);
                            invokeNode.owner = "org/embeddedt/blacksmith/impl/hooks/Hooks";
                            invokeNode.name = "getZipFsChannel";
                            invokeNode.desc = "(Ljava/lang/invoke/MethodHandle;Ljava/nio/file/FileSystem;)Ljava/lang/Object;";
                        }
                    }
                }
            } else if(m.name.equals("zipFsExists")) {
                for(AbstractInsnNode i : m.instructions) {
                    if(i.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode invokeNode = (MethodInsnNode)i;
                        if(invokeNode.owner.equals("java/lang/invoke/MethodHandle")
                                && invokeNode.name.equals("invoke")) {
                            System.out.println("Replacing ZIPFS_EXISTS.invoke with safe hook");
                            invokeNode.setOpcode(Opcodes.INVOKESTATIC);
                            invokeNode.owner = "org/embeddedt/blacksmith/impl/hooks/Hooks";
                            invokeNode.name = "zipPathExists";
                            invokeNode.desc = "(Ljava/lang/invoke/MethodHandle;Ljava/nio/file/Path;)Z";
                        }
                    }
                }
            }
        }
    }
}
