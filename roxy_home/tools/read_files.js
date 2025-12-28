var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
var ArrayList = Java.type('java.util.ArrayList');

try {
    if (!Array.isArray(args.paths)) {
        throw new Error("'paths' must be an array of strings.");
    }

    var fileContents = new ArrayList();

    for (var i = 0; i < args.paths.length; i++) {
        var filePath = args.paths[i];
        var path = sandbox.resolve(filePath);

        if (!Files.exists(path)) {
            throw new Error("File not found: " + filePath);
        }

        if (Files.isDirectory(path)) {
            throw new Error("Path is a directory: " + filePath);
        }

        var content = Files.readString(path, StandardCharsets.UTF_8);
        fileContents.add(content);
    }
    
    // The last evaluated expression is returned.
    fileContents;

} catch (e) {
    throw new Error("Error: " + e.message);
}