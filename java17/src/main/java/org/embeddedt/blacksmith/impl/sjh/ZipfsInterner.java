package org.embeddedt.blacksmith.impl.sjh;

import org.embeddedt.blacksmith.impl.zipfs.EfficientZipFileSystem;

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

    private static boolean isZipFile(Path path) {
        try (var is = java.nio.file.Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = is.read(magic, n, 4 - n);
                if (r < 0) return false;
                n += r;
            }
            // ZIP local file header magic: PK\x03\x04 (little-endian 0x04034b50)
            return magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        } catch (IOException e) {
            return false;
        }
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
            fs = isZipFile(path) ? new EfficientZipFileSystem(path) : FileSystems.newFileSystem(path);
            CACHE.put(key, new WeakReference<>(fs));
        }
        return fs;
    }
}
