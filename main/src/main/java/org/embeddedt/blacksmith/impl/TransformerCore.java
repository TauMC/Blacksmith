package org.embeddedt.blacksmith.impl;

import org.embeddedt.blacksmith.impl.transformers.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarFile;

public class TransformerCore {
    private static final List<RuntimeTransformer> TRANSFORMERS = new ArrayList<>();

    private static final boolean DEBUG = Boolean.getBoolean("blacksmith.debug");

    public static boolean MODERN_FML;

    static {
        TRANSFORMERS.add(new FMLScannerTransformer());
        TRANSFORMERS.add(new SJHJarTransformer());
        TRANSFORMERS.add(new AbstractModProviderTransformer());
        TRANSFORMERS.add(new LaunchClassLoaderTransformer());
        TRANSFORMERS.add(new LaunchTransformer());
        TRANSFORMERS.add(new FinalFieldHelperTransformer());
        TRANSFORMERS.add(new ModuleLayerHandlerTransformer());
        TRANSFORMERS.add(new FileWatcherTransformer());
        TRANSFORMERS.add(new BackgroundScanHandlerTransformer());
        TRANSFORMERS.add(new TransformerDiscovererConstantsTransformer());
        TRANSFORMERS.add(new ModDirTransformerDiscovererTransformer());
        TRANSFORMERS.add(new BootstrapLauncherTransformer());
    }

    public static void log(String s) {
        System.out.printf("[%1$tT] [%2$s/INFO] [Blacksmith]: %3$s %n",
                new Date(),
                Thread.currentThread().getName(),
                s
        );
    }

    /* borrowed from Commons */
    static long copyLarge(final InputStream inputStream, final OutputStream outputStream)
            throws IOException {
        byte[] buffer = new byte[1024];
        long count = 0;
        int n;
        while (-1 != (n = inputStream.read(buffer))) {
            outputStream.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    static void loadJ17Jar(Instrumentation instrumentation) {
        try(InputStream j17Jar = TransformerCore.class.getResourceAsStream("/java17.jar")) {
            if(j17Jar == null) {
                System.out.println("UNABLE TO LOAD JAVA 17 MODULE!!!");
                return;
            }
            final File tempFile = File.createTempFile("blacksmith", ".jar");
            tempFile.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                copyLarge(j17Jar, out);
            }
            JarFile j17File = new JarFile(tempFile);
            instrumentation.appendToBootstrapClassLoaderSearch(j17File);
            System.out.println("Injected Java 17 module " + tempFile);
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void start(Instrumentation instrumentation) {
        log("Loaded on separate classloader");
        instrumentation.addTransformer(new AgentTransformer());
        loadJ17Jar(instrumentation);
    }

    private static class AgentTransformer implements ClassFileTransformer {
        private final HashMap<String, List<RuntimeTransformer>> transformersByClass;

        AgentTransformer() {
            transformersByClass = new HashMap<>();
            for(RuntimeTransformer transformer : TRANSFORMERS) {
                for(String clz : transformer.getTransformedClasses()) {
                    List<RuntimeTransformer> list = transformersByClass.get(clz);
                    if(list == null) {
                        list = new ArrayList<>();
                        transformersByClass.put(clz, list);
                    }
                    list.add(transformer);
                }
            }
        }

        private void dumpDebugClass(String s, byte[] data) {
            Path fp = Paths.get("/tmp/" + s + ".class");
            try {
                Files.createDirectories(fp.getParent());
                try(OutputStream os = Files.newOutputStream(fp)) {
                    os.write(data);
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public byte[] transform(ClassLoader classLoader, String s, Class<?> aClass, ProtectionDomain protectionDomain, byte[] bytes) throws IllegalClassFormatException {
            List<RuntimeTransformer> transformers = transformersByClass.get(s);
            if(transformers == null || transformers.isEmpty())
                return bytes;
            log("transforming " + s);
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassNode node = new ClassNode(Opcodes.ASM9);
                reader.accept(node, 0);
                int flags = 0;
                for(RuntimeTransformer t : transformers) {
                    t.transformClass(node);
                    flags |= t.getWriteFlags();
                }
                ClassWriter writer = new ClassWriter(flags);
                node.accept(writer);
                byte[] data = writer.toByteArray();
                if(DEBUG) dumpDebugClass(s, data);
                return data;
            } catch(Throwable e) {
                e.printStackTrace();
                return bytes;
            }
        }
    }
}
