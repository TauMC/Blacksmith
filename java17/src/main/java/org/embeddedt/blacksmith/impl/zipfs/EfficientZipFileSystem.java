package org.embeddedt.blacksmith.impl.zipfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An NIO file system for zips that wraps a Java ZipFile.
 */
public class EfficientZipFileSystem extends FileSystem {

    private static final int[] EMPTY_OFFSETS = new int[0];

    static final class DirNode {
        Map<String, DirNode> childDirs;
        int[] fileChildOffsets; // offsets into cdBuffer for each file child

        DirNode() {
            childDirs = new HashMap<>();
            fileChildOffsets = EMPTY_OFFSETS;
        }

        void freeze() {
            childDirs = childDirs.isEmpty() ? Map.of() : Map.copyOf(childDirs);
            for (DirNode child : childDirs.values()) {
                child.freeze();
            }
        }
    }

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int EOCD_SIZE = 22;
    private static final int EOCD_OFF_CD_SIZE = 12;
    private static final int EOCD_OFF_CD_OFFSET = 16;
    private static final int EOCD_MAX_COMMENT_LENGTH = 65535;

    private static final int CD_ENTRY_SIGNATURE = 0x02014b50;
    private static final int CD_ENTRY_HEADER_SIZE = 46;
    private static final int CD_OFF_FILENAME_LENGTH = 28;
    private static final int CD_OFF_EXTRA_LENGTH = 30;
    private static final int CD_OFF_COMMENT_LENGTH = 32;

    private final Path zipPath;
    private final ZipFile zipFile;
    private final Path tempFile; // non-null if we had to copy to a temp file
    private final MappedByteBuffer cdBuffer; // memory-mapped central directory, null for empty zips
    private final DirNode root;
    private final EfficientZipFileSystemProvider provider;
    private final Thread shutdownHook; // non-null if tempFile is non-null
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
        if (tmp != null) {
            Path tempRef = tmp;
            Thread hook = new Thread(() -> {
                try { Files.deleteIfExists(tempRef); } catch (IOException ignored) {}
            });
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
        } else {
            this.shutdownHook = null;
        }
        Path mmapPath = (tmp != null) ? tmp : zipPath;
        this.cdBuffer = mmapCentralDirectory(mmapPath);
        this.root = buildTree();
    }

    private static MappedByteBuffer mmapCentralDirectory(Path filePath) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < EOCD_SIZE) return null; // too small for a valid zip

            // Read the tail of the file to find the EOCD record
            int tailSize = (int) Math.min(fileSize, (long) EOCD_SIZE + EOCD_MAX_COMMENT_LENGTH);
            ByteBuffer tail = ByteBuffer.allocate(tailSize);
            tail.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(tail, fileSize - tailSize);
            tail.flip();

            // Scan backwards for EOCD signature
            int eocdPos = -1;
            for (int i = tailSize - EOCD_SIZE; i >= 0; i--) {
                if (tail.getInt(i) == EOCD_SIGNATURE) {
                    eocdPos = i;
                    break;
                }
            }
            if (eocdPos < 0) return null;

            long cdSize = Integer.toUnsignedLong(tail.getInt(eocdPos + EOCD_OFF_CD_SIZE));
            long cdOffset = Integer.toUnsignedLong(tail.getInt(eocdPos + EOCD_OFF_CD_OFFSET));

            if (cdSize == 0) return null;

            MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, cdOffset, cdSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return buf;
        }
    }

    private DirNode buildTree() {
        DirNode treeRoot = new DirNode();
        if (cdBuffer == null) {
            treeRoot.freeze();
            return treeRoot;
        }

        IdentityHashMap<DirNode, List<Integer>> fileOffsets = new IdentityHashMap<>();

        int pos = 0;
        int limit = cdBuffer.limit();
        while (pos + CD_ENTRY_HEADER_SIZE <= limit) {
            if (cdBuffer.getInt(pos) != CD_ENTRY_SIGNATURE) break;

            int fileNameLen = Short.toUnsignedInt(cdBuffer.getShort(pos + CD_OFF_FILENAME_LENGTH));
            int extraLen = Short.toUnsignedInt(cdBuffer.getShort(pos + CD_OFF_EXTRA_LENGTH));
            int commentLen = Short.toUnsignedInt(cdBuffer.getShort(pos + CD_OFF_COMMENT_LENGTH));

            byte[] nameBytes = new byte[fileNameLen];
            cdBuffer.get(pos + CD_ENTRY_HEADER_SIZE, nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            boolean isDirectory = name.endsWith("/");
            if (isDirectory) name = name.substring(0, name.length() - 1);

            if (!name.isEmpty()) {
                String[] parts = name.split("/");
                DirNode current = treeRoot;
                int dirDepth = isDirectory ? parts.length : parts.length - 1;
                for (int i = 0; i < dirDepth; i++) {
                    current = current.childDirs.computeIfAbsent(parts[i], k -> new DirNode());
                }
                if (!isDirectory) {
                    fileOffsets.computeIfAbsent(current, k -> new ArrayList<>()).add(pos);
                }
            }

            pos += CD_ENTRY_HEADER_SIZE + fileNameLen + extraLen + commentLen;
        }

        // Convert temporary lists to compact int[] arrays on each DirNode
        fileOffsets.forEach((node, offsets) -> {
            int[] arr = new int[offsets.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = offsets.get(i);
            node.fileChildOffsets = arr;
        });

        treeRoot.freeze();
        return treeRoot;
    }

    /**
     * Read the basename (filename after last '/') of the entry at the given
     * central directory offset.
     */
    String readBasename(int cdOffset) {
        int nameLen = Short.toUnsignedInt(cdBuffer.getShort(cdOffset + CD_OFF_FILENAME_LENGTH));
        byte[] nameBytes = new byte[nameLen];
        cdBuffer.get(cdOffset + CD_ENTRY_HEADER_SIZE, nameBytes); // absolute get, thread-safe
        int lastSlash = -1;
        for (int i = nameBytes.length - 1; i >= 0; i--) {
            if (nameBytes[i] == '/') { lastSlash = i; break; }
        }
        return new String(nameBytes, lastSlash + 1, nameLen - lastSlash - 1, StandardCharsets.UTF_8);
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
        // Check cache first
        ZipEntry cached = path.getCachedEntry();
        if (cached != null) {
            return cached;
        }

        String[] components = path.getComponents();
        if (components.length == 0) {
            var entry = new ZipEntry("/");
            path.setCachedEntry(entry);
            return entry;
        }

        var entry = zipFile.getEntry(path.getEntryName());

        if (entry != null) {
            path.setCachedEntry(entry);
            return entry;
        }

        // Check if the path is a known directory
        if (resolveDir(path) != null) {
            String entryName = path.getEntryName() + "/";
            entry = zipFile.getEntry(entryName);
            entry = entry != null ? entry : new ZipEntry(entryName);
            path.setCachedEntry(entry);
            return entry;
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
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (Exception ignored) {
                }
            }
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
