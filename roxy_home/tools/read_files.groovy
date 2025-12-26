import java.nio.file.Files

StringBuilder output = new StringBuilder()
def paths = args.paths

if (paths == null) {
    return "Error: 'paths' argument is required (list of file paths)."
}

if (!(paths instanceof List)) {
    // Fallback if a single string is passed
    paths = [paths.toString()]
}

paths.each { pathStr ->
    output.append("--- File: ").append(pathStr).append(" ---\n")
    try {
        def p = sandbox.resolve(pathStr)
        if (Files.exists(p)) {
            if (Files.isDirectory(p)) {
                 output.append("(Error: Is a directory)\n")
            } else {
                 output.append(Files.readString(p))
                 output.append("\n")
            }
        } else {
            output.append("(Error: File not found)\n")
        }
    } catch (Exception e) {
        output.append("(Error reading file: ").append(e.message).append(")\n")
    }
    output.append("\n")
}

return output.toString()
