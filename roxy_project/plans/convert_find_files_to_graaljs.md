# Plan: Convert `find_files` from Groovy to GraalJS

## 1. Understand Current Functionality:
   - The existing `roxy_home/tools/find_files.groovy` script uses `fs.listFiles(path, pattern, recursive)` to find files matching a regex pattern within a given path, recursively.
   - The script content is: `return fs.listFiles(args.path ?: ".", args.pattern, true)`

## 2. Identify GraalJS Equivalents:
   - It's assumed that the `fs` object and its `listFiles` method are part of the Roxy environment and will be available in the GraalJS execution context. The call structure should remain largely the same.

## 3. Conversion Steps:

   a. **Create `find_files.js`:**
      - Create a new file at `roxy_home/tools/find_files.js`.

   b. **Translate Groovy to GraalJS:**
      - Translate the Groovy script `return fs.listFiles(args.path ?: ".", args.pattern, true)` to GraalJS.
      - The Groovy Elvis operator `?:` should be replaced with the JavaScript logical OR operator `||`.
      - The GraalJS equivalent will be:
        ```javascript
        return fs.listFiles(args.path || ".", args.pattern, true);
        ```
      - Write this content into `roxy_home/tools/find_files.js`.

   c. **Update `find_files.toml`:**
      - Modify the `roxy_home/tools/find_files.toml` file to point to the new `find_files.js` script. This involves changing the `script` field within the TOML file.
      - The `script` field should be updated from `script = "find_files.groovy"` to `script = "find_files.js"`.

   d. **Test:**
      - After the conversion, perform a test to ensure the `find_files` tool functions correctly with the GraalJS implementation. This would involve invoking the `find_files` tool with various paths and patterns and verifying the output.

## 4. Rollback Plan (if needed):
   - In case of any issues or unexpected behavior after the conversion, revert the changes made in `roxy_home/tools/find_files.js` and `roxy_home/tools/find_files.toml` to restore the original Groovy version.
