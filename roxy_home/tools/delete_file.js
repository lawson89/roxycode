try {
    fs.delete(args.path);
    "Successfully deleted " + args.path;
} catch (e) {
    "Error: " + e.message;
}
