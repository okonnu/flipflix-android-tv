# Website-to-native experiment

The catalog portion was straightforward. In Chrome, the public website showed conventional data-backed screens: a home hero and rows, filterable movie and TV grids, search, title details, seasons, and episodes. Public JSON endpoints exposed the metadata needed to render a functional native prototype. A focused Android project could display those responses without using a WebView for the catalog.

The difficult part was playback. Sampled movie and TV pages placed third-party players in iframes. The observed source selector mapped to different embed hosts (for example, VidBolt, Bingr, and Vidrift), rather than exposing a stable direct media URL. A native source selector is easy to build, but each selected player remains web content, with its own ads, availability, remote-control behavior, and terms. A fully native player would require a permitted HLS/DASH or comparable media source.

Fire TV also changes the interaction model. A desktop's mouse-hover and scrolling behavior cannot simply be copied. This prototype uses focusable native cards, buttons, lists, and back navigation. The iframe itself remains the one area whose D-pad experience depends on the provider.

The conclusion for the essay is that AI makes a credible native catalog prototype quick to create, but it does not solve data ownership, stream rights, reliable playback, provider dependencies, or physical-remote testing. This APK demonstrates the distinction rather than claiming full equivalence to the website.
