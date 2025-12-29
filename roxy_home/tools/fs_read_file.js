try {
    fs.readFile(args.path);
} catch (e) {
    "Error: " + e.message;
}
