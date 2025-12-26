import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

// 1. Resolve Path using Sandbox
def pathStr = args.path ?: "."
def rootDir = sandbox.resolve(pathStr)

if (!Files.exists(rootDir)) {
    return "❌ Path not found: " + pathStr
}
if (!Files.isDirectory(rootDir)) {
    return "❌ Not a directory: " + pathStr
}

def sb = new StringBuilder()
// Header
sb.append(rootDir.getFileName()).append("/\n")

// 2. Recursive Tree Logic
def traverse
traverse = { Path currentDir, String prefix, int depth ->
    if (depth > 5) { // Safety break for very deep directories
        sb.append(prefix).append("└── ... (max depth reached)\n")
        return
    }

    try {
        // List, sort, and collect files
        List<Path> contents = Files.list(currentDir)
                .sorted { a, b ->
                    // Sort directories first, then files
                    boolean aDir = Files.isDirectory(a)
                    boolean bDir = Files.isDirectory(b)
                    if (aDir && !bDir) return -1
                    if (!aDir && bDir) return 1
                    return a.compareTo(b)
                }
                .collect(Collectors.toList())

        for (int i = 0; i < contents.size(); i++) {
            Path p = contents.get(i)
            boolean isLast = (i == contents.size() - 1)

            // Draw tree branches
            sb.append(prefix)
            sb.append(isLast ? "└── " : "├── ")

            // Print Name
            sb.append(p.getFileName())
            if (Files.isDirectory(p)) sb.append("/")
            sb.append("\n")

            // Recurse if directory
            if (Files.isDirectory(p)) {
                // Ignore hidden git folders to save tokens
                if (!p.getFileName().toString().startsWith(".")) {
                    traverse(p, prefix + (isLast ? "    " : "│   "), depth + 1)
                }
            }
        }
    } catch (Exception e) {
        sb.append(prefix).append("❌ Access Denied: ").append(e.getMessage()).append("\n")
    }
}

// 3. Start Traversal
traverse(rootDir, "", 0)

return sb.toString()