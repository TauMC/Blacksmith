package org.embeddedt.blacksmith.impl.hooks;

import org.embeddedt.blacksmith.impl.Agent;
import sun.net.www.protocol.jar.URLJarFile;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class Hooks {
    static final ConcurrentHashMap<JarFile, Manifest> manifestCache = new ConcurrentHashMap<>();
    public static Manifest jarFileGetManifest(JarFile jarFile) throws IOException {
        if(jarFile instanceof URLJarFile) {
            Manifest m = manifestCache.get(jarFile);
            if(m != null)
                return m;
            m = jarFile.getManifest();
            manifestCache.put(jarFile, m);
            return m;
        }
        return jarFile.getManifest();
    }

    public static <T> Stream<T> arrayParallelStream(T[] array) {
        return Arrays.stream(array).parallel();
    }

    public static <T> Stream<T> dummyFilter(Stream<T> stream, Predicate<T> filter) {
        return stream;
    }

    // also update implementation in Hooks17
    public static ExecutorService makeScanningExecutor(ThreadFactory factory) {
        int maxScanThreads = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        System.out.println("Using " + maxScanThreads + " threads for scanner");
        AtomicInteger tCount = new AtomicInteger();
        return Executors.newFixedThreadPool(maxScanThreads, r -> {
            Thread t = factory.newThread(r);
            t.setName("Scan-Handler-" + tCount.getAndIncrement());
            return t;
        });
    }

    public static Stream<Path> getPackagesSkippingAssets(Path basePath, FileVisitOption[] options) throws IOException {
        List<Path> validPaths = new ArrayList<>();
        Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if(file.getFileName().toString().endsWith(".class") && attrs.isRegularFile())
                    validPaths.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                if (path.getNameCount() > 0) {
                    String firstName = path.getName(0).toString();
                    if(firstName.equals("assets") || firstName.equals("data") || firstName.equals("META-INF"))
                        return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return validPaths.stream();
    }

    public static Object getZipFsChannel(java.lang.invoke.MethodHandle mh, FileSystem fs) throws Throwable {
        if (fs.getClass().getName().equals("jdk.nio.zipfs.ZipFileSystem")) {
            return mh.invoke(fs);
        }
        return null;
    }

    public static boolean zipPathExists(java.lang.invoke.MethodHandle mh, Path path) throws Throwable {
        if (path.getClass().getName().equals("jdk.nio.zipfs.ZipPath")) {
            return (boolean) mh.invoke(path);
        }
        return Files.exists(path);
    }

    public static ClassLoader getClassLoaderJ9(Class<?> clz) {
        ClassLoader oldCl = clz.getClassLoader();
        URLClassLoader urlCl;
        if(oldCl instanceof URLClassLoader)
            urlCl = (URLClassLoader)oldCl;
        else {
            // spawn fake URLClassLoader to grab the classpath from
            // code based off lwjgl3ify patch
            final String cpString = System.getProperty("java.class.path");
            final String[] cpEntries = cpString.split(File.pathSeparator);
            URL[] classpath = new URL[cpEntries.length + 1];
            for (int i = 0; i < cpEntries.length; i++) {
                try {
                    classpath[i] = new File(cpEntries[i]).toURI().toURL();
                } catch (MalformedURLException ex) {
                    throw new RuntimeException(ex);
                }
            }
            classpath[classpath.length - 1] = Hooks.class.getProtectionDomain().getCodeSource().getLocation();
            urlCl = new URLClassLoader(classpath);
        }
        return urlCl;
    }
}
