package org.embeddedt.blacksmith.impl.transformers;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.lenni0451.classtransform.annotations.CLocalVariable;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.List;

@CTransformer(name = "cpw.mods.modlauncher.ClassTransformer")
public class ClassTransformerAuditor {
    @CShadow
    private static Logger LOGGER;

    @CInject(method = "transform", target = @CTarget(value = "NEW", target = "org/objectweb/asm/tree/ClassNode"))
    public void logTransformers(@CLocalVariable(ordinal = 0) String className, @CLocalVariable(ordinal = 0) EnumMap<ILaunchPluginService.Phase, List<ILaunchPluginService>> transformers) {
        for (var phase : ILaunchPluginService.Phase.values()) {
            var list = transformers.get(phase);
            if (list == null || list.isEmpty()) {
                continue;
            }
            StringBuilder names = new StringBuilder();
            for (var service : list) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(service.name());
            }
            LOGGER.info("Transforming class {} due to transformers [{}]", className, names.toString());
        }
    }
}
