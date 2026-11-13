package cl.nuam;

import cl.nuam.Auth.Principal;
import cl.nuam.CalificacionService.Page;
import cl.nuam.Http.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** nuam · Mantenedor de Calificaciones Tributarias — punto de entrada. */
public final class Main {
    static Map<String, Object> m(Object... kv) { return CalificacionService.m(kv); }
    static final String CAL = "/api/v1/calificaciones", V = "/api/v1";
    static final String[] OPERA = {"CORREDOR", "SUPERVISOR", "ADMIN"};

    public static void main(String[] args) throws Exception {
        initDb();
        if (Config.SEED) Seed.run();
        Router r = new Router();
        rutas(r);
        HttpServer s = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
        s.createContext("/", r);
        s.setExecutor(Executors.newFixedThreadPool(24));
        s.start();
        Auth.startJanitor();
        Backup.programar();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(t -> { Thread th = new Thread(t, "notifier"); th.setDaemon(true); return th; });
        ex.scheduleAtFixedRate(() -> { try { Db.tx(c -> { PeriodoService.generarVencimientos(c); return null; }); } catch (Throwable t) { System.err.println("[notifier] " + t); } }, 5, 3600, TimeUnit.SECONDS);
        System.out.println("nuam listo en http://localhost:" + Config.PORT + "  (BD: " + Config.DB_URL + ", demo=" + Config.DEMO + ")");
    }

    static void initDb() throws Exception {
        String sql = Files.readString(Paths.get(System.getenv().getOrDefault("NUAM_SCHEMA", "sql/schema.sql")), StandardCharsets.UTF_8);
        try (java.sql.Connection c = Db.open(); java.sql.Statement st = c.createStatement()) { st.execute(sql); }
    }

