try {
    var path = sandbox.resolve(args.path);
    var source = xml.getElementSource(path, args.xpath);
    if (source.isPresent()) {
        source.get();
    } else {
        "❌ Element not found: " + args.xpath;
    }
} catch (e) {
    "❌ Error retrieving element source: " + e.message;
}
