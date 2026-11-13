package cl.nuam;

import java.util.*;
import java.util.concurrent.*;

/** Autenticación: contraseña + 2FA (HU-12), sesiones con expiración por inactividad (HU-20), tokens de API (HU-19). */
public final class Auth {
    private Auth() {}

    public record Principal(int userId, String nombre, String correo, String rol, Integer corredoraId, String corredora, String origen, String csrf) {
        public boolean is(String... roles) { for (String r : roles) if (r.equals(rol)) return true; return false; }
        /** Corredor/supervisor: ven solo su corredora. */
        public boolean esDeCorredora() { return is("CORREDOR", "SUPERVISOR"); }
    }

    static final class Session {
        final String id; final int userId; final String correo; volatile String stage; // 2FA | SETUP | FULL
        volatile long last = System.currentTimeMillis(); final long created = last;
        String pendingSecret; int fails; boolean newDevice; String devToken;
        Principal principal;
        Session(String id, int userId, String correo, String stage) { this.id = id; this.userId = userId; this.correo = correo; this.stage = stage; }
    }

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EXPIRED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Deque<Long>> RATE = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- autenticación por petición
    public static Principal authenticate(Http.Ctx c) throws Exception {
        String ah = c.header("Authorization");
        if (ah != null && ah.regionMatches(true, 0, "Bearer ", 0, 7)) return byToken(ah.substring(7).trim());

        String sid = c.cookie("NUAM_SID");
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null) {
            if (sid != null && EXPIRED.containsKey(sid))
                throw new ApiException(401, "Su sesión se cerró por inactividad. Inicie sesión nuevamente.").with("expirada", true);
            throw new ApiException(401, "Debe iniciar sesión");
        }
        if (!"FULL".equals(s.stage)) throw new ApiException(401, "Debe completar la verificación en dos pasos");
        long idle = System.currentTimeMillis() - s.last;
        if (idle > Config.IDLE_SECONDS * 1000L) { expire(s); throw new ApiException(401, "Su sesión se cerró por inactividad. Inicie sesión nuevamente.").with("expirada", true); }
        if (!c.method().equals("GET") && !c.method().equals("HEAD")) {
            String h = c.header("X-CSRF");
            if (h == null || !MessageDigestEq(h, s.principal.csrf())) throw ApiException.forbidden("Token CSRF inválido");
        }
        if (!c.path().equals("/auth/idle")) s.last = System.currentTimeMillis();   // actividad extiende la sesión (HU-20.2)
        return s.principal;
    }
    private static boolean MessageDigestEq(String a, String b) { return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes()); }

    private static Principal byToken(String token) throws Exception {
        String h = Crypto.sha256(token);
        Map<String, Object> r = Db.tx(cn -> {
            Map<String, Object> row = Db.one(cn, "SELECT t.token_id, u.usuario_id, u.nombre, u.correo, u.rol, u.corredora_id, u.activo, co.nombre AS corredora " +
                    "FROM api_token t JOIN usuario u USING (usuario_id) LEFT JOIN corredora co ON co.corredora_id = u.corredora_id WHERE t.token_hash = ? AND t.activo", h);
            if (row != null) Db.exec(cn, "UPDATE api_token SET ultimo_uso = now() WHERE token_id = ?", row.get("token_id"));
            return row;
        });
        if (r == null || !(Boolean) r.get("activo") || r.get("rol") == null)
            throw new ApiException(401, "Credenciales de API inválidas o revocadas");
        int tokenId = Db.i(r.get("token_id"));
        Deque<Long> dq = RATE.computeIfAbsent(tokenId, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > 60_000) dq.pollFirst();
            if (dq.size() >= Config.API_RATE_PER_MIN)
                throw new ApiException(429, "Límite de solicitudes excedido (" + Config.API_RATE_PER_MIN + " por minuto). Intente más tarde.");
            dq.addLast(now);
        }
        return new Principal(Db.i(r.get("usuario_id")), (String) r.get("nombre"), (String) r.get("correo"), (String) r.get("rol"),
                r.get("corredora_id") == null ? null : Db.i(r.get("corredora_id")), (String) r.get("corredora"), "API", null);
    }

    // ---------------------------------------------------------------- sesiones
    private static void expire(Session s) {
        if (SESSIONS.remove(s.id, s)) {
            EXPIRED.put(s.id, System.currentTimeMillis());
            Audit.logNew(s.userId, "SESION_EXPIRADA", "usuario", s.userId, "Cierre automático por inactividad (" + Config.IDLE_SECONDS + " s)", "SISTEMA");   // HU-20.5
        }
    }

    public static void startJanitor() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "session-janitor"); t.setDaemon(true); return t; });
        ex.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                for (Session s : SESSIONS.values()) {
                    if ("FULL".equals(s.stage)) { if (now - s.last > Config.IDLE_SECONDS * 1000L) expire(s); }
                    else if (now - s.created > 10 * 60_000L) SESSIONS.remove(s.id);
                }
                EXPIRED.values().removeIf(t -> now - t > 3_600_000L);
            } catch (Throwable t) { System.err.println("[janitor] " + t); }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public static void revokeUser(int userId) {
        for (Session s : SESSIONS.values()) if (s.userId == userId) SESSIONS.remove(s.id);
    }

    private static Session pre(Http.Ctx c, String... stages) {
        String sid = c.cookie("NUAM_SID");
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null || !Arrays.asList(stages).contains(s.stage)) throw new ApiException(401, "Inicie el proceso de acceso nuevamente");
        return s;
    }

    // ---------------------------------------------------------------- login paso 1
    public static Object login(Http.Ctx c) throws Exception {
        Map<String, Object> b = c.json();
        String correo = Json.str(b, "correo"), pw = Json.str(b, "password");
        if (correo == null || pw == null) throw ApiException.bad("Ingrese correo y contraseña");
        String origen = c.ex.getRemoteAddress().getAddress().getHostAddress();

        Map<String, Object> u = Db.tx(cn -> Db.one(cn, "SELECT * FROM usuario WHERE lower(correo) = lower(?)", correo));
        if (u == null) { Crypto.verifyPassword(pw, "pbkdf2$120000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); throw new ApiException(401, "Credenciales inválidas"); }
        int uid = Db.i(u.get("usuario_id"));

        java.sql.Timestamp bl = (java.sql.Timestamp) u.get("bloqueado_hasta");
        if (bl != null && bl.getTime() > System.currentTimeMillis()) {
            long min = Math.max(1, (bl.getTime() - System.currentTimeMillis() + 59_999) / 60_000);
            throw new ApiException(423, "Cuenta bloqueada temporalmente. Intente nuevamente en " + min + " minuto(s).");
        }
        if (!Crypto.verifyPassword(pw, (String) u.get("password_hash"))) {
            int fails = Db.tx(cn -> {
                int f = Db.i(u.get("intentos_fallidos")) + 1;
                if (f >= Config.MAX_FAILED) {
                    Db.exec(cn, "UPDATE usuario SET intentos_fallidos = 0, bloqueado_hasta = now() + (? || ' minutes')::interval WHERE usuario_id = ?", String.valueOf(Config.LOCK_MINUTES), uid);
                    Audit.log(cn, uid, "LOGIN_BLOQUEO", "usuario", uid, "Bloqueo por " + Config.MAX_FAILED + " contraseñas incorrectas desde " + origen, "WEB");
                } else Db.exec(cn, "UPDATE usuario SET intentos_fallidos = ? WHERE usuario_id = ?", f, uid);
                return f;
            });
            if (fails >= Config.MAX_FAILED) throw new ApiException(423, "Cuenta bloqueada temporalmente por intentos fallidos.");
            throw new ApiException(401, "Credenciales inválidas");
        }
        Db.tx(cn -> { Db.exec(cn, "UPDATE usuario SET intentos_fallidos = 0 WHERE usuario_id = ?", uid); return null; });
        if (!(Boolean) u.get("activo")) throw ApiException.forbidden("Usuario desactivado. Contacte al administrador.");
        if (u.get("rol") == null) {   // HU-8.5
            Audit.logNew(uid, "LOGIN_RECHAZADO", "usuario", uid, "Usuario sin rol asignado", "WEB");
            throw ApiException.forbidden("Su cuenta no tiene un rol asignado. Un administrador debe asignárselo antes de iniciar sesión.");
        }

        boolean totp = (Boolean) u.get("totp_activo");
        Session s = new Session(Crypto.token(32), uid, (String) u.get("correo"), totp ? "2FA" : "SETUP");
        String dev = c.cookie("NUAM_DEV");
        String huella = dev == null ? null : Crypto.sha256(dev);
        boolean known = huella != null && Db.tx(cn -> Db.count(cn, "SELECT count(*) FROM dispositivo WHERE usuario_id = ? AND huella = ?", uid, huella) > 0);
        s.newDevice = !known;     // HU-12.5: dispositivo no reconocido -> 2FA obligatorio (siempre se exige)
        s.devToken = dev;
        SESSIONS.put(s.id, s);
        c.setCookie("NUAM_SID", s.id, -1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siguiente", totp ? "2fa" : "setup2fa");
        out.put("dispositivoNuevo", s.newDevice);
        if (totp && Config.DEMO) out.put("demoCodigo", Crypto.currentTotp((String) u.get("totp_secret")));
        return out;
    }

    // ---------------------------------------------------------------- 2FA
    public static Object verify2fa(Http.Ctx c) throws Exception {
        Session s = pre(c, "2FA");
        String code = Json.str(c.json(), "codigo");
        Map<String, Object> u = Db.tx(cn -> Db.one(cn, "SELECT totp_secret FROM usuario WHERE usuario_id = ?", s.userId));
        if (Crypto.verifyTotp((String) u.get("totp_secret"), code)) return complete(c, s);
        return failCode(s);
    }

    public static Object setupInfo(Http.Ctx c) throws Exception {
        Session s = pre(c, "SETUP");
        if (s.pendingSecret == null) s.pendingSecret = Crypto.newTotpSecret();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("secreto", s.pendingSecret);
        o.put("uri", "otpauth://totp/nuam:" + s.correo + "?secret=" + s.pendingSecret + "&issuer=nuam&digits=6&period=30");
        o.put("pasos", List.of("Abra su app de autenticación (Google Authenticator, Microsoft Authenticator, Authy).",
                "Agregue una cuenta nueva ingresando la clave secreta que se muestra (tipo: basada en tiempo).",
                "Escriba el código de 6 dígitos que muestra la app para confirmar la activación."));
        if (Config.DEMO) o.put("demoCodigo", Crypto.currentTotp(s.pendingSecret));
        return o;
    }

    public static Object setupConfirm(Http.Ctx c) throws Exception {
        Session s = pre(c, "SETUP");
        if (s.pendingSecret == null) throw ApiException.bad("Primero solicite la clave de configuración");
        if (Crypto.verifyTotp(s.pendingSecret, Json.str(c.json(), "codigo"))) {
            Db.tx(cn -> { Db.exec(cn, "UPDATE usuario SET totp_secret = ?, totp_activo = TRUE WHERE usuario_id = ?", s.pendingSecret, s.userId);
                Audit.log(cn, s.userId, "2FA_ACTIVADO", "usuario", s.userId, "Activación de autenticación en dos pasos", "WEB"); return null; });
            return complete(c, s);
        }
        return failCode(s);
    }

    private static Object failCode(Session s) throws Exception {
        s.fails++;
        if (s.fails >= Config.MAX_2FA_FAILED) {
            SESSIONS.remove(s.id);
            Db.tx(cn -> { Db.exec(cn, "UPDATE usuario SET bloqueado_hasta = now() + (? || ' minutes')::interval WHERE usuario_id = ?", String.valueOf(Config.LOCK_MINUTES), s.userId);
                Audit.log(cn, s.userId, "LOGIN_BLOQUEO", "usuario", s.userId, "Bloqueo por " + Config.MAX_2FA_FAILED + " códigos 2FA incorrectos", "WEB"); return null; });
            throw new ApiException(423, "Superó el número de intentos. La cuenta fue bloqueada temporalmente por " + Config.LOCK_MINUTES + " minutos.");
        }
        throw new ApiException(401, "Código de verificación incorrecto. Intentos restantes: " + (Config.MAX_2FA_FAILED - s.fails)).with("restantes", Config.MAX_2FA_FAILED - s.fails);
    }

    private static Object complete(Http.Ctx c, Session old) throws Exception {
        Map<String, Object> u = Db.tx(cn -> Db.one(cn, "SELECT u.*, co.nombre AS corredora FROM usuario u LEFT JOIN corredora co USING (corredora_id) WHERE usuario_id = ?", old.userId));
        SESSIONS.remove(old.id);
        String csrf = Crypto.token(24);
        Principal p = new Principal(old.userId, (String) u.get("nombre"), (String) u.get("correo"), (String) u.get("rol"),   // el rol queda fijado al iniciar sesión (HU-8.3)
                u.get("corredora_id") == null ? null : Db.i(u.get("corredora_id")), (String) u.get("corredora"), "WEB", csrf);
        Session s = new Session(Crypto.token(32), old.userId, old.correo, "FULL");
        s.principal = p;
        SESSIONS.put(s.id, s);
        c.setCookie("NUAM_SID", s.id, -1);
        String dev = old.devToken;
        boolean nuevo = old.newDevice;
        if (nuevo) { dev = Crypto.token(24); c.setCookie("NUAM_DEV", dev, 365 * 24 * 3600); }
        final String devF = dev;
        Db.tx(cn -> {
            Db.exec(cn, "UPDATE usuario SET ultimo_login = now() WHERE usuario_id = ?", p.userId());
            if (nuevo) { Db.exec(cn, "INSERT INTO dispositivo (usuario_id, huella) VALUES (?,?) ON CONFLICT DO NOTHING", p.userId(), Crypto.sha256(devF));
                Audit.log(cn, p.userId(), "LOGIN_DISPOSITIVO_NUEVO", "usuario", p.userId(), "Inicio de sesión desde un dispositivo no reconocido (2FA verificado)", "WEB"); }
            Audit.log(cn, p.userId(), "LOGIN", "usuario", p.userId(), "Inicio de sesión correcto", "WEB");
            return null; });
        return me(p);
    }

    // ---------------------------------------------------------------- utilidades expuestas como rutas
    public static Map<String, Object> me(Principal p) {
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", p.userId()); u.put("nombre", p.nombre()); u.put("correo", p.correo()); u.put("rol", p.rol());
        u.put("corredoraId", p.corredoraId()); u.put("corredora", p.corredora());
        o.put("usuario", u); o.put("csrf", p.csrf()); o.put("idleSegundos", Config.IDLE_SECONDS);
        return o;
    }

    public static Object logout(Http.Ctx c) throws Exception {
        String sid = c.cookie("NUAM_SID");
        if (sid != null) SESSIONS.remove(sid);
        Auth.Principal p = c.user;
        Db.tx(cn -> { Audit.log(cn, p, "LOGOUT", "usuario", p.userId(), "Cierre de sesión"); return null; });
        c.setCookie("NUAM_SID", "", 0);
        return Map.of("ok", true);
    }

    public static Object idle(Http.Ctx c) {
        Session s = SESSIONS.get(c.cookie("NUAM_SID"));
        long rem = Config.IDLE_SECONDS - (System.currentTimeMillis() - s.last) / 1000;
        return Map.of("restante", Math.max(0, rem));
    }
}
