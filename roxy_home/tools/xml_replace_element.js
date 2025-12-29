try {
    var path = sandbox.resolve(args.path);
    xml.replaceElement(path, args.xpath, args.newXml);
    "✅ Element replaced successfully.";
} catch (e) {
    "❌ Error replacing element: " + e.message;
}
