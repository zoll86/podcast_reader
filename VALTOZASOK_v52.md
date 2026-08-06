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
