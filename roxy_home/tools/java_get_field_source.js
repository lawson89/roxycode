try {
    var path = sandbox.resolve(args.path);
    var source = java.getFieldSource(path, args.className, args.fieldName);
    
    if (source.isPresent()) {
        source.get();
    } else {
        "❌ Field not found: " + args.className + "." + args.fieldName;
    }
} catch (e) {
    "❌ Error getting field source: " + e.message;
}
