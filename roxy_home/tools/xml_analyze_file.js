try {
    var path = sandbox.resolve(args.path);
    var summary = xml.analyzeFile(path);
    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
} catch (e) {
    "❌ Error analyzing XML file: " + e.message;
}
