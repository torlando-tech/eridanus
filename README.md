<img src="./eridanus-icon.svg" width="200" height="200" alt="Eridanus" />

# Eridanus

IRC-style chatrooms over [Reticulum](https://github.com/markqvist/Reticulum).

Eridanus is an Android app that lets you create and join chatrooms on the Reticulum network. Think of it like IRC, but running on a decentralized mesh — no internet, no servers, no accounts required. Rooms are hosted on shared hubs, and anyone on the network can connect.

## What You Can Do

- **Join chatrooms** — Browse rooms on a hub and jump in, just like IRC channels
- **Create rooms** — Set up public or invite-only rooms with optional keys and moderation
- **Rich room modes** — Channel modes like +k (keyed), +i (invite-only), +m (moderated), +t (topic lock)
- **Nicknames and presence** — Pick a nickname, see who's in the room, get join/part notices
- **Works without internet** — Communicate over LoRa, Bluetooth LE, WiFi, or TCP — whatever path Reticulum can find
- **Private by default** — End-to-end encrypted with no central authority

## Getting Started

Download the latest release from [Releases](https://github.com/torlando-tech/eridanus/releases) and install on your Android device.

You'll need a way to connect to the Reticulum network. The easiest option is [Sideband](https://github.com/markqvist/Sideband) running on the same device, or you can configure a shared Reticulum instance via [Carina](https://github.com/torlando-tech/carina). If you already have RNS running on your network, Eridanus can connect to it directly.

## About Reticulum

[Reticulum](https://github.com/markqvist/Reticulum) is a networking stack for building resilient, encrypted communications over any medium — radio, serial, WiFi, the internet, or anything in between. It doesn't need any infrastructure and works equally well across one hop or a dozen. Eridanus uses Reticulum's link and resource system to provide real-time chatroom functionality over the mesh.

Want to learn more? Visit [Reticulum's documentation](https://reticulum.network/).

## Why "Eridanus"

Eridanus is a [constellation](https://en.wikipedia.org/wiki/Eridanus_(constellation)) representing a great celestial river winding through the southern sky. Like its namesake, the app carries messages along winding paths through the mesh — finding a way even when the direct route doesn't exist.
