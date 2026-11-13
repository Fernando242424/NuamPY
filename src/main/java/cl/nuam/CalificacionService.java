package cl.nuam;

import cl.nuam.Auth.Principal;
import java.math.BigDecimal;
import java.sql.Connection;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Núcleo del mantenedor: HU-1, 2, 3, 4, 10, 13, 14, 16 y reglas de prioridad/aislamiento por corredora. */
public final class CalificacionService {
    private CalificacionService() {}
    public static final Set<String> MERCADOS = Set.of("CL", "PE", "CO");
    public static final String PENDIENTES_SQL = "('INCOMPLETA','PENDIENTE_REVISION','RECHAZADA','ERROR')";

    public static String pais(String m) { return switch (m) { case "CL" -> "Chile"; case "PE" -> "Perú"; case "CO" -> "Colombia"; default -> m; }; }

    /** Minúsculas y sin acentos (HU-14.5). */
    public static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim().replaceAll("\\s+", " ");
    }

    static Map<String, Object> m(Object... kv) { Map<String, Object> o = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); return o; }

    // ============================================================ visibilidad
    /** Corredor/supervisor: sus datos + datos globales (bolsa/sistema) salvo que exista una versión local (prioridad local). Admin: solo globales. */
    static String scope(Principal p, List<Object> params) {
        if (p.is("ADMIN")) return "c.corredora_id IS NULL";
        if (!p.esDeCorredora() || p.corredoraId() == null) throw ApiException.forbidden("Su rol no tiene acceso a las calificaciones");
        params.add(p.corredoraId()); params.add(p.corredoraId());
        return "(c.corredora_id = ? OR (c.corredora_id IS NULL AND NOT EXISTS (SELECT 1 FROM calificacion l WHERE l.corredora_id = ? AND NOT l.eliminada " +
                "AND l.instrumento_id = c.instrumento_id AND l.secuencia = c.secuencia AND l.periodo_comercial = c.periodo_comercial AND l.tipo_dj = c.tipo_dj)))";
    }

    static boolean puedeEditar(Principal p, Map<String, Object> row) {
        Object co = row.get("corredora_id");
        if (p.is("CORREDOR")) return co != null && Db.i(co) == p.corredoraId();
        if (p.is("ADMIN")) return co == null;
        return false;
    }

    private static final String SELECT_BASE =
            "SELECT c.calificacion_id AS id, i.nombre AS instrumento, i.mercado, c.secuencia, c.periodo_comercial AS periodo, c.ejercicio, c.fecha_pago, " +
            "c.descripcion, c.origen, c.estado, c.acogido_isfut, c.tipo_dj, c.corredora_id, c.motivo_error, c.eliminada, c.instrumento_id, " +
            "(SELECT COALESCE(SUM(f.valor),0) FROM factor f WHERE f.calificacion_id = c.calificacion_id AND f.numero_factor BETWEEN 8 AND 19) AS suma_8_19, " +
            "EXISTS (SELECT 1 FROM conciliacion k WHERE k.calificacion_id = c.calificacion_id AND k.estado = 'DISCREPANCIA') AS discrepancia " +
            "FROM calificacion c JOIN instrumento i USING (instrumento_id) ";

    // ============================================================ HU-1 / HU-14 / HU-16: listado con filtros
    public record Page(List<Map<String, Object>> filas, long total) {}

    public static Page listar(Connection c, Principal p, Map<String, String> f, int pagina, int tam) throws Exception {
        List<Object> prm = new ArrayList<>();
        StringBuilder w = new StringBuilder("NOT c.eliminada AND ").append(scope(p, prm));
        String mercado = f.get("mercado"), origen = f.get("origen"), periodo = f.get("periodo"), q = f.get("q");
        if (mercado != null) { if (!MERCADOS.contains(mercado)) throw ApiException.bad("Mercado inválido"); w.append(" AND i.mercado = ?"); prm.add(mercado); }
        if (origen != null) { w.append(" AND c.origen = ?"); prm.add(origen); }
        if (periodo != null) { w.append(" AND c.periodo_comercial = ?"); prm.add(periodo); }
        String tdj = f.get("tipo_dj"); if (tdj != null) { if (!tdj.equals("1948") && !tdj.equals("1922")) throw ApiException.bad("Tipo de DJ inválido"); w.append(" AND c.tipo_dj = ?"); prm.add(tdj); }
        if (q != null) {   // búsqueda segura: parámetro enlazado + escape de comodines (HU-14 / sección Seguridad)
            w.append(" AND i.nombre_norm LIKE ? ESCAPE '\\'");
            prm.add("%" + norm(q).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if ("1".equals(f.get("pendientes"))) w.append(" AND c.estado IN ").append(PENDIENTES_SQL);
        long total = Db.count(c, "SELECT count(*) FROM calificacion c JOIN instrumento i USING (instrumento_id) WHERE " + w, prm.toArray());
        List<Object> p2 = new ArrayList<>(prm); p2.add(tam); p2.add((pagina - 1) * tam);
        List<Map<String, Object>> rows = Db.q(c, SELECT_BASE + "WHERE " + w + " ORDER BY i.nombre, c.secuencia, c.calificacion_id LIMIT ? OFFSET ?", p2.toArray());
        for (Map<String, Object> r : rows) decorar(p, r);
        return new Page(rows, total);
    }

    private static void decorar(Principal p, Map<String, Object> r) {
        r.put("pais", pais((String) r.get("mercado")));
        r.put("editable", puedeEditar(p, r));
        r.remove("instrumento_id");
    }

    static Map<String, Object> cargar(Connection c, Principal p, int id, boolean incluirEliminadas) throws Exception {
        List<Object> prm = new ArrayList<>();
        String sc = scope(p, prm); prm.add(0, id);
        // el parámetro del id va primero en el SQL, luego los del scope
        Map<String, Object> r = Db.one(c, SELECT_BASE + "WHERE c.calificacion_id = ? AND " + sc + (incluirEliminadas ? "" : " AND NOT c.eliminada"), prm.toArray());
        if (r == null) throw ApiException.notFound("Calificación no encontrada");
        return r;
    }

    public static Map<String, Object> detalle(Connection c, Principal p, int id) throws Exception {
        Map<String, Object> r = cargar(c, p, id, false);
        decorar(p, r);
        List<Map<String, Object>> fs = Db.q(c, "SELECT numero_factor AS n, monto, valor FROM factor WHERE calificacion_id = ? ORDER BY numero_factor", id);
        r.put("factores", fs);
        r.put("conciliacion", Db.one(c, "SELECT conciliacion_id AS id, estado, diferencias, fuente_usada, motivo, fecha, montos_certificado, montos_dj FROM conciliacion WHERE calificacion_id = ? ORDER BY conciliacion_id DESC LIMIT 1", id));
        if (r.get("corredora_id") != null) {
            Map<String, Object> per = Db.one(c, "SELECT estado FROM periodo WHERE corredora_id = ? AND periodo_comercial = ?", r.get("corredora_id"), r.get("periodo"));
            r.put("periodoEstado", per == null ? "ABIERTO" : per.get("estado"));
        }
        return r;
    }

    // ============================================================ datos de entrada
    static final class Datos {
        String mercado, periodo, instrumento, descripcion, comentario, origen, tipoDj = "1948"; int secuencia; LocalDate fechaPago; boolean isfut;
        Map<Integer, BigDecimal> montos = new TreeMap<>(), valores = new TreeMap<>();
    }

    private static ApiException campo(String campo, String etiqueta) {
        return ApiException.invalid("El campo obligatorio '" + etiqueta + "' está vacío o es inválido").with("campo", campo);
    }

    static Datos leer(Map<String, Object> d, boolean exigirBasicos) {
        Datos x = new Datos();
        x.mercado = Json.str(d, "mercado");
        if (x.mercado == null || !MERCADOS.contains(x.mercado)) throw ApiException.invalid("Debe indicar el país/mercado de origen (Chile, Perú o Colombia)").with("campo", "mercado");
        x.periodo = Json.str(d, "periodo");
        if (x.periodo == null || !x.periodo.matches("\\d{4}")) throw campo("periodo", "Periodo comercial (año)");
        x.instrumento = Json.str(d, "instrumento");
        if (x.instrumento == null) throw campo("instrumento", "Instrumento");
        if (x.instrumento.length() > 160) throw ApiException.invalid("El instrumento no puede superar 160 caracteres").with("campo", "instrumento");
        BigDecimal sq = Json.dec(d.get("secuencia"));
        if (sq == null || sq.stripTrailingZeros().scale() > 0 || sq.signum() <= 0 || sq.compareTo(BigDecimal.valueOf(9_999_999)) > 0) throw campo("secuencia", "Secuencia");
        x.secuencia = sq.intValue();
        try { x.fechaPago = LocalDate.parse(Json.str(d, "fechaPago")); } catch (Exception e) { throw campo("fechaPago", "Fecha de pago"); }
        x.descripcion = Json.str(d, "descripcion");
        if (x.descripcion == null) throw campo("descripcion", "Descripción");
        if (x.descripcion.length() > 400) throw ApiException.invalid("La descripción no puede superar 400 caracteres").with("campo", "descripcion");
        x.isfut = Json.bool(d.get("acogidoIsfut"));
        String tdj = Json.str(d, "tipoDj"); if (tdj != null) x.tipoDj = tdj;
        if (!x.tipoDj.equals("1948") && !x.tipoDj.equals("1922")) throw ApiException.invalid("El tipo de declaración jurada debe ser 1948 o 1922").with("campo", "tipoDj");
        x.comentario = Json.str(d, "comentario");
        x.origen = Json.str(d, "origen");
        Object fo = d.get("factores");
        if (fo instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                int n; try { n = Integer.parseInt(e.getKey().toString()); } catch (NumberFormatException ex) { throw ApiException.bad("Número de factor inválido: " + e.getKey()); }
                if (n < Formulas.F_MIN || n > Formulas.F_MAX) throw ApiException.bad("Los factores van del 8 al 36");
                if (!(e.getValue() instanceof Map<?, ?> vm)) continue;
                Object mo = vm.get("monto"), va = vm.get("valor");
                BigDecimal bm = Json.dec(mo), bv = Json.dec(va);
                if (mo != null && !mo.toString().isBlank() && bm == null) throw ApiException.invalid("El monto del factor " + n + " no es numérico").with("campo", "monto_" + n);
                if (va != null && !va.toString().isBlank() && bv == null) throw ApiException.invalid("El factor " + n + " no es numérico").with("campo", "valor_" + n);
                if (bm != null && (bm.signum() < 0 || bm.precision() - bm.scale() > 16)) throw ApiException.invalid("El monto del factor " + n + " es inválido (debe ser ≥ 0)").with("campo", "monto_" + n);
                if (bv != null && (bv.signum() < 0 || bv.precision() - bv.scale() > 5)) throw ApiException.invalid("El factor " + n + " es inválido (debe ser ≥ 0)").with("campo", "valor_" + n);
                if (bm != null) x.montos.put(n, bm);
                if (bv != null) x.valores.put(n, bv.setScale(Math.min(10, Math.max(0, bv.scale())), java.math.RoundingMode.DOWN));
            }
        }
        Formulas.validarSuma(x.valores);     // HU-4: se aplica igual en manual, API, carga masiva
        return x;
    }

    // ============================================================ utilidades de escritura
    static int instrumentoId(Connection c, String nombre, String mercado) throws Exception {
        return Db.insert(c, "INSERT INTO instrumento (nombre, nombre_norm, mercado) VALUES (?,?,?) ON CONFLICT (nombre_norm, mercado) DO UPDATE SET nombre = instrumento.nombre RETURNING instrumento_id",
                nombre, norm(nombre), mercado);
    }

    static void asegurarPeriodo(Connection c, int corredoraId, String periodo) throws Exception {
        Db.exec(c, "INSERT INTO periodo (corredora_id, periodo_comercial, fecha_limite_dj1948) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                corredoraId, periodo, LocalDate.of(Integer.parseInt(periodo) + 1, 3, 31));
    }

    /** HU-18.4: un periodo cerrado no admite cambios. */
    static void verificarPeriodoAbierto(Connection c, Integer corredoraId, String periodo) throws Exception {
        if (corredoraId == null) return;
        Map<String, Object> per = Db.one(c, "SELECT estado FROM periodo WHERE corredora_id = ? AND periodo_comercial = ?", corredoraId, periodo);
        if (per != null && "CERRADO".equals(per.get("estado")))
            throw new ApiException(423, "El periodo comercial " + periodo + " está cerrado: no se pueden modificar sus calificaciones. Solo un administrador puede reabrirlo.");
    }

    private static void guardarFactores(Connection c, int id, Datos x) throws Exception {
        Db.exec(c, "DELETE FROM factor WHERE calificacion_id = ?", id);
        Set<Integer> ns = new TreeSet<>(x.montos.keySet()); ns.addAll(x.valores.keySet());
        for (int n : ns) Db.exec(c, "INSERT INTO factor (calificacion_id, numero_factor, monto, valor) VALUES (?,?,?,?)", id, n, x.montos.get(n), x.valores.get(n));
    }

    static String estadoCalculado(Connection c, int id, Datos x) throws Exception {
        if (Db.count(c, "SELECT count(*) FROM conciliacion WHERE calificacion_id = ? AND estado = 'DISCREPANCIA'", id) > 0) return "PENDIENTE_REVISION";   // HU-17.5
        boolean completo = true;
        for (int n = Formulas.SUM_MIN; n <= Formulas.SUM_MAX; n++) if (x.valores.get(n) == null) { completo = false; break; }
        return completo ? "COMPLETA" : "INCOMPLETA";
    }

    private static void duplicado(Connection c, Integer corredoraId, int instId, int sec, String periodo, Integer excluir, String nombre, String tipoDj) throws Exception {
        long n = Db.count(c, "SELECT count(*) FROM calificacion WHERE NOT eliminada AND instrumento_id = ? AND secuencia = ? AND periodo_comercial = ? AND tipo_dj = ? " +
                "AND corredora_id IS NOT DISTINCT FROM ? AND calificacion_id <> ?", instId, sec, periodo, tipoDj, corredoraId, excluir == null ? -1 : excluir);
        if (n > 0) throw new ApiException(409, "Posible duplicado: ya existe una calificación del instrumento '" + nombre + "' con secuencia " + sec + " en el periodo " + periodo + " (DJ " + tipoDj + ").").with("duplicado", true);
    }

    static Map<String, Object> snapshot(Connection c, int id) throws Exception {
        Map<String, Object> r = Db.one(c, "SELECT c.calificacion_id AS id, i.nombre AS instrumento, i.mercado, c.secuencia, c.periodo_comercial AS periodo, c.fecha_pago, c.descripcion, " +
                "c.origen, c.estado, c.acogido_isfut, c.tipo_dj, c.corredora_id, c.eliminada FROM calificacion c JOIN instrumento i USING (instrumento_id) WHERE c.calificacion_id = ?", id);
        r.put("factores", Db.q(c, "SELECT numero_factor AS n, monto, valor FROM factor WHERE calificacion_id = ? ORDER BY numero_factor", id));
        return r;
    }

    static void nuevaVersion(Connection c, Principal p, int id, String accion, Map<String, Object> extra) throws Exception {
        Map<String, Object> snap = snapshot(c, id);
        if (extra != null) snap.putAll(extra);
        int v = Db.i(Db.one(c, "SELECT COALESCE(MAX(numero_version),0) + 1 AS v FROM calificacion_version WHERE calificacion_id = ?", id).get("v"));
        Db.exec(c, "INSERT INTO calificacion_version (calificacion_id, numero_version, accion, usuario_id, snapshot) VALUES (?,?,?,?,?)", id, v, accion, p.userId(), Json.write(snap));
    }

    private static void comentar(Connection c, Principal p, int id, String texto) throws Exception {
        if (texto == null) return;   // HU-10.3: el comentario es opcional
        int cid = Db.insert(c, "INSERT INTO comentario (calificacion_id, usuario_id, texto) VALUES (?,?,?) RETURNING comentario_id", id, p.userId(), texto);
        Audit.log(c, p, "COMENTAR", "calificacion", id, "Comentario #" + cid + ": " + (texto.length() > 200 ? texto.substring(0, 200) + "…" : texto));
    }

    // ============================================================ HU-2: ingreso
    public static int crear(Connection c, Principal p, Map<String, Object> d, boolean confirmarDup, String evento, Integer cargaId) throws Exception {
        if (!p.is("CORREDOR", "ADMIN")) throw ApiException.forbidden("Su rol no puede ingresar calificaciones");
        Datos x = leer(d, true);
        Integer corredora = p.is("CORREDOR") ? p.corredoraId() : null;
        String origen = p.is("CORREDOR") ? "corredor" : ("sistema".equals(x.origen) ? "sistema" : "bolsa");
        verificarPeriodoAbierto(c, corredora, x.periodo);
        int inst = instrumentoId(c, x.instrumento, x.mercado);
        if (!confirmarDup) duplicado(c, corredora, inst, x.secuencia, x.periodo, null, x.instrumento, x.tipoDj);
        if (corredora != null) asegurarPeriodo(c, corredora, x.periodo);
        String estado = x.valores.isEmpty() && !x.montos.isEmpty() ? "INCOMPLETA" : null;
        int id = Db.insert(c, "INSERT INTO calificacion (instrumento_id, corredora_id, usuario_id, secuencia, ejercicio, periodo_comercial, fecha_pago, descripcion, origen, acogido_isfut, estado, tipo_dj) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING calificacion_id", inst, corredora, p.userId(), x.secuencia, Integer.parseInt(x.periodo), x.periodo, x.fechaPago, x.descripcion, origen, x.isfut, "INCOMPLETA", x.tipoDj);
        guardarFactores(c, id, x);
        Db.exec(c, "UPDATE calificacion SET estado = ? WHERE calificacion_id = ?", estado != null ? estado : estadoCalculado(c, id, x), id);
        nuevaVersion(c, p, id, "CREAR", null);
        comentar(c, p, id, x.comentario);
        Audit.log(c, p.userId(), "CREAR", "calificacion", id, "Calificación creada: " + x.instrumento + " (" + pais(x.mercado) + ") sec " + x.secuencia + ", periodo " + x.periodo
                + ", origen " + origen + (cargaId != null ? ", carga masiva #" + cargaId : ""), evento == null ? p.origen() : evento);
        return id;
    }

    /** HU-15.4: registro importado con formato inesperado: se guarda marcado para revisión manual. */
    public static int crearConError(Connection c, Principal p, String instrumento, String mercado, String periodo, String motivo, String evento) throws Exception {
        if (!p.is("CORREDOR")) throw ApiException.forbidden("Solo un corredor puede importar declaraciones");
        int inst = instrumentoId(c, instrumento == null ? "(sin identificar)" : instrumento, mercado);
        asegurarPeriodo(c, p.corredoraId(), periodo);
        int id = Db.insert(c, "INSERT INTO calificacion (instrumento_id, corredora_id, usuario_id, secuencia, ejercicio, periodo_comercial, descripcion, origen, estado, motivo_error) " +
                "VALUES (?,?,?,0,?,?,?, 'corredor','ERROR',?) RETURNING calificacion_id", inst, p.corredoraId(), p.userId(), Integer.parseInt(periodo), periodo, "Importado: requiere revisión manual", motivo);
        nuevaVersion(c, p, id, "CREAR", null);
        Audit.log(c, p.userId(), "CREAR", "calificacion", id, "Registro importado marcado para revisión manual: " + motivo, evento);
        return id;
    }

    // ============================================================ edición / baja
    public static void actualizar(Connection c, Principal p, int id, Map<String, Object> d, boolean confirmarDup, String evento) throws Exception {
        Map<String, Object> r = cargar(c, p, id, false);
        if (!puedeEditar(p, r)) throw ApiException.forbidden("No puede modificar esta calificación (pertenece a otro origen). Use 'Sobrescribir localmente' para crear su versión.");
        verificarPeriodoAbierto(c, (Integer) r.get("corredora_id"), (String) r.get("periodo"));
        Datos x = leer(d, true);
        Integer corredora = r.get("corredora_id") == null ? null : Db.i(r.get("corredora_id"));
        verificarPeriodoAbierto(c, corredora, x.periodo);
        int inst = instrumentoId(c, x.instrumento, x.mercado);
        if (!confirmarDup && (inst != Db.i(r.get("instrumento_id")) || x.secuencia != Db.i(r.get("secuencia")) || !x.periodo.equals(r.get("periodo"))))
            duplicado(c, corredora, inst, x.secuencia, x.periodo, id, x.instrumento, x.tipoDj);
        if (corredora != null) asegurarPeriodo(c, corredora, x.periodo);
        Map<String, Object> antes = snapshot(c, id);
        Db.exec(c, "UPDATE calificacion SET instrumento_id=?, secuencia=?, ejercicio=?, periodo_comercial=?, fecha_pago=?, descripcion=?, acogido_isfut=?, tipo_dj=?, motivo_error=NULL, fecha_modificacion=now(), usuario_id=? WHERE calificacion_id=?",
                inst, x.secuencia, Integer.parseInt(x.periodo), x.periodo, x.fechaPago, x.descripcion, x.isfut, x.tipoDj, p.userId(), id);
        guardarFactores(c, id, x);
        Db.exec(c, "UPDATE calificacion SET estado = ? WHERE calificacion_id = ?", estadoCalculado(c, id, x), id);   // una edición requiere nueva aprobación
        nuevaVersion(c, p, id, "MODIFICAR", null);
        comentar(c, p, id, x.comentario);
        Audit.log(c, p.userId(), "MODIFICAR", "calificacion", id, "Calificación modificada. " + diff(antes, snapshot(c, id)), evento == null ? p.origen() : evento);
    }

    @SuppressWarnings("unchecked")
    private static String diff(Map<String, Object> a, Map<String, Object> b) {
        List<String> out = new ArrayList<>();
        for (String k : List.of("instrumento", "mercado", "secuencia", "periodo", "fecha_pago", "descripcion", "acogido_isfut", "tipo_dj", "estado"))
            if (!Objects.equals(String.valueOf(a.get(k)), String.valueOf(b.get(k)))) out.add(k + ": " + a.get(k) + " → " + b.get(k));
        Map<Integer, String> fa = new TreeMap<>(), fb = new TreeMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) a.get("factores")) fa.put(Db.i(f.get("n")), f.get("monto") + "/" + f.get("valor"));
        for (Map<String, Object> f : (List<Map<String, Object>>) b.get("factores")) fb.put(Db.i(f.get("n")), f.get("monto") + "/" + f.get("valor"));
        Set<Integer> ks = new TreeSet<>(fa.keySet()); ks.addAll(fb.keySet());
        for (int n : ks) if (!Objects.equals(fa.get(n), fb.get(n))) out.add("factor " + n + " (monto/valor): " + fa.get(n) + " → " + fb.get(n));
        return out.isEmpty() ? "Sin cambios de datos" : String.join("; ", out);
    }

    public static void eliminar(Connection c, Principal p, int id, String evento) throws Exception {
        Map<String, Object> r = cargar(c, p, id, false);
        if (!puedeEditar(p, r)) throw ApiException.forbidden("No puede eliminar esta calificación");
        verificarPeriodoAbierto(c, (Integer) r.get("corredora_id"), (String) r.get("periodo"));
        List<Map<String, Object>> cms = Db.q(c, "SELECT u.nombre AS usuario, k.texto, k.fecha FROM comentario k LEFT JOIN usuario u USING (usuario_id) WHERE k.calificacion_id = ? ORDER BY k.fecha", id);
        Db.exec(c, "UPDATE calificacion SET eliminada = TRUE, fecha_modificacion = now() WHERE calificacion_id = ?", id);   // baja lógica: versiones y comentarios se conservan
        nuevaVersion(c, p, id, "ELIMINAR", m("comentariosConservados", cms));
        Audit.log(c, p.userId(), "ELIMINAR", "calificacion", id, "Calificación eliminada: " + r.get("instrumento") + " sec " + r.get("secuencia") + " periodo " + r.get("periodo")
                + ". Se conservan " + cms.size() + " comentario(s) y el historial de versiones.", evento == null ? p.origen() : evento);
    }

    /** Prioridad local: el corredor crea su propia versión de un dato de bolsa/sistema, sin afectar a otros corredores. */
    public static int sobrescribir(Connection c, Principal p, int id) throws Exception {
        if (!p.is("CORREDOR")) throw ApiException.forbidden("Solo un corredor puede crear una versión local");
        Map<String, Object> r = cargar(c, p, id, false);
        if (r.get("corredora_id") != null) throw ApiException.bad("La calificación ya es local de su corredora");
        verificarPeriodoAbierto(c, p.corredoraId(), (String) r.get("periodo"));
        asegurarPeriodo(c, p.corredoraId(), (String) r.get("periodo"));
        int nid = Db.insert(c, "INSERT INTO calificacion (instrumento_id, corredora_id, usuario_id, secuencia, ejercicio, periodo_comercial, fecha_pago, descripcion, origen, acogido_isfut, estado, tipo_dj) " +
                "SELECT instrumento_id, ?, ?, secuencia, ejercicio, periodo_comercial, fecha_pago, descripcion, 'corredor', acogido_isfut, estado, tipo_dj FROM calificacion WHERE calificacion_id = ? RETURNING calificacion_id", p.corredoraId(), p.userId(), id);
        Db.exec(c, "INSERT INTO factor (calificacion_id, numero_factor, monto, valor) SELECT ?, numero_factor, monto, valor FROM factor WHERE calificacion_id = ?", nid, id);
        nuevaVersion(c, p, nid, "CREAR", m("copiadaDe", id));
        Audit.log(c, p, "SOBRESCRIBIR_LOCAL", "calificacion", nid, "Versión local (prioridad sobre " + r.get("origen") + ") creada a partir de la calificación #" + id);
        return nid;
    }

    // ============================================================ HU-13 historial de versiones
    public static List<Map<String, Object>> versiones(Connection c, Principal p, int id) throws Exception {
        cargar(c, p, id, true);   // valida visibilidad (incluye eliminadas: el historial se conserva)
        return Db.q(c, "SELECT v.numero_version AS version, v.accion, u.nombre AS usuario, v.fecha FROM calificacion_version v LEFT JOIN usuario u USING (usuario_id) " +
                "WHERE v.calificacion_id = ? ORDER BY v.numero_version DESC", id);
    }

    public static Object versionDetalle(Connection c, Principal p, int id, int ver) throws Exception {
        cargar(c, p, id, true);
        Map<String, Object> r = Db.one(c, "SELECT v.numero_version AS version, v.accion, u.nombre AS usuario, v.fecha, v.snapshot FROM calificacion_version v LEFT JOIN usuario u USING (usuario_id) WHERE v.calificacion_id = ? AND v.numero_version = ?", id, ver);
        if (r == null) throw ApiException.notFound("Versión no encontrada");
        r.put("datos", Json.parse((String) r.remove("snapshot")));    // solo lectura: no sobrescribe la versión actual (HU-13.3)
        return r;
    }

    // ============================================================ HU-10 comentarios
    public static List<Map<String, Object>> comentarios(Connection c, Principal p, int id) throws Exception {
        cargar(c, p, id, true);
        return Db.q(c, "SELECT k.comentario_id AS id, u.nombre AS usuario, k.texto, k.fecha FROM comentario k LEFT JOIN usuario u USING (usuario_id) WHERE k.calificacion_id = ? ORDER BY k.fecha, k.comentario_id", id);
    }

    public static void agregarComentario(Connection c, Principal p, int id, String texto) throws Exception {
        Map<String, Object> r = cargar(c, p, id, false);
        if (!p.is("CORREDOR", "SUPERVISOR", "ADMIN")) throw ApiException.forbidden("No puede comentar");
        if (texto == null || texto.isBlank()) throw ApiException.invalid("El comentario no puede estar vacío").with("campo", "comentario");
        if (texto.length() > 2000) throw ApiException.invalid("El comentario no puede superar 2000 caracteres");
        comentar(c, p, id, texto.trim());
    }

    // ============================================================ HU-3 cálculo
    public static Map<String, Object> calcular(Connection c, Map<String, Object> d) throws Exception {
        String mercado = Json.str(d, "mercado");
        if (mercado == null || !MERCADOS.contains(mercado)) throw ApiException.invalid("Debe indicar el país/mercado").with("campo", "mercado");
        Map<Integer, BigDecimal> montos = new TreeMap<>();
        Object mo = d.get("montos");
        if (mo instanceof Map<?, ?> mm) for (Map.Entry<?, ?> e : mm.entrySet()) {
            int n; try { n = Integer.parseInt(e.getKey().toString()); } catch (NumberFormatException ex) { throw ApiException.bad("Factor inválido"); }
            if (n < Formulas.F_MIN || n > Formulas.F_MAX) throw ApiException.bad("Los factores van del 8 al 36");
            BigDecimal v = Json.dec(e.getValue());
            if (e.getValue() != null && !e.getValue().toString().isBlank() && v == null) throw ApiException.invalid("El monto del factor " + n + " no es numérico").with("campo", "monto_" + n);
            if (v != null) montos.put(n, v);
        }
        Map<Integer, BigDecimal> val = Formulas.calcular(c, mercado, montos);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> fs = new LinkedHashMap<>();
        val.forEach((k, v) -> fs.put(String.valueOf(k), v));
        out.put("factores", fs); out.put("suma", Formulas.suma(val));
        try { Formulas.validarSuma(val); out.put("sumaValida", true); } catch (ApiException e) { out.put("sumaValida", false); out.put("aviso", e.getMessage()); }
        return out;
    }
}
