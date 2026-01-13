# Coding Standards & Guidelines

## 1. Logging Policy
- **Rule ID:** LOG-001
- **Severity:** Critical
- **Description:** Never use `println` or `System.out.print` for logging. Always use `org.slf4j.Logger`.
- **Reasoning:** Standard output cannot be properly routed to log aggregation systems (ELK, Splunk) and lacks severity levels.

## 2. Hardcoded Secrets
- **Rule ID:** SEC-001
- **Severity:** Critical
- **Description:** API keys, passwords, and tokens must never be hardcoded in source files.
- **Reasoning:** Source code is often shared or stored in version control. Use environment variables or `local.properties`.

## 3. Exception Handling
- **Rule ID:** ERR-002
- **Severity:** Warning
- **Description:** Empty `catch` blocks are forbidden. At minimum, log the exception.
- **Reasoning:** Silencing exceptions makes debugging impossible.

## 4. Layer Architecture
- **Rule ID:** ARCH-001
- **Severity:** Major
- **Description:** Domain logic should not depend on Framework SDKs (e.g. Android SDK, Spring).
- **Reasoning:** Keeps domain logic testable and portable.
