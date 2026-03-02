package org.embeddedt.blacksmith.impl.zipfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.zip.ZipEntry;

public class EfficientZipFileSystemProvider extends FileSystemProvider {

    private static final EfficientZipFileSystemProvider INSTANCE = new EfficientZipFileSystemProvider();

    static EfficientZipFileSystemProvider instance() {
        return INSTANCE;
    }

    @Override
    public String getScheme() {
        return "jar";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw new UnsupportedOperationException("Use EfficientZipFileSystem constructor directly");
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        throw new FileSystemNotFoundException(uri.toString());
    }

    @Override
    public Path getPath(URI uri) {
        throw new UnsupportedOperationException();
    }

    // --- Core I/O ---

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        SeekableByteChannel ch = newByteChannel(path, options, attrs);
        return new SeekableByteChannelFileChannel(ch);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        checkReadOnly(options);
        var p = toZipPath(path);
        var fs = (EfficientZipFileSystem) p.getFileSystem();
        var entry = resolveOrThrow(fs, p);
        if (entry.isDirectory())
            throw new IOException("Is a directory: " + path);

        byte[] data;
        try (InputStream in = fs.getZipFile().getInputStream(entry)) {
            data = in.readAllBytes();
        }
        return new ByteBufferChannel(ByteBuffer.wrap(data));
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        for (OpenOption opt : options) {
            if (opt != StandardOpenOption.READ) throw new UnsupportedOperationException("Read-only: " + opt);
        }
        var p = toZipPath(path);
        var fs = (EfficientZipFileSystem) p.getFileSystem();
        var entry = resolveOrThrow(fs, p);
        if (entry.isDirectory())
            throw new IOException("Is a directory: " + path);

        return fs.getZipFile().getInputStream(entry);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        var p = toZipPath(dir);
        var fs = (EfficientZipFileSystem) p.getFileSystem();
        var dirNode = fs.resolveDir(p);
        if (dirNode == null)
            throw new NotDirectoryException(dir.toString());

