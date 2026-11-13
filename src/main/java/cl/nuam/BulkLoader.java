package cl.nuam;

import cl.nuam.Auth.Principal;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Carga masiva CSV/Excel (RF-05) y procesamiento compartido con la importación desde el SII (HU-15). */
public final class BulkLoader {
    private BulkLoader() {}
    private static final int MAX_FILAS = 5000;

    public static final String PLANTILLA;
    static {
        StringBuilder h = new StringBuilder("mercado,periodo,instrumento,secuencia,fecha_pago,descripcion,tipo_dj");
        for (int n = 8; n <= 36; n++) h.append(",m").append(n);
        StringBuilder e1 = new StringBuilder("CL,2026,ENELAM,1,2026-04-15,Dividendo definitivo,1948");
        int[] ms = {100, 80, 60, 40, 30, 20, 10, 10, 5, 5, 5, 5};
        for (int n = 8; n <= 36; n++) e1.append(',').append(n - 8 < ms.length ? ms[n - 8] : "");
        PLANTILLA = h + "\n" + e1 + "\n# Use columnas m8..m36 para montos (el sistema calcula los factores) o f8..f36 para ingresar factores directamente.\n";
    }

    // ============================================================ lectura de archivos
    public static List<Map<String, String>> parseCsv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\R")) if (!l.isBlank() && !l.trim().startsWith("#")) lines.add(l);
        if (lines.isEmpty()) throw ApiException.bad("El archivo está vacío");
        char sep = count(lines.get(0), ';') > count(lines.get(0), ',') ? ';' : ',';
        List<String> head = split(lines.get(0), sep);
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = split(lines.get(i), sep);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < head.size(); j++) row.put(head.get(j), j < cells.size() ? cells.get(j).trim() : "");
            row.put("__fila", String.valueOf(i + 1));
            out.add(row);
        }
        return out;
    }
    private static int count(String s, char c) { int n = 0; for (char x : s.toCharArray()) if (x == c) n++; return n; }
    private static List<String> split(String l, char sep) {
        List<String> out = new ArrayList<>(); StringBuilder sb = new StringBuilder(); boolean q = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') { if (q && i + 1 < l.length() && l.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = !q; }
            else if (c == sep && !q) { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    public static List<Map<String, String>> parseXlsx(byte[] data) {
        try {
            Map<String, byte[]> files = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e; long total = 0;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.getName().startsWith("xl/")) continue;
                    byte[] b = z.readNBytes(30 * 1024 * 1024); total += b.length;
                    if (total > 60L * 1024 * 1024) throw ApiException.bad("Archivo Excel demasiado grande");
                    files.put(e.getName(), b);
                }
            }
            String sheetName = files.keySet().stream().filter(k -> k.matches("xl/worksheets/sheet\\d+\\.xml")).sorted().findFirst().orElseThrow(() -> ApiException.bad("El Excel no contiene hojas"));
            List<String> shared = new ArrayList<>();
            if (files.containsKey("xl/sharedStrings.xml")) {
                NodeList si = dom(files.get("xl/sharedStrings.xml")).getElementsByTagName("si");
                for (int i = 0; i < si.getLength(); i++) shared.add(si.item(i).getTextContent());
            }
            NodeList rows = dom(files.get(sheetName)).getElementsByTagName("row");
            List<List<String>> grid = new ArrayList<>(); List<Integer> nums = new ArrayList<>();
            for (int i = 0; i < rows.getLength(); i++) {
                Element r = (Element) rows.item(i); List<String> cells = new ArrayList<>();
                NodeList cs = r.getElementsByTagName("c");
                for (int j = 0; j < cs.getLength(); j++) {
                    Element c = (Element) cs.item(j);
                    int col = colIndex(c.getAttribute("r"));
                    while (cells.size() <= col) cells.add("");
                    String t = c.getAttribute("t"), val = "";
                    NodeList v = c.getElementsByTagName("v");
                    if ("inlineStr".equals(t)) val = c.getTextContent();
                    else if (v.getLength() > 0) { val = v.item(0).getTextContent(); if ("s".equals(t)) val = shared.get(Integer.parseInt(val)); }
                    cells.set(col, val.trim());
                }
                grid.add(cells); nums.add(r.getAttribute("r").isEmpty() ? i + 1 : Integer.parseInt(r.getAttribute("r")));
            }
            if (grid.isEmpty()) throw ApiException.bad("El archivo está vacío");
            List<String> head = grid.get(0); List<Map<String, String>> out = new ArrayList<>();
            for (int i = 1; i < grid.size(); i++) {
                Map<String, String> row = new LinkedHashMap<>();
                boolean vacia = true;
                for (int j = 0; j < head.size(); j++) { String v = j < grid.get(i).size() ? grid.get(i).get(j) : ""; if (!v.isEmpty()) vacia = false; row.put(head.get(j), v); }
                if (vacia) continue;
                row.put("__fila", String.valueOf(nums.get(i)));
                out.add(row);
            }
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("No se pudo leer el archivo Excel (.xlsx): " + e.getMessage()); }
    }
    private static int colIndex(String ref) { int n = 0; for (char ch : ref.toCharArray()) { if (!Character.isLetter(ch)) break; n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1); } return n - 1; }
    private static Document dom(byte[] b) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));
    }

    // ============================================================ normalización de filas
    private static String hn(String s) { return CalificacionService.norm(s).replace(' ', '_'); }

    static String fecha(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.matches("\\d{4,6}")) { try { return LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(s)).toString(); } catch (Exception e) { return s; } }   // serie de Excel
        for (String p : new String[]{"yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy"}) {
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p)).toString(); } catch (Exception ignore) { }
        }
        return s;
    }

    static String mercado(String s, String def) {
        if (s == null || s.isBlank()) return def;
        return switch (CalificacionService.norm(s)) { case "cl", "chile" -> "CL"; case "pe", "peru" -> "PE"; case "co", "colombia" -> "CO"; default -> s.trim().toUpperCase(); };
    }

    /** Convierte una fila de texto al mapa de datos que entiende CalificacionService. */
    static Map<String, Object> convertir(Map<String, String> raw, String periodoDef, String mercadoDef) {
        Map<String, String> r = new HashMap<>();
        raw.forEach((k, v) -> r.put(hn(k), v));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mercado", mercado(r.getOrDefault("mercado", r.get("pais")), mercadoDef));
        String per = r.get("periodo"); if (per == null || per.isBlank()) per = periodoDef;
        if (per != null && per.matches("\\d+\\.0")) per = per.substring(0, per.length() - 2);
        d.put("periodo", per);
        d.put("instrumento", r.get("instrumento"));
        String sq = r.get("secuencia"); if (sq != null && sq.matches("\\d+\\.0")) sq = sq.substring(0, sq.length() - 2);
        d.put("secuencia", sq);
        d.put("fechaPago", fecha(r.getOrDefault("fecha_pago", r.get("fechapago"))));
        d.put("descripcion", r.get("descripcion"));
        d.put("acogidoIsfut", r.getOrDefault("acogido_isfut", r.get("isfut")));
        d.put("origen", r.get("origen"));
        d.put("tipoDj", r.getOrDefault("tipo_dj", r.get("dj")));
        Map<String, Object> fs = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        for (int n = 8; n <= 36; n++) {
            String m = r.get("m" + n), f = r.get("f" + n);
            if ((m != null && !m.isBlank()) || (f != null && !f.isBlank())) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("monto", m == null || m.isBlank() ? null : m); e.put("valor", f == null || f.isBlank() ? null : f);
                fs.put(String.valueOf(n), e);
            }
        }
        d.put("factores", fs);
        return d;
    }

    /** Si hay montos pero no factores, calcula los factores con la fórmula del país (HU-3 aplicado en lote). */
    @SuppressWarnings("unchecked")
    static void completarFactores(java.sql.Connection c, Map<String, Object> d) throws Exception {
        Map<String, Object> fs = (Map<String, Object>) d.get("factores");
        boolean hayMontos = false, hayValores = false;
        for (Object o : fs.values()) { Map<String, Object> e = (Map<String, Object>) o; if (e.get("monto") != null) hayMontos = true; if (e.get("valor") != null) hayValores = true; }
        if (hayMontos && !hayValores) {
            Map<String, Object> mo = new LinkedHashMap<>();
            fs.forEach((k, v) -> mo.put(k, ((Map<String, Object>) v).get("monto")));
            Map<String, Object> calc = CalificacionService.calcular(c, CalificacionService.m("mercado", d.get("mercado"), "montos", mo));
            Map<String, Object> vals = (Map<String, Object>) calc.get("factores");
            for (Map.Entry<String, Object> e : vals.entrySet()) {
                Map<String, Object> x = (Map<String, Object>) fs.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                x.put("valor", e.getValue());
            }
        }
    }

    // ============================================================ procesamiento
    public static Map<String, Object> procesarArchivo(Principal p, String nombre, byte[] contenido, String periodoDef, String mercadoDef) throws Exception {
        String lower = nombre.toLowerCase();
        String tipo = lower.endsWith(".xlsx") ? "XLSX" : lower.endsWith(".csv") || lower.endsWith(".txt") ? "CSV" : null;
        if (tipo == null) throw ApiException.bad("Formato no soportado. Use un archivo .csv o .xlsx");
        List<Map<String, String>> raw = tipo.equals("XLSX") ? parseXlsx(contenido) : parseCsv(new String(contenido, StandardCharsets.UTF_8));
        if (raw.isEmpty()) throw ApiException.bad("El archivo no contiene filas de datos");
        if (raw.size() > MAX_FILAS) throw ApiException.bad("El archivo supera el máximo de " + MAX_FILAS + " filas");
        List<Map<String, Object>> filas = new ArrayList<>();
        for (Map<String, String> r : raw) { Map<String, Object> d = convertir(r, periodoDef, mercadoDef); d.put("__fila", Integer.parseInt(r.get("__fila"))); filas.add(d); }
        return procesar(p, tipo, nombre, contenido, periodoDef, filas, false, "MASIVA");
    }

    /**
     * Procesa fila por fila: cada fila se registra individualmente en el detalle de la carga y en el log de auditoría (HU-5.3).
     * marcarErrores=true (importación SII): las filas con formato inválido se guardan con estado ERROR para revisión manual (HU-15.4).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> procesar(Principal p, String tipo, String nombre, byte[] contenido, String periodo, List<Map<String, Object>> filas, boolean marcarErrores, String evento) throws Exception {
        String nombreSeguro = nombre.replaceAll("[^A-Za-z0-9._-]", "_");
        int cargaId = Db.tx(c -> Db.insert(c, "INSERT INTO carga_masiva (usuario_id, tipo, nombre_archivo, estado, periodo_comercial, total_filas) VALUES (?,?,?, 'PROCESANDO', ?, ?) RETURNING carga_masiva_id",
                p.userId(), tipo, nombre, periodo, filas.size()));
        Path ruta = null;
        if (contenido != null) {
            Files.createDirectories(Paths.get(Config.UPLOAD_DIR));
            ruta = Paths.get(Config.UPLOAD_DIR, cargaId + "_" + nombreSeguro);
            Files.write(ruta, contenido);
        }
        final Path rutaF = ruta;
        Db.tx(c -> { Db.exec(c, "UPDATE carga_masiva SET ruta_archivo = ? WHERE carga_masiva_id = ?", rutaF == null ? null : rutaF.toString(), cargaId);
            Audit.log(c, p.userId(), "CARGA_INICIO", "carga_masiva", cargaId, tipo + " '" + nombre + "' con " + filas.size() + " fila(s)", evento); return null; });

        int ok = 0, err = 0; List<Map<String, Object>> detalle = new ArrayList<>();
        for (Map<String, Object> d : filas) {
            int fila = ((Number) d.getOrDefault("__fila", 0)).intValue();
            Integer secuencia = null; try { secuencia = Integer.valueOf(String.valueOf(d.get("secuencia"))); } catch (Exception ignore) { }
            String estado = "OK", motivo = null; Integer instId = null; Integer califId = null;
            try {
                califId = Db.tx(c -> { completarFactores(c, d); return CalificacionService.crear(c, p, d, false, evento, cargaId); });
            } catch (ApiException e) {
                if (marcarErrores && (e.status == 422 || e.status == 400)) {
                    try {
                        String inst = d.get("instrumento") == null ? null : d.get("instrumento").toString();
                        String merc = d.get("mercado") == null || !CalificacionService.MERCADOS.contains(d.get("mercado").toString()) ? "CL" : d.get("mercado").toString();
                        final String motivoF = e.getMessage();
                        Integer cid = Db.tx(c -> CalificacionService.crearConError(c, p, inst, merc, periodo, motivoF, evento));
                        estado = "ERROR"; motivo = "Marcado para revisión manual (calificación #" + cid + "): " + e.getMessage();
                    } catch (Exception e2) { estado = "ERROR"; motivo = e.getMessage(); }
                } else { estado = "ERROR"; motivo = e.getMessage(); }
            } catch (Exception e) { estado = "ERROR"; motivo = "Error interno al procesar la fila"; }
            if (estado.equals("OK")) ok++; else { err++; Audit.logNew(p.userId(), "CARGA_FILA_ERROR", "carga_masiva", cargaId, "Fila " + fila + ": " + motivo, evento); }
            final String est = estado, mot = motivo; final Integer sq = secuencia; final Integer cid = califId;
            Db.tx(c -> {
                Db.exec(c, "INSERT INTO carga_masiva_detalle (carga_masiva_id, instrumento_id, fila, secuencia, estado, motivo_error) VALUES (?,?,?,?,?,?)",
                        cargaId, cid == null ? null : Db.one(c, "SELECT instrumento_id FROM calificacion WHERE calificacion_id = ?", cid).get("instrumento_id"), fila, sq, est, mot);
                return null; });
            Map<String, Object> dr = new LinkedHashMap<>(); dr.put("fila", fila); dr.put("secuencia", secuencia); dr.put("estado", estado); dr.put("motivo", motivo); dr.put("calificacionId", califId);
            detalle.add(dr);
        }
        String fin = err == 0 ? "COMPLETADA" : ok == 0 ? "FALLIDA" : "COMPLETADA_CON_ERRORES";
        final int okF = ok, errF = err;
        Db.tx(c -> { Db.exec(c, "UPDATE carga_masiva SET estado = ?, filas_ok = ?, filas_error = ? WHERE carga_masiva_id = ?", fin, okF, errF, cargaId);
            Audit.log(c, p.userId(), "CARGA_FIN", "carga_masiva", cargaId, "Resultado " + fin + ": " + okF + " correctas, " + errF + " con error", evento); return null; });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cargaId", cargaId); out.put("estado", fin); out.put("total", filas.size()); out.put("ok", ok); out.put("errores", err); out.put("detalle", detalle);
        return out;
    }
}
