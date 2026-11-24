# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub Security Advisories
("Security" tab -> "Report a vulnerability") instead of a public issue. Include the affected
version, a short description, and reproduction steps if possible. We will acknowledge the report
and publish a fix in a tagged release.

## Secret handling

This repository never stores signing keys, keystores, passwords, tokens, or API keys.

- Release signing material is kept private by the maintainer and supplied to the build only
  through encrypted CI secrets at run time.
- Keystores stay outside the working tree. `.gitignore` blocks `*.keystore`, `*.jks`,
  `keystore.properties`, `*.b64`, and `.env`.
- Nothing secret is printed to the build logs.

If a secret is leaked by mistake, rotate it immediately and remove it from history before
publishing anything else.
