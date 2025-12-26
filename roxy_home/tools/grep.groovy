import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

String patternStr = args.pattern
String startPathStr = args.path ?: "."
String fileGlob = args.filePattern ?: "*"

Path root = sandbox.resolve(startPathStr)
if (!Files.exists(root)) return "❌ Path not found: $startPathStr"

Pattern regex = Pattern.compile(patternStr)
StringBuilder sb = new StringBuilder()
int matchCount = 0
Path projectRoot = sandbox.getRoot()

// Matcher for file glob
// Note: PathMatcher matches the Path object. For "glob:*.java", it matches the file name if we pass the filename path.
PathMatcher fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)

Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
    @Override
    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        String name = dir.getFileName().toString()
        // Skip common ignored directories
        if (name.startsWith(".") || name == "target" || name == "build" || name == "node_modules") {
            return FileVisitResult.SKIP_SUBTREE
        }
        return FileVisitResult.CONTINUE
    }

    @Override
    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!fileMatcher.matches(file.getFileName())) return FileVisitResult.CONTINUE
        
        try {
            int lineNum = 0
            // Read file line by line to avoid memory issues
            file.withReader { reader ->
                String line
                while ((line = reader.readLine()) != null) {
                    lineNum++
                    if (regex.matcher(line).find()) {
                        sb.append("${projectRoot.relativize(file)}:${lineNum}: ${line.trim()}\n")
                        matchCount++
                        if (matchCount >= 500) { // Limit results
                             throw new RuntimeException("LimitReached")
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            if (e.message == "LimitReached") return FileVisitResult.TERMINATE
            // Other runtime exceptions
        } catch (Exception e) {
            // Likely binary or unreadable
        }
        return FileVisitResult.CONTINUE
    }
})

if (matchCount == 0) return "No matches found."
if (matchCount >= 500) sb.append("\n... (truncated after 500 matches)")
return sb.toString()
