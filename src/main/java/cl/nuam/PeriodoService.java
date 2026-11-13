package cl.nuam;

import cl.nuam.Auth.Principal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** HU-7 notificaciones, HU-9 dashboard, HU-18 aprobación y cierre de periodos. */
public final class PeriodoService {
    private PeriodoService() {}
    static Map<String, Object> m(Object... kv) { return CalificacionService.m(kv); }

    // ============================================================ periodos
    public static List<Map<String, Object>> listar(Connection c, Principal p) throws Exception {
        if (p.esDeCorredora()) {
            int y = LocalDate.now().getYear();
            for (int a : new int[]{y - 1, y}) CalificacionService.asegurarPeriodo(c, p.corredoraId(), String.valueOf(a));
        }
        String where = p.esDeCorredora() ? "WHERE pe.corredora_id = ?" : "";
        List<Map<String, Object>> rows = Db.q(c, "SELECT pe.periodo_id AS id, pe.corredora_id, co.nombre AS corredora, pe.periodo_comercial AS periodo, pe.estado, pe.fecha_limite_dj1948 AS fecha_limite, pe.fecha_cierre FROM periodo pe " +
                "JOIN corredora co USING (corredora_id) " + where + " ORDER BY pe.periodo_comercial DESC, co.nombre", p.esDeCorredora() ? new Object[]{p.corredoraId()} : new Object[0]);
        LocalDate hoy = LocalDate.now();
        for (Map<String, Object> r : rows) {
            Map<String, Object> cnt = Db.one(c, "SELECT count(*) AS total, count(*) FILTER (WHERE estado IN " + CalificacionService.PENDIENTES_SQL + ") AS pendientes, count(*) FILTER (WHERE estado = 'APROBADA') AS aprobadas " +
                    "FROM calificacion WHERE NOT eliminada AND corredora_id = ? AND periodo_comercial = ?", r.get("corredora_id"), r.get("periodo"));
            r.putAll(cnt);
            LocalDate lim = ((java.sql.Date) r.get("fecha_limite")).toLocalDate();
            long dias = ChronoUnit.DAYS.between(hoy, lim);
            r.put("dias_restantes", dias);
            r.put("vencido", dias < 0 && !"CERRADO".equals(r.get("estado")));
        }
        return rows;
    }

    private static Map<String, Object> periodo(Connection c, Principal p, int id) throws Exception {
        Map<String, Object> r = Db.one(c, "SELECT * FROM periodo WHERE periodo_id = ?", id);
        if (r == null || (p.esDeCorredora() && Db.i(r.get("corredora_id")) != p.corredoraId())) throw ApiException.notFound("Periodo no encontrado");
        return r;
    }

    public static void definirLimite(Connection c, Principal p, int id, String fecha) throws Exception {
        Map<String, Object> r = periodo(c, p, id);
        LocalDate f; try { f = LocalDate.parse(fecha); } catch (Exception e) { throw ApiException.invalid("Fecha inválida"); }
        Db.exec(c, "UPDATE periodo SET fecha_limite_dj1948 = ? WHERE periodo_id = ?", f, id);
        Audit.log(c, p, "PERIODO_LIMITE", "periodo", id, "Fecha límite DJ 1948 del periodo " + r.get("periodo_comercial") + " (corredora #" + r.get("corredora_id") + ") = " + f);
    }