        List<Path> entries = new ArrayList<>();
        for (String childName : dirNode.childDirs.keySet()) {
            Path childPath = p.resolve(childName + "/");
            if (filter.accept(childPath)) entries.add(childPath);
        }
        // Scan zip entries for file children (not tracked in the directory tree)
        String prefix = p.getEntryName();
        if (!prefix.isEmpty()) prefix = prefix + "/";
        Enumeration<? extends ZipEntry> zipEntries = fs.getZipFile().entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry ze = zipEntries.nextElement();
            String name = ze.getName();
            if (ze.isDirectory() || !name.startsWith(prefix)) continue;
            String remainder = name.substring(prefix.length());
            // Only immediate children (no slashes)
            if (remainder.isEmpty() || remainder.indexOf('/') >= 0) continue;
            Path childPath = p.resolve(remainder);
            if (filter.accept(childPath)) entries.add(childPath);
        }

        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return entries.iterator();
            }
            @Override
            public void close() {}
        };
    }

    // --- Attribute access ---

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class)
            throw new UnsupportedOperationException("Only BasicFileAttributes supported");
        var p = toZipPath(path);
        var fs = (EfficientZipFileSystem) p.getFileSystem();
        var entry = resolveOrThrow(fs, p);
        return (A) new ZipEntryAttributes(entry);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        var attrs = readAttributes(path, BasicFileAttributes.class, options);
        Map<String, Object> result = new LinkedHashMap<>();
        // Support "basic:*" or just "*"
        String attrList = attributes;
        if (attrList.startsWith("basic:")) attrList = attrList.substring(6);
        if (attrList.equals("*")) {
            result.put("lastModifiedTime", attrs.lastModifiedTime());
            result.put("lastAccessTime", attrs.lastAccessTime());
            result.put("creationTime", attrs.creationTime());
            result.put("size", attrs.size());
            result.put("isRegularFile", attrs.isRegularFile());
            result.put("isDirectory", attrs.isDirectory());
            result.put("isSymbolicLink", attrs.isSymbolicLink());
            result.put("isOther", attrs.isOther());
            result.put("fileKey", attrs.fileKey());
        } else {
            for (String attr : attrList.split(",")) {
                switch (attr.trim()) {
                    case "lastModifiedTime" -> result.put("lastModifiedTime", attrs.lastModifiedTime());
                    case "lastAccessTime" -> result.put("lastAccessTime", attrs.lastAccessTime());
                    case "creationTime" -> result.put("creationTime", attrs.creationTime());
                    case "size" -> result.put("size", attrs.size());
                    case "isRegularFile" -> result.put("isRegularFile", attrs.isRegularFile());
                    case "isDirectory" -> result.put("isDirectory", attrs.isDirectory());
                    case "isSymbolicLink" -> result.put("isSymbolicLink", attrs.isSymbolicLink());
                    case "isOther" -> result.put("isOther", attrs.isOther());
                    case "fileKey" -> result.put("fileKey", attrs.fileKey());
                    default -> throw new IllegalArgumentException("Unknown attribute: " + attr);
                }
            }
        }
        return result;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    // --- Access checks ---

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (AccessMode mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE)
                throw new AccessDeniedException(path.toString());
        }
        var p = toZipPath(path);
        var fs = (EfficientZipFileSystem) p.getFileSystem();
        resolveOrThrow(fs, p);
    }

    // --- Write operations: all throw ReadOnlyFileSystemException ---

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public boolean isSameFile(Path path1, Path path2) throws IOException {
        return path1.toAbsolutePath().equals(path2.toAbsolutePath());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new UnsupportedOperationException();
    }

    // --- Helpers ---

    private static EfficientZipPath toZipPath(Path path) {
        if (path instanceof EfficientZipPath zp) return zp;
        throw new ProviderMismatchException("Not an EfficientZipPath: " + path.getClass());
    }

    private static ZipEntry resolveOrThrow(EfficientZipFileSystem fs, EfficientZipPath path) throws NoSuchFileException {
        var entry = fs.resolve(path);
        if (entry == null) throw new NoSuchFileException(path.toString());
        return entry;
    }

    private static void checkReadOnly(Set<? extends OpenOption> options) {
        for (OpenOption opt : options) {
            if (opt == StandardOpenOption.WRITE || opt == StandardOpenOption.APPEND
                || opt == StandardOpenOption.CREATE || opt == StandardOpenOption.CREATE_NEW
                || opt == StandardOpenOption.DELETE_ON_CLOSE || opt == StandardOpenOption.TRUNCATE_EXISTING)
                throw new ReadOnlyFileSystemException();
        }
    }

    // --- Inner classes ---

    /**
     * SeekableByteChannel backed by a byte array (read via ZipFile.getInputStream).
     */
    static final class ByteBufferChannel implements SeekableByteChannel {
        private final ByteBuffer data;
        private boolean closed;

        ByteBufferChannel(ByteBuffer data) {
            this.data = data;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            ensureOpen();
            if (data.remaining() == 0) return -1;
            int toRead = Math.min(dst.remaining(), data.remaining());
            if (toRead <= 0) return -1;
            int oldLimit = data.limit();
            data.limit(data.position() + toRead);
            dst.put(data);
            data.limit(oldLimit);
            return toRead;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new ReadOnlyFileSystemException();
        }

        @Override
        public long position() {
            return data.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            if (newPosition < 0) throw new IllegalArgumentException("Negative position");
            data.position((int) Math.min(newPosition, data.limit()));
            return this;
        }

        @Override
        public long size() {
            return data.limit();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new ReadOnlyFileSystemException();
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new ClosedChannelException();
        }
    }

    /**
     * Adapts a SeekableByteChannel to a FileChannel for callers that require FileChannel.open().
     */
    static final class SeekableByteChannelFileChannel extends FileChannel {
        private final SeekableByteChannel ch;

        SeekableByteChannelFileChannel(SeekableByteChannel ch) {
            this.ch = ch;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return ch.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                int n = ch.read(dsts[i]);
                if (n < 0) { return total == 0 ? -1 : total; }
                total += n;
            }
            return total;
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            synchronized (this) {
                long old = ch.position();
                ch.position(position);
                int n = ch.read(dst);
                ch.position(old);
                return n;
            }
        }

        @Override
        public long position() throws IOException { return ch.position(); }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            ch.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException { return ch.size(); }

        @Override
        public int write(ByteBuffer src) { throw new ReadOnlyFileSystemException(); }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) { throw new ReadOnlyFileSystemException(); }

        @Override
        public int write(ByteBuffer src, long position) { throw new ReadOnlyFileSystemException(); }

        @Override
        public FileChannel truncate(long size) { throw new ReadOnlyFileSystemException(); }

        @Override
        public void force(boolean metaData) {}

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(count, 8192));
            long total = 0;
            synchronized (this) {
                long old = ch.position();
                ch.position(position);
                while (total < count) {
                    buf.clear();
                    buf.limit((int) Math.min(buf.capacity(), count - total));
                    int n = ch.read(buf);
                    if (n <= 0) break;
                    buf.flip();
                    target.write(buf);
                    total += n;
                }
                ch.position(old);
            }
            return total;
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) { throw new ReadOnlyFileSystemException(); }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            throw new UnsupportedOperationException("Cannot mmap a zip entry");
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void implCloseChannel() throws IOException {
            ch.close();
        }
    }

    /**
     * BasicFileAttributes implementation backed by ZipEntry.
     */
    static final class ZipEntryAttributes implements BasicFileAttributes {
        private static final FileTime EPOCH = FileTime.fromMillis(0);
        private final boolean isDir;
        private final long size;

        ZipEntryAttributes(ZipEntry entry) {
            this.isDir = entry.isDirectory();
            this.size = entry.getSize() >= 0 ? entry.getSize() : 0;
        }

        @Override
        public FileTime lastModifiedTime() { return EPOCH; }
        @Override
        public FileTime lastAccessTime() { return EPOCH; }
        @Override
        public FileTime creationTime() { return EPOCH; }
        @Override
        public boolean isRegularFile() { return !isDir; }
        @Override
        public boolean isDirectory() { return isDir; }
        @Override
        public boolean isSymbolicLink() { return false; }
        @Override
        public boolean isOther() { return false; }
        @Override
        public long size() { return size; }
        @Override
        public Object fileKey() { return null; }
    }
}
