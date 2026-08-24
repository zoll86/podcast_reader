package hu.zoll86.mappa;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;

/**
 * PODCAST-MAPPA (SAF) — a Kétnyelvű hallgató natív mappa-hozzáférése.
 *
 * Miért kell: az androidos böngészőben nincs könyvtárválasztó API, ezért a
 * webapp mindent a saját tárolójába másolt — dupla tárhely, és minden
 * hangfájlt kézzel kellett behúzni. Ez a plugin a rendszer könyvtárválasztóját
 * (Storage Access Framework) használja: a felhasználó EGYSZER kijelöli a
 * podcast-mappáját, az app tartós olvasási jogot kap rá, és onnantól a
 * könyvtár = a mappa tartalma. A fájlok ott maradnak, ahol vannak.
 *
 * A hangot NEM adjuk át egyben (egy nyolcórás m4b memóriába olvasása
 * elfogyasztaná a WebView-t): a `read` metódus bájt-tartományt ad vissza
 * base64-ben, pontosan úgy, ahogy a felismerés szeletelője kéri.
 *
 * Ráadás: a `http` metódus natív HTTP-t ad, amire NEM vonatkozik a CORS —
 * ezzel az RSS-feedek proxy nélkül töltődnek.
 */
@CapacitorPlugin(name = "Mappa")
public class MappaPlugin extends Plugin {

    private static final String PREF = "mappa_prefs";
    private static final String KEY_TREE = "tree_uri";

