package cl.nuam;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** JSON mínimo (sin dependencias): números se leen como BigDecimal. */
public final class Json {
    private Json() {}
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------------- escritura ----------------
    public static String write(Object o) { StringBuilder sb = new StringBuilder(); w(sb, o); return sb.toString(); }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(sb, s);
        else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) sb.append(o);
        else if (o instanceof BigDecimal b) sb.append(b.toPlainString());
        else if (o instanceof Number n) sb.append(n);
        else if (o instanceof Timestamp t) str(sb, t.toLocalDateTime().format(TS));
        else if (o instanceof java.time.temporal.TemporalAccessor || o instanceof java.sql.Date) str(sb, o.toString());
        else if (o instanceof Map<?, ?> m) {
            sb.append('{'); boolean f = true;
            for (Map.Entry<?, ?> e : m.entrySet()) { if (!f) sb.append(','); f = false; str(sb, String.valueOf(e.getKey())); sb.append(':'); w(sb, e.getValue()); }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('['); boolean f = true;
            for (Object x : it) { if (!f) sb.append(','); f = false; w(sb, x); }
            sb.append(']');
        } else str(sb, o.toString());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    // ---------------- lectura ----------------
    public static Object parse(String s) {
        P p = new P(s); p.ws(); Object v = p.val(); p.ws();
        if (p.i != s.length()) throw ApiException.bad("JSON inválido");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObj(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        Object o = parse(s);
        if (!(o instanceof Map)) throw ApiException.bad("Se esperaba un objeto JSON");
        return (Map<String, Object>) o;
    }

    private static final class P {
        final String s; int i = 0;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        ApiException err() { return ApiException.bad("JSON inválido en posición " + i); }
        Object val() {
            if (i >= s.length()) throw err();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err();
            try { return new BigDecimal(s.substring(st, i)); } catch (NumberFormatException e) { throw err(); }
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); if (i >= s.length() || s.charAt(i) != '"') throw err();
                String k = string(); ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err();
                i++; ws(); m.put(k, val()); ws();
                if (i >= s.length()) throw err();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err();
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>(); i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(val()); ws();
                if (i >= s.length()) throw err();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err();
            }
        }
        String string() {
            StringBuilder sb = new StringBuilder(); i++;
            while (true) {
                if (i >= s.length()) throw err();
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }
    }

    // ---------------- helpers de acceso ----------------
    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k); if (v == null) return null; String s = v.toString().trim(); return s.isEmpty() ? null : s;
    }
    public static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        String s = v.toString().trim().replace(',', '.'); if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
    public static boolean bool(Object v) { return v instanceof Boolean b ? b : v != null && ("true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString()) || "si".equalsIgnoreCase(v.toString())); }
}
