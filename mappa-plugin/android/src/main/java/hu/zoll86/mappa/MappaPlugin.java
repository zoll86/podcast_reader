package hu.zoll86.mappa;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

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
                    bejar(tree, id, out, utvonal.isEmpty() ? nev : (utvonal + "/" + nev), melyseg + 1);
                } else if (hang(nev, mime)) {
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
