# Lucid — a floating translator for Android

A small glass lens that floats over every app on your phone. Drag it like a cursor; the
moment it stops, it reads the text underneath and translates it in place. No screen
recording, no "choose one app", no per-app setup.

---

## Install on your phone

Download **`Lucid-1.0-debug.apk`** from the [latest release](https://github.com/MatrixZockt/Lucid-ScreenTranslator/releases/latest)
(67 MB, arm64 + x86_64). Open it on the phone, or with a USB cable:

```bash
adb install -r Lucid-1.0-debug.apk
```

It is a debug build, so Android will ask you to allow installing from this source the first
time.

First launch asks for two switches, once:

1. **Display over other apps** — so the lens can float on top of anything.
2. **Text access** — Android's Accessibility permission for Lucid. This is what lets the
   lens read the text of whatever app you are in, without capturing pixels. It applies to
   the whole phone, not one app.

Text access is asked in the open, before Android's switch: the sheet says exactly what
the lens reads (the words under it, never the rest of the screen, with no copy kept) and
that with **ENGINE: Online** those words are sent to Google Translate, along with the
rest of that screen as context. You tap **I agree** first; only then does the switch
appear. Declining leaves the app usable, and asking for text access again brings the
note back.

Then press **Start the lens**.

- **Drag** the lens anywhere — it reads ~0.3 s after you stop moving. The words it picked
  are washed in mint the way Google Lens marks a selection: one tight band per line, ending
  where the words end, so you can see at a glance that the right thing was read.
- While it works, a lit piece of the button's own border runs around it — no track, no gap,
  just the edge lighting up and travelling. The card's status reads READING or TRANSLATING
  with its own small spinner, so there is never a silent pause.
- **Touch anywhere else, scroll, or move the lens and the card and highlight disappear
  together.** A translation only sits on screen while it is the thing you are looking at,
  and the touch still reaches the app underneath. That holds while it is *still reading*
  too: a press outside cancels the read where it stands and the card never arrives, so you
  are never made to wait for an answer you no longer want.
- **Tap** it to open the translation options — the language list over a dimmed screen. The
  pick is saved, so everything the lens reads from then on lands in that language, and a
  press anywhere outside the list puts it away again. A tap never reads.
- **Long-press** it to pause: the lens fades to a ghost until you long-press again. While it
  is paused, a tap just reminds you how to wake it.
- With **AUTO-TRANSLATE** switched off there is no hover read at all: the read happens when
  you let go of the lens after moving it.
- Language and engine live in the app, never on the lens.
- The notification (or **Turn the lens off** in the app) ends the session.
- **Quick-settings tile:** pull the shade down, tap the ✎, and drag **Lucid lens** up into
  your tiles. Tapping it starts or stops the lens — no need to open the app.

### Translation engine

`ENGINE` in the app picks how text is translated:

- **Online** (default) — Google's translation service. Best quality, especially for
  Japanese, Chinese and Korean. The text under the lens is sent off the phone.
- **On-device** — ML Kit models, downloaded once and then offline. Nothing leaves the
  phone, but the smaller models are occasionally clumsy on complex sentences.

If the online service is unreachable the card says so instead of showing a rough guess.

## How it reads your screen

Lucid asks Android's accessibility tree "what text is at this point?" instead of taking
screenshots:

- **No recording, no screen-share dialog.** Accessibility is granted once in Settings and
  stays granted; there is no MediaProjection session and no per-app picker.
- **Text, not pixels.** It gets the exact string plus its bounds, so the highlight sits
  exactly on the text and timestamps/tick marks can be dropped from chats.
- **The wash is cut from the app's own geometry when the app publishes it.** Android lets
  a service ask a text node where each of its characters sits — the channel TalkBack's
  magnifier uses — and most native views answer, so on those the band is exact: gathered
  into lines, from the first glyph to the last in the run. Chrome answers that request with
  the whole line's rect for every character, which is not geometry, so that answer is
  refused and the band is fitted from the node's box instead: a box the text exactly fills
  gives the type size outright, and a box the text barely fills (a padded control, or a
  block a page stretched to its column) is measured against the app's own line spacing.
  Web views publish no type size at all, so on a page whose CSS line height is unusual the
  band can be a little short or loose — it is never off the run it read.
- **Only the text under the lens.** It takes the single text node the lens is sitting on — a
  chat bubble, a message, a paragraph — and translates that. It never widens to the
  surrounding block or section, so nothing bigger than what you pointed at is read. If you
  land in the gap between two lines it looks a few millimetres around the point and takes the
  nearest text, so the cursor has no dead zones.
- **One language at a time.** A line can mix scripts — “I love 日本語 so much”. Only the run
  under the lens is read: hover the Japanese and the card holds the Japanese, hover the
  English and it holds the English. Punctuation and spaces bridge two stretches of the same
  script, so an ordinary sentence or a whole paragraph still comes through in one piece.
- **Punctuation travels with the text.** The run under the lens keeps the marks that touch
  it, `？` included. A Japanese line needs that: without the mark, `彼は来ます？` reached the
  engine as a statement; with it the card reads **Will he come?**.
- **The card tells you which engine ran** — `online` or `on-device` in its footer.
- **`Copy` hands over the scanned words**, not the translation: the message as it was
  written, in its own script, ready to paste somewhere else.
- **On-device mode keeps everything local.** Language detection and translation run in
  ML Kit, with a model downloaded once per language pair.
- **Look mode** (optional) adds OCR through MediaProjection for surfaces with no text to
  read, see below.

### Japanese: the reading above the kanji

When the online engine translates Japanese, the card's original line gains the *reading* of
the sentence in small, faint kana, centred over the kanji they belong to — the way a
Japanese book prints ruby:

The engine hands back the reading of the whole sentence in Latin letters
(`Kono mise wa totemo yoidesu.`), never which kanji it belongs to. Japanese supplies the
missing half: the kana written in the sentence are identical in the reading, in the same
order, so they act as anchors — a kanji takes exactly the reading between the kana around
it, and kanji with nothing pronounceable between them share one stretch, split in
proportion. Where it can be placed honestly, it is; a kanji whose reading cannot be
resolved stays bare rather than being guessed at.

The respelling is a conversion, not a dictionary: particles are matched by sound (は is
spoken わ) and long vowels spelled out (ō → おう, so 学校 → がっこう). It knows that
"nihon" is にほん, never that 日本 is read that way. On-device mode publishes no reading at
all, so the card is plain there — this needs **ENGINE: Online**.

### Japanese: word by word

Japanese is written without spaces, so a translated line on its own leaves no way to tell
which word means what. Underneath the original, the card therefore grows a short list — one
word per line, the word in mint, its meaning beside it:

```
日本語        Japanese
勉強しています  I'm studying
```

The split comes from the writing itself, not from a dictionary of the language: a run of
kanji takes the kana welded onto it (食べる, 飲みます), a katakana run stands alone
(コーヒー), and a kana run that is only a particle (を, が, に) is dropped — as is a particle
glued to a noun (東京から is 東京, then a particle). Latin words and digits are left out;
they already read as themselves. All the words then travel in **one** request, one per line,
and the engine hands back one line of meaning each.

If that answer does not come back with exactly as many lines as there were words, nothing
is drawn: a word is never shown a meaning that belongs to its neighbour. At most ten words
are asked about and six are shown, so a paragraph stays a paragraph and not a glossary. The
list needs **ENGINE: Online**, arrives a moment after the translation itself, and disappears
with the card.

### Changing the language from the card

Both chips in the card are live: tap the target chip (`English ▾`) to translate the same
text into another language, or the source chip (`Japanese ▾`, which also offers *Auto
detect*) if the language was guessed wrong. The sheet dims the screen and closes itself
when you pick.

The change applies to **that card only** — the footer gains a `temporary` marker and the
target chip loses its mint fill. Nothing is saved: drag the lens to the next thing and it
translates into the language chosen in the app again. For a change that sticks, tap the lens
itself — that list saves what you pick — or use `TRANSLATE INTO → Change` in the app.

### Look mode (optional)

Some surfaces (images, games, video, apps that hide their text) have nothing in the
accessibility tree. **Look mode** adds a screen-capture fallback: same lens, but it asks
for the *whole display* — never a single app — and takes one still frame per read, runs
OCR on-device, and keeps nothing. It is off by default and only prompted for when you turn
it on.

## Build from source

Requirements: JDK 17, Android SDK with platform 36 and build-tools 36, and network access
for the first Gradle run.

```bash
git clone https://github.com/MatrixZockt/Lucid-ScreenTranslator.git
cd Lucid-ScreenTranslator/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your SDK path
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/Lucid-v1.0.0-v1-debug.apk
```

Stack: Kotlin, plain Android views (no Compose), an `AccessibilityService` for text,
`TYPE_APPLICATION_OVERLAY` windows for the lens/frame/card, ML Kit for translation (and
OCR in look mode), coroutines for the pipeline.

### Trying it without a chat app

`DemoChatActivity` is a full-screen messaging surface (bundled HTML) for trying the lens
without a real chat app:

```bash
adb shell am start -n com.grafizum.lucid/.DemoChatActivity
```

## Policies site

The Play Store listing needs a public privacy policy, so the app's legal pages live at
**https://lucid.grafizum.com** (`site/`, served as the Cloudflare Worker `lucid-legal`
from `wrangler.toml`):