    /* ======== SÖTÉT RENDSZERSÁVOK (natív, a témától függetlenül) ========
       A navigációs sáv (a képernyő alján) az Android sajátja, nem a weboldal
       része — CSS-ből elérhetetlen. Ha az app témája nem ad neki színt, a
       rendszer VILÁGOS alapot használ: fehér csík a sötét olvasófelület alatt.
       A témába írt szín könnyen kimarad (ha a build-recept nem frissül), ezért
       itt, kódból is beállítjuk, minden indulásnál. */
    @Override
    public void load() {
        super.load();
        final android.app.Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                android.view.Window w = a.getWindow();
                final int hatter = 0xFF0B0A0E;          /* az app alapszíne */
                w.getDecorView().setBackgroundColor(hatter);
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    w.setNavigationBarColor(hatter);
                    w.setStatusBarColor(hatter);
                }
                /* a sávok ikonjai VILÁGOSAK legyenek (sötét háttéren) */
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    android.view.View d = w.getDecorView();
                    int f = d.getSystemUiVisibility();
                    f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(f);
                }
                /* Android 15-től a fenti szín-hívások hatástalanok lehetnek
                   (kikényszerített edge-to-edge). Ilyenkor a sáv átlátszó lesz,
                   és a mögötte lévő sötét felület látszik — ezért a decorView
                   háttere fekete, és a WebView is sötét alapon fut. */
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    try {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
                        androidx.core.view.WindowInsetsControllerCompat c =
                                androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
                        if (c != null) {
                            c.setAppearanceLightStatusBars(false);
                            c.setAppearanceLightNavigationBars(false);
                        }
                    } catch (Throwable ignored) { }
                }
                if (getBridge() != null && getBridge().getWebView() != null) {
                    getBridge().getWebView().setBackgroundColor(hatter);
                }
            } catch (Throwable e) {
                /* a megjelenés nem lehet ok az összeomlásra */
            }
        });
    }

    private MappaSzerver szerver;
    private String jegy;
    private int port;

    /**
     * A kiszolgáló elindítása (idempotens) — a webapp ezt kéri legelőször, és
     * megkapja a localhost-előtagot, amiből a fájl-URL-eket építi.
     */
    @PluginMethod
    public void serve(PluginCall call) {
        try {
            if (szerver == null || szerver.getPort() == 0) {
                jegy = java.util.UUID.randomUUID().toString().replace("-", "");
                szerver = new MappaSzerver(getContext(), jegy);
                port = szerver.indit();
            }
            JSObject r = new JSObject();
            r.put("base", "http://127.0.0.1:" + port + "/f");
            r.put("token", jegy);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("a kiszolgáló nem indult: " + e.getMessage());
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (szerver != null) szerver.leallit();
        super.handleOnDestroy();
    }

    /* ---------- a mappa kijelölése (egyszeri) ---------- */

    @PluginMethod
    public void pick(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        Uri tree = (data != null) ? data.getData() : null;
        if (tree == null) {
            call.reject("nem választottál mappát");
            return;
        }
        try {
            /* tartós jog: újraindítás után is megvan */
            getContext().getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        getContext().getSharedPreferences(PREF, 0).edit()
                .putString(KEY_TREE, tree.toString()).apply();
        JSObject r = new JSObject();
        r.put("uri", tree.toString());
        r.put("name", szepNev(tree));
        call.resolve(r);
    }

    @PluginMethod
    public void current(PluginCall call) {
        String s = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        JSObject r = new JSObject();
        r.put("uri", s);
        r.put("name", (s == null) ? null : szepNev(Uri.parse(s)));
        call.resolve(r);
    }

    @PluginMethod
    public void forget(PluginCall call) {
        getContext().getSharedPreferences(PREF, 0).edit().remove(KEY_TREE).apply();
        call.resolve();
    }

    /* ---------- a mappa tartalma (alkönyvtárakkal együtt) ---------- */

    @PluginMethod
    public void list(PluginCall call) {
        String s = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        if (s == null) { call.reject("nincs kijelölt mappa"); return; }
        JSArray out = new JSArray();
        try {
            Uri tree = Uri.parse(s);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            bejar(tree, rootId, out, "", 0);
        } catch (Exception e) {
            call.reject("a mappa nem olvasható: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("files", out);
        call.resolve(r);
    }

    /** Rekurzív bejárás, legfeljebb 3 szint mélyen (podcast-mappa/műsor/epizód). */
    private void bejar(Uri tree, String docId, JSArray out, String utvonal, int melyseg) {
        bejarSzuro(tree, docId, out, utvonal, melyseg, false);
    }

    /** Ugyanaz a bejárás, választható szűrővel: hang (alap) vagy felirat. */
    private void bejarSzuro(Uri tree, String docId, JSArray out, String utvonal, int melyseg, boolean feliratMod) {
        if (melyseg > 3 || out.length() > 3000) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String nev = c.getString(1);
                String mime = c.getString(2);
                long meret = c.getLong(3);
                long mod = c.getLong(4);
                boolean mappa = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (mappa) {
                    bejarSzuro(tree, id, out, utvonal.isEmpty() ? nev : (utvonal + "/" + nev), melyseg + 1, feliratMod);
                } else if (feliratMod ? felirat(nev) : hang(nev, mime)) {
                    JSObject f = new JSObject();
                    f.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                    f.put("name", nev);
                    f.put("folder", utvonal);
                    f.put("size", meret);
                    f.put("mtime", mod);
                    f.put("mime", mime == null ? "" : mime);
                    out.put(f);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- v95: FELIRATFÁJLOK a mappából ----------
       A `list` szándékosan csak hangot ad (a könyvtár-nézetek arra épülnek);
       a feliratok külön metódust kapnak, ugyanazzal a bejárással. */
    @PluginMethod
    public void subs(PluginCall call) {
        String s = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        if (s == null) { call.reject("nincs kijelölt mappa"); return; }
        JSArray out = new JSArray();
        try {
            Uri tree = Uri.parse(s);
            bejarSzuro(tree, DocumentsContract.getTreeDocumentId(tree), out, "", 0, true);
        } catch (Exception e) {
            call.reject("a mappa nem olvasható: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("files", out);
        call.resolve(r);
    }

    private boolean felirat(String nev) {
        String n = (nev == null) ? "" : nev.toLowerCase();
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".tsv");
    }

    private boolean hang(String nev, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String n = (nev == null) ? "" : nev.toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".m4b")
                || n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".flac") || n.endsWith(".mp4");
    }

    /* ---------- olvasás bájt-tartományban (a szeleteléshez) ---------- */

    @PluginMethod
    public void read(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) { call.reject("nincs uri"); return; }
        long tol = call.getLong("offset", 0L);
        /* alapból 1 MB; a szeletelő ennél nagyobbat is kérhet */
        int hossz = call.getInt("length", 1024 * 1024);
        if (hossz > 12 * 1024 * 1024) hossz = 12 * 1024 * 1024;   /* memória-védelem */
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) { call.reject("a fájl nem nyitható meg"); return; }
            long ugrando = tol;
            while (ugrando > 0) {
                long n = in.skip(ugrando);
                if (n <= 0) break;
                ugrando -= n;
            }
            byte[] buf = new byte[hossz];
            int ossz = 0;
            while (ossz < hossz) {
                int n = in.read(buf, ossz, hossz - ossz);
                if (n < 0) break;
                ossz += n;
            }
            byte[] ki = (ossz == hossz) ? buf : java.util.Arrays.copyOf(buf, Math.max(0, ossz));
            JSObject r = new JSObject();
            r.put("data", Base64.encodeToString(ki, Base64.NO_WRAP));
            r.put("bytes", ossz);
            r.put("eof", ossz < hossz);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("olvasási hiba: " + e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** A teljes fájl mérete — a lejátszóhoz és a szeleteléshez. */
    @PluginMethod
    public void size(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) { call.reject("nincs uri"); return; }
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(Uri.parse(uri),
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            long meret = 0;
            if (c != null && c.moveToFirst()) meret = c.getLong(0);
            JSObject r = new JSObject();
            r.put("size", meret);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("méret nem olvasható: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- natív HTTP: az RSS-hez, CORS nélkül ---------- */

    @PluginMethod
    public void http(PluginCall call) {
        final String cim = call.getString("url");
        if (cim == null) { call.reject("nincs url"); return; }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(cim);
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.setRequestProperty("User-Agent", "KetnyelvuHallgato/1.0");
                c.setRequestProperty("Accept", "*/*");
                int kod = c.getResponseCode();
                InputStream in = (kod >= 400) ? c.getErrorStream() : c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                long ossz = 0;
                while (in != null && (n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    ossz += n;
                    if (ossz > 8L * 1024 * 1024) break;      /* feed-XML sosem ekkora */
                }
                JSObject r = new JSObject();
                r.put("status", kod);
                r.put("text", bo.toString("UTF-8"));
                call.resolve(r);
            } catch (Exception e) {
                call.reject("hálózati hiba: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /* ---------- epizód letöltése EGYENESEN a mappába ---------- */

    @PluginMethod
    public void download(PluginCall call) {
        final String cim = call.getString("url");
        final String nev = call.getString("name", "epizod.mp3");
        if (cim == null) { call.reject("nincs url"); return; }
        final String treeS = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        if (treeS == null) { call.reject("nincs kijelölt mappa"); return; }
        new Thread(() -> {
            HttpURLConnection c = null;
            OutputStream os = null;
            InputStream in = null;
            try {
                Uri tree = Uri.parse(treeS);
                Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Uri cel = DocumentsContract.createDocument(
                        getContext().getContentResolver(), dir, "audio/mpeg", nev);
                if (cel == null) { call.reject("nem sikerült létrehozni a fájlt a mappában"); return; }
                URL u = new URL(cim);
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "KetnyelvuHallgato/1.0");
                in = c.getInputStream();
                os = getContext().getContentResolver().openOutputStream(cel);
                byte[] buf = new byte[131072];
                long ossz = 0;
                int n;
                long teljes = c.getContentLengthLong();
                long utolsoJelzes = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    ossz += n;
                    if (System.currentTimeMillis() - utolsoJelzes > 400) {
                        utolsoJelzes = System.currentTimeMillis();
                        JSObject p = new JSObject();
                        p.put("bytes", ossz);
                        p.put("total", teljes);
                        notifyListeners("progress", p);
                    }
                }
                os.flush();
                JSObject r = new JSObject();
                r.put("uri", cel.toString());
                r.put("name", nev);
                r.put("size", ossz);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("letöltési hiba: " + e.getMessage());
            } finally {
                try { if (os != null) os.close(); } catch (Exception ignored) { }
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /* ---------- segéd ---------- */

    /* ---------- FÁJL ÍRÁSA a mappába (kivitel, mentés) ----------
       A WebView-ben a böngészős letöltés (blob + <a download>) nem működik:
       a felhasználó „felirat kiírva" üzenetet látott, de fájl nem keletkezett.
       Ezért a natív app a kijelölt podcast-mappába írja a kimenetet — ott
       megtalálja a fájlkezelő is, és vissza is tölthető. */
    @PluginMethod
    public void writeText(PluginCall call) {
        final String nev = call.getString("name", "kimenet.txt");
        final String tartalom = call.getString("text", "");
        final String mime = call.getString("mime", "text/plain");
        final String treeS = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        if (treeS == null) { call.reject("nincs kijelolt mappa"); return; }
        OutputStream os = null;
        try {
            Uri tree = Uri.parse(treeS);
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri cel = DocumentsContract.createDocument(
                    getContext().getContentResolver(), dir, mime, nev);
            if (cel == null) { call.reject("nem sikerult letrehozni a fajlt a mappaban"); return; }
            os = getContext().getContentResolver().openOutputStream(cel);
            os.write(tartalom.getBytes("UTF-8"));
            os.flush();
            JSObject r = new JSObject();
            r.put("uri", cel.toString());
            r.put("name", nev);
            r.put("bytes", tartalom.getBytes("UTF-8").length);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("iras hiba: " + e.getMessage());
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- DARABOLT ÍRÁS: nagy mentés memória-összeomlás nélkül ----------
       A teljes könyvtár egyetlen szövegként több tízmegabájt: ha a WebView azt
       egyben állítja össze és egyben adja át, elfogy a memória, és az app
       kidob. Ezért a mentés felvételenként érkezik: az első hívás létrehozza a
       fájlt, a többi hozzáfűzi. A megnyitott kimenetet a hívások közt tartjuk. */
    private OutputStream fuzOs;
    private String fuzUri;
    private long fuzBytes;

    @PluginMethod
    public void appendText(PluginCall call) {
        final String nev = call.getString("name", "mentes.jsonl");
        final String tartalom = call.getString("text", "");
        final boolean elso = call.getBoolean("first", false);
        final boolean utolso = call.getBoolean("last", false);
        try {
            if (elso) {
                zarFuz();
                final String treeS = getContext().getSharedPreferences(PREF, 0)
                        .getString(KEY_TREE, null);
                if (treeS == null) { call.reject("nincs kijelolt mappa"); return; }
                Uri tree = Uri.parse(treeS);
                Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Uri cel = DocumentsContract.createDocument(
                        getContext().getContentResolver(), dir, "application/json", nev);
                if (cel == null) { call.reject("nem sikerult letrehozni a fajlt"); return; }
                fuzUri = cel.toString();
                fuzOs = getContext().getContentResolver().openOutputStream(cel);
                fuzBytes = 0;
            }
            if (fuzOs == null) { call.reject("nincs megnyitott mentes-fajl"); return; }
            byte[] b = tartalom.getBytes("UTF-8");
            fuzOs.write(b);
            fuzBytes += b.length;
            JSObject r = new JSObject();
            r.put("uri", fuzUri);
            r.put("bytes", fuzBytes);
            if (utolso) {
                fuzOs.flush();
                zarFuz();
                r.put("done", true);
            }
            call.resolve(r);
        } catch (Exception e) {
            zarFuz();
            call.reject("iras hiba: " + e.getMessage());
        }
    }

    private void zarFuz() {
        try { if (fuzOs != null) { fuzOs.flush(); fuzOs.close(); } } catch (Exception ignored) { }
        fuzOs = null;
    }

    /* ---------- a mappában lévő mentés-fájlok (hogy ne kelljen keresni) ---------- */
    @PluginMethod
    public void listBackups(PluginCall call) {
        String s = getContext().getSharedPreferences(PREF, 0).getString(KEY_TREE, null);
        if (s == null) { call.reject("nincs kijelolt mappa"); return; }
        JSArray out = new JSArray();
        Cursor c = null;
        try {
            Uri tree = Uri.parse(s);
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            c = getContext().getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            while (c != null && c.moveToNext()) {
                String nev = c.getString(1);
                if (nev == null) continue;
                String n = nev.toLowerCase();
                if (!(n.endsWith(".json") || n.endsWith(".jsonl"))) continue;
                if (!n.contains("mentes") && !n.contains("hallgato")) continue;
                JSObject f = new JSObject();
                f.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString());
                f.put("name", nev);
                f.put("size", c.getLong(2));
                f.put("mtime", c.getLong(3));
                out.put(f);
            }
        } catch (Exception e) {
            call.reject("a mappa nem olvashato: " + e.getMessage());
            return;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        JSObject r = new JSObject();
        r.put("files", out);
        call.resolve(r);
    }

    /* ---------- adott URI beolvasása (mentés visszaállítása) ---------- */
    @PluginMethod
    public void readText(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) { call.reject("nincs uri"); return; }
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) { call.reject("a fajl nem nyithato meg"); return; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[131072];
            int n;
            long ossz = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                ossz += n;
                if (ossz > 128L * 1024 * 1024) break;
            }
            JSObject r = new JSObject();
            r.put("text", bo.toString("UTF-8"));
            r.put("bytes", ossz);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("olvasasi hiba: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- FÁJL VÁLASZTÁSA ÉS BEOLVASÁSA (visszaállítás) ---------- */
    @PluginMethod
    public void pickText(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "text/tab-separated-values",
                "application/x-subrip", "text/*"});
        startActivityForResult(call, i, "pickTextResult");
    }

    @ActivityCallback
    private void pickTextResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        Uri uri = (data != null) ? data.getData() : null;
        if (uri == null) { call.reject("nem valasztottal fajlt"); return; }
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) { call.reject("a fajl nem nyithato meg"); return; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long ossz = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                ossz += n;
                if (ossz > 64L * 1024 * 1024) break;   /* mentes-fajl sosem ekkora */
            }
            JSObject r = new JSObject();
            r.put("text", bo.toString("UTF-8"));
            r.put("bytes", ossz);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("olvasasi hiba: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- v90: előtér-szolgáltatás + ébrentartás ----------
       A JS oldal hívja a sorozat-előfelismerés elején (fgOn) és végén (fgOff).
       Amíg fut, a rendszer nem altatja el a folyamatot — a képernyőzár alatt
       is megy a felismerés. */
    @PluginMethod
    public void fgOn(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), MappaMunka.class);
            i.putExtra("cim", call.getString("title", "feldolgozás fut"));
            if (android.os.Build.VERSION.SDK_INT >= 26)
                getContext().startForegroundService(i);
            else
                getContext().startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("nem indul az ébrentartás: " + e.getMessage());
        }
    }

    @PluginMethod
    public void fgOff(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), MappaMunka.class));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       FELOLVASÓ (v139)
       Az Android WebView-ban NINCS Web Speech API — a `speechSynthesis` a
       böngészőben működik, az alkalmazásba ágyazott WebView-ban nem. Ezért a
       felolvasás natívan megy: a rendszer TextToSpeech szolgáltatásával,
       ugyanazzal, amit a telefon máshol is használ. A JS csak szöveget küld,
       és megkapja, mikor lett kész.
       ══════════════════════════════════════════════════════════════════════ */
    private TextToSpeech tts = null;
    private boolean ttsKesz = false;
    private PluginCall ttsVar = null;

    private void ttsIndit(final Runnable utana) {
        if (tts != null && ttsKesz) { if (utana != null) utana.run(); return; }
        if (tts == null) {
            tts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsKesz = (status == TextToSpeech.SUCCESS);
                    if (ttsKesz) {
                        try { tts.setLanguage(new Locale("hu", "HU")); } catch (Exception ignore) {}
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {}
                            @Override public void onDone(String id) { ttsVege(true, ""); }
                            @Override public void onError(String id) { ttsVege(false, "a felolvasás megszakadt"); }
                        });
                    }
                    if (utana != null) utana.run();
                }
            });
        } else if (utana != null) utana.run();
    }

    private synchronized void ttsVege(boolean ok, String hiba) {
        PluginCall c = ttsVar; ttsVar = null;
        if (c == null) return;
        JSObject r = new JSObject();
        r.put("ok", ok);
        if (!ok) r.put("hiba", hiba);
        c.resolve(r);
    }

    /** felolvasás; a hívás akkor tér vissza, amikor a mondat elhangzott */
    @PluginMethod
    public void ttsSpeak(final PluginCall call) {
        final String szoveg = call.getString("text", "");
        if (szoveg == null || szoveg.trim().isEmpty()) {
            JSObject r = new JSObject(); r.put("ok", true); call.resolve(r); return;
        }
        call.setKeepAlive(true);
        ttsIndit(new Runnable() { @Override public void run() {
            if (!ttsKesz || tts == null) {
                JSObject r = new JSObject(); r.put("ok", false);
                r.put("hiba", "a készüléken nincs beszédszintézis");
                call.resolve(r); return;
            }
            try {
                float rate = (float) call.getDouble("rate", 1.05);
                float pitch = (float) call.getDouble("pitch", 1.0);
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);
                String hang = call.getString("voice", "");
                if (hang != null && !hang.isEmpty()) {
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        if (hang.equals(v.getName())) { tts.setVoice(v); break; }
                    }
                } else {
                    try { tts.setLanguage(new Locale("hu", "HU")); } catch (Exception ignore) {}
                }
                synchronized (MappaPlugin.this) {
                    if (ttsVar != null) { ttsVege(true, ""); }
                    ttsVar = call;
                }
                tts.stop();
                Bundle b = new Bundle();
                b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "m");
                int e = tts.speak(szoveg, TextToSpeech.QUEUE_FLUSH, b, "m");
                if (e != TextToSpeech.SUCCESS) ttsVege(false, "a felolvasás nem indult");
            } catch (Exception ex) {
                ttsVege(false, ex.getMessage());
            }
        }});
    }

    @PluginMethod
    public void ttsStop(PluginCall call) {
        try { if (tts != null) tts.stop(); } catch (Exception ignore) {}
        ttsVege(true, "");
        call.resolve();
    }

    /** a készüléken elérhető hangok (a magyarok elöl) */
    @PluginMethod
    public void ttsVoices(final PluginCall call) {
        ttsIndit(new Runnable() { @Override public void run() {
            JSArray ki = new JSArray();
            try {
                if (tts != null && ttsKesz) {
                    Set<android.speech.tts.Voice> v = tts.getVoices();
                    if (v != null) for (android.speech.tts.Voice x : v) {
                        String lang = (x.getLocale() != null) ? x.getLocale().toString() : "";
                        JSObject o = new JSObject();
                        o.put("name", x.getName());
                        o.put("lang", lang);
                        o.put("hu", lang.toLowerCase().startsWith("hu"));
                        ki.put(o);
                    }
                }
            } catch (Exception ignore) {}
            JSObject r = new JSObject();
            r.put("voices", ki);
            r.put("van", ttsKesz);
            call.resolve(r);
        }});
    }

    private String szepNev(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int k = id.lastIndexOf(':');
            String p = (k >= 0) ? id.substring(k + 1) : id;
            if (p.isEmpty()) return "belső tároló";
            int s = p.lastIndexOf('/');
            return (s >= 0) ? p.substring(s + 1) : p;
        } catch (Exception e) {
            return "mappa";
        }
    }
}