    public static void solicitarCierre(Connection c, Principal p, int id) throws Exception {
        if (!p.is("CORREDOR", "SUPERVISOR")) throw ApiException.forbidden("No puede solicitar el cierre");
        Map<String, Object> r = periodo(c, p, id);
        if (!"ABIERTO".equals(r.get("estado"))) throw ApiException.bad("El periodo no está abierto (estado actual: " + r.get("estado") + ")");
        Map<String, Object> cnt = Db.one(c, "SELECT count(*) AS total, count(*) FILTER (WHERE estado IN " + CalificacionService.PENDIENTES_SQL + ") AS pend FROM calificacion WHERE NOT eliminada AND corredora_id = ? AND periodo_comercial = ?", r.get("corredora_id"), r.get("periodo_comercial"));
        if (Db.i(cnt.get("total")) == 0) throw ApiException.invalid("El periodo no tiene calificaciones para cerrar");
        if (Db.i(cnt.get("pend")) > 0) throw ApiException.invalid("No se puede solicitar el cierre: hay " + cnt.get("pend") + " calificación(es) pendientes (incompletas, rechazadas, con errores o por revisar)");
        Db.exec(c, "UPDATE periodo SET estado = 'EN_REVISION' WHERE periodo_id = ?", id);
        Audit.log(c, p, "PERIODO_SOLICITA_CIERRE", "periodo", id, "Solicitud de cierre del periodo " + r.get("periodo_comercial") + ": requiere aprobación de supervisor");
        for (Map<String, Object> s : Db.q(c, "SELECT usuario_id FROM usuario WHERE corredora_id = ? AND rol = 'SUPERVISOR' AND activo", r.get("corredora_id")))
            Db.exec(c, "INSERT INTO notificacion (usuario_id, tipo, mensaje, periodo_comercial, url) VALUES (?, 'APROBACION', ?, ?, ?) ON CONFLICT DO NOTHING", s.get("usuario_id"),
                    "El periodo " + r.get("periodo_comercial") + " está pendiente de su aprobación antes del cierre.", r.get("periodo_comercial"), "#periodos");
    }

    public static List<Map<String, Object>> pendientesAprobacion(Connection c, Principal p) throws Exception {
        if (!p.is("SUPERVISOR")) throw ApiException.forbidden("Solo un supervisor puede revisar aprobaciones");
        List<Map<String, Object>> rows = Db.q(c, "SELECT c.calificacion_id AS id, i.nombre AS instrumento, i.mercado, c.secuencia, c.periodo_comercial AS periodo, c.estado, c.descripcion, u.nombre AS ingresado_por, " +
                "(SELECT COALESCE(SUM(f.valor),0) FROM factor f WHERE f.calificacion_id = c.calificacion_id AND f.numero_factor BETWEEN 8 AND 19) AS suma_8_19 " +
                "FROM calificacion c JOIN instrumento i USING (instrumento_id) JOIN periodo pe ON pe.corredora_id = c.corredora_id AND pe.periodo_comercial = c.periodo_comercial " +
                "LEFT JOIN usuario u ON u.usuario_id = c.usuario_id WHERE NOT c.eliminada AND c.corredora_id = ? AND pe.estado = 'EN_REVISION' AND c.estado IN ('COMPLETA','APROBADA','RECHAZADA') " +
                "ORDER BY c.estado, i.nombre, c.secuencia", p.corredoraId());
        for (Map<String, Object> r : rows) r.put("pais", CalificacionService.pais((String) r.get("mercado")));
        return rows;
    }

    private static Map<String, Object> paraAprobar(Connection c, Principal p, int id) throws Exception {
        if (!p.is("SUPERVISOR")) throw ApiException.forbidden("Solo un supervisor puede aprobar o rechazar");
        Map<String, Object> r = Db.one(c, "SELECT c.*, i.nombre AS instrumento FROM calificacion c JOIN instrumento i USING (instrumento_id) WHERE c.calificacion_id = ? AND NOT c.eliminada AND c.corredora_id = ?", id, p.corredoraId());
        if (r == null) throw ApiException.notFound("Calificación no encontrada");
        Map<String, Object> per = Db.one(c, "SELECT * FROM periodo WHERE corredora_id = ? AND periodo_comercial = ?", p.corredoraId(), r.get("periodo_comercial"));
        if (per == null || !"EN_REVISION".equals(per.get("estado"))) throw ApiException.bad("El periodo no tiene una solicitud de cierre pendiente de aprobación");
        r.put("periodo_id", per.get("periodo_id"));
        return r;
    }

