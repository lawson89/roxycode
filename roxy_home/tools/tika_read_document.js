var Files = Java.type('java.nio.file.Files');
var FileInputStream = Java.type('java.io.FileInputStream');

try {
    // 'tika' is bound to TikaService in ToolExecutionService
    var path = sandbox.resolve(args.path);

    if (!Files.exists(path)) {
        throw new Error("File not found: " + args.path);
    }

    if (Files.isDirectory(path)) {
        throw new Error("Path is a directory: " + args.path);
    }

    var fileInputStream = new FileInputStream(path.toFile());
    try {
        var result = tika.extractText(fileInputStream);
        // The last evaluated expression is returned.
        result;
    } finally {
        fileInputStream.close();
    }

} catch (e) {
    throw new Error("Error processing document: " + e.message);
}
