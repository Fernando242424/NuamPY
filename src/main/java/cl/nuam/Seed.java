package cl.nuam;

import cl.nuam.Auth.Principal;
import java.math.BigDecimal;
import java.util.*;

/** Datos de demostración (solo si la tabla de usuarios está vacía). Usuarios: Fernando (admin), Abel (corredor) y Joako (supervisor). Contraseña inicial de todos: Nuam2026! */
public final class Seed {
    private Seed() {}
    static final String PW = "Nuam2026!";

    public static void run() throws Exception {
        if (Db.tx(c -> Db.count(c, "SELECT count(*) FROM usuario")) > 0) return;
        Db.tx(c -> {
            int andes = Db.insert(c, "INSERT INTO corredora (nombre) VALUES ('Corredora Andes S.A.') RETURNING corredora_id");
            int pac = Db.insert(c, "INSERT INTO corredora (nombre) VALUES ('Corredora Pacífico S.A.') RETURNING corredora_id");
            String h = Crypto.hashPassword(PW);
            Object[][] us = {
                    {null, "Fernando", "admin@nuam.cl", "ADMIN", true},
                    {andes, "Abel", "abel@abel.cl", "CORREDOR", false},
                    {andes, "Joako", "joako@joako.cl", "SUPERVISOR", false}};
            for (Object[] u : us) {
                String sec = (Boolean) u[4] ? Crypto.newTotpSecret() : null;
                Db.exec(c, "INSERT INTO usuario (corredora_id, nombre, correo, rol, password_hash, totp_secret, totp_activo) VALUES (?,?,?,?,?,?,?)", u[0], u[1], u[2], u[3], h, sec, sec != null);
            }
            Map<String, Principal> P = new HashMap<>();
            P.put("admin", pr(c, "admin@nuam.cl")); P.put("c1", pr(c, "abel@abel.cl"));
            // datos globales (bolsa) – visibles para todos los corredores
            cal(c, P.get("admin"), "CL", "2026", "ENELAM", 1, "2026-04-10", "Dividendo definitivo (bolsa)", 120, 80, 60, 40, 30, 20, 20, 10, 10, 5, 3, 2);
            cal(c, P.get("admin"), "PE", "2026", "BCP", 1, "2026-03-28", "Dividendo en efectivo (bolsa Lima)", 200, 100, 50, 25, 10, 5, 5, 3, 1, 1, 1, 1);
            cal(c, P.get("admin"), "CO", "2026", "ECOPETROL", 1, "2026-06-12", "Dividendo ordinario (bolsa Colombia)", 90, 60, 30, 20, 10, 10, 5, 5, 5, 5, 5, 5);
            // Corredora Andes
            cal(c, P.get("c1"), "CL", "2026", "COPEC", 1, "2026-04-20", "Dividendo definitivo", 50, 30, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5);
            cal(c, P.get("c1"), "CL", "2026", "ENELAM", 1, "2026-04-10", "Ajuste local de la corredora (prioridad sobre bolsa)", 100, 100, 60, 40, 30, 20, 20, 10, 10, 5, 3, 2);
            sinFactores(c, P.get("c1"), "CL", "2026", "FALABELLA", 1, "2026-05-02", "Pendiente de montos");
            sinFactores(c, P.get("c1"), "CL", "2025", "CMPC", 2, "2025-05-20", "Pendiente (periodo vencido)");
            cal(c, P.get("c1"), "CL", "2025", "BCI", 1, "2025-04-18", "Dividendo definitivo", 60, 40, 20, 20, 10, 10, 10, 5, 5, 5, 5, 5);
            Db.exec(c, "UPDATE periodo SET fecha_limite_dj1948 = current_date + 7 WHERE corredora_id = ? AND periodo_comercial = '2026'", andes);   // demo: vence en 7 días (HU-7)
            Db.exec(c, "UPDATE periodo SET fecha_limite_dj1948 = current_date - 5 WHERE corredora_id = ? AND periodo_comercial = '2025'", andes);   // demo: vencido (HU-7.5)
            return null;
        });
    }

    static Principal pr(java.sql.Connection c, String correo) throws Exception {
        Map<String, Object> u = Db.one(c, "SELECT u.*, co.nombre AS corredora FROM usuario u LEFT JOIN corredora co USING (corredora_id) WHERE correo = ?", correo);
        return new Principal(Db.i(u.get("usuario_id")), (String) u.get("nombre"), correo, (String) u.get("rol"), u.get("corredora_id") == null ? null : Db.i(u.get("corredora_id")), (String) u.get("corredora"), "SISTEMA", null);
    }

    static void cal(java.sql.Connection c, Principal p, String mercado, String periodo, String inst, int sec, String fecha, String desc, int... montos) throws Exception {
        Map<Integer, BigDecimal> mo = new TreeMap<>();
        for (int i = 0; i < montos.length; i++) mo.put(8 + i, BigDecimal.valueOf(montos[i] * 1000L));
        Map<Integer, BigDecimal> val = Formulas.calcular(c, mercado, mo);
        Map<String, Object> fs = new LinkedHashMap<>();
        for (int n : mo.keySet()) fs.put(String.valueOf(n), CalificacionService.m("monto", mo.get(n), "valor", val.get(n)));
        Map<String, Object> d = CalificacionService.m("mercado", mercado, "periodo", periodo, "instrumento", inst, "secuencia", sec, "fechaPago", fecha, "descripcion", desc, "factores", fs, "origen", "bolsa");
        CalificacionService.crear(c, p, d, true, "SISTEMA", null);
    }

    static void sinFactores(java.sql.Connection c, Principal p, String mercado, String periodo, String inst, int sec, String fecha, String desc) throws Exception {
        CalificacionService.crear(c, p, CalificacionService.m("mercado", mercado, "periodo", periodo, "instrumento", inst, "secuencia", sec, "fechaPago", fecha, "descripcion", desc), true, "SISTEMA", null);
    }
}