    public static Map<String, Object> aprobar(Connection c, Principal p, int id) throws Exception {
        Map<String, Object> r = paraAprobar(c, p, id);
        if (!"COMPLETA".equals(r.get("estado"))) throw ApiException.bad("Solo se pueden aprobar calificaciones en estado COMPLETA (actual: " + r.get("estado") + ")");
        Db.exec(c, "UPDATE calificacion SET estado = 'APROBADA', fecha_modificacion = now() WHERE calificacion_id = ?", id);
        CalificacionService.nuevaVersion(c, p, id, "APROBAR", null);
        Audit.log(c, p, "APROBAR", "calificacion", id, "Aprobada: " + r.get("instrumento") + " sec " + r.get("secuencia") + " periodo " + r.get("periodo_comercial"));
        long falta = Db.count(c, "SELECT count(*) FROM calificacion WHERE NOT eliminada AND corredora_id = ? AND periodo_comercial = ? AND estado <> 'APROBADA'", p.corredoraId(), r.get("periodo_comercial"));
        boolean cerrado = false;
        if (falta == 0) {   // HU-18.3
            Db.exec(c, "UPDATE periodo SET estado = 'CERRADO', fecha_cierre = now() WHERE periodo_id = ?", r.get("periodo_id"));
            Audit.log(c, p, "PERIODO_CERRADO", "periodo", Db.i(r.get("periodo_id")), "Periodo " + r.get("periodo_comercial") + " cerrado: todas las calificaciones aprobadas");
            cerrado = true;
        }
        return m("ok", true, "periodoCerrado", cerrado, "faltanPorAprobar", falta);
    }

    public static void rechazar(Connection c, Principal p, int id, String motivo) throws Exception {
        Map<String, Object> r = paraAprobar(c, p, id);
        if (motivo == null || motivo.isBlank()) throw ApiException.invalid("Debe indicar el motivo del rechazo").with("campo", "motivo");
        if ("INCOMPLETA".equals(r.get("estado"))) throw ApiException.bad("La calificación está incompleta");
        Db.exec(c, "UPDATE calificacion SET estado = 'RECHAZADA', fecha_modificacion = now() WHERE calificacion_id = ?", id);
        Db.exec(c, "INSERT INTO comentario (calificacion_id, usuario_id, texto) VALUES (?,?,?)", id, p.userId(), "Rechazada por supervisor: " + motivo.trim());   // HU-18.2
        CalificacionService.nuevaVersion(c, p, id, "RECHAZAR", null);
        Audit.log(c, p, "RECHAZAR", "calificacion", id, "Rechazada: " + motivo.trim());
        if (r.get("usuario_id") != null)
            Db.exec(c, "INSERT INTO notificacion (usuario_id, tipo, mensaje, periodo_comercial, url) VALUES (?, 'RECHAZO', ?, ?, ?) ON CONFLICT DO NOTHING", r.get("usuario_id"),
                    "El supervisor rechazó '" + r.get("instrumento") + "' (sec " + r.get("secuencia") + "): " + motivo.trim(), r.get("periodo_comercial"), "#mantenedor?periodo=" + r.get("periodo_comercial") + "&pendientes=1");
    }

    public static void reabrir(Connection c, Principal p, int id, String motivo) throws Exception {
        if (!p.is("ADMIN")) throw ApiException.forbidden("Solo un administrador puede reabrir un periodo");
        Map<String, Object> r = periodo(c, p, id);
        if (!"CERRADO".equals(r.get("estado"))) throw ApiException.bad("El periodo no está cerrado");
        if (motivo == null || motivo.isBlank()) throw ApiException.invalid("Debe indicar el motivo de la reapertura").with("campo", "motivo");
        Db.exec(c, "UPDATE periodo SET estado = 'ABIERTO', fecha_cierre = NULL WHERE periodo_id = ?", id);
        Db.exec(c, "UPDATE calificacion SET estado = 'COMPLETA' WHERE NOT eliminada AND corredora_id = ? AND periodo_comercial = ? AND estado = 'APROBADA'", r.get("corredora_id"), r.get("periodo_comercial"));
        Audit.log(c, p, "PERIODO_REABIERTO", "periodo", id, "Periodo " + r.get("periodo_comercial") + " (corredora #" + r.get("corredora_id") + ") reabierto. Motivo: " + motivo.trim());   // HU-18.5
    }

