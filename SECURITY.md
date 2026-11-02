# Security Policy

**English** | [简体中文](SECURITY_CN.md)

## Supported versions

Security fixes land on the latest release line. If a fix is released for a report you made, upgrade to the newest version.

| Version | Supported |
| ------- | --------- |
| [Latest release](https://github.com/Conflux-Union/conflux-map/releases) | ✅ |
| Older releases | ❌ — upgrade first |

## Reporting a vulnerability

Do not open a public issue for security problems. Report privately through either channel:

- **Preferred:** the "Report a vulnerability" button on the [Security tab](https://github.com/Conflux-Union/conflux-map/security).
- Or email [contact@cxu.org.cn](mailto:contact@cxu.org.cn) with `conflux-map security` in the subject.

Include what you can: the affected version(s), steps to reproduce (world seed, config, relevant logs or captures), and the impact you see. English and Chinese are both fine. We aim to acknowledge reports within 14 days.

If the report is confirmed, we prepare a fix, release it, and publish a GitHub security advisory crediting you — unless you prefer to stay anonymous.

## Scope

- **In scope:** the client mod, the Fabric server companion, the Paper plugin, the bundled cubiomes native binaries, and the CI workflows in this repository.
- **Out of scope:** vanilla Minecraft vulnerabilities, crashes or exploits caused by other mods, and reports that are already public.
