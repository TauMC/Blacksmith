package org.embeddedt.blacksmith.impl.zipfs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An NIO file system for zips that wraps a Java ZipFile.
 */
public class EfficientZipFileSystem extends FileSystem {

    static final class DirNode {
        Map<String, DirNode> childDirs;

        DirNode() {
            childDirs = new HashMap<>();
        }

        void freeze() {
            childDirs = childDirs.isEmpty() ? Map.of() : Map.copyOf(childDirs);
            for (DirNode child : childDirs.values()) {
                child.freeze();
            }
        }
    }

    private final Path zipPath;
    private final ZipFile zipFile;
    private final Path tempFile; // non-null if we had to copy to a temp file
    private final DirNode root;
    private final EfficientZipFileSystemProvider provider;
    private volatile boolean closed;

    public EfficientZipFileSystem(Path zipPath) throws IOException {
        this.zipPath = zipPath;
        this.provider = EfficientZipFileSystemProvider.instance();
        Path tmp = null;
        ZipFile zf;
        try {
            zf = new ZipFile(zipPath.toFile());
        } catch (UnsupportedOperationException e) {
            // Path's provider doesn't support toFile() (e.g. JarInJar paths).
            // Copy to a temporary file.
            tmp = Files.createTempFile("blacksmith-zipfs-", ".jar");
            Files.copy(zipPath, tmp, StandardCopyOption.REPLACE_EXISTING);
            zf = new ZipFile(tmp.toFile());
        }
        this.zipFile = zf;
        this.tempFile = tmp;
        this.root = buildTree();
    }

    private DirNode buildTree() {
        DirNode treeRoot = new DirNode();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty()) continue;

            // Every path component except the last is an implicit directory.
            // The last component is also a directory if the original entry had a trailing slash.
            // For file entries, we still create all ancestor dirs.
            String[] parts = name.split("/");
            DirNode current = treeRoot;
            int dirDepth = entry.isDirectory() ? parts.length : parts.length - 1;
            for (int i = 0; i < dirDepth; i++) {
                current = current.childDirs.computeIfAbsent(parts[i], k -> new DirNode());
            }
        }
        treeRoot.freeze();
        return treeRoot;
    }

    Path getZipPath() {
        return zipPath;
    }

    ZipFile getZipFile() {
        return zipFile;
    }

    /**
     * Resolve the DirNode for a path. Returns null if the path is not a directory.
     */
    DirNode resolveDir(EfficientZipPath path) {
        String[] components = path.getComponents();
        DirNode current = root;
        for (String component : components) {
            current = current.childDirs.get(component);
            if (current == null) return null;
        }
        return current;
    }

    /**
     * Resolve a path to a ZipEntry. Returns null if not found.
     * Uses the precomputed directory tree first, then ZipFile.getEntry() for files.
     */
    ZipEntry resolve(EfficientZipPath path) {
        String[] components = path.getComponents();
        if (components.length == 0) {
            return new ZipEntry("/");
        }

        var entry = zipFile.getEntry(path.getEntryName());

        if (entry != null) {
            return entry;
        }

        // Check if the path is a known directory
        if (resolveDir(path) != null) {
            String entryName = path.getEntryName() + "/";
            entry = zipFile.getEntry(entryName);
            return entry != null ? entry : new ZipEntry(entryName);
        }

        // Not exists
        return null;
    }

    // --- FileSystem overrides ---

    @Override
    public EfficientZipFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            zipFile.close();
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singleton(new EfficientZipPath(this, "/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.emptyList();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Collections.singleton("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        if (more.length == 0) return new EfficientZipPath(this, first);
        StringBuilder sb = new StringBuilder(first);
        for (String m : more) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(m);
        }
        return new EfficientZipPath(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        // Basic glob support
        if (syntaxAndPattern.startsWith("glob:")) {
            String pattern = syntaxAndPattern.substring(5);
            String regex = globToRegex(pattern);
            var compiled = java.util.regex.Pattern.compile(regex);
            return path -> compiled.matcher(path.toString()).matches();
        } else if (syntaxAndPattern.startsWith("regex:")) {
            var compiled = java.util.regex.Pattern.compile(syntaxAndPattern.substring(6));
            return path -> compiled.matcher(path.toString()).matches();
        }
        throw new UnsupportedOperationException("Unsupported syntax: " + syntaxAndPattern);
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                case '?': sb.append("[^/]"); break;
                case '.': sb.append("\\."); break;
                case '\\': sb.append("\\\\"); break;
                case '{': sb.append("(?:"); break;
                case '}': sb.append(")"); break;
                case ',': sb.append("|"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }
}
