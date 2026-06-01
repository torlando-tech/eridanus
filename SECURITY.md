# Security Policy

Eridanus is a messaging app, so I take reports about its security seriously.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue.
Use GitHub's private reporting: go to the
[Security tab](https://github.com/torlando-tech/eridanus/security) and click
**Report a vulnerability**. That opens a private advisory only the maintainers
can see.

Useful things to include: what you found, how to reproduce it, which build
flavor (kotlin or python) and version you saw it on, and the impact as you
understand it. A proof of concept helps but isn't required.

I'll acknowledge your report as soon as I can and keep you posted while it's
worked on. If a fix ships because of your report, I'm happy to credit you in
the release notes — let me know if you'd rather stay anonymous.

## Scope

The most useful reports concern Eridanus's own code: the RRC client and hub,
identity storage, and how the app drives the Reticulum stack.

Eridanus runs on top of Reticulum, and the cryptography and transport security
live in the Reticulum implementation each flavor bundles — these are separate
projects with their own security processes:

- **python flavor** — upstream [Reticulum](https://github.com/markqvist/Reticulum)
  (the reference implementation, recommended).
- **kotlin flavor** — [reticulum-kt](https://github.com/torlando-tech/reticulum-kt),
  an experimental, largely AI-generated port. It has **not** been audited;
  treat its cryptography as unverified and prefer the python flavor for anything
  that matters. Issues specific to the port are still welcome here.

If you're unsure which project an issue belongs to, report it here and I'll help
route it.
