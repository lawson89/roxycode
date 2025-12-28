var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
var Pattern = Java.type('java.util.regex.Pattern');

try {
    var path = sandbox.resolve(args.path);

    if (!Files.exists(path)) {
        throw new Error("File not found: " + args.path);
    }

    if (Files.isDirectory(path)) {
        throw new Error("Path is a directory: " + args.path);
    }

    var originalContent = Files.readString(path, StandardCharsets.UTF_8);

    // Create a Pattern object from the search regex
    var pattern = Pattern.compile(args.search);
    var matcher = pattern.matcher(originalContent);

    // Perform the replacement
    var modifiedContent = matcher.replaceAll(args.replace);

    // Write the modified content back to the file
    Files.writeString(path, modifiedContent, StandardCharsets.UTF_8);

    // The last evaluated expression is returned.
    "Successfully replaced text in: " + args.path;

} catch (e) {
    throw new Error("Error: " + e.message);
}