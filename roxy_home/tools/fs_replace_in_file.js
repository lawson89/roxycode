try {
    fs.replaceInFile(args.path, args.search, args.replace);
} catch (e) {
    "Error: " + e.message;
}
