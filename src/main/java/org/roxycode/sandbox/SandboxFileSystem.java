package org.roxycode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;

public class SandboxFileSystem extends FileSystem {
    private final SandboxFileSystemProvider provider;
    private final Path realRoot;
    private final boolean readOnly;
    private final FileSystem delegateFs;

    public SandboxFileSystem(SandboxFileSystemProvider provider, Path realRoot, boolean readOnly) {
        this.provider = provider;
        this.realRoot = realRoot.toAbsolutePath().normalize();
        this.readOnly = readOnly;
        this.delegateFs = realRoot.getFileSystem();
    }

    public Path getRealRoot() {
        return realRoot;
    }

    public boolean isReadOnlyFlag() {
        return readOnly;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        // We don't close the delegate as it's the default FS
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public String getSeparator() {
        return delegateFs.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singleton(new SandboxPath(this, Paths.get("/")));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return delegateFs.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return delegateFs.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
        Path p = delegateFs.getPath(first, more);
        return new SandboxPath(this, p);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return delegateFs.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return delegateFs.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return delegateFs.newWatchService();
    }
}
