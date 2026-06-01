<p align="center">
  <img src="./eridanus-icon.svg" width="200" height="200" alt="Eridanus" />
</p>

# Eridanus

IRC-style chatrooms over [Reticulum](https://github.com/markqvist/Reticulum), built on [RRC](https://rrc.kc1awv.net/)

Eridanus is an Android app that lets you create and join chatrooms on the Reticulum network. Think of it like IRC, but running on a decentralized mesh - no internet, no servers, no accounts required. Rooms are hosted on shared hubs, and anyone on the network can connect.

<img width="33%" height="2430" alt="Screenshot_20260527_003331_Eridanus" src="https://github.com/user-attachments/assets/e20537ee-92a7-4e60-b552-9bfc1894e109" />
<img width="33%" height="2427" alt="Screenshot_20260527_003339_Eridanus" src="https://github.com/user-attachments/assets/cd992d86-c48c-4ace-9290-1cb819d6fc47" />
<img width="33%" height="2435" alt="Screenshot_20260527_233444_Eridanus Test" src="https://github.com/user-attachments/assets/4440bfef-a881-4c66-bc57-fb0f0a34dd19" />

## What You Can Do

- **Join chatrooms** - Browse rooms on a hub and jump in, just like IRC channels
- **Host your own hub** - Set up public or invite-only rooms with optional keys and moderation
- **Rich room modes** - Channel modes like +k (keyed), +i (invite-only), +m (moderated), +t (topic lock)
- **Nicknames and presence** - Pick a nickname, see who's in the room, get join/part notices
- **Works without internet** - Communicate over LoRa, Bluetooth LE, WiFi, or TCP - whatever path Reticulum can find
- **Private by default** - End-to-end encrypted with no central authority

## Getting Started

Download the latest release from [Releases](https://github.com/torlando-tech/eridanus/releases) and install on your Android device.

**Eridanus requires a shared Reticulum instance on your phone, so you'll need a way to connect to the Reticulum network.** The most reliable option is [Sideband](https://github.com/markqvist/Sideband) running on the same device, with the Shared Instance option enabled. As of v2.0.0-beta, [Columba](https://github.com/torlando-tech/columba) also offers a Shared Instance toggle. If you're feeling more adventurous, you can try [Reticulum for Android](https://github.com/torlando-tech/reticulum-android) which provides a configuration UI for the reference python implementation of Reticulum. If you already have RNS running on your device, Eridanus can connect to it directly, so long as it has enabled its shared instance.

## About Reticulum

[Reticulum](https://github.com/markqvist/Reticulum) is a networking stack for building resilient, encrypted communications over any medium - radio, serial, WiFi, the internet, or anything in between. It doesn't need any infrastructure and works equally well across one hop or a dozen. Eridanus uses Reticulum's link and resource system to provide real-time chatroom functionality over the mesh.

Want to learn more? Visit [Reticulum's documentation](https://reticulum.network/).

## Why "Eridanus"

Eridanus is a [constellation](https://en.wikipedia.org/wiki/Eridanus_(constellation)) representing a great celestial river winding through the southern sky. Like its namesake, the app carries messages along winding paths through the mesh - finding a way even when the direct route doesn't exist. 
> RRC exists for conversations that are ephemeral, contextual, and situational.
> -_[kc1awv](https://rrc.kc1awv.net/0-RRC-welcome.html#)_

> No man ever steps in the same river twice, for it's not the same river and he's not the same man.
> -_Heraclitus_

## License

Eridanus's own source code is licensed under the [Mozilla Public License 2.0](LICENSE).

Eridanus ships in two build flavors that bundle different Reticulum implementations under different downstream terms; the kotlin flavor bundles MPL-2.0 [reticulum-kt](https://github.com/torlando-tech/reticulum-kt); the python flavor bundles the upstream Python Reticulum under the Reticulum License, which adds field-of-use restrictions. See [NOTICE](NOTICE) for the full breakdown before redistributing a build.

## AI Development

This app has been made with AI assistance, almost exclusively Claude Opus via Claude Code. I am a software egineer by profession, but not in the mobile app space. The python backend build uses Mark Qvist's reference Reticulum implementation for all network communicati. The kotlin backend uses my own experimental Reticulum implementation, also heavily AI assisted. The kotlin backend has a smaller APK and may or may not improve your battery life while using Eridanus. 
