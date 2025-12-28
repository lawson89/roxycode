var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var TikaService = Java.type('org.roxycode.core.service.TikaService');
var FileInputStream = Java.type('java.io.FileInputStream');

try {
    // Assuming sandbox can provide the TikaService instance
    // This might need adjustment based on how services are actually exposed in GraalJS context
    var tikaService = sandbox.getTikaService();

    var path = sandbox.resolve(args.path);

    if (!Files.exists(path)) {
        throw new Error("File not found: " + args.path);
    }

    if (Files.isDirectory(path)) {
        throw new Error("Path is a directory: " + args.path);
    }

    var fileInputStream = new FileInputStream(path.toFile());
    try {
        var result = tikaService.extractText(fileInputStream);
        // The last evaluated expression is returned.
        result;
    } finally {
        fileInputStream.close();
    }

} catch (e) {
    throw new Error("Error processing document: " + e.message);
}