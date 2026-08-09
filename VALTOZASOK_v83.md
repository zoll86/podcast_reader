# v83 — a felismerő kihagyásainak gyökerei ellen (PCM-előfeldolgozás + minőség-jelek)

## Mérnöki diagnózis (miért hagyott ki mondatokat a mostani lánc)

1. **MP3 bit-tartalék (bit reservoir).** A Layer-III keret hangadata részben a
   KORÁBBI keretek szabad helyén ül (main_data_begin). A kerethatáron vágott
   szelet dekódolható, de az első ~0,1–0,5 mp torz/néma. A pótló ablakok pont a
   lyuk elejéről indultak — a visszakérdezett mondat ELEJE sérülten ment ki.
2. **Nincs hangerő-kezelés.** A Whisper a hangos zene/beszéd melletti halk
   megszólalást no_speech-nek nézi és átugorja. Ez volt a mért 8:09–8:35-ös
   kimaradás típusa is.
3. **Szelethatár-veszteség.** A szelet végi, írásjel nélküli mondatot a kód
   eldobta, és a következő szelet csak 1,5 mp átfedéssel indult — ha a csonka
   mondat ennél korábban kezdődött, az eleje SOHA nem került be egyetlen
   kérésbe sem. Ráadásul a Whisper gyakran írásjel nélkül ír, tehát az eldobás
   TELJES mondatokat is vitt.
4. **Vak időpontú vágás.** A nyers vágás mondat közepén tört, ami csonka
   változatokat szült, amiket aztán a 70%-os ismétlés-szűrő elnyelt.
5. **A minőség-jelek eldobása.** A verbose_json szegmensenként adja az
   avg_logprob / compression_ratio / no_speech_prob értékeket — a Groq
   megmondta, HOL bizonytalan, de a kód nem nézte.

## Változások

- **PCM-út (cutSmart):** a szelet a készüléken dekódolódik (150 mp-es
  darabokban, ~90 MB memóriacsúcs alatt), a ráfutás MINTÁRA levágva → a
  bit-tartalék szemete sosem megy ki. Blokkonkénti AGC (0,4 mp, cél-RMS 0,12,
  max 6×, néma blokk érintetlen) felhozza a halk beszédet. Kimenet: 16 kHz
  mono WAV (a Whisper bemenete úgyis ez — minőségvesztés nincs).
- **Csendhatárra igazított szeletvég:** az első futam szelete a célidő előtti
  legcsendesebb ponton ér véget; a done-könyvelés a tényleges végig megy.
  Ha a vágás valódi csenden történt, a szelet végi mondat NEM dobódik el.
- **QC-jelek:** a gyanús szegmensek (compression_ratio>2,4, avg_logprob<-1,1,
  vagy no_speech>0,5 és avg_logprob<-0,6) a futam végi automatikus pótló körbe
  kerülnek, a hézagokkal uniózva (legfeljebb 40 folt).
- **Pótló ablakok:** geometriájuk érintetlen (a v82-es rácsmérés eredménye),
  de mostantól tiszta, erősített WAV megy ki belőlük.
- **Beállítás:** „hang-előkészítés: javított (WAV) / nyers szelet" kapcsoló a
  szeletméret alatt; 3 dekódolási hiba után a menet automatikusan visszaáll a
  nyers útra (a felismerés sosem áll meg emiatt).

## Tesztek
- agcF32: halk 0,021→0,118 RMS, hangos érintetlen, néma néma marad, csúcs<0,98
- quietCutIdx: csendet a helyes sávban találja, folyamatos beszédnél quiet=false
- wavF32: RIFF/16 kHz/mono/16 bit fejléc helyes
- mind a 3 script blokk: node --check OK
