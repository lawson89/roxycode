def file = new File(sandbox.getRoot().toFile(), path)
if (!file.exists()) {
    return "File not found: " + path
}

try {
    def content = file.text
    // Use replaceAll which takes a regex
    def newContent = content.replaceAll(search, replace)
    
    if (content.equals(newContent)) {
        return "No matches found for pattern: " + search
    }
    
    file.text = newContent
    return "Successfully updated " + path
} catch (Exception e) {
    return "Error replacing text: " + e.message
}
