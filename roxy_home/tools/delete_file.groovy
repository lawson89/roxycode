import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

String pathStr = args.path
Path target = sandbox.resolve(pathStr)

if (!Files.exists(target)) {
    return "❌ Path does not exist: $pathStr"
}

// Safety check
Path root = sandbox.getRoot()
if (target.equals(root)) return "❌ Cannot delete project root."
if (target.startsWith(root.resolve(".git"))) return "❌ Cannot delete .git folder."
// Also protect roxy_home context/tools if needed, but maybe I want to delete my own tools?
// Let's protect src/main/java/org/roxycode/core just in case? No, refactoring needs deletion.

try {
    if (Files.isDirectory(target)) {
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            @Override
            FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    } else {
        Files.delete(target)
    }
    return "✅ Deleted: $pathStr"
} catch (Exception e) {
    return "❌ Failed to delete: ${e.message}"
}
