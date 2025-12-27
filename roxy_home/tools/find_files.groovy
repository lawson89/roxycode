import groovy.io.FileType
import java.util.regex.Pattern

def pathStr = args.path ?: "."
def startDir = sandbox.resolve(pathStr).toFile()
def rootDir = sandbox.getRoot().toFile()

if (!startDir.exists()) {
    return "Directory not found: " + startDir.path
}

try {
    def regex = Pattern.compile(args.pattern)
    def result = new StringBuilder()

    startDir.traverse(type: FileType.FILES) { file ->
        if (file.name =~ regex) {
            // Return relative path from project root
            def relativePath = file.absolutePath.substring(rootDir.absolutePath.length())
            if (relativePath.startsWith(File.separator)) {
                relativePath = relativePath.substring(1)
            }
            result.append(relativePath).append("\n")
        }
    }

    return result.toString().trim() ?: "No matches found."
} catch (Exception e) {
    return "Error: " + e.message
}
