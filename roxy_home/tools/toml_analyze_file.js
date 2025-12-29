try {
    var path = sandbox.resolve(args.path);
    var summary = toml.analyzeFile(path);
    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
} catch (e) {
    "❌ Error analyzing TOML file: " + e.message;
}
