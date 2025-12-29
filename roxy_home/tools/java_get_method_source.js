try {
    // 'java' is bound to JavaAnalysisService
    var path = sandbox.resolve(args.path);
    var source = java.getMethodSource(path, args.className, args.methodName);
    
    if (source.isPresent()) {
        source.get();
    } else {
        "❌ Method not found: " + args.className + "." + args.methodName;
    }
} catch (e) {
    "❌ Error getting method source: " + e.message;
}
