package cl.nuam;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Contexto de petición, router y servidor de archivos estáticos sobre com.sun.net.httpserver. */
public final class Http {
    private Http() {}

    /** Respuesta binaria/personalizada (exportaciones, CSV, etc.). */
    public record Raw(int status, String contentType, byte[] body, Map<String, String> headers) {
        public Raw(int status, String ct, byte[] body) { this(status, ct, body, Map.of()); }
    }

    public interface Handler { Object handle(Ctx c) throws Exception; }
    private record Route(String method, Pattern pattern, Handler h, Set<String> roles, boolean pub) {}

    public static final class Ctx {
        public final HttpExchange ex;
        public Matcher m;
        public Auth.Principal user;
        private String body;
        private Map<String, String> query;

        Ctx(HttpExchange ex) { this.ex = ex; }

        public String method() { return ex.getRequestMethod(); }
        public String path() { return ex.getRequestURI().getRawPath(); }
        public String pathParam(int i) { return m.group(i); }
        public int pathInt(int i) { try { return Integer.parseInt(m.group(i)); } catch (NumberFormatException e) { throw ApiException.bad("Identificador inválido"); } }
        public String header(String k) { return ex.getRequestHeaders().getFirst(k); }

        public Map<String, String> query() {
            if (query == null) {
                query = new LinkedHashMap<>();
                String q = ex.getRequestURI().getRawQuery();
                if (q != null) for (String kv : q.split("&")) {
                    if (kv.isEmpty()) continue;
                    int i = kv.indexOf('=');
                    String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                    String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                    query.put(k, v);
                }
            }
            return query;
        }
        public String q(String k) { String v = query().get(k); return v == null || v.isBlank() ? null : v.trim(); }

        public String cookie(String name) {
            List<String> hs = ex.getRequestHeaders().get("Cookie");
            if (hs == null) return null;
            for (String h : hs) for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
            return null;
        }
        public void setCookie(String name, String value, int maxAge) {
            String c = name + "=" + value + "; Path=/; HttpOnly; SameSite=Strict" + (maxAge >= 0 ? "; Max-Age=" + maxAge : "")
                    + (Config.SECURE_COOKIES ? "; Secure" : "");
            ex.getResponseHeaders().add("Set-Cookie", c);
        }

        public String body() throws IOException {
            if (body == null) {
                byte[] b = ex.getRequestBody().readNBytes(12 * 1024 * 1024 + 1);
                if (b.length > 12 * 1024 * 1024) throw new ApiException(413, "Archivo demasiado grande (máx. 12 MB)");
                body = new String(b, StandardCharsets.UTF_8);
            }
            return body;
        }
        public Map<String, Object> json() throws IOException { return Json.parseObj(body()); }

        void send(int status, String ct, byte[] data, Map<String, String> headers) throws IOException {
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", ct);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
            h.set("Cache-Control", "no-store");
            headers.forEach(h::set);
            if (status == 204 || ex.getRequestMethod().equals("HEAD")) { ex.sendResponseHeaders(status, -1); }
            else { ex.sendResponseHeaders(status, data.length); try (OutputStream os = ex.getResponseBody()) { os.write(data); } }
            ex.close();
        }
        void json(int status, Object o) throws IOException {
            send(status, "application/json; charset=utf-8", Json.write(o).getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    // ---------------- Router ----------------
    public static final class Router implements HttpHandler {
        private final List<Route> routes = new ArrayList<>();
        private final Path webRoot = Paths.get(Config.WEB_DIR).toAbsolutePath().normalize();

        /** roles vacío = cualquier usuario autenticado. */
        public void add(String method, String regex, Handler h, String... roles) { routes.add(new Route(method, Pattern.compile("^" + regex + "$"), h, Set.of(roles), false)); }
        public void pub(String method, String regex, Handler h) { routes.add(new Route(method, Pattern.compile("^" + regex + "$"), h, Set.of(), true)); }

        @Override public void handle(HttpExchange ex) throws IOException {
            Ctx c = new Ctx(ex);
            try {
                String path = c.path();
                for (Route r : routes) {
                    if (!r.method.equals(c.method())) continue;
                    Matcher m = r.pattern.matcher(path);
                    if (!m.matches()) continue;
                    c.m = m;
                    if (!r.pub) {
                        c.user = Auth.authenticate(c);
                        if (!r.roles.isEmpty() && !r.roles.contains(c.user.rol())) {
                            Audit.logNew(c.user.userId(), "ACCESO_DENEGADO", null, null, c.method() + " " + path + " (rol " + c.user.rol() + ")", c.user.origen());
                            throw ApiException.forbidden("No tiene permiso para esta operación");
                        }
                    }
                    Object res = r.h.handle(c);
                    if (res instanceof Raw raw) c.send(raw.status(), raw.contentType(), raw.body(), raw.headers());
                    else c.json(200, res == null ? Map.of("ok", true) : res);
                    return;
                }
                if (c.method().equals("GET") || c.method().equals("HEAD")) { serveStatic(c, path); return; }
                throw new ApiException(404, "Ruta no encontrada");
            } catch (ApiException e) {
                Map<String, Object> o = new LinkedHashMap<>(); o.put("error", e.getMessage()); o.putAll(e.extra);
                c.json(e.status, o);
            } catch (Throwable t) {
                Throwable root = t; while (root.getCause() != null) root = root.getCause();
                System.err.println("[ERROR] " + c.method() + " " + c.path() + " -> " + root);
                if (System.getenv("NUAM_DEBUG") != null) t.printStackTrace();
                String msg = root instanceof java.sql.SQLException se && "42501".equals(se.getSQLState())
                        ? "El log de auditoría es inmutable: operación rechazada por la base de datos" : "Error interno del servidor";
                try { c.json(root instanceof java.sql.SQLException se2 && "42501".equals(se2.getSQLState()) ? 403 : 500, Map.of("error", msg)); } catch (Exception ignore) { }
            }
        }

        private void serveStatic(Ctx c, String path) throws IOException {
            if (path.equals("/")) path = "/index.html";
            Path f = webRoot.resolve("." + URLDecoder.decode(path, StandardCharsets.UTF_8)).normalize();
            if (!f.startsWith(webRoot) || !Files.isRegularFile(f)) throw new ApiException(404, "No encontrado");
            String n = f.getFileName().toString();
            String ct = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8"
                    : n.endsWith(".js") ? "text/javascript; charset=utf-8" : n.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            c.send(200, ct, Files.readAllBytes(f), Map.of());
        }
    }
}
