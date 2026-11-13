package cl.nuam;

import java.sql.*;
import java.util.*;

/** Acceso a PostgreSQL por JDBC con transacciones simples. */
public final class Db {
    private Db() {}
    public interface Tx<T> { T run(Connection c) throws Exception; }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(Config.DB_URL, Config.DB_USER, Config.DB_PASS);
    }

    public static <T> T tx(Tx<T> f) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try { T r = f.run(c); c.commit(); return r; }
            catch (Throwable e) {
                try { c.rollback(); } catch (SQLException ignore) { }
                if (e instanceof RuntimeException re) throw re;
                if (e instanceof Error er) throw er;
                throw new RuntimeException(e);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static PreparedStatement prep(Connection c, String sql, Object[] p) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
        return ps;
    }

    public static List<Map<String, Object>> q(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, p); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData(); int n = md.getColumnCount();
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                out.add(row);
            }
            return out;
        }
    }

    public static Map<String, Object> one(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> r = q(c, sql, p); return r.isEmpty() ? null : r.get(0);
    }

    public static int exec(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, p)) { return ps.executeUpdate(); }
    }

    /** INSERT ... RETURNING id */
    public static int insert(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, p); ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
    }

    public static long count(Connection c, String sql, Object... p) throws SQLException {
        Map<String, Object> r = one(c, sql, p); return ((Number) r.values().iterator().next()).longValue();
    }

    public static int i(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
}
