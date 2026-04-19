---
paths:
  - "**/*.java"
---

# Code Conventions

## Acronym Naming

Always use full uppercase for acronyms in Java identifiers — class names, method names, field names, local variables. Do NOT use title-case (camelCase-with-only-the-first-letter-uppercase) for acronyms.

### Examples

| Wrong | Right |
|-------|-------|
| `JsonBodySupplier` | `JSONBodySupplier` |
| `XmlParser` | `XMLParser` |
| `HttpRequest` | `HTTPRequest` |
| `ApiClient` | `APIClient` |
| `JwtToken` | `JWTToken` |
| `UrlBuilder` | `URLBuilder` |
| `SqlQuery` | `SQLQuery` |
| `parseXml()` | `parseXML()` |
| `toJson()` | `toJSON()` |
| `getHttpStatus()` | `getHTTPStatus()` |

### Scope

Applies to:
- Class and interface names
- Method and constructor names
- Field and local variable names where the acronym appears
- Enum constants (usually fine — they're already uppercase)

Package names remain all-lowercase per standard Java convention (e.g., `org.lattejava.web.json`, not `org.lattejava.web.JSON`).

### Why

Matches the convention already used throughout Latte Java (`HTTPRequest`, `HTTPResponse`, `HTTPServer`, `HTTPMethod`, etc.) and the JDK (`URL`, `URI`, `HTTPServer`). Title-cased acronyms (`Http`, `Json`) are easy to mis-read and inconsistent with the rest of the codebase.

## Alphabetization

Alphabetize whenever ordering is not otherwise meaningful. Default to alphabetical order for any list-style construct where the elements are peers.

### Applies to

- Field declarations within a class (grouped by visibility, then alphabetized)
- Method declarations within a class (alphabetized within their visibility/kind group)
- Import statements (alphabetized within each import group)
- `requires` clauses in `module-info.java`
- `exports` clauses in `module-info.java`
- `opens` clauses in `module-info.java` (and the targets after `to` are alphabetized too)
- Entries in enum declarations (unless order carries semantic meaning)
- Constants grouped together
- Dependencies in `project.latte` within a group

### Does not apply when

- Ordering carries semantic meaning (e.g., middleware execution order, route registration order, pipeline stages)
- Dependency groups where transitive-dependency ordering matters (rare)
- Constructor parameter order (typically dictated by usage patterns, not alphabetic)

### Example

```java
// Wrong
module org.lattejava.web {
  requires java.net.http;
  requires org.lattejava.http;
  requires com.fasterxml.jackson.databind;
}

// Right
module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
}
```