    // ============================================================ HU-7 notificaciones
    public static void generarVencimientos(Connection c) throws Exception {
        List<Map<String, Object>> pers = Db.q(c, "SELECT periodo_id, corredora_id, periodo_comercial, (fecha_limite_dj1948 - current_date) AS dias FROM periodo WHERE estado <> 'CERRADO' AND fecha_limite_dj1948 - current_date BETWEEN 0 AND 10");
        for (Map<String, Object> pe : pers) {
            long pend = Db.count(c, "SELECT count(*) FROM calificacion WHERE NOT eliminada AND corredora_id = ? AND periodo_comercial = ? AND estado IN " + CalificacionService.PENDIENTES_SQL, pe.get("corredora_id"), pe.get("periodo_comercial"));
            if (pend == 0) continue;   // HU-7.2: si ya completó, no se notifica
            int dias = Db.i(pe.get("dias"));
            String msg = "Faltan " + dias + " día(s) para el vencimiento de la DJ 1948 (periodo " + pe.get("periodo_comercial") + "). Tiene " + pend + " calificación(es) pendiente(s).";   // HU-7.3
            for (Map<String, Object> u : Db.q(c, "SELECT usuario_id FROM usuario WHERE corredora_id = ? AND rol = 'CORREDOR' AND activo", pe.get("corredora_id")))
                Db.exec(c, "INSERT INTO notificacion (usuario_id, tipo, mensaje, pendientes, periodo_comercial, url) VALUES (?, 'VENCIMIENTO', ?, ?, ?, ?) ON CONFLICT (usuario_id, tipo, periodo_comercial, dia) DO UPDATE SET mensaje = EXCLUDED.mensaje, pendientes = EXCLUDED.pendientes",
                        u.get("usuario_id"), msg, (int) pend, pe.get("periodo_comercial"), "#mantenedor?periodo=" + pe.get("periodo_comercial") + "&pendientes=1");
        }
    }

    public static Map<String, Object> notificaciones(Connection c, Principal p) throws Exception {
        generarVencimientos(c);
        List<Map<String, Object>> l = Db.q(c, "SELECT notificacion_id AS id, tipo, mensaje, pendientes, periodo_comercial AS periodo, url, leida, fecha FROM notificacion WHERE usuario_id = ? ORDER BY fecha DESC LIMIT 30", p.userId());
        long nl = l.stream().filter(r -> !(Boolean) r.get("leida")).count();
        return m("noLeidas", nl, "items", l);
    }

    public static Map<String, Object> leer(Connection c, Principal p, int id) throws Exception {
        Map<String, Object> n = Db.one(c, "SELECT url FROM notificacion WHERE notificacion_id = ? AND usuario_id = ?", id, p.userId());
        if (n == null) throw ApiException.notFound("Notificación no encontrada");
        Db.exec(c, "UPDATE notificacion SET leida = TRUE WHERE notificacion_id = ?", id);
        return m("url", n.get("url"));   // HU-7.4: el cliente redirige al mantenedor filtrado por pendientes
    }

