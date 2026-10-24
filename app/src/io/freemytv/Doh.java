package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * For hosts the TV's DNS answers wrong: some ISPs (Jio in India) give TMDB's name the address of a block page, and the
 * connection times out. Then the real address comes from Cloudflare's DNS over HTTPS (reached by its address, 1.1.1.1),
 * and the request goes there by hand: TLS with the real name (SNI and the certificate check), then plain HTTP/1.1.
 */
final class Doh {
    private static final int TIMEOUT = 8000;
    private static final Map<String, String> ADDRESS = new HashMap<>();   // host → address from Cloudflare
    private static final Map<String, Long> UNTIL = new HashMap<>();       // host → when to ask again

    /** True while `host` is known to need the lookup, so its requests skip the TV's DNS (and its timeout). */
    static synchronized boolean known(String host) {
        Long until = UNTIL.get(host);
        return until != null && until > System.currentTimeMillis();
    }

    /** GET `url` at the address Cloudflare gives its host: {status code, body}. */
    static String[] get(URL url, Map<String, String> headers) throws IOException {
        String host = url.getHost();
        InetAddress address = InetAddress.getByName(resolve(host));   // a literal: no lookup
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, 443), TIMEOUT);
            raw.setSoTimeout(TIMEOUT);
            return request(raw, url, headers);
        } finally {
            raw.close();
        }
    }

    private static String[] request(Socket raw, URL url, Map<String, String> headers) throws IOException {
        String host = url.getHost();
        try (SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true)) {
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new SSLException("the certificate isn't " + host + "'s");
            }
            StringBuilder req = new StringBuilder("GET ").append(url.getFile()).append(" HTTP/1.1\r\nHost: ").append(host)
                    .append("\r\nConnection: close\r\nUser-Agent: FreeMyTV\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return parse(readAll(s.getInputStream()));
        }
    }

    private static String resolve(String host) throws IOException {
        synchronized (Doh.class) {
            if (known(host)) return ADDRESS.get(host);
        }
        HttpURLConnection c = (HttpURLConnection) new URL("https://1.1.1.1/dns-query?type=A&name=" + host).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("Accept", "application/dns-json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("DNS over HTTPS returned HTTP " + c.getResponseCode());
            JSONArray answers = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).optJSONArray("Answer");
            for (int i = 0; answers != null && i < answers.length(); i++) {
                JSONObject a = answers.getJSONObject(i);
                if (a.optInt("type") != 1) continue;   // CNAMEs come first
                synchronized (Doh.class) {
                    ADDRESS.put(host, a.getString("data"));
                    UNTIL.put(host, System.currentTimeMillis() + 30 * 60_000L);
                }
                return a.getString("data");
            }
            throw new IOException("DNS over HTTPS has no address for " + host);
        } catch (org.json.JSONException e) {
            throw new IOException("DNS over HTTPS: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** Status line, headers and body (chunked or not; no compression was asked for). */
    private static String[] parse(byte[] r) throws IOException {
        int end = -1;
        for (int i = 0; i + 3 < r.length; i++) {
            if (r[i] == '\r' && r[i + 1] == '\n' && r[i + 2] == '\r' && r[i + 3] == '\n') {
                end = i;
                break;
            }
        }
        if (end < 0) throw new IOException("incomplete HTTP response");
        String[] head = new String(r, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] status = head[0].split(" ");
        if (status.length < 2) throw new IOException("bad HTTP status line");
        boolean chunked = false;
        for (String h : head) {
            String l = h.toLowerCase(Locale.US);
            if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
        }
        int at = end + 4;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (!chunked) {
            body.write(r, at, r.length - at);
        } else {
            while (true) {
                int eol = at;
                while (eol + 1 < r.length && !(r[eol] == '\r' && r[eol + 1] == '\n')) eol++;
                if (eol + 1 >= r.length) throw new IOException("incomplete chunked body");
                String size = new String(r, at, eol - at, StandardCharsets.ISO_8859_1);
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
                if (n == 0) break;
                at = eol + 2;
                if (at + n > r.length) throw new IOException("incomplete chunked body");
                body.write(r, at, n);
                at += n + 2;
            }
        }
        return new String[] {status[1], body.toString(StandardCharsets.UTF_8.name())};
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
