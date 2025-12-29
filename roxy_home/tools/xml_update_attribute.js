try {
    var path = sandbox.resolve(args.path);
    xml.updateAttribute(path, args.xpath, args.name, args.value);
    "✅ Attribute updated successfully.";
} catch (e) {
    "❌ Error updating attribute: " + e.message;
}
