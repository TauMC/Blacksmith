package org.embeddedt.blacksmith.impl.transformers;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class ClassTransformerAuditorTest {
    @Test
    void testTransform() throws IOException {
        TransformerManager manager = new TransformerManager(new BasicClassProvider());
        manager.addTransformer("org.embeddedt.blacksmith.impl.transformers.ClassTransformerAuditor");
        try (var is = ClassTransformerAuditorTest.class.getClassLoader().getResourceAsStream("cpw/mods/modlauncher/ClassTransformer.class")) {
            manager.transform("cpw.mods.modlauncher.ClassTransformer", is.readAllBytes());
        }
    }
}
