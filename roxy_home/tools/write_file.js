var Files = Java.type('java.nio.file.Files');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');

try {
    var path = sandbox.resolve(args.path);
    var parent = path.getParent();
    if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
    }
    Files.writeString(path, args.content, StandardCharsets.UTF_8);
    "Successfully wrote to " + args.path;
} catch (e) {
    "Error: " + e.message;
}
