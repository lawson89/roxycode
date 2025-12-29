try {
    var path = sandbox.resolve(args.path);
    java.replaceField(path, args.className, args.fieldName, args.newFieldSource);
    "✅ Field replaced successfully";
} catch (e) {
    "❌ Error replacing field: " + e.message;
}
