var Files = Java.type('java.nio.file.Files');
var SimpleFileVisitor = Java.type('java.nio.file.SimpleFileVisitor');
var FileVisitResult = Java.type('java.nio.file.FileVisitResult');

var pathStr = args.path;
var p = sandbox.resolve(pathStr);
var file = p.toFile();

var result;

if (!file.exists()) {
    result = "File did not exist: " + pathStr;
} else {
    try {
        if (file.isDirectory()) {
            Files.walkFileTree(p, new (Java.extend(SimpleFileVisitor))({
                visitFile: function(file, attrs) {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                },
                postVisitDirectory: function(dir, exc) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            }));
        } else {
            Files.delete(p);
        }
        result = "✅ Deleted: " + pathStr;
    } catch (e) {
        result = "❌ Failed to delete: " + e.message;
    }
}

result;
