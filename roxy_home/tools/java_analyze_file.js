try {
    // 'java' is bound to JavaAnalysisService
    // 'json' is bound to ObjectMapper
    
    var path = sandbox.resolve(args.path);
    var summary = java.analyzeFile(path);
    
    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
} catch (e) {
    "❌ Error analyzing Java file: " + e.message;
}
