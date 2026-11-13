package cl.nuam;

import cl.nuam.Auth.Principal;
import java.io.File;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** HU-11 / RNF-06: respaldos diarios cifrados (pg_dump + AES-256-GCM), retención de 30 días y restauración por fecha. */
public final class Backup {
    private Backup() {}
    private static final Object LOCK = new Object();

    private static ProcessBuilder pb(List<String> cmd) {
        ProcessBuilder b = new ProcessBuilder(cmd).redirectErrorStream(true);
        b.environment().put("PGPASSWORD", Config.DB_PASS);
        return b;
    }
    private static String exec(List<String> cmd) throws Exception {
        Process pr = pb(cmd).start();
        String out = new String(pr.getInputStream().readAllBytes());
        if (!pr.waitFor(10, TimeUnit.MINUTES)) { pr.destroyForcibly(); throw new IllegalStateException("tiempo agotado"); }
        if (pr.exitValue() != 0) throw new IllegalStateException(out.isBlank() ? "código " + pr.exitValue() : out.trim());
        return out;
    }

    /** Ejecuta un respaldo completo. tipo: AUTO (programado) o MANUAL. */
    public static Map<String, Object> ejecutar(String tipo, Integer usuarioId) {
        synchronized (LOCK) {
            String nombre = "nuam_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".dump.enc";
            int id = Db.tx(c -> Db.insert(c, "INSERT INTO respaldo (archivo, estado, tipo) VALUES (?, 'EN_CURSO', ?) RETURNING respaldo_id", nombre, tipo));
            Path dir = Paths.get(Config.BACKUP_DIR), plano = dir.resolve(nombre + ".tmp"), cif = dir.resolve(nombre);
            try {
                Files.createDirectories(dir);
                exec(List.of("pg_dump", "-Fc", "-h", Config.pgHost(), "-p", String.valueOf(Config.pgPort()), "-U", Config.DB_USER, "-f", plano.toString(), Config.pgDb()));
                Crypto.encryptFile(plano, cif);
                long bytes = Files.size(cif);
                Files.deleteIfExists(plano);
                Db.tx(c -> { Db.exec(c, "UPDATE respaldo SET estado='OK', finalizado=now(), bytes=? WHERE respaldo_id=?", bytes, id);   // HU-11.2
                    Audit.log(c, usuarioId, "RESPALDO_OK", "respaldo", id, "Respaldo " + nombre + " (" + bytes + " bytes)", "SISTEMA"); return null; });
                purgar();
                return Map.of("id", id, "estado", "OK", "archivo", nombre, "bytes", bytes);
            } catch (Exception e) {
                try { Files.deleteIfExists(plano); Files.deleteIfExists(cif); } catch (Exception ignore) { }
                String msg = String.valueOf(e.getMessage());
                try {
                    Db.tx(c -> {
                        Db.exec(c, "UPDATE respaldo SET estado='ERROR', finalizado=now(), mensaje=? WHERE respaldo_id=?", msg, id);
                        Audit.log(c, usuarioId, "RESPALDO_ERROR", "respaldo", id, "Falló el respaldo: " + msg, "SISTEMA");
                        for (Map<String, Object> a : Db.q(c, "SELECT usuario_id FROM usuario WHERE rol = 'ADMIN' AND activo"))   // HU-11.3
                            Db.exec(c, "INSERT INTO notificacion (usuario_id, tipo, mensaje, url) VALUES (?, 'RESPALDO_ERROR', ?, '#respaldos') ON CONFLICT (usuario_id, tipo, periodo_comercial, dia) DO NOTHING", a.get("usuario_id"), "El respaldo automático falló: " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
                        return null; });
                } catch (Exception e2) { System.err.println("[BACKUP] " + e2); }
                return Map.of("id", id, "estado", "ERROR", "mensaje", msg);
            }
        }
    }

    /** HU-11.5: conserva al menos los últimos 30 días. */
    static void purgar() throws Exception {
        List<Map<String, Object>> viejos = Db.tx(c -> Db.q(c, "SELECT respaldo_id, archivo FROM respaldo WHERE estado = 'OK' AND iniciado < now() - (? || ' days')::interval", String.valueOf(Config.BACKUP_RETENTION_DAYS)));
        for (Map<String, Object> v : viejos) {
            Files.deleteIfExists(Paths.get(Config.BACKUP_DIR, (String) v.get("archivo")));
            Db.tx(c -> { Db.exec(c, "UPDATE respaldo SET estado = 'PURGADO' WHERE respaldo_id = ?", v.get("respaldo_id")); return null; });
        }
    }

    public static List<Map<String, Object>> listar() {
        return Db.tx(c -> Db.q(c, "SELECT respaldo_id AS id, archivo, estado, tipo, iniciado, finalizado, bytes, mensaje FROM respaldo ORDER BY respaldo_id DESC LIMIT 100"));
    }

    /** HU-11.4: el administrador elige el respaldo de una fecha específica. */
    public static Map<String, Object> restaurar(Principal p, int id) throws Exception {
        synchronized (LOCK) {
            Map<String, Object> r = Db.tx(c -> Db.one(c, "SELECT * FROM respaldo WHERE respaldo_id = ?", id));
            if (r == null) throw ApiException.notFound("Respaldo no encontrado");
            if (!"OK".equals(r.get("estado"))) throw ApiException.bad("Solo se puede restaurar un respaldo en estado OK");
            Path cif = Paths.get(Config.BACKUP_DIR, (String) r.get("archivo")), plano = Paths.get(Config.BACKUP_DIR, r.get("archivo") + ".restore.tmp");
            if (!Files.exists(cif)) throw ApiException.bad("El archivo del respaldo ya no existe en disco");
            try {
                Crypto.decryptFile(cif, plano);
                exec(List.of("pg_restore", "--clean", "--if-exists", "--no-owner", "-h", Config.pgHost(), "-p", String.valueOf(Config.pgPort()), "-U", Config.DB_USER, "-d", Config.pgDb(), plano.toString()));
            } catch (ApiException e) { throw e; }
            catch (Exception e) { throw new ApiException(500, "No se pudo restaurar el respaldo: " + e.getMessage()); }
            finally { Files.deleteIfExists(plano); }
            Audit.logNew(p.userId(), "RESPALDO_RESTAURADO", "respaldo", id, "Base de datos restaurada desde " + r.get("archivo"), "WEB");
            return Map.of("ok", true, "archivo", r.get("archivo"));
        }
    }

    /** Programa el respaldo diario al finalizar el día (23:55 hora local). */
    public static void programar() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "backup-scheduler"); t.setDaemon(true); return t; });
        LocalDateTime next = LocalDate.now().atTime(23, 55);
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1);
        long delay = Duration.between(LocalDateTime.now(), next).getSeconds();
        ex.scheduleAtFixedRate(() -> { try { ejecutar("AUTO", null); } catch (Throwable t) { System.err.println("[BACKUP] " + t); } }, delay, 24 * 3600, TimeUnit.SECONDS);
        System.out.println("Respaldo automático programado: " + next);
    }
}
