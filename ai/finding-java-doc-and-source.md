# Finding Java Documentation and Source Code

The project provides utilities to access Java class and method documentation and source code directly from the Clojure REPL.

## Using the Java API Lookup tools

When working with Java interop in Clojure, you can access documentation and source code using the following functions from the `com.fulcrologic.repl.java-info` namespace:

### 1. Finding Javadoc Documentation

```clojure
;; Load the namespace
(require '[com.fulcrologic.repl.java-info :as ji])

;; Get documentation for a class
(ji/javadoc "java.util.ArrayList")

;; Get documentation for a specific method (includes all overloads)
(ji/javadoc "java.util.ArrayList" "add")

;; With custom length limits
(ji/javadoc "java.util.ArrayList" "add" {:max-method-length 500})
```

### 2. Finding Java Source Code

```clojure
;; Get source code for a class (without javadoc comments)
(ji/javasrc "java.util.ArrayList")

;; Get source code for a specific method (all overloads)
(ji/javasrc "java.util.ArrayList" "add")

;; With custom length limits
(ji/javasrc "java.util.ArrayList" nil {:max-class-length 5000})
```

### 3. Finding Classes by Simple Name

If you only know the simple class name (e.g., "ArrayList" instead of "java.util.ArrayList"), you can find the fully qualified class name:

```clojure
;; Find all classes with the simple name "List"
(ji/find-classes "List")
;; => ["java.util.List" "java.awt.List" ...]
```

## Examples

Here are some examples of how to use these tools:

### Example 1: Looking up Java HashMap documentation

```clojure
(ji/javadoc "java.util.HashMap")
;; Returns class-level documentation (truncated to 1000 chars by default)

(ji/javadoc "java.util.HashMap" "put")
;; Returns documentation for all overloaded put methods
```

### Example 2: Finding method source code

```clojure
(ji/javasrc "java.util.ArrayList" "add")
;; Returns source code for all add methods without javadoc comments
;; (truncated to 1000 chars by default)
```

### Example 3: Finding a class when you only know the simple name

```clojure
(ji/find-classes "File")
;; => ["java.io.File" ...]

;; Then you can get documentation
(ji/javadoc "java.io.File")
```

### Example 4: Listing and filtering method signatures

```clojure
;; List all public methods of ArrayList
(ji/methods "java.util.ArrayList")

;; List only methods matching a wildcard pattern
(ji/methods "java.util.ArrayList" {:pattern "add*"})
;; Shows only methods starting with "add"

;; List methods with custom length limit
(ji/methods "java.util.ArrayList" {:max-length 1000})
```

### Example 5: Using with MCP/AI tools

```clojure
;; For MCP tools that have stricter token limits, you can adjust the sizes
(ji/javadoc "java.util.ArrayList" nil {:max-class-length 500})
;; Returns a shorter summary suitable for token-limited contexts

;; For method documentation in limited contexts
(ji/javadoc "java.util.HashMap" "put" {:max-method-length 300 :max-param-length 50})
```

## Notes

- The system automatically downloads source JARs for Maven dependencies when needed
- For JDK classes, it looks for source code in the local JDK installation
- Documentation includes inherited method documentation from parent classes
- All documentation is presented in a clean, readable format suitable for REPL use
- Default length limits are applied to prevent overwhelming output:
  - Class documentation: 1000 characters
  - Method documentation: 600 characters
  - Parameter documentation: 200 characters
  - Class source code: 2000 characters
  - Method source code: 1000 characters
- All limits can be customized via the options map in the 3-arity version of functions