    // ---------------------------------------------------------------- utilidades de rutas
    static Map<String, String> filtros(Ctx c) {
        Map<String, String> f = new LinkedHashMap<>();
        for (String k : new String[]{"mercado", "origen", "periodo", "tipo_dj", "q", "pendientes"}) if (c.q(k) != null) f.put(k, c.q(k));
        if (f.get("mercado") == null && c.q("pais") != null) f.put("mercado", c.q("pais"));
        return f;
    }
    static int entero(String s, int def, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(s))); } catch (Exception e) { return def; } }

    static String descFiltros(Map<String, String> f) {
        if (f.isEmpty()) return "(sin filtros: todas las calificaciones)";
        List<String> l = new ArrayList<>();
        f.forEach((k, v) -> l.add((k.equals("q") ? "texto" : k) + "=" + (k.equals("mercado") ? CalificacionService.pais(v) : v)));
        return String.join("; ", l);
    }

    static Object json(Ctx c, Tx0 t) throws Exception { return t.run(c); }
    interface Tx0 { Object run(Ctx c) throws Exception; }

    static void rutas(Router r) {
        // ---------- acceso y sesión
        r.pub("POST", "/auth/login", Auth::login);
        r.pub("POST", "/auth/2fa", Auth::verify2fa);
        r.pub("GET", "/auth/2fa/setup", Auth::setupInfo);
        r.pub("POST", "/auth/2fa/setup", Auth::setupConfirm);
        r.add("GET", "/auth/me", c -> Auth.me(c.user));
        r.add("POST", "/auth/logout", Auth::logout);
        r.add("POST", "/auth/ping", c -> m("ok", true));
        r.add("GET", "/auth/idle", Auth::idle);
        r.pub("GET", "/health", c -> m("estado", "ok"));
        r.pub("GET", "/mock/sii/dj", SiiClient::mock);

        // ---------- HU-1/2/14/16 calificaciones
        r.add("GET", CAL, c -> Db.tx(cn -> {
            int pagina = entero(c.q("pagina"), 1, 1, 100000), tam = entero(c.q("tam"), 50, 1, 500);
            Page pg = CalificacionService.listar(cn, c.user, filtros(c), pagina, tam);
            return m("total", pg.total(), "pagina", pagina, "tam", tam, "filas", pg.filas());
        }), OPERA);
        r.add("POST", CAL, c -> Db.tx(cn -> {
            Map<String, Object> d = c.json();
            int id = CalificacionService.crear(cn, c.user, d, Json.bool(d.get("confirmarDuplicado")), null, null);
            return m("id", id);
        }), "CORREDOR", "ADMIN");
        r.add("GET", CAL + "/(\\d+)", c -> Db.tx(cn -> CalificacionService.detalle(cn, c.user, c.pathInt(1))), OPERA);
        r.add("PUT", CAL + "/(\\d+)", c -> Db.tx(cn -> { Map<String, Object> d = c.json();
            CalificacionService.actualizar(cn, c.user, c.pathInt(1), d, Json.bool(d.get("confirmarDuplicado")), null); return m("ok", true); }), "CORREDOR", "ADMIN");
        r.add("DELETE", CAL + "/(\\d+)", c -> Db.tx(cn -> { CalificacionService.eliminar(cn, c.user, c.pathInt(1), null); return m("ok", true); }), "CORREDOR", "ADMIN");
        r.add("POST", CAL + "/(\\d+)/sobrescribir", c -> Db.tx(cn -> m("id", CalificacionService.sobrescribir(cn, c.user, c.pathInt(1)))), "CORREDOR");
        r.add("POST", V + "/calcular", c -> Db.tx(cn -> CalificacionService.calcular(cn, c.json())), "CORREDOR", "ADMIN");

        // ---------- HU-13 versiones, HU-10 comentarios
        r.add("GET", CAL + "/(\\d+)/versiones", c -> Db.tx(cn -> CalificacionService.versiones(cn, c.user, c.pathInt(1))), OPERA);
        r.add("GET", CAL + "/(\\d+)/versiones/(\\d+)", c -> Db.tx(cn -> CalificacionService.versionDetalle(cn, c.user, c.pathInt(1), c.pathInt(2))), OPERA);
        r.add("GET", CAL + "/(\\d+)/comentarios", c -> Db.tx(cn -> CalificacionService.comentarios(cn, c.user, c.pathInt(1))), OPERA);
        r.add("POST", CAL + "/(\\d+)/comentarios", c -> Db.tx(cn -> { CalificacionService.agregarComentario(cn, c.user, c.pathInt(1), Json.str(c.json(), "texto")); return m("ok", true); }), OPERA);

        // ---------- HU-17 conciliación
        r.add("POST", CAL + "/(\\d+)/conciliar", c -> Db.tx(cn -> Conciliacion.conciliar(cn, c.user, c.pathInt(1), c.json())), "CORREDOR", "ADMIN");
        r.add("POST", CAL + "/(\\d+)/conciliar/resolver", c -> Db.tx(cn -> { Map<String, Object> d = c.json();
            Conciliacion.resolver(cn, c.user, c.pathInt(1), Json.str(d, "fuente"), Json.str(d, "motivo")); return m("ok", true); }), "CORREDOR", "ADMIN");

        // ---------- HU-18 aprobaciones y periodos
        r.add("GET", V + "/aprobaciones", c -> Db.tx(cn -> PeriodoService.pendientesAprobacion(cn, c.user)), "SUPERVISOR");
        r.add("POST", CAL + "/(\\d+)/aprobar", c -> Db.tx(cn -> PeriodoService.aprobar(cn, c.user, c.pathInt(1))), "SUPERVISOR");
        r.add("POST", CAL + "/(\\d+)/rechazar", c -> Db.tx(cn -> { PeriodoService.rechazar(cn, c.user, c.pathInt(1), Json.str(c.json(), "motivo")); return m("ok", true); }), "SUPERVISOR");
        r.add("GET", V + "/periodos", c -> Db.tx(cn -> PeriodoService.listar(cn, c.user)), OPERA);
        r.add("PUT", V + "/periodos/(\\d+)/limite", c -> Db.tx(cn -> { PeriodoService.definirLimite(cn, c.user, c.pathInt(1), Json.str(c.json(), "fecha")); return m("ok", true); }), "ADMIN");
        r.add("POST", V + "/periodos/(\\d+)/solicitar-cierre", c -> Db.tx(cn -> { PeriodoService.solicitarCierre(cn, c.user, c.pathInt(1)); return m("ok", true); }), "CORREDOR", "SUPERVISOR");
        r.add("POST", V + "/periodos/(\\d+)/reabrir", c -> Db.tx(cn -> { PeriodoService.reabrir(cn, c.user, c.pathInt(1), Json.str(c.json(), "motivo")); return m("ok", true); }), "ADMIN");

        // ---------- HU-7 notificaciones, HU-9 dashboard
        r.add("GET", V + "/notificaciones", c -> Db.tx(cn -> PeriodoService.notificaciones(cn, c.user)));
        r.add("POST", V + "/notificaciones/(\\d+)/leer", c -> Db.tx(cn -> PeriodoService.leer(cn, c.user, c.pathInt(1))));
        r.add("GET", V + "/alertas", c -> Db.tx(cn -> PeriodoService.alertas(cn, c.user)), OPERA);
        r.add("GET", V + "/dashboard", c -> Db.tx(cn -> PeriodoService.dashboard(cn, c.user, c.q("periodo"))), OPERA);

        // ---------- carga masiva (RF-05) e integración SII (HU-15)
        r.add("POST", V + "/carga-masiva", c -> {
            Map<String, Object> d = c.json();
            String nombre = Json.str(d, "nombre"), b64 = Json.str(d, "contenidoBase64");
            if (nombre == null || b64 == null) throw ApiException.bad("Adjunte un archivo (.csv o .xlsx)");
            byte[] bytes; try { bytes = Base64.getDecoder().decode(b64); } catch (IllegalArgumentException e) { throw ApiException.bad("Archivo corrupto"); }
            return BulkLoader.procesarArchivo(c.user, nombre, bytes, Json.str(d, "periodo"), Json.str(d, "mercado"));
        }, "CORREDOR", "ADMIN");
        r.add("GET", V + "/carga-masiva", c -> Db.tx(cn -> Db.q(cn, "SELECT cm.carga_masiva_id AS id, cm.tipo, cm.nombre_archivo, cm.estado, cm.periodo_comercial AS periodo, cm.total_filas AS total, cm.filas_ok AS ok, cm.filas_error AS errores, cm.fecha, u.nombre AS usuario " +
                "FROM carga_masiva cm JOIN usuario u USING (usuario_id) WHERE u.corredora_id IS NOT DISTINCT FROM ? ORDER BY cm.carga_masiva_id DESC LIMIT 50", c.user.corredoraId())), "CORREDOR", "SUPERVISOR", "ADMIN");
        r.add("GET", V + "/carga-masiva/(\\d+)", c -> Db.tx(cn -> {
            Map<String, Object> cm = Db.one(cn, "SELECT cm.carga_masiva_id AS id, cm.tipo, cm.nombre_archivo, cm.estado, cm.total_filas AS total, cm.filas_ok AS ok, cm.filas_error AS errores, cm.fecha, u.nombre AS usuario " +
                    "FROM carga_masiva cm JOIN usuario u USING (usuario_id) WHERE cm.carga_masiva_id = ? AND u.corredora_id IS NOT DISTINCT FROM ?", c.pathInt(1), c.user.corredoraId());
            if (cm == null) throw ApiException.notFound("Carga no encontrada");
            cm.put("detalle", Db.q(cn, "SELECT fila, secuencia, estado, motivo_error AS motivo FROM carga_masiva_detalle WHERE carga_masiva_id = ? ORDER BY fila", c.pathInt(1)));
            return cm;
        }), "CORREDOR", "SUPERVISOR", "ADMIN");
        r.add("GET", V + "/plantilla.csv", c -> new Raw(200, "text/csv; charset=utf-8", BulkLoader.PLANTILLA.getBytes(StandardCharsets.UTF_8), Map.of("Content-Disposition", "attachment; filename=\"plantilla_carga_masiva.csv\"")), "CORREDOR", "ADMIN");
        r.add("POST", V + "/sii/importar", c -> { Map<String, Object> d = c.json(); return SiiClient.importar(c.user, Json.str(d, "periodo"), Json.bool(d.get("simularCaida"))); }, "CORREDOR");

        // ---------- HU-6 exportación
        r.add("GET", V + "/export", c -> Db.tx(cn -> {
            String fmt = c.q("format") == null ? "xlsx" : c.q("format").toLowerCase();
            if (!fmt.equals("xlsx") && !fmt.equals("pdf")) throw ApiException.bad("Formato inválido (xlsx o pdf)");
            Map<String, String> f = filtros(c);
            Page pg = CalificacionService.listar(cn, c.user, f, entero(c.q("pagina"), 1, 1, 100000), entero(c.q("tam"), 50, 1, 500));
            if (pg.filas().isEmpty()) throw new ApiException(404, "No hay datos disponibles para exportar con los filtros actuales");   // HU-6.4
            byte[] data = fmt.equals("xlsx") ? Export.xlsx(pg.filas()) : Export.pdf(pg.filas(), c.user.nombre(), descFiltros(f));
            Audit.log(cn, c.user, "EXPORTAR", "calificacion", null, "Exportación " + fmt.toUpperCase() + " de " + pg.filas().size() + " registro(s). Filtros: " + descFiltros(f));   // HU-6.5
            String fn = "calificaciones_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "." + fmt;
            return new Raw(200, fmt.equals("xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/pdf", data,
                    Map.of("Content-Disposition", "attachment; filename=\"" + fn + "\"", "X-Registros", String.valueOf(pg.filas().size())));
        }), OPERA);

        // ---------- HU-8 usuarios, HU-19 tokens
        r.add("GET", V + "/usuarios", c -> Db.tx(Admin::usuarios), "ADMIN");
        r.add("GET", V + "/corredoras", c -> Db.tx(Admin::corredoras), "ADMIN");
        r.add("POST", V + "/usuarios", c -> Db.tx(cn -> Admin.crearUsuario(cn, c.user, c.json())), "ADMIN");
        r.add("PUT", V + "/usuarios/(\\d+)", c -> Db.tx(cn -> { Admin.actualizarUsuario(cn, c.user, c.pathInt(1), c.json()); return m("ok", true); }), "ADMIN");
        r.add("DELETE", V + "/usuarios/(\\d+)", c -> Db.tx(cn -> { Admin.eliminarUsuario(cn, c.user, c.pathInt(1)); return m("ok", true); }), "ADMIN");
        r.add("POST", V + "/usuarios/(\\d+)/reset2fa", c -> Db.tx(cn -> { Admin.reset2fa(cn, c.user, c.pathInt(1)); return m("ok", true); }), "ADMIN");
        r.add("POST", V + "/usuarios/(\\d+)/password", c -> Db.tx(cn -> { Admin.cambiarPassword(cn, c.user, c.pathInt(1), Json.str(c.json(), "password")); return m("ok", true); }), "ADMIN");
        r.add("GET", V + "/tokens", c -> Db.tx(cn -> Admin.tokens(cn, c.user)), OPERA);
        r.add("POST", V + "/tokens", c -> Db.tx(cn -> Admin.crearToken(cn, c.user, Json.str(c.json(), "nombre"))), OPERA);
        r.add("DELETE", V + "/tokens/(\\d+)", c -> Db.tx(cn -> { Admin.revocarToken(cn, c.user, c.pathInt(1)); return m("ok", true); }), OPERA);

        // ---------- HU-5 auditoría (solo lectura)
        r.add("GET", V + "/auditoria", c -> Db.tx(cn -> {
            Map<String, String> f = new LinkedHashMap<>();
            for (String k : new String[]{"usuario", "accion", "entidad", "origen", "registro", "desde", "hasta"}) if (c.q(k) != null) f.put(k, c.q(k));
            return Admin.auditoria(cn, f, entero(c.q("pagina"), 1, 1, 100000), entero(c.q("tam"), 50, 1, 200));
        }), "ADMIN", "AUDITOR");
        Handler negar = c -> {   // HU-5.4: ningún usuario puede modificar ni eliminar el log; el intento queda registrado
            Audit.logNew(c.user.userId(), "INTENTO_MODIFICAR_LOG", "auditoria", null, c.method() + " " + c.path(), c.user.origen());
            throw ApiException.forbidden("El log de auditoría es inmutable: no puede modificarse ni eliminarse en ninguna circunstancia");
        };
        for (String mt : new String[]{"PUT", "PATCH", "DELETE", "POST"}) r.add(mt, V + "/auditoria(?:/\\d+)?", negar);

        // ---------- HU-11 respaldos
        r.add("GET", V + "/respaldos", c -> Backup.listar(), "ADMIN");
        r.add("POST", V + "/respaldos", c -> Backup.ejecutar("MANUAL", c.user.userId()), "ADMIN");
        r.add("POST", V + "/respaldos/(\\d+)/restaurar", c -> Backup.restaurar(c.user, c.pathInt(1)), "ADMIN");
    }
}
