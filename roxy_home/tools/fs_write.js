try {
    fs.writeFile(args.path, args.content);
    "Successfully wrote to " + args.path;
} catch (e) {
    "Error: " + e.message;
}
