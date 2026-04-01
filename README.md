# kvnoid (WIP)

A small, simple system for local secrets management written in Kotlin.
Minimal dependencies, uses `javax.crypto` and `kotlin.io`.

Secrets are stored in `.kvn` files.
These files are a little paranoid.
They consist of some plaintext metadata in the header
and three pieces of encrypted information:
- Category (<= 128 Bytes)
- Nametag (<= 128 Bytes)
- Encrypted value (<= 2048 Bytes)

Each of these are encrypted piecemeal using AES-256-GCM best practices
(SecureRandom, unique IV, salted),
with part of each key being derived from a "vault key" pre-known plaintext
entered by the user when the program launches.

## Why this exists 
I wrote this because I am a fan of trying to write one's own personal tools.
Better products exist out there to achieve secrets management, but my solution is *mine*.

## TODO
- Complete `--help` command
- General code cleanup, security audits
- Complete unit testing for frontend CLI
- Packaging/distribution/pipelines

## Want to do
- Android application
