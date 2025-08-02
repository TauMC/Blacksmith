package org.embeddedt.blacksmith.impl.sjh;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * This is risky. It assumes callers never try to close the zipfs themselves. Fortunately that seems to be the case
 * in Forge 20.1 at least.
 */
public class ZipfsInterner {
    private static final HashMap<URI, WeakReference<FileSystem>> CACHE = new HashMap<>();

    public static FileSystem internFilesystem(Path path, ClassLoader cl) throws IOException {
        return internFilesystem(path);
    }

    public static synchronized FileSystem internFilesystem(Path path) throws IOException {
        if (path.getClass().getName().equals("net.minecraftforge.jarjar.nio.pathfs.PathPath") && path.getNameCount() == 1 && path.getName(0).toString().isEmpty()) {
            var fs = path.getFileSystem();
            try {
                path = (Path)fs.getClass().getMethod("getTarget").invoke(fs);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Failed to find target", e);
            }
        }
        var key = path.toUri();
        var ref = CACHE.get(key);
        var fs = ref != null ? ref.get() : null;
        if (fs == null) {
            fs = FileSystems.newFileSystem(path);
            CACHE.put(key, new WeakReference<>(fs));
        }
        return fs;
    }
}
