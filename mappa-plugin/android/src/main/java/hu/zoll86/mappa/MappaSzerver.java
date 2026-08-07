package hu.zoll86.mappa;

import android.content.Context;
import android.net.Uri;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * MINI HTTP-KISZOLGÁLÓ a mappa fájljaihoz (csak localhost).
 *
 * Miért: a webapp teljes bontó- és szeletelő-gépezete `file.slice()`-ra épül,
 * a lejátszó pedig `A.src`-re. Ha a mappa fájljait localhoston kiszolgáljuk
 * Range-fejléccel, akkor
 *   - az <audio> elem KÖZVETLENÜL streamel (nincs memóriába olvasás, a seek is
 *     működik: egy nyolcórás m4b-t nem kell betölteni),
 *   - a `fetch(url,{headers:{Range}})` VALÓDI Blobot ad, tehát a mp4/mp3/wav/adts
 *     bontók és a szeletelő egyetlen sor változtatás nélkül működnek tovább.
 *
 * A kiszolgáló csak a hurok-illesztőn (127.0.0.1) hallgat, kizárólag a
 * kijelölt mappa alá tartozó content:// URI-kat adja ki, és egy futásonként
 * generált titkos jegy (token) nélkül minden kérést elutasít.
 */
class MappaSzerver implements Runnable {

    private final Context ctx;
    private final String jegy;
    private ServerSocket sock;
    private volatile boolean fut = true;
    private int port = 0;

    MappaSzerver(Context ctx, String jegy) {
        this.ctx = ctx;
        this.jegy = jegy;
    }

    int indit() throws Exception {
        sock = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        port = sock.getLocalPort();
        Thread t = new Thread(this, "mappa-szerver");
        t.setDaemon(true);
        t.start();
        return port;
    }

    void leallit() {
        fut = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
    }

    int getPort() { return port; }

    @Override
    public void run() {
        while (fut) {
            Socket s = null;
            try {
                s = sock.accept();
                kezel(s);
            } catch (Exception e) {
                /* a bezárt socket kivétele normális leállásnál */
            } finally {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void kezel(Socket s) throws Exception {
        s.setSoTimeout(15000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        /* --- kéréssor és fejlécek --- */
        StringBuilder fej = new StringBuilder();
        int c, ures = 0;
        while ((c = in.read()) != -1) {
            fej.append((char) c);
            if (c == '\n') {
                if (++ures == 2) break;
            } else if (c != '\r') {
                ures = 0;
            }
            if (fej.length() > 16384) break;
        }
        String[] sorok = fej.toString().split("\r?\n");
        if (sorok.length == 0) return;
        String[] elso = sorok[0].split(" ");
        if (elso.length < 2) { hiba(out, 400, "hibás kérés"); return; }
        String metodus = elso[0];
        String utvonal = elso[1];

        String range = null;
        for (int i = 1; i < sorok.length; i++) {
            int k = sorok[i].indexOf(':');
            if (k < 0) continue;
            if (sorok[i].substring(0, k).trim().equalsIgnoreCase("Range"))
                range = sorok[i].substring(k + 1).trim();
        }

        /* --- paraméterek --- */
        String uriS = param(utvonal, "uri"), jegyS = param(utvonal, "t");
        if (jegyS == null || !jegy.equals(jegyS)) { hiba(out, 403, "hibás jegy"); return; }
        if (uriS == null) { hiba(out, 400, "nincs uri"); return; }

        Uri uri = Uri.parse(uriS);
        long teljes = meret(uri);
        if (teljes <= 0) { hiba(out, 404, "a fájl nem olvasható"); return; }

        long tol = 0, ig = teljes - 1;
        boolean reszleges = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6).trim();
            int m = r.indexOf('-');
            if (m >= 0) {
                try {
                    String a = r.substring(0, m).trim(), b = r.substring(m + 1).trim();
                    if (!a.isEmpty()) {
                        tol = Long.parseLong(a);
                        if (!b.isEmpty()) ig = Math.min(teljes - 1, Long.parseLong(b));
                    } else if (!b.isEmpty()) {                 /* bytes=-N : a vég */
                        long n = Long.parseLong(b);
                        tol = Math.max(0, teljes - n);
                    }
                    reszleges = true;
                } catch (Exception ignored) { }
            }
        }
        if (tol >= teljes) { hiba(out, 416, "a kért tartomány a fájl végén túl van"); return; }
        long hossz = ig - tol + 1;

        String mime = tipus(uriS);
        StringBuilder h = new StringBuilder();
        h.append(reszleges ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(hossz).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (reszleges)
            h.append("Content-Range: bytes ").append(tol).append('-').append(ig)
             .append('/').append(teljes).append("\r\n");
        /* a WebView más origin-ből kéri, ezért kell a CORS-engedély */
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Range\r\n");
        h.append("Access-Control-Expose-Headers: Content-Range, Content-Length\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        if ("HEAD".equalsIgnoreCase(metodus) || "OPTIONS".equalsIgnoreCase(metodus)) {
            out.flush(); return;
        }

        /* --- törzs --- */
        InputStream fin = null;
        try {
            fin = ctx.getContentResolver().openInputStream(uri);
            if (fin == null) return;
            long ugrando = tol;
            while (ugrando > 0) {
                long n = fin.skip(ugrando);
                if (n <= 0) break;
                ugrando -= n;
            }
            byte[] buf = new byte[131072];
            long marad = hossz;
            while (marad > 0) {
                int n = fin.read(buf, 0, (int) Math.min(buf.length, marad));
                if (n <= 0) break;
                out.write(buf, 0, n);
                marad -= n;
            }
            out.flush();
        } catch (Exception e) {
            /* a lejátszó gyakran félbehagyja a kérést seek-nél — ez normális */
        } finally {
            try { if (fin != null) fin.close(); } catch (Exception ignored) { }
        }
    }

    private long meret(Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_SIZE},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    private String tipus(String nev) {
        String n = nev.toLowerCase();
        if (n.contains(".mp3")) return "audio/mpeg";
        if (n.contains(".m4b") || n.contains(".m4a") || n.contains(".mp4")) return "audio/mp4";
        if (n.contains(".aac")) return "audio/aac";
        if (n.contains(".wav")) return "audio/wav";
        if (n.contains(".ogg") || n.contains(".opus")) return "audio/ogg";
        if (n.contains(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    private String param(String utvonal, String kulcs) {
        int q = utvonal.indexOf('?');
        if (q < 0) return null;
        for (String p : utvonal.substring(q + 1).split("&")) {
            int e = p.indexOf('=');
            if (e < 0) continue;
            if (p.substring(0, e).equals(kulcs)) {
                try { return URLDecoder.decode(p.substring(e + 1), "UTF-8"); }
                catch (Exception ex) { return null; }
            }
        }
        return null;
    }

    private void hiba(OutputStream out, int kod, String szoveg) {
        try {
            byte[] b = szoveg.getBytes("UTF-8");
            out.write(("HTTP/1.1 " + kod + " Error\r\nContent-Length: " + b.length +
                    "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Exception ignored) { }
    }
}