- https://lucid.grafizum.com/privacy — what the lens reads, where text goes, what is kept
- https://lucid.grafizum.com/terms — terms of use, Swiss law

```bash
npx wrangler deploy     # uploads site/ and keeps lucid.grafizum.com attached
```

## Project layout

```
android/                      the app
  app/src/main/java/com/grafizum/lucid/
    MainActivity.kt              permissions + start/stop the lens
    LensService.kt               foreground service: bubble, selection, card, pipeline
    LensAccessibilityService.kt  "what text is at this point?" via the a11y tree
    HighlightView.kt             the mint selection wash, one band per line
    Romaji.kt                    romaji -> hiragana, for the readings
    Furigana.kt                  lines a reading up with the kanji it belongs to
    RubyTextView.kt              keeps room above the line and draws that kana there
    TextLines.kt                 text box -> per-line bands, from the app's
                                 character boxes or a fitted type size
    TextRun.kt                   narrows a mixed-script line to the run under the lens
    SightView.kt                 crop frame for look mode
    ScreenCapturer.kt            MediaProjection -> one cropped still (look mode)
    VisionTranslator.kt          clean-up -> language ID -> translation (OCR in look mode)
    LanguagePicker.kt            target language list
    DemoChatActivity.kt          demo chat surface
  app/src/main/assets/demo-chat.html
site/                         policies site served at lucid.grafizum.com
wrangler.toml                 Worker lucid-legal + the custom domain
desktop/                      earlier desktop prototype (Electron), kept for reference
store-listing/                Play Store listing copy and art
```

## Limits worth knowing

- **Android only.** iOS does not allow floating overlays over other apps.
- **Text access needs text.** Chat apps, browsers, settings and most native apps expose it;
  images, video and some games do not — that is what Look mode is for.
- The APK is a **debug build** (no Play Store signing, no minification).
- Tested on an Android 15 emulator (1080×2400, Pixel 7a profile), not yet on a physical
  phone. Some manufacturers kill accessibility services aggressively; if the lens stops
  reading, check that "Lucid text access" is still switched on.
- Machine translation is good, not perfect: "portería" came back as "goal" in one test
  instead of "concierge desk".

## The desktop prototype

`desktop/` holds an earlier Electron version of the same idea for Linux/macOS/Windows
(floating lens, one-shot capture, local Tesseract OCR, translation card). It works and has
its own preview script, but the phone is what this project targets. Run it with
`npm install && npm start` inside `desktop/`.

## Licence

© 2026 Grafizum. The source is published so that you can read it, build it and install it for
yourself; it is not released under an open-source licence and is not licensed for
redistribution. The name, the design and the code stay ours — the full terms are at
[lucid.grafizum.com/terms](https://lucid.grafizum.com/terms). Open-source components bundled
with the app remain under their own licences.
