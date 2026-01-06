package org.roxycode.cache;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class FileListingService {

    private static final Logger LOG = LoggerFactory.getLogger(FileListingService.class);

    /**
     * Modern, recursive file listing using Java NIO Streams.
     * * @param rootDir The directory to scan.
     *
     * @param extensions List of extensions (e.g. "java", "xml") WITHOUT the dot.
     * @return A List of Paths.
     */
    public List<Path> findFiles(Path rootDir, List<String> extensions) {
        if (rootDir == null || !Files.exists(rootDir)) {
            return Collections.emptyList();
        }

        // Optimize lookup by converting list to a Set of lowercase extensions
        Set<String> targetExtensions = (extensions == null || extensions.isEmpty())
                ? Collections.emptySet()
                : extensions.stream()
                .map(ext -> "." + ext.toLowerCase())
                .collect(Collectors.toSet());

        // Files.walk creates a lazy stream of paths
        // We use try-with-resources to ensure the underlying file handle is closed
        //@todo externalize the src folder, this only applied to Java projects
        try (Stream<Path> stream = Files.walk(rootDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesExtension(path, targetExtensions))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("Failed to walk directory: {}", rootDir, e);
            return Collections.emptyList();
        }
    }

    private boolean matchesExtension(Path path, Set<String> extensions) {
        // If no extensions provided, return everything
        if (extensions.isEmpty()) return true;

        String fileName = path.getFileName().toString().toLowerCase();
        for (String ext : extensions) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}