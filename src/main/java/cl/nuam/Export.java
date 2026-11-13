package cl.nuam;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/** HU-6: exportación a Excel (.xlsx) y PDF sin librerías externas. Columnas en el mismo orden que la grilla. */
public final class Export {
    private Export() {}
    public static final String[] COLUMNAS = {"País", "Instrumento", "Secuencia", "Periodo", "Fecha de pago", "Descripción", "Origen", "Estado", "Suma factores 8-19", "DJ"};
    private static final String[] CLAVES = {"pais", "instrumento", "secuencia", "periodo", "fecha_pago", "descripcion", "origen", "estado", "suma_8_19", "tipo_dj"};

    static String celda(Map<String, Object> r, int i) {
        Object v = r.get(CLAVES[i]);
        if (v == null) return "";
        if (i == 7 && Boolean.TRUE.equals(r.get("discrepancia"))) return v + " (discrepancia)";
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return v.toString();
    }

    // ------------------------------------------------------------------ XLSX
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", ""); }
    static String ref(int col, int row) { return (char) ('A' + col) + "" + row; }

    public static byte[] xlsx(List<Map<String, Object>> filas) throws IOException {
        StringBuilder sh = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<cols><col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"28\" customWidth=\"1\"/><col min=\"3\" max=\"5\" width=\"16\" customWidth=\"1\"/><col min=\"6\" max=\"6\" width=\"46\" customWidth=\"1\"/><col min=\"7\" max=\"9\" width=\"20\" customWidth=\"1\"/></cols><sheetData>");
        sh.append("<row r=\"1\">");
        for (int i = 0; i < COLUMNAS.length; i++) sh.append("<c r=\"").append(ref(i, 1)).append("\" t=\"inlineStr\" s=\"1\"><is><t>").append(esc(COLUMNAS[i])).append("</t></is></c>");
        sh.append("</row>");
        int rn = 2;
        for (Map<String, Object> r : filas) {
            sh.append("<row r=\"").append(rn).append("\">");
            for (int i = 0; i < COLUMNAS.length; i++) {
                String v = celda(r, i);
                if ((i == 2 || i == 8) && !v.isEmpty()) sh.append("<c r=\"").append(ref(i, rn)).append("\"><v>").append(v).append("</v></c>");
                else sh.append("<c r=\"").append(ref(i, rn)).append("\" t=\"inlineStr\"><is><t>").append(esc(v)).append("</t></is></c>");
            }
            sh.append("</row>"); rn++;
        }
        sh.append("</sheetData></worksheet>");
        String ns = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", ns + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
        parts.put("_rels/.rels", ns + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("xl/workbook.xml", ns + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Calificaciones\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", ns + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        parts.put("xl/styles.xml", ns + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>");
        parts.put("xl/worksheets/sheet1.xml", sh.toString());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (Map.Entry<String, String> e : parts.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ PDF
    private static String pdfEsc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\').append(ch);
            else if (ch > 255) sb.append('?');
            else if (ch < 32) sb.append(' ');
            else if (ch > 126) sb.append(String.format("\\%03o", (int) ch));   // WinAnsi
            else sb.append(ch);
        }
        return sb.toString();
    }
    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…".replace('…', '.'); }

    public static byte[] pdf(List<Map<String, Object>> filas, String usuario, String filtros) throws IOException {
        final int W = 842, H = 595, MX = 30; final int[] x = {30, 82, 232, 272, 322, 382, 560, 610, 690, 780};
        final int[] maxc = {10, 30, 6, 8, 11, 50, 10, 18, 12, 5};   // caracteres por columna (Helvetica 7.5 pt)
        int porPagina = 28; int paginas = Math.max(1, (filas.size() + porPagina - 1) / porPagina);
        String generado = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        List<byte[]> objs = new ArrayList<>();
        // objetos: 1 catálogo, 2 páginas, 3 fuente, 4 fuente negrita, luego (página, contenido) por cada página
        for (int pg = 0; pg < paginas; pg++) {
            StringBuilder cs = new StringBuilder();
            cs.append("BT /F2 14 Tf ").append(MX).append(' ').append(H - 36).append(" Td (nuam - Calificaciones tributarias) Tj ET\n");
            cs.append("BT /F1 8 Tf ").append(MX).append(' ').append(H - 52).append(" Td (").append(pdfEsc("Fecha de generación: " + generado + "   Usuario: " + usuario + "   Registros: " + filas.size() + "   Página " + (pg + 1) + " de " + paginas)).append(") Tj ET\n");
            cs.append("BT /F1 8 Tf ").append(MX).append(' ').append(H - 63).append(" Td (").append(pdfEsc("Filtros: " + cut(filtros, 150))).append(") Tj ET\n");
            int y = H - 90;
            cs.append("0.88 0.92 0.97 rg ").append(MX - 4).append(' ').append(y - 4).append(' ').append(W - 2 * MX + 8).append(" 16 re f 0 g\n");
            for (int i = 0; i < COLUMNAS.length; i++) cs.append("BT /F2 7.5 Tf ").append(x[i]).append(' ').append(y).append(" Td (").append(pdfEsc(COLUMNAS[i])).append(") Tj ET\n");
            y -= 16;
            for (int k = pg * porPagina; k < Math.min(filas.size(), (pg + 1) * porPagina); k++) {
                for (int i = 0; i < COLUMNAS.length; i++)
                    cs.append("BT /F1 7.5 Tf ").append(x[i]).append(' ').append(y).append(" Td (").append(pdfEsc(cut(celda(filas.get(k), i), maxc[i]))).append(") Tj ET\n");
                y -= 15;
                cs.append("0.85 G 0.4 w ").append(MX - 4).append(' ').append(y + 11).append(" m ").append(W - MX + 4).append(' ').append(y + 11).append(" l S\n");
            }
            byte[] content = cs.toString().getBytes(StandardCharsets.ISO_8859_1);
            int pageObj = 5 + pg * 2, contObj = pageObj + 1;
            objs.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + W + " " + H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contObj + " 0 R >>").getBytes(StandardCharsets.ISO_8859_1));
            ByteArrayOutputStream st = new ByteArrayOutputStream();
            st.write(("<< /Length " + content.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1)); st.write(content); st.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            objs.add(st.toByteArray());
        }
        StringBuilder kids = new StringBuilder();
        for (int pg = 0; pg < paginas; pg++) kids.append(5 + pg * 2).append(" 0 R ");
        List<byte[]> all = new ArrayList<>();
        all.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes());
        all.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + paginas + " >>").getBytes());
        all.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes());
        all.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes());
        all.addAll(objs);
        ByteArrayOutputStream o = new ByteArrayOutputStream(); List<Integer> offs = new ArrayList<>();
        o.write("%PDF-1.4\n".getBytes());
        for (int i = 0; i < all.size(); i++) { offs.add(o.size()); o.write(((i + 1) + " 0 obj\n").getBytes()); o.write(all.get(i)); o.write("\nendobj\n".getBytes()); }
        int xr = o.size();
        o.write(("xref\n0 " + (all.size() + 1) + "\n0000000000 65535 f \n").getBytes());
        for (int off : offs) o.write(String.format("%010d 00000 n \n", off).getBytes());
        o.write(("trailer\n<< /Size " + (all.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xr + "\n%%EOF").getBytes());
        return o.toByteArray();
    }
}
