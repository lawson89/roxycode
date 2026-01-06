package org.roxycode.core.tools;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;

public class RestrictedFileSystem implements FileSystem {
    private final Path root;
    private final FileSystem delegate = FileSystem.newDefaultFileSystem();

    public RestrictedFileSystem(Path root) {
        this.root = root;
    }

    private Path resolve(Path path) {
        // Resolve the requested path against the sandbox root
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Access denied: " + path + " is outside the sandbox.");
        }
        return resolved;
    }

    @Override
    public Path parsePath(URI uri) {
        return delegate.parsePath(uri);
    }

    @Override
    public Path parsePath(String path) {
        return delegate.parsePath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        delegate.checkAccess(resolve(path), modes, linkOptions);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(resolve(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(resolve(path));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return delegate.newByteChannel(resolve(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(resolve(dir), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return resolve(path); // Force resolution to root
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return resolve(path).toRealPath(linkOptions);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(resolve(path), attributes, options);
    }
}
