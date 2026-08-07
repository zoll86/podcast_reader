# APK-építés — mit hova

Három kis fájl van a repóban ehhez:

1. `.github/workflows/android.yml`  ← a felhő-építő recept
2. `package.json`                    ← a Capacitor függőségei
3. `capacitor.config.json`           ← az app neve/azonosítója

Az Android-projektet NEM tároljuk a repóban — a GitHub Actions generálja
minden építésnél.

## Használat
- Minden push után az Actions fül alatt elindul az "Android APK" munka
  (~6-8 perc).
- A kész APK a repó **Releases** oldalán jelenik meg: "apk-latest" →
  `ketnyelvu-hallgato.apk` → letöltés a telefonra → telepítés
  (ismeretlen forrás engedélyezése egyszer).
- Kézzel is indítható: Actions fül → Android APK → Run workflow.

## Mit tud ez az 1. fázis
A mostani app fut natív burokban, saját ikonnal, Play Store nélkül. A
2. fázis (RSS CORS nélkül) és a 3. fázis (mappa-alapú könyvtár, SAF)
külön kör — a burok ehhez készen áll.
