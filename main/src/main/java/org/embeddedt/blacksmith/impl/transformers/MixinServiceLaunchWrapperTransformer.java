package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Collections;
import java.util.List;

/**
 * Quick hack to work around https://bugs.openjdk.org/browse/JDK-8350481 causing Mixin to crash at startup.
 * The strategy is to make MixinServiceLaunchWrapper an empty class to allow the buggy ServiceLoader implementation
 * to catch an error properly rather than propagating it up to Mixin. The acrobatics are unavoidable as we cannot
 * transform ServiceLoader from this agent to fix the bug itself (at least it did not work when I tried).
 */
public class MixinServiceLaunchWrapperTransformer implements RuntimeTransformer {
    private static final String NAME = "org/spongepowered/asm/service/mojang/MixinServiceLaunchWrapper";

    public static boolean isNeeded() {
        String version = System.getProperty("java.version"); // e.g., "24" or "24.0.1"
        String majorStr = version.split("\\.")[0];
        int major;
        try {
            major = Integer.parseInt(majorStr);
        } catch (NumberFormatException e) {
            return false;
        }
        return major >= 24 && System.getProperty("java.class.path").contains("cpw/mods/modlauncher");
    }

    @Override
    public List<String> getTransformedClasses() {
        if (MixinServiceLaunchWrapperTransformer.isNeeded()) {
            System.out.println("Enabled Java 24+ ServiceLoader patch");
            return Collections.singletonList(NAME);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassNode replaceClass(ClassNode data) {
        System.out.println(System.getProperties());
        ClassNode cn = new ClassNode();

        cn.version = Opcodes.V1_8; // or Opcodes.V1_8 for Java 8
        cn.access = Opcodes.ACC_PUBLIC;
        cn.name = NAME;
        cn.superName = "java/lang/Object"; // empty class extends Object
        cn.interfaces = new java.util.ArrayList<>();

        return cn;
    }
}
