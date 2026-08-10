# v89+v90 — kezelhetőség, DeepL, sorozat-előfelismerés

## v89
- Újrafordító pöcök: elrejtett kezelőknél is működik — közép = megáll, pöcök =
  újrafordít (a zónakezelő szünetben átengedi).
- Tanulómód (folyamatos): a felső buborék nem csúszik az állapotsor alá
  (biztonsági sáv felül).
- DeepL fordítómotor az alkalmazásban (CapacitorHttp, CORS-mentes): kulcs a
  beállításokban, ":fx" végű kulcs = ingyenes végpont. Kötegelt fordítás,
  újrafordító lapon is választható.

## v90
- Sorozat-előfelismerés (felismerés-menü): minden szöveg nélküli rész egymás
  után, angol szövegre. Natív előtér-szolgáltatás + ébrenlét-zár: képernyőzár
  alatt is fut, értesítés jelzi. Fordítás nem készül — az hallgatáskor jön.
- Natív oldal: MappaMunka szolgáltatás (dataSync, PARTIAL_WAKE_LOCK, max 6 óra),
  MappaPlugin.fgOn/fgOff, jogosultságok a plugin manifestjében.
