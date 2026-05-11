<p align="center">
  <img src="./eridanus-icon.svg" width="200" height="200" alt="Eridanus" />
</p>

# Eridanus

IRC-style chatrooms over [Reticulum](https://github.com/markqvist/Reticulum), built on [RRC](https://rrc.kc1awv.net/)

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

**Eridanus requires a shared Reticulum instance on your phone, so you'll need a way to connect to the Reticulum network.** The most reliable option is [Sideband](https://github.com/markqvist/Sideband) running on the same device, with the Shared Instance option enabled. If you're feeling more adventurous, you can try [Reticulum for Android](https://github.com/torlando-tech/reticulum-android) which provides a configuration UI for the reference python implementation of Reticulum, or if you really like living on the edge, you can try [Carina](https://github.com/torlando-tech/carina), which uses the experimental kotlin implementation of Reticulum, [reticulum-kt](https://github.com/torlando-tech/reticulum-kt). If you already have RNS running on your device, Eridanus can connect to it directly.

## About Reticulum

[Reticulum](https://github.com/markqvist/Reticulum) is a networking stack for building resilient, encrypted communications over any medium — radio, serial, WiFi, the internet, or anything in between. It doesn't need any infrastructure and works equally well across one hop or a dozen. Eridanus uses Reticulum's link and resource system to provide real-time chatroom functionality over the mesh.

Want to learn more? Visit [Reticulum's documentation](https://reticulum.network/).

## Why "Eridanus"

Eridanus is a [constellation](https://en.wikipedia.org/wiki/Eridanus_(constellation)) representing a great celestial river winding through the southern sky. Like its namesake, the app carries messages along winding paths through the mesh — finding a way even when the direct route doesn't exist. 
> RRC exists for conversations that are ephemeral, contextual, and situational.
> -_[kc1awv](https://rrc.kc1awv.net/0-RRC-welcome.html#)_

> No man ever steps in the same river twice, for it's not the same river and he's not the same man.
> -_Heraclitus_
