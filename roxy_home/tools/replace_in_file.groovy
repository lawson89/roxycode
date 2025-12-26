import java.nio.file.Files

def p = sandbox.resolve(args.path)
def file = p.toFile()

if (!file.exists()) {
    return "File not found: " + args.path
}

try {
    def content = file.text
    // Use replaceAll which takes a regex
    def newContent = content.replaceAll(args.search, args.replace)
    
    if (content.equals(newContent)) {
        return "No matches found for pattern: " + args.search
    }
    
    file.text = newContent
    return "Successfully updated " + args.path
} catch (Exception e) {
    return "Error replacing text: " + e.message
}