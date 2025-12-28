let gitArgs = ["git", "diff"];

if (args.cached) {
  gitArgs.push("--cached");
}
if (args.path) {
  gitArgs.push(args.path);
}

const result = shell.exec(gitArgs);

if (result.exitCode === 0) {
  if (result.stdout.trim().length === 0) {
    return "No changes found.";
  }
  return result.stdout;
} else {
  return "Error running git diff:\n" + result.stderr;
}