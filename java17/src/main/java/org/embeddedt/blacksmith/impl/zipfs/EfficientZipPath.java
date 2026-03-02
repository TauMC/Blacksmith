package org.embeddedt.blacksmith.impl.zipfs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public class EfficientZipPath implements Path {
    private final EfficientZipFileSystem fs;
    private final boolean absolute;
    private final String[] components; // individual path segments, never null, never contain slashes
    private volatile String pathString; // lazily computed

    private static final String[] EMPTY = new String[0];

    EfficientZipPath(EfficientZipFileSystem fs, String path) {
        this.fs = fs;
        this.absolute = !path.isEmpty() && path.charAt(0) == '/';
        this.components = parseComponents(path);
    }

    private EfficientZipPath(EfficientZipFileSystem fs, boolean absolute, String[] components) {
        this.fs = fs;
        this.absolute = absolute;
        this.components = components;
    }

    private static String[] parseComponents(String p) {
        if (p.isEmpty() || p.equals("/")) return EMPTY;
        // Normalize: split, resolve . and ..
        String[] parts = p.split("/");
        int count = 0;
        boolean abs = p.charAt(0) == '/';
        String[] stack = new String[parts.length];
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (count > 0 && !stack[count - 1].equals("..")) {
                    count--;
                } else if (!abs) {
                    stack[count++] = "..";
                }
            } else {
                stack[count++] = part;
            }
        }
        return count == 0 ? EMPTY : Arrays.copyOf(stack, count);
    }

    String[] getComponents() {
        return components;
    }

    private String buildPathString() {
        if (components.length == 0) return absolute ? "/" : "";
        StringBuilder sb = new StringBuilder();
        if (absolute) sb.append('/');
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(components[i]);
        }
        return sb.toString();
    }

    private String getPathString() {
        String s = pathString;
        if (s == null) {
            s = buildPathString();
            pathString = s;
        }
        return s;
    }

    @Override
    public FileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? new EfficientZipPath(fs, true, EMPTY) : null;
    }

    @Override
    public Path getFileName() {
        if (components.length == 0) return null;
        return new EfficientZipPath(fs, false, new String[]{ components[components.length - 1] });
    }

    @Override
    public Path getParent() {
        if (components.length == 0) return null;
        if (components.length == 1) return absolute ? new EfficientZipPath(fs, true, EMPTY) : null;
        return new EfficientZipPath(fs, absolute, Arrays.copyOf(components, components.length - 1));
    }

    @Override
    public int getNameCount() {
        return components.length;
    }

    @Override
    public Path getName(int index) {
        if (index < 0 || index >= components.length)
            throw new IllegalArgumentException("Invalid index: " + index);
        return new EfficientZipPath(fs, false, new String[]{ components[index] });
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || beginIndex >= components.length || endIndex > components.length || beginIndex >= endIndex)
            throw new IllegalArgumentException();
        return new EfficientZipPath(fs, false, Arrays.copyOfRange(components, beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(other instanceof EfficientZipPath o) || o.fs != this.fs) return false;
        if (this.absolute != o.absolute) return false;
        String[] oc = o.components;
        if (oc.length > components.length) return false;
        for (int i = 0; i < oc.length; i++) {
            if (!components[i].equals(oc[i])) return false;
        }
        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(other instanceof EfficientZipPath o) || o.fs != this.fs) return false;
        if (o.absolute) return equals(o);
        String[] oc = o.components;
        if (oc.length > components.length) return false;
        int offset = components.length - oc.length;
        for (int i = 0; i < oc.length; i++) {
            if (!components[offset + i].equals(oc[i])) return false;
        }
        return true;
    }

    @Override
    public Path normalize() {
        return this; // already normalized at construction
    }

    @Override
    public Path resolve(Path other) {
        if (other instanceof EfficientZipPath o) {
            if (o.absolute || components.length == 0) return o;
            if (o.components.length == 0) return this;
            String[] merged = new String[components.length + o.components.length];
            System.arraycopy(components, 0, merged, 0, components.length);
            System.arraycopy(o.components, 0, merged, components.length, o.components.length);
            return new EfficientZipPath(fs, absolute, merged);
        }
        return resolve(new EfficientZipPath(fs, other.toString()));
    }

    @Override
    public Path resolve(String other) {
        return resolve(new EfficientZipPath(fs, other));
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof EfficientZipPath o) || o.fs != this.fs)
            throw new IllegalArgumentException("Different filesystem");
        if (this.absolute != o.absolute)
            throw new IllegalArgumentException("Cannot relativize absolute and relative paths");
        String[] oc = o.components;
        int common = 0;
        int max = Math.min(components.length, oc.length);
        while (common < max && components[common].equals(oc[common])) common++;
        int ups = components.length - common;
        int tails = oc.length - common;
        String[] result = new String[ups + tails];
        Arrays.fill(result, 0, ups, "..");
        System.arraycopy(oc, common, result, ups, tails);
        return new EfficientZipPath(fs, false, result);
    }

    @Override
    public URI toUri() {
        try {
            String jarPath = fs.getZipPath().toUri().toString();
            String p = getPathString();
            return new URI("jar:" + jarPath + "!" + (absolute ? p : "/" + p));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Path toAbsolutePath() {
        if (absolute) return this;
        return new EfficientZipPath(fs, true, components);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return toAbsolutePath();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException("Zip filesystems do not support watching");
    }

    @Override
    public int compareTo(Path other) {
        if (other instanceof EfficientZipPath o) {
            return this.getPathString().compareTo(o.getPathString());
        }
        return this.getPathString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EfficientZipPath other)) return false;
        return this.fs == other.fs && this.absolute == other.absolute
                && Arrays.equals(this.components, other.components);
    }

    @Override
    public int hashCode() {
        int h = fs.hashCode();
        h = 31 * h + Boolean.hashCode(absolute);
        h = 31 * h + Arrays.hashCode(components);
        return h;
    }

    @Override
    public String toString() {
        return getPathString();
    }

    /**
     * Returns the ZIP entry name for this path (components joined with "/", no leading slash).
     */
    String getEntryName() {
        if (components.length == 0) return "";
        if (components.length == 1) return components[0];
        return String.join("/", components);
    }

    @Override
    public Iterator<Path> iterator() {
        return new Iterator<>() {
            private int idx = 0;
            @Override
            public boolean hasNext() { return idx < components.length; }
            @Override
            public Path next() {
                if (idx >= components.length) throw new NoSuchElementException();
                return getName(idx++);
            }
        };
    }
}
