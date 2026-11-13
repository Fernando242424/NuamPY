package cl.nuam;

import java.sql.Connection;

/** HU-5: registro de auditoría (solo inserciones; la BD rechaza UPDATE/DELETE). */
public final class Audit {
    private Audit() {}

    public static void log(Connection c, Integer usuarioId, String accion, String entidad, Integer registroId, String detalle, String origen) throws java.sql.SQLException {
        Db.exec(c, "INSERT INTO auditoria (usuario_id, accion, entidad_afectada, registro_id, detalle, origen) VALUES (?,?,?,?,?,?)",
                usuarioId, accion, entidad, registroId, detalle, origen == null ? "WEB" : origen);
    }

    public static void log(Connection c, Auth.Principal p, String accion, String entidad, Integer registroId, String detalle) throws java.sql.SQLException {
        log(c, p == null ? null : p.userId(), accion, entidad, registroId, detalle, p == null ? "SISTEMA" : p.origen());
    }

    /** Registra en transacción propia (por ejemplo, tras un error que revierte la transacción principal). */
    public static void logNew(Integer usuarioId, String accion, String entidad, Integer registroId, String detalle, String origen) {
        try { Db.tx(c -> { log(c, usuarioId, accion, entidad, registroId, detalle, origen); return null; }); }
        catch (Exception e) { System.err.println("[AUDIT] no se pudo registrar " + accion + ": " + e); }
    }
}