    /** HU-7.5: alerta visible en el mantenedor mientras exista un plazo vencido con registros pendientes. */
    public static List<Map<String, Object>> alertas(Connection c, Principal p) throws Exception {
        if (!p.esDeCorredora()) return List.of();
        return Db.q(c, "SELECT pe.periodo_comercial AS periodo, pe.fecha_limite_dj1948 AS fecha_limite, (current_date - pe.fecha_limite_dj1948) AS dias_vencido, " +
                "(SELECT count(*) FROM calificacion k WHERE NOT k.eliminada AND k.corredora_id = pe.corredora_id AND k.periodo_comercial = pe.periodo_comercial AND k.estado IN " + CalificacionService.PENDIENTES_SQL + ") AS pendientes " +
                "FROM periodo pe WHERE pe.corredora_id = ? AND pe.estado <> 'CERRADO' AND pe.fecha_limite_dj1948 < current_date AND EXISTS (SELECT 1 FROM calificacion k WHERE NOT k.eliminada AND k.corredora_id = pe.corredora_id AND k.periodo_comercial = pe.periodo_comercial AND k.estado IN " + CalificacionService.PENDIENTES_SQL + ")",
                p.corredoraId());
    }

    // ============================================================ HU-9 dashboard
    public static Map<String, Object> dashboard(Connection c, Principal p, String periodo) throws Exception {
        if (!p.is("CORREDOR", "SUPERVISOR", "ADMIN")) throw ApiException.forbidden("Sin acceso al dashboard");
        if (periodo == null) periodo = String.valueOf(LocalDate.now().getYear());   // periodo comercial vigente
        List<Object> prm = new ArrayList<>();
        String sc = CalificacionService.scope(p, prm); prm.add(periodo);
        Map<String, Object> cnt = Db.one(c, "SELECT count(*) AS total, count(*) FILTER (WHERE c.estado IN ('INCOMPLETA','PENDIENTE_REVISION','RECHAZADA')) AS pendientes, count(*) FILTER (WHERE c.estado = 'ERROR') AS con_error, " +
                "count(*) FILTER (WHERE c.estado = 'COMPLETA') AS completas, count(*) FILTER (WHERE c.estado = 'APROBADA') AS aprobadas FROM calificacion c WHERE NOT c.eliminada AND " + sc + " AND c.periodo_comercial = ?", prm.toArray());
        long filasErr = p.esDeCorredora() ? Db.count(c, "SELECT count(*) FROM carga_masiva_detalle d JOIN carga_masiva cm USING (carga_masiva_id) JOIN usuario u ON u.usuario_id = cm.usuario_id " +
                "WHERE u.corredora_id = ? AND cm.periodo_comercial = ? AND d.estado = 'ERROR'", p.corredoraId(), periodo) : 0;
        List<Object> prm2 = new ArrayList<>(); String sc2 = CalificacionService.scope(p, prm2); prm2.add(periodo);
        List<Map<String, Object>> pm = Db.q(c, "SELECT i.mercado, count(*) AS total FROM calificacion c JOIN instrumento i USING (instrumento_id) WHERE NOT c.eliminada AND " + sc2 + " AND c.periodo_comercial = ? GROUP BY i.mercado ORDER BY i.mercado", prm2.toArray());
        for (Map<String, Object> r : pm) r.put("pais", CalificacionService.pais((String) r.get("mercado")));
        List<Object> prm3 = new ArrayList<>(); String sc3 = CalificacionService.scope(p, prm3);
        List<Object> periodos = new ArrayList<>();
        for (Map<String, Object> r : Db.q(c, "SELECT DISTINCT c.periodo_comercial FROM calificacion c WHERE NOT c.eliminada AND " + sc3 + " ORDER BY 1 DESC", prm3.toArray())) periodos.add(r.get("periodo_comercial"));
        String vig = String.valueOf(LocalDate.now().getYear());
        if (!periodos.contains(vig)) periodos.add(0, vig);
        long errores = Db.i(cnt.get("con_error")) + filasErr;
        return m("periodo", periodo, "total", cnt.get("total"), "pendientes", cnt.get("pendientes"), "completas", cnt.get("completas"), "aprobadas", cnt.get("aprobadas"),
                "errores", m("total", errores, "calificaciones", cnt.get("con_error"), "filasCarga", filasErr), "porMercado", pm, "periodos", periodos,
                "vacio", Db.i(cnt.get("total")) == 0 && errores == 0, "actualizado", new java.sql.Timestamp(System.currentTimeMillis()));
    }
}
