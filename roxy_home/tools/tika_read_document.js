// 'tika' is bound to TikaService
// 'sandbox' is bound to Sandbox
try {
    var path = sandbox.resolve(args.path);
    tika.readDocument(path);
} catch (e) {
    throw new Error("Error processing document: " + e.message);
}
