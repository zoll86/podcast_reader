# v85+v86 — Silero VAD beszédtérkép, precíz mód, kontroll-futam

## v85 — Silero VAD (beszédtérkép)
- Neurális beszédjelenlét-felismerő a készüléken (ONNX WASM, CDN-ről lusta
  betöltéssel, egyszeri ~2 MB; élő teszt: ×90 sebesség). Ha nem tölthető,
  minden automatikusan visszaáll az energia-alapú tartalékra.
- Szeletvég VALÓDI beszédszünet közepén (a hangerő-minimum zenénél tévedett).
- Beszédtérkép-ellenőrzés: ahol a mondatok közt ≥1,4 s lyuk van és a Silero
  ≥0,9 s beszédet lát benne, az bizonyított kihagyás → célzott pótlás.
- Beállítás: „beszédtérkép (Silero VAD): be/ki".

## v86 — precíz mód + kontroll-futam + prompt-kapcsoló
- Precíz szeletelés: a dekódolt szeletet beszédhatáros, ≤28 mp-es ablakokra
  bontjuk (WhisperX-féle cut&merge; az irodalom szerint a 30 mp-es dekódolási
  ablakon belül a legkevesebb a kihagyás). Kb. 8-10× több kérés — a v84-es
  ütemező kezeli. Kudarcot valló ablak automatikusan pótlásra jelölve.
- Kontroll-futam (felismerés-menü): a turbo modell prompt nélkül, 0,2
  hőmérséklettel végigmegy a teljes felvételen; ahol ≥1,2 s / ≥3 szavas
  mondatot hall, amit a fő leirat <40%-ban fed, azt a pontos modell pótolja.
- Előzmény-prompt kapcsoló: az ismétlési hurokra hajlamos fájloknál a hint
  kikapcsolható.

## Tesztek
- vadGaps, lyukkereső, cut&merge (minden ablak ≤30 mp, teljes beszédlefedés),
  senCover: node-tesztek zöldek; mindhárom script blokk node --check OK.
- Silero CDN+API élőben validálva a zoll86.github.io originon.
