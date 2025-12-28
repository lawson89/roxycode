var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');

try {
    var path = sandbox.resolve(args.path);

    if (!Files.exists(path)) {
        throw new Error("File not found: " + args.path);
    }

    if (Files.isDirectory(path)) {
        throw new Error("Path is a directory: " + args.path);
    }

    var content = Files.readString(path, StandardCharsets.UTF_8);
    // The last evaluated expression is returned.
    content;

} catch (e) {
    throw new Error("Error: " + e.message);
}