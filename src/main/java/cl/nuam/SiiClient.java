package cl.nuam;

import cl.nuam.Auth.Principal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** HU-15: importación de declaraciones juradas (DJ 1948/1922) mediante servicio web. */
public final class SiiClient {
    private SiiClient() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> importar(Principal p, String periodo, boolean simularCaida) throws Exception {
        if (!p.is("CORREDOR")) throw ApiException.forbidden("Solo un corredor puede importar declaraciones juradas");
        if (periodo == null || !periodo.matches("\\d{4}")) throw ApiException.invalid("Indique el periodo comercial (año)").with("campo", "periodo");
        String url = Config.SII_URL + (Config.SII_URL.contains("?") ? "&" : "?") + "anio=" + periodo + (simularCaida ? "&fail=1" : "");
        String body;
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            body = r.body();
        } catch (Exception e) {   // HU-15.2: se informa el error y se ofrece la carga manual como alternativa
            Audit.logNew(p.userId(), "SII_ERROR", "carga_masiva", null, "No se pudo contactar al servicio del SII: " + e.getMessage(), "SII");
            throw new ApiException(502, "El servicio del SII no está disponible en este momento. Puede continuar con la carga manual mediante archivo CSV/Excel.").with("alternativa", "carga-manual");
        }
        Object parsed = Json.parse(body);
        List<Object> decl = parsed instanceof Map<?, ?> mm && mm.get("declaraciones") instanceof List<?> l ? (List<Object>) l : null;
        if (decl == null) throw new ApiException(502, "Respuesta inesperada del servicio del SII. Use la carga manual.").with("alternativa", "carga-manual");

        List<Map<String, Object>> filas = new ArrayList<>(); int i = 1;
        for (Object o : decl) {
            i++;
            Map<String, Object> row = o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("mercado", row.getOrDefault("mercado", "CL")); d.put("periodo", periodo); d.put("instrumento", row.get("instrumento"));
            d.put("secuencia", row.get("secuencia")); d.put("fechaPago", BulkLoader.fecha(row.get("fechaPago") == null ? null : row.get("fechaPago").toString()));
            String tdj = row.get("tipoDj") == null ? "1948" : row.get("tipoDj").toString();
            d.put("tipoDj", tdj);
            d.put("descripcion", row.getOrDefault("descripcion", "Importado desde el SII (DJ " + tdj + ")"));
            Map<String, Object> fs = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
            if (row.get("montos") instanceof Map<?, ?> mm) for (Map.Entry<?, ?> e : mm.entrySet()) { Map<String, Object> x = new LinkedHashMap<>(); x.put("monto", e.getValue()); x.put("valor", null); fs.put(e.getKey().toString(), x); }
            d.put("factores", fs); d.put("__fila", i);
            filas.add(d);
        }
        Map<String, Object> res = BulkLoader.procesar(p, "SII", "Importación SII DJ 1948 periodo " + periodo, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), periodo, filas, true, "SII");
        return res;
    }

    /** Servicio simulado del SII (para desarrollo y demostración). Configure NUAM_SII_URL para apuntar al servicio real. */
    public static Object mock(Http.Ctx c) {
        if ("1".equals(c.q("fail"))) throw new ApiException(503, "Servicio no disponible");
        String anio = c.q("anio") == null ? "2026" : c.q("anio");
        List<Object> l = new ArrayList<>();
        l.add(dj("CL", "COPEC", 1, anio + "-04-20", "Dividendo definitivo", new int[]{50, 30, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5}));
        l.add(dj("CL", "FALABELLA", 2, anio + "-05-02", "Dividendo provisorio", new int[]{70, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5, 5}));
        l.add(dj("CL", "CENCOSUD", 1, anio + "-05-15", "Dividendo definitivo", new int[]{40, 40, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5}));
        l.add(dj("CL", "ENTEL", 1, anio + "-06-10", "Dividendo definitivo", new int[]{60, 25, 15, 10, 10, 5, 5, 5, 5, 5, 5, 5}));
        l.add(tipo(dj("CL", "BCI", 1, anio + "-06-20", "Declaración DJ 1922", new int[]{30, 30, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5}), "1922"));
        l.add(tipo(dj("CL", "CMPC", 2, anio + "-07-05", "Declaración DJ 1922", new int[]{45, 25, 20, 10, 10, 5, 5, 5, 5, 5, 5, 5}), "1922"));
        Map<String, Object> mal = dj("CL", "SQM-B", 3, "31-31-" + anio, "Registro con fecha mal formada", new int[]{10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10});   // formato inesperado
        l.add(mal);
        return CalificacionService.m("fuente", "SII (simulado)", "anio", anio, "declaraciones", l);
    }
    private static Map<String, Object> tipo(Map<String, Object> m, String t) { m.put("tipoDj", t); return m; }

    private static Map<String, Object> dj(String mercado, String inst, int sec, String fecha, String desc, int[] montos) {
        Map<String, Object> m = new LinkedHashMap<>(); Map<String, Object> mo = new LinkedHashMap<>();
        for (int i = 0; i < montos.length; i++) mo.put(String.valueOf(8 + i), montos[i] * 1000);
        m.put("mercado", mercado); m.put("instrumento", inst); m.put("secuencia", sec); m.put("fechaPago", fecha); m.put("descripcion", desc); m.put("montos", mo);
        return m;
    }
}
