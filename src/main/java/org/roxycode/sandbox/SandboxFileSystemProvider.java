package org.roxycode.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class SandboxFileSystemProvider extends FileSystemProvider {
    private final Map<URI, SandboxFileSystem> filesystems = new HashMap<>();

    @Override
    public String getScheme() {
        return "sandbox";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        synchronized (filesystems) {
            if (filesystems.containsKey(uri)) {
                throw new FileSystemAlreadyExistsException();
            }
            
            String rootPathStr = (String) env.get("root");
            if (rootPathStr == null) {
                throw new IllegalArgumentException("Missing 'root' in environment");
            }
            
            boolean readOnly = Boolean.TRUE.equals(env.get("readOnly"));
            Path rootPath = Paths.get(rootPathStr);
            SandboxFileSystem fs = new SandboxFileSystem(this, rootPath, readOnly);
            filesystems.put(uri, fs);
            return fs;
        }
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        synchronized (filesystems) {
            SandboxFileSystem fs = filesystems.get(uri);
            if (fs == null) throw new FileSystemNotFoundException();
            return fs;
        }
    }

    @Override
    public Path getPath(URI uri) {
        // sandbox:///foo/bar
        String pathPart = uri.getPath();
        URI baseUri = URI.create(uri.getScheme() + "://" + (uri.getHost() != null ? uri.getHost() : ""));
        SandboxFileSystem fs = (SandboxFileSystem) getFileSystem(baseUri);
        return fs.getPath(pathPart);
    }

    public URI getUri(SandboxPath path) {
        SandboxFileSystem fs = (SandboxFileSystem) path.getFileSystem();
        // find the uri for this fs
        URI base = null;
        synchronized (filesystems) {
            for (Map.Entry<URI, SandboxFileSystem> entry : filesystems.entrySet()) {
                if (entry.getValue() == fs) {
                    base = entry.getKey();
                    break;
                }
            }
        }
        if (base == null) return null;
        return URI.create(base.toString() + path.toString());
    }

    private Path toReal(Path path) {
        if (!(path instanceof SandboxPath)) {
            throw new ProviderMismatchException();
        }
        return ((SandboxPath) path).getRealDelegate();
    }

    private void checkWrite(SandboxPath path) throws IOException {
        if (path.getFileSystem().isReadOnly()) {
            throw new AccessDeniedException(path.toString(), null, "FileSystem is read-only");
        }
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return Files.newInputStream(toReal(path), options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        checkWrite((SandboxPath) path);
        return Files.newOutputStream(toReal(path), options);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        if (options.contains(StandardOpenOption.WRITE)) {
            checkWrite((SandboxPath) path);
        }
        return FileChannel.open(toReal(path), options, attrs);
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options, ExecutorService executor, FileAttribute<?>... attrs) throws IOException {
        if (options.contains(StandardOpenOption.WRITE)) {
            checkWrite((SandboxPath) path);
        }
        return AsynchronousFileChannel.open(toReal(path), options, executor, attrs);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        if (options.contains(StandardOpenOption.WRITE)) {
            checkWrite((SandboxPath) path);
        }
        return Files.newByteChannel(toReal(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        final SandboxFileSystem fs = (SandboxFileSystem) dir.getFileSystem();
        final DirectoryStream<Path> delegateStream = Files.newDirectoryStream(toReal(dir), entry -> {
             // filter must return true for the filter passed in
             return filter.accept(new SandboxPath(fs, Paths.get("/").resolve(fs.getRealRoot().relativize(entry))));
        });
        
        return new DirectoryStream<Path>() {
            @Override
            public java.util.Iterator<Path> iterator() {
                final java.util.Iterator<Path> it = delegateStream.iterator();
                return new java.util.Iterator<Path>() {
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public Path next() { 
                        Path next = it.next();
                        return new SandboxPath(fs, Paths.get("/").resolve(fs.getRealRoot().relativize(next)));
                    }
                };
            }
            @Override public void close() throws IOException { delegateStream.close(); }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        checkWrite((SandboxPath) dir);
        Files.createDirectory(toReal(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        checkWrite((SandboxPath) path);
        Files.delete(toReal(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        checkWrite((SandboxPath) target);
        Files.copy(toReal(source), toReal(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        checkWrite((SandboxPath) source);
        checkWrite((SandboxPath) target);
        Files.move(toReal(source), toReal(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return Files.isSameFile(toReal(path), toReal(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return Files.isHidden(toReal(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return Files.getFileStore(toReal(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (AccessMode mode : modes) {
            if (mode == AccessMode.WRITE && ((SandboxFileSystem)path.getFileSystem()).isReadOnly()) {
                throw new AccessDeniedException(path.toString());
            }
        }
        toReal(path).getFileSystem().provider().checkAccess(toReal(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return Files.getFileAttributeView(toReal(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        return Files.readAttributes(toReal(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return Files.readAttributes(toReal(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        checkWrite((SandboxPath) path);
        Files.setAttribute(toReal(path), attribute, value, options);
    }
}
