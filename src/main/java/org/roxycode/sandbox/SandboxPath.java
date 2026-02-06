package org.roxycode.sandbox;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Iterator;

public class SandboxPath implements Path {
    private final SandboxFileSystem fs;
    private final Path virtualPath;

    public SandboxPath(SandboxFileSystem fs, Path virtualPath) {
        this.fs = fs;
        this.virtualPath = virtualPath.normalize();
    }

    public Path getRealDelegate() {
        Path resolved = fs.getRealRoot().resolve(virtualPath.isAbsolute() 
            ? virtualPath.toString().substring(1) 
            : virtualPath.toString()).normalize();
        
        if (!resolved.startsWith(fs.getRealRoot())) {
            return fs.getRealRoot();
        }
        return resolved;
    }

    @Override
    public FileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return virtualPath.isAbsolute();
    }

    @Override
    public Path getRoot() {
        return virtualPath.getRoot() != null ? new SandboxPath(fs, virtualPath.getRoot()) : null;
    }

    @Override
    public Path getFileName() {
        Path name = virtualPath.getFileName();
        return name != null ? new SandboxPath(fs, name) : null;
    }

    @Override
    public Path getParent() {
        Path parent = virtualPath.getParent();
        return parent != null ? new SandboxPath(fs, parent) : null;
    }

    @Override
    public int getNameCount() {
        return virtualPath.getNameCount();
    }

    @Override
    public Path getName(int index) {
        return new SandboxPath(fs, virtualPath.getName(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new SandboxPath(fs, virtualPath.subpath(beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(other instanceof SandboxPath)) return false;
        return virtualPath.startsWith(((SandboxPath) other).virtualPath);
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(other instanceof SandboxPath)) return false;
        return virtualPath.endsWith(((SandboxPath) other).virtualPath);
    }

    @Override
    public Path normalize() {
        return new SandboxPath(fs, virtualPath.normalize());
    }

    @Override
    public Path resolve(Path other) {
        if (other instanceof SandboxPath) {
            return resolve(((SandboxPath) other).virtualPath.toString());
        }
        return resolve(other.toString());
    }

    @Override
    public Path resolve(String other) {
        Path otherPath = Paths.get(other);
        if (otherPath.isAbsolute()) {
            return new SandboxPath(fs, otherPath);
        }
        return new SandboxPath(fs, virtualPath.resolve(otherPath));
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof SandboxPath)) {
            throw new IllegalArgumentException("Expected SandboxPath");
        }
        return new SandboxPath(fs, virtualPath.relativize(((SandboxPath) other).virtualPath));
    }

    @Override
    public URI toUri() {
        return ((SandboxFileSystemProvider)fs.provider()).getUri(this);
    }

    @Override
    public Path toAbsolutePath() {
        if (isAbsolute()) return this;
        return new SandboxPath(fs, Paths.get("/").resolve(virtualPath));
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        Path real = getRealDelegate().toRealPath(options);
        if (!real.startsWith(fs.getRealRoot())) {
             throw new IOException("Real path escaped sandbox");
        }
        return new SandboxPath(fs, Paths.get("/").resolve(fs.getRealRoot().relativize(real)));
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException("Sandbox paths cannot be converted to java.io.File");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        return getRealDelegate().register(watcher, events, modifiers);
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        return getRealDelegate().register(watcher, events);
    }

    @Override
    public Iterator<Path> iterator() {
        final Iterator<Path> it = virtualPath.iterator();
        return new Iterator<Path>() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public Path next() { return new SandboxPath(fs, it.next()); }
        };
    }

    @Override
    public int compareTo(Path other) {
        return virtualPath.compareTo(((SandboxPath) other).virtualPath);
    }
    
    @Override
    public String toString() {
        return virtualPath.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof SandboxPath && virtualPath.equals(((SandboxPath) obj).virtualPath);
    }
    
    @Override
    public int hashCode() {
        return virtualPath.hashCode();
    }
}
