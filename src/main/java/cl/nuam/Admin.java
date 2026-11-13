package cl.nuam;

import cl.nuam.Auth.Principal;
import java.sql.Connection;
import java.util.*;

/** HU-8 gestión de usuarios, HU-19 tokens de API, HU-5 consulta del log de auditoría. */
public final class Admin {
    private Admin() {}
    static final Set<String> ROLES = Set.of("CORREDOR", "SUPERVISOR", "ADMIN", "AUDITOR");
    static Map<String, Object> m(Object... kv) { return CalificacionService.m(kv); }

    static void validarPassword(String pw) {
        if (pw == null || pw.length() < 10 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*"))
            throw ApiException.invalid("La contraseña debe tener al menos 10 caracteres e incluir letras y números").with("campo", "password");
    }

    // ============================================================ usuarios
    public static List<Map<String, Object>> usuarios(Connection c) throws Exception {
        return Db.q(c, "SELECT u.usuario_id AS id, u.nombre, u.correo, u.rol, u.activo, u.corredora_id, co.nombre AS corredora, u.totp_activo, u.ultimo_login, u.bloqueado_hasta " +
                "FROM usuario u LEFT JOIN corredora co USING (corredora_id) ORDER BY u.activo DESC, u.nombre");
    }

    public static List<Map<String, Object>> corredoras(Connection c) throws Exception { return Db.q(c, "SELECT corredora_id AS id, nombre FROM corredora ORDER BY nombre"); }

    private static Integer corredoraDe(Connection c, Map<String, Object> d, String rol) throws Exception {
        java.math.BigDecimal cid = Json.dec(d.get("corredoraId"));
        if (cid != null && Db.one(c, "SELECT 1 AS x FROM corredora WHERE corredora_id = ?", cid.intValue()) == null) throw ApiException.invalid("La corredora indicada no existe").with("campo", "corredoraId");
        if (rol != null && (rol.equals("CORREDOR") || rol.equals("SUPERVISOR")) && cid == null) throw ApiException.invalid("Los roles corredor y supervisor requieren una corredora").with("campo", "corredoraId");
        return cid == null ? null : cid.intValue();
    }

    public static Object crearUsuario(Connection c, Principal p, Map<String, Object> d) throws Exception {
        String nombre = Json.str(d, "nombre"), correo = Json.str(d, "correo"), rol = Json.str(d, "rol"), pw = Json.str(d, "password");
        if (nombre == null) throw ApiException.invalid("El nombre es obligatorio").with("campo", "nombre");
        if (correo == null || !correo.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.invalid("Correo inválido").with("campo", "correo");
        if (rol != null && !ROLES.contains(rol)) throw ApiException.invalid("Rol inválido").with("campo", "rol");
        validarPassword(pw);
        if (Db.count(c, "SELECT count(*) FROM usuario WHERE lower(correo) = lower(?)", correo) > 0) throw new ApiException(409, "Ya existe un usuario con ese correo").with("campo", "correo");
        Integer co = corredoraDe(c, d, rol);
        int id = Db.insert(c, "INSERT INTO usuario (corredora_id, nombre, correo, rol, password_hash) VALUES (?,?,?,?,?) RETURNING usuario_id", co, nombre, correo.toLowerCase(), rol, Crypto.hashPassword(pw));
        Audit.log(c, p, "USUARIO_CREAR", "usuario", id, "Usuario " + correo + " creado con rol " + (rol == null ? "(sin rol)" : rol));
        return m("id", id);
    }

    public static void actualizarUsuario(Connection c, Principal p, int id, Map<String, Object> d) throws Exception {
        Map<String, Object> u = Db.one(c, "SELECT * FROM usuario WHERE usuario_id = ?", id);
        if (u == null) throw ApiException.notFound("Usuario no encontrado");
        String nombre = d.containsKey("nombre") ? Json.str(d, "nombre") : (String) u.get("nombre");
        String rol = d.containsKey("rol") ? Json.str(d, "rol") : (String) u.get("rol");
        boolean activo = d.containsKey("activo") ? Json.bool(d.get("activo")) : (Boolean) u.get("activo");
        if (rol != null && !ROLES.contains(rol)) throw ApiException.invalid("Rol inválido").with("campo", "rol");
        if (nombre == null) throw ApiException.invalid("El nombre es obligatorio").with("campo", "nombre");
        if (id == p.userId() && (!activo || !"ADMIN".equals(rol))) throw ApiException.bad("No puede quitarse a sí mismo el rol de administrador ni desactivarse");
        Integer co = d.containsKey("corredoraId") ? corredoraDe(c, d, rol) : (u.get("corredora_id") == null ? null : Db.i(u.get("corredora_id")));
        if (co == null && ("CORREDOR".equals(rol) || "SUPERVISOR".equals(rol))) throw ApiException.invalid("Los roles corredor y supervisor requieren una corredora").with("campo", "corredoraId");
        Db.exec(c, "UPDATE usuario SET nombre=?, rol=?, activo=?, corredora_id=? WHERE usuario_id=?", nombre, rol, activo, co, id);
        if (!activo) Auth.revokeUser(id);
        // HU-8.3: los cambios de rol se aplican la próxima vez que el usuario inicie sesión (la sesión vigente conserva su rol)
        Audit.log(c, p, "USUARIO_MODIFICAR", "usuario", id, "Usuario " + u.get("correo") + ": rol " + u.get("rol") + " → " + rol + ", activo " + u.get("activo") + " → " + activo
                + (Objects.equals(u.get("corredora_id"), co) ? "" : ", corredora → " + co));
    }

    /** HU-8.4: "eliminar" es una baja lógica: sus calificaciones y su historial permanecen intactos. */
    public static void eliminarUsuario(Connection c, Principal p, int id) throws Exception {
        Map<String, Object> u = Db.one(c, "SELECT correo FROM usuario WHERE usuario_id = ?", id);
        if (u == null) throw ApiException.notFound("Usuario no encontrado");
        if (id == p.userId()) throw ApiException.bad("No puede eliminar su propio usuario");
        Db.exec(c, "UPDATE usuario SET activo = FALSE WHERE usuario_id = ?", id);
        Db.exec(c, "UPDATE api_token SET activo = FALSE WHERE usuario_id = ?", id);
        Auth.revokeUser(id);
        long n = Db.count(c, "SELECT count(*) FROM calificacion WHERE usuario_id = ?", id);
        Audit.log(c, p, "USUARIO_ELIMINAR", "usuario", id, "Usuario " + u.get("correo") + " dado de baja. Sus " + n + " calificación(es) permanecen sin cambios.");
    }

    public static void reset2fa(Connection c, Principal p, int id) throws Exception {
        if (Db.exec(c, "UPDATE usuario SET totp_activo = FALSE, totp_secret = NULL WHERE usuario_id = ?", id) == 0) throw ApiException.notFound("Usuario no encontrado");
        Auth.revokeUser(id);
        Audit.log(c, p, "USUARIO_RESET_2FA", "usuario", id, "Se reinició la verificación en dos pasos (deberá configurarla al ingresar)");
    }

    public static void cambiarPassword(Connection c, Principal p, int id, String pw) throws Exception {
        validarPassword(pw);
        if (Db.exec(c, "UPDATE usuario SET password_hash = ?, intentos_fallidos = 0, bloqueado_hasta = NULL WHERE usuario_id = ?", Crypto.hashPassword(pw), id) == 0) throw ApiException.notFound("Usuario no encontrado");
        Auth.revokeUser(id);
        Audit.log(c, p, "USUARIO_PASSWORD", "usuario", id, "Contraseña restablecida por un administrador");
    }

    // ============================================================ tokens de API (HU-19)
    public static List<Map<String, Object>> tokens(Connection c, Principal p) throws Exception {
        return Db.q(c, "SELECT token_id AS id, nombre, activo, creado_en, ultimo_uso FROM api_token WHERE usuario_id = ? ORDER BY token_id DESC", p.userId());
    }
    public static Object crearToken(Connection c, Principal p, String nombre) throws Exception {
        if (!"WEB".equals(p.origen())) throw ApiException.forbidden("Los tokens solo se pueden crear desde la interfaz web");
        if (nombre == null) throw ApiException.invalid("Indique un nombre para el token").with("campo", "nombre");
        String t = "nuam_" + Crypto.token(32);
        int id = Db.insert(c, "INSERT INTO api_token (usuario_id, nombre, token_hash) VALUES (?,?,?) RETURNING token_id", p.userId(), nombre, Crypto.sha256(t));
        Audit.log(c, p, "TOKEN_CREAR", "api_token", id, "Token de API '" + nombre + "' creado");
        return m("id", id, "token", t, "aviso", "Copie el token ahora: no se volverá a mostrar.");
    }
    public static void revocarToken(Connection c, Principal p, int id) throws Exception {
        if (Db.exec(c, "UPDATE api_token SET activo = FALSE WHERE token_id = ? AND usuario_id = ?", id, p.userId()) == 0) throw ApiException.notFound("Token no encontrado");
        Audit.log(c, p, "TOKEN_REVOCAR", "api_token", id, "Token de API revocado");
    }

    // ============================================================ auditoría (HU-5)
    public static Object auditoria(Connection c, Map<String, String> f, int pagina, int tam) throws Exception {
        StringBuilder w = new StringBuilder("WHERE TRUE"); List<Object> prm = new ArrayList<>();
        if (f.get("usuario") != null) { w.append(" AND (lower(u.correo) LIKE ? OR lower(u.nombre) LIKE ?)"); String l = "%" + f.get("usuario").toLowerCase().replace("%", "") + "%"; prm.add(l); prm.add(l); }
        if (f.get("accion") != null) { w.append(" AND a.accion = ?"); prm.add(f.get("accion")); }
        if (f.get("entidad") != null) { w.append(" AND a.entidad_afectada = ?"); prm.add(f.get("entidad")); }
        if (f.get("origen") != null) { w.append(" AND a.origen = ?"); prm.add(f.get("origen")); }
        if (f.get("registro") != null) { try { prm.add(Integer.parseInt(f.get("registro"))); w.append(" AND a.registro_id = ?"); } catch (NumberFormatException e) { throw ApiException.bad("Registro inválido"); } }
        try {
            if (f.get("desde") != null) { prm.add(java.sql.Date.valueOf(f.get("desde"))); w.append(" AND a.fecha >= ?"); }
            if (f.get("hasta") != null) { prm.add(java.sql.Date.valueOf(java.time.LocalDate.parse(f.get("hasta")).plusDays(1))); w.append(" AND a.fecha < ?"); }
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) { throw ApiException.bad("Fecha inválida (use AAAA-MM-DD)"); }
        long total = Db.count(c, "SELECT count(*) FROM auditoria a LEFT JOIN usuario u USING (usuario_id) " + w, prm.toArray());
        List<Object> p2 = new ArrayList<>(prm); p2.add(tam); p2.add((pagina - 1) * tam);
        List<Map<String, Object>> rows = Db.q(c, "SELECT a.auditoria_id AS id, a.fecha, u.nombre AS usuario, u.correo, a.accion, a.entidad_afectada AS entidad, a.registro_id AS registro, a.detalle, a.origen " +
                "FROM auditoria a LEFT JOIN usuario u USING (usuario_id) " + w + " ORDER BY a.auditoria_id DESC LIMIT ? OFFSET ?", p2.toArray());
        List<Object> acciones = new ArrayList<>(); for (Map<String, Object> r : Db.q(c, "SELECT DISTINCT accion FROM auditoria ORDER BY 1")) acciones.add(r.get("accion"));
        return m("total", total, "pagina", pagina, "tam", tam, "filas", rows, "acciones", acciones);
    }
}
