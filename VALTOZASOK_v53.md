# VÁLTOZÁSOK — v52 → v53: BUBORÉK = MONDAT

Az alapszabály: az angol buborékban és az alatta lévő magyarban MINDIG ugyanaz
a mondat áll, és a fordítási kérésbe is mondat megy — vágni csak mondathatáron
szabad.

## 1. A mondat a fizikai egység (ÚJ: sentencize + splitFrag)
A rec.sen elemei mondatok, nem felismerő-szeletek:
- a felismerő által szétvágott mondat töredékei EGY töredékké olvadnak
  (nem csak közös fordítási kulcsot kapnak — az angol sáv sem lapoz fél
  mondatokat), a szó-időbélyegek összefűzve öröklődnek a karaokéhoz;
- a több mondatot hordozó töredék mondatonként szétválik, az időket a
  szó-időbélyegek adják;
- automatikusan fut minden beolvasztásnál; kész magyar szöveget kérdés
  nélkül soha nem dob el.

## 2. Veszteségmentes migráció megnyitáskor
A régi felvételek breakLong-darabjai (közös full) megnyitáskor csendben egy
mondattá olvadnak: az egyesített szövegük pont a full, ami eddig is a
fordítási kulcs volt — a kész magyar egy betű újrafordítás és egyetlen
API-hívás nélkül átöröklődik. A buborék = mondat a meglévő felvételeken is.

## 3. Mondaton belül nem tördelünk
- breakLong és pushSen: valódi (írásjeles) mondat egyben marad, akármilyen
  hosszú. Egyetlen kivétel az írásjel nélküli szalag (dal): MAXF=60 szó fölött
  ott muszáj vágni, és a darabok KÜLÖN egységek (v46 tanulsága).
- huAlign: a tagmondat-alapú magyar blokk-faragás megszűnt — egy fordítási
  egység = egy magyar buborék. A magyar sosincs másképp tördelve, mint az angol.

## 4. Kézi menüpont átalakítva
„mondatok összefűzése a fordításhoz" → „mondatokra rendezés (buborék =
mondat)": összeolvaszt ÉS szétválaszt, force-szal, csak az érintetteket
fordítja újra. A régi, fordított csonka futamokat is ez rendezi.

## Ellenőrzés
17 egységteszt zöld; mindkét fixture-ön (170 + 206 töredék) a lánc után
0 több-mondatos buborék és 0 óriás (60+ szavas) buborék. A korábbi v52-es
tesztek (contd, dedup, punctAsr, llmPairs) változatlanul zöldek.

---

# VÁLTOZÁSOK — v51 → v52

Minden változás mérésen alapul (ellenorzes_v52.js, pilot fixture 170 + friss
export 206 töredék). Részletek: ATADO.md eleje.

## 1. Duplikátum-tisztító (ÚJ: dedupSen)
A második átfutás részleges ismétlés-maradványait szedi ki, három
determinisztikus szabállyal (belső „X. X" futam; szomszédos vég-eleje ismétlés;
határon átlógó ismétlés). A dalszöveg valódi (vesszős) ismétléseit védi.
- automatikusan fut minden beolvasztásnál (mergeSen)
- kézi menüpont a régi felvételekhez: ⋯ → szöveg → „duplikátumok tisztítása"
  — csak az érintett mondatokat fordítja újra

## 2. Szigorúbb mondat-összefűzés (contd, v52)
Duplikátum-őr (B eleje = A vége → nem fűz) + kötelező pozitív jel (kisbetűs
folytatás VAGY nyilvánvalóan csonka vég). Mért pontosság: 53% → 93%,
duplikátum-fűzés: 9 → 0.

## 3. Új sorrend a beolvasztásban (mergeSen)
dedup → stitch → normSen (eddig: normSen → stitch). Az összefűzés az EREDETI
felismerői időkkel fut, a <2 s szünet-szűrő újra él.

## 4. Írásjelezés a lánc elején (ÚJ: punctAsr)
Ha a felismerő szeletéből hiányoznak az írásjelek (needPunct), az LLM még a
tagolás ELŐTT visszateszi őket a nyers válaszba — a v50-es szó-ellenőrzéssel
(eltérő szavaknál eldobjuk). Így a buborék eleve mondat, a fordító eleve teljes
mondatokat kap, és nem kell utólag újrafordítani. Normál (írásjeles) podcastnál
egyetlen API-hívás sem történik. Kulcs nélküli (ingyenes) motornál kimarad —
ott a 2. pont hálója dolgozik.

## 5. A fordítás és a szópár-gyártás szétválasztva
- a fordítás MINDIG a tiszta SYS_TR-en fut (eddig bekapcsolt szópároknál a
  nehéz kombinált prompton, ami hosszú mondatoknál csordult, a JSON csonkult,
  és az egész köteg visszaesett vagy üresen maradt)
- a szópárak külön, könnyebb kéréssel készülnek (SYS_PAIRS), és megkapják a
  KÉSZ magyar fordítást — a párok ahhoz horgonyzódnak, ami a képernyőn van
  (a régi pótló újrafordíttatott és a friss magyart eldobta)
- az újrafordítás (más motorral) is így működik
- bekapcsolt szópároknál ez fordításonként EGY további, könnyebb kérést jelent

## Visszavonási utak
- automatikus összefűzés ki: mergeSen-ben a stitchSen(rec,false) sor
  kikommentelése (= v45-ös viselkedés)
- automatikus tisztítás ki: mergeSen-ben a dedupSen(rec) sor kikommentelése
- automatikus írásjelezés ki: runJob/runPass-ban a punctAsr-blokk kikommentelése

## Nem változott
splitSentences (a mérés felmentette), normSen, huAlign/huBuild, tanuló mód,
lapozó, polc, listák, PWA, felismerés-indítás.
