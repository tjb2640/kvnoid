# kvnoid (WIP)

A small, simple system for local secrets management written in Kotlin.

Secrets are stored in `.kvn` files.
These files consist of some metadata in the header and an encrypted block.
The encrypted block uses AES-256-GCM to store data at rest.
The metadata includes a `category` and `nametag` for categorization/searching,
as well as other nice-to-haves like created and modified datetimes.

I wrote this because I am a fan of trying to write one's own personal tools.
Better products exist out there to achieve secrets management, but my solution is *mine*.

## TODO
- Complete `--help` command
- CLI: spruce up visual formatting, entry filtering
- General code cleanup, security audits
- Complete unit testing for frontend CLI
- Packaging/distribution/pipelines

## Want to do
- Android application
