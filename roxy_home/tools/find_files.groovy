import groovy.io.FileType
import java.util.regex.Pattern

def rootDir = sandbox.getRoot().toFile()
def path = args.path ?: "."
def startDir = path ? new File(rootDir, path) : rootDir

if (!startDir.exists()) {
    return "Directory not found: " + startDir.path
}

try {
    def regex = Pattern.compile(pattern)
    def result = new StringBuilder()

    startDir.traverse(type: FileType.FILES) { file ->
        if (file.name =~ regex) {
            // Return relative path from project root
            def relativePath = file.absolutePath.substring(rootDir.absolutePath.length() + 1)
            result.append(relativePath).append("\n")
        }
    }

    return result.toString().trim() ?: "No matches found."
} catch (Exception e) {
    return "Error: " + e.message
}
