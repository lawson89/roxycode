# Important notes for LLMs working with this project

## js environment
- You have at your disposal the ability to run javascript scripts in a secure sandboxed environment.
- The tool to use to run a script is called run_js
- The run_js tool accepts a single argument called script which is the javascript code to execute.
- The javascript environment has access to various services via global objects, the details will be provided in the system message or cached context.

// Service: gitService
const gitService = {
  /** Returns the current Git branch name */
  getCurrentBranch(projectRoot: any): string,

  /** Returns the Git status in porcelain format */
  getStatus(projectRoot: any): string,

  /** Returns the Git log for a path with a specified limit on the number of entries */
  log(projectRoot: any, path: string, limit: number): string,

  /** Returns the Git diff, optionally cached (staged) and for a specific path */
  diff(projectRoot: any, cached: boolean, path: string): string,

};

// Service: tikaService
const tikaService = {
  /** Extracts both text and metadata from an input stream using Tika */
  extractAll(inputStream: any): any,

  /** Extracts text content from an input stream using Tika */
  extractText(inputStream: any): string,

  /** Reads a document from a path and extracts its text content using Tika */
  readDocument(path: any): string,

};

// Service: previewService
const previewService = {
  /** Compiles the project, launches it, takes a screenshot, and returns the path to the image */
  launchAndScreenshot(): string,

};

// Service: buildToolService
const buildToolService = {
  /** Returns the contents of AGENTS.md and the tool API documentation */
  getAgentsContents(): string,

  /** Returns the operating system name */
  getOperatingSystem(): string,

  /** Returns the contents of the project build file (pom.xml, build.gradle, etc.) */
  getBuildFileContents(): string,

  /** Returns the project structure (modules/subprojects) */
  getProjectStructure(): string,

  /** Returns the contents of the project README file */
  getReadmeContents(): string,

  /** Runs the tests for the current project */
  runTests(): string,

  /** Returns the dependency tree for the current project */
  getDependencyTree(): string,

  /** Returns the effective build configuration (e.g., effective POM for Maven) */
  getEffectiveConfig(): string,

  /** Checks the health of the project dependencies */
  getDependencyHealth(): string,

  /** Returns a comprehensive summary of the project, including build info, files, and readme */
  getProjectSummary(): string,

  /** Detects the current build tool */
  detect(): any,

  /** Compiles the current project */
  compile(): string,

};

// Service: xmlService
const xmlService = {
  /** Analyzes an XML file and returns a structural summary of its elements */
  analyzeFile(path: any): any,

  /** Returns the XML source code of an element selected by an XPath expression */
  getElementSource(path: any, xpathExpr: string): any,

  /** Replaces an XML element selected by an XPath expression with new XML content */
  replaceElement(path: any, xpathExpr: string, newXml: string): void,

  /** Updates an attribute of an XML element selected by an XPath expression */
  updateAttribute(path: any, xpathExpr: string, attrName: string, attrValue: string): void,

};

// Service: grepService
const grepService = {
  /** Searches for a regex pattern in files matching a file pattern within a directory */
  grep(patternStr: string, pathStr: string, filePattern: string): string,

};

// Service: javaService
const javaService = {
  /** Analyzes a Java file and returns a summary of its classes, methods, and imports */
  analyzeFile(pathStr: string): any,

  /** Returns the list of classes that the specified class depends on (extends, implements, fields, method parameters, local variables) */
  getClassDependencies(pathStr: string, className: string): Array<any>,

  /** Returns the source code of a specific method in a class */
  getMethodSource(pathStr: string, className: string, methodName: string): any,

  /** Replaces the source code of a specific method in a class */
  replaceMethod(pathStr: string, className: string, methodName: string, newMethodSource: string): void,

  /** Returns the source code of a specific field in a class */
  getFieldSource(pathStr: string, className: string, fieldName: string): any,

  /** Replaces the source code of a specific field in a class */
  replaceField(pathStr: string, className: string, fieldName: string, newFieldSource: string): void,

  /** Initializes the Java analysis engine */
  init(): void,

};

// Service: tomlService
const tomlService = {
  /** Analyzes a TOML file and returns a structural summary of its elements */
  analyzeFile(path: any): any,

  /** Writes a JsonNode to a file in TOML format */
  write(path: any, content: any): void,

  /** Reads a TOML file and returns its content as a JsonNode */
  read(path: any): any,

};

// Service: sierraPreviewService
const sierraPreviewService = {
  /** Use this method to validate a Sierra file. Returns a string indicating whether the file is valid or not. */
  validateSierra(path: string): string,

  /** Use this method to generate a preview image of a Sierra file. Returns the path to the generated PNG image. */
  previewSierra(path: string): string,

  close(): void,

};

// Service: fileSystemService
const fileSystemService = {
  /** Replaces occurrences of a string in a file with another string using regex */
  replaceInFile(path: string, search: string, replace: string): string,

  /** Reads the contents of multiple files and returns them as a single string */
  readFiles(paths: Array<any>): string,

  /** Writes the given content to a file at the specified path */
  writeFile(path: string, content: string): void,

  /** Reads the content of a file at the given path */
  readFile(path: string): string,

  /** Deletes the file or directory at the given path */
  delete(path: string): void,

  /** Lists files in a directory matching a pattern, optionally recursive */
  listFiles(path: string, pattern: string, recursive: boolean): string,

  /** Returns a visual tree representation of the directory structure */
  tree(path: string): string,

};

