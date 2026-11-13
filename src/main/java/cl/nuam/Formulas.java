package cl.nuam;

import java.math.*;
import java.sql.Connection;
import java.util.*;

/** HU-3 / HU-4 / HU-16.3: montos -> factores según la fórmula del país, y validación de suma 8..19. */
public final class Formulas {
    private Formulas() {}
    public static final int F_MIN = 8, F_MAX = 36, SUM_MIN = 8, SUM_MAX = 19;
    public static final BigDecimal UNO = BigDecimal.ONE;

    /** montos: número de factor -> monto (null = no informado). Devuelve número de factor -> valor. */
    public static Map<Integer, BigDecimal> calcular(Connection c, String mercado, Map<Integer, BigDecimal> montos) throws Exception {
        Map<String, Object> f = Db.one(c, "SELECT * FROM mercado_formula WHERE mercado = ?", mercado);
        if (f == null) throw ApiException.invalid("No hay fórmula de cálculo definida para el mercado " + mercado);
        int desde = Db.i(f.get("base_desde")), hasta = Db.i(f.get("base_hasta")), dec = Db.i(f.get("decimales"));

        List<Integer> faltan = new ArrayList<>();
        for (int n = SUM_MIN; n <= SUM_MAX; n++) if (montos.get(n) == null) faltan.add(n);
        if (!faltan.isEmpty())
            throw ApiException.invalid("Faltan montos obligatorios para calcular (factor " + faltan.get(0) + (faltan.size() > 1 ? " y " + (faltan.size() - 1) + " más" : "") + ")").with("campo", "monto_" + faltan.get(0));
        BigDecimal base = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> e : montos.entrySet()) {
            if (e.getValue() != null && e.getValue().signum() < 0)
                throw ApiException.invalid("El monto del factor " + e.getKey() + " no puede ser negativo").with("campo", "monto_" + e.getKey());
            if (e.getKey() >= desde && e.getKey() <= hasta && e.getValue() != null) base = base.add(e.getValue());
        }
        if (base.signum() == 0) throw ApiException.invalid("La suma base de montos (factores " + desde + " a " + hasta + ") es cero; no se puede calcular").with("campo", "monto_" + desde);
        Map<Integer, BigDecimal> out = new TreeMap<>();
        for (int n = F_MIN; n <= F_MAX; n++) {
            BigDecimal m = montos.get(n);
            if (m != null) out.put(n, m.divide(base, dec, RoundingMode.DOWN));   // truncado: nunca excede 1
        }
        return out;
    }

    public static BigDecimal suma(Map<Integer, BigDecimal> valores) {
        BigDecimal s = BigDecimal.ZERO;
        for (int n = SUM_MIN; n <= SUM_MAX; n++) { BigDecimal v = valores.get(n); if (v != null) s = s.add(v); }
        return s;
    }

    /** HU-4.1 / 4.4: bloquea si la suma excede 1 e informa suma actual y exceso. */
    public static void validarSuma(Map<Integer, BigDecimal> valores) {
        BigDecimal s = suma(valores);
        if (s.compareTo(UNO) > 0) {
            BigDecimal exceso = s.subtract(UNO);
            throw ApiException.invalid("La suma de los factores 8 al 19 es " + s.stripTrailingZeros().toPlainString()
                    + " y excede el límite permitido (1) en " + exceso.stripTrailingZeros().toPlainString())
                    .with("suma", s).with("exceso", exceso).with("campo", "suma");
        }
    }
}
