# F1 TV Viewer

Android TV application to watch content from [F1 TV](https://f1tv.formula1.com).

At this time, content from the current session and old sessions are available, including F1, F2, F3...
Onboard channels when available are supported, as well as technical channels like the tracker channel,
and on future I will bring documentaries (I hope).

This app currently use the F1TV 3.0 APIs and support stream content of 1080p at 50FPS

Audio selection (language or no commentary) is supported.

Resolution selection (from 270p to 1080p) is available, however the app knows based on bandwidth what
is the best resolution to pickup, this is built in with ExoPlayer.

Closed captions supported in English, Spanish, Dutch and German.

Application is available in English, Dutch, German, French, Portuguese, Russian and Spanish.
Feel free to translate it and open a PR :D, also to update some translations that are not fully ready.

## Install (as of 15 Feb 2022)

F1 has "requested" me to remove the app from Google Playstore, so that means that the only way to install the app now is sideloading the APK.
For the users that already have the app installed nothing changes, but you wont get updates via Google Play anymore.

## Fire Sticks / TVS

The app is supported on fire sticks / tvs, however due some technicalities I can't offer
the app directly on Amazon store please refer to the [releases page](https://github.com/leonardoxh/race-control-tv/releases)
to download the latest apk and side load it.

## Open beta testing
The app itself is always sort of "beta", however you can join the public app beta (on playstore) by clicking
on the following link (on your web browser) https://play.google.com/apps/testing/com.github.leonardoxh.f1
by joining the beta you'll receive the features before anyone else, but it might mean that the app is uninstable.

## New features PR

I normally accept new features in PRs that bring improvements to the app, however, due some last recent
bugs introduced in some PRs where I had to revert literally the whole PR for new features please create a github issue
first before any code change and try to describe what you want to do as detailed as possible so we can chat on the issue
and see if this is something we want to onboard or not, be in mind also that we do not work in this app full time, so
the features have to be very well tested, so everyone can enjoy the races.

Also be in mind that the code of the app needs an extensive refactor.

## App is buffering all the time, can't get content at 1080p

Normally that is not an app issue, as you might know we simply use the content that is available on F1TvPro, most of the cases
I'd say 99.99% of the cases this is **related to problems on the API that we cannot fix**, unless it is a major outage that is happening to 
everyone using the app, there is simply nothing I can do. As a workaround try to enable the option to play with external players and use a
external player like VLC. Issues that are open with these subjects will be closed.

## Screenshots

![Browse current season](/screenshots/season_browse.png)

![Browse sessions of an event](/screenshots/event_sessions_browse.png)

![Channel selection of a multi-channel session](/screenshots/session_channel_selection.png)

![Channel playback](/screenshots/channel_playback.png)

![Channel audio selection](/screenshots/channel_audio_selection.png)

## Disclaimer

I have created this app because the official [F1 TV app](https://play.google.com/store/apps/details?id=com.formulaone.production)
does not officially support Android TV and even after sideloading it, it's not usable with a remote.
The official website is also not easily usable with a remote. If in the future an official application
would be available, I will happily abandon this one.

This will always be a free and open source app.

## Credits

First of all thank you [Groggy](https://github.com/Groggy) the original creator of this project that I forked without you this would never be possible.

Thanks to all contributors of [f1viewer](https://github.com/SoMuchForSubtlety/f1viewer) for their work on how to use the F1 TV API.

Thanks to my friend [Thiago Andrade](https://github.com/ttandrade) for the exclusive icons and color guidelines.

## Donations

[![Donations](https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png)](https://www.buymeacoffee.com/lrossett)
