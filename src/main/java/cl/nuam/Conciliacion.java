package cl.nuam;

import cl.nuam.Auth.Principal;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.regex.*;

/** HU-17: conciliación del certificado del emisor contra la declaración jurada del SII. */
public final class Conciliacion {
    private Conciliacion() {}
    private static final BigDecimal TOL = new BigDecimal("0.005");
    private static final Pattern LINEA = Pattern.compile("^\\s*(\\d{1,2})\\s*[;:=\\t ]\\s*(-?[\\d.,]+)\\s*$");

    /** Acepta {"8":1000,...} o texto con líneas "factor;monto". */
    static Map<Integer, BigDecimal> parse(Object o, String etiqueta) {
        Map<Integer, BigDecimal> out = new TreeMap<>();
        if (o instanceof Map<?, ?> mm) {
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                BigDecimal v = Json.dec(e.getValue());
                if (v == null) throw ApiException.invalid("Monto inválido en " + etiqueta + " (factor " + e.getKey() + ")");
                out.put(Integer.parseInt(e.getKey().toString()), v);
            }
        } else if (o != null) {
            int ln = 0;
            for (String l : o.toString().split("\\R")) {
                ln++; if (l.isBlank()) continue;
                Matcher m = LINEA.matcher(l);
                if (!m.matches()) throw ApiException.invalid("Línea " + ln + " del " + etiqueta + " no tiene el formato 'factor;monto'");
                String num = m.group(2);
                if (num.indexOf('.') < 0 && num.indexOf(',') >= 0 && num.indexOf(',') == num.lastIndexOf(',')) num = num.replace(',', '.');
                out.put(Integer.parseInt(m.group(1)), new BigDecimal(num.replace(",", "")));
            }
        }
        for (int n : out.keySet()) if (n < Formulas.F_MIN || n > Formulas.F_MAX) throw ApiException.invalid("El " + etiqueta + " contiene el factor " + n + " (válidos: 8 a 36)");
        return out;
    }

    public static Map<String, Object> conciliar(Connection c, Principal p, int id, Map<String, Object> d) throws Exception {
        Map<String, Object> r = CalificacionService.cargar(c, p, id, false);
        if (!CalificacionService.puedeEditar(p, r)) throw ApiException.forbidden("No puede conciliar esta calificación");
        CalificacionService.verificarPeriodoAbierto(c, (Integer) r.get("corredora_id"), (String) r.get("periodo"));
        Map<Integer, BigDecimal> cert = parse(d.get("certificado"), "certificado del emisor"), dj = parse(d.get("dj"), "documento de la DJ del SII");
        if (cert.isEmpty() || dj.isEmpty()) throw ApiException.invalid("Debe cargar ambos documentos (certificado del emisor y declaración jurada) para comparar");
        List<String> difs = new ArrayList<>();
        Set<Integer> ks = new TreeSet<>(cert.keySet()); ks.addAll(dj.keySet());
        for (int n : ks) {
            BigDecimal a = cert.get(n), b = dj.get(n);
            if (a == null || b == null || a.subtract(b).abs().compareTo(TOL) > 0)
                difs.add("Factor " + n + ": certificado " + (a == null ? "—" : a.stripTrailingZeros().toPlainString()) + " vs DJ " + (b == null ? "—" : b.stripTrailingZeros().toPlainString()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = difs.isEmpty();
        Db.insert(c, "INSERT INTO conciliacion (calificacion_id, usuario_id, montos_certificado, montos_dj, diferencias, estado) VALUES (?,?,?,?,?,?) RETURNING conciliacion_id",
                id, p.userId(), Json.write(cert), Json.write(dj), ok ? null : String.join("\n", difs), ok ? "COINCIDE" : "DISCREPANCIA");
        if (ok) {
            out.put("estado", "COINCIDE"); out.put("mensaje", "Los datos coinciden. Puede continuar con el cálculo sin advertencias.");
            Map<String, Object> mo = new LinkedHashMap<>(); cert.forEach((k, v) -> mo.put(String.valueOf(k), v)); out.put("montos", mo);
        } else {
            Db.exec(c, "UPDATE calificacion SET estado = 'PENDIENTE_REVISION', fecha_modificacion = now() WHERE calificacion_id = ?", id);   // HU-17.2
            out.put("estado", "DISCREPANCIA"); out.put("diferencias", difs);
            out.put("mensaje", "Se detectaron " + difs.size() + " discrepancia(s). La calificación quedó 'pendiente de revisión'.");
        }
        Audit.log(c, p, "CONCILIAR", "calificacion", id, ok ? "Conciliación sin diferencias" : "Discrepancias: " + String.join(" | ", difs));
        return out;
    }

    /** HU-17.4: se indica qué fuente se usó y por qué. */
    public static void resolver(Connection c, Principal p, int id, String fuente, String motivo) throws Exception {
        Map<String, Object> r = CalificacionService.cargar(c, p, id, false);
        if (!CalificacionService.puedeEditar(p, r)) throw ApiException.forbidden("No puede resolver esta discrepancia");
        CalificacionService.verificarPeriodoAbierto(c, (Integer) r.get("corredora_id"), (String) r.get("periodo"));
        if (fuente == null || !(fuente.equals("CERTIFICADO") || fuente.equals("DJ"))) throw ApiException.invalid("Indique la fuente utilizada (CERTIFICADO o DJ)").with("campo", "fuente");
        if (motivo == null || motivo.isBlank()) throw ApiException.invalid("Debe indicar el motivo de la decisión").with("campo", "motivo");
        Map<String, Object> k = Db.one(c, "SELECT * FROM conciliacion WHERE calificacion_id = ? AND estado = 'DISCREPANCIA' ORDER BY conciliacion_id DESC LIMIT 1", id);
        if (k == null) throw ApiException.bad("La calificación no tiene discrepancias abiertas");
        @SuppressWarnings("unchecked") Map<String, Object> src = (Map<String, Object>) Json.parse((String) k.get(fuente.equals("DJ") ? "montos_dj" : "montos_certificado"));
        Db.exec(c, "DELETE FROM factor WHERE calificacion_id = ?", id);
        for (Map.Entry<String, Object> e : src.entrySet()) Db.exec(c, "INSERT INTO factor (calificacion_id, numero_factor, monto, valor) VALUES (?,?,?,NULL)", id, Integer.parseInt(e.getKey()), Json.dec(e.getValue()));
        Db.exec(c, "UPDATE conciliacion SET estado='RESUELTA', fuente_usada=?, motivo=?, fecha_resolucion=now() WHERE conciliacion_id=?", fuente, motivo.trim(), k.get("conciliacion_id"));
        Db.exec(c, "UPDATE calificacion SET estado = 'INCOMPLETA', fecha_modificacion = now() WHERE calificacion_id = ?", id);   // los montos cambiaron: corresponde recalcular
        CalificacionService.nuevaVersion(c, p, id, "CONCILIAR", null);
        Db.exec(c, "INSERT INTO comentario (calificacion_id, usuario_id, texto) VALUES (?,?,?)", id, p.userId(), "Conciliación resuelta con fuente " + fuente + ". Motivo: " + motivo.trim());
        Audit.log(c, p, "CONCILIAR_RESUELTA", "calificacion", id, "Discrepancia resuelta usando " + fuente + ". Motivo: " + motivo.trim());
    }
}